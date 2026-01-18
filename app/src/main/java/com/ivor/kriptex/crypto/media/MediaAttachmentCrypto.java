package com.ivor.kriptex.crypto.media;

import android.util.Base64;

import com.ivor.kriptex.db.FileShare;
import com.ivor.kriptex.db.Message;
import com.ivor.kriptex.crypto.media.store.DefaultMediaBlobStore;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Application-layer E2EE for legacy media attachments.
 *
 * <p>Ciphertext format on disk is:</p>
 * <pre>
 * [nonce(24) || encrypted_bytes || tag(16)]
 * </pre>
 * using XChaCha20-Poly1305 (implemented as HChaCha20 -> ChaCha20-Poly1305).
 */
public final class MediaAttachmentCrypto {

    public static final String AEAD_XCHACHA20_POLY1305 = "XCHACHA20_POLY1305";

    private static final int NONCE_BYTES = 24;
    private static final int CHACHA20_POLY1305_NONCE_BYTES = 12;

    public static final int CHUNK_SIZE_DEFAULT_BYTES = 64 * 1024;
    private static final int MANIFEST_MAGIC = 0x4B584D46; // 'KXMF'
    private static final byte MANIFEST_VERSION_1 = 1;
    private static final int MAX_TOTAL_CHUNKS = 1_000_000;

    private static final SecureRandom RNG = new SecureRandom();

    private MediaAttachmentCrypto() {
        // no instances
    }

    public static byte[] randomMediaKey32() {
        byte[] key = new byte[32];
        RNG.nextBytes(key);
        return key;
    }

    public static String randomMediaIdUuid() {
        // Matches requirement: UUID string.
        return java.util.UUID.randomUUID().toString();
    }

    /**
     * AAD = mediaId || mimeType || originalFileSizeLE.
     */
    public static byte[] buildAad(String mediaId, String mimeType, long originalSize) {
        byte[] a = safeUtf8(mediaId);
        byte[] b = safeUtf8(mimeType);
        byte[] c = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(originalSize).array();

        byte[] out = new byte[a.length + b.length + c.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        System.arraycopy(c, 0, out, a.length + b.length, c.length);
        return out;
    }

    /**
     * Phase 2 hardening AAD:
     * AADv2 = (mediaId || mimeType || plaintextSizeLE) || plaintextSha256(32).
     */
    public static byte[] buildAadV2(String mediaId, String mimeType, long plaintextSize, byte[] plaintextSha256)
            throws GeneralSecurityException {
        requireSha256(plaintextSha256);
        byte[] base = buildAad(mediaId, mimeType, plaintextSize);
        byte[] out = new byte[base.length + plaintextSha256.length];
        System.arraycopy(base, 0, out, 0, base.length);
        System.arraycopy(plaintextSha256, 0, out, base.length, plaintextSha256.length);
        return out;
    }

    /**
     * Phase 3 chunk AAD:
     * AADchunk = mediaIdUUID(16) || chunkIndexLE || totalChunksLE || plaintextSha256(32).
     */
    public static byte[] buildChunkAadV1(String mediaId, int chunkIndex, int totalChunks, byte[] plaintextSha256)
            throws GeneralSecurityException {
        requireSha256(plaintextSha256);
        if (mediaId == null || mediaId.trim().isEmpty()) throw new IllegalArgumentException("mediaId empty");
        if (chunkIndex < 0) throw new IllegalArgumentException("chunkIndex < 0");
        if (totalChunks <= 0) throw new IllegalArgumentException("totalChunks <= 0");
        if (chunkIndex >= totalChunks) throw new IllegalArgumentException("chunkIndex >= totalChunks");
        ByteBuffer bb = ByteBuffer.allocate(16 + 4 + 4 + 32).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(uuidToBytes16(mediaId));
        bb.putInt(chunkIndex);
        bb.putInt(totalChunks);
        bb.put(plaintextSha256);
        return bb.array();
    }

    /**
     * Phase 3 manifest AAD:
     * AADmanifest = mediaIdUUID(16) || (-1)LE || totalChunksLE || plaintextSizeLE || chunkSizeLE || plaintextSha256(32).
     */
    public static byte[] buildManifestAadV1(String mediaId, int totalChunks, long plaintextSize, int chunkSize, byte[] plaintextSha256)
            throws GeneralSecurityException {
        requireSha256(plaintextSha256);
        if (mediaId == null || mediaId.trim().isEmpty()) throw new IllegalArgumentException("mediaId empty");
        if (totalChunks <= 0) throw new IllegalArgumentException("totalChunks <= 0");
        if (plaintextSize < 0) throw new IllegalArgumentException("plaintextSize < 0");
        if (chunkSize <= 0) throw new IllegalArgumentException("chunkSize <= 0");
        ByteBuffer bb = ByteBuffer.allocate(16 + 4 + 4 + 8 + 4 + 32).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(uuidToBytes16(mediaId));
        bb.putInt(-1);
        bb.putInt(totalChunks);
        bb.putLong(plaintextSize);
        bb.putInt(chunkSize);
        bb.put(plaintextSha256);
        return bb.array();
    }

    /**
     * Chunked media manifest (plaintext).
     */
    public static final class MediaManifestV1 {
        public final String mediaId;
        public final int totalChunks;
        public final long plaintextSize;
        public final byte[] plaintextSha256;
        public final int chunkSize;

        public MediaManifestV1(String mediaId, int totalChunks, long plaintextSize, byte[] plaintextSha256, int chunkSize)
                throws GeneralSecurityException {
            requireSha256(plaintextSha256);
            if (mediaId == null || mediaId.trim().isEmpty()) throw new IllegalArgumentException("mediaId empty");
            if (totalChunks <= 0) throw new IllegalArgumentException("totalChunks <= 0");
            if (totalChunks > MAX_TOTAL_CHUNKS) throw new IllegalArgumentException("totalChunks too large");
            if (plaintextSize < 0) throw new IllegalArgumentException("plaintextSize < 0");
            if (chunkSize <= 0) throw new IllegalArgumentException("chunkSize <= 0");
            this.mediaId = mediaId;
            this.totalChunks = totalChunks;
            this.plaintextSize = plaintextSize;
            this.plaintextSha256 = Arrays.copyOf(plaintextSha256, plaintextSha256.length);
            this.chunkSize = chunkSize;
        }

        public byte[] encode() {
            ByteBuffer bb = ByteBuffer.allocate(4 + 1 + 16 + 4 + 4 + 8 + 32).order(ByteOrder.LITTLE_ENDIAN);
            bb.putInt(MANIFEST_MAGIC);
            bb.put(MANIFEST_VERSION_1);
            bb.put(uuidToBytes16(mediaId));
            bb.putInt(chunkSize);
            bb.putInt(totalChunks);
            bb.putLong(plaintextSize);
            bb.put(plaintextSha256);
            return bb.array();
        }

        public static MediaManifestV1 decode(byte[] bytes) throws GeneralSecurityException {
            if (bytes == null || bytes.length < (4 + 1 + 16 + 4 + 4 + 8 + 32)) {
                throw new GeneralSecurityException("manifest_too_short");
            }
            ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int magic = bb.getInt();
            if (magic != MANIFEST_MAGIC) throw new GeneralSecurityException("manifest_bad_magic");
            byte v = bb.get();
            if (v != MANIFEST_VERSION_1) throw new GeneralSecurityException("manifest_bad_version");
            byte[] uuid16 = new byte[16];
            bb.get(uuid16);
            String mediaId = uuidFromBytes16(uuid16);
            int chunkSize = bb.getInt();
            int totalChunks = bb.getInt();
            long plaintextSize = bb.getLong();
            byte[] sha = new byte[32];
            bb.get(sha);
            return new MediaManifestV1(mediaId, totalChunks, plaintextSize, sha, chunkSize);
        }
    }

    public static final class ChunkedEncryptionResult {
        public final int totalChunks;
        public final int chunkSize;
        public final long totalCiphertextBytes;

        public ChunkedEncryptionResult(int totalChunks, int chunkSize, long totalCiphertextBytes) {
            this.totalChunks = totalChunks;
            this.chunkSize = chunkSize;
            this.totalCiphertextBytes = totalCiphertextBytes;
        }
    }

    /**
     * Encrypts a plaintext file into chunked ciphertext files + encrypted manifest.
     * Writes only ciphertext to disk.
     */
    public static ChunkedEncryptionResult encryptFileToChunkedCiphertexts(
            android.content.Context context,
            File inputPlaintext,
            String mediaId,
            byte[] mediaKey32,
            int chunkSize,
            long plaintextSize,
            byte[] plaintextSha256) throws IOException, GeneralSecurityException {
        requireKey(mediaKey32);
        requireSha256(plaintextSha256);
        if (context == null) throw new IllegalArgumentException("context is null");
        if (inputPlaintext == null || !inputPlaintext.exists()) throw new IllegalArgumentException("inputPlaintext missing");
        if (chunkSize <= 0) throw new IllegalArgumentException("chunkSize <= 0");
        if (plaintextSize < 0) throw new IllegalArgumentException("plaintextSize < 0");

        int totalChunks = (int) ((plaintextSize + (long) chunkSize - 1L) / (long) chunkSize);
        if (totalChunks <= 0) totalChunks = 1;
        if (totalChunks > MAX_TOTAL_CHUNKS) throw new GeneralSecurityException("totalChunks too large");

        DefaultMediaBlobStore store = new DefaultMediaBlobStore();
        File manifestOut = store.chunkedManifestFile(context, mediaId);
        File chunksDir = manifestOut.getParentFile();
        if (chunksDir != null && !chunksDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            chunksDir.mkdirs();
        }

        long totalCiphertext = 0L;

        // Manifest first.
        MediaManifestV1 manifest = new MediaManifestV1(mediaId, totalChunks, plaintextSize, plaintextSha256, chunkSize);
        byte[] manifestPlain = manifest.encode();
        byte[] manifestAad = buildManifestAadV1(mediaId, totalChunks, plaintextSize, chunkSize, plaintextSha256);
        byte[] manifestNonce = deriveDeterministicNonce(mediaKey32, mediaId, -1, "manifest");
        encryptBytesToCiphertextWithNonce(manifestPlain, manifestOut, mediaKey32, manifestNonce, manifestAad);
        totalCiphertext += manifestOut.length();

        // Chunks.
        try (InputStream fis = new BufferedInputStream(new FileInputStream(inputPlaintext))) {
            byte[] buffer = new byte[chunkSize];
            for (int i = 0; i < totalChunks; i++) {
                int expectedPlain = (int) Math.min((long) chunkSize, plaintextSize - ((long) i * (long) chunkSize));
                int offset = 0;
                while (offset < expectedPlain) {
                    int r = fis.read(buffer, offset, expectedPlain - offset);
                    if (r < 0) break;
                    offset += r;
                }
                if (offset != expectedPlain) {
                    throw new IOException("unexpected_eof");
                }
                byte[] chunkPlain = (expectedPlain == buffer.length) ? buffer : Arrays.copyOf(buffer, expectedPlain);
                File chunkOut = store.chunkedChunkFile(context, mediaId, i);
                byte[] chunkAad = buildChunkAadV1(mediaId, i, totalChunks, plaintextSha256);
                byte[] chunkNonce = deriveDeterministicNonce(mediaKey32, mediaId, i, "chunk");
                encryptBytesToCiphertextWithNonce(chunkPlain, chunkOut, mediaKey32, chunkNonce, chunkAad);
                totalCiphertext += chunkOut.length();
            }
        }

        return new ChunkedEncryptionResult(totalChunks, chunkSize, totalCiphertext);
    }

    /**
     * Encrypts plaintext bytes into chunked ciphertext files + encrypted manifest.
     * Writes only ciphertext to disk.
     */
    public static ChunkedEncryptionResult encryptBytesToChunkedCiphertexts(
            android.content.Context context,
            byte[] plaintext,
            String mediaId,
            byte[] mediaKey32,
            int chunkSize,
            byte[] plaintextSha256) throws IOException, GeneralSecurityException {
        requireKey(mediaKey32);
        requireSha256(plaintextSha256);
        if (context == null) throw new IllegalArgumentException("context is null");
        if (plaintext == null) throw new IllegalArgumentException("plaintext is null");
        if (chunkSize <= 0) throw new IllegalArgumentException("chunkSize <= 0");

        long plaintextSize = plaintext.length;
        int totalChunks = (int) ((plaintextSize + (long) chunkSize - 1L) / (long) chunkSize);
        if (totalChunks <= 0) totalChunks = 1;
        if (totalChunks > MAX_TOTAL_CHUNKS) throw new GeneralSecurityException("totalChunks too large");

        DefaultMediaBlobStore store = new DefaultMediaBlobStore();
        File manifestOut = store.chunkedManifestFile(context, mediaId);
        File dir = manifestOut.getParentFile();
        if (dir != null && !dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        long totalCiphertext = 0L;

        MediaManifestV1 manifest = new MediaManifestV1(mediaId, totalChunks, plaintextSize, plaintextSha256, chunkSize);
        byte[] manifestPlain = manifest.encode();
        byte[] manifestAad = buildManifestAadV1(mediaId, totalChunks, plaintextSize, chunkSize, plaintextSha256);
        byte[] manifestNonce = deriveDeterministicNonce(mediaKey32, mediaId, -1, "manifest");
        encryptBytesToCiphertextWithNonce(manifestPlain, manifestOut, mediaKey32, manifestNonce, manifestAad);
        totalCiphertext += manifestOut.length();

        for (int i = 0; i < totalChunks; i++) {
            int start = i * chunkSize;
            int end = (int) Math.min((long) plaintext.length, (long) start + (long) chunkSize);
            byte[] chunkPlain = Arrays.copyOfRange(plaintext, start, end);
            File chunkOut = store.chunkedChunkFile(context, mediaId, i);
            byte[] chunkAad = buildChunkAadV1(mediaId, i, totalChunks, plaintextSha256);
            byte[] chunkNonce = deriveDeterministicNonce(mediaKey32, mediaId, i, "chunk");
            encryptBytesToCiphertextWithNonce(chunkPlain, chunkOut, mediaKey32, chunkNonce, chunkAad);
            totalCiphertext += chunkOut.length();
        }

        return new ChunkedEncryptionResult(totalChunks, chunkSize, totalCiphertext);
    }

    /**
     * Decrypts and validates a downloaded chunked manifest ciphertext.
     */
    public static MediaManifestV1 decryptAndValidateManifest(
            File manifestCiphertext,
            byte[] mediaKey32,
            String expectedMediaId,
            int expectedTotalChunks,
            long expectedPlaintextSize,
            int expectedChunkSize,
            byte[] expectedPlaintextSha256) throws IOException, GeneralSecurityException {
        requireKey(mediaKey32);
        requireSha256(expectedPlaintextSha256);
        if (manifestCiphertext == null || !manifestCiphertext.exists()) throw new IllegalArgumentException("manifestCiphertext missing");
        if (expectedMediaId == null || expectedMediaId.trim().isEmpty()) throw new IllegalArgumentException("expectedMediaId empty");
        if (expectedTotalChunks <= 0) throw new IllegalArgumentException("expectedTotalChunks <= 0");
        if (expectedTotalChunks > MAX_TOTAL_CHUNKS) throw new IllegalArgumentException("expectedTotalChunks too large");
        if (expectedPlaintextSize < 0) throw new IllegalArgumentException("expectedPlaintextSize < 0");
        if (expectedChunkSize <= 0) throw new IllegalArgumentException("expectedChunkSize <= 0");

        byte[] expectedNonce = deriveDeterministicNonce(mediaKey32, expectedMediaId, -1, "manifest");
        byte[] aad = buildManifestAadV1(expectedMediaId, expectedTotalChunks, expectedPlaintextSize, expectedChunkSize, expectedPlaintextSha256);
        byte[] manifestPlain = decryptCiphertextToBytesWithExpectedNonce(manifestCiphertext, mediaKey32, expectedNonce, aad);
        MediaManifestV1 decoded = MediaManifestV1.decode(manifestPlain);

        if (!expectedMediaId.equals(decoded.mediaId)) {
            throw new GeneralSecurityException("manifest_mediaId_mismatch");
        }
        if (decoded.totalChunks != expectedTotalChunks) {
            throw new GeneralSecurityException("manifest_totalChunks_mismatch");
        }
        if (decoded.plaintextSize != expectedPlaintextSize) {
            throw new GeneralSecurityException("manifest_plaintextSize_mismatch");
        }
        if (decoded.chunkSize != expectedChunkSize) {
            throw new GeneralSecurityException("manifest_chunkSize_mismatch");
        }
        if (!Arrays.equals(decoded.plaintextSha256, expectedPlaintextSha256)) {
            throw new GeneralSecurityException("manifest_plaintextHash_mismatch");
        }

        return decoded;
    }

    /**
     * Validates a downloaded chunk ciphertext file by checking nonce prefix and AEAD tag.
     * Does not persist plaintext.
     */
    public static void validateChunkCiphertext(
            File chunkCiphertext,
            byte[] mediaKey32,
            String mediaId,
            int chunkIndex,
            int totalChunks,
            byte[] plaintextSha256,
            int expectedPlaintextLen) throws IOException, GeneralSecurityException {
        requireKey(mediaKey32);
        requireSha256(plaintextSha256);
        if (chunkCiphertext == null || !chunkCiphertext.exists()) throw new IllegalArgumentException("chunkCiphertext missing");
        if (expectedPlaintextLen < 0) throw new IllegalArgumentException("expectedPlaintextLen < 0");

        long expectedCipherLen = (long) NONCE_BYTES + (long) expectedPlaintextLen + 16L;
        if (chunkCiphertext.length() != expectedCipherLen) {
            throw new GeneralSecurityException("chunk_size_mismatch");
        }

        byte[] expectedNonce = deriveDeterministicNonce(mediaKey32, mediaId, chunkIndex, "chunk");
        byte[] aad = buildChunkAadV1(mediaId, chunkIndex, totalChunks, plaintextSha256);
        byte[] plain = decryptCiphertextToBytesWithExpectedNonce(chunkCiphertext, mediaKey32, expectedNonce, aad);
        if (plain.length != expectedPlaintextLen) {
            throw new GeneralSecurityException("chunk_plaintext_len_mismatch");
        }
    }

    /**
     * Decrypts a chunk ciphertext file and writes plaintext into the provided OutputStream.
     */
    public static void decryptChunkToStream(
            File chunkCiphertext,
            java.io.OutputStream out,
            byte[] mediaKey32,
            String mediaId,
            int chunkIndex,
            int totalChunks,
            byte[] plaintextSha256) throws IOException, GeneralSecurityException {
        requireKey(mediaKey32);
        requireSha256(plaintextSha256);
        if (chunkCiphertext == null || !chunkCiphertext.exists()) throw new IllegalArgumentException("chunkCiphertext missing");
        if (out == null) throw new IllegalArgumentException("out is null");

        byte[] expectedNonce = deriveDeterministicNonce(mediaKey32, mediaId, chunkIndex, "chunk");
        byte[] aad = buildChunkAadV1(mediaId, chunkIndex, totalChunks, plaintextSha256);
        decryptCiphertextToStreamWithExpectedNonce(chunkCiphertext, out, mediaKey32, expectedNonce, aad);
    }

    public static byte[] sha256Bytes(byte[] data) throws GeneralSecurityException {
        if (data == null) throw new IllegalArgumentException("data is null");
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        return d.digest(data);
    }

    public static byte[] sha256File(File file) throws IOException, GeneralSecurityException {
        if (file == null || !file.exists()) throw new IllegalArgumentException("file missing");
        try (InputStream is = new BufferedInputStream(new FileInputStream(file))) {
            return sha256Stream(is);
        }
    }

    public static byte[] sha256Stream(InputStream is) throws IOException, GeneralSecurityException {
        if (is == null) throw new IllegalArgumentException("is is null");
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        int r;
        while ((r = is.read(buffer)) >= 0) {
            if (r > 0) d.update(buffer, 0, r);
        }
        return d.digest();
    }

    /**
     * Encrypts plaintext bytes to ciphertext on disk. Only ciphertext is written.
     */
    public static void encryptBytesToCiphertext(byte[] plaintext, File outputCiphertext, byte[] mediaKey32, byte[] aad)
            throws IOException, GeneralSecurityException {
        if (plaintext == null) throw new IllegalArgumentException("plaintext is null");
        try (InputStream is = new ByteArrayInputStream(plaintext)) {
            encryptStreamToCiphertext(is, outputCiphertext, mediaKey32, aad);
        }
    }

    /**
     * Encrypts plaintext stream to ciphertext on disk. Only ciphertext is written.
     */
    public static void encryptStreamToCiphertext(InputStream inputPlaintext, File outputCiphertext, byte[] mediaKey32, byte[] aad)
            throws IOException, GeneralSecurityException {
        requireKey(mediaKey32);
        if (inputPlaintext == null) {
            throw new IllegalArgumentException("inputPlaintext is null");
        }
        if (outputCiphertext == null) {
            throw new IllegalArgumentException("outputCiphertext is null");
        }

        ensureAeadProvider();

        File parent = outputCiphertext.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }

        byte[] nonce = new byte[NONCE_BYTES];
        RNG.nextBytes(nonce);

        byte[] subkey = xchachaSubkey(mediaKey32, nonce);
        byte[] nonce12 = xchachaNonce12(nonce);

        Cipher cipher = chacha20Poly1305Cipher();
        SecretKeySpec keySpec = new SecretKeySpec(subkey, "ChaCha20");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, new IvParameterSpec(nonce12));
        if (aad != null && aad.length > 0) {
            cipher.updateAAD(aad);
        }

        try (FileOutputStream fos = new FileOutputStream(outputCiphertext);
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             CipherOutputStream cos = new CipherOutputStream(bos, cipher)) {

            bos.write(nonce);
            bos.flush();

            byte[] buffer = new byte[64 * 1024];
            int r;
            while ((r = inputPlaintext.read(buffer)) >= 0) {
                if (r > 0) cos.write(buffer, 0, r);
            }
        }
    }

    /**
     * Encrypts a file to ciphertext on disk. Only ciphertext is written.
     */
    public static void encryptFileToCiphertext(File inputPlaintext, File outputCiphertext, byte[] mediaKey32, byte[] aad)
            throws IOException, GeneralSecurityException {
        requireKey(mediaKey32);
        if (inputPlaintext == null || !inputPlaintext.exists()) {
            throw new IllegalArgumentException("inputPlaintext missing");
        }
        if (outputCiphertext == null) {
            throw new IllegalArgumentException("outputCiphertext is null");
        }

        ensureAeadProvider();

        File parent = outputCiphertext.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }

        byte[] nonce = new byte[NONCE_BYTES];
        RNG.nextBytes(nonce);

        byte[] subkey = xchachaSubkey(mediaKey32, nonce);
        byte[] nonce12 = xchachaNonce12(nonce);

        Cipher cipher = chacha20Poly1305Cipher();
        SecretKeySpec keySpec = new SecretKeySpec(subkey, "ChaCha20");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, new IvParameterSpec(nonce12));
        if (aad != null && aad.length > 0) {
            cipher.updateAAD(aad);
        }

        try (FileOutputStream fos = new FileOutputStream(outputCiphertext);
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             FileInputStream fis = new FileInputStream(inputPlaintext);
             BufferedInputStream bis = new BufferedInputStream(fis);
             CipherOutputStream cos = new CipherOutputStream(bos, cipher)) {

            // Prepend nonce.
            bos.write(nonce);
            bos.flush();

            byte[] buffer = new byte[64 * 1024];
            int r;
            while ((r = bis.read(buffer)) >= 0) {
                cos.write(buffer, 0, r);
            }
        }
    }

    /**
     * Decrypts ciphertext file to plaintext output file. Auth failures throw.
     */
    public static void decryptCiphertextToFile(File inputCiphertext, File outputPlaintext, byte[] mediaKey32, byte[] aad)
            throws IOException, GeneralSecurityException {
        requireKey(mediaKey32);
        if (inputCiphertext == null || !inputCiphertext.exists()) {
            throw new IllegalArgumentException("inputCiphertext missing");
        }
        if (outputPlaintext == null) {
            throw new IllegalArgumentException("outputPlaintext is null");
        }

        ensureAeadProvider();

        File parent = outputPlaintext.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }

        File tmpOut = new File(outputPlaintext.getAbsolutePath() + ".tmp");
        //noinspection ResultOfMethodCallIgnored
        tmpOut.delete();

        boolean ok = false;
        try (FileInputStream fis = new FileInputStream(inputCiphertext);
             BufferedInputStream bis = new BufferedInputStream(fis)) {

            byte[] nonce = new byte[NONCE_BYTES];
            int read = bis.read(nonce);
            if (read != NONCE_BYTES) {
                throw new IOException("ciphertext too short (nonce)");
            }

            byte[] subkey = xchachaSubkey(mediaKey32, nonce);
            byte[] nonce12 = xchachaNonce12(nonce);

            Cipher cipher = chacha20Poly1305Cipher();
            SecretKeySpec keySpec = new SecretKeySpec(subkey, "ChaCha20");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new IvParameterSpec(nonce12));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }

            try (CipherInputStream cis = new CipherInputStream(bis, cipher);
                 FileOutputStream fos = new FileOutputStream(tmpOut);
                 BufferedOutputStream bos = new BufferedOutputStream(fos)) {

                byte[] buffer = new byte[64 * 1024];
                int r;
                while ((r = cis.read(buffer)) >= 0) {
                    bos.write(buffer, 0, r);
                }
            }

            // At this point doFinal/auth has occurred; rename into place.
            if (outputPlaintext.exists()) {
                //noinspection ResultOfMethodCallIgnored
                outputPlaintext.delete();
            }
            ok = tmpOut.renameTo(outputPlaintext);
            if (!ok) {
                throw new IOException("failed to move decrypted file into place");
            }
        } finally {
            if (!ok) {
                //noinspection ResultOfMethodCallIgnored
                tmpOut.delete();
                //noinspection ResultOfMethodCallIgnored
                outputPlaintext.delete();
            }
        }
    }

    /**
     * Wrap a 32-byte media key under the legacy per-message symmetric key.
     *
     * <p>Encryption: XChaCha20-Poly1305 with random 24-byte nonce prepended.</p>
     * <p>Key material: SHA-256("KriptexMediaKeyWrap:" || messageKeyUtf8).</p>
     * <p>AAD: mediaId || sender || receiver || messageId.</p>
     */
    public static byte[] wrapMediaKeyForTransport(byte[] mediaKey32, String messageKey, Message message, FileShare fs)
            throws GeneralSecurityException {
        requireKey(mediaKey32);
        if (messageKey == null || messageKey.trim().isEmpty()) {
            throw new IllegalArgumentException("messageKey empty");
        }
        if (message == null) {
            throw new IllegalArgumentException("message is null");
        }
        if (fs == null || fs.getMediaId() == null || fs.getMediaId().trim().isEmpty()) {
            throw new IllegalArgumentException("mediaId missing");
        }

        ensureAeadProvider();

        byte[] wrapKey = sha256(("KriptexMediaKeyWrap:" + messageKey).getBytes(StandardCharsets.UTF_8));

        byte[] nonce = new byte[NONCE_BYTES];
        RNG.nextBytes(nonce);

        byte[] subkey = xchachaSubkey(wrapKey, nonce);
        byte[] nonce12 = xchachaNonce12(nonce);

        Cipher cipher = chacha20Poly1305Cipher();
        SecretKeySpec keySpec = new SecretKeySpec(subkey, "ChaCha20");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, new IvParameterSpec(nonce12));

        byte[] aad = buildKeyWrapAad(message, fs.getMediaId());
        cipher.updateAAD(aad);

        byte[] encryptedPlusTag = cipher.doFinal(mediaKey32);

        byte[] out = new byte[nonce.length + encryptedPlusTag.length];
        System.arraycopy(nonce, 0, out, 0, nonce.length);
        System.arraycopy(encryptedPlusTag, 0, out, nonce.length, encryptedPlusTag.length);
        return out;
    }

    public static byte[] unwrapMediaKeyFromTransport(byte[] wrapped, String messageKey, Message message, FileShare fs)
            throws GeneralSecurityException {
        if (wrapped == null || wrapped.length < (NONCE_BYTES + 16)) {
            throw new IllegalArgumentException("wrapped key too short");
        }
        if (messageKey == null || messageKey.trim().isEmpty()) {
            throw new IllegalArgumentException("messageKey empty");
        }
        if (message == null) {
            throw new IllegalArgumentException("message is null");
        }
        if (fs == null || fs.getMediaId() == null || fs.getMediaId().trim().isEmpty()) {
            throw new IllegalArgumentException("mediaId missing");
        }

        ensureAeadProvider();

        byte[] wrapKey = sha256(("KriptexMediaKeyWrap:" + messageKey).getBytes(StandardCharsets.UTF_8));

        byte[] nonce = new byte[NONCE_BYTES];
        System.arraycopy(wrapped, 0, nonce, 0, nonce.length);
        int encLen = wrapped.length - nonce.length;
        byte[] encryptedPlusTag = new byte[encLen];
        System.arraycopy(wrapped, nonce.length, encryptedPlusTag, 0, encLen);

        byte[] subkey = xchachaSubkey(wrapKey, nonce);
        byte[] nonce12 = xchachaNonce12(nonce);

        Cipher cipher = chacha20Poly1305Cipher();
        SecretKeySpec keySpec = new SecretKeySpec(subkey, "ChaCha20");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, new IvParameterSpec(nonce12));

        byte[] aad = buildKeyWrapAad(message, fs.getMediaId());
        cipher.updateAAD(aad);

        byte[] mediaKey = cipher.doFinal(encryptedPlusTag);
        requireKey(mediaKey);
        return mediaKey;
    }

    /**
     * Local wrap for persistence: RSA encrypt base64(mediaKey) with the device's Tor public key.
     * Output is UTF-8 bytes for the RSA ciphertext string.
     */
    public static byte[] wrapMediaKeyForDevice(byte[] mediaKey32, com.ivor.kriptex.tor.Tor tor) throws GeneralSecurityException {
        requireKey(mediaKey32);
        if (tor == null) throw new IllegalArgumentException("tor is null");
        String b64 = Base64.encodeToString(mediaKey32, Base64.NO_WRAP);
        try {
            String rsa = tor.encryptByPublicKey(b64);
            return rsa.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new GeneralSecurityException("device_wrap_failed", e);
        }
    }

    public static byte[] unwrapMediaKeyFromDevice(byte[] wrapped, com.ivor.kriptex.tor.Tor tor)
            throws GeneralSecurityException {
        if (wrapped == null || wrapped.length == 0) {
            throw new IllegalArgumentException("wrapped empty");
        }
        if (tor == null) throw new IllegalArgumentException("tor is null");
        String rsaCiphertext = new String(wrapped, StandardCharsets.UTF_8);
        try {
            String b64 = tor.decryptByPrivateKey(rsaCiphertext);
            byte[] mediaKey = Base64.decode(b64, Base64.DEFAULT);
            requireKey(mediaKey);
            return mediaKey;
        } catch (Exception e) {
            throw new GeneralSecurityException("device_unwrap_failed", e);
        }
    }

    public static File ciphertextFileForServing(android.content.Context context, String mediaId) {
        return new DefaultMediaBlobStore().ciphertextBlobFile(context, mediaId);
    }

    public static File chunkedManifestFileForServing(android.content.Context context, String mediaId) {
        return new DefaultMediaBlobStore().chunkedManifestFile(context, mediaId);
    }

    public static File chunkedChunkFileForServing(android.content.Context context, String mediaId, int chunkIndex) {
        return new DefaultMediaBlobStore().chunkedChunkFile(context, mediaId, chunkIndex);
    }

    public static File chunkedManifestTempDownloadFile(android.content.Context context, String mediaId) {
        return new DefaultMediaBlobStore().chunkedManifestTempFile(context, mediaId);
    }

    public static File chunkedChunkTempDownloadFile(android.content.Context context, String mediaId, int chunkIndex) {
        return new DefaultMediaBlobStore().chunkedChunkTempFile(context, mediaId, chunkIndex);
    }

    private static byte[] deriveDeterministicNonce(byte[] mediaKey32, String mediaId, int chunkIndex, String purpose)
            throws GeneralSecurityException {
        requireKey(mediaKey32);
        if (mediaId == null || mediaId.trim().isEmpty()) throw new IllegalArgumentException("mediaId empty");
        if (purpose == null || purpose.trim().isEmpty()) throw new IllegalArgumentException("purpose empty");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(mediaKey32, "HmacSHA256"));
        mac.update(("KriptexChunkNonce:" + purpose + ":").getBytes(StandardCharsets.UTF_8));
        mac.update(uuidToBytes16(mediaId));
        mac.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(chunkIndex).array());
        byte[] full = mac.doFinal();
        return Arrays.copyOf(full, NONCE_BYTES);
    }

    private static void encryptBytesToCiphertextWithNonce(byte[] plaintext, File outputCiphertext, byte[] mediaKey32, byte[] nonce24, byte[] aad)
            throws IOException, GeneralSecurityException {
        if (plaintext == null) throw new IllegalArgumentException("plaintext is null");
        try (InputStream is = new ByteArrayInputStream(plaintext)) {
            encryptStreamToCiphertextWithNonce(is, outputCiphertext, mediaKey32, nonce24, aad);
        }
    }

    private static void encryptStreamToCiphertextWithNonce(InputStream inputPlaintext, File outputCiphertext, byte[] mediaKey32, byte[] nonce24, byte[] aad)
            throws IOException, GeneralSecurityException {
        requireKey(mediaKey32);
        if (nonce24 == null || nonce24.length != NONCE_BYTES) throw new IllegalArgumentException("nonce must be 24 bytes");
        if (inputPlaintext == null) throw new IllegalArgumentException("inputPlaintext is null");
        if (outputCiphertext == null) throw new IllegalArgumentException("outputCiphertext is null");

        ensureAeadProvider();

        File parent = outputCiphertext.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }

        byte[] subkey = xchachaSubkey(mediaKey32, nonce24);
        byte[] nonce12 = xchachaNonce12(nonce24);

        Cipher cipher = chacha20Poly1305Cipher();
        SecretKeySpec keySpec = new SecretKeySpec(subkey, "ChaCha20");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, new IvParameterSpec(nonce12));
        if (aad != null && aad.length > 0) {
            cipher.updateAAD(aad);
        }

        try (FileOutputStream fos = new FileOutputStream(outputCiphertext);
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             CipherOutputStream cos = new CipherOutputStream(bos, cipher)) {

            bos.write(nonce24);
            bos.flush();

            byte[] buffer = new byte[64 * 1024];
            int r;
            while ((r = inputPlaintext.read(buffer)) >= 0) {
                if (r > 0) cos.write(buffer, 0, r);
            }
        }
    }

    private static byte[] decryptCiphertextToBytesWithExpectedNonce(File inputCiphertext, byte[] mediaKey32, byte[] expectedNonce24, byte[] aad)
            throws IOException, GeneralSecurityException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        decryptCiphertextToStreamWithExpectedNonce(inputCiphertext, baos, mediaKey32, expectedNonce24, aad);
        return baos.toByteArray();
    }

    private static void decryptCiphertextToStreamWithExpectedNonce(File inputCiphertext, java.io.OutputStream out, byte[] mediaKey32, byte[] expectedNonce24, byte[] aad)
            throws IOException, GeneralSecurityException {
        requireKey(mediaKey32);
        if (expectedNonce24 == null || expectedNonce24.length != NONCE_BYTES) {
            throw new IllegalArgumentException("expectedNonce must be 24 bytes");
        }
        if (inputCiphertext == null || !inputCiphertext.exists()) throw new IllegalArgumentException("inputCiphertext missing");
        if (out == null) throw new IllegalArgumentException("out is null");

        ensureAeadProvider();

        try (FileInputStream fis = new FileInputStream(inputCiphertext);
             BufferedInputStream bis = new BufferedInputStream(fis)) {

            byte[] nonce = new byte[NONCE_BYTES];
            int read = bis.read(nonce);
            if (read != NONCE_BYTES) {
                throw new IOException("ciphertext too short (nonce)");
            }
            if (!Arrays.equals(nonce, expectedNonce24)) {
                throw new GeneralSecurityException("nonce_mismatch");
            }

            byte[] subkey = xchachaSubkey(mediaKey32, nonce);
            byte[] nonce12 = xchachaNonce12(nonce);

            Cipher cipher = chacha20Poly1305Cipher();
            SecretKeySpec keySpec = new SecretKeySpec(subkey, "ChaCha20");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new IvParameterSpec(nonce12));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }

            try (CipherInputStream cis = new CipherInputStream(bis, cipher)) {
                byte[] buffer = new byte[64 * 1024];
                int r;
                while ((r = cis.read(buffer)) >= 0) {
                    if (r > 0) out.write(buffer, 0, r);
                }
                out.flush();
            }
        }
    }

    private static byte[] uuidToBytes16(String mediaId) {
        java.util.UUID uuid = java.util.UUID.fromString(mediaId);
        ByteBuffer bb = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return bb.array();
    }

    private static String uuidFromBytes16(byte[] uuid16) {
        if (uuid16 == null || uuid16.length != 16) throw new IllegalArgumentException("uuid16 must be 16 bytes");
        ByteBuffer bb = ByteBuffer.wrap(uuid16).order(ByteOrder.BIG_ENDIAN);
        long msb = bb.getLong();
        long lsb = bb.getLong();
        return new java.util.UUID(msb, lsb).toString();
    }

    private static byte[] buildKeyWrapAad(Message message, String mediaId) {
        String mid = message.getPrimaryKey() == null ? "" : message.getPrimaryKey();
        String sender = message.getSender() == null ? "" : message.getSender();
        String receiver = message.getReceiver() == null ? "" : message.getReceiver();
        String s = mediaId + "|" + sender + "|" + receiver + "|" + mid;
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void ensureAeadProvider() {
        if (Security.getProvider("SC") == null) {
            try {
                Class<?> clazz = Class.forName("org.spongycastle.jce.provider.BouncyCastleProvider");
                java.security.Provider provider = (java.security.Provider) clazz.getDeclaredConstructor().newInstance();
                Security.addProvider(provider);
            } catch (Throwable ignored) {
                // ignore
            }
        }

        if (Security.getProvider("BC") == null) {
            try {
                Class<?> clazz = Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider");
                java.security.Provider provider = (java.security.Provider) clazz.getDeclaredConstructor().newInstance();
                Security.addProvider(provider);
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    private static Cipher chacha20Poly1305Cipher() throws GeneralSecurityException {
        ensureAeadProvider();

        List<String> names = Arrays.asList(
                "ChaCha20-Poly1305",
                "CHACHA20-POLY1305",
                "ChaCha20/Poly1305/NoPadding",
                "CHACHA20/POLY1305/NOPADDING"
        );
        List<String> providers = Arrays.asList("SC", "BC", null);

        ArrayList<Throwable> errors = new ArrayList<>();
        for (String provider : providers) {
            for (String name : names) {
                try {
                    if (provider == null) {
                        return Cipher.getInstance(name);
                    }
                    return Cipher.getInstance(name, provider);
                } catch (Throwable t) {
                    errors.add(t);
                }
            }
        }
        GeneralSecurityException gse = new GeneralSecurityException("chacha20_poly1305_unavailable");
        for (Throwable t : errors) {
            gse.addSuppressed(t);
        }
        throw gse;
    }

    private static byte[] xchachaNonce12(byte[] nonce24) {
        if (nonce24 == null || nonce24.length != NONCE_BYTES) {
            throw new IllegalArgumentException("nonce must be 24 bytes");
        }
        byte[] nonce12 = new byte[CHACHA20_POLY1305_NONCE_BYTES];
        // 4 zero bytes then the last 8 bytes of the 24-byte nonce.
        System.arraycopy(nonce24, 16, nonce12, 4, 8);
        return nonce12;
    }

    private static byte[] xchachaSubkey(byte[] key32, byte[] nonce24) throws GeneralSecurityException {
        requireKey(key32);
        if (nonce24 == null || nonce24.length != NONCE_BYTES) {
            throw new IllegalArgumentException("nonce must be 24 bytes");
        }

        byte[] nonce16 = new byte[16];
        System.arraycopy(nonce24, 0, nonce16, 0, nonce16.length);
        return hChaCha20(key32, nonce16);
    }

    /**
     * HChaCha20 as specified by libsodium/XChaCha: 32-byte output subkey.
     */
    private static byte[] hChaCha20(byte[] key32, byte[] nonce16) throws GeneralSecurityException {
        requireKey(key32);
        if (nonce16 == null || nonce16.length != 16) {
            throw new IllegalArgumentException("nonce16 must be 16 bytes");
        }

        int[] state = new int[16];

        // "expand 32-byte k"
        state[0] = 0x61707865;
        state[1] = 0x3320646e;
        state[2] = 0x79622d32;
        state[3] = 0x6b206574;

        // key (8 words)
        for (int i = 0; i < 8; i++) {
            state[4 + i] = leToInt(key32, i * 4);
        }

        // nonce16 (4 words)
        state[12] = leToInt(nonce16, 0);
        state[13] = leToInt(nonce16, 4);
        state[14] = leToInt(nonce16, 8);
        state[15] = leToInt(nonce16, 12);

        // 20 rounds (10 double rounds)
        for (int i = 0; i < 10; i++) {
            // Column rounds
            quarterRound(state, 0, 4, 8, 12);
            quarterRound(state, 1, 5, 9, 13);
            quarterRound(state, 2, 6, 10, 14);
            quarterRound(state, 3, 7, 11, 15);

            // Diagonal rounds
            quarterRound(state, 0, 5, 10, 15);
            quarterRound(state, 1, 6, 11, 12);
            quarterRound(state, 2, 7, 8, 13);
            quarterRound(state, 3, 4, 9, 14);
        }

        byte[] out = new byte[32];
        // output: state[0..3] || state[12..15]
        intToLe(state[0], out, 0);
        intToLe(state[1], out, 4);
        intToLe(state[2], out, 8);
        intToLe(state[3], out, 12);
        intToLe(state[12], out, 16);
        intToLe(state[13], out, 20);
        intToLe(state[14], out, 24);
        intToLe(state[15], out, 28);
        return out;
    }

    private static void quarterRound(int[] x, int a, int b, int c, int d) {
        x[a] += x[b];
        x[d] = rotl(x[d] ^ x[a], 16);
        x[c] += x[d];
        x[b] = rotl(x[b] ^ x[c], 12);
        x[a] += x[b];
        x[d] = rotl(x[d] ^ x[a], 8);
        x[c] += x[d];
        x[b] = rotl(x[b] ^ x[c], 7);
    }

    private static int rotl(int v, int c) {
        return (v << c) | (v >>> (32 - c));
    }

    private static int leToInt(byte[] in, int off) {
        return (in[off] & 0xff)
                | ((in[off + 1] & 0xff) << 8)
                | ((in[off + 2] & 0xff) << 16)
                | ((in[off + 3] & 0xff) << 24);
    }

    private static void intToLe(int v, byte[] out, int off) {
        out[off] = (byte) (v);
        out[off + 1] = (byte) (v >>> 8);
        out[off + 2] = (byte) (v >>> 16);
        out[off + 3] = (byte) (v >>> 24);
    }

    private static byte[] sha256(byte[] input) throws GeneralSecurityException {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        return d.digest(input);
    }

    private static void requireKey(byte[] key32) {
        if (key32 == null || key32.length != 32) {
            throw new IllegalArgumentException("key must be 32 bytes");
        }
    }

    private static void requireSha256(byte[] sha256) throws GeneralSecurityException {
        if (sha256 == null || sha256.length != 32) {
            throw new GeneralSecurityException("sha256_required");
        }
    }

    private static byte[] safeUtf8(String s) {
        if (s == null) return new byte[0];
        return s.getBytes(StandardCharsets.UTF_8);
    }
}

package com.ivor.kriptex.crypto.media;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MediaAttachmentCryptoTest {

    @Test
    public void encryptDecrypt_roundTrip_and_ciphertextDoesNotContainPlaintextMarker() throws Exception {
        File dir = Files.createTempDirectory("kriptex_media").toFile();
        File plain = new File(dir, "plain.bin");
        File cipher = new File(dir, "cipher.bin");
        File out = new File(dir, "out.bin");

        byte[] marker = new byte[64];
        for (int i = 0; i < marker.length; i++) {
            marker[i] = (byte) (i ^ 0x5A);
        }

        byte[] payload = new byte[4096];
        Arrays.fill(payload, (byte) 0x11);
        System.arraycopy(marker, 0, payload, 200, marker.length);

        Files.write(plain.toPath(), payload);

        byte[] mediaKey = new byte[32];
        for (int i = 0; i < mediaKey.length; i++) {
            mediaKey[i] = (byte) i;
        }

        String mediaId = "00000000-0000-0000-0000-000000000000";
        String mime = "application/octet-stream";
        byte[] aad = MediaAttachmentCrypto.buildAad(mediaId, mime, payload.length);

        MediaAttachmentCrypto.encryptFileToCiphertext(plain, cipher, mediaKey, aad);
        assertTrue(cipher.exists());

        byte[] cipherBytes = Files.readAllBytes(cipher.toPath());
        assertFalse("ciphertext should not contain plaintext marker", containsSubsequence(cipherBytes, marker));

        MediaAttachmentCrypto.decryptCiphertextToFile(cipher, out, mediaKey, aad);
        assertTrue(out.exists());
        byte[] roundTrip = Files.readAllBytes(out.toPath());
        assertArrayEquals(payload, roundTrip);
    }

    @Test
    public void decrypt_fails_with_wrong_key_and_does_not_leave_plaintext() throws Exception {
        File dir = Files.createTempDirectory("kriptex_media").toFile();
        File plain = new File(dir, "plain.bin");
        File cipher = new File(dir, "cipher.bin");
        File out = new File(dir, "out.bin");

        byte[] payload = new byte[2048];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31);
        }
        Files.write(plain.toPath(), payload);

        byte[] key1 = new byte[32];
        byte[] key2 = new byte[32];
        Arrays.fill(key1, (byte) 0x01);
        Arrays.fill(key2, (byte) 0x02);

        String mediaId = "11111111-1111-1111-1111-111111111111";
        String mime = "application/octet-stream";
        byte[] aad = MediaAttachmentCrypto.buildAad(mediaId, mime, payload.length);

        MediaAttachmentCrypto.encryptFileToCiphertext(plain, cipher, key1, aad);

        boolean failed = false;
        try {
            MediaAttachmentCrypto.decryptCiphertextToFile(cipher, out, key2, aad);
        } catch (GeneralSecurityException | IllegalArgumentException | java.io.IOException expected) {
            failed = true;
        }

        assertTrue("decryption must fail with wrong key", failed);
        assertFalse("plaintext output must not exist after auth failure", out.exists());
    }

    @Test
    public void encryptDecrypt_with_aadV2_binds_plaintext_hash() throws Exception {
        File dir = Files.createTempDirectory("kriptex_media").toFile();
        File plain = new File(dir, "plain.bin");
        File cipher = new File(dir, "cipher.bin");
        File out = new File(dir, "out.bin");

        byte[] payload = new byte[1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (0xA5 ^ i);
        }
        Files.write(plain.toPath(), payload);

        byte[] mediaKey = new byte[32];
        Arrays.fill(mediaKey, (byte) 0x33);

        byte[] sha = MediaAttachmentCrypto.sha256Bytes(payload);
        byte[] aad = MediaAttachmentCrypto.buildAadV2(
                "22222222-2222-2222-2222-222222222222",
                "application/octet-stream",
                payload.length,
                sha);

        MediaAttachmentCrypto.encryptFileToCiphertext(plain, cipher, mediaKey, aad);
        MediaAttachmentCrypto.decryptCiphertextToFile(cipher, out, mediaKey, aad);
        assertArrayEquals(payload, Files.readAllBytes(out.toPath()));
    }

    @Test
    public void decrypt_fails_when_ciphertext_tag_tampered() throws Exception {
        File dir = Files.createTempDirectory("kriptex_media").toFile();
        File plain = new File(dir, "plain.bin");
        File cipher = new File(dir, "cipher.bin");
        File out = new File(dir, "out.bin");

        byte[] payload = new byte[1536];
        Arrays.fill(payload, (byte) 0x7F);
        Files.write(plain.toPath(), payload);

        byte[] mediaKey = new byte[32];
        Arrays.fill(mediaKey, (byte) 0x55);

        byte[] sha = MediaAttachmentCrypto.sha256Bytes(payload);
        byte[] aad = MediaAttachmentCrypto.buildAadV2(
                "33333333-3333-3333-3333-333333333333",
                "application/octet-stream",
                payload.length,
                sha);

        MediaAttachmentCrypto.encryptFileToCiphertext(plain, cipher, mediaKey, aad);
        byte[] c = Files.readAllBytes(cipher.toPath());
        // Flip a bit near the end (auth tag area).
        c[c.length - 1] ^= 0x01;
        Files.write(cipher.toPath(), c);

        boolean failed = false;
        try {
            MediaAttachmentCrypto.decryptCiphertextToFile(cipher, out, mediaKey, aad);
        } catch (Exception expected) {
            failed = true;
        }
        assertTrue(failed);
        assertFalse(out.exists());
    }

    @Test
    public void decrypt_fails_when_nonce_tampered() throws Exception {
        File dir = Files.createTempDirectory("kriptex_media").toFile();
        File plain = new File(dir, "plain.bin");
        File cipher = new File(dir, "cipher.bin");
        File out = new File(dir, "out.bin");

        byte[] payload = new byte[2048];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;
        Files.write(plain.toPath(), payload);

        byte[] mediaKey = new byte[32];
        Arrays.fill(mediaKey, (byte) 0x12);

        byte[] sha = MediaAttachmentCrypto.sha256Bytes(payload);
        byte[] aad = MediaAttachmentCrypto.buildAadV2(
                "44444444-4444-4444-4444-444444444444",
                "application/octet-stream",
                payload.length,
                sha);

        MediaAttachmentCrypto.encryptFileToCiphertext(plain, cipher, mediaKey, aad);
        byte[] c = Files.readAllBytes(cipher.toPath());
        c[0] ^= 0x40; // flip nonce byte
        Files.write(cipher.toPath(), c);

        boolean failed = false;
        try {
            MediaAttachmentCrypto.decryptCiphertextToFile(cipher, out, mediaKey, aad);
        } catch (Exception expected) {
            failed = true;
        }
        assertTrue(failed);
        assertFalse(out.exists());
    }

    @Test
    public void decrypt_fails_when_aad_swapped_between_messages() throws Exception {
        File dir = Files.createTempDirectory("kriptex_media").toFile();
        File plain = new File(dir, "plain.bin");
        File cipher = new File(dir, "cipher.bin");
        File out = new File(dir, "out.bin");

        byte[] payload = new byte[1024];
        Arrays.fill(payload, (byte) 0x22);
        Files.write(plain.toPath(), payload);

        byte[] mediaKey = new byte[32];
        Arrays.fill(mediaKey, (byte) 0x2A);

        byte[] sha = MediaAttachmentCrypto.sha256Bytes(payload);
        byte[] aad1 = MediaAttachmentCrypto.buildAadV2(
                "55555555-5555-5555-5555-555555555555",
                "application/octet-stream",
                payload.length,
                sha);
        byte[] aad2 = MediaAttachmentCrypto.buildAadV2(
                "66666666-6666-6666-6666-666666666666",
                "application/octet-stream",
                payload.length,
                sha);

        MediaAttachmentCrypto.encryptFileToCiphertext(plain, cipher, mediaKey, aad1);

        boolean failed = false;
        try {
            MediaAttachmentCrypto.decryptCiphertextToFile(cipher, out, mediaKey, aad2);
        } catch (Exception expected) {
            failed = true;
        }
        assertTrue(failed);
        assertFalse(out.exists());
    }

    private static boolean containsSubsequence(byte[] haystack, byte[] needle) {
        if (needle.length == 0) return true;
        if (haystack.length < needle.length) return false;

        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}

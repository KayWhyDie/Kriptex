package com.ivor.kriptex.crypto;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minimal AEAD wrapper for already-padded envelopes.
 *
 * <p>This class treats inputs as opaque bytes and does not parse the envelope.</p>
 *
 * <p>Ciphertext format:</p>
 * <pre>
 * [nonce || encrypted_bytes || auth_tag]
 * </pre>
 *
 * <p>Encryption input must be exactly 512/1024/2048 bytes.</p>
 */
final class CryptoBox {

    private static final int TAG_LENGTH_BYTES = 16; // 128-bit tag

    private static final int NONCE_XCHACHA20_POLY1305_BYTES = 24;
    private static final int NONCE_AES_GCM_BYTES = 12;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private CryptoBox() {
        // no instances
    }

    /**
     * Encrypts an already padded envelope as an opaque blob.
     *
     * <p>Prefers XChaCha20-Poly1305 when available; otherwise uses AES-256-GCM.</p>
     */
    static byte[] encrypt(byte[] paddedEnvelope, SecretKey key) throws GeneralSecurityException {
        if (paddedEnvelope == null) {
            throw new IllegalArgumentException("paddedEnvelope is null");
        }
        if (!StaticLengthPadding.isValidBucketSize(paddedEnvelope.length)) {
            throw new IllegalArgumentException("encryption input must be exactly 512/1024/2048 bytes");
        }

        byte[] keyBytes = require256BitKey(key);

        byte[] xchacha = tryEncryptXChaCha20Poly1305(paddedEnvelope, keyBytes);
        if (xchacha != null) {
            return xchacha;
        }

        return encryptAes256Gcm(paddedEnvelope, keyBytes);
    }

    /**
     * Decrypts ciphertext produced by {@link #encrypt(byte[], SecretKey)}.
     *
     * <p>Authentication must verify, otherwise this throws and no plaintext is returned.</p>
     */
    static byte[] decrypt(byte[] ciphertext, SecretKey key) throws GeneralSecurityException {
        if (ciphertext == null) {
            throw new IllegalArgumentException("ciphertext is null");
        }

        byte[] keyBytes = require256BitKey(key);

        if (ciphertext.length < (NONCE_AES_GCM_BYTES + TAG_LENGTH_BYTES)) {
            throw new IllegalArgumentException("ciphertext too short");
        }

        // Stateless selection based on nonce length (24 => XChaCha, 12 => AES-GCM).
        if (ciphertext.length >= NONCE_XCHACHA20_POLY1305_BYTES + TAG_LENGTH_BYTES) {
            int possibleNonceLen = NONCE_XCHACHA20_POLY1305_BYTES;
            int remaining = ciphertext.length - possibleNonceLen;
            if (isValidCiphertextRemainder(remaining)) {
                return decryptXChaCha20Poly1305(ciphertext, keyBytes);
            }
        }

        int remaining = ciphertext.length - NONCE_AES_GCM_BYTES;
        if (!isValidCiphertextRemainder(remaining)) {
            throw new IllegalArgumentException("ciphertext length does not match expected bucket sizes");
        }
        return decryptAes256Gcm(ciphertext, keyBytes);
    }

    private static boolean isValidCiphertextRemainder(int encryptedPlusTagLen) {
        // encrypted_bytes length equals plaintext length for stream-like ciphers;
        // ciphertext from JCA is encrypted_bytes || tag.
        if (encryptedPlusTagLen < TAG_LENGTH_BYTES) {
            return false;
        }
        int plaintextLen = encryptedPlusTagLen - TAG_LENGTH_BYTES;
        return StaticLengthPadding.isValidBucketSize(plaintextLen);
    }

    private static byte[] require256BitKey(SecretKey key) {
        if (key == null) {
            throw new IllegalArgumentException("key is null");
        }
        byte[] keyBytes = key.getEncoded();
        if (keyBytes == null) {
            throw new IllegalArgumentException("key has no encoding");
        }
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException("key must be 256-bit (32 bytes)");
        }
        return keyBytes;
    }

    private static byte[] tryEncryptXChaCha20Poly1305(byte[] plaintext, byte[] keyBytes) {
        try {
            Cipher cipher = Cipher.getInstance("XChaCha20-Poly1305");

            byte[] nonce = new byte[NONCE_XCHACHA20_POLY1305_BYTES];
            SECURE_RANDOM.nextBytes(nonce);

            SecretKeySpec key = new SecretKeySpec(keyBytes, "ChaCha20");
            cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(nonce));

            byte[] encryptedPlusTag = cipher.doFinal(plaintext);

            byte[] out = new byte[nonce.length + encryptedPlusTag.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(encryptedPlusTag, 0, out, nonce.length, encryptedPlusTag.length);
            return out;
        } catch (GeneralSecurityException | RuntimeException ignored) {
            // Provider/algorithm not available (or not usable on this runtime).
            return null;
        }
    }

    private static byte[] decryptXChaCha20Poly1305(byte[] ciphertext, byte[] keyBytes) throws GeneralSecurityException {
        if (ciphertext.length < (NONCE_XCHACHA20_POLY1305_BYTES + TAG_LENGTH_BYTES)) {
            throw new IllegalArgumentException("ciphertext too short");
        }

        Cipher cipher = Cipher.getInstance("XChaCha20-Poly1305");

        byte[] nonce = new byte[NONCE_XCHACHA20_POLY1305_BYTES];
        System.arraycopy(ciphertext, 0, nonce, 0, nonce.length);

        int encLen = ciphertext.length - nonce.length;
        byte[] encryptedPlusTag = new byte[encLen];
        System.arraycopy(ciphertext, nonce.length, encryptedPlusTag, 0, encLen);

        SecretKeySpec key = new SecretKeySpec(keyBytes, "ChaCha20");
        cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(nonce));

        // doFinal verifies authenticity; any failure throws.
        return cipher.doFinal(encryptedPlusTag);
    }

    private static byte[] encryptAes256Gcm(byte[] plaintext, byte[] keyBytes) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");

        byte[] nonce = new byte[NONCE_AES_GCM_BYTES];
        SECURE_RANDOM.nextBytes(nonce);

        SecretKeySpec key = new SecretKeySpec(keyBytes, "AES");
        GCMParameterSpec params = new GCMParameterSpec(TAG_LENGTH_BYTES * 8, nonce);
        cipher.init(Cipher.ENCRYPT_MODE, key, params);

        byte[] encryptedPlusTag = cipher.doFinal(plaintext);

        byte[] out = new byte[nonce.length + encryptedPlusTag.length];
        System.arraycopy(nonce, 0, out, 0, nonce.length);
        System.arraycopy(encryptedPlusTag, 0, out, nonce.length, encryptedPlusTag.length);
        return out;
    }

    private static byte[] decryptAes256Gcm(byte[] ciphertext, byte[] keyBytes) throws GeneralSecurityException {
        if (ciphertext.length < (NONCE_AES_GCM_BYTES + TAG_LENGTH_BYTES)) {
            throw new IllegalArgumentException("ciphertext too short");
        }

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");

        byte[] nonce = new byte[NONCE_AES_GCM_BYTES];
        System.arraycopy(ciphertext, 0, nonce, 0, nonce.length);

        int encLen = ciphertext.length - nonce.length;
        byte[] encryptedPlusTag = new byte[encLen];
        System.arraycopy(ciphertext, nonce.length, encryptedPlusTag, 0, encLen);

        SecretKeySpec key = new SecretKeySpec(keyBytes, "AES");
        GCMParameterSpec params = new GCMParameterSpec(TAG_LENGTH_BYTES * 8, nonce);
        cipher.init(Cipher.DECRYPT_MODE, key, params);

        // doFinal verifies authenticity; any failure throws.
        return cipher.doFinal(encryptedPlusTag);
    }
}

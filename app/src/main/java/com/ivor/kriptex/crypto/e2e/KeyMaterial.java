package com.ivor.kriptex.crypto.e2e;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Locale;

import org.spongycastle.jce.provider.BouncyCastleProvider;

/**
 * Pure key generation + (public) serialization helpers.
 *
 * <p>No storage is performed here.</p>
 */
public final class KeyMaterial {

    private static final SecureRandom RNG = new SecureRandom();

    private KeyMaterial() {
        // no instances
    }

    /**
     * Generates a long-term identity key pair.
     *
     * <p>Prefers X25519 when available, otherwise falls back to Curve25519 equivalent.</p>
     */
    public static IdentityKeyPair generateIdentityKeyPair() throws GeneralSecurityException {
        KeyPair kp = generateX25519OrCurve25519();
        return new IdentityKeyPair(kp.getPrivate(), kp.getPublic());
    }

    /**
     * Generates a batch of one-time prekeys into the given store.
     */
    public static void generatePreKeys(PreKeyStore store, int count) throws GeneralSecurityException {
        if (store == null) {
            throw new IllegalArgumentException("store is null");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("count must be > 0");
        }

        for (int i = 0; i < count; i++) {
            storeOnePreKey(store);
        }
    }

    /**
     * Builds a publishable bundle from an identity public key and an unused prekey.
     */
    public static PreKeyBundle toBundle(PublicKey identityPublic, PreKey oneTimePreKey) {
        if (identityPublic == null) {
            throw new IllegalArgumentException("identityPublic is null");
        }
        if (oneTimePreKey == null) {
            throw new IllegalArgumentException("oneTimePreKey is null");
        }
        return new PreKeyBundle(identityPublic, new PreKeyPublic(oneTimePreKey.preKeyId, oneTimePreKey.publicKey));
    }

    public static byte[] encodePublicKey(PublicKey key) {
        if (key == null) {
            throw new IllegalArgumentException("key is null");
        }
        byte[] enc = key.getEncoded();
        if (enc == null) {
            throw new IllegalArgumentException("public key has no encoding");
        }
        return enc.clone();
    }

    public static PublicKey decodePublicKey(byte[] encoded, String algorithm) throws GeneralSecurityException {
        if (encoded == null) {
            throw new IllegalArgumentException("encoded is null");
        }
        if (algorithm == null) {
            throw new IllegalArgumentException("algorithm is null");
        }
        KeyFactory kf = KeyFactory.getInstance(algorithm);
        try {
            return kf.generatePublic(new X509EncodedKeySpec(encoded));
        } catch (InvalidKeySpecException e) {
            throw new GeneralSecurityException("invalid public key encoding", e);
        }
    }

    public static PrivateKey decodePrivateKey(byte[] encoded, String algorithm) throws GeneralSecurityException {
        if (encoded == null) {
            throw new IllegalArgumentException("encoded is null");
        }
        if (algorithm == null) {
            throw new IllegalArgumentException("algorithm is null");
        }
        KeyFactory kf = KeyFactory.getInstance(algorithm);
        try {
            return kf.generatePrivate(new PKCS8EncodedKeySpec(encoded));
        } catch (InvalidKeySpecException e) {
            throw new GeneralSecurityException("invalid private key encoding", e);
        }
    }

    private static void storeOnePreKey(PreKeyStore store) throws GeneralSecurityException {
        // preKeyId is defined as uint32; we store it in a Java signed int.
        // Guard against collisions by retrying.
        for (int attempt = 0; attempt < 32; attempt++) {
            int preKeyId = RNG.nextInt();
            KeyPair kp = generateX25519OrCurve25519();
            try {
                store.put(new PreKey(preKeyId, kp.getPrivate(), kp.getPublic()));
                return;
            } catch (IllegalStateException collision) {
                // try again
            }
        }
        throw new GeneralSecurityException("unable to allocate unique preKeyId after retries");
    }

    private static KeyPair generateX25519OrCurve25519() throws GeneralSecurityException {
        // 1) Prefer platform X25519 (Android 12+/JDK 11+ typically)
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("X25519");
            return kpg.generateKeyPair();
        } catch (GeneralSecurityException ignored) {
            // fall through
        }

        // 2) Curve25519 equivalent via SpongyCastle provider.
        ensureSpongyCastleProvider();

        // Try a couple of common names used by BC/SC.
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("X25519", "SC");
            return kpg.generateKeyPair();
        } catch (GeneralSecurityException ignored) {
            // fall through
        }

        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", "SC");
            // BC uses lowercase name for this curve.
            kpg.initialize(new ECGenParameterSpec("curve25519"), RNG);
            return kpg.generateKeyPair();
        } catch (GeneralSecurityException firstCurveNameFailed) {
            // Some providers may expect different casing.
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", "SC");
            kpg.initialize(new ECGenParameterSpec("Curve25519"), RNG);
            return kpg.generateKeyPair();
        }
    }

    private static void ensureSpongyCastleProvider() {
        if (Security.getProvider("SC") != null) {
            return;
        }
        // Safe to call multiple times; Security will ignore duplicates.
        Security.addProvider(new BouncyCastleProvider());
    }
}

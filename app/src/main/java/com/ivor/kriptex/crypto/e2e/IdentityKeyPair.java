package com.ivor.kriptex.crypto.e2e;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;

/**
 * Long-term asymmetric identity key pair.
 *
 * <p>Private key must never leave the device. Only the public key is shareable.</p>
 */
public final class IdentityKeyPair {

    public final PrivateKey identityPrivate;
    public final PublicKey identityPublic;

    IdentityKeyPair(PrivateKey identityPrivate, PublicKey identityPublic) {
        if (identityPrivate == null) {
            throw new IllegalArgumentException("identityPrivate is null");
        }
        if (identityPublic == null) {
            throw new IllegalArgumentException("identityPublic is null");
        }
        this.identityPrivate = identityPrivate;
        this.identityPublic = identityPublic;
    }

    /**
     * Serializable/shareable public key bytes.
     *
     * <p>For X25519 keys this will typically be 32 bytes (raw), but we do not assume that here.</p>
     */
    public byte[] identityPublicBytes() {
        byte[] enc = identityPublic.getEncoded();
        if (enc == null) {
            throw new IllegalStateException("public key has no encoding");
        }
        return Arrays.copyOf(enc, enc.length);
    }
}

package com.ivor.kriptex.crypto.e2e;

import java.security.PublicKey;
import java.util.Arrays;

/**
 * Public-only representation of a one-time prekey.
 */
public final class PreKeyPublic {

    public final int preKeyId; // uint32 stored in signed int
    public final PublicKey publicKey;

    public PreKeyPublic(int preKeyId, PublicKey publicKey) {
        if (publicKey == null) {
            throw new IllegalArgumentException("publicKey is null");
        }
        this.preKeyId = preKeyId;
        this.publicKey = publicKey;
    }

    public byte[] publicKeyBytes() {
        byte[] enc = publicKey.getEncoded();
        if (enc == null) {
            throw new IllegalStateException("public key has no encoding");
        }
        return Arrays.copyOf(enc, enc.length);
    }
}

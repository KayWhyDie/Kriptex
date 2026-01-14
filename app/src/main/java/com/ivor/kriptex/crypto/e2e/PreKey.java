package com.ivor.kriptex.crypto.e2e;

import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * One-time prekey (asymmetric key pair) used for offline session bootstrap.
 */
public final class PreKey {

    public final int preKeyId; // uint32 stored in signed int
    public final PrivateKey privateKey;
    public final PublicKey publicKey;

    PreKey(int preKeyId, PrivateKey privateKey, PublicKey publicKey) {
        if (privateKey == null) {
            throw new IllegalArgumentException("privateKey is null");
        }
        if (publicKey == null) {
            throw new IllegalArgumentException("publicKey is null");
        }
        this.preKeyId = preKeyId;
        this.privateKey = privateKey;
        this.publicKey = publicKey;
    }
}

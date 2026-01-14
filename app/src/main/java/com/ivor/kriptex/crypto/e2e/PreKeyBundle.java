package com.ivor.kriptex.crypto.e2e;

import java.security.PublicKey;

/**
 * Public bundle that can be published for offline session bootstrap.
 *
 * <p>Contains ONLY public material.</p>
 */
public final class PreKeyBundle {

    public final PublicKey identityKey;
    public final PreKeyPublic oneTimePreKey;

    public PreKeyBundle(PublicKey identityKey, PreKeyPublic oneTimePreKey) {
        if (identityKey == null) {
            throw new IllegalArgumentException("identityKey is null");
        }
        if (oneTimePreKey == null) {
            throw new IllegalArgumentException("oneTimePreKey is null");
        }
        this.identityKey = identityKey;
        this.oneTimePreKey = oneTimePreKey;
    }
}

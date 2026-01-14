package com.ivor.kriptex.crypto.e2e;

import java.security.GeneralSecurityException;

/**
 * Identity key lifecycle helper.
 *
 * <p>Creates the identity key on first run (when store is empty) and returns the existing
 * key on subsequent runs.</p>
 */
public final class IdentityKeyManager {

    private IdentityKeyManager() {
        // no instances
    }

    public static IdentityKeyPair getOrCreate(IdentityKeyStore store) throws GeneralSecurityException {
        if (store == null) {
            throw new IllegalArgumentException("store is null");
        }

        IdentityKeyPair existing = store.load();
        if (existing != null) {
            return existing;
        }

        IdentityKeyPair created = KeyMaterial.generateIdentityKeyPair();
        store.save(created);
        return created;
    }
}

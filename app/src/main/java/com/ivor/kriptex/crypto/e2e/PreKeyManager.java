package com.ivor.kriptex.crypto.e2e;

import java.security.GeneralSecurityException;

/**
 * Prekey pool lifecycle helper.
 *
 * <p>No networking is implemented here; this is purely local state management.</p>
 */
public final class PreKeyManager {

    private PreKeyManager() {
        // no instances
    }

    /**
     * Ensures at least {@code desiredUnusedCount} unused prekeys exist in the store.
     */
    public static void ensurePreKeys(PreKeyStore store, int desiredUnusedCount) throws GeneralSecurityException {
        if (store == null) {
            throw new IllegalArgumentException("store is null");
        }
        if (desiredUnusedCount <= 0) {
            throw new IllegalArgumentException("desiredUnusedCount must be > 0");
        }

        int missing = desiredUnusedCount - store.unusedCount();
        if (missing <= 0) {
            return;
        }
        KeyMaterial.generatePreKeys(store, missing);
    }

    /**
     * Marks a prekey as used and returns it for one-time usage.
     */
    public static PreKey consume(PreKeyStore store, int preKeyId) {
        if (store == null) {
            throw new IllegalArgumentException("store is null");
        }
        return store.consume(preKeyId);
    }
}

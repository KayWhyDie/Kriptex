package com.ivor.kriptex.crypto.e2e;

/**
 * Storage abstraction for prekeys.
 *
 * <p>Implementation is intentionally left to the app (DB/keystore/etc).</p>
 * <p>Keys are sensitive; do not log or expose private key bytes.</p>
 */
public interface PreKeyStore {

    /**
     * Adds a freshly generated prekey to the store.
     *
     * @throws IllegalStateException if the preKeyId already exists
     */
    void put(PreKey preKey);

    /**
     * Returns a prekey by id, or null if missing.
     */
    PreKey get(int preKeyId);

    /**
     * Atomically marks the prekey as used and removes it from the unused pool.
     * Returns the prekey so the caller can use the private key material once.
     *
     * @return the prekey if it was unused and is now consumed; otherwise null
     */
    PreKey consume(int preKeyId);

    /**
     * @return number of unused prekeys remaining
     */
    int unusedCount();
}

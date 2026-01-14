package com.ivor.kriptex.crypto.e2e;

/**
 * Storage abstraction for long-term identity key material.
 *
 * <p>The private key must never leave the device; implementations should use Android Keystore
 * or an encrypted local database. This interface defines state only; no storage backend is
 * implemented here.</p>
 */
public interface IdentityKeyStore {

    /**
     * Loads the persisted identity key pair, or null if none exists yet.
     */
    IdentityKeyPair load();

    /**
     * Persists the identity key pair.
     *
     * @throws IllegalStateException if an identity already exists and overwrite is not allowed
     */
    void save(IdentityKeyPair identityKeyPair);
}

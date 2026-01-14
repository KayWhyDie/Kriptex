package com.ivor.kriptex.crypto.e2e;

import org.junit.Test;

import java.security.GeneralSecurityException;
import java.security.PublicKey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class KeyMaterialTest {

    @Test
    public void identityKeyPair_generates_and_public_is_serializable() throws Exception {
        IdentityKeyPair id = KeyMaterial.generateIdentityKeyPair();
        assertNotNull(id);
        assertNotNull(id.identityPrivate);
        assertNotNull(id.identityPublic);

        byte[] pub = id.identityPublicBytes();
        assertNotNull(pub);
        assertTrue(pub.length > 0);

        // Ensure we can round-trip the public key encoding with the same algorithm.
        PublicKey decoded = KeyMaterial.decodePublicKey(pub, id.identityPublic.getAlgorithm());
        assertNotNull(decoded);
        assertEquals(id.identityPublic.getAlgorithm(), decoded.getAlgorithm());
    }

    @Test
    public void preKeyStore_generates_consumes_and_never_reuses() throws Exception {
        InMemoryPreKeyStore store = new InMemoryPreKeyStore();
        KeyMaterial.generatePreKeys(store, 50);
        assertEquals(50, store.unusedCount());

        // Consume one prekey and ensure it cannot be consumed twice.
        int preKeyId = store.anyUnusedPreKeyIdForTest();
        PreKey consumed = store.consume(preKeyId);
        assertNotNull(consumed);
        assertEquals(preKeyId, consumed.preKeyId);
        assertEquals(49, store.unusedCount());

        PreKey consumedAgain = store.consume(preKeyId);
        assertEquals(null, consumedAgain);
        assertEquals(49, store.unusedCount());
    }

    @Test(expected = GeneralSecurityException.class)
    public void decodePublicKey_rejects_garbage() throws Exception {
        KeyMaterial.decodePublicKey(new byte[]{1, 2, 3, 4, 5}, "X25519");
    }
}

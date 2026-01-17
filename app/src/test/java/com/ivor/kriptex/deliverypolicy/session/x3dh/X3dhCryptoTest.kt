package com.ivor.kriptex.deliverypolicy.session.x3dh

import com.ivor.kriptex.deliverypolicy.session.ratchet.RatchetDh
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class X3dhCryptoTest {

    @Test
    fun signed_prekey_signature_verifies_and_shared_secret_matches_both_sides() {
        val aId = X3dhCrypto.generateIdentityKeyPair()
        val bId = X3dhCrypto.generateIdentityKeyPair()

        val bSpk: RatchetDh.KeyPair = X3dhCrypto.generateX25519KeyPair()
        val spkSig = X3dhCrypto.signSignedPreKey(identitySeed = bId.seed, signedPreKeyId = 7, signedPreKeyPublic = bSpk.publicKey)

        assertTrue(
            X3dhCrypto.verifySignedPreKeySignature(
                identityPublic = bId.publicKey,
                signedPreKeyId = 7,
                signedPreKeyPublic = bSpk.publicKey,
                signature = spkSig,
            )
        )
        assertFalse(
            X3dhCrypto.verifySignedPreKeySignature(
                identityPublic = bId.publicKey,
                signedPreKeyId = 8,
                signedPreKeyPublic = bSpk.publicKey,
                signature = spkSig,
            )
        )

        val bOpk: RatchetDh.KeyPair = X3dhCrypto.generateX25519KeyPair()
        val aBase: RatchetDh.KeyPair = X3dhCrypto.generateX25519KeyPair()

        val ssA = X3dhCrypto.computeSharedSecretInitiator(
            initiatorIdentitySeedEd = aId.seed,
            initiatorBasePrivateX = aBase.privateKey,
            responderIdentityPublicEd = bId.publicKey,
            responderSignedPreKeyPublicX = bSpk.publicKey,
            responderOneTimePreKeyPublicX = bOpk.publicKey,
        )

        val ssB = X3dhCrypto.computeSharedSecretResponder(
            responderIdentitySeedEd = bId.seed,
            responderSignedPreKeyPrivateX = bSpk.privateKey,
            initiatorIdentityPublicEd = aId.publicKey,
            initiatorBasePublicX = aBase.publicKey,
            responderOneTimePreKeyPrivateX = bOpk.privateKey,
        )

        assertArrayEquals(ssA, ssB)

        val secretsA = X3dhCrypto.deriveSecrets(
            sharedSecret = ssA,
            initiatorIdentityPublicEd = aId.publicKey,
            responderIdentityPublicEd = bId.publicKey,
            sessionId = "s1",
            initiatorNonce = byteArrayOf(1, 2, 3),
            responderNonce = byteArrayOf(4, 5, 6),
        )
        val secretsB = X3dhCrypto.deriveSecrets(
            sharedSecret = ssB,
            initiatorIdentityPublicEd = aId.publicKey,
            responderIdentityPublicEd = bId.publicKey,
            sessionId = "s1",
            initiatorNonce = byteArrayOf(1, 2, 3),
            responderNonce = byteArrayOf(4, 5, 6),
        )

        assertArrayEquals(secretsA.initialRootKey, secretsB.initialRootKey)
        assertArrayEquals(secretsA.confirmKey, secretsB.confirmKey)

        val transcript = byteArrayOf(9, 9, 9)
        val tagA = X3dhCrypto.confirmTag(secretsA.confirmKey, transcript)
        val tagB = X3dhCrypto.confirmTag(secretsB.confirmKey, transcript)
        assertArrayEquals(tagA, tagB)
    }
}

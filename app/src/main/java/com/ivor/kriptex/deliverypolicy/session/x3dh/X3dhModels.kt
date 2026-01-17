package com.ivor.kriptex.deliverypolicy.session.x3dh

/**
 * X3DH identity key pair.
 *
 * Signal semantics: identity is an Ed25519 signing key; an X25519 DH key is derived from it.
 *
 * - [seed] is the 32-byte Ed25519 private seed and is the only persisted secret.
 * - [publicKey] is the 32-byte Ed25519 public key.
 */
data class X3dhIdentityKeyPair(
    val seed: ByteArray,
    val publicKey: ByteArray,
) {
    init {
        require(seed.size == 32) { "bad_identity_seed_len" }
        require(publicKey.size == 32) { "bad_identity_public_len" }
    }
}

/** Signed prekey record (X25519), signed by the identity key. */
data class X3dhSignedPreKey(
    val preKeyId: Int,
    val privateKey: ByteArray,
    val publicKey: ByteArray,
    val signature: ByteArray,
    val createdAtElapsedMs: Long,
) {
    init {
        require(publicKey.size == 32) { "bad_signed_prekey_public_len" }
        require(privateKey.size == 32) { "bad_signed_prekey_private_len" }
        require(signature.isNotEmpty()) { "missing_signed_prekey_signature" }
    }
}

/** One-time prekey record (X25519). */
data class X3dhOneTimePreKey(
    val preKeyId: Int,
    val privateKey: ByteArray,
    val publicKey: ByteArray,
    val createdAtElapsedMs: Long,
) {
    init {
        require(publicKey.size == 32) { "bad_onetime_prekey_public_len" }
        require(privateKey.size == 32) { "bad_onetime_prekey_private_len" }
    }
}

/** Publishable prekey bundle (no private key material). */
data class X3dhPreKeyBundle(
    /** Responder identity public key (Ed25519, 32 bytes). */
    val identityPublicKey: ByteArray,
    /** Responder signed prekey id. */
    val signedPreKeyId: Int,
    /** Responder signed prekey public key (X25519, 32 bytes). */
    val signedPreKeyPublicKey: ByteArray,
    /** Ed25519 signature by identity key over (signedPreKeyId || signedPreKeyPublicKey). */
    val signedPreKeySignature: ByteArray,
    /** Optional one-time prekey id. */
    val oneTimePreKeyId: Int? = null,
    /** Optional one-time prekey public key (X25519, 32 bytes). */
    val oneTimePreKeyPublicKey: ByteArray? = null,
) {
    init {
        require(identityPublicKey.size == 32) { "bad_identity_public_len" }
        require(signedPreKeyPublicKey.size == 32) { "bad_signed_prekey_public_len" }
        require(signedPreKeySignature.isNotEmpty()) { "missing_signed_prekey_signature" }
        if (oneTimePreKeyId != null || oneTimePreKeyPublicKey != null) {
            require(oneTimePreKeyId != null && oneTimePreKeyPublicKey != null) { "one_time_prekey_incomplete" }
            require(oneTimePreKeyPublicKey.size == 32) { "bad_onetime_prekey_public_len" }
        }
    }
}

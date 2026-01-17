package com.ivor.kriptex.deliverypolicy.session.crypto

/**
 * Opaque ciphertext container.
 *
 * No plaintext metadata is stored here (messageId/sessionId are carried out-of-band).
 */
data class CiphertextBlob(
    val nonce: ByteArray,
    /**
     * Cleartext but authenticated metadata (included in AEAD AAD).
     *
     * For Double Ratchet this is the encoded ratchet header.
     */
    val header: ByteArray,
    /** Ciphertext including authentication tag (if the underlying AEAD appends it). */
    val ciphertext: ByteArray,
)

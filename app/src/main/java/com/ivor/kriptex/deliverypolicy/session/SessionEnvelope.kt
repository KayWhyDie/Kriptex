package com.ivor.kriptex.deliverypolicy.session

/**
 * Session-bound wire envelope.
 *
 * This wraps a protocol message's encoded bytes and binds it to:
 * - sessionId
 * - per-session sequence number
 */
data class SessionEnvelope(
    val sessionId: String,
    val seq: Long,
    /**
     * Cleartext protocol messageId.
     *
     * Present in envelope v2+ to allow AEAD AAD binding before decrypt.
     * Null for legacy v1 envelopes (pre-AEAD).
     */
    val messageId: String?,
    val inner: ByteArray,
)

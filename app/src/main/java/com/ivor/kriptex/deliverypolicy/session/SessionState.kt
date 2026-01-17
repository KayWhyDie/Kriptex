package com.ivor.kriptex.deliverypolicy.session

import com.ivor.kriptex.deliverypolicy.protocol.SessionAeadAlgorithm
import com.ivor.kriptex.deliverypolicy.session.ratchet.RatchetState

enum class SessionStatus {
    PENDING,
    ESTABLISHED,
}

data class SessionState(
    val peerId: String,
    val conversationId: String,
    val sessionId: String,
    val role: SessionRole,
    val status: SessionStatus,
    /**
     * Whether session-bound payloads are protected by the AEAD boundary.
     *
     * Restored legacy sessions (from older snapshots) may have this false to keep wire compatibility.
     */
    val aeadEnabled: Boolean,
    val aeadAlgorithm: SessionAeadAlgorithm,
    val localIdentityPublicKey: ByteArray,
    val peerIdentityPublicKey: ByteArray,
    val initiatorNonce: ByteArray,
    val responderNonce: ByteArray?,
    val sharedKey: ByteArray?,
    /** Pending responder signed prekey id (initiator-side, until accept). */
    val pendingResponderSignedPreKeyId: Int? = null,
    /** Pending responder signed prekey public key (X25519, 32 bytes). */
    val pendingResponderSignedPreKeyPublicKey: ByteArray? = null,
    /** Pending responder signed prekey signature (Ed25519). */
    val pendingResponderSignedPreKeySignature: ByteArray? = null,
    /** Pending responder one-time prekey id, if used. */
    val pendingResponderOneTimePreKeyId: Int? = null,
    /** Pending responder one-time prekey public key (X25519, 32 bytes), if used. */
    val pendingResponderOneTimePreKeyPublicKey: ByteArray? = null,
    val nextOutboundSeq: Long,
    val replayWindow: ReplayWindow,
    /** Sliding set of inbound messageIds already accepted (pre-decrypt replay filter). */
    val inboundMessageIdsSeen: List<String>,
    /** Established Double Ratchet state (present when both peers support ratcheting). */
    val ratchetState: RatchetState? = null,
    /** Pending initiator local DH keys until SessionAccept finalizes the session. */
    val pendingRatchetDhPrivateKey: ByteArray? = null,
    val pendingRatchetDhPublicKey: ByteArray? = null,
) {

    fun isEstablished(): Boolean {
        if (status != SessionStatus.ESTABLISHED) return false
        if (responderNonce == null) return false

        // For AEAD-enabled sessions, require Double Ratchet.
        if (aeadEnabled) return ratchetState != null

        // Legacy plaintext envelopes still require the session to have been established.
        return sharedKey != null
    }
}

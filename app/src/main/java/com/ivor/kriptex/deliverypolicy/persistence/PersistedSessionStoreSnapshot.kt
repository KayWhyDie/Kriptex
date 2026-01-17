package com.ivor.kriptex.deliverypolicy.persistence

import com.ivor.kriptex.deliverypolicy.protocol.SessionAeadAlgorithm
import com.ivor.kriptex.deliverypolicy.session.SessionRole
import com.ivor.kriptex.deliverypolicy.session.SessionStatus

data class PersistedSessionStoreSnapshot(
    val version: Int = 5,
    val capturedAtElapsedMs: Long,
    val sessions: List<PersistedSessionState>,
)

data class PersistedSessionState(
    val peerId: String,
    val conversationId: String,
    val sessionId: String,
    val role: SessionRole,
    val status: SessionStatus,
    val aeadEnabled: Boolean = true,
    val aeadAlgorithm: SessionAeadAlgorithm,
    val localIdentityPublicKey: ByteArray,
    val peerIdentityPublicKey: ByteArray,
    val initiatorNonce: ByteArray,
    val responderNonce: ByteArray?,
    val sharedKey: ByteArray?,
    val pendingResponderSignedPreKeyId: Int? = null,
    val pendingResponderSignedPreKeyPublicKey: ByteArray? = null,
    val pendingResponderSignedPreKeySignature: ByteArray? = null,
    val pendingResponderOneTimePreKeyId: Int? = null,
    val pendingResponderOneTimePreKeyPublicKey: ByteArray? = null,
    /** Established Double Ratchet state (null if peer/session is legacy). */
    val ratchet: PersistedRatchetState? = null,
    /** Pending initiator ratchet DH keys until accept is received. */
    val pendingRatchetDhPrivateKey: ByteArray? = null,
    val pendingRatchetDhPublicKey: ByteArray? = null,
    val nextOutboundSeq: Long,
    val replayHighestSeqSeen: Long,
    val replaySeenBitmask: Long,
    val inboundMessageIdsSeen: List<String> = emptyList(),
)

data class PersistedRatchetState(
    val rootKey: ByteArray,
    val sendingChainKey: ByteArray,
    val receivingChainKey: ByteArray,
    val localDhPrivateKey: ByteArray,
    val localDhPublicKey: ByteArray,
    val remoteDhPublicKey: ByteArray,
    val ns: Int,
    val nr: Int,
    val pn: Int,
    val pendingSendDhRatchet: Boolean,
    val skippedKeys: List<PersistedSkippedMessageKey> = emptyList(),
)

data class PersistedSkippedMessageKey(
    val dhPublicKey: ByteArray,
    val n: Int,
    val messageKey: ByteArray,
)

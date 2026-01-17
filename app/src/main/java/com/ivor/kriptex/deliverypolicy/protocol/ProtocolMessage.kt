package com.ivor.kriptex.deliverypolicy.protocol

/**
 * First-class protocol message layer.
 *
 * Sits ABOVE transport (raw bytes) and BELOW crypto.
 *
 * Guarantees:
 * - payloads are opaque
 * - time is monotonic (elapsed)
 */
sealed interface ProtocolMessage {
    val messageId: String
    val conversationId: String
    val createdAtElapsedMs: Long
    val type: Type

    enum class Type {
        USER,
        ACK,
        SESSION_INIT,
        SESSION_ACCEPT,
        SENDER_KEY_DISTRIBUTION,
        SENDER_KEY_GROUP_MESSAGE,
        UNKNOWN,
    }
}

data class UserMessage(
    override val messageId: String,
    override val conversationId: String,
    override val createdAtElapsedMs: Long,
    val payload: ByteArray,
) : ProtocolMessage {
    override val type: ProtocolMessage.Type = ProtocolMessage.Type.USER
}

data class AckMessage(
    override val messageId: String,
    override val conversationId: String,
    override val createdAtElapsedMs: Long,
    /** The outbound/user messageId being acknowledged. */
    val ackedMessageId: String,
) : ProtocolMessage {
    override val type: ProtocolMessage.Type = ProtocolMessage.Type.ACK
}

/**
 * Future-safe extension point.
 *
 * Unknown messages are preserved as (typeName + payload) for round-trip safety.
 */
data class UnknownMessage(
    override val messageId: String,
    override val conversationId: String,
    override val createdAtElapsedMs: Long,
    val typeName: String,
    val payload: ByteArray,
) : ProtocolMessage {
    override val type: ProtocolMessage.Type = ProtocolMessage.Type.UNKNOWN
}

/**
 * Session establishment: initiator -> responder.
 */
data class SessionInitMessage(
    override val messageId: String,
    override val conversationId: String,
    override val createdAtElapsedMs: Long,
    val sessionId: String,
    /** Initiator's preferred AEAD algorithm for this session. */
    val aeadAlgorithm: SessionAeadAlgorithm,
    /** Initiator identity public key (Ed25519, 32 bytes). */
    val initiatorIdentityPublicKey: ByteArray,
    val initiatorNonce: ByteArray,
    /** Initiator X3DH base key public (X25519, 32 bytes). */
    val initiatorBasePublicKey: ByteArray,
    /** Responder identity public key (Ed25519, 32 bytes). */
    val responderIdentityPublicKey: ByteArray,
    /** Responder signed prekey id. */
    val responderSignedPreKeyId: Int,
    /** Responder signed prekey public (X25519, 32 bytes). */
    val responderSignedPreKeyPublicKey: ByteArray,
    /** Signature by responder identity key over (responderSignedPreKeyId || responderSignedPreKeyPublicKey). */
    val responderSignedPreKeySignature: ByteArray,
    /** Optional one-time prekey id. */
    val responderOneTimePreKeyId: Int? = null,
    /** Optional one-time prekey public key (X25519, 32 bytes). */
    val responderOneTimePreKeyPublicKey: ByteArray? = null,
) : ProtocolMessage {
    override val type: ProtocolMessage.Type = ProtocolMessage.Type.SESSION_INIT
}

/**
 * Session establishment: responder -> initiator.
 */
data class SessionAcceptMessage(
    override val messageId: String,
    override val conversationId: String,
    override val createdAtElapsedMs: Long,
    val sessionId: String,
    /** Responder's selected/accepted AEAD algorithm for this session. */
    val aeadAlgorithm: SessionAeadAlgorithm,
    val responderIdentityPublicKey: ByteArray,
    val responderNonce: ByteArray,
    // Echo back initiator material to bind accept to init.
    val initiatorIdentityPublicKey: ByteArray,
    val initiatorNonce: ByteArray,
    /** Echo of initiator X3DH base key public (X25519, 32 bytes). */
    val initiatorBasePublicKey: ByteArray,
    /** Echo of responder signed prekey id. */
    val responderSignedPreKeyId: Int,
    /** Echo of responder one-time prekey id, if used. */
    val responderOneTimePreKeyId: Int? = null,
    /** Confirm tag proving both sides derived the same X3DH secret. */
    val confirmTag: ByteArray,
) : ProtocolMessage {
    override val type: ProtocolMessage.Type = ProtocolMessage.Type.SESSION_ACCEPT
}

/**
 * Sender Key Distribution (control plane for group messaging).
 *
 * This message MUST be sent inside a 1:1 established session envelope.
 */
data class SenderKeyDistributionMessage(
    override val messageId: String,
    override val conversationId: String,
    override val createdAtElapsedMs: Long,
    /** Opaque 32-byte group id (derived; do not use human-readable ids on wire). */
    val groupId: ByteArray,
    /** Sender Ed25519 identity public key (32 bytes). Must match the 1:1 session peer identity. */
    val senderIdentityPublicKey: ByteArray,
    /** Sender key id for this (groupId, sender). Monotonically increases on rotation. */
    val senderKeyId: Long,
    /** Initial chain key material for this sender key id (32 bytes). */
    val senderChainKey: ByteArray,
) : ProtocolMessage {
    override val type: ProtocolMessage.Type = ProtocolMessage.Type.SENDER_KEY_DISTRIBUTION

    init {
        require(groupId.size == 32) { "group_id_must_be_32_bytes" }
        require(senderIdentityPublicKey.size == 32) { "sender_identity_key_must_be_32_bytes" }
        require(senderKeyId > 0) { "non_positive_sender_key_id" }
        require(senderChainKey.size == 32) { "sender_chain_key_must_be_32_bytes" }
    }
}

/**
 * Sender-key encrypted group message (data plane).
 *
 * This message MUST be sent inside a 1:1 established session envelope.
 * The ciphertext is decrypted using a per-(groupId, senderIdentity) sender key ratchet.
 */
data class SenderKeyGroupMessage(
    override val messageId: String,
    override val conversationId: String,
    override val createdAtElapsedMs: Long,
    /** Opaque 32-byte group id. */
    val groupId: ByteArray,
    /** Sender Ed25519 identity public key (32 bytes). Must match the 1:1 session peer identity. */
    val senderIdentityPublicKey: ByteArray,
    /** Sender key id for this (groupId, sender). Monotonically increases on rotation. */
    val senderKeyId: Long,
    /** Sender message counter within this sender key id (starts at 1). */
    val counter: Long,
    /** AEAD ciphertext bytes (includes tag). */
    val ciphertext: ByteArray,
) : ProtocolMessage {
    override val type: ProtocolMessage.Type = ProtocolMessage.Type.SENDER_KEY_GROUP_MESSAGE

    init {
        require(groupId.size == 32) { "group_id_must_be_32_bytes" }
        require(senderIdentityPublicKey.size == 32) { "sender_identity_key_must_be_32_bytes" }
        require(senderKeyId > 0) { "non_positive_sender_key_id" }
        require(counter > 0) { "non_positive_counter" }
        require(ciphertext.isNotEmpty()) { "missing_ciphertext" }
    }
}

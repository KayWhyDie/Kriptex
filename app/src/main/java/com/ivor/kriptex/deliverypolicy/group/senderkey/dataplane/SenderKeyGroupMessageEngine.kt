package com.ivor.kriptex.deliverypolicy.group.senderkey.dataplane

import com.ivor.kriptex.deliverypolicy.group.GroupId
import com.ivor.kriptex.deliverypolicy.group.GroupStore
import com.ivor.kriptex.deliverypolicy.group.senderkey.SenderKeyStore
import com.ivor.kriptex.deliverypolicy.protocol.SenderKeyGroupMessage
import com.ivor.kriptex.deliverypolicy.protocol.UserMessage

class SenderKeyGroupMessageEngine(
    private val localIdentityPublicKey: ByteArray,
    private val groupStore: GroupStore,
    private val senderKeyStore: SenderKeyStore,
    private val crypto: SenderKeyGroupCrypto = SenderKeyGroupCrypto(),
) {

    sealed interface InboundDecision {
        data class Accepted(val userMessage: UserMessage) : InboundDecision
        data class Rejected(val reason: String) : InboundDecision
    }

    fun encryptOutbound(
        conversationId: String,
        messageId: String,
        createdAtElapsedMs: Long,
        plaintextPayload: ByteArray,
    ): SenderKeyGroupMessage {
        val group = groupStore.getByConversationId(conversationId) ?: throw IllegalStateException("unknown_group")
        if (!group.isMember(localIdentityPublicKey)) throw IllegalStateException("local_not_group_member")

        val state = senderKeyStore.get(group.groupId, localIdentityPublicKey) ?: throw IllegalStateException("missing_local_sender_key")
        val step = crypto.encrypt(
            state = state,
            groupId = group.groupId,
            messageId = messageId,
            conversationId = conversationId,
            plaintext = plaintextPayload,
        )

        senderKeyStore.put(step.newState)

        return SenderKeyGroupMessage(
            messageId = messageId,
            conversationId = conversationId,
            createdAtElapsedMs = createdAtElapsedMs,
            groupId = group.groupId.copyBytes(),
            senderIdentityPublicKey = localIdentityPublicKey.copyOf(),
            senderKeyId = state.senderKeyId,
            counter = step.counter,
            ciphertext = step.ciphertext,
        )
    }

    fun decryptInbound(authenticatedPeerIdentityPublicKey: ByteArray, msg: SenderKeyGroupMessage): InboundDecision {
        if (!msg.senderIdentityPublicKey.contentEquals(authenticatedPeerIdentityPublicKey)) {
            return InboundDecision.Rejected("sender_identity_mismatch")
        }

        val groupId = try {
            GroupId(msg.groupId)
        } catch (_: Exception) {
            return InboundDecision.Rejected("bad_group_id")
        }

        val group = groupStore.getById(groupId) ?: return InboundDecision.Rejected("unknown_group")
        if (group.conversationId != msg.conversationId) return InboundDecision.Rejected("conversation_mismatch")
        if (!group.isMember(localIdentityPublicKey)) return InboundDecision.Rejected("local_not_group_member")
        if (!group.isMember(authenticatedPeerIdentityPublicKey)) return InboundDecision.Rejected("sender_not_group_member")

        val state = senderKeyStore.get(groupId, authenticatedPeerIdentityPublicKey)
            ?: return InboundDecision.Rejected("missing_sender_key")

        val outcome = crypto.decrypt(
            state = state,
            groupId = groupId,
            senderIdentityPublicKey = authenticatedPeerIdentityPublicKey,
            senderKeyId = msg.senderKeyId,
            counter = msg.counter,
            messageId = msg.messageId,
            conversationId = msg.conversationId,
            ciphertext = msg.ciphertext,
        )

        return when (outcome) {
            is SenderKeyGroupCrypto.DecryptOutcome.Rejected -> InboundDecision.Rejected(outcome.reason)
            is SenderKeyGroupCrypto.DecryptOutcome.Accepted -> {
                senderKeyStore.put(outcome.newState)
                InboundDecision.Accepted(
                    userMessage = UserMessage(
                        messageId = msg.messageId,
                        conversationId = msg.conversationId,
                        createdAtElapsedMs = msg.createdAtElapsedMs,
                        payload = outcome.plaintext,
                    ),
                )
            }
        }
    }
}

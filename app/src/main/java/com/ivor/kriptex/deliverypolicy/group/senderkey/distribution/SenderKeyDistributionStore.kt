package com.ivor.kriptex.deliverypolicy.group.senderkey.distribution

import com.ivor.kriptex.deliverypolicy.group.GroupId
import com.ivor.kriptex.deliverypolicy.persistence.PersistedSenderKeyDistributionPending
import com.ivor.kriptex.deliverypolicy.persistence.PersistedSenderKeyDistributionSnapshot
import com.ivor.kriptex.deliverypolicy.persistence.PersistedSenderKeyDistributionState

interface SenderKeyDistributionStore {
    fun getState(groupId: GroupId, senderIdentityPublicKey: ByteArray): LocalDistributionState?

    fun putState(state: LocalDistributionState)

    fun markPending(pending: PendingDistribution)

    fun pendingByMessageId(messageId: String): PendingDistribution?

    /** Read-only view used for idempotent planning. */
    fun listPending(): List<PendingDistribution>

    fun removePending(messageId: String)

    fun snapshot(): PersistedSenderKeyDistributionSnapshot

    fun restore(snapshot: PersistedSenderKeyDistributionSnapshot)
}

data class LocalDistributionState(
    val groupId: GroupId,
    /** Local sender identity key (Ed25519, 32 bytes). */
    val senderIdentityPublicKey: ByteArray,
    val currentSenderKeyId: Long,
    val deliveredRecipientIdentityPublicKeys: Set<ByteArray>,
) {
    init {
        require(senderIdentityPublicKey.size == 32) { "sender_identity_key_must_be_32_bytes" }
        require(currentSenderKeyId > 0) { "non_positive_sender_key_id" }
    }
}

data class PendingDistribution(
    val messageId: String,
    val groupId: GroupId,
    val senderIdentityPublicKey: ByteArray,
    val senderKeyId: Long,
    val recipientIdentityPublicKey: ByteArray,
) {
    init {
        require(messageId.isNotEmpty()) { "empty_message_id" }
        require(senderIdentityPublicKey.size == 32) { "sender_identity_key_must_be_32_bytes" }
        require(recipientIdentityPublicKey.size == 32) { "recipient_identity_key_must_be_32_bytes" }
        require(senderKeyId > 0) { "non_positive_sender_key_id" }
    }
}

class InMemorySenderKeyDistributionStore : SenderKeyDistributionStore {

    private data class ByteKey(val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean = other is ByteKey && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    private data class CompositeKey(val groupId: GroupId, val sender: ByteKey)

    private val states = LinkedHashMap<CompositeKey, LocalDistributionState>()
    private val pendingByMessageId = LinkedHashMap<String, PendingDistribution>()

    @Synchronized
    override fun getState(groupId: GroupId, senderIdentityPublicKey: ByteArray): LocalDistributionState? {
        return states[CompositeKey(groupId, ByteKey(senderIdentityPublicKey))]
    }

    @Synchronized
    override fun putState(state: LocalDistributionState) {
        states[CompositeKey(state.groupId, ByteKey(state.senderIdentityPublicKey.copyOf()))] = state.copy(
            senderIdentityPublicKey = state.senderIdentityPublicKey.copyOf(),
            deliveredRecipientIdentityPublicKeys = state.deliveredRecipientIdentityPublicKeys.map { it.copyOf() }.toSet(),
        )
    }

    @Synchronized
    override fun markPending(pending: PendingDistribution) {
        pendingByMessageId[pending.messageId] = pending.copy(
            senderIdentityPublicKey = pending.senderIdentityPublicKey.copyOf(),
            recipientIdentityPublicKey = pending.recipientIdentityPublicKey.copyOf(),
        )
    }

    @Synchronized
    override fun pendingByMessageId(messageId: String): PendingDistribution? = pendingByMessageId[messageId]

    @Synchronized
    override fun listPending(): List<PendingDistribution> = pendingByMessageId.values.map { it.copy() }

    @Synchronized
    override fun removePending(messageId: String) {
        pendingByMessageId.remove(messageId)
    }

    @Synchronized
    override fun snapshot(): PersistedSenderKeyDistributionSnapshot {
        val stateList = states.values.map { s ->
            PersistedSenderKeyDistributionState(
                groupId = s.groupId.copyBytes(),
                senderIdentityPublicKey = s.senderIdentityPublicKey.copyOf(),
                currentSenderKeyId = s.currentSenderKeyId,
                deliveredRecipientIdentityPublicKeys = s.deliveredRecipientIdentityPublicKeys.map { it.copyOf() },
            )
        }
        val pendingList = pendingByMessageId.values.map { p ->
            PersistedSenderKeyDistributionPending(
                messageId = p.messageId,
                groupId = p.groupId.copyBytes(),
                senderIdentityPublicKey = p.senderIdentityPublicKey.copyOf(),
                senderKeyId = p.senderKeyId,
                recipientIdentityPublicKey = p.recipientIdentityPublicKey.copyOf(),
            )
        }
        return PersistedSenderKeyDistributionSnapshot(
            capturedAtElapsedMs = 0L,
            states = stateList,
            pending = pendingList,
        )
    }

    @Synchronized
    override fun restore(snapshot: PersistedSenderKeyDistributionSnapshot) {
        states.clear()
        pendingByMessageId.clear()

        snapshot.states.forEach { s ->
            val groupId = GroupId(s.groupId)
            putState(
                LocalDistributionState(
                    groupId = groupId,
                    senderIdentityPublicKey = s.senderIdentityPublicKey.copyOf(),
                    currentSenderKeyId = s.currentSenderKeyId,
                    deliveredRecipientIdentityPublicKeys = s.deliveredRecipientIdentityPublicKeys.map { it.copyOf() }.toSet(),
                ),
            )
        }

        snapshot.pending.forEach { p ->
            markPending(
                PendingDistribution(
                    messageId = p.messageId,
                    groupId = GroupId(p.groupId),
                    senderIdentityPublicKey = p.senderIdentityPublicKey.copyOf(),
                    senderKeyId = p.senderKeyId,
                    recipientIdentityPublicKey = p.recipientIdentityPublicKey.copyOf(),
                ),
            )
        }
    }
}

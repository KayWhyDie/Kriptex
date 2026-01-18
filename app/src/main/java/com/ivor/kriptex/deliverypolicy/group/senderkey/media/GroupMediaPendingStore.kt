package com.ivor.kriptex.deliverypolicy.group.senderkey.media

import com.ivor.kriptex.deliverypolicy.group.GroupId
import com.ivor.kriptex.deliverypolicy.persistence.PersistedGroupMediaPendingSnapshot
import com.ivor.kriptex.deliverypolicy.persistence.PersistedPendingGroupMediaKeyDistribution

/**
 * Stores group media key distribution messages that could not yet be decrypted
 * due to missing sender key state.
 */
interface GroupMediaPendingStore {
    fun putIfAbsent(pending: PendingGroupMediaKeyDistribution)

    fun remove(messageId: String): Boolean

    fun listFor(groupId: GroupId, senderIdentityPublicKey: ByteArray): List<PendingGroupMediaKeyDistribution>

    fun snapshot(capturedAtElapsedMs: Long): PersistedGroupMediaPendingSnapshot

    fun restore(snapshot: PersistedGroupMediaPendingSnapshot)
}

data class PendingGroupMediaKeyDistribution(
    val messageId: String,
    val conversationId: String,
    val createdAtElapsedMs: Long,
    val groupId: GroupId,
    val senderIdentityPublicKey: ByteArray,
    val senderKeyId: Long,
    val counter: Long,
    val mediaId: String,
    val ciphertext: ByteArray,
) {
    init {
        require(messageId.isNotEmpty()) { "empty_message_id" }
        require(conversationId.isNotEmpty()) { "empty_conversation_id" }
        require(senderIdentityPublicKey.size == 32) { "sender_identity_key_must_be_32_bytes" }
        require(senderKeyId > 0) { "non_positive_sender_key_id" }
        require(counter > 0) { "non_positive_counter" }
        require(mediaId.isNotEmpty()) { "empty_media_id" }
        require(ciphertext.isNotEmpty()) { "missing_ciphertext" }
    }
}

class InMemoryGroupMediaPendingStore : GroupMediaPendingStore {

    private data class ByteKey(val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean = other is ByteKey && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    private data class CompositeKey(val groupId: GroupId, val sender: ByteKey)

    private val byMessageId = LinkedHashMap<String, PendingGroupMediaKeyDistribution>()

    @Synchronized
    override fun putIfAbsent(pending: PendingGroupMediaKeyDistribution) {
        if (byMessageId.containsKey(pending.messageId)) return
        byMessageId[pending.messageId] = pending.copy(
            senderIdentityPublicKey = pending.senderIdentityPublicKey.copyOf(),
            ciphertext = pending.ciphertext.copyOf(),
        )
    }

    @Synchronized
    override fun remove(messageId: String): Boolean = byMessageId.remove(messageId) != null

    @Synchronized
    override fun listFor(groupId: GroupId, senderIdentityPublicKey: ByteArray): List<PendingGroupMediaKeyDistribution> {
        val key = CompositeKey(groupId, ByteKey(senderIdentityPublicKey))
        return byMessageId.values
            .filter { CompositeKey(it.groupId, ByteKey(it.senderIdentityPublicKey)) == key }
            .sortedWith(compareBy({ it.senderKeyId }, { it.counter }, { it.messageId }))
            .map { it.copy(senderIdentityPublicKey = it.senderIdentityPublicKey.copyOf(), ciphertext = it.ciphertext.copyOf()) }
    }

    @Synchronized
    override fun snapshot(capturedAtElapsedMs: Long): PersistedGroupMediaPendingSnapshot {
        val pending = byMessageId.values.map { p ->
            PersistedPendingGroupMediaKeyDistribution(
                messageId = p.messageId,
                conversationId = p.conversationId,
                createdAtElapsedMs = p.createdAtElapsedMs,
                groupId = p.groupId.copyBytes(),
                senderIdentityPublicKey = p.senderIdentityPublicKey.copyOf(),
                senderKeyId = p.senderKeyId,
                counter = p.counter,
                mediaId = p.mediaId,
                ciphertext = p.ciphertext.copyOf(),
            )
        }
        return PersistedGroupMediaPendingSnapshot(capturedAtElapsedMs = capturedAtElapsedMs, pending = pending)
    }

    @Synchronized
    override fun restore(snapshot: PersistedGroupMediaPendingSnapshot) {
        byMessageId.clear()
        snapshot.pending.forEach { p ->
            val entry = PendingGroupMediaKeyDistribution(
                messageId = p.messageId,
                conversationId = p.conversationId,
                createdAtElapsedMs = p.createdAtElapsedMs,
                groupId = GroupId(p.groupId),
                senderIdentityPublicKey = p.senderIdentityPublicKey.copyOf(),
                senderKeyId = p.senderKeyId,
                counter = p.counter,
                mediaId = p.mediaId,
                ciphertext = p.ciphertext.copyOf(),
            )
            byMessageId[entry.messageId] = entry
        }
    }
}

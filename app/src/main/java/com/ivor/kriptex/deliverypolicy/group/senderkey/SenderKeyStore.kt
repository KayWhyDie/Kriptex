package com.ivor.kriptex.deliverypolicy.group.senderkey

import com.ivor.kriptex.deliverypolicy.group.GroupId
import com.ivor.kriptex.deliverypolicy.persistence.PersistedSenderKeyStoreSnapshot
import com.ivor.kriptex.deliverypolicy.persistence.PersistedSkippedSenderMessageKey
import com.ivor.kriptex.deliverypolicy.persistence.PersistedSenderKeyState

interface SenderKeyStore {
    fun put(state: SenderKeyState)

    fun get(groupId: GroupId, senderIdentityPublicKey: ByteArray): SenderKeyState?

    fun snapshot(): PersistedSenderKeyStoreSnapshot

    fun restore(snapshot: PersistedSenderKeyStoreSnapshot)
}

class InMemorySenderKeyStore : SenderKeyStore {

    private data class ByteKey(val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean = other is ByteKey && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    private data class CompositeKey(val groupId: GroupId, val sender: ByteKey)

    private val states = LinkedHashMap<CompositeKey, SenderKeyState>()

    @Synchronized
    override fun put(state: SenderKeyState) {
        val key = CompositeKey(state.groupId, ByteKey(state.senderIdentityPublicKey.copyOf()))
        states[key] = state.copy(
            senderIdentityPublicKey = state.senderIdentityPublicKey.copyOf(),
            chainKey = state.chainKey.copyOf(),
            skippedMessageKeys = state.skippedMessageKeys.mapValues { (_, mk) -> mk.copyOf() },
        )
    }

    @Synchronized
    override fun get(groupId: GroupId, senderIdentityPublicKey: ByteArray): SenderKeyState? {
        return states[CompositeKey(groupId, ByteKey(senderIdentityPublicKey))]
    }

    @Synchronized
    override fun snapshot(): PersistedSenderKeyStoreSnapshot {
        val persisted = states.values.map {
            PersistedSenderKeyState(
                groupId = it.groupId.copyBytes(),
                senderIdentityPublicKey = it.senderIdentityPublicKey.copyOf(),
                senderKeyId = it.senderKeyId,
                chainKey = it.chainKey.copyOf(),
                nextCounter = it.nextCounter,
                skippedMessageKeys = it.skippedMessageKeys.entries
                    .sortedBy { (counter, _) -> counter }
                    .map { (counter, mk) -> PersistedSkippedSenderMessageKey(counter = counter, messageKey = mk.copyOf()) },
            )
        }
        return PersistedSenderKeyStoreSnapshot(capturedAtElapsedMs = 0L, states = persisted)
    }

    @Synchronized
    override fun restore(snapshot: PersistedSenderKeyStoreSnapshot) {
        states.clear()
        snapshot.states.forEach { s ->
            val groupId = GroupId(s.groupId)

            val skipped: Map<Long, ByteArray> = when (snapshot.version) {
                1 -> emptyMap()
                else -> s.skippedMessageKeys.associate { it.counter to it.messageKey.copyOf() }
            }
            put(
                SenderKeyState(
                    groupId = groupId,
                    senderIdentityPublicKey = s.senderIdentityPublicKey.copyOf(),
                    senderKeyId = s.senderKeyId,
                    chainKey = s.chainKey.copyOf(),
                    nextCounter = s.nextCounter,
                    skippedMessageKeys = skipped,
                ),
            )
        }
    }
}

package com.ivor.kriptex.deliverypolicy.group.senderkey.media

import com.ivor.kriptex.deliverypolicy.group.GroupId
import com.ivor.kriptex.deliverypolicy.persistence.PersistedGroupMediaKeyEntry
import com.ivor.kriptex.deliverypolicy.persistence.PersistedGroupMediaKeyStoreSnapshot

interface GroupMediaKeyStore {
    fun putIfAbsent(entry: GroupMediaKeyEntry): PutResult

    fun get(mediaId: String): GroupMediaKeyEntry?

    fun snapshot(capturedAtElapsedMs: Long): PersistedGroupMediaKeyStoreSnapshot

    fun restore(snapshot: PersistedGroupMediaKeyStoreSnapshot)

    sealed interface PutResult {
        data object Stored : PutResult
        data object AlreadyStoredSame : PutResult
        data class Conflict(val reason: String) : PutResult
    }
}

data class GroupMediaKeyEntry(
    val mediaId: String,
    val groupId: GroupId,
    val senderIdentityPublicKey: ByteArray,
    val senderKeyId: Long,
    val counter: Long,
    val mediaKey: ByteArray,
) {
    init {
        require(mediaId.isNotEmpty()) { "empty_media_id" }
        require(senderIdentityPublicKey.size == 32) { "sender_identity_key_must_be_32_bytes" }
        require(senderKeyId > 0) { "non_positive_sender_key_id" }
        require(counter > 0) { "non_positive_counter" }
        require(mediaKey.size == 32) { "media_key_must_be_32_bytes" }
    }
}

class InMemoryGroupMediaKeyStore : GroupMediaKeyStore {

    private data class ByteKey(val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean = other is ByteKey && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    private val byMediaId = LinkedHashMap<String, GroupMediaKeyEntry>()

    @Synchronized
    override fun putIfAbsent(entry: GroupMediaKeyEntry): GroupMediaKeyStore.PutResult {
        val existing = byMediaId[entry.mediaId]
        if (existing == null) {
            byMediaId[entry.mediaId] = entry.copy(
                senderIdentityPublicKey = entry.senderIdentityPublicKey.copyOf(),
                mediaKey = entry.mediaKey.copyOf(),
            )
            return GroupMediaKeyStore.PutResult.Stored
        }

        val sameBinding =
            existing.groupId == entry.groupId &&
                existing.senderKeyId == entry.senderKeyId &&
                existing.counter == entry.counter &&
                existing.senderIdentityPublicKey.contentEquals(entry.senderIdentityPublicKey)

        if (sameBinding && existing.mediaKey.contentEquals(entry.mediaKey)) {
            return GroupMediaKeyStore.PutResult.AlreadyStoredSame
        }

        return GroupMediaKeyStore.PutResult.Conflict("media_id_conflict")
    }

    @Synchronized
    override fun get(mediaId: String): GroupMediaKeyEntry? = byMediaId[mediaId]

    @Synchronized
    override fun snapshot(capturedAtElapsedMs: Long): PersistedGroupMediaKeyStoreSnapshot {
        val entries = byMediaId.values.map {
            PersistedGroupMediaKeyEntry(
                mediaId = it.mediaId,
                groupId = it.groupId.copyBytes(),
                senderIdentityPublicKey = it.senderIdentityPublicKey.copyOf(),
                senderKeyId = it.senderKeyId,
                counter = it.counter,
                mediaKey = it.mediaKey.copyOf(),
            )
        }
        return PersistedGroupMediaKeyStoreSnapshot(capturedAtElapsedMs = capturedAtElapsedMs, entries = entries)
    }

    @Synchronized
    override fun restore(snapshot: PersistedGroupMediaKeyStoreSnapshot) {
        byMediaId.clear()
        snapshot.entries.forEach { e ->
            val entry = GroupMediaKeyEntry(
                mediaId = e.mediaId,
                groupId = GroupId(e.groupId),
                senderIdentityPublicKey = e.senderIdentityPublicKey.copyOf(),
                senderKeyId = e.senderKeyId,
                counter = e.counter,
                mediaKey = e.mediaKey.copyOf(),
            )
            byMediaId[entry.mediaId] = entry
        }
    }
}

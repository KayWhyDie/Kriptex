package com.ivor.kriptex.deliverypolicy.group

import java.security.MessageDigest

/**
 * Stable opaque group identifier.
 *
 * To avoid leaking application-visible room ids on the wire, we derive a 32-byte id from
 * the room/conversation id using SHA-256.
 */
class GroupId(val bytes: ByteArray) {
    init {
        require(bytes.size == 32) { "group_id_must_be_32_bytes" }
    }

    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean {
        return other is GroupId && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "GroupId(${bytes.size}b)"

    companion object {
        fun fromConversationId(conversationId: String): GroupId {
            val md = MessageDigest.getInstance("SHA-256")
            md.update("KPX-GROUP-ID".encodeToByteArray())
            md.update(0)
            md.update(conversationId.encodeToByteArray())
            return GroupId(md.digest())
        }
    }
}

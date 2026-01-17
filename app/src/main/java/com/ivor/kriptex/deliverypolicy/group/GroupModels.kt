package com.ivor.kriptex.deliverypolicy.group

/**
 * Group definition.
 *
 * Membership is an explicit list of Ed25519 identity public keys (32 bytes).
 */
data class GroupDefinition(
    val conversationId: String,
    val groupId: GroupId = GroupId.fromConversationId(conversationId),
    val memberIdentityPublicKeys: List<ByteArray>,
) {
    init {
        require(conversationId.isNotEmpty()) { "empty_conversation_id" }
        require(memberIdentityPublicKeys.isNotEmpty()) { "empty_membership" }
        memberIdentityPublicKeys.forEach { require(it.size == 32) { "member_identity_key_must_be_32_bytes" } }
    }

    fun isMember(identityPublicKey: ByteArray): Boolean {
        return memberIdentityPublicKeys.any { it.contentEquals(identityPublicKey) }
    }
}

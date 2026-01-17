package com.ivor.kriptex.deliverypolicy.group

import com.ivor.kriptex.deliverypolicy.persistence.PersistedGroupStoreSnapshot
import com.ivor.kriptex.deliverypolicy.persistence.PersistedGroupStoreState

interface GroupStore {
    fun put(group: GroupDefinition)

    fun getById(groupId: GroupId): GroupDefinition?

    fun getByConversationId(conversationId: String): GroupDefinition? = getById(GroupId.fromConversationId(conversationId))

    fun snapshot(): PersistedGroupStoreSnapshot

    fun restore(snapshot: PersistedGroupStoreSnapshot)
}

class InMemoryGroupStore : GroupStore {

    private val byGroupId = LinkedHashMap<GroupId, GroupDefinition>()

    @Synchronized
    override fun put(group: GroupDefinition) {
        byGroupId[group.groupId] = group.copy(
            memberIdentityPublicKeys = group.memberIdentityPublicKeys.map { it.copyOf() },
        )
    }

    @Synchronized
    override fun getById(groupId: GroupId): GroupDefinition? {
        return byGroupId[groupId]
    }

    @Synchronized
    override fun snapshot(): PersistedGroupStoreSnapshot {
        val states = byGroupId.values.map {
            PersistedGroupStoreState(
                conversationId = it.conversationId,
                groupId = it.groupId.copyBytes(),
                memberIdentityPublicKeys = it.memberIdentityPublicKeys.map { k -> k.copyOf() },
            )
        }
        return PersistedGroupStoreSnapshot(capturedAtElapsedMs = 0L, groups = states)
    }

    @Synchronized
    override fun restore(snapshot: PersistedGroupStoreSnapshot) {
        byGroupId.clear()
        snapshot.groups.forEach { g ->
            val groupId = GroupId(g.groupId)
            byGroupId[groupId] = GroupDefinition(
                conversationId = g.conversationId,
                groupId = groupId,
                memberIdentityPublicKeys = g.memberIdentityPublicKeys.map { it.copyOf() },
            )
        }
    }
}

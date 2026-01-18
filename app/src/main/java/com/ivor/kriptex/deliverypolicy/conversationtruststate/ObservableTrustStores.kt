package com.ivor.kriptex.deliverypolicy.conversationtruststate

import com.ivor.kriptex.deliverypolicy.group.GroupDefinition
import com.ivor.kriptex.deliverypolicy.group.GroupId
import com.ivor.kriptex.deliverypolicy.group.GroupStore
import com.ivor.kriptex.deliverypolicy.group.senderkey.SenderKeyState
import com.ivor.kriptex.deliverypolicy.group.senderkey.SenderKeyStore
import com.ivor.kriptex.deliverypolicy.group.senderkey.distribution.LocalDistributionState
import com.ivor.kriptex.deliverypolicy.group.senderkey.distribution.PendingDistribution
import com.ivor.kriptex.deliverypolicy.group.senderkey.distribution.SenderKeyDistributionStore
import com.ivor.kriptex.deliverypolicy.persistence.PersistedConversationTrustSnapshot
import com.ivor.kriptex.deliverypolicy.session.InMemorySessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

class ObservableIdentityKeyStore(private val delegate: IdentityKeyStore) : IdentityKeyStore {
    private val _invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    val invalidations: Flow<Unit> = _invalidations

    private fun changed() {
        _invalidations.tryEmit(Unit)
    }

    override fun getPeerIdentityPublicKey(peerId: String): ByteArray? = delegate.getPeerIdentityPublicKey(peerId)

    override fun putPeerIdentityPublicKey(peerId: String, identityPublicKey: ByteArray) {
        delegate.putPeerIdentityPublicKey(peerId, identityPublicKey)
        changed()
    }

    override fun snapshot(): PersistedIdentityKeyStoreSnapshot = delegate.snapshot()

    override fun restore(snapshot: PersistedIdentityKeyStoreSnapshot) {
        delegate.restore(snapshot)
        changed()
    }
}

class ObservableConversationTrustStore(private val delegate: ConversationTrustStore) : ConversationTrustStore {
    private val _invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    val invalidations: Flow<Unit> = _invalidations

    private fun changed() {
        _invalidations.tryEmit(Unit)
    }

    override fun isExplicitlyVerified(conversationId: String): Boolean = delegate.isExplicitlyVerified(conversationId)

    override fun setExplicitlyVerified(conversationId: String, verified: Boolean) {
        delegate.setExplicitlyVerified(conversationId, verified)
        changed()
    }

    override fun acknowledgedIssueKeys(conversationId: String): Set<String> = delegate.acknowledgedIssueKeys(conversationId)

    override fun acknowledgeIssueKeys(conversationId: String, keys: Set<String>) {
        delegate.acknowledgeIssueKeys(conversationId, keys)
        changed()
    }

    override fun snapshot(capturedAtElapsedMs: Long): PersistedConversationTrustSnapshot = delegate.snapshot(capturedAtElapsedMs)

    override fun restore(snapshot: PersistedConversationTrustSnapshot) {
        delegate.restore(snapshot)
        changed()
    }
}

class ObservableGroupStore(private val delegate: GroupStore) : GroupStore {
    private val _invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    val invalidations: Flow<Unit> = _invalidations

    private fun changed() {
        _invalidations.tryEmit(Unit)
    }

    override fun put(group: GroupDefinition) {
        delegate.put(group)
        changed()
    }

    override fun getById(groupId: GroupId): GroupDefinition? = delegate.getById(groupId)

    override fun snapshot() = delegate.snapshot()

    override fun restore(snapshot: com.ivor.kriptex.deliverypolicy.persistence.PersistedGroupStoreSnapshot) {
        delegate.restore(snapshot)
        changed()
    }
}

class ObservableSenderKeyStore(private val delegate: SenderKeyStore) : SenderKeyStore {
    private val _invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    val invalidations: Flow<Unit> = _invalidations

    private fun changed() {
        _invalidations.tryEmit(Unit)
    }

    override fun put(state: SenderKeyState) {
        delegate.put(state)
        changed()
    }

    override fun get(groupId: GroupId, senderIdentityPublicKey: ByteArray): SenderKeyState? = delegate.get(groupId, senderIdentityPublicKey)

    override fun snapshot() = delegate.snapshot()

    override fun restore(snapshot: com.ivor.kriptex.deliverypolicy.persistence.PersistedSenderKeyStoreSnapshot) {
        delegate.restore(snapshot)
        changed()
    }
}

class ObservableSenderKeyDistributionStore(private val delegate: SenderKeyDistributionStore) : SenderKeyDistributionStore {
    private val _invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    val invalidations: Flow<Unit> = _invalidations

    private fun changed() {
        _invalidations.tryEmit(Unit)
    }

    override fun getState(groupId: GroupId, senderIdentityPublicKey: ByteArray): LocalDistributionState? {
        return delegate.getState(groupId, senderIdentityPublicKey)
    }

    override fun putState(state: LocalDistributionState) {
        delegate.putState(state)
        changed()
    }

    override fun markPending(pending: PendingDistribution) {
        delegate.markPending(pending)
        changed()
    }

    override fun pendingByMessageId(messageId: String): PendingDistribution? = delegate.pendingByMessageId(messageId)

    override fun listPending(): List<PendingDistribution> = delegate.listPending()

    override fun removePending(messageId: String) {
        delegate.removePending(messageId)
        changed()
    }

    override fun snapshot() = delegate.snapshot()

    override fun restore(snapshot: com.ivor.kriptex.deliverypolicy.persistence.PersistedSenderKeyDistributionSnapshot) {
        delegate.restore(snapshot)
        changed()
    }
}

class ObservableSessionStore(private val delegate: InMemorySessionStore) {
    private val _invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    val invalidations: Flow<Unit> = _invalidations

    private fun changed() {
        _invalidations.tryEmit(Unit)
    }

    fun findEstablished(peerId: String, conversationId: String) = delegate.findEstablished(peerId, conversationId)

    fun snapshot() = delegate.snapshot()

    fun restore(snapshot: com.ivor.kriptex.deliverypolicy.persistence.PersistedSessionStoreSnapshot) {
        delegate.restore(snapshot)
        changed()
    }

    // For tests: allow manual invalidation for in-memory mutations outside this wrapper.
    fun notifyChanged() = changed()
}

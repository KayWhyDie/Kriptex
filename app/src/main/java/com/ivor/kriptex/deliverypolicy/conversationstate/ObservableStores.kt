package com.ivor.kriptex.deliverypolicy.conversationstate

import com.ivor.kriptex.deliverypolicy.group.GroupDefinition
import com.ivor.kriptex.deliverypolicy.group.GroupId
import com.ivor.kriptex.deliverypolicy.group.GroupStore
import com.ivor.kriptex.deliverypolicy.group.senderkey.SenderKeyState
import com.ivor.kriptex.deliverypolicy.group.senderkey.SenderKeyStore
import com.ivor.kriptex.deliverypolicy.ledger.ConversationDeliveryLedger
import com.ivor.kriptex.deliverypolicy.ledger.ConversationLedgerView
import com.ivor.kriptex.deliverypolicy.ledger.MessageLifecycle
import com.ivor.kriptex.deliverypolicy.messagestore.AppendResult
import com.ivor.kriptex.deliverypolicy.messagestore.ConversationMessage
import com.ivor.kriptex.deliverypolicy.messagestore.ConversationMessageStore
import com.ivor.kriptex.deliverypolicy.messagestore.ConversationTimeline
import com.ivor.kriptex.deliverypolicy.persistence.PersistedConversationDeliveryLedgerSnapshot
import com.ivor.kriptex.deliverypolicy.persistence.PersistedConversationMessageStoreSnapshot
import com.ivor.kriptex.deliverypolicy.persistence.PersistedGroupStoreSnapshot
import com.ivor.kriptex.deliverypolicy.persistence.PersistedSenderKeyStoreSnapshot
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Thin wrappers that provide invalidation signals when existing stores are mutated.
 * They do not change behavior or persist anything.
 */

class ObservableConversationMessageStore(private val delegate: ConversationMessageStore) : ConversationMessageStore {

    private val _invalidations = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val invalidations: Flow<Unit> = _invalidations

    private fun changed() {
        _invalidations.tryEmit(Unit)
    }

    override fun appendOutbound(messageId: String, conversationId: String, payload: ByteArray, elapsedMs: Long): AppendResult {
        val res = delegate.appendOutbound(messageId, conversationId, payload, elapsedMs)
        changed()
        return res
    }

    override fun appendInbound(messageId: String, conversationId: String, payload: ByteArray, elapsedMs: Long): AppendResult {
        val res = delegate.appendInbound(messageId, conversationId, payload, elapsedMs)
        changed()
        return res
    }

    override fun markSent(messageId: String, elapsedMs: Long): Boolean {
        val res = delegate.markSent(messageId, elapsedMs)
        changed()
        return res
    }

    override fun markReceived(messageId: String, elapsedMs: Long): Boolean {
        val res = delegate.markReceived(messageId, elapsedMs)
        changed()
        return res
    }

    override fun markAcked(messageId: String, elapsedMs: Long): Boolean {
        val res = delegate.markAcked(messageId, elapsedMs)
        changed()
        return res
    }

    override fun markFailed(messageId: String, elapsedMs: Long, reason: String?): Boolean {
        val res = delegate.markFailed(messageId, elapsedMs, reason)
        changed()
        return res
    }

    override fun conversationTimeline(conversationId: String): ConversationTimeline = delegate.conversationTimeline(conversationId)

    override fun message(messageId: String): ConversationMessage? = delegate.message(messageId)

    override fun snapshot(): PersistedConversationMessageStoreSnapshot = delegate.snapshot()

    override fun restore(snapshot: PersistedConversationMessageStoreSnapshot) {
        delegate.restore(snapshot)
        changed()
    }

    override fun close() {
        delegate.close()
        changed()
    }
}

class ObservableConversationDeliveryLedger(private val delegate: ConversationDeliveryLedger) : ConversationDeliveryLedger {

    private val _invalidations = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val invalidations: Flow<Unit> = _invalidations

    private fun changed() {
        _invalidations.tryEmit(Unit)
    }

    override fun recordEnqueued(messageId: String, conversationId: String) {
        delegate.recordEnqueued(messageId, conversationId)
        changed()
    }

    override fun recordSent(messageId: String) {
        delegate.recordSent(messageId)
        changed()
    }

    override fun recordReceived(messageId: String, conversationId: String) {
        delegate.recordReceived(messageId, conversationId)
        changed()
    }

    override fun recordAcked(messageId: String) {
        delegate.recordAcked(messageId)
        changed()
    }

    override fun recordTerminalFailure(messageId: String, reason: String?) {
        delegate.recordTerminalFailure(messageId, reason)
        changed()
    }

    override fun snapshot(): PersistedConversationDeliveryLedgerSnapshot = delegate.snapshot()

    override fun restore(snapshot: PersistedConversationDeliveryLedgerSnapshot) {
        delegate.restore(snapshot)
        changed()
    }

    override fun conversationView(conversationId: String): ConversationLedgerView = delegate.conversationView(conversationId)

    override fun messageState(messageId: String): MessageLifecycle? = delegate.messageState(messageId)
}

class ObservableGroupStore(private val delegate: GroupStore) : GroupStore {

    private val _invalidations = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val invalidations: Flow<Unit> = _invalidations

    private fun changed() {
        _invalidations.tryEmit(Unit)
    }

    override fun put(group: GroupDefinition) {
        delegate.put(group)
        changed()
    }

    override fun getById(groupId: GroupId): GroupDefinition? = delegate.getById(groupId)

    override fun snapshot(): PersistedGroupStoreSnapshot = delegate.snapshot()

    override fun restore(snapshot: PersistedGroupStoreSnapshot) {
        delegate.restore(snapshot)
        changed()
    }
}

class ObservableSenderKeyStore(private val delegate: SenderKeyStore) : SenderKeyStore {

    private val _invalidations = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val invalidations: Flow<Unit> = _invalidations

    private fun changed() {
        _invalidations.tryEmit(Unit)
    }

    override fun put(state: SenderKeyState) {
        delegate.put(state)
        changed()
    }

    override fun get(groupId: GroupId, senderIdentityPublicKey: ByteArray): SenderKeyState? {
        return delegate.get(groupId, senderIdentityPublicKey)
    }

    override fun snapshot(): PersistedSenderKeyStoreSnapshot = delegate.snapshot()

    override fun restore(snapshot: PersistedSenderKeyStoreSnapshot) {
        delegate.restore(snapshot)
        changed()
    }
}

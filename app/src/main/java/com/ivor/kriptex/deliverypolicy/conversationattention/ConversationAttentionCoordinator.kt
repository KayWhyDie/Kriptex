package com.ivor.kriptex.deliverypolicy.conversationattention

import com.ivor.kriptex.deliverypolicy.conversationstate.ConversationSnapshot
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * Coordinates attention + notification decisions for conversations.
 *
 * - No protocol/crypto calls.
 * - No Android APIs (use [NotificationSink]).
 * - Restore-safe: the first snapshot observed per conversation is treated as baseline (no notify).
 */
class ConversationAttentionCoordinator(
    private val notificationSink: NotificationSink,
    private val debugTrace: ConversationAttentionDebugTrace = NoOpConversationAttentionDebugTrace,
) {

    private data class InternalState(
        var lifecycle: AppLifecycleState = AppLifecycleState.FOREGROUND,
        var visibleConversationId: String? = null,
        val lastSnapshotByConversation: LinkedHashMap<String, ConversationSnapshot> = LinkedHashMap(),
        val baselineSeen: HashSet<String> = HashSet(),
        val activeNotifications: HashSet<String> = HashSet(),
    )

    private val state = InternalState()

    private val _decisions = MutableSharedFlow<ConversationAttentionDecision>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Hot stream of decisions emitted by [observe]. */
    val decisions: Flow<ConversationAttentionDecision> = _decisions

    /**
     * Pure derivation of attention state from lifecycle + visible conversation id.
     */
    fun attentionStateFor(conversationId: String): ConversationAttentionState {
        return when (state.lifecycle) {
            AppLifecycleState.BACKGROUND -> ConversationAttentionState.BACKGROUND
            AppLifecycleState.FOREGROUND -> {
                if (state.visibleConversationId == conversationId) ConversationAttentionState.VISIBLE
                else ConversationAttentionState.FOREGROUND_BACKGROUND
            }
        }
    }

    /**
     * Update app lifecycle (foreground/background).
     * May emit cancellation decisions if the newly visible conversation should suppress notifications.
     */
    @Synchronized
    fun onAppLifecycle(newState: AppLifecycleState) {
        val previous = state.lifecycle
        state.lifecycle = newState

        // If we become foreground and have a visible conversation, ensure cancellation is applied.
        if (previous != newState) {
            val visible = state.visibleConversationId
            if (visible != null) {
                val snap = state.lastSnapshotByConversation[visible]
                if (snap != null) {
                    emitDecision(deriveDecision(snap, reasonOverride = "lifecycle_changed"))
                }
            }
        }
    }

    /**
     * Update which conversation is currently visible.
     * Emits decisions for the newly visible conversation (and cancels notifications if needed).
     */
    @Synchronized
    fun onVisibleConversationChanged(conversationId: String?) {
        val previous = state.visibleConversationId
        state.visibleConversationId = conversationId

        if (previous != conversationId) {
            val newVisible = conversationId
            if (newVisible != null) {
                val snap = state.lastSnapshotByConversation[newVisible]
                if (snap != null) {
                    emitDecision(deriveDecision(snap, reasonOverride = "conversation_became_visible"))
                }
            }
        }
    }

    /**
     * Observe a new snapshot for a conversation.
     * Restore-safe: first snapshot per conversation is baseline and will not trigger notify/unread.
     */
    @Synchronized
    fun onSnapshot(snapshot: ConversationSnapshot) {
        val conversationId = snapshot.conversationId
        val previous = state.lastSnapshotByConversation[conversationId]
        state.lastSnapshotByConversation[conversationId] = snapshot

        // Restore-safety: first snapshot per conversation establishes baseline.
        val baselineFirstTime = state.baselineSeen.add(conversationId)
        if (baselineFirstTime) {
            // Baseline emission: allow visibility-driven cancellation, but never notify or increment unread.
            emitDecision(deriveDecision(snapshot, forceSuppressNotify = true, forceSuppressUnread = true, reasonOverride = "baseline"))
            return
        }

        // Only react to meaningful transitions (monotonic lastActivity).
        val activityAdvanced = previous == null || snapshot.lastActivityTimestamp > previous.lastActivityTimestamp
        if (!activityAdvanced) {
            emitDecision(deriveDecision(snapshot, forceSuppressNotify = true, forceSuppressUnread = true, reasonOverride = "no_new_activity"))
            return
        }

        emitDecision(deriveDecision(snapshot, reasonOverride = "activity_advanced"))
    }

    /**
     * Convenience: wires flows and emits decisions into [decisions].
     */
    fun observe(
        snapshots: Flow<ConversationSnapshot>,
        appLifecycle: StateFlow<AppLifecycleState>,
        visibleConversationId: StateFlow<String?>,
    ): Flow<ConversationAttentionDecision> {
        return channelFlow {
            // Forward our internal decision bus to the returned flow.
            val forwardJob = launch(start = CoroutineStart.UNDISPATCHED) {
                decisions.collect { send(it) }
            }

            val lifecycleJob = launch(start = CoroutineStart.UNDISPATCHED) {
                appLifecycle.collect { onAppLifecycle(it) }
            }

            val visibleJob = launch(start = CoroutineStart.UNDISPATCHED) {
                visibleConversationId.collect { onVisibleConversationChanged(it) }
            }

            val snapshotsJob = launch(start = CoroutineStart.UNDISPATCHED) {
                snapshots.collect { onSnapshot(it) }
            }

            awaitClose {
                forwardJob.cancel()
                lifecycleJob.cancel()
                visibleJob.cancel()
                snapshotsJob.cancel()
            }
        }
    }

    private fun deriveDecision(
        snapshot: ConversationSnapshot,
        forceSuppressNotify: Boolean = false,
        forceSuppressUnread: Boolean = false,
        reasonOverride: String? = null,
    ): ConversationAttentionDecision {
        val cid = snapshot.conversationId
        val attention = attentionStateFor(cid)

        val (notificationDecision, unreadDecision, reason) = when (attention) {
            ConversationAttentionState.VISIBLE -> {
                val cancel = if (state.activeNotifications.contains(cid)) NotificationDecision.Cancel(cid) else NotificationDecision.None
                Triple(cancel, UnreadDecision.SUPPRESS, "visible")
            }

            ConversationAttentionState.FOREGROUND_BACKGROUND -> {
                // Foreground, but not visible: suppress user-facing notifications, but allow unread increments.
                Triple(NotificationDecision.None, UnreadDecision.INCREMENT, "foreground_not_visible")
            }

            ConversationAttentionState.BACKGROUND -> {
                Triple(NotificationDecision.Notify(cid, snapshot), UnreadDecision.INCREMENT, "background")
            }
        }

        val finalNotification = if (forceSuppressNotify && notificationDecision is NotificationDecision.Notify) {
            NotificationDecision.None
        } else {
            notificationDecision
        }
        val finalUnread = if (forceSuppressUnread) UnreadDecision.SUPPRESS else unreadDecision

        val finalReason = reasonOverride ?: reason

        debugTrace.onDecision(
            conversationId = cid,
            attentionState = attention,
            notification = when (finalNotification) {
                NotificationDecision.None -> "none"
                is NotificationDecision.Notify -> "notify"
                is NotificationDecision.Cancel -> "cancel"
            },
            unread = finalUnread.name.lowercase(),
            reason = finalReason,
            lastActivityTimestamp = snapshot.lastActivityTimestamp,
        )

        return ConversationAttentionDecision(
            conversationId = cid,
            attentionState = attention,
            snapshot = snapshot,
            notification = finalNotification,
            unread = finalUnread,
            reason = finalReason,
        )
    }

    private fun emitDecision(decision: ConversationAttentionDecision) {
        val cid = decision.conversationId

        when (val n = decision.notification) {
            NotificationDecision.None -> Unit

            is NotificationDecision.Notify -> {
                // Restore-safe: callers should not see Notify decisions on baseline/no_new_activity.
                notificationSink.showNotification(cid, n.snapshot)
                state.activeNotifications.add(cid)
                debugTrace.onNotify(cid, reason = decision.reason, lastActivityTimestamp = n.snapshot.lastActivityTimestamp)
            }

            is NotificationDecision.Cancel -> {
                notificationSink.cancelNotification(cid)
                state.activeNotifications.remove(cid)
                debugTrace.onCancel(cid, reason = decision.reason)
            }
        }

        _decisions.tryEmit(decision)
    }
}

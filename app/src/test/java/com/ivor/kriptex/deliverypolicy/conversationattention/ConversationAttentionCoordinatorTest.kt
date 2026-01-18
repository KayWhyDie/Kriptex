package com.ivor.kriptex.deliverypolicy.conversationattention

import com.ivor.kriptex.deliverypolicy.conversationstate.ConversationEncryptionStatus
import com.ivor.kriptex.deliverypolicy.conversationstate.ConversationHealth
import com.ivor.kriptex.deliverypolicy.conversationstate.ConversationSnapshot
import com.ivor.kriptex.deliverypolicy.conversationstate.ConversationType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingNotificationSink : NotificationSink {
    data class Shown(val conversationId: String, val lastActivity: Long)

    val shown = ArrayList<Shown>()
    val cancelled = ArrayList<String>()

    override fun showNotification(conversationId: String, snapshot: ConversationSnapshot) {
        shown.add(Shown(conversationId = conversationId, lastActivity = snapshot.lastActivityTimestamp))
    }

    override fun cancelNotification(conversationId: String) {
        cancelled.add(conversationId)
    }
}

class ConversationAttentionCoordinatorTest {

    private fun snapshot(conversationId: String, lastActivity: Long): ConversationSnapshot {
        return ConversationSnapshot(
            conversationId = conversationId,
            conversationType = ConversationType.ONE_TO_ONE,
            health = ConversationHealth.ACTIVE,
            encryptionStatus = ConversationEncryptionStatus.OK,
            pendingMessageCount = 0,
            lastActivityTimestamp = lastActivity,
        )
    }

    @Test
    fun baseline_in_background_does_not_notify_or_increment_unread() = runBlocking {
        val sink = RecordingNotificationSink()
        val coordinator = ConversationAttentionCoordinator(notificationSink = sink)

        val lifecycle = MutableStateFlow(AppLifecycleState.BACKGROUND)
        val visible = MutableStateFlow<String?>(null)
        val snapshots = MutableSharedFlow<ConversationSnapshot>(replay = 1, extraBufferCapacity = 8)

        val decisions = ArrayList<ConversationAttentionDecision>()
        val started = CompletableDeferred<Unit>()
        val job = launch {
            coordinator.observe(snapshots, lifecycle, visible)
            .onStart { started.complete(Unit) }
                .take(1)
                .toList(decisions)
        }

        withTimeout(2_000) { started.await() }
        snapshots.tryEmit(snapshot("c1", lastActivity = 100L))
        withTimeout(2_000) { job.join() }

        assertEquals(1, decisions.size)
        assertEquals(NotificationDecision.None, decisions[0].notification)
        assertEquals(UnreadDecision.SUPPRESS, decisions[0].unread)
        assertTrue(sink.shown.isEmpty())
    }

    @Test
    fun background_activity_advance_triggers_notify_after_baseline() = runBlocking {
        val sink = RecordingNotificationSink()
        val coordinator = ConversationAttentionCoordinator(notificationSink = sink)

        val lifecycle = MutableStateFlow(AppLifecycleState.BACKGROUND)
        val visible = MutableStateFlow<String?>(null)
        val snapshots = MutableSharedFlow<ConversationSnapshot>(replay = 1, extraBufferCapacity = 8)

        val decisions = ArrayList<ConversationAttentionDecision>()
        val started = CompletableDeferred<Unit>()
        val firstDecisionSeen = CompletableDeferred<Unit>()
        val job = launch {
            var seen = 0
            coordinator.observe(snapshots, lifecycle, visible)
            .onStart { started.complete(Unit) }
                .onEach {
                    seen += 1
                    if (seen == 1 && !firstDecisionSeen.isCompleted) firstDecisionSeen.complete(Unit)
                }
                .take(2)
                .toList(decisions)
        }

        withTimeout(2_000) { started.await() }
        snapshots.tryEmit(snapshot("c1", lastActivity = 100L)) // baseline
        withTimeout(2_000) { firstDecisionSeen.await() }

        snapshots.tryEmit(snapshot("c1", lastActivity = 110L)) // advance

        withTimeout(2_000) { job.join() }

        assertEquals(2, decisions.size)
        assertEquals(NotificationDecision.None, decisions[0].notification)
        assertTrue(decisions[1].notification is NotificationDecision.Notify)
        assertEquals(1, sink.shown.size)
        assertEquals("c1", sink.shown[0].conversationId)
        assertEquals(110L, sink.shown[0].lastActivity)
    }

    @Test
    fun entering_foreground_and_making_conversation_visible_cancels_active_notification() = runBlocking {
        val sink = RecordingNotificationSink()
        val coordinator = ConversationAttentionCoordinator(notificationSink = sink)

        val lifecycle = MutableStateFlow(AppLifecycleState.BACKGROUND)
        val visible = MutableStateFlow<String?>(null)
        val snapshots = MutableSharedFlow<ConversationSnapshot>(replay = 1, extraBufferCapacity = 8)

        val decisions = ArrayList<ConversationAttentionDecision>()
        val started = CompletableDeferred<Unit>()
        val baselineSeen = CompletableDeferred<Unit>()
        val notifySeen = CompletableDeferred<Unit>()
        val job = launch {
            var seen = 0
            coordinator.observe(snapshots, lifecycle, visible)
            .onStart { started.complete(Unit) }
                .onEach {
                    seen += 1
                    if (seen == 1 && !baselineSeen.isCompleted) baselineSeen.complete(Unit)
                    if (seen == 2 && it.notification is NotificationDecision.Notify && !notifySeen.isCompleted) notifySeen.complete(Unit)
                }
                .take(3)
                .toList(decisions)
        }

        withTimeout(2_000) { started.await() }
        snapshots.tryEmit(snapshot("c1", lastActivity = 100L)) // baseline
        withTimeout(2_000) { baselineSeen.await() }

        snapshots.tryEmit(snapshot("c1", lastActivity = 110L)) // notify
        withTimeout(2_000) { notifySeen.await() }

        // User opens the app and navigates to the conversation.
        lifecycle.value = AppLifecycleState.FOREGROUND
        visible.value = "c1"

        withTimeout(2_000) { job.join() }

        assertEquals(3, decisions.size)
        assertTrue(decisions[1].notification is NotificationDecision.Notify)
        assertTrue(decisions[2].notification is NotificationDecision.Cancel)
        assertEquals(listOf("c1"), sink.cancelled)
    }

    @Test
    fun foreground_not_visible_suppresses_notification_but_increments_unread_on_activity_advance() = runBlocking {
        val sink = RecordingNotificationSink()
        val coordinator = ConversationAttentionCoordinator(notificationSink = sink)

        val lifecycle = MutableStateFlow(AppLifecycleState.FOREGROUND)
        val visible = MutableStateFlow<String?>("other")
        val snapshots = MutableSharedFlow<ConversationSnapshot>(replay = 1, extraBufferCapacity = 8)

        val decisions = ArrayList<ConversationAttentionDecision>()
        val started = CompletableDeferred<Unit>()
        val baselineSeen = CompletableDeferred<Unit>()
        val job = launch {
            var seen = 0
            coordinator.observe(snapshots, lifecycle, visible)
            .onStart { started.complete(Unit) }
                .onEach {
                    seen += 1
                    if (seen == 1 && !baselineSeen.isCompleted) baselineSeen.complete(Unit)
                }
                .take(2)
                .toList(decisions)
        }

        withTimeout(2_000) { started.await() }
        snapshots.tryEmit(snapshot("c1", lastActivity = 100L)) // baseline
        withTimeout(2_000) { baselineSeen.await() }

        snapshots.tryEmit(snapshot("c1", lastActivity = 110L)) // advance

        withTimeout(2_000) { job.join() }

        assertEquals(2, decisions.size)
        assertEquals(NotificationDecision.None, decisions[1].notification)
        assertEquals(UnreadDecision.INCREMENT, decisions[1].unread)
        assertTrue(sink.shown.isEmpty())
    }
}

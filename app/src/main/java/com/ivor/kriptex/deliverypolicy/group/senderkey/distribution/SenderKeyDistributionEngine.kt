package com.ivor.kriptex.deliverypolicy.group.senderkey.distribution

import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.MonotonicClock
import com.ivor.kriptex.deliverypolicy.group.GroupId
import com.ivor.kriptex.deliverypolicy.group.GroupStore
import com.ivor.kriptex.deliverypolicy.group.senderkey.SenderKeyState
import com.ivor.kriptex.deliverypolicy.group.senderkey.SenderKeyStore
import com.ivor.kriptex.deliverypolicy.protocol.SenderKeyDistributionMessage
import java.security.SecureRandom

/**
 * Sender Key Distribution Protocol (control plane).
 *
 * Coordinates:
 * - sender key generation/rotation for (groupId, localIdentity)
 * - distribution planning to current group members using existing 1:1 secure sessions
 * - delivery tracking via ACKs (Protocol-level ACKs)
 * - inbound distribution validation (membership + sender identity binding)
 */
class SenderKeyDistributionEngine(
    private val localIdentityPublicKey: ByteArray,
    private val groupStore: GroupStore,
    private val senderKeyStore: SenderKeyStore,
    private val distributionStore: SenderKeyDistributionStore,
    private val random: SecureRandom = SecureRandom(),
    private val clock: Clock = MonotonicClock,
    private val debug: SenderKeyDistributionDebugTrace = SenderKeyDistributionDebugTrace.NO_OP,
) {
    init {
        require(localIdentityPublicKey.size == 32) { "local_identity_key_must_be_32_bytes" }
    }

    data class PlannedDistribution(
        val recipientIdentityPublicKey: ByteArray,
        val message: SenderKeyDistributionMessage,
    )

    data class InboundApplyResult(
        val accepted: Boolean,
        val reason: String? = null,
    )

    fun getOrCreateLocalSenderKey(groupId: GroupId): SenderKeyState {
        val existing = senderKeyStore.get(groupId, localIdentityPublicKey)
        if (existing != null) return existing

        val keyId = 1L
        val chainKey = ByteArray(32)
        random.nextBytes(chainKey)

        val state = SenderKeyState(
            groupId = groupId,
            senderIdentityPublicKey = localIdentityPublicKey.copyOf(),
            senderKeyId = keyId,
            chainKey = chainKey,
            nextCounter = 1L,
        )
        senderKeyStore.put(state)

        distributionStore.putState(
            LocalDistributionState(
                groupId = groupId,
                senderIdentityPublicKey = localIdentityPublicKey.copyOf(),
                currentSenderKeyId = keyId,
                deliveredRecipientIdentityPublicKeys = emptySet(),
            ),
        )

        debug.onKeyGenerated(groupId, keyId, clock.nowMs())
        return state
    }

    fun rotateLocalSenderKey(groupId: GroupId, reason: String) {
        val existing = getOrCreateLocalSenderKey(groupId)
        val oldKeyId = existing.senderKeyId
        val newKeyId = oldKeyId + 1

        val chainKey = ByteArray(32)
        random.nextBytes(chainKey)

        senderKeyStore.put(
            existing.copy(
                senderKeyId = newKeyId,
                chainKey = chainKey,
                nextCounter = 1L,
            ),
        )

        // Reset delivery tracking for the new key id.
        distributionStore.putState(
            LocalDistributionState(
                groupId = groupId,
                senderIdentityPublicKey = localIdentityPublicKey.copyOf(),
                currentSenderKeyId = newKeyId,
                deliveredRecipientIdentityPublicKeys = emptySet(),
            ),
        )

        debug.onKeyRotated(groupId, oldKeyId, newKeyId, reason, clock.nowMs())
    }

    /**
     * Plans distribution messages to current group members.
     *
     * Idempotent:
     * - If a recipient has already been delivered current senderKeyId, no message is returned.
     * - If a pending message already exists, no new message is returned.
     */
    fun planDistributions(
        groupId: GroupId,
        conversationIdForRecipient: (ByteArray) -> String,
        messageIdGenerator: () -> String,
    ): List<PlannedDistribution> {
        val group = groupStore.getById(groupId) ?: return emptyList()

        // Ensure we have a local sender key.
        val local = getOrCreateLocalSenderKey(groupId)

        val localDist = distributionStore.getState(groupId, localIdentityPublicKey)
            ?: LocalDistributionState(
                groupId = groupId,
                senderIdentityPublicKey = localIdentityPublicKey.copyOf(),
                currentSenderKeyId = local.senderKeyId,
                deliveredRecipientIdentityPublicKeys = emptySet(),
            )

        val planned = ArrayList<PlannedDistribution>()

        group.memberIdentityPublicKeys
            .filterNot { it.contentEquals(localIdentityPublicKey) }
            .forEach { recipientIdentity ->
                // Membership enforcement: only current members.
                if (!group.isMember(recipientIdentity)) return@forEach

                // Already delivered?
                val alreadyDelivered = localDist.deliveredRecipientIdentityPublicKeys.any { it.contentEquals(recipientIdentity) }
                if (alreadyDelivered) return@forEach

                // Pending exists?
                val pendingExists = findPendingForRecipient(groupId, local.senderKeyId, recipientIdentity)
                if (pendingExists) return@forEach

                val messageId = messageIdGenerator()
                val msg = SenderKeyDistributionMessage(
                    messageId = messageId,
                    conversationId = conversationIdForRecipient(recipientIdentity),
                    createdAtElapsedMs = clock.nowMs(),
                    groupId = groupId.copyBytes(),
                    senderIdentityPublicKey = localIdentityPublicKey.copyOf(),
                    senderKeyId = local.senderKeyId,
                    senderChainKey = local.chainKey.copyOf(),
                )

                distributionStore.markPending(
                    PendingDistribution(
                        messageId = messageId,
                        groupId = groupId,
                        senderIdentityPublicKey = localIdentityPublicKey.copyOf(),
                        senderKeyId = local.senderKeyId,
                        recipientIdentityPublicKey = recipientIdentity.copyOf(),
                    ),
                )

                debug.onPlannedDistribution(groupId, local.senderKeyId, recipientIdentity, messageId, clock.nowMs())
                planned.add(PlannedDistribution(recipientIdentity.copyOf(), msg))
            }

        return planned
    }

    /**
     * Handles inbound SenderKeyDistributionMessage after it has been decrypted by a 1:1 session.
     *
     * The caller MUST supply the authenticated peer identity public key for that 1:1 session.
     */
    fun onInboundDistribution(authenticatedPeerIdentityPublicKey: ByteArray, msg: SenderKeyDistributionMessage): Boolean {
        val now = clock.nowMs()

        if (!msg.senderIdentityPublicKey.contentEquals(authenticatedPeerIdentityPublicKey)) {
            debug.onInboundRejected("sender_identity_mismatch", now)
            return false
        }

        val groupId = GroupId(msg.groupId)
        val group = groupStore.getById(groupId)
        if (group == null) {
            debug.onInboundRejected("unknown_group", now)
            return false
        }

        // Membership enforcement.
        if (!group.isMember(authenticatedPeerIdentityPublicKey) || !group.isMember(localIdentityPublicKey)) {
            debug.onInboundRejected("non_member", now)
            return false
        }

        // Idempotent: ignore duplicates / old keys.
        val existing = senderKeyStore.get(groupId, authenticatedPeerIdentityPublicKey)
        if (existing != null && existing.senderKeyId >= msg.senderKeyId) {
            debug.onInboundAccepted(groupId, authenticatedPeerIdentityPublicKey, existing.senderKeyId, now)
            return true
        }

        senderKeyStore.put(
            SenderKeyState(
                groupId = groupId,
                senderIdentityPublicKey = authenticatedPeerIdentityPublicKey.copyOf(),
                senderKeyId = msg.senderKeyId,
                chainKey = msg.senderChainKey.copyOf(),
                nextCounter = 1L,
            ),
        )

        debug.onInboundAccepted(groupId, authenticatedPeerIdentityPublicKey, msg.senderKeyId, now)
        return true
    }

    fun applyInboundDistribution(authenticatedPeerIdentityPublicKey: ByteArray, msg: SenderKeyDistributionMessage): InboundApplyResult {
        val now = clock.nowMs()

        if (!msg.senderIdentityPublicKey.contentEquals(authenticatedPeerIdentityPublicKey)) {
            debug.onInboundRejected("sender_identity_mismatch", now)
            return InboundApplyResult(accepted = false, reason = "sender_identity_mismatch")
        }

        val groupId = GroupId(msg.groupId)
        val group = groupStore.getById(groupId)
        if (group == null) {
            debug.onInboundRejected("unknown_group", now)
            return InboundApplyResult(accepted = false, reason = "unknown_group")
        }

        // Membership enforcement.
        if (!group.isMember(authenticatedPeerIdentityPublicKey) || !group.isMember(localIdentityPublicKey)) {
            debug.onInboundRejected("non_member", now)
            return InboundApplyResult(accepted = false, reason = "non_member")
        }

        // Idempotent: ignore duplicates / old keys.
        val existing = senderKeyStore.get(groupId, authenticatedPeerIdentityPublicKey)
        if (existing != null && existing.senderKeyId >= msg.senderKeyId) {
            debug.onInboundAccepted(groupId, authenticatedPeerIdentityPublicKey, existing.senderKeyId, now)
            return InboundApplyResult(accepted = true)
        }

        senderKeyStore.put(
            SenderKeyState(
                groupId = groupId,
                senderIdentityPublicKey = authenticatedPeerIdentityPublicKey.copyOf(),
                senderKeyId = msg.senderKeyId,
                chainKey = msg.senderChainKey.copyOf(),
                nextCounter = 1L,
            ),
        )

        debug.onInboundAccepted(groupId, authenticatedPeerIdentityPublicKey, msg.senderKeyId, now)
        return InboundApplyResult(accepted = true)
    }

    /**
     * ACK hook: called when an authenticated peer ACKs one of our outbound messages.
     */
    fun onInboundAck(authenticatedPeerIdentityPublicKey: ByteArray, ackedMessageId: String): Boolean {
        val pending = distributionStore.pendingByMessageId(ackedMessageId) ?: return false

        // Ensure the ACK came from the recipient we targeted.
        if (!pending.recipientIdentityPublicKey.contentEquals(authenticatedPeerIdentityPublicKey)) return false

        val state = distributionStore.getState(pending.groupId, pending.senderIdentityPublicKey)
            ?: return false

        if (state.currentSenderKeyId != pending.senderKeyId) {
            // Key rotated since this was sent; still clear pending.
            distributionStore.removePending(ackedMessageId)
            return false
        }

        val delivered = state.deliveredRecipientIdentityPublicKeys
            .map { it.copyOf() }
            .toMutableSet()
        delivered.add(authenticatedPeerIdentityPublicKey.copyOf())

        distributionStore.putState(
            state.copy(
                deliveredRecipientIdentityPublicKeys = delivered,
            ),
        )
        distributionStore.removePending(ackedMessageId)

        debug.onMarkedDelivered(pending.groupId, pending.senderKeyId, authenticatedPeerIdentityPublicKey, clock.nowMs())
        return true
    }

    private fun findPendingForRecipient(groupId: GroupId, senderKeyId: Long, recipientIdentityPublicKey: ByteArray): Boolean {
        // O(n) scan is OK for small groups; can be optimized later.
        return distributionStore.listPending().any { p ->
            p.groupId == groupId &&
                p.senderKeyId == senderKeyId &&
                p.recipientIdentityPublicKey.contentEquals(recipientIdentityPublicKey)
        }
    }
}

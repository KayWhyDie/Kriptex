package com.ivor.kriptex.deliverypolicy.conversationtruststate

import com.ivor.kriptex.deliverypolicy.group.GroupStore
import com.ivor.kriptex.deliverypolicy.group.senderkey.SenderKeyState
import com.ivor.kriptex.deliverypolicy.group.senderkey.SenderKeyStore
import com.ivor.kriptex.deliverypolicy.group.senderkey.distribution.SenderKeyDistributionStore
import com.ivor.kriptex.deliverypolicy.session.InMemorySessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.awaitCancellation
import java.security.MessageDigest

/**
 * Derived, authoritative trust view for a single conversation.
 *
 * Notes:
 * - No crypto changes.
 * - No protocol changes.
 * - Restore-safe alerting: issue/trust-change diagnostics are only emitted on transitions after the first emission.
 */
class ConversationTrustStateEngine(
    private val conversationId: String,
    private val trustStore: ConversationTrustStore,
    private val identityKeyStore: IdentityKeyStore? = null,
    private val sessionStore: InMemorySessionStore? = null,
    private val peerId: String? = null,
    private val groupStore: GroupStore? = null,
    private val senderKeyStore: SenderKeyStore? = null,
    private val senderKeyDistributionStore: SenderKeyDistributionStore? = null,
    /** 32-byte Ed25519 identity public key; required for group derivation. */
    private val localIdentityPublicKey: ByteArray? = null,
    private val invalidations: ConversationTrustInvalidationSources = ConversationTrustInvalidationSources(),
    private val debugTrace: TrustStateDebugTrace = NoOpTrustStateDebugTrace,
) {

    private data class IssueInstance(val issue: TrustIssue, val key: String)

    private sealed interface BaselineKey {
        data class PeerIdentity(val peerKeyHash: String) : BaselineKey
        data class Session(val sessionIdOrMissing: String) : BaselineKey
        data class GroupMembers(val memberKeyHashes: Set<String>) : BaselineKey
        data class SenderKey(val senderKeyHash: String, val senderKeyId: Long) : BaselineKey
        data class LocalSenderKey(val senderKeyId: Long) : BaselineKey
    }

    private var baselineEmitted = false
    private var lastSnapshot: TrustSnapshot? = null
    private var lastUnacked: Set<TrustIssue> = emptySet()

    fun snapshot(): TrustSnapshot {
        val issueInstances = deriveIssueInstances()
        val issues = issueInstances.map { it.issue }.toSet()

        val acknowledged = trustStore.acknowledgedIssueKeys(conversationId)
        val unackedIssues = issueInstances
            .filter { it.key !in acknowledged }
            .map { it.issue }
            .toSet()

        val explicitlyVerified = trustStore.isExplicitlyVerified(conversationId)
        val trust = deriveTrustLevel(explicitlyVerified = explicitlyVerified, issues = issues)

        return TrustSnapshot(
            conversationId = conversationId,
            trustLevel = trust,
            issues = issues,
            unacknowledgedIssues = unackedIssues,
            explicitlyVerified = explicitlyVerified,
        )
    }

    fun observe(): Flow<TrustSnapshot> {
        val ticks = merge(
            invalidations.identityKeyStore,
            invalidations.sessionStore,
            invalidations.groupStore,
            invalidations.senderKeyStore,
            invalidations.senderKeyDistributionStore,
            invalidations.manual,
            keepAlive(),
        )

        return ticks
            .onStart { emit(Unit) }
            .map {
                val next = snapshot()
                emitDiagnostics(previous = lastSnapshot, next = next)
                lastSnapshot = next
                next
            }
            .distinctUntilChanged()
    }

    private fun keepAlive(): Flow<Unit> = flow {
        awaitCancellation()
    }

    /**
     * User acknowledgment: persists all currently present issue keys.
     */
    @Synchronized
    fun acknowledgeCurrentIssues(reason: String = "user_acknowledged") {
        val current = currentBaselineKeys()
        val keysToAck = LinkedHashSet<String>()

        // Always acknowledge missing-sender-key fingerprints (warning suppression) if present.
        keysToAck.addAll(deriveMissingSenderKeyIssueInstances().map { it.key })

        // For change-based issues, acknowledging updates the baseline to current.
        current.peerIdentity?.let { keysToAck.add(encodeBaselineKey(it)) }
        current.session?.let { keysToAck.add(encodeBaselineKey(it)) }
        current.groupMembers?.let { keysToAck.add(encodeBaselineKey(it)) }
        current.senderKeys.forEach { keysToAck.add(encodeBaselineKey(it)) }
        current.localSenderKey?.let { keysToAck.add(encodeBaselineKey(it)) }

        trustStore.acknowledgeIssueKeys(conversationId, keysToAck)
        debugTrace.onUserAcknowledged(conversationId = conversationId, acknowledgedIssueCount = keysToAck.size, reason = reason)
    }

    /**
     * User verification: marks conversation verified and acknowledges current issues.
     */
    @Synchronized
    fun verifyConversation(reason: String = "user_verified") {
        trustStore.setExplicitlyVerified(conversationId, true)
        acknowledgeCurrentIssues(reason = reason)
    }

    private fun emitDiagnostics(previous: TrustSnapshot?, next: TrustSnapshot) {
        if (!baselineEmitted) {
            baselineEmitted = true
            lastUnacked = next.unacknowledgedIssues
            return
        }

        // Issue detected (transition only): newly unacknowledged.
        val newlyUnacked = next.unacknowledgedIssues - lastUnacked
        newlyUnacked.forEach { issue ->
            debugTrace.onIssueDetected(conversationId = conversationId, issue = issue, reason = "unacknowledged_issue_detected")
        }

        if (previous != null && next.trustLevel != previous.trustLevel) {
            if (next.trustLevel.ordinal > previous.trustLevel.ordinal) {
                // ordinal is not an ordering guarantee; use explicit downgrade check
            }

            // Treat VERIFIED -> anything else, or CHANGED->BROKEN, etc as downgrade.
            val downgrade = isDowngrade(from = previous.trustLevel, to = next.trustLevel)
            if (downgrade) {
                debugTrace.onTrustDowngraded(
                    conversationId = conversationId,
                    from = previous.trustLevel,
                    to = next.trustLevel,
                    reason = "trust_level_changed",
                )
            }
        }

        lastUnacked = next.unacknowledgedIssues
    }

    private fun isDowngrade(from: TrustLevel, to: TrustLevel): Boolean {
        val rank = mapOf(
            TrustLevel.VERIFIED to 3,
            TrustLevel.UNVERIFIED to 2,
            TrustLevel.CHANGED to 1,
            TrustLevel.BROKEN to 0,
        )
        return (rank[to] ?: 0) < (rank[from] ?: 0)
    }

    private fun deriveTrustLevel(explicitlyVerified: Boolean, issues: Set<TrustIssue>): TrustLevel {
        return when {
            TrustIssue.SessionReset in issues -> TrustLevel.BROKEN
            TrustIssue.IdentityKeyChanged in issues -> TrustLevel.CHANGED
            TrustIssue.MemberAdded in issues || TrustIssue.MemberRemoved in issues -> TrustLevel.CHANGED
            TrustIssue.MissingSenderKey in issues -> TrustLevel.UNVERIFIED
            explicitlyVerified && issues.isEmpty() -> TrustLevel.VERIFIED
            else -> TrustLevel.UNVERIFIED
        }
    }

    private fun deriveIssueInstances(): List<IssueInstance> {
        val out = ArrayList<IssueInstance>()
        val acknowledged = trustStore.acknowledgedIssueKeys(conversationId)

        // 1:1 identity key change relative to acknowledged baseline.
        val currentIdentity = currentPeerIdentityBaselineKey()
        val baselineIdentity = decodeBaselineKey(acknowledged, prefix = "PeerIdentity") as? BaselineKey.PeerIdentity
        if (currentIdentity != null && baselineIdentity != null && currentIdentity.peerKeyHash != baselineIdentity.peerKeyHash) {
            out.add(IssueInstance(TrustIssue.IdentityKeyChanged, key = "IdentityKeyChanged:${currentIdentity.peerKeyHash}"))
        }

        // 1:1 session reset relative to acknowledged baseline.
        val currentSession = currentSessionBaselineKey()
        val baselineSession = decodeBaselineKey(acknowledged, prefix = "Session") as? BaselineKey.Session
        if (currentSession != null && baselineSession != null && currentSession.sessionIdOrMissing != baselineSession.sessionIdOrMissing) {
            out.add(IssueInstance(TrustIssue.SessionReset, key = "SessionReset:${currentSession.sessionIdOrMissing}"))
        }

        // Group membership changes relative to acknowledged baseline.
        val currentMembers = currentGroupMembersBaselineKey()
        val baselineMembers = decodeBaselineKey(acknowledged, prefix = "GroupMembers") as? BaselineKey.GroupMembers
        if (currentMembers != null && baselineMembers != null) {
            val added = currentMembers.memberKeyHashes - baselineMembers.memberKeyHashes
            val removed = baselineMembers.memberKeyHashes - currentMembers.memberKeyHashes
            if (added.isNotEmpty()) out.add(IssueInstance(TrustIssue.MemberAdded, key = "MemberAdded:${currentMembers.memberKeyHashes.size}"))
            if (removed.isNotEmpty()) out.add(IssueInstance(TrustIssue.MemberRemoved, key = "MemberRemoved:${currentMembers.memberKeyHashes.size}"))
        }

        // Missing sender key is absolute: always present if missing.
        out.addAll(deriveMissingSenderKeyIssueInstances())

        // Sender key rotations relative to acknowledged baselines.
        val currentSenderKeys = currentSenderKeyBaselineKeys()
        val baselineSenderKeys = decodeAllSenderKeyBaselines(acknowledged)
        currentSenderKeys.forEach { current ->
            val baseline = baselineSenderKeys[current.senderKeyHash]
            if (baseline != null && baseline.senderKeyId != current.senderKeyId) {
                out.add(IssueInstance(TrustIssue.SenderKeyRotated, key = "SenderKeyRotated:${current.senderKeyHash}:${current.senderKeyId}"))
            }
        }

        val currentLocalSenderKey = currentLocalSenderKeyBaselineKey()
        val baselineLocalSenderKey = decodeBaselineKey(acknowledged, prefix = "LocalSenderKey") as? BaselineKey.LocalSenderKey
        if (currentLocalSenderKey != null && baselineLocalSenderKey != null && currentLocalSenderKey.senderKeyId != baselineLocalSenderKey.senderKeyId) {
            out.add(IssueInstance(TrustIssue.SenderKeyRotated, key = "SenderKeyRotated:local:${currentLocalSenderKey.senderKeyId}"))
        }

        return out
    }

    private data class CurrentBaselines(
        val peerIdentity: BaselineKey.PeerIdentity?,
        val session: BaselineKey.Session?,
        val groupMembers: BaselineKey.GroupMembers?,
        val senderKeys: List<BaselineKey.SenderKey>,
        val localSenderKey: BaselineKey.LocalSenderKey?,
    )

    private fun currentBaselineKeys(): CurrentBaselines {
        return CurrentBaselines(
            peerIdentity = currentPeerIdentityBaselineKey(),
            session = currentSessionBaselineKey(),
            groupMembers = currentGroupMembersBaselineKey(),
            senderKeys = currentSenderKeyBaselineKeys(),
            localSenderKey = currentLocalSenderKeyBaselineKey(),
        )
    }

    private fun currentPeerIdentityBaselineKey(): BaselineKey.PeerIdentity? {
        val pid = peerId ?: return null
        val key = identityKeyStore?.getPeerIdentityPublicKey(pid) ?: return null
        return BaselineKey.PeerIdentity(peerKeyHash = sha256Hex(key))
    }

    private fun currentSessionBaselineKey(): BaselineKey.Session? {
        val pid = peerId ?: return null
        val s = sessionStore?.findEstablished(pid, conversationId)
        return BaselineKey.Session(sessionIdOrMissing = s?.sessionId ?: "missing")
    }

    private fun currentGroupMembersBaselineKey(): BaselineKey.GroupMembers? {
        val group = groupStore?.getByConversationId(conversationId) ?: return null
        val hashes = group.memberIdentityPublicKeys.map { sha256Hex(it) }.toSet()
        return BaselineKey.GroupMembers(memberKeyHashes = hashes)
    }

    private fun currentSenderKeyBaselineKeys(): List<BaselineKey.SenderKey> {
        val group = groupStore?.getByConversationId(conversationId) ?: return emptyList()
        val sk = senderKeyStore ?: return emptyList()
        val out = ArrayList<BaselineKey.SenderKey>()
        group.memberIdentityPublicKeys.forEach { memberKey ->
            val s: SenderKeyState = sk.get(group.groupId, memberKey) ?: return@forEach
            out.add(BaselineKey.SenderKey(senderKeyHash = sha256Hex(memberKey), senderKeyId = s.senderKeyId))
        }
        return out
    }

    private fun currentLocalSenderKeyBaselineKey(): BaselineKey.LocalSenderKey? {
        val group = groupStore?.getByConversationId(conversationId) ?: return null
        val dist = senderKeyDistributionStore ?: return null
        val local = localIdentityPublicKey ?: return null
        val state = dist.getState(group.groupId, local) ?: return null
        return BaselineKey.LocalSenderKey(senderKeyId = state.currentSenderKeyId)
    }

    private fun deriveMissingSenderKeyIssueInstances(): List<IssueInstance> {
        val group = groupStore?.getByConversationId(conversationId) ?: return emptyList()
        val sk = senderKeyStore ?: return emptyList()
        val missingHashes = group.memberIdentityPublicKeys
            .filter { memberKey -> sk.get(group.groupId, memberKey) == null }
            .map { sha256Hex(it) }
            .sorted()

        if (missingHashes.isEmpty()) return emptyList()

        val fingerprint = sha256Hex(missingHashes.joinToString(",").encodeToByteArray())
        return listOf(IssueInstance(TrustIssue.MissingSenderKey, key = "MissingSenderKey:$fingerprint"))
    }

    private fun encodeBaselineKey(key: BaselineKey): String {
        return when (key) {
            is BaselineKey.PeerIdentity -> "PeerIdentity:${key.peerKeyHash}"
            is BaselineKey.Session -> "Session:${key.sessionIdOrMissing}"
            is BaselineKey.GroupMembers -> {
                val sorted = key.memberKeyHashes.toList().sorted()
                "GroupMembers:${sorted.joinToString(",")}" 
            }
            is BaselineKey.SenderKey -> "SenderKey:${key.senderKeyHash}:${key.senderKeyId}"
            is BaselineKey.LocalSenderKey -> "LocalSenderKey:${key.senderKeyId}"
        }
    }

    private fun decodeBaselineKey(acknowledged: Set<String>, prefix: String): BaselineKey? {
        val entry = acknowledged.firstOrNull { it.startsWith(prefix + ":") } ?: return null
        val parts = entry.split(":", limit = 2)
        if (parts.size != 2) return null
        val payload = parts[1]
        return when (prefix) {
            "PeerIdentity" -> BaselineKey.PeerIdentity(peerKeyHash = payload)
            "Session" -> BaselineKey.Session(sessionIdOrMissing = payload)
            "GroupMembers" -> {
                val set = payload.split(",").filter { it.isNotEmpty() }.toSet()
                BaselineKey.GroupMembers(memberKeyHashes = set)
            }
            "LocalSenderKey" -> BaselineKey.LocalSenderKey(senderKeyId = payload.toLongOrNull() ?: 0L)
            else -> null
        }
    }

    private fun decodeAllSenderKeyBaselines(acknowledged: Set<String>): Map<String, BaselineKey.SenderKey> {
        val out = LinkedHashMap<String, BaselineKey.SenderKey>()
        acknowledged.filter { it.startsWith("SenderKey:") }.forEach { entry ->
            val parts = entry.split(":")
            if (parts.size != 3) return@forEach
            val senderHash = parts[1]
            val id = parts[2].toLongOrNull() ?: return@forEach
            out[senderHash] = BaselineKey.SenderKey(senderKeyHash = senderHash, senderKeyId = id)
        }
        return out
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { b -> "%02x".format(b) }
    }
}

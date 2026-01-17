package com.ivor.kriptex.deliverypolicy.session

import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.MonotonicClock
import com.ivor.kriptex.deliverypolicy.persistence.PersistedRatchetState
import com.ivor.kriptex.deliverypolicy.persistence.PersistedSessionState
import com.ivor.kriptex.deliverypolicy.persistence.PersistedSessionStoreSnapshot
import com.ivor.kriptex.deliverypolicy.persistence.PersistedSkippedMessageKey
import com.ivor.kriptex.deliverypolicy.protocol.SessionAeadAlgorithm
import com.ivor.kriptex.deliverypolicy.session.crypto.NoOpSessionCryptoDebugTrace
import com.ivor.kriptex.deliverypolicy.session.crypto.RatchetCryptoBox
import com.ivor.kriptex.deliverypolicy.session.crypto.SessionCryptoBox
import com.ivor.kriptex.deliverypolicy.session.crypto.SessionCryptoDebugTrace
import com.ivor.kriptex.deliverypolicy.session.ratchet.HkdfSha256
import com.ivor.kriptex.deliverypolicy.session.ratchet.NoOpRatchetDebugTrace
import com.ivor.kriptex.deliverypolicy.session.ratchet.RatchetDebugTrace
import com.ivor.kriptex.deliverypolicy.session.ratchet.RatchetDh
import com.ivor.kriptex.deliverypolicy.session.ratchet.RatchetState
import com.ivor.kriptex.deliverypolicy.session.x3dh.X3dhCrypto
import com.ivor.kriptex.deliverypolicy.session.x3dh.X3dhPreKeyBundle
import com.ivor.kriptex.deliverypolicy.session.x3dh.X3dhPreKeyStore
import com.ivor.kriptex.deliverypolicy.session.x3dh.X3dhTranscript
import java.security.MessageDigest

class InMemorySessionStore(
    private val x3dhIdentitySeedEd: ByteArray,
    private val x3dhIdentityPublicKeyEd: ByteArray,
    private val x3dhPreKeyStore: X3dhPreKeyStore,
    private val clock: Clock = MonotonicClock,
    private val debugTrace: SessionDebugTrace = NoOpSessionDebugTrace,
) {

    init {
        require(x3dhIdentitySeedEd.size == 32) { "bad_x3dh_identity_seed_len" }
        require(x3dhIdentityPublicKeyEd.size == 32) { "bad_x3dh_identity_public_len" }
    }

    private val sessionsByPeerAndSessionId = LinkedHashMap<String, SessionState>()
    private val sessionsByPeerAndConversation = LinkedHashMap<String, SessionState>()

    private val inboundMessageIdCapacity = 512

    @Synchronized
    fun findEstablished(peerId: String, conversationId: String): SessionState? {
        val key = keyPeerConversation(peerId, conversationId)
        val s = sessionsByPeerAndConversation[key]
        return if (s != null && s.isEstablished()) s else null
    }

    @Synchronized
    fun findBySessionId(peerId: String, sessionId: String): SessionState? {
        return sessionsByPeerAndSessionId[keyPeerSession(peerId, sessionId)]
    }

    @Synchronized
    fun createLocalInit(
        peerId: String,
        conversationId: String,
        sessionId: String,
        preferredAeadAlgorithm: SessionAeadAlgorithm,
        localIdentityPublicKey: ByteArray,
        initiatorNonce: ByteArray,
        peerBundle: X3dhPreKeyBundle,
    ): SessionState {
        check(localIdentityPublicKey.contentEquals(x3dhIdentityPublicKeyEd)) { "local_identity_mismatch" }
        check(peerBundle.identityPublicKey.size == 32) { "bad_peer_identity_len" }
        check(
            X3dhCrypto.verifySignedPreKeySignature(
                identityPublic = peerBundle.identityPublicKey,
                signedPreKeyId = peerBundle.signedPreKeyId,
                signedPreKeyPublic = peerBundle.signedPreKeyPublicKey,
                signature = peerBundle.signedPreKeySignature,
            )
        ) { "bad_signed_prekey_signature" }

        // X3DH base key (X25519) for initiator.
        val base = RatchetDh.DefaultKeyPairGenerator.generate()
        val state = SessionState(
            peerId = peerId,
            conversationId = conversationId,
            sessionId = sessionId,
            role = SessionRole.INITIATOR,
            status = SessionStatus.PENDING,
            aeadEnabled = true,
            aeadAlgorithm = preferredAeadAlgorithm,
            localIdentityPublicKey = localIdentityPublicKey,
            peerIdentityPublicKey = peerBundle.identityPublicKey,
            initiatorNonce = initiatorNonce,
            responderNonce = null,
            sharedKey = null,
            pendingResponderSignedPreKeyId = peerBundle.signedPreKeyId,
            pendingResponderSignedPreKeyPublicKey = peerBundle.signedPreKeyPublicKey,
            pendingResponderSignedPreKeySignature = peerBundle.signedPreKeySignature,
            pendingResponderOneTimePreKeyId = peerBundle.oneTimePreKeyId,
            pendingResponderOneTimePreKeyPublicKey = peerBundle.oneTimePreKeyPublicKey,
            nextOutboundSeq = 1L,
            replayWindow = ReplayWindow.empty(),
            inboundMessageIdsSeen = emptyList(),
            ratchetState = null,
            pendingRatchetDhPrivateKey = base.privateKey,
            pendingRatchetDhPublicKey = base.publicKey,
        )
        put(state)
        debugTrace.onSessionInitCreated(peerId, conversationId, sessionId, clock.nowMs())
        return state
    }

    @Synchronized
    fun acceptRemoteInit(
        peerId: String,
        conversationId: String,
        sessionId: String,
        aeadAlgorithm: SessionAeadAlgorithm,
        localIdentityPublicKey: ByteArray,
        initiatorPublicKey: ByteArray,
        initiatorNonce: ByteArray,
        initiatorBasePublicKey: ByteArray,
        responderIdentityPublicKey: ByteArray,
        responderSignedPreKeyId: Int,
        responderSignedPreKeyPublicKey: ByteArray,
        responderSignedPreKeySignature: ByteArray,
        responderOneTimePreKeyId: Int?,
        responderOneTimePreKeyPublicKey: ByteArray?,
        responderNonce: ByteArray,
    ): Pair<SessionState, ByteArray> {
        check(localIdentityPublicKey.contentEquals(x3dhIdentityPublicKeyEd)) { "local_identity_mismatch" }
        if (!responderIdentityPublicKey.contentEquals(x3dhIdentityPublicKeyEd)) {
            throw IllegalArgumentException("wrong_responder_identity")
        }

        if (!X3dhCrypto.verifySignedPreKeySignature(
                identityPublic = responderIdentityPublicKey,
                signedPreKeyId = responderSignedPreKeyId,
                signedPreKeyPublic = responderSignedPreKeyPublicKey,
                signature = responderSignedPreKeySignature,
            )
        ) {
            throw IllegalArgumentException("bad_signed_prekey_signature")
        }

        val signed = x3dhPreKeyStore.getSignedPreKey(responderSignedPreKeyId) ?: throw IllegalArgumentException("unknown_signed_prekey")
        if (!signed.publicKey.contentEquals(responderSignedPreKeyPublicKey)) {
            throw IllegalArgumentException("signed_prekey_mismatch")
        }

        val consumedOpkPrivate: ByteArray? = if (responderOneTimePreKeyId != null) {
            val consumed = x3dhPreKeyStore.consumeOneTimePreKey(responderOneTimePreKeyId)
                ?: throw IllegalArgumentException("one_time_prekey_unavailable")
            if (responderOneTimePreKeyPublicKey == null || !consumed.publicKey.contentEquals(responderOneTimePreKeyPublicKey)) {
                throw IllegalArgumentException("one_time_prekey_mismatch")
            }
            consumed.privateKey
        } else {
            null
        }

        val ss = X3dhCrypto.computeSharedSecretResponder(
            responderIdentitySeedEd = x3dhIdentitySeedEd,
            responderSignedPreKeyPrivateX = signed.privateKey,
            initiatorIdentityPublicEd = initiatorPublicKey,
            initiatorBasePublicX = initiatorBasePublicKey,
            responderOneTimePreKeyPrivateX = consumedOpkPrivate,
        )
        val secrets = X3dhCrypto.deriveSecrets(
            sharedSecret = ss,
            initiatorIdentityPublicEd = initiatorPublicKey,
            responderIdentityPublicEd = responderIdentityPublicKey,
            sessionId = sessionId,
            initiatorNonce = initiatorNonce,
            responderNonce = responderNonce,
        )

        val ratchetState = RatchetState.initialize(
            role = SessionRole.RESPONDER.name,
            initialRootKey = secrets.initialRootKey,
            localDh = RatchetDh.KeyPair(privateKey = signed.privateKey, publicKey = signed.publicKey),
            remoteDhPublicKey = initiatorBasePublicKey,
        )

        val transcript = X3dhTranscript.transcriptHash(
            sessionId = sessionId,
            aeadAlgorithm = aeadAlgorithm,
            initiatorIdentityPublicEd = initiatorPublicKey,
            responderIdentityPublicEd = responderIdentityPublicKey,
            initiatorBasePublicX = initiatorBasePublicKey,
            responderSignedPreKeyId = responderSignedPreKeyId,
            responderSignedPreKeyPublicX = responderSignedPreKeyPublicKey,
            responderOneTimePreKeyId = responderOneTimePreKeyId,
            initiatorNonce = initiatorNonce,
            responderNonce = responderNonce,
        )
        val confirmTag = X3dhCrypto.confirmTag(secrets.confirmKey, transcript)

        val state = SessionState(
            peerId = peerId,
            conversationId = conversationId,
            sessionId = sessionId,
            role = SessionRole.RESPONDER,
            status = SessionStatus.ESTABLISHED,
            aeadEnabled = true,
            aeadAlgorithm = aeadAlgorithm,
            localIdentityPublicKey = localIdentityPublicKey,
            peerIdentityPublicKey = initiatorPublicKey,
            initiatorNonce = initiatorNonce,
            responderNonce = responderNonce,
            sharedKey = null,
            nextOutboundSeq = 1L,
            replayWindow = ReplayWindow.empty(),
            inboundMessageIdsSeen = emptyList(),
            ratchetState = ratchetState,
            pendingRatchetDhPrivateKey = null,
            pendingRatchetDhPublicKey = null,
        )
        put(state)
        debugTrace.onSessionInitAccepted(peerId, conversationId, sessionId, clock.nowMs())
        debugTrace.onSessionEstablished(peerId, conversationId, sessionId, clock.nowMs())
        return state to confirmTag
    }

    @Synchronized
    fun applyRemoteAccept(
        peerId: String,
        conversationId: String,
        sessionId: String,
        aeadAlgorithm: SessionAeadAlgorithm,
        localIdentityPublicKey: ByteArray,
        initiatorPublicKey: ByteArray,
        initiatorNonce: ByteArray,
        responderPublicKey: ByteArray,
        responderNonce: ByteArray,
        initiatorBasePublicKey: ByteArray,
        responderSignedPreKeyId: Int,
        responderOneTimePreKeyId: Int?,
        confirmTag: ByteArray,
    ): SessionState {
        val prior = findBySessionId(peerId, sessionId) ?: throw IllegalStateException("unknown_session")
        if (prior.status != SessionStatus.PENDING || prior.role != SessionRole.INITIATOR) {
            throw IllegalStateException("unexpected_accept")
        }

        if (!prior.localIdentityPublicKey.contentEquals(initiatorPublicKey)) {
            throw IllegalArgumentException("initiator_identity_mismatch")
        }
        if (!prior.initiatorNonce.contentEquals(initiatorNonce)) {
            throw IllegalArgumentException("initiator_nonce_mismatch")
        }

        if (!prior.peerIdentityPublicKey.contentEquals(responderPublicKey)) {
            throw IllegalArgumentException("responder_identity_mismatch")
        }

        if (prior.pendingRatchetDhPrivateKey == null || prior.pendingRatchetDhPublicKey == null) {
            throw IllegalStateException("missing_pending_base_key")
        }
        if (!prior.pendingRatchetDhPublicKey.contentEquals(initiatorBasePublicKey)) {
            throw IllegalArgumentException("base_key_mismatch")
        }

        if (prior.pendingResponderSignedPreKeyId == null ||
            prior.pendingResponderSignedPreKeyPublicKey == null ||
            prior.pendingResponderSignedPreKeySignature == null
        ) {
            throw IllegalStateException("missing_pending_peer_prekeys")
        }
        if (prior.pendingResponderSignedPreKeyId != responderSignedPreKeyId) {
            throw IllegalArgumentException("signed_prekey_id_mismatch")
        }

        val expectedOpkId = prior.pendingResponderOneTimePreKeyId
        if (expectedOpkId != responderOneTimePreKeyId) {
            throw IllegalArgumentException("one_time_prekey_id_mismatch")
        }

        val ss = X3dhCrypto.computeSharedSecretInitiator(
            initiatorIdentitySeedEd = x3dhIdentitySeedEd,
            initiatorBasePrivateX = prior.pendingRatchetDhPrivateKey,
            responderIdentityPublicEd = responderPublicKey,
            responderSignedPreKeyPublicX = prior.pendingResponderSignedPreKeyPublicKey,
            responderOneTimePreKeyPublicX = prior.pendingResponderOneTimePreKeyPublicKey,
        )
        val secrets = X3dhCrypto.deriveSecrets(
            sharedSecret = ss,
            initiatorIdentityPublicEd = initiatorPublicKey,
            responderIdentityPublicEd = responderPublicKey,
            sessionId = sessionId,
            initiatorNonce = initiatorNonce,
            responderNonce = responderNonce,
        )
        val transcript = X3dhTranscript.transcriptHash(
            sessionId = sessionId,
            aeadAlgorithm = aeadAlgorithm,
            initiatorIdentityPublicEd = initiatorPublicKey,
            responderIdentityPublicEd = responderPublicKey,
            initiatorBasePublicX = initiatorBasePublicKey,
            responderSignedPreKeyId = responderSignedPreKeyId,
            responderSignedPreKeyPublicX = prior.pendingResponderSignedPreKeyPublicKey,
            responderOneTimePreKeyId = responderOneTimePreKeyId,
            initiatorNonce = initiatorNonce,
            responderNonce = responderNonce,
        )
        val expectedTag = X3dhCrypto.confirmTag(secrets.confirmKey, transcript)
        if (!expectedTag.contentEquals(confirmTag)) {
            throw IllegalArgumentException("x3dh_confirm_failed")
        }

        val localDh = RatchetDh.KeyPair(
            privateKey = prior.pendingRatchetDhPrivateKey,
            publicKey = prior.pendingRatchetDhPublicKey,
        )
        val ratchetState = RatchetState.initialize(
            role = SessionRole.INITIATOR.name,
            initialRootKey = secrets.initialRootKey,
            localDh = localDh,
            remoteDhPublicKey = prior.pendingResponderSignedPreKeyPublicKey,
        )

        val state = prior.copy(
            peerId = peerId,
            conversationId = conversationId,
            sessionId = sessionId,
            role = SessionRole.INITIATOR,
            status = SessionStatus.ESTABLISHED,
            aeadEnabled = true,
            aeadAlgorithm = aeadAlgorithm,
            localIdentityPublicKey = localIdentityPublicKey,
            peerIdentityPublicKey = responderPublicKey,
            initiatorNonce = initiatorNonce,
            responderNonce = responderNonce,
            sharedKey = null,
            ratchetState = ratchetState,
            pendingResponderSignedPreKeyId = null,
            pendingResponderSignedPreKeyPublicKey = null,
            pendingResponderSignedPreKeySignature = null,
            pendingResponderOneTimePreKeyId = null,
            pendingResponderOneTimePreKeyPublicKey = null,
            pendingRatchetDhPrivateKey = null,
            pendingRatchetDhPublicKey = null,
        )
        put(state)
        debugTrace.onSessionAcceptReceived(peerId, conversationId, sessionId, clock.nowMs())
        debugTrace.onSessionEstablished(peerId, conversationId, sessionId, clock.nowMs())
        return state
    }

    data class EncryptedSessionPayload(
        val sessionId: String,
        val seq: Long,
        val messageId: String,
        val inner: ByteArray,
    )

    @Synchronized
    fun encryptSessionPayload(
        peerId: String,
        conversationId: String,
        messageId: String,
        plaintextProtocolBytes: ByteArray,
        aeadDebug: SessionCryptoDebugTrace = NoOpSessionCryptoDebugTrace,
        ratchetDebug: RatchetDebugTrace = NoOpRatchetDebugTrace,
    ): EncryptedSessionPayload {
        val s = findEstablished(peerId, conversationId) ?: throw IllegalStateException("no_established_session")
        check(s.aeadEnabled) { "aead_disabled" }

        val seq = s.nextOutboundSeq
        var updated = s.copy(nextOutboundSeq = seq + 1)

        val ratchet = s.ratchetState ?: throw IllegalStateException("missing_ratchet_state")
        val crypto = RatchetCryptoBox(session = s, direction = RatchetCryptoBox.Direction.OUTBOUND, aeadDebug = aeadDebug, ratchetDebug = ratchetDebug)
        val res = crypto.encrypt(ratchet = ratchet, plaintextBytes = plaintextProtocolBytes, messageId = messageId, envelopeSeq = seq)
        updated = updated.copy(ratchetState = res.updatedRatchet)
        val inner = res.ciphertextBlobBytes

        put(updated)
        return EncryptedSessionPayload(sessionId = s.sessionId, seq = seq, messageId = messageId, inner = inner)
    }

    @Synchronized
    fun decryptSessionPayload(
        peerId: String,
        sessionId: String,
        messageId: String,
        envelopeSeq: Long,
        ciphertextBlobBytes: ByteArray,
        aeadDebug: SessionCryptoDebugTrace = NoOpSessionCryptoDebugTrace,
        ratchetDebug: RatchetDebugTrace = NoOpRatchetDebugTrace,
    ): ByteArray {
        val s = findBySessionId(peerId, sessionId) ?: throw IllegalStateException("unknown_session")
        check(s.isEstablished()) { "session_not_established" }
        check(s.aeadEnabled) { "aead_disabled" }

        val ratchet = s.ratchetState ?: throw IllegalStateException("missing_ratchet_state")
        val crypto = RatchetCryptoBox(session = s, direction = RatchetCryptoBox.Direction.INBOUND, aeadDebug = aeadDebug, ratchetDebug = ratchetDebug)
        val res = crypto.decrypt(ratchet = ratchet, ciphertextBlobBytes = ciphertextBlobBytes, messageId = messageId, envelopeSeq = envelopeSeq)
        put(s.copy(ratchetState = res.updatedRatchet))
        return res.plaintext
    }

    @Synchronized
    fun nextOutboundSeq(peerId: String, conversationId: String): Long {
        val s = findEstablished(peerId, conversationId) ?: throw IllegalStateException("no_established_session")
        val next = s.nextOutboundSeq
        put(s.copy(nextOutboundSeq = next + 1))
        return next
    }

    @Synchronized
    fun acceptInboundSeq(peerId: String, sessionId: String, seq: Long): ReplayDecision {
        val s = findBySessionId(peerId, sessionId) ?: return ReplayDecision.Rejected("unknown_session")
        val decision = s.replayWindow.accept(seq)
        if (decision is ReplayDecision.Accepted) {
            put(s.copy(replayWindow = decision.updated))
        }
        return decision
    }

    /**
     * MessageId-level replay enforcement at the session boundary.
     *
     * This is checked BEFORE decrypt so the AEAD AAD binding can safely include messageId.
     */
    @Synchronized
    fun acceptInboundMessageId(peerId: String, sessionId: String, messageId: String): Boolean {
        val s = findBySessionId(peerId, sessionId) ?: return false
        val seen = LinkedHashSet<String>(minOf(inboundMessageIdCapacity, s.inboundMessageIdsSeen.size + 1))
        s.inboundMessageIdsSeen.forEach { seen.add(it) }
        if (seen.contains(messageId)) return false
        seen.add(messageId)
        while (seen.size > inboundMessageIdCapacity) {
            val it = seen.iterator()
            it.next()
            it.remove()
        }
        put(s.copy(inboundMessageIdsSeen = seen.toList()))
        return true
    }

    @Synchronized
    fun snapshot(): PersistedSessionStoreSnapshot {
        val capturedAt = clock.nowMs()
        val persisted = sessionsByPeerAndSessionId.values.map {
            PersistedSessionState(
                peerId = it.peerId,
                conversationId = it.conversationId,
                sessionId = it.sessionId,
                role = it.role,
                status = it.status,
                aeadEnabled = it.aeadEnabled,
                aeadAlgorithm = it.aeadAlgorithm,
                localIdentityPublicKey = it.localIdentityPublicKey,
                peerIdentityPublicKey = it.peerIdentityPublicKey,
                initiatorNonce = it.initiatorNonce,
                responderNonce = it.responderNonce,
                sharedKey = it.sharedKey,
                pendingResponderSignedPreKeyId = it.pendingResponderSignedPreKeyId,
                pendingResponderSignedPreKeyPublicKey = it.pendingResponderSignedPreKeyPublicKey,
                pendingResponderSignedPreKeySignature = it.pendingResponderSignedPreKeySignature,
                pendingResponderOneTimePreKeyId = it.pendingResponderOneTimePreKeyId,
                pendingResponderOneTimePreKeyPublicKey = it.pendingResponderOneTimePreKeyPublicKey,
                ratchet = it.ratchetState?.toPersisted(),
                pendingRatchetDhPrivateKey = it.pendingRatchetDhPrivateKey,
                pendingRatchetDhPublicKey = it.pendingRatchetDhPublicKey,
                nextOutboundSeq = it.nextOutboundSeq,
                replayHighestSeqSeen = it.replayWindow.highestSeqSeen,
                replaySeenBitmask = it.replayWindow.seenBitmask,
                inboundMessageIdsSeen = it.inboundMessageIdsSeen,
            )
        }
        debugTrace.onSnapshotBuilt(persisted.size, capturedAt)
        return PersistedSessionStoreSnapshot(capturedAtElapsedMs = capturedAt, sessions = persisted)
    }

    @Synchronized
    fun restore(snapshot: PersistedSessionStoreSnapshot) {
        sessionsByPeerAndSessionId.clear()
        sessionsByPeerAndConversation.clear()

        snapshot.sessions.forEach {
            val state = SessionState(
                peerId = it.peerId,
                conversationId = it.conversationId,
                sessionId = it.sessionId,
                role = it.role,
                status = it.status,
                aeadEnabled = if (snapshot.version >= 3) it.aeadEnabled else snapshot.version >= 2,
                aeadAlgorithm = it.aeadAlgorithm,
                localIdentityPublicKey = it.localIdentityPublicKey,
                peerIdentityPublicKey = it.peerIdentityPublicKey,
                initiatorNonce = it.initiatorNonce,
                responderNonce = it.responderNonce,
                sharedKey = it.sharedKey,
                pendingResponderSignedPreKeyId = it.pendingResponderSignedPreKeyId,
                pendingResponderSignedPreKeyPublicKey = it.pendingResponderSignedPreKeyPublicKey,
                pendingResponderSignedPreKeySignature = it.pendingResponderSignedPreKeySignature,
                pendingResponderOneTimePreKeyId = it.pendingResponderOneTimePreKeyId,
                pendingResponderOneTimePreKeyPublicKey = it.pendingResponderOneTimePreKeyPublicKey,
                nextOutboundSeq = it.nextOutboundSeq,
                replayWindow = ReplayWindow(it.replayHighestSeqSeen, it.replaySeenBitmask),
                inboundMessageIdsSeen = it.inboundMessageIdsSeen,
                ratchetState = if (snapshot.version >= 4) it.ratchet?.toRuntime() else null,
                pendingRatchetDhPrivateKey = if (snapshot.version >= 4) it.pendingRatchetDhPrivateKey else null,
                pendingRatchetDhPublicKey = if (snapshot.version >= 4) it.pendingRatchetDhPublicKey else null,
            )

            // Enforce no-downgrade for AEAD-enabled sessions.
            if (state.aeadEnabled && state.status == SessionStatus.ESTABLISHED && state.ratchetState == null) {
                throw IllegalStateException("invalid_session_snapshot")
            }
            put(state)
        }

        val now = clock.nowMs()
        debugTrace.onRestoreApplied(snapshot.sessions.size, now)

        // Sanity verification: ensure sessionId keys are unique.
        snapshot.sessions.forEach {
            val restored = findBySessionId(it.peerId, it.sessionId)
            val ok = restored != null
            debugTrace.onRestoreVerification(it.peerId, it.sessionId, ok, if (ok) "ok" else "missing", now)
        }
    }

    private fun put(state: SessionState) {
        sessionsByPeerAndSessionId[keyPeerSession(state.peerId, state.sessionId)] = state
        sessionsByPeerAndConversation[keyPeerConversation(state.peerId, state.conversationId)] = state
    }

    private fun initialRatchetSalt(sessionId: String, initiatorNonce: ByteArray, responderNonce: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("KPX-DR-INIT".encodeToByteArray())
        md.update(0)
        md.update(sessionId.encodeToByteArray())
        md.update(0)
        md.update(initiatorNonce)
        md.update(0)
        md.update(responderNonce)
        return md.digest()
    }

    private fun RatchetState.toPersisted(): PersistedRatchetState {
        return PersistedRatchetState(
            rootKey = rootKey,
            sendingChainKey = sendingChainKey,
            receivingChainKey = receivingChainKey,
            localDhPrivateKey = localDhPrivateKey,
            localDhPublicKey = localDhPublicKey,
            remoteDhPublicKey = remoteDhPublicKey,
            ns = ns,
            nr = nr,
            pn = pn,
            pendingSendDhRatchet = pendingSendDhRatchet,
            skippedKeys = skippedKeys.map {
                PersistedSkippedMessageKey(dhPublicKey = it.dhPublicKey, n = it.n, messageKey = it.messageKey)
            },
        )
    }

    private fun PersistedRatchetState.toRuntime(): RatchetState {
        return RatchetState(
            rootKey = rootKey,
            sendingChainKey = sendingChainKey,
            receivingChainKey = receivingChainKey,
            localDhPrivateKey = localDhPrivateKey,
            localDhPublicKey = localDhPublicKey,
            remoteDhPublicKey = remoteDhPublicKey,
            ns = ns,
            nr = nr,
            pn = pn,
            pendingSendDhRatchet = pendingSendDhRatchet,
            skippedKeys = skippedKeys.map {
                RatchetState.SkippedMessageKey(dhPublicKey = it.dhPublicKey, n = it.n, messageKey = it.messageKey)
            },
        )
    }

    private fun keyPeerSession(peerId: String, sessionId: String): String = "$peerId|$sessionId"

    private fun keyPeerConversation(peerId: String, conversationId: String): String = "$peerId|$conversationId"
}

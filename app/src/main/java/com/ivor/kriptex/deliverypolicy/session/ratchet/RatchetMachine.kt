package com.ivor.kriptex.deliverypolicy.session.ratchet

/**
 * Pure Double Ratchet state transitions.
 *
 * This is intentionally side-effect-free: callers should only commit the returned state
 * after successful AEAD verification.
 */
internal object RatchetMachine {

    data class OutboundStep(
        val header: RatchetMessageHeader,
        val messageKey: ByteArray,
        val updated: RatchetState,
    )

    data class InboundStep(
        val header: RatchetMessageHeader,
        val messageKey: ByteArray,
        val updated: RatchetState,
        val usedSkippedKey: Boolean,
    )

    fun nextOutbound(state: RatchetState, debug: RatchetDebugTrace, sessionId: String): OutboundStep {
        check(state.isInitialized()) { "ratchet_not_initialized" }

        var s = state

        if (s.pendingSendDhRatchet) {
            // After receiving a new DHr, we must generate a new DHs and derive a fresh sending chain.
            val newDh = RatchetDh.DefaultKeyPairGenerator.generate()
            val dhOut = RatchetDh.dh(newDh.privateKey, s.remoteDhPublicKey)
            val rkStep = RatchetKdf.kdfRootKey(s.rootKey, dhOut)

            // Best-effort wipe DH output.
            dhOut.fill(0)

            debug.onDhRatchetStep(sessionId, "send_chain")

            s = s.copy(
                rootKey = rkStep.newRootKey,
                sendingChainKey = rkStep.newChainKey,
                localDhPrivateKey = newDh.privateKey,
                localDhPublicKey = newDh.publicKey,
                pn = s.ns,
                ns = 0,
                pendingSendDhRatchet = false,
            )
        }

        val n = s.ns
        val header = RatchetMessageHeader(dhPublicKey = s.localDhPublicKey, n = n, pn = s.pn)

        val ckStep = RatchetKdf.kdfChainKey(s.sendingChainKey)
        debug.onSymmetricRatchetStep(sessionId, direction = "OUTBOUND", n = n)

        val updated = s.copy(
            sendingChainKey = ckStep.newChainKey,
            ns = n + 1,
        )

        return OutboundStep(header = header, messageKey = ckStep.messageKey, updated = updated)
    }

    fun nextInbound(state: RatchetState, header: RatchetMessageHeader, debug: RatchetDebugTrace, sessionId: String): InboundStep {
        check(state.isInitialized()) { "ratchet_not_initialized" }

        // 1) If this message key was skipped earlier, use it immediately.
        val skippedIndex = state.skippedKeys.indexOfFirst { it.n == header.n && it.dhPublicKey.contentEquals(header.dhPublicKey) }
        if (skippedIndex >= 0) {
            val sk = state.skippedKeys[skippedIndex]
            val newSkipped = ArrayList<RatchetState.SkippedMessageKey>(state.skippedKeys.size - 1)
            state.skippedKeys.forEachIndexed { idx, e -> if (idx != skippedIndex) newSkipped.add(e) }
            debug.onSkippedKeyUsed(sessionId)
            return InboundStep(
                header = header,
                messageKey = sk.messageKey,
                updated = state.copy(skippedKeys = newSkipped),
                usedSkippedKey = true,
            )
        }

        var s = state

        // 2) If remote DH changed, perform a DH ratchet.
        if (!header.dhPublicKey.contentEquals(s.remoteDhPublicKey)) {
            // Before switching, store skipped keys up to PN from the old receiving chain.
            s = skipMessageKeys(s, until = header.pn, debug = debug, sessionId = sessionId)

            // New receiving chain.
            val dhOut = RatchetDh.dh(s.localDhPrivateKey, header.dhPublicKey)
            val rkStep = RatchetKdf.kdfRootKey(s.rootKey, dhOut)
            dhOut.fill(0)

            debug.onDhRatchetStep(sessionId, "recv_chain")

            s = s.copy(
                rootKey = rkStep.newRootKey,
                receivingChainKey = rkStep.newChainKey,
                remoteDhPublicKey = header.dhPublicKey,
                nr = 0,
                pendingSendDhRatchet = true,
            )
        }

        // 3) Store skipped keys in current receiving chain up to message number N.
        s = skipMessageKeys(s, until = header.n, debug = debug, sessionId = sessionId)

        // 4) Derive the message key for this message.
        if (header.n != s.nr) {
            // After skipMessageKeys, we must be exactly at header.n.
            debug.onReplayRejected(sessionId, "unexpected_nr")
            throw IllegalStateException("unexpected_nr")
        }

        val step = RatchetKdf.kdfChainKey(s.receivingChainKey)
        debug.onSymmetricRatchetStep(sessionId, direction = "INBOUND", n = s.nr)

        val updated = s.copy(
            receivingChainKey = step.newChainKey,
            nr = s.nr + 1,
        )

        return InboundStep(header = header, messageKey = step.messageKey, updated = updated, usedSkippedKey = false)
    }

    private fun skipMessageKeys(s: RatchetState, until: Int, debug: RatchetDebugTrace, sessionId: String): RatchetState {
        if (until < s.nr) return s

        val delta = until - s.nr
        if (delta > RatchetState.MAX_SKIP) {
            debug.onReplayRejected(sessionId, "too_many_skipped")
            throw IllegalStateException("too_many_skipped")
        }

        var state = s
        var nr = state.nr
        var ck = state.receivingChainKey
        val skipped = ArrayList<RatchetState.SkippedMessageKey>(state.skippedKeys)

        while (nr < until) {
            if (skipped.size >= RatchetState.MAX_SKIPPED_KEYS) {
                debug.onReplayRejected(sessionId, "skipped_store_full")
                throw IllegalStateException("skipped_store_full")
            }

            val step = RatchetKdf.kdfChainKey(ck)
            skipped.add(
                RatchetState.SkippedMessageKey(
                    dhPublicKey = state.remoteDhPublicKey,
                    n = nr,
                    messageKey = step.messageKey,
                ),
            )
            debug.onSkippedKeyStored(sessionId, skipped.size)

            ck = step.newChainKey
            nr += 1
        }

        state = state.copy(receivingChainKey = ck, nr = nr, skippedKeys = skipped)
        return state
    }
}

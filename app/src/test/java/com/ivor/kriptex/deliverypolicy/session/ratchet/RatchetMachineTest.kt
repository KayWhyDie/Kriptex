package com.ivor.kriptex.deliverypolicy.session.ratchet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RatchetMachineTest {

    private fun keyPair(seed: Int): RatchetDh.KeyPair {
        val priv = ByteArray(32) { i -> (i + seed).toByte() }
        val pub = RatchetDh.publicKeyFromPrivate(priv)
        return RatchetDh.KeyPair(privateKey = priv, publicKey = pub)
    }

    private fun initialStates(): Pair<RatchetState, RatchetState> {
        val a = keyPair(1)
        val b = keyPair(77)

        val shared = RatchetDh.dh(a.privateKey, b.publicKey)
        val rk = HkdfSha256.extract(salt = "KPX-DR-TEST".encodeToByteArray(), ikm = shared)

        val initiator = RatchetState.initialize(role = "INITIATOR", initialRootKey = rk, localDh = a, remoteDhPublicKey = b.publicKey)
        val responder = RatchetState.initialize(role = "RESPONDER", initialRootKey = rk, localDh = b, remoteDhPublicKey = a.publicKey)
        return initiator to responder
    }

    @Test
    fun out_of_order_inbound_uses_skipped_keys_and_rejects_replay() {
        val (a0, b0) = initialStates()

        val s0 = RatchetMachine.nextOutbound(a0, debug = NoOpRatchetDebugTrace, sessionId = "s")
        val a1 = s0.updated
        val s1 = RatchetMachine.nextOutbound(a1, debug = NoOpRatchetDebugTrace, sessionId = "s")
        val a2 = s1.updated
        val s2 = RatchetMachine.nextOutbound(a2, debug = NoOpRatchetDebugTrace, sessionId = "s")

        // Deliver message #2 first.
        val r2 = RatchetMachine.nextInbound(b0, header = s2.header, debug = NoOpRatchetDebugTrace, sessionId = "s")
        assertEquals(false, r2.usedSkippedKey)
        assertEquals(2, r2.updated.skippedKeys.size)

        // Now deliver message #0; should be satisfied from skipped keys.
        val r0 = RatchetMachine.nextInbound(r2.updated, header = s0.header, debug = NoOpRatchetDebugTrace, sessionId = "s")
        assertTrue(r0.usedSkippedKey)
        assertArrayEquals(s0.messageKey, r0.messageKey)

        // Replaying message #0 again should be rejected.
        var threw = false
        try {
            RatchetMachine.nextInbound(r0.updated, header = s0.header, debug = NoOpRatchetDebugTrace, sessionId = "s")
        } catch (_: Exception) {
            threw = true
        }
        assertTrue(threw)

        // Message keys should be distinct.
        assertNotEquals(s0.messageKey.toList(), s1.messageKey.toList())
        assertNotEquals(s1.messageKey.toList(), s2.messageKey.toList())
    }

    @Test
    fun dh_ratchet_sets_pending_send_and_changes_outbound_dh() {
        val (a0, b0) = initialStates()

        val oldLocalDh = a0.localDhPublicKey
        val newRemoteDh = ByteArray(32) { i -> (200 + i).toByte() }

        val inboundHeader = RatchetMessageHeader(dhPublicKey = newRemoteDh, n = 0, pn = 0)
        val afterInbound = RatchetMachine.nextInbound(a0, header = inboundHeader, debug = NoOpRatchetDebugTrace, sessionId = "s")
        assertTrue(afterInbound.updated.pendingSendDhRatchet)

        val out = RatchetMachine.nextOutbound(afterInbound.updated, debug = NoOpRatchetDebugTrace, sessionId = "s")
        assertTrue(!out.updated.pendingSendDhRatchet)
        assertEquals(0, out.header.n)
        assertNotEquals(oldLocalDh.toList(), out.header.dhPublicKey.toList())
    }
}

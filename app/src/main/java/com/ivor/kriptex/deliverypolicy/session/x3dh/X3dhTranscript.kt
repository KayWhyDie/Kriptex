package com.ivor.kriptex.deliverypolicy.session.x3dh

import com.ivor.kriptex.deliverypolicy.protocol.SessionAeadAlgorithm
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal object X3dhTranscript {

    fun transcriptHash(
        sessionId: String,
        aeadAlgorithm: SessionAeadAlgorithm,
        initiatorIdentityPublicEd: ByteArray,
        responderIdentityPublicEd: ByteArray,
        initiatorBasePublicX: ByteArray,
        responderSignedPreKeyId: Int,
        responderSignedPreKeyPublicX: ByteArray,
        responderOneTimePreKeyId: Int?,
        initiatorNonce: ByteArray,
        responderNonce: ByteArray,
    ): ByteArray {
        // Fixed-domain transcript binding.
        val header = "KPX-X3DH-TRANSCRIPT".encodeToByteArray()
        val sid = sessionId.encodeToByteArray()
        val opkId = responderOneTimePreKeyId ?: -1

        val buf = ByteBuffer.allocate(
            header.size + 1 +
                4 + sid.size +
                1 +
                32 + 32 + 32 +
                4 + 32 +
                4 +
                4 + initiatorNonce.size +
                4 + responderNonce.size
        ).order(ByteOrder.BIG_ENDIAN)

        buf.put(header)
        buf.put(0)
        buf.putInt(sid.size)
        buf.put(sid)
        buf.put(aeadAlgorithm.id.toByte())
        buf.put(initiatorIdentityPublicEd)
        buf.put(responderIdentityPublicEd)
        buf.put(initiatorBasePublicX)
        buf.putInt(responderSignedPreKeyId)
        buf.put(responderSignedPreKeyPublicX)
        buf.putInt(opkId)
        buf.putInt(initiatorNonce.size)
        buf.put(initiatorNonce)
        buf.putInt(responderNonce.size)
        buf.put(responderNonce)

        return MessageDigest.getInstance("SHA-256").digest(buf.array())
    }
}

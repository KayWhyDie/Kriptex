package com.ivor.kriptex.deliverypolicy.session

import com.ivor.kriptex.deliverypolicy.protocol.SessionAeadAlgorithm
import com.ivor.kriptex.deliverypolicy.session.crypto.AeadRuntimeSupport

interface SessionAeadSupport {
    fun preferred(): SessionAeadAlgorithm
    fun supports(algorithm: SessionAeadAlgorithm): Boolean
}

/**
 * Default support: prefer XChaCha20-Poly1305 but fall back to AES-256-GCM.
 *
 * Note: XChaCha availability depends on the installed crypto provider.
 */
object DefaultSessionAeadSupport : SessionAeadSupport {
    override fun preferred(): SessionAeadAlgorithm = SessionAeadAlgorithm.XCHACHA20_POLY1305

    override fun supports(algorithm: SessionAeadAlgorithm): Boolean {
        return when (algorithm) {
            SessionAeadAlgorithm.AES_256_GCM -> true
            SessionAeadAlgorithm.XCHACHA20_POLY1305 -> AeadRuntimeSupport.isAlgorithmAvailable(algorithm)
        }
    }
}

package com.ivor.kriptex.deliverypolicy.session.crypto

import com.ivor.kriptex.deliverypolicy.protocol.SessionAeadAlgorithm
import java.security.GeneralSecurityException
import java.security.Security
import javax.crypto.Cipher

internal object AeadRuntimeSupport {

    fun isAlgorithmAvailable(algorithm: SessionAeadAlgorithm): Boolean {
        return when (algorithm) {
            SessionAeadAlgorithm.AES_256_GCM -> true
            SessionAeadAlgorithm.XCHACHA20_POLY1305 -> isXChaChaAvailable()
        }
    }

    fun ensureXChaChaProvider() {
        if (Security.getProvider("SC") != null) return
        try {
            val clazz = Class.forName("org.spongycastle.jce.provider.BouncyCastleProvider")
            val provider = clazz.getDeclaredConstructor().newInstance() as java.security.Provider
            Security.addProvider(provider)
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun isXChaChaAvailable(): Boolean {
        ensureXChaChaProvider()
        val candidates = listOf("XChaCha20-Poly1305", "XCHACHA20-POLY1305")
        for (c in candidates) {
            try {
                Cipher.getInstance(c, "SC")
                return true
            } catch (_: Throwable) {
                // keep trying
            }
        }
        return false
    }

    fun xchachaCipherInstance(): Cipher {
        ensureXChaChaProvider()
        val candidates = listOf("XChaCha20-Poly1305", "XCHACHA20-POLY1305")
        val errors = ArrayList<Throwable>()
        for (c in candidates) {
            try {
                return Cipher.getInstance(c, "SC")
            } catch (t: Throwable) {
                errors.add(t)
            }
        }
        throw GeneralSecurityException("xchacha_unavailable")
    }
}

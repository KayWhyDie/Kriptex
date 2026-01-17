package com.ivor.kriptex.deliverypolicy.protocol

/**
 * AEAD algorithm negotiated during session establishment.
 */
enum class SessionAeadAlgorithm(val id: Int) {
    XCHACHA20_POLY1305(1),
    AES_256_GCM(2),
    ;

    companion object {
        fun fromId(id: Int): SessionAeadAlgorithm {
            return entries.firstOrNull { it.id == id } ?: AES_256_GCM
        }
    }
}

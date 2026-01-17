package com.ivor.kriptex.deliverypolicy.protocol

/**
 * Protocol codec version used on the wire.
 *
 * Exposed so other layers (e.g., session AEAD AAD) can bind to the same version
 * without reaching into codec internals.
 */
object ProtocolVersion {
    const val CURRENT: Int = 1
}

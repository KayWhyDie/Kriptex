package com.ivor.kriptex.deliverypolicy.protocol

interface ProtocolEncoder {
    fun encode(message: ProtocolMessage): ByteArray
}

interface ProtocolDecoder {
    fun decode(bytes: ByteArray): ProtocolMessage
}

class ProtocolWireFormatException(message: String) : IllegalArgumentException(message)

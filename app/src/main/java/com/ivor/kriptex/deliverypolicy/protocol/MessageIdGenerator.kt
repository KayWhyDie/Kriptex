package com.ivor.kriptex.deliverypolicy.protocol

interface MessageIdGenerator {
    fun nextId(): String
}

class IncrementingMessageIdGenerator(
    private val prefix: String = "m",
    start: Int = 1,
) : MessageIdGenerator {

    private var next = start

    @Synchronized
    override fun nextId(): String {
        val id = "$prefix${next}"
        next += 1
        return id
    }
}

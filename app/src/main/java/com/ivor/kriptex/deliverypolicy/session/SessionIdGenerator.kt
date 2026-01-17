package com.ivor.kriptex.deliverypolicy.session

interface SessionIdGenerator {
    fun nextSessionId(): String
}

class IncrementingSessionIdGenerator(
    private val prefix: String = "s",
    start: Int = 1,
) : SessionIdGenerator {

    private var next = start

    @Synchronized
    override fun nextSessionId(): String {
        val id = "$prefix${next}"
        next += 1
        return id
    }
}

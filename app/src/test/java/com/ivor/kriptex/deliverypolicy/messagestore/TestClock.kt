package com.ivor.kriptex.deliverypolicy.messagestore

import com.ivor.kriptex.deliverypolicy.Clock

internal class TestClock(private var now: Long = 0L) : Clock {
    override fun nowMs(): Long = now

    fun set(ms: Long) {
        now = ms
    }

    fun advanceBy(deltaMs: Long) {
        now += deltaMs
    }
}

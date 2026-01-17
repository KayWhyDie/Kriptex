package com.ivor.kriptex.deliverypolicy

interface Clock {
    fun nowMs(): Long
}

object MonotonicClock : Clock {
    override fun nowMs(): Long = System.nanoTime() / 1_000_000L
}

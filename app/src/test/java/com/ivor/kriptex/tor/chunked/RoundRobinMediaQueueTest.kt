package com.ivor.kriptex.tor.chunked

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoundRobinMediaQueueTest {

    @Test
    fun `round robin cycles fairly`() {
        val q = RoundRobinMediaQueue()
        q.offer("a")
        q.offer("b")
        q.offer("c")

        assertEquals("a", q.next(emptySet()))
        assertEquals("b", q.next(emptySet()))
        assertEquals("c", q.next(emptySet()))
        assertEquals("a", q.next(emptySet()))
    }

    @Test
    fun `skips in-flight media`() {
        val q = RoundRobinMediaQueue()
        q.offer("a")
        q.offer("b")
        q.offer("c")

        val inFlight = setOf("b")
        assertEquals("a", q.next(inFlight))
        assertEquals("c", q.next(inFlight))
        assertEquals("a", q.next(inFlight))
    }

    @Test
    fun `returns null when all in flight`() {
        val q = RoundRobinMediaQueue()
        q.offer("a")
        q.offer("b")

        assertNull(q.next(setOf("a", "b")))
    }

    @Test
    fun `remove drops from rotation`() {
        val q = RoundRobinMediaQueue()
        q.offer("a")
        q.offer("b")
        q.offer("c")
        q.remove("b")

        assertEquals("a", q.next(emptySet()))
        assertEquals("c", q.next(emptySet()))
        assertEquals("a", q.next(emptySet()))
    }
}

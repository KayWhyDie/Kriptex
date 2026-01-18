package com.ivor.kriptex.tor.chunked

import org.junit.Test

class ChunkedMediaLimitsTest {

    @Test
    fun `accepts consistent params`() {
        val size = 1024L
        val chunkSize = 256
        val totalChunks = ChunkedMediaLimits.expectedTotalChunks(size, chunkSize).toInt()
        ChunkedMediaLimits.validateOrThrow(size, chunkSize, totalChunks)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects plaintext too large`() {
        ChunkedMediaLimits.validateOrThrow(
            ChunkedMediaLimits.MAX_PLAINTEXT_SIZE_BYTES + 1,
            1024,
            1
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects total chunks mismatch`() {
        ChunkedMediaLimits.validateOrThrow(
            1024L,
            512,
            999
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects too many chunks`() {
        val size = 1024L
        val chunkSize = 1
        ChunkedMediaLimits.validateOrThrow(size, chunkSize, ChunkedMediaLimits.MAX_TOTAL_CHUNKS + 1)
    }
}

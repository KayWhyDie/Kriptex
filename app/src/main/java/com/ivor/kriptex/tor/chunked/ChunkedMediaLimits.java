package com.ivor.kriptex.tor.chunked;

/**
 * Phase 3.5 caps for chunked E2EE media downloads.
 *
 * Pure-JVM (no Android deps) so it can be unit tested.
 */
public final class ChunkedMediaLimits {

    private ChunkedMediaLimits() {
    }

    // Max plaintext size we will accept for a chunked attachment.
    public static final long MAX_PLAINTEXT_SIZE_BYTES = 256L * 1024L * 1024L; // 256 MiB

    // Max chunk size to prevent huge per-request memory/disk pressure.
    public static final int MAX_CHUNK_SIZE_BYTES = 1024 * 1024; // 1 MiB

    // Max number of chunks to bound bitmap size and O(n) scans.
    public static final int MAX_TOTAL_CHUNKS = 4096;

    // Max incomplete (not-yet-downloaded) chunked entries to keep around.
    public static final int MAX_INCOMPLETE_ENTRIES = 24;

    // Max concurrently active chunked download tasks.
    public static final int MAX_CONCURRENT_CHUNKED_TASKS = 3;

    public static int bitmapBytesForTotalChunks(int totalChunks) {
        if (totalChunks <= 0) return 0;
        return (totalChunks + 7) / 8;
    }

    /**
     * Validates chunked parameters. Throws IllegalArgumentException on rejection.
     *
     * Rejections are designed to be stable/idempotent and do not depend on attacker-controlled indices.
     */
    public static void validateOrThrow(long plaintextSizeBytes, int chunkSizeBytes, int totalChunks) {
        if (plaintextSizeBytes < 0) throw new IllegalArgumentException("bad_plaintext_size");
        if (plaintextSizeBytes > MAX_PLAINTEXT_SIZE_BYTES) throw new IllegalArgumentException("plaintext_too_large");

        if (chunkSizeBytes <= 0) throw new IllegalArgumentException("bad_chunk_size");
        if (chunkSizeBytes > MAX_CHUNK_SIZE_BYTES) throw new IllegalArgumentException("chunk_size_too_large");

        if (totalChunks <= 0) throw new IllegalArgumentException("bad_total_chunks");
        if (totalChunks > MAX_TOTAL_CHUNKS) throw new IllegalArgumentException("too_many_chunks");

        // Ensure totalChunks is consistent with plaintextSize/chunkSize to prevent bitmap amplification.
        long expected = expectedTotalChunks(plaintextSizeBytes, chunkSizeBytes);
        if (expected != (long) totalChunks) {
            throw new IllegalArgumentException("total_chunks_mismatch");
        }

        // Extra sanity: ensure bitmap size is bounded by our chunk cap.
        int bmBytes = bitmapBytesForTotalChunks(totalChunks);
        if (bmBytes <= 0 || bmBytes > bitmapBytesForTotalChunks(MAX_TOTAL_CHUNKS)) {
            throw new IllegalArgumentException("bitmap_out_of_bounds");
        }
    }

    public static long expectedTotalChunks(long plaintextSizeBytes, int chunkSizeBytes) {
        if (chunkSizeBytes <= 0) throw new IllegalArgumentException("bad_chunk_size");
        if (plaintextSizeBytes <= 0) return 1;
        return (plaintextSizeBytes + (long) chunkSizeBytes - 1L) / (long) chunkSizeBytes;
    }
}

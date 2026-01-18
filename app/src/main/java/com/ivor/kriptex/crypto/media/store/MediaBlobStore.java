package com.ivor.kriptex.crypto.media.store;

import android.content.Context;

import java.io.File;

/**
 * Minimal indirection layer for media blobs.
 *
 * <p>Today, an E2EE attachment is a single ciphertext blob file on disk.
 * This abstraction prepares for future chunked/streaming storage without
 * changing the Tor FileServer behavior or message transport.</p>
 */
public interface MediaBlobStore {

    /**
     * Returns the on-disk ciphertext blob file for a given blob id.
     *
     * <p>Current format is a single file that begins with a 24-byte XChaCha nonce.</p>
     */
    File ciphertextBlobFile(Context context, String blobId);

    /**
     * Returns the encrypted manifest file for chunked media.
     */
    File chunkedManifestFile(Context context, String mediaId);

    /**
     * Returns the encrypted chunk file for chunked media.
     */
    File chunkedChunkFile(Context context, String mediaId, int chunkIndex);

    /**
     * Temporary download location for the manifest (supports resume/retry without clobbering verified state).
     */
    File chunkedManifestTempFile(Context context, String mediaId);

    /**
     * Temporary download location for a chunk.
     */
    File chunkedChunkTempFile(Context context, String mediaId, int chunkIndex);
}

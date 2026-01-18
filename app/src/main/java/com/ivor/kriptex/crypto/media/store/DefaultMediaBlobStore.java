package com.ivor.kriptex.crypto.media.store;

import android.content.Context;

import java.io.File;

/**
 * Default (non-chunked) blob store implementation.
 *
 * <p>Maps blobId -> filesDir/media/{blobId}.bin</p>
 */
public final class DefaultMediaBlobStore implements MediaBlobStore {

    @Override
    public File ciphertextBlobFile(Context context, String blobId) {
        if (context == null) throw new IllegalArgumentException("context is null");
        if (blobId == null || blobId.trim().isEmpty()) {
            throw new IllegalArgumentException("blobId empty");
        }
        File dir = new File(context.getFilesDir(), "media");
        return new File(dir, blobId + ".bin");
    }

    @Override
    public File chunkedManifestFile(Context context, String mediaId) {
        if (context == null) throw new IllegalArgumentException("context is null");
        if (mediaId == null || mediaId.trim().isEmpty()) throw new IllegalArgumentException("mediaId empty");
        File dir = new File(new File(context.getFilesDir(), "media"), mediaId);
        return new File(dir, "manifest.bin");
    }

    @Override
    public File chunkedChunkFile(Context context, String mediaId, int chunkIndex) {
        if (context == null) throw new IllegalArgumentException("context is null");
        if (mediaId == null || mediaId.trim().isEmpty()) throw new IllegalArgumentException("mediaId empty");
        if (chunkIndex < 0) throw new IllegalArgumentException("chunkIndex < 0");
        File dir = new File(new File(new File(context.getFilesDir(), "media"), mediaId), "chunks");
        return new File(dir, "chunk_" + chunkIndex + ".bin");
    }

    @Override
    public File chunkedManifestTempFile(Context context, String mediaId) {
        if (context == null) throw new IllegalArgumentException("context is null");
        if (mediaId == null || mediaId.trim().isEmpty()) throw new IllegalArgumentException("mediaId empty");
        File dir = new File(new File(context.getFilesDir(), "media"), mediaId);
        return new File(dir, "manifest.bin.tmpdl");
    }

    @Override
    public File chunkedChunkTempFile(Context context, String mediaId, int chunkIndex) {
        if (context == null) throw new IllegalArgumentException("context is null");
        if (mediaId == null || mediaId.trim().isEmpty()) throw new IllegalArgumentException("mediaId empty");
        if (chunkIndex < 0) throw new IllegalArgumentException("chunkIndex < 0");
        File dir = new File(new File(new File(context.getFilesDir(), "media"), mediaId), "chunks");
        return new File(dir, "chunk_" + chunkIndex + ".bin.tmpdl");
    }
}

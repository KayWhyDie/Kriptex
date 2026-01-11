package com.jarvanmo.exoplayerview.media;

import android.net.Uri;

import java.io.File;

/**
 * Minimal compatibility shim for the historical ExoVideoView library.
 *
 * This project previously depended on a third-party wrapper. To keep the
 * project buildable without that dependency, we model only what the app uses.
 */
public final class SimpleMediaSource {
    private final String filePath;

    public SimpleMediaSource(String filePath) {
        this.filePath = filePath;
    }

    public String getFilePath() {
        return filePath;
    }

    public Uri getUri() {
        if (filePath == null) {
            return Uri.EMPTY;
        }
        return Uri.fromFile(new File(filePath));
    }
}

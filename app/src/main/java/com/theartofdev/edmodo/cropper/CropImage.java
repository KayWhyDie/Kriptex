package com.theartofdev.edmodo.cropper;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.Nullable;

/**
 * Minimal stub for android-image-cropper.
 * Provides only the APIs referenced by this codebase.
 */
public final class CropImage {

    public static final int CROP_IMAGE_ACTIVITY_REQUEST_CODE = 203;
    public static final int CROP_IMAGE_ACTIVITY_RESULT_ERROR_CODE = 204;

    private CropImage() {
    }

    public static ActivityBuilder activity() {
        return new ActivityBuilder();
    }

    @Nullable
    public static ActivityResult getActivityResult(@Nullable Intent data) {
        // This stub doesn't encode/decode real results.
        return new ActivityResult(null, null);
    }

    public static final class ActivityBuilder {
        public ActivityBuilder setGuidelines(CropImageView.Guidelines guidelines) {
            return this;
        }

        public ActivityBuilder setOutputUri(Uri uri) {
            return this;
        }

        public ActivityBuilder setFixAspectRatio(boolean fix) {
            return this;
        }

        public void start(Activity activity) {
            // no-op stub
        }
    }

    public static final class ActivityResult {
        @Nullable
        private final Uri uri;
        @Nullable
        private final Exception error;

        public ActivityResult(@Nullable Uri uri, @Nullable Exception error) {
            this.uri = uri;
            this.error = error;
        }

        @Nullable
        public Uri getUri() {
            return uri;
        }

        @Nullable
        public Exception getError() {
            return error;
        }
    }
}

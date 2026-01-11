package com.stfalcon.imageviewer;

import android.content.Context;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * Minimal stub for stfalcon-imageviewer to keep the project compiling.
 */
public class StfalconImageViewer<T> {

    public interface ImageLoader<T> {
        void loadImage(@NonNull ImageView imageView, @NonNull T image);
    }

    public interface OnImageChangeListener {
        void onImageChange(int position);
    }

    private StfalconImageViewer() {
    }

    public void show(boolean animate) {
        // no-op stub
    }

    public static final class Builder<T> {
        @SuppressWarnings("unused")
        private final Context context;
        @SuppressWarnings("unused")
        private final List<T> images;
        @SuppressWarnings("unused")
        private final ImageLoader<T> imageLoader;

        public Builder(@NonNull Context context, @NonNull List<T> images, @NonNull ImageLoader<T> imageLoader) {
            this.context = context;
            this.images = images;
            this.imageLoader = imageLoader;
        }

        public Builder<T> withOverlayView(@Nullable View overlay) {
            return this;
        }

        public Builder<T> withStartPosition(int position) {
            return this;
        }

        public Builder<T> allowZooming(boolean allow) {
            return this;
        }

        public Builder<T> withImageChangeListener(@Nullable OnImageChangeListener listener) {
            return this;
        }

        public StfalconImageViewer<T> build() {
            return new StfalconImageViewer<>();
        }
    }
}

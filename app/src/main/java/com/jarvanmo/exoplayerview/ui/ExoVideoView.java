package com.jarvanmo.exoplayerview.ui;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.PlayerView;
import com.jarvanmo.exoplayerview.media.SimpleMediaSource;
import com.jarvanmo.exoplayerview.orientation.OnOrientationChangedListener;

/**
 * Minimal compatibility implementation for the historical third-party ExoVideoView.
 *
 * Goal: keep this project buildable and allow basic local-file playback.
 */
public class ExoVideoView extends PlayerView {

    public interface BackListener {
        /** Return true if back press is consumed. */
        boolean onBack(ExoVideoView view, boolean isPortrait);
    }

    private @Nullable ExoPlayer player;
    private @Nullable BackListener backListener;
    private @Nullable OnOrientationChangedListener orientationListener;

    public ExoVideoView(Context context) {
        super(context);
        init(context);
    }

    public ExoVideoView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ExoVideoView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        // Create lazily on first play() to reduce overhead.
        setUseController(true);
    }

    public void setBackListener(@Nullable BackListener backListener) {
        this.backListener = backListener;
    }

    public void setOrientationListener(@Nullable OnOrientationChangedListener listener) {
        this.orientationListener = listener;
    }

    public void play(SimpleMediaSource source) {
        if (player == null) {
            player = new ExoPlayer.Builder(getContext()).build();
            setPlayer(player);
        }

        MediaItem item = MediaItem.fromUri(source.getUri());
        player.setMediaItem(item);
        player.prepare();
        player.setPlayWhenReady(true);
    }

    public void resume() {
        if (player != null) {
            player.setPlayWhenReady(true);
        }
    }

    public void pause() {
        if (player != null) {
            player.setPlayWhenReady(false);
        }
    }

    public void releasePlayer() {
        if (player != null) {
            Player old = player;
            player = null;
            old.release();
            setPlayer(null);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        // Preserve old behavior: let the activity delegate BACK to us.
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (backListener != null) {
                // We do not implement orientation detection; default to portrait.
                boolean consumed = backListener.onBack(this, true);
                if (consumed) {
                    return true;
                }
            }
        }
        return super.onKeyDown(keyCode, event);
    }
}

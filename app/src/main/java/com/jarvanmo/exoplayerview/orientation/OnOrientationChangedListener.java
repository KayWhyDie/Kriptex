package com.jarvanmo.exoplayerview.orientation;

/**
 * Minimal compatibility interface for legacy ExoVideoView usage.
 */
public interface OnOrientationChangedListener {
    int SENSOR_PORTRAIT = 1;
    int SENSOR_LANDSCAPE = 2;

    void onOrientationChanged(int orientation);
}

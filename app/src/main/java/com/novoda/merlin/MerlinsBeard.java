package com.novoda.merlin;

import android.content.Context;

import androidx.annotation.NonNull;

/** Minimal stub for Novoda MerlinsBeard. */
public class MerlinsBeard {

    public static final class Builder {
        public MerlinsBeard build(@NonNull Context context) {
            return new MerlinsBeard();
        }
    }

    public boolean isConnected() {
        return true;
    }
}

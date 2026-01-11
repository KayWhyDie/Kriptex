package com.novoda.merlin;

import android.content.Context;

import androidx.annotation.NonNull;

/** Minimal stub for Novoda Merlin connectivity library. */
public class Merlin {

    public interface Connectable {
        void onConnect();
    }

    public interface Disconnectable {
        void onDisconnect();
    }

    public static final class Builder {
        public Builder withConnectableCallbacks() {
            return this;
        }

        public Builder withAllCallbacks() {
            return this;
        }

        public Merlin build(@NonNull Context context) {
            return new Merlin();
        }
    }

    public void bind() {
        // no-op stub
    }

    public void unbind() {
        // no-op stub
    }

    public void registerConnectable(@NonNull Connectable connectable) {
        // no-op stub
    }

    public void registerDisconnectable(@NonNull Disconnectable disconnectable) {
        // no-op stub
    }
}

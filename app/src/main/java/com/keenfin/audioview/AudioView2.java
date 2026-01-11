package com.keenfin.audioview;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.FrameLayout;

import androidx.annotation.LayoutRes;
import androidx.annotation.Nullable;

import com.ivor.kriptex.R;

/**
 * Minimal compatibility shim for the AudioView2 widget previously provided by a third-party library.
 *
 * This implementation inflates an optional control layout and exposes the subset of APIs used by the
 * app so the project remains buildable.
 */
public class AudioView2 extends FrameLayout {

    private boolean attached;

    public AudioView2(Context context) {
        super(context);
        init(context, null);
    }

    public AudioView2(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public AudioView2(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    private void init(Context context, @Nullable AttributeSet attrs) {
        @LayoutRes int customLayout = 0;
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.AudioView2);
            try {
                customLayout = a.getResourceId(R.styleable.AudioView2_customLayout, 0);
            } finally {
                a.recycle();
            }
        }

        if (customLayout != 0) {
            LayoutInflater.from(context).inflate(customLayout, this, true);
        }
    }

    public boolean attached() {
        return attached;
    }

    public void setUpControls() {
        attached = true;
    }

    public void setDataSource(@Nullable String filePath) throws java.io.IOException {
        // No-op shim: kept only to satisfy compilation.
    }
}

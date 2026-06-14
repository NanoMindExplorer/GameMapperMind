package com.nanomindexplorer.gamemappermind;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.FrameLayout;

public class InterceptFrameLayout extends FrameLayout {
    
    public interface InputEventListener {
        boolean onGamepadEvent(MotionEvent event);
        boolean onKeyEvent(KeyEvent event);
    }

    private InputEventListener listener;

    public InterceptFrameLayout(Context context) {
        super(context);
    }

    public InterceptFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setInputEventListener(InputEventListener listener) {
        this.listener = listener;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (listener != null) {
            listener.onGamepadEvent(event);
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (listener != null) {
            listener.onKeyEvent(event);
        }
        return super.dispatchKeyEvent(event);
    }
}

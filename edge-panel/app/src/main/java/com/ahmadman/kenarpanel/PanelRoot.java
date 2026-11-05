package com.ahmadman.kenarpanel;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.FrameLayout;

/** Full-screen panel window: a tap on the dimmed area or the back key closes it. */
public class PanelRoot extends FrameLayout {

    interface OnClose {
        void close();
    }

    private OnClose onClose;

    public PanelRoot(Context c) {
        super(c);
    }

    public PanelRoot(Context c, AttributeSet a) {
        super(c, a);
    }

    void setOnClose(OnClose l) {
        onClose = l;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP && onClose != null) {
                onClose.close();
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            return true;
        }
        if (e.getAction() == MotionEvent.ACTION_UP && onClose != null) {
            performClick();
            onClose.close();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}

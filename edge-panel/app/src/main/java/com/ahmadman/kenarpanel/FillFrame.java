package com.ahmadman.kenarpanel;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Takes exactly the height its parent hands out (the space left in the Android TV page) and
 * asks for none of its own, so its contents can never make the remote card bigger.
 */
public class FillFrame extends FrameLayout {

    public FillFrame(Context c) {
        super(c);
    }

    public FillFrame(Context c, AttributeSet a) {
        super(c, a);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        if (View.MeasureSpec.getMode(heightSpec) != View.MeasureSpec.EXACTLY) {
            setMeasuredDimension(View.MeasureSpec.getSize(widthSpec), 0);
            return;
        }
        super.onMeasure(widthSpec, heightSpec);
    }
}

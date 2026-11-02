package com.promodj.trendy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.widget.ImageView;

/**
 * ImageView со скруглёнными углами — обложки в списке и плеере.
 */
public class RoundedImageView extends ImageView {

    private final Path clipPath = new Path();
    private final RectF clipRect = new RectF();
    private float cornerRadius;

    public RoundedImageView(Context context) {
        super(context);
        init(context);
    }

    public RoundedImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public RoundedImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        cornerRadius = Ui.dp(context, 12);
        setWillNotDraw(false);
    }

    public void setCornerRadius(float pixels) {
        cornerRadius = pixels;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0 || cornerRadius <= 0f) {
            super.onDraw(canvas);
            return;
        }
        clipPath.reset();
        clipRect.set(0f, 0f, width, height);
        clipPath.addRoundRect(clipRect, cornerRadius, cornerRadius, Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(clipPath);
        super.onDraw(canvas);
        canvas.restoreToCount(save);
    }
}

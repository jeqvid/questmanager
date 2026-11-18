package com.jeqvid.QuestManager;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import java.util.Random;

/**
 * Little white circles falling like snow. Drawn on top of the whole panel but never takes clicks or hover,
 * so everything underneath works normally.
 */
public class SnowView extends View {

    private static final int COUNT = 70;

    private final float[] x = new float[COUNT], y = new float[COUNT], radius = new float[COUNT];
    private final float[] speed = new float[COUNT], sway = new float[COUNT], phase = new float[COUNT];
    private final int[] alpha = new int[COUNT];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private final float density;
    private long lastFrame;
    private float time;
    private boolean ready;

    public SnowView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        paint.setColor(Color.WHITE);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        for (int i = 0; i < COUNT; i++) reset(i, w, h, true);
        ready = w > 0 && h > 0;
    }

    private void reset(int i, int w, int h, boolean anywhere) {
        radius[i] = density * (0.8f + random.nextFloat() * 1.8f);
        x[i] = random.nextFloat() * w;
        y[i] = anywhere ? random.nextFloat() * h : -radius[i] * 2;
        speed[i] = density * (14 + random.nextFloat() * 36);  // dp per second
        sway[i] = density * (4 + random.nextFloat() * 10);
        phase[i] = random.nextFloat() * 6.283f;
        alpha[i] = 70 + random.nextInt(140);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!ready) return;
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0 : Math.min(0.05f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        time += dt;
        int w = getWidth(), h = getHeight();
        for (int i = 0; i < COUNT; i++) {
            y[i] += speed[i] * dt;
            float drawX = x[i] + (float) Math.sin(time * 0.8f + phase[i]) * sway[i];
            if (y[i] - radius[i] > h) reset(i, w, h, false);
            paint.setAlpha(alpha[i]);
            canvas.drawCircle(drawX, y[i], radius[i], paint);
        }
        postInvalidateOnAnimation();
    }
}

package com.andre.hibyr4.settings;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * LED colour picker: hue/saturation wheel with a live selector, plus a row of preset swatches underneath.
 * Reports colours as "rrggbb" - while dragging at most every 40 ms (each change rewrites the LED), and on release.
 */
public class ColorPickerView extends View {

    public interface Listener { void onColor(String hex, boolean finalValue); }

    static final String[] PRESET_NAMES = {"HiBy blue", "Red", "Orange", "Yellow", "Green", "Cyan", "Blue", "Purple", "Pink", "White"};
    static final String[] PRESETS = {"00a0ff", "ff0000", "ff6000", "ffd000", "00ff00", "00ffff", "0000ff", "8000ff", "ff40a0", "ffffff"};

    private final Listener listener;
    private final float dp;
    private final Paint wheel = new Paint(Paint.ANTI_ALIAS_FLAG), sel = new Paint(Paint.ANTI_ALIAS_FLAG),
            ring = new Paint(Paint.ANTI_ALIAS_FLAG), shadow = new Paint(Paint.ANTI_ALIAS_FLAG),
            sw = new Paint(Paint.ANTI_ALIAS_FLAG), swRing = new Paint(Paint.ANTI_ALIAS_FLAG),
            text = new Paint(Paint.ANTI_ALIAS_FLAG), outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] hsv = {200, 1, 1};
    private float cx, cy, radius, swY, swR, swGap, swX0;
    private boolean dragging, enabled = true;
    private long lastSent;
    private int accent;
    private String exact;   // exact hex while the colour came from a swatch / saved value (HSV round trip can be off by 1)

    public ColorPickerView(Context c, String hex, Listener l) {
        super(c);
        listener = l;
        dp = c.getResources().getDisplayMetrics().density;
        TypedArray a = c.obtainStyledAttributes(new int[]{android.R.attr.colorAccent, android.R.attr.textColorSecondary});
        accent = a.getColor(0, 0xff3d7cff);
        int textSecondary = a.getColor(1, 0xff707070);
        a.recycle();
        ring.setColor(0xffffffff);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(3 * dp);
        shadow.setColor(0x40000000);
        swRing.setColor(accent);
        swRing.setStyle(Paint.Style.STROKE);
        swRing.setStrokeWidth(2.5f * dp);
        outline.setColor((textSecondary & 0x00ffffff) | 0x40000000);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(dp);
        text.setColor(textSecondary);
        text.setTextSize(13 * dp);
        text.setTextAlign(Paint.Align.CENTER);
        setColor(hex);
        setLayoutParams(new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, (int) (330 * dp)));
    }

    public void setColor(String hex) {
        try { Color.colorToHSV(Color.parseColor("#" + hex), hsv); exact = hex.toLowerCase(); } catch (RuntimeException ignored) { }
        if (hsv[2] != 1) exact = null;   // the LED brightness is separate: keep hue/saturation, full value
        hsv[2] = 1;
        invalidate();
    }

    public void setActive(boolean on) {
        enabled = on;
        setAlpha(on ? 1f : 0.4f);
    }

    public String hex() { return exact != null ? exact : String.format("%06x", Color.HSVToColor(hsv) & 0xffffff); }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        radius = Math.min(w, 250 * dp) / 2f - 14 * dp;
        cx = w / 2f;
        cy = 14 * dp + radius;
        swR = 15 * dp;
        swGap = Math.min(12 * dp, (w - 32 * dp - PRESETS.length * 2 * swR) / (PRESETS.length - 1));
        float total = PRESETS.length * 2 * swR + (PRESETS.length - 1) * swGap;
        swX0 = (w - total) / 2f + swR;
        swY = cy + radius + 40 * dp;
        int[] hues = new int[13];
        for (int i = 0; i < hues.length; i++) hues[i] = Color.HSVToColor(new float[]{(360f - i * 30f) % 360f, 1, 1});
        Shader sweep = new SweepGradient(cx, cy, hues, null);
        Shader radial = new RadialGradient(cx, cy, radius, 0xffffffff, 0x00ffffff, Shader.TileMode.CLAMP);
        wheel.setShader(new ComposeShader(sweep, radial, PorterDuff.Mode.SRC_OVER));
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawCircle(cx, cy + 2 * dp, radius + 2 * dp, shadow);
        c.drawCircle(cx, cy, radius, wheel);
        c.drawCircle(cx, cy, radius, outline);
        // selector at hue (angle, counter-clockwise like the sweep) / saturation (distance)
        double ang = Math.toRadians(-hsv[0]);
        float sx = cx + (float) (Math.cos(ang) * hsv[1] * radius), sy = cy + (float) (Math.sin(ang) * hsv[1] * radius);
        sel.setColor(Color.HSVToColor(hsv));
        float r = (dragging ? 18 : 14) * dp;
        c.drawCircle(sx, sy + 1.5f * dp, r + 1.5f * dp, shadow);
        c.drawCircle(sx, sy, r, sel);
        c.drawCircle(sx, sy, r, ring);
        // swatches
        String cur = hex();
        for (int i = 0; i < PRESETS.length; i++) {
            float x = swX0 + i * (2 * swR + swGap);
            sw.setColor(Color.parseColor("#" + PRESETS[i]));
            c.drawCircle(x, swY, swR, sw);
            c.drawCircle(x, swY, swR, outline);
            if (PRESETS[i].equals(cur)) c.drawCircle(x, swY, swR + 4 * dp, swRing);
        }
        c.drawText(name(cur), cx, swY + swR + 26 * dp, text);
    }

    static String name(String hex) {
        for (int i = 0; i < PRESETS.length; i++) if (PRESETS[i].equals(hex)) return PRESET_NAMES[i];
        return "Custom  #" + hex.toUpperCase();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!enabled) return false;
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (Math.hypot(x - cx, y - cy) <= radius + 16 * dp) {
                    dragging = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    pick(x, y, false);
                    return true;
                }
                if (Math.abs(y - swY) <= swR + 10 * dp) return true;   // swatch tap -> handled on UP
                return false;
            case MotionEvent.ACTION_MOVE:
                if (dragging) pick(x, y, false);
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    pick(x, y, true);
                } else if (Math.abs(y - swY) <= swR + 10 * dp) {
                    for (int i = 0; i < PRESETS.length; i++) {
                        if (Math.abs(x - (swX0 + i * (2 * swR + swGap))) <= swR + swGap / 2) {
                            setColor(PRESETS[i]);
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                            if (listener != null) listener.onColor(hex(), true);
                            break;
                        }
                    }
                }
                // fall through
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                invalidate();
                return true;
        }
        return false;
    }

    private void pick(float x, float y, boolean fin) {
        double dx = x - cx, dy = y - cy;
        float s = (float) Math.min(1, Math.hypot(dx, dy) / radius);
        float h = (float) Math.toDegrees(Math.atan2(-dy, dx));
        if (h < 0) h += 360;
        exact = null;
        // snap the centre to pure white
        hsv[0] = h;
        hsv[1] = s < 0.06f ? 0 : s;
        hsv[2] = 1;
        invalidate();
        long now = SystemClock.uptimeMillis();
        if (listener != null && (fin || now - lastSent > 40)) {
            lastSent = now;
            listener.onColor(hex(), fin);
        }
    }
}

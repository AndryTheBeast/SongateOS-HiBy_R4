package com.andre.hibyr4.settings;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** Graphic equalizer: response curve through 10 draggable band points (31 Hz - 16 kHz, -12..+12 dB). */
public class EqGraphView extends View {

    public interface Listener { void onBandChanged(int band, int db); }

    private static final String[] LABELS = {"31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"};
    private final int[] gains = new int[Hb.BANDS];
    private final Listener listener;
    private final float dp;
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG), zero = new Paint(Paint.ANTI_ALIAS_FLAG),
            curve = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            knob = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG),
            label = new Paint(Paint.ANTI_ALIAS_FLAG), value = new Paint(Paint.ANTI_ALIAS_FLAG),
            bubble = new Paint(Paint.ANTI_ALIAS_FLAG), card = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int accent, textPrimary, textSecondary;
    private final Path path = new Path(), fillPath = new Path();
    private final RectF area = new RectF(), tmp = new RectF();
    private int active = -1;
    private boolean enabled = true;

    public EqGraphView(Context c, int[] initial, Listener l) {
        super(c);
        listener = l;
        dp = c.getResources().getDisplayMetrics().density;
        TypedArray a = c.obtainStyledAttributes(new int[]{android.R.attr.colorAccent,
                android.R.attr.textColorPrimary, android.R.attr.textColorSecondary});
        accent = a.getColor(0, 0xff3d7cff);
        textPrimary = a.getColor(1, 0xff202020);
        textSecondary = a.getColor(2, 0xff707070);
        a.recycle();

        card.setColor(withAlpha(accent, 0x14));
        grid.setColor(withAlpha(textSecondary, 0x30));
        grid.setStrokeWidth(dp);
        zero.setColor(withAlpha(textSecondary, 0x70));
        zero.setStrokeWidth(1.5f * dp);
        curve.setColor(accent);
        curve.setStyle(Paint.Style.STROKE);
        curve.setStrokeWidth(3 * dp);
        curve.setStrokeCap(Paint.Cap.ROUND);
        curve.setStrokeJoin(Paint.Join.ROUND);
        knob.setColor(accent);
        ring.setColor(0xffffffff);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2.5f * dp);
        label.setColor(textSecondary);
        label.setTextSize(11 * dp);
        label.setTextAlign(Paint.Align.CENTER);
        value.setColor(0xffffffff);
        value.setTextSize(12 * dp);
        value.setFakeBoldText(true);
        value.setTextAlign(Paint.Align.CENTER);
        bubble.setColor(accent);
        setGains(initial);
        setLayoutParams(new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, (int) (250 * dp)));
    }

    public void setGains(int[] g) {
        for (int i = 0; i < Hb.BANDS; i++) gains[i] = Math.max(-Hb.MAX_DB, Math.min(Hb.MAX_DB, g[i]));
        invalidate();
    }

    public void setActive(boolean on) {
        enabled = on;
        setAlpha(on ? 1f : 0.45f);
    }

    private static int withAlpha(int color, int alpha) { return (color & 0x00ffffff) | (alpha << 24); }

    private float bx(int i) { return area.left + area.width() * (i + 0.5f) / Hb.BANDS; }
    private float y(float db) { return area.centerY() - db / Hb.MAX_DB * area.height() / 2f; }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float pad = 16 * dp;
        tmp.set(pad, 4 * dp, w - pad, h - 4 * dp);
        c.drawRoundRect(tmp, 20 * dp, 20 * dp, card);
        area.set(pad + 30 * dp, 34 * dp, w - pad - 12 * dp, h - 34 * dp);

        // grid: +12/+6/0/-6/-12 dB and one vertical line per band
        for (int db = -12; db <= 12; db += 6) {
            float yy = y(db);
            c.drawLine(area.left, yy, area.right, yy, db == 0 ? zero : grid);
            label.setTextAlign(Paint.Align.RIGHT);
            c.drawText((db > 0 ? "+" : "") + db, area.left - 8 * dp, yy + 4 * dp, label);
        }
        label.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < Hb.BANDS; i++) {
            float x = bx(i);
            c.drawLine(x, area.top, x, area.bottom, grid);
            c.drawText(LABELS[i], x, area.bottom + 22 * dp, label);
        }

        // smooth curve (Catmull-Rom -> cubic Bezier), flat beyond the outer bands
        path.reset();
        float x0 = area.left, y0 = y(gains[0]);
        path.moveTo(x0, y0);
        path.lineTo(bx(0), y(gains[0]));
        for (int i = 0; i < Hb.BANDS - 1; i++) {
            float p0x = bx(Math.max(0, i - 1)), p0y = y(gains[Math.max(0, i - 1)]);
            float p1x = bx(i), p1y = y(gains[i]);
            float p2x = bx(i + 1), p2y = y(gains[i + 1]);
            float p3x = bx(Math.min(Hb.BANDS - 1, i + 2)), p3y = y(gains[Math.min(Hb.BANDS - 1, i + 2)]);
            path.cubicTo(p1x + (p2x - p0x) / 6f, p1y + (p2y - p0y) / 6f,
                    p2x - (p3x - p1x) / 6f, p2y - (p3y - p1y) / 6f, p2x, p2y);
        }
        path.lineTo(area.right, y(gains[Hb.BANDS - 1]));

        fillPath.set(path);
        fillPath.lineTo(area.right, y(0));
        fillPath.lineTo(area.left, y(0));
        fillPath.close();
        fill.setShader(new LinearGradient(0, area.top, 0, area.bottom,
                withAlpha(accent, 0x55), withAlpha(accent, 0x08), Shader.TileMode.CLAMP));
        c.drawPath(fillPath, fill);
        c.drawPath(path, curve);

        // knobs
        for (int i = 0; i < Hb.BANDS; i++) {
            float x = bx(i), yy = y(gains[i]);
            float r = (i == active ? 11 : 8) * dp;
            c.drawCircle(x, yy, r, knob);
            c.drawCircle(x, yy, r, ring);
        }
        // value bubble for the band being dragged
        if (active >= 0) {
            float x = bx(active), yy = y(gains[active]);
            String t = Hb.db(gains[active]);
            float tw = value.measureText(t) + 16 * dp;
            float by = yy - 22 * dp;
            if (by - 14 * dp < 0) by = yy + 34 * dp;
            float bxl = Math.max(4 * dp, Math.min(w - tw - 4 * dp, x - tw / 2));
            tmp.set(bxl, by - 16 * dp, bxl + tw, by + 6 * dp);
            c.drawRoundRect(tmp, 11 * dp, 11 * dp, bubble);
            c.drawText(t, tmp.centerX(), by - 1 * dp, value);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!enabled) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int best = -1;
                float bestD = Float.MAX_VALUE;
                for (int i = 0; i < Hb.BANDS; i++) {
                    float d = Math.abs(e.getX() - bx(i));
                    if (d < bestD) { bestD = d; best = i; }
                }
                if (bestD > area.width() / Hb.BANDS) return false;
                active = best;
                getParent().requestDisallowInterceptTouchEvent(true);
                move(e.getY());
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (active >= 0) move(e.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                active = -1;
                getParent().requestDisallowInterceptTouchEvent(false);
                invalidate();
                return true;
        }
        return false;
    }

    private void move(float yy) {
        float db = (area.centerY() - yy) / (area.height() / 2f) * Hb.MAX_DB;
        int v = Math.max(-Hb.MAX_DB, Math.min(Hb.MAX_DB, Math.round(db)));
        if (v != gains[active]) {
            gains[active] = v;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            if (listener != null) listener.onBandChanged(active, v);
        }
        invalidate();
    }
}

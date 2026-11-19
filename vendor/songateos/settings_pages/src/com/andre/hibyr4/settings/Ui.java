package com.andre.hibyr4.settings;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Shared look for the HiBy pages: theme colours, sizes and small line icons drawn in code (no resources). */
final class Ui {
    final float dp;
    final int accent, text, text2;

    Ui(Context c) {
        dp = c.getResources().getDisplayMetrics().density;
        TypedArray a = c.obtainStyledAttributes(new int[]{android.R.attr.colorAccent,
                android.R.attr.textColorPrimary, android.R.attr.textColorSecondary});
        accent = a.getColor(0, 0xff3d7cff);
        text = a.getColor(1, 0xff202020);
        text2 = a.getColor(2, 0xff707070);
        a.recycle();
    }

    static int alpha(int color, int alpha) { return (color & 0x00ffffff) | (alpha << 24); }

    // row geometry shared by the custom rows (matches Settings' icon column + text start)
    float iconX() { return 20 * dp; }
    float textX() { return 72 * dp; }
    float endPad() { return 24 * dp; }

    static final int HEADPHONE = 0, GAIN = 1, FILTER = 2, BALANCE = 3, EQ = 4, DSD = 5, REMOTE = 6, STEPS = 7,
            TURBO = 8, JACK = 9, LED = 10, UPDATE = 11, DOWNLOAD = 12, RECOVERY = 13, BETA = 14, AUTO = 15,
            NOTES = 16, CHECK = 17, POWER = 18;

    /** 24x24 line icon in the given colour. */
    static final class Icon extends Drawable {
        private final int type;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), f = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int size;

        Icon(Context c, int type, int color) {
            this.type = type;
            size = (int) (24 * c.getResources().getDisplayMetrics().density);
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.8f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            f.setColor(color);
        }

        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }

        @Override
        public void draw(Canvas c) {
            int save = c.save();
            c.translate(getBounds().left, getBounds().top);
            c.scale(getBounds().width() / 24f, getBounds().height() / 24f);
            path.reset();
            switch (type) {
                case HEADPHONE:
                    path.addArc(new RectF(4, 4, 20, 20), 180, 180);
                    c.drawPath(path, p);
                    c.drawRoundRect(new RectF(3, 12, 7.5f, 20.5f), 2, 2, p);
                    c.drawRoundRect(new RectF(16.5f, 12, 21, 20.5f), 2, 2, p);
                    break;
                case GAIN:
                    path.moveTo(3, 9.5f); path.lineTo(7, 9.5f); path.lineTo(11.5f, 5.5f); path.lineTo(11.5f, 18.5f);
                    path.lineTo(7, 14.5f); path.lineTo(3, 14.5f); path.close();
                    c.drawPath(path, p);
                    c.drawArc(new RectF(11, 8.5f, 18, 15.5f), -50, 100, false, p);
                    c.drawArc(new RectF(10, 4.5f, 22, 19.5f), -50, 100, false, p);
                    break;
                case FILTER:
                    path.moveTo(2, 12); path.quadTo(5, 2, 8, 12); path.quadTo(11, 22, 14, 12);
                    path.quadTo(16, 6, 18, 12); path.quadTo(19.5f, 15, 22, 12);
                    c.drawPath(path, p);
                    break;
                case BALANCE:
                    c.drawLine(4, 12, 20, 12, p);
                    c.drawLine(12, 7, 12, 17, p);
                    path.moveTo(7, 9); path.lineTo(4, 12); path.lineTo(7, 15);
                    path.moveTo(17, 9); path.lineTo(20, 12); path.lineTo(17, 15);
                    c.drawPath(path, p);
                    break;
                case EQ:
                    c.drawLine(6, 4, 6, 20, p); c.drawLine(12, 4, 12, 20, p); c.drawLine(18, 4, 18, 20, p);
                    c.drawCircle(6, 14, 2.4f, f); c.drawCircle(12, 8, 2.4f, f); c.drawCircle(18, 12, 2.4f, f);
                    break;
                case DSD:
                    c.drawCircle(12, 12, 9, p); c.drawCircle(12, 12, 2.5f, p);
                    c.drawArc(new RectF(6, 6, 18, 18), 200, 60, false, p);
                    break;
                case REMOTE:
                    c.drawLine(12, 2, 12, 7, p);
                    c.drawRoundRect(new RectF(8.5f, 7, 15.5f, 18), 3, 3, p);
                    c.drawCircle(12, 11, 1.4f, f); c.drawCircle(12, 14.5f, 1.4f, f);
                    c.drawLine(12, 18, 12, 22, p);
                    break;
                case STEPS:
                    for (int i = 0; i < 4; i++) {
                        float x = 4 + i * 4.6f, h = 4 + i * 4;
                        c.drawRoundRect(new RectF(x, 20 - h, x + 2.8f, 20), 1, 1, f);
                    }
                    break;
                case TURBO:
                    path.moveTo(13.5f, 2); path.lineTo(5, 13.5f); path.lineTo(11, 13.5f); path.lineTo(10, 22);
                    path.lineTo(19, 10); path.lineTo(13, 10); path.close();
                    c.drawPath(path, p);
                    break;
                case JACK:
                    c.drawRoundRect(new RectF(9, 13, 15, 22), 1.5f, 1.5f, p);
                    c.drawLine(12, 13, 12, 3, p);
                    c.drawLine(10.5f, 6, 13.5f, 6, p); c.drawLine(10.5f, 9, 13.5f, 9, p);
                    break;
                case LED:
                    c.drawCircle(12, 12, 4, f);
                    for (int i = 0; i < 8; i++) {
                        double a = Math.PI * i / 4;
                        c.drawLine(12 + (float) Math.cos(a) * 7, 12 + (float) Math.sin(a) * 7,
                                12 + (float) Math.cos(a) * 9.5f, 12 + (float) Math.sin(a) * 9.5f, p);
                    }
                    break;
                case UPDATE:   // two circular arrows (system update), solid heads on the arc tangents
                    arrowArc(c, 205, 110);
                    arrowArc(c, 25, 110);
                    break;
                case DOWNLOAD:  // arrow into a tray
                    c.drawLine(12, 3.5f, 12, 15, p);
                    path.moveTo(7.5f, 10.5f); path.lineTo(12, 15); path.lineTo(16.5f, 10.5f);
                    path.moveTo(4, 15.5f); path.lineTo(4, 20); path.lineTo(20, 20); path.lineTo(20, 15.5f);
                    c.drawPath(path, p);
                    break;
                case RECOVERY:  // shield with a tick
                    path.moveTo(12, 2.5f); path.lineTo(19.5f, 5.5f); path.lineTo(19.5f, 11);
                    path.quadTo(19.5f, 18, 12, 21.5f); path.quadTo(4.5f, 18, 4.5f, 11); path.lineTo(4.5f, 5.5f); path.close();
                    path.moveTo(8.5f, 12); path.lineTo(11, 14.5f); path.lineTo(15.5f, 9.5f);
                    c.drawPath(path, p);
                    break;
                case BETA:      // flask
                    path.moveTo(9.5f, 3); path.lineTo(14.5f, 3);
                    path.moveTo(10.5f, 3); path.lineTo(10.5f, 9.5f); path.lineTo(5, 19); path.quadTo(4.5f, 21, 6.5f, 21);
                    path.lineTo(17.5f, 21); path.quadTo(19.5f, 21, 19, 19); path.lineTo(13.5f, 9.5f); path.lineTo(13.5f, 3);
                    path.moveTo(7.5f, 15); path.lineTo(16.5f, 15);
                    c.drawPath(path, p);
                    break;
                case AUTO:      // clock
                    c.drawCircle(12, 12, 9, p);
                    path.moveTo(12, 7); path.lineTo(12, 12); path.lineTo(15.5f, 14);
                    c.drawPath(path, p);
                    break;
                case NOTES:     // page with lines
                    c.drawRoundRect(new RectF(5, 3, 19, 21), 2.5f, 2.5f, p);
                    c.drawLine(8.5f, 8, 15.5f, 8, p); c.drawLine(8.5f, 12, 15.5f, 12, p); c.drawLine(8.5f, 16, 13, 16, p);
                    break;
                case POWER:     // power symbol
                    c.drawArc(new RectF(4, 4.5f, 20, 20.5f), 300, 300, false, p);
                    c.drawLine(12, 3, 12, 11.5f, p);
                    break;
                case CHECK:     // circle with a tick
                    c.drawCircle(12, 12, 9, p);
                    path.moveTo(8, 12.2f); path.lineTo(11, 15.2f); path.lineTo(16.2f, 9.2f);
                    c.drawPath(path, p);
                    break;
            }
            c.restoreToCount(save);
        }

        /** Clockwise arc (radius 7.5 around the centre) ending in a solid arrow head that sits on the arc's end,
         *  pointing along its tangent (angles in degrees, Android's clockwise convention). */
        private void arrowArc(Canvas c, float start, float sweep) {
            float r = 7.5f;
            c.drawArc(new RectF(12 - r, 12 - r, 12 + r, 12 + r), start, sweep, false, p);
            double a = Math.toRadians(start + sweep);
            float ex = 12 + r * (float) Math.cos(a), ey = 12 + r * (float) Math.sin(a);
            float tx = (float) -Math.sin(a), ty = (float) Math.cos(a);   // tangent (direction of travel)
            float nx = (float) Math.cos(a), ny = (float) Math.sin(a);    // normal (outwards)
            Path h = new Path();
            h.moveTo(ex + 3.2f * tx, ey + 3.2f * ty);
            h.lineTo(ex + 2.9f * nx, ey + 2.9f * ny);
            h.lineTo(ex - 2.9f * nx, ey - 2.9f * ny);
            h.close();
            c.drawPath(h, f);
        }

        @Override public void setAlpha(int a) { p.setAlpha(a); f.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); f.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Tiny EQ curve used as the icon of the Equalizer row. */
    static final class MiniCurve extends Drawable {
        private final int[] g;
        private final boolean on;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), base = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int size;

        MiniCurve(Context c, int[] gains, boolean on, int color, int dim) {
            g = gains;
            this.on = on;
            size = (int) (24 * c.getResources().getDisplayMetrics().density);
            p.setColor(on ? color : dim);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            p.setStrokeCap(Paint.Cap.ROUND);
            base.setColor(alpha(dim, 0x60));
            base.setStrokeWidth(1f);
        }

        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }

        @Override
        public void draw(Canvas c) {
            int save = c.save();
            c.translate(getBounds().left, getBounds().top);
            c.scale(getBounds().width() / 24f, getBounds().height() / 24f);
            c.drawLine(2, 12, 22, 12, base);
            Path path = new Path();
            for (int i = 0; i < g.length; i++) {
                float x = 2 + 20f * i / (g.length - 1), y = 12 - (on ? g[i] : 0) * 8f / Hb.MAX_DB;
                if (i == 0) path.moveTo(x, y);
                else {
                    float px = 2 + 20f * (i - 1) / (g.length - 1), py = 12 - (on ? g[i - 1] : 0) * 8f / Hb.MAX_DB;
                    path.cubicTo((px + x) / 2, py, (px + x) / 2, y, x, y);
                }
            }
            c.drawPath(path, p);
            c.restoreToCount(save);
        }

        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}

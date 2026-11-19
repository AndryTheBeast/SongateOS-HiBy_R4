package com.andre.hibyr4.settings;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

/** L/R balance row: icon, title, value; a centred track with L and R ends, centre detent and a thumb (-10..10). */
public class BalanceView extends View {

    public interface Listener { void onBalance(int v); }

    private final Ui ui;
    private final Drawable icon;
    private final Listener listener;
    private int value;
    private boolean dragging;
    private final Paint tTitle = new Paint(Paint.ANTI_ALIAS_FLAG), tVal = new Paint(Paint.ANTI_ALIAS_FLAG),
            tEnd = new Paint(Paint.ANTI_ALIAS_FLAG), track = new Paint(Paint.ANTI_ALIAS_FLAG),
            active = new Paint(Paint.ANTI_ALIAS_FLAG), thumb = new Paint(Paint.ANTI_ALIAS_FLAG),
            tick = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float left, right, ty;

    public BalanceView(Context c, int value, Listener l) {
        super(c);
        ui = new Ui(c);
        icon = new Ui.Icon(c, Ui.BALANCE, ui.text2);
        this.value = value;
        listener = l;
        float dp = ui.dp;
        tTitle.setColor(ui.text);
        tTitle.setTextSize(17 * dp);
        tVal.setColor(ui.accent);
        tVal.setTextSize(15 * dp);
        tVal.setFakeBoldText(true);
        tVal.setTextAlign(Paint.Align.RIGHT);
        tEnd.setColor(ui.text2);
        tEnd.setTextSize(14 * dp);
        tEnd.setFakeBoldText(true);
        tEnd.setTextAlign(Paint.Align.CENTER);
        track.setColor(Ui.alpha(ui.text2, 0x40));
        track.setStrokeWidth(6 * dp);
        track.setStrokeCap(Paint.Cap.ROUND);
        active.setColor(ui.accent);
        active.setStrokeWidth(6 * dp);
        active.setStrokeCap(Paint.Cap.ROUND);
        tick.setColor(ui.text2);
        tick.setStrokeWidth(2 * dp);
        tick.setStrokeCap(Paint.Cap.ROUND);
        thumb.setColor(ui.accent);
        ring.setColor(0xffffffff);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2.5f * dp);
        setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (104 * dp)));
    }

    static String label(int v) { return v == 0 ? "Centre" : v < 0 ? "Left " + (-v) : "Right " + v; }

    private float xOf(int v) { return left + (right - left) * (v + 10) / 20f; }

    @Override
    protected void onDraw(Canvas c) {
        float dp = ui.dp, w = getWidth();
        int s = (int) (24 * dp), ix = (int) ui.iconX(), iy = (int) (16 * dp);
        icon.setBounds(ix, iy, ix + s, iy + s);
        icon.draw(c);
        c.drawText("L/R balance", ui.textX(), 35 * dp, tTitle);
        c.drawText(label(value), w - ui.endPad(), 35 * dp, tVal);

        ty = 72 * dp;
        float lx = ui.textX() + 8 * dp, rx = w - ui.endPad() - 8 * dp;
        c.drawText("L", lx, ty + 5 * dp, tEnd);
        c.drawText("R", rx, ty + 5 * dp, tEnd);
        left = lx + 22 * dp;
        right = rx - 22 * dp;
        c.drawLine(left, ty, right, ty, track);
        float cx = xOf(0), x = xOf(value);
        c.drawLine(cx, ty - 10 * dp, cx, ty + 10 * dp, tick);
        if (value != 0) c.drawLine(cx, ty, x, ty, active);
        float r = (dragging ? 13 : 11) * dp;
        c.drawCircle(x, ty, r, thumb);
        c.drawCircle(x, ty, r, ring);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (Math.abs(e.getY() - ty) > 30 * ui.dp) return false;
                dragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                move(e.getX());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) move(e.getX());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                invalidate();
                return true;
        }
        return false;
    }

    private void move(float x) {
        int v = Math.round((x - left) / (right - left) * 20f) - 10;
        v = Math.max(-10, Math.min(10, v));
        if (v != value) {
            value = v;
            performHapticFeedback(v == 0 ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.CLOCK_TICK);
            if (listener != null) listener.onBalance(v);
        }
        invalidate();
    }
}

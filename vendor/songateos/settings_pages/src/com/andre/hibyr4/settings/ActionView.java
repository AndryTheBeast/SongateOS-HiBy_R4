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

/**
 * A row with an icon, a title, an optional note and up to two pill buttons, in the same geometry as SegmentedView
 * (icon column, text column, pill bar). The first button is filled (accent), the second outlined.
 */
public class ActionView extends View {

    public interface Listener { void onClick(int index); }

    private final Ui ui;
    private final Drawable icon;
    private final String title, note;
    private final String[] labels;
    private final Listener listener;
    private int pressed = -1;
    private final Paint tTitle = new Paint(Paint.ANTI_ALIAS_FLAG), tNote = new Paint(Paint.ANTI_ALIAS_FLAG),
            tBtn = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            outline = new Paint(Paint.ANTI_ALIAS_FLAG), press = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF[] btn;

    public ActionView(Context c, int iconType, String title, String note, String[] labels, Listener l) {
        super(c);
        ui = new Ui(c);
        icon = iconType >= 0 ? new Ui.Icon(c, iconType, ui.text2) : null;
        this.title = title;
        this.note = note;
        this.labels = labels;
        listener = l;
        btn = new RectF[labels.length];
        for (int i = 0; i < btn.length; i++) btn[i] = new RectF();
        float dp = ui.dp;
        tTitle.setColor(ui.text);
        tTitle.setTextSize(17 * dp);
        tNote.setColor(ui.text2);
        tNote.setTextSize(13.5f * dp);
        tBtn.setTextSize(15 * dp);
        tBtn.setTextAlign(Paint.Align.CENTER);
        fill.setColor(ui.accent);
        outline.setColor(Ui.alpha(ui.text2, 0x60));
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(dp);
        press.setColor(Ui.alpha(ui.accent, 0x26));
        int h = (int) ((title == null ? 76 : note != null ? 128 : 108) * dp);
        setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
    }

    @Override
    protected void onDraw(Canvas c) {
        float dp = ui.dp, w = getWidth();
        float y = 18 * dp, barTop = 16 * dp;
        if (title != null) {
            if (icon != null) {
                int s = (int) (24 * dp), ix = (int) ui.iconX(), iy = (int) (y - 2 * dp);
                icon.setBounds(ix, iy, ix + s, iy + s);
                icon.draw(c);
            }
            c.drawText(title, ui.textX(), y + 17 * dp, tTitle);
            barTop = y + 32 * dp;
            if (note != null) {
                c.drawText(fit(note, tNote, w - ui.endPad() - ui.textX()), ui.textX(), y + 38 * dp, tNote);
                barTop = y + 52 * dp;
            }
        }
        float left = ui.textX(), right = w - ui.endPad(), gap = 10 * dp;
        float bw = (right - left - gap * (labels.length - 1)) / labels.length;
        for (int i = 0; i < labels.length; i++) {
            RectF b = btn[i];
            b.set(left + i * (bw + gap), barTop, left + i * (bw + gap) + bw, barTop + 44 * dp);
            float rad = b.height() / 2;
            boolean primary = i == 0;
            if (primary) {
                fill.setAlpha(isEnabled() ? (i == pressed ? 0xc0 : 0xff) : 0x60);
                c.drawRoundRect(b, rad, rad, fill);
            } else {
                if (i == pressed) c.drawRoundRect(b, rad, rad, press);
                c.drawRoundRect(b, rad, rad, outline);
            }
            tBtn.setColor(primary ? 0xffffffff : ui.accent);
            tBtn.setFakeBoldText(primary);
            c.drawText(fit(labels[i], tBtn, bw - 16 * dp), b.centerX(), b.centerY() + 5 * dp, tBtn);
        }
    }

    private static String fit(String s, Paint p, float max) {
        if (p.measureText(s) <= max) return s;
        while (s.length() > 1 && p.measureText(s + "…") > max) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private int at(float x, float y) {
        for (int i = 0; i < btn.length; i++) {
            RectF b = btn[i];
            if (x >= b.left && x <= b.right && y >= b.top - 6 * ui.dp && y <= b.bottom + 6 * ui.dp) return i;
        }
        return -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = at(e.getX(), e.getY());
                invalidate();
                return pressed >= 0;
            case MotionEvent.ACTION_UP: {
                int i = at(e.getX(), e.getY());
                boolean hit = i >= 0 && i == pressed;
                pressed = -1;
                invalidate();
                if (hit) {
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                    if (listener != null) listener.onClick(i);
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                pressed = -1;
                invalidate();
                return true;
        }
        return true;
    }
}

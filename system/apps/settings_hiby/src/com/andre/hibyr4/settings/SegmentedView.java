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

/** Settings row with icon, title, optional note and a segmented button bar (one choice, tap to switch). */
public class SegmentedView extends View {

    public interface Listener { boolean onSelect(int index); }   // return false to refuse (value stays)

    private final Ui ui;
    private final Drawable icon;
    private final String title, note;
    private final String[] labels;
    private final Listener listener;
    private int selected;
    private final Paint tTitle = new Paint(Paint.ANTI_ALIAS_FLAG), tNote = new Paint(Paint.ANTI_ALIAS_FLAG),
            tSeg = new Paint(Paint.ANTI_ALIAS_FLAG), track = new Paint(Paint.ANTI_ALIAS_FLAG),
            border = new Paint(Paint.ANTI_ALIAS_FLAG), pill = new Paint(Paint.ANTI_ALIAS_FLAG),
            divider = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF(), r = new RectF();
    private int pressed = -1;

    public SegmentedView(Context c, int iconType, String title, String note, String[] labels, int selected, Listener l) {
        super(c);
        ui = new Ui(c);
        icon = iconType >= 0 ? new Ui.Icon(c, iconType, ui.text2) : null;
        this.title = title;
        this.note = note;
        this.labels = labels;
        this.selected = selected;
        listener = l;
        float dp = ui.dp;
        tTitle.setColor(ui.text);
        tTitle.setTextSize(17 * dp);
        tNote.setColor(ui.text2);
        tNote.setTextSize(13.5f * dp);
        tSeg.setTextSize(14 * dp);
        tSeg.setTextAlign(Paint.Align.CENTER);
        track.setColor(Ui.alpha(ui.text2, 0x14));
        border.setColor(Ui.alpha(ui.text2, 0x50));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(dp);
        pill.setColor(ui.accent);
        divider.setColor(Ui.alpha(ui.text2, 0x40));
        divider.setStrokeWidth(dp);
        int h = (int) ((note != null ? 128 : 108) * dp);
        setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
    }

    public void setSelected(int i) { selected = i; invalidate(); }

    @Override
    protected void onDraw(Canvas c) {
        float dp = ui.dp, w = getWidth();
        float y = 18 * dp;
        if (icon != null) {
            int s = (int) (24 * dp), ix = (int) ui.iconX(), iy = (int) (y - 2 * dp);
            icon.setBounds(ix, iy, ix + s, iy + s);
            icon.draw(c);
        }
        c.drawText(title, ui.textX(), y + 17 * dp, tTitle);
        float barTop = y + 32 * dp;
        if (note != null) {
            c.drawText(note, ui.textX(), y + 38 * dp, tNote);
            barTop = y + 52 * dp;
        }
        bar.set(ui.textX(), barTop, w - ui.endPad(), barTop + 44 * dp);
        float rad = bar.height() / 2;
        c.drawRoundRect(bar, rad, rad, track);
        c.drawRoundRect(bar, rad, rad, border);
        float segW = bar.width() / labels.length;
        for (int i = 1; i < labels.length; i++) {
            if (i == selected || i - 1 == selected) continue;
            float x = bar.left + i * segW;
            c.drawLine(x, bar.top + 10 * dp, x, bar.bottom - 10 * dp, divider);
        }
        r.set(bar.left + selected * segW + 3 * dp, bar.top + 3 * dp, bar.left + (selected + 1) * segW - 3 * dp, bar.bottom - 3 * dp);
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, pill);
        for (int i = 0; i < labels.length; i++) {
            boolean sel = i == selected;
            tSeg.setColor(sel ? 0xffffffff : (i == pressed ? ui.accent : ui.text));
            tSeg.setFakeBoldText(sel);
            float cx = bar.left + (i + 0.5f) * segW;
            c.drawText(fit(labels[i], segW - 10 * dp), cx, bar.centerY() + 5 * dp, tSeg);
        }
    }

    private String fit(String s, float max) {
        if (tSeg.measureText(s) <= max) return s;
        while (s.length() > 1 && tSeg.measureText(s + "…") > max) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private int segAt(float x, float y) {
        if (y < bar.top - 8 * ui.dp || y > bar.bottom + 8 * ui.dp || x < bar.left || x > bar.right) return -1;
        return Math.min(labels.length - 1, (int) ((x - bar.left) / (bar.width() / labels.length)));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = segAt(e.getX(), e.getY());
                invalidate();
                return pressed >= 0;
            case MotionEvent.ACTION_UP: {
                int i = segAt(e.getX(), e.getY());
                if (i >= 0 && i == pressed && i != selected && (listener == null || listener.onSelect(i))) {
                    selected = i;
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                }
                pressed = -1;
                invalidate();
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

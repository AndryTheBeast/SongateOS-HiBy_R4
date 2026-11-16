package com.andre.hibyr4.settings;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;

/** Header card of the HiBy pages (HiBy audio: output, rate, sound summary; SongateOS update: version, state):
 *  round icon, title, one line, chips, and an optional progress bar. */
public class HeaderView extends View {

    private final Ui ui;
    private String title, line1, line2;
    private String[] chips;
    private boolean[] dim;   // chip i greyed out (setting not in use right now)
    private float progress = -1;   // 0..1 shows a bar under the line, < 0 hides it
    private final Ui.Icon icon;
    private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG), circle = new Paint(Paint.ANTI_ALIAS_FLAG),
            tTitle = new Paint(Paint.ANTI_ALIAS_FLAG), tLine = new Paint(Paint.ANTI_ALIAS_FLAG),
            chip = new Paint(Paint.ANTI_ALIAS_FLAG), tChip = new Paint(Paint.ANTI_ALIAS_FLAG),
            barBg = new Paint(Paint.ANTI_ALIAS_FLAG), barFg = new Paint(Paint.ANTI_ALIAS_FLAG),
            chipDim = new Paint(Paint.ANTI_ALIAS_FLAG), tChipDim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    public HeaderView(Context c, String title, String line1, String[] chips) {
        this(c, Ui.HEADPHONE, title, line1, chips);
    }

    public HeaderView(Context c, int iconType, String title, String line1, String[] chips) {
        super(c);
        ui = new Ui(c);
        this.title = title;
        this.line1 = line1;
        this.chips = chips;
        float dp = ui.dp;
        icon = new Ui.Icon(c, iconType, 0xffffffff);
        barBg.setColor(Ui.alpha(ui.accent, 0x30));
        barFg.setColor(ui.accent);
        circle.setColor(ui.accent);
        tTitle.setColor(ui.text);
        tTitle.setTextSize(21 * dp);
        tTitle.setFakeBoldText(true);
        tLine.setColor(ui.text2);
        tLine.setTextSize(14 * dp);
        chip.setColor(Ui.alpha(ui.accent, 0x26));
        tChip.setColor(ui.text);
        tChip.setTextSize(13 * dp);
        chipDim.setColor(Ui.alpha(ui.text2, 0x12));
        tChipDim.setColor(Ui.alpha(ui.text2, 0x80));
        tChipDim.setTextSize(13 * dp);
        setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (172 * dp)));
    }

    public void setChips(String[] chips) { this.chips = chips; invalidate(); }

    public void setChips(String[] chips, boolean[] dim) { this.chips = chips; this.dim = dim; invalidate(); }

    public void setTexts(String title, String line1) { this.title = title; this.line1 = line1; invalidate(); }

    /** a smaller second line under line1 (null = none) */
    public void setLine2(String line2) { this.line2 = line2; invalidate(); }

    public void setProgress(float p) { progress = p; invalidate(); }

    private String fit(String s, Paint p, float max) {
        if (p.measureText(s) <= max) return s;
        while (s.length() > 1 && p.measureText(s + "…") > max) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    @Override
    protected void onDraw(Canvas c) {
        float dp = ui.dp, w = getWidth();
        r.set(16 * dp, 8 * dp, w - 16 * dp, getHeight() - 12 * dp);
        card.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                Ui.alpha(ui.accent, 0x33), Ui.alpha(ui.accent, 0x10), Shader.TileMode.CLAMP));
        c.drawRoundRect(r, 28 * dp, 28 * dp, card);

        float cx = r.left + 46 * dp, cy = r.top + 50 * dp;
        c.drawCircle(cx, cy, 28 * dp, circle);
        int s = (int) (30 * dp);
        icon.setBounds((int) (cx - s / 2f), (int) (cy - s / 2f), (int) (cx + s / 2f), (int) (cy + s / 2f));
        icon.draw(c);

        float tx = r.left + 90 * dp;
        float tmax = r.right - 20 * dp - tx;
        c.drawText(fit(title, tTitle, tmax), tx, r.top + 44 * dp, tTitle);
        c.drawText(fit(line1, tLine, tmax), tx, r.top + 67 * dp, tLine);
        if (progress >= 0) {
            RectF b = new RectF(tx, r.top + 78 * dp, r.right - 24 * dp, r.top + 84 * dp);
            c.drawRoundRect(b, 3 * dp, 3 * dp, barBg);
            float pw = Math.max(b.height(), b.width() * Math.min(1f, progress));
            c.drawRoundRect(new RectF(b.left, b.top, b.left + pw, b.bottom), 3 * dp, 3 * dp, barFg);
        }

        if (line2 != null && progress < 0) c.drawText(fit(line2, tLine, tmax), tx, r.top + 88 * dp, tLine);
        // chips: gain, filter, EQ ...
        float x = r.left + 20 * dp, y = r.top + (line2 != null ? 104 : 100) * dp;
        for (int i = 0; i < chips.length; i++) {
            String t = chips[i];
            boolean off = dim != null && i < dim.length && dim[i];
            float tw = tChip.measureText(t) + 24 * dp;
            if (x + tw > r.right - 16 * dp) break;
            RectF cr = new RectF(x, y, x + tw, y + 32 * dp);
            c.drawRoundRect(cr, 16 * dp, 16 * dp, off ? chipDim : chip);
            c.drawText(t, x + 12 * dp, y + 21 * dp, off ? tChipDim : tChip);
            x += tw + 8 * dp;
        }
    }
}

package com.andre.hibyr4.settings;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;

/** Header card of Settings > HiBy audio: current output, sample rate and a summary of the sound settings. */
public class HeaderView extends View {

    private final Ui ui;
    private final String title, line1;
    private String[] chips;
    private final Ui.Icon icon;
    private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG), circle = new Paint(Paint.ANTI_ALIAS_FLAG),
            tTitle = new Paint(Paint.ANTI_ALIAS_FLAG), tLine = new Paint(Paint.ANTI_ALIAS_FLAG),
            chip = new Paint(Paint.ANTI_ALIAS_FLAG), tChip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    public HeaderView(Context c, String title, String line1, String[] chips) {
        super(c);
        ui = new Ui(c);
        this.title = title;
        this.line1 = line1;
        this.chips = chips;
        float dp = ui.dp;
        icon = new Ui.Icon(c, Ui.HEADPHONE, 0xffffffff);
        circle.setColor(ui.accent);
        tTitle.setColor(ui.text);
        tTitle.setTextSize(21 * dp);
        tTitle.setFakeBoldText(true);
        tLine.setColor(ui.text2);
        tLine.setTextSize(14 * dp);
        chip.setColor(Ui.alpha(ui.accent, 0x26));
        tChip.setColor(ui.text);
        tChip.setTextSize(13 * dp);
        setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (172 * dp)));
    }

    public void setChips(String[] chips) { this.chips = chips; invalidate(); }

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
        c.drawText(title, tx, r.top + 44 * dp, tTitle);
        c.drawText(line1, tx, r.top + 67 * dp, tLine);

        // chips: gain, filter, EQ ...
        float x = r.left + 20 * dp, y = r.top + 100 * dp;
        for (String t : chips) {
            float tw = tChip.measureText(t) + 24 * dp;
            if (x + tw > r.right - 16 * dp) break;
            RectF cr = new RectF(x, y, x + tw, y + 32 * dp);
            c.drawRoundRect(cr, 16 * dp, 16 * dp, chip);
            c.drawText(t, x + 12 * dp, y + 21 * dp, tChip);
            x += tw + 8 * dp;
        }
    }
}

package com.andre.hibyr4sound;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Choreographer;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SongateOS app switcher, drawn by the service as a system overlay above the current app (user, 2026-11-18: in
 * Songate it brought Songate to the front). Driven by the gesture strip so it moves like Android's own recents:
 *   drag(p)      the current app's live screenshot follows the finger, shrinking with rounded corners, the background
 *                dims (p = 0..1 of the way to the switcher);
 *   releaseHome  the card shrinks away downwards and fades while home comes up behind;
 *   settle       (hold) the card glides into its place in the row and the other apps' cards slide in;
 *   cancel       the card grows back to full screen and the overlay goes.
 * In the row (layout A, Songate's style): tap a card = it grows to full screen and the app takes over; swipe a card
 * up = closes it; "Close all"; tap outside or Back = back to the app you came from. Material motion curves.
 */
final class Switcher {
    static final String TAG = "SongateSwitcher";
    private static final int TYPE = 2024;                                   // WindowManager.LayoutParams panel above apps
    private static final PathInterpolator EMPHASIZED = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final PathInterpolator STANDARD = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final float CARD_W = 0.62f;                              // of the screen width
    private static final ExecutorService EX = Executors.newSingleThreadExecutor();

    private static Context sApp;
    private static Handler sH;
    private static Root sRoot;
    private static FrameLayout sLive;                                       // the current app's card while dragging
    private static ImageView sLiveImg;
    private static ImageView sBlur;                                         // heavily blurred screenshot behind everything
    private static ImageView sBlurSoft;                                     // lightly blurred one under it (the blur builds up)
    private static float sBlurReady = 1f, sScrimA;
    private static boolean sReleasing;                                      // going home: let the light blur fade too                         // fades the blur in when it arrives mid-swipe
    // the card follows the finger once per frame (not per touch event), easing towards it: smooth on an uneven touch rate
    private static float sTarget, sShown, sRadius;
    // 1 = the card rides under the finger (live swipe), 0 = it sits where its slot in the row is (switcher)
    private static float sFollowK = 1f;
    // Songate is brought up behind the card as soon as the swipe starts (user, 2026-11-19: "the app should disappear
    // with Songate behind it already"); the blur only comes in when the switcher opens (sHold 0 -> 1)
    private static boolean sHomeEarly;
    private static long sSettleEnd;                 // when the live card arrives in its slot (hand-over after that)
    private static final android.view.animation.Interpolator SETTLE = new PathInterpolator(0.3f, 0f, 0.1f, 1f);
    private static float sHold = 1f;
    private static boolean sFollowing;
    private static final Choreographer.FrameCallback FOLLOW = new Choreographer.FrameCallback() {
        @Override public void doFrame(long t) {
            if (!sFollowing) return;
            float d = sTarget - sShown;
            if (Math.abs(d) < 0.002f) { sShown = sTarget; apply(sShown); sFollowing = false; return; }
            sShown += d * 0.55f;
            apply(sShown);
            Choreographer.getInstance().postFrameCallback(this);
        }
    };
    private static final ViewOutlineProvider LIVE_OUTLINE = new ViewOutlineProvider() {
        @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), sRadius); }
    };
    private static LinearLayout sRow;
    private static HorizontalScrollView sScroll;
    private static View sBottom;
    private static int sCurrentTask = -1;
    private static float sProgress;
    private static boolean sOpen;

    private Switcher() { }

    static void init(Context c) { sApp = c.getApplicationContext(); sH = new Handler(Looper.getMainLooper()); }

    private static float dp(float v) { return v * sApp.getResources().getDisplayMetrics().density; }
    private static int sw() { return sApp.getResources().getDisplayMetrics().widthPixels; }
    private static int sh() { return sApp.getResources().getDisplayMetrics().heightPixels; }
    private static int accent() { return 0xff000000 | SystemTheme.currentColour(sApp); }

    // ------------------------------------------------------------------ the window
    private static final class Root extends FrameLayout {
        Root(Context c) { super(c); setFocusableInTouchMode(true); }
        @Override public boolean dispatchKeyEvent(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) dismiss(true);
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
    }

    private static void ensureWindow() {
        if (sRoot != null) return;
        sRoot = new Root(sApp);
        sRoot.setBackgroundColor(Color.TRANSPARENT);
        sRoot.setOnClickListener(v -> dismiss(true));
        sBlurSoft = new ImageView(sApp);
        sBlurSoft.setScaleType(ImageView.ScaleType.FIT_XY);
        sBlurSoft.setAlpha(0f);
        sRoot.addView(sBlurSoft, new FrameLayout.LayoutParams(-1, -1));
        sBlur = new ImageView(sApp);
        sBlur.setScaleType(ImageView.ScaleType.FIT_XY);
        sBlur.setAlpha(0f);
        sRoot.addView(sBlur, new FrameLayout.LayoutParams(-1, -1));
        sBlurReady = 1f; sScrimA = 0f; sReleasing = false; sHold = 1f; sHomeEarly = false;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, TYPE,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("SongateOS app switcher");
        lp.setFitInsetsTypes(0);
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        try {
            sApp.getSystemService(WindowManager.class).addView(sRoot, lp);
        } catch (RuntimeException e) { Log.w(TAG, "window", e); sRoot = null; }
    }

    private static void removeWindow() {
        if (sRoot == null) return;
        try { sApp.getSystemService(WindowManager.class).removeViewImmediate(sRoot); } catch (RuntimeException ignored) { }
        stopFollow();
        sRoot = null; sBlur = null; sBlurSoft = null; sLive = null; sLiveImg = null; sRow = null; sScroll = null; sBottom = null; sOpen = false;
    }

    /** A strongly blurred small copy of a screenshot (drawn stretched, so the filtering smooths it further). */
    private static Bitmap blurred(Bitmap src) { return blurred(src, 10, 3, 3); }

    private static Bitmap blurred(Bitmap src, int div, int passes, int r) {
        if (src == null) return null;
        try {
            Bitmap soft = src.getConfig() == Bitmap.Config.HARDWARE ? src.copy(Bitmap.Config.ARGB_8888, false) : src;
            int w = Math.max(8, soft.getWidth() / div), h = Math.max(8, soft.getHeight() / div);
            Bitmap small = Bitmap.createScaledBitmap(soft, w, h, true);
            int[] px = new int[w * h], tmp = new int[w * h];
            small.getPixels(px, 0, w, 0, 0, w, h);
            for (int pass = 0; pass < passes; pass++) { boxBlur(px, tmp, w, h, r, true); boxBlur(tmp, px, w, h, r, false); }
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            out.setPixels(px, 0, w, 0, 0, w, h);
            return out;
        } catch (RuntimeException e) { Log.w(TAG, "blur", e); return null; }
    }

    private static void boxBlur(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w, n = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[idx(Math.max(0, Math.min(len - 1, i)), l, w, horizontal)];
                sa += c >>> 24; sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255;
            }
            for (int i = 0; i < len; i++) {
                out[idx(i, l, w, horizontal)] = ((sa / n) << 24) | ((sr / n) << 16) | ((sg / n) << 8) | (sb / n);
                int c0 = in[idx(Math.max(0, i - r), l, w, horizontal)], c1 = in[idx(Math.min(len - 1, i + r + 1), l, w, horizontal)];
                sa += (c1 >>> 24) - (c0 >>> 24); sr += ((c1 >> 16) & 255) - ((c0 >> 16) & 255);
                sg += ((c1 >> 8) & 255) - ((c0 >> 8) & 255); sb += (c1 & 255) - (c0 & 255);
            }
        }
    }

    private static int idx(int i, int l, int w, boolean horizontal) { return horizontal ? l * w + i : i * w + l; }

    /** Worker thread: make both blur layers from a screenshot and show them (fading in if the swipe is under way). */
    private static void makeBlur(Bitmap shot) {
        Bitmap soft = blurred(shot, 6, 1, 2), heavy = blurred(shot, 10, 3, 3);
        if (soft == null || heavy == null) return;
        sH.post(() -> {
            if (sBlur == null) return;
            sBlurSoft.setImageBitmap(soft);
            sBlur.setImageBitmap(heavy);
            if (sScrimA <= 0.02f) { sBlurReady = 1f; return; }
            ValueAnimator f = ValueAnimator.ofFloat(0f, 1f).setDuration(160);
            f.addUpdateListener(x -> { sBlurReady = (float) x.getAnimatedValue(); scrim(sScrimA); });
            f.start();
        });
    }

    private static float ease(float x) { x = Math.max(0, Math.min(1, x)); return x * x * (3 - 2 * x); }

    private static void stopFollow() {
        sFollowing = false;
        Choreographer.getInstance().removeFrameCallback(FOLLOW);
    }

    private static void scrim(float a) {               // the dimmed background, 0..1
        sScrimA = a;
        // a light dimming over Songate while swiping, the full one when the switcher opens
        if (sRoot != null) sRoot.setBackgroundColor(Color.argb(Math.round((50 + 100 * sHold) * ease(a)), 8, 8, 12));
        // the light blur covers the real app as soon as the swipe starts (no full-size "ghost" behind the card),
        // the full blur builds up with the swipe
        if (sBlurSoft != null) sBlurSoft.setAlpha(sHold * sBlurReady * (sReleasing ? ease(a / 0.5f) : 1f));
        if (sBlur != null) sBlur.setAlpha(sHold * sBlurReady * ease(a / 0.7f));
    }

    private static void round(View v, float radius) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) { o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius); }
        });
        v.setClipToOutline(true);
    }

    // ------------------------------------------------------------------ live drag from the gesture strip
    /** The swipe has started: show the current app's card at full size (no-op on the home screen). */
    static void begin() {
        if (sOpen) return;
        ActivityManager.RunningTaskInfo top = topTask();
        if (top == null || isHome(top)) { sCurrentTask = -1; return; }
        sCurrentTask = top.taskId;
        ensureWindow();
        if (sRoot == null) return;
        sLive = new FrameLayout(sApp);
        sLiveImg = new ImageView(sApp);
        sLiveImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        sLive.addView(sLiveImg, new FrameLayout.LayoutParams(-1, -1));
        sRoot.addView(sLive, new FrameLayout.LayoutParams(sw(), sh()));
        sRadius = 0;
        sLive.setOutlineProvider(LIVE_OUTLINE);
        sLive.setClipToOutline(true);
        sLive.setElevation(dp(8));
        sLive.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        sBlurReady = 0f;
        sHold = 0f; sHomeEarly = false;
        final int task = sCurrentTask;
        final FrameLayout live = sLive;
        EX.execute(() -> {
            Bitmap b = Recents.shotBitmap(task, sw());
            sH.post(() -> {
                if (sLiveImg == null || sLive != live || b == null) return;   // no picture: home comes at let go
                sLiveImg.setImageBitmap(b);
                // Songate is NOT brought up behind during the swipe any more: a swipe that becomes the switcher showed
                // it through the fading app for a moment (screen recording, user 2026-11-19). Home comes at let go.
            });
            makeBlur(b);
        });
        stopFollow();
        sTarget = sShown = 0;
        sFollowK = 1f;
        apply(0);
    }

    /** The finger's position, p: 0 = full screen .. 1 = the size it has as a card in the row. Drawn once per frame. */
    static void drag(float p) {
        sTarget = Math.max(0, Math.min(1.15f, p));
        if (!sFollowing) { sFollowing = true; Choreographer.getInstance().postFrameCallback(FOLLOW); }
    }

    private static void apply(float p) {
        sProgress = Math.max(0, Math.min(1.15f, p));
        if (sLive == null) return;
        // Live swipe (sFollowK = 1): the app stays put (user, 2026-11-19: "the swipe to home should just be a smooth
        // fade"; the fade itself is in releaseHome). Switcher (sFollowK -> 0): it becomes the card in its slot in the
        // row (smaller, rounded).
        float k = sFollowK, q = Math.min(1, sProgress);
        float cardS = 1 - (1 - CARD_W) * q;
        float s = k + (1 - k) * cardS;
        sLive.setPivotX(sw() / 2f); sLive.setPivotY(sh() / 2f);
        sLive.setScaleX(s); sLive.setScaleY(s);
        float rowY = -dp(40) * q - dp(30) * Math.max(0, sProgress - 1);
        sLive.setTranslationY((1 - k) * rowY);
        sLive.setAlpha(1f);                // the app stays as it is while swiping; the fade comes at let go (home)
        sRadius = dp(28) / Math.max(s, 0.3f) * (1 - k) * Math.min(1, sProgress * 1.5f);
        sLive.invalidateOutline();
        scrim(q);
    }

    /** Let go of a swipe home: the app finishes fading into Songate (already behind it since the swipe started). */
    static void releaseHome(float velUp) {
        stopFollow();
        if (sLive == null) { GestureNav.goHome(); removeWindow(); return; }
        GestureNav.goHome(true);
        sReleasing = true;
        final View v = sLive;
        // the app's picture stays fully up until Songate is on screen and has drawn, then fades into it (fading at
        // once showed black frames in between: screen recording, 2026-11-19)
        final long until = SystemClock.uptimeMillis() + 350;
        sH.post(new Runnable() { @Override public void run() {
            if (sLive != v) return;
            if (!GestureNav.songateFront() && SystemClock.uptimeMillis() < until) { sH.postDelayed(this, 16); return; }
            sH.postDelayed(() -> { if (sLive == v) fadeHome(v, velUp); }, 70);
        } });
    }

    private static void fadeHome(final View v, float velUp) {
        final float a0 = v.getAlpha(), scrim0 = Math.min(1, sProgress);
        // a quicker flick finishes a little faster (160-260 ms)
        long ms = Math.round(Math.max(160, Math.min(260, 260 - velUp / sApp.getResources().getDisplayMetrics().density * 0.04f)));
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(ms);
        a.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
        a.addUpdateListener(x -> {
            float t = (float) x.getAnimatedValue();
            v.setAlpha(a0 * (1 - t));
            scrim(scrim0 * (1 - t));
        });
        a.addListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator an) { removeWindow(); } });
        a.start();
    }

    /** Changed your mind: the card grows back and the overlay goes. */
    static void cancel() {
        stopFollow();
        if (sLive == null) { if (!sOpen) removeWindow(); return; }
        float from = sProgress;
        ValueAnimator a = ValueAnimator.ofFloat(from, 0f).setDuration(240);
        a.setInterpolator(EMPHASIZED);
        a.addUpdateListener(x -> apply((float) x.getAnimatedValue()));
        a.addListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator an) {
            if (sHomeEarly && sCurrentTask >= 0) revealApp(sCurrentTask);   // Songate is behind: bring the app back
            else removeWindow();
        } });
        a.start();
    }

    /** Held: the switcher opens; the live card glides into the first slot and the others slide in. */
    static void settle() {
        if (sOpen) return;
        stopFollow();
        ensureWindow();
        if (sRoot == null) return;
        sOpen = true;
        sRoot.requestFocus();
        final int liveTask = sCurrentTask;
        EX.execute(() -> {
            List<Recents.Item> items = Recents.items(sApp);
            Bitmap[] shots = new Bitmap[items.size()];
            int cardW = Math.round(sw() * CARD_W);
            for (int i = 0; i < items.size() && i < 12; i++) shots[i] = Recents.shotBitmap(items.get(i).task, cardW);
            sH.post(() -> buildRow(items, shots, liveTask));
        });
        if (sLive != null) {          // glide from under the finger into its slot in the row; the blur comes in
            final float p0 = sProgress, k0 = sFollowK, h0 = sHold;
            // a little slower and softer than the other animations (user, 2026-11-19: "could be smoother")
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(340);
            a.setInterpolator(SETTLE);
            sSettleEnd = SystemClock.uptimeMillis() + 340;
            a.addUpdateListener(x -> {
                float t = (float) x.getAnimatedValue();
                sFollowK = k0 * (1 - t);
                sHold = h0 + (1 - h0) * t;
                apply(p0 + (1f - p0) * t);
            });
            a.start();
        } else {
            sHold = 1f;
            ActivityManager.RunningTaskInfo top = topTask();
            if (top != null) {
                final int t = top.taskId;
                EX.execute(() -> makeBlur(Recents.shotBitmap(t, sw() / 2)));
            }
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(260);
            a.setInterpolator(EMPHASIZED);
            a.addUpdateListener(x -> scrim((float) x.getAnimatedValue()));
            a.start();
        }
    }

    /** Open the switcher without a drag (for example from three-button navigation later). */
    static void show() { sCurrentTask = -1; settle(); }

    static boolean isOpen() { return sOpen; }

    // ------------------------------------------------------------------ the row (layout A)
    private static void buildRow(List<Recents.Item> items, Bitmap[] shots, int liveTask) {
        if (sRoot == null) return;
        int cardW = Math.round(sw() * CARD_W), cardH = Math.round(cardW * sh() / (float) sw());
        float gap = dp(16);
        if (items.isEmpty()) {
            if (sLive != null) { sRoot.removeView(sLive); sLive = null; }
            TextView t = text("No open apps", 17, 0xccffffff);
            sRoot.addView(t, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            t.setAlpha(0f); t.animate().alpha(1f).setDuration(160).start();
            return;
        }
        sScroll = new HorizontalScrollView(sApp);
        sScroll.setHorizontalScrollBarEnabled(false);
        sScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sRow = new LinearLayout(sApp);
        sRow.setOrientation(LinearLayout.HORIZONTAL);
        int side = (sw() - cardW) / 2;
        sRow.setPadding(side, 0, side, 0);
        sRow.setGravity(Gravity.CENTER_VERTICAL);
        sScroll.addView(sRow, new ViewGroup.LayoutParams(-2, -1));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-1, cardH + Math.round(dp(48)), Gravity.CENTER);
        slp.topMargin = -Math.round(dp(40));
        sRoot.addView(sScroll, sBlur != null ? 2 : 0, slp);
        for (int i = 0; i < items.size(); i++) {
            View card = card(items.get(i), i < shots.length ? shots[i] : null, cardW, cardH);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, -2);
            if (i > 0) lp.leftMargin = Math.round(gap);
            sRow.addView(card, lp);
            boolean live = items.get(i).task == liveTask && sLive != null;
            if (live) { card.setAlpha(0f); }
            else { card.setTranslationX(dp(80)); card.setAlpha(0f);
                   card.animate().translationX(0).alpha(1f).setStartDelay(40 + 30L * i).setDuration(240).setInterpolator(EMPHASIZED).start(); }
        }
        // the live card hands over to its slot in the row
        if (sLive != null) {
            final View liveView = sLive;
            // swap the live card for the row's own card only once it has arrived (mid-move it made a small jump)
            long wait = Math.max(60, sSettleEnd - SystemClock.uptimeMillis() + 20);
            sH.postDelayed(() -> {
                if (sRow != null && sRow.getChildCount() > 0) sRow.getChildAt(0).setAlpha(1f);
                if (sRoot != null) sRoot.removeView(liveView);
                if (sLive == liveView) { sLive = null; sLiveImg = null; }
            }, wait);
        }
        // "Close all" and the hint
        LinearLayout bottom = new LinearLayout(sApp);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView hint = text("Swipe a card up to close it", 13, 0x99ffffff);
        hint.setSingleLine(true);
        hint.setGravity(Gravity.CENTER);
        bottom.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        TextView all = text("✕  Close all", 16, 0xffffffff);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0x26ffffff); pill.setCornerRadius(dp(24));
        all.setBackground(pill);
        all.setPadding(Math.round(dp(22)), Math.round(dp(12)), Math.round(dp(22)), Math.round(dp(12)));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
        alp.topMargin = Math.round(dp(10));
        bottom.addView(all, alp);
        all.setOnClickListener(v -> closeAll());
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        blp.bottomMargin = Math.round(dp(56));
        sRoot.addView(bottom, blp);
        sBottom = bottom;
        bottom.setAlpha(0f); bottom.animate().alpha(1f).setStartDelay(120).setDuration(200).start();
    }

    private static TextView text(String s, float sp, int colour) {
        TextView t = new TextView(sApp);
        t.setText(s);
        t.setTextColor(colour);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private static View card(Recents.Item it, Bitmap shot, int cardW, int cardH) {
        LinearLayout box = new LinearLayout(sApp);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(sApp);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(sApp);
        if (it.icon != null) icon.setImageDrawable(it.icon);
        head.addView(icon, new LinearLayout.LayoutParams(Math.round(dp(26)), Math.round(dp(26))));
        TextView name = text(it.label, 15, 0xffffffff);
        name.setSingleLine(true);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.leftMargin = Math.round(dp(10));
        head.addView(name, nlp);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.bottomMargin = Math.round(dp(10));
        box.addView(head, hlp);
        FrameLayout frame = new FrameLayout(sApp);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xff1b1b22); bg.setCornerRadius(dp(22));
        frame.setBackground(bg);
        ImageView img = new ImageView(sApp);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (shot != null) img.setImageBitmap(shot);
        else if (it.icon != null) { img.setImageDrawable(it.icon); img.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                                    int pad = Math.round(cardW * 0.3f); img.setPadding(pad, pad, pad, pad); }
        frame.addView(img, new FrameLayout.LayoutParams(-1, -1));
        round(frame, dp(22));
        frame.setElevation(dp(6));
        box.addView(frame, new LinearLayout.LayoutParams(cardW, cardH));
        // tap = open; swipe up = close
        final float[] y0 = new float[1];
        final boolean[] dragging = new boolean[1];
        frame.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: y0[0] = e.getRawY(); dragging[0] = false; return true;
                case MotionEvent.ACTION_MOVE: {
                    float dy = e.getRawY() - y0[0];
                    if (!dragging[0] && dy < -dp(12)) { dragging[0] = true; sScroll.requestDisallowInterceptTouchEvent(true); }
                    if (dragging[0]) { box.setTranslationY(Math.min(0, dy)); box.setAlpha(1 + Math.min(0, dy) / (cardH * 1.2f)); }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (dragging[0]) {
                        if (box.getTranslationY() < -cardH * 0.22f) closeCard(box, it.task);
                        else box.animate().translationY(0).alpha(1f).setDuration(180).setInterpolator(STANDARD).start();
                    } else {
                        openCard(frame, it.task);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    box.animate().translationY(0).alpha(1f).setDuration(180).start();
                    return true;
            }
            return false;
        });
        return box;
    }

    private static void openCard(View frame, int task) {
        // The card grows to full screen, then the app takes over. Growing the card in place got clipped by the row
        // (user, 2026-11-19: "it zooms in in the square itself"), so a full-screen copy is lifted above the row,
        // shrunk onto the card, and grown back out: rounded corners go square on the way.
        if (sRoot == null || frame.getWidth() == 0) { Recents.open(sApp, Integer.toString(task)); dismiss(false); return; }
        int[] at = new int[2]; frame.getLocationOnScreen(at);
        int[] base = new int[2]; sRoot.getLocationOnScreen(base);
        final float x0 = at[0] - base[0], y0 = at[1] - base[1];
        final float s0x = frame.getWidth() / (float) sw(), s0y = frame.getHeight() / (float) sh();
        final FrameLayout fly = new FrameLayout(sApp);
        fly.setBackgroundColor(0xff1b1b22);
        ImageView src = (ImageView) ((ViewGroup) frame).getChildAt(0);
        ImageView img = new ImageView(sApp);
        img.setScaleType(src.getScaleType());
        img.setImageDrawable(src.getDrawable());
        if (src.getScaleType() == ImageView.ScaleType.CENTER_INSIDE) {        // icon-only card: keep the icon's size
            int pad = Math.round(sw() * 0.3f); img.setPadding(pad, pad, pad, pad);
        }
        fly.addView(img, new FrameLayout.LayoutParams(-1, -1));
        final float[] radius = { dp(22) / s0x };
        fly.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius[0]); }
        });
        fly.setClipToOutline(true);
        fly.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        fly.setPivotX(0); fly.setPivotY(0);
        sRoot.addView(fly, new FrameLayout.LayoutParams(sw(), sh()));
        frame.setAlpha(0f);
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(280);
        a.setInterpolator(EMPHASIZED);
        a.addUpdateListener(x -> {
            float t = (float) x.getAnimatedValue();
            float sx = s0x + (1 - s0x) * t, sy = s0y + (1 - s0y) * t;
            fly.setScaleX(sx); fly.setScaleY(sy);
            fly.setTranslationX(x0 * (1 - t)); fly.setTranslationY(y0 * (1 - t));
            radius[0] = dp(22) * (1 - t) / Math.max(sx, 0.3f);
            fly.invalidateOutline();
        });
        a.addListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator an) {
            revealApp(task);
        } });
        fly.setScaleX(s0x); fly.setScaleY(s0y); fly.setTranslationX(x0); fly.setTranslationY(y0);
        a.start();
        if (sScroll != null) sScroll.animate().alpha(0f).setDuration(160).start();
        if (sBottom != null) sBottom.animate().alpha(0f).setDuration(120).start();
    }

    /**
     * An app comes back over Songate (open from the switcher, or a swipe changed its mind): without Android's
     * transition, and the overlay (showing the app's picture) stays until the app is in front and has had time to draw,
     * then fades out. Removing it at once showed Songate for a split second (user tests 2026-11-19).
     */
    private static void revealApp(final int task) {
        final Root root = sRoot;
        if (!Recents.reopenInstant(sApp, task)) Recents.open(sApp, Integer.toString(task));   // not running: start it
        final long until = SystemClock.uptimeMillis() + 900;
        sH.post(new Runnable() { @Override public void run() {
            if (sRoot != root || root == null) return;
            ActivityManager.RunningTaskInfo top = topTask();
            boolean front = top != null && top.taskId == task;
            if (!front && SystemClock.uptimeMillis() < until) { sH.postDelayed(this, 30); return; }
            sH.postDelayed(() -> {
                if (sRoot != root) return;
                root.animate().alpha(0f).setDuration(150).withEndAction(() -> { if (sRoot == root) removeWindow(); }).start();
            }, 220);
        } });
    }

    private static void closeCard(View box, int task) {
        box.animate().translationY(-sh()).alpha(0f).setDuration(200).setInterpolator(EMPHASIZED).withEndAction(() -> {
            EX.execute(() -> Recents.close(sApp, Integer.toString(task)));
            if (sRow == null) return;
            int w = box.getWidth();
            ValueAnimator a = ValueAnimator.ofInt(w, 0).setDuration(200);
            a.setInterpolator(STANDARD);
            a.addUpdateListener(x -> { ViewGroup.LayoutParams lp = box.getLayoutParams(); lp.width = (int) x.getAnimatedValue(); box.setLayoutParams(lp); });
            a.addListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator an) {
                if (sRow != null) { sRow.removeView(box); if (sRow.getChildCount() == 0) dismiss(true); } } });
            a.start();
        }).start();
    }

    private static void closeAll() {
        if (sRow == null) { dismiss(true); return; }
        for (int i = 0; i < sRow.getChildCount(); i++)
            sRow.getChildAt(i).animate().translationY(-sh()).alpha(0f).setStartDelay(30L * i).setDuration(220).setInterpolator(EMPHASIZED).start();
        EX.execute(() -> Recents.close(sApp, "all"));
        sH.postDelayed(() -> { GestureNav.goHome(); dismiss(true); }, 260 + 30L * sRow.getChildCount());
    }

    /** Close the switcher; animated = fade out. */
    static void dismiss(boolean animated) {
        if (sRoot == null) return;
        if (!animated) { removeWindow(); return; }
        View r = sRoot;
        r.animate().alpha(0f).setDuration(160).withEndAction(Switcher::removeWindow).start();
    }

    // ------------------------------------------------------------------ helpers
    @SuppressWarnings("deprecation")
    private static ActivityManager.RunningTaskInfo topTask() {
        try {
            List<ActivityManager.RunningTaskInfo> l = sApp.getSystemService(ActivityManager.class).getRunningTasks(1);
            return l.isEmpty() ? null : l.get(0);
        } catch (RuntimeException e) { return null; }
    }

    private static boolean isHome(ActivityManager.RunningTaskInfo t) {
        String p = t.baseActivity != null ? t.baseActivity.getPackageName() : t.topActivity != null ? t.topActivity.getPackageName() : "";
        return Setup.SONGATE.equals(p);
    }
}

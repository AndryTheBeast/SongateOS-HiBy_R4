package com.andre.hibyr4sound;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * SongateOS gesture navigation: swipe up from the bottom edge = home (Songate), swipe up and hold = Songate's app
 * switcher (user, 2026-11-18). In Android's gesture navigation these two swipes come from the launcher's Quickstep
 * service, and SongateOS has no such launcher (Songate is the only one), so they did nothing. Back stays Android's
 * own (SystemUI, side edges).
 *
 * A thin invisible system window along the bottom edge (the service runs as the system uid, so it may add one) gets
 * every touch that starts there, including the rest of the swipe outside it. Active only in gesture navigation
 * (Settings.Secure navigation_mode = 2); three-button navigation keeps Android's own buttons.
 */
final class GestureNav {
    static final String TAG = "SongateGesture";
    static final String SWITCHER = "songateos.intent.action.APP_SWITCHER";   // handled by Songate

    private static final int TYPE_NAVIGATION_BAR_PANEL = 2024;   // WindowManager.LayoutParams (system window)
    private static final float STRIP_DP = 20f;        // touch strip height along the bottom edge (pill inside; user kept 20, 2026-11-18)
    private static final float HOME_MIN = 0.06f;      // of the screen height: a swipe this long goes home
    private static final float HOLD_MIN = 0.09f;      // ... this long, then held still, opens the switcher
    private static final float FLING_DP_S = 900f;     // or a quick flick upwards (dp per second) goes home
    private static final float STILL_DP_S = 200f;     // slower than this counts as holding still
    private static final long HOLD_MS = 50;

    private static Context sApp;
    private static Handler sH;
    private static View sStrip;
    private static volatile boolean sSongateFront;   // Songate (home) on screen: no strip, no pill (user, 2026-11-18)

    private GestureNav() { }

    /** User, 2026-11-19: "drop the pill, we don't need the pill at all". The swipe strip stays, invisible. */
    private static final boolean NO_PILL = true;

    /** Songate (home) is on screen (its uid is in the foreground). */
    static boolean songateFront() { return sSongateFront; }

    static void start(Context c) {
        if (sApp != null) return;
        sApp = c.getApplicationContext();
        sH = new Handler(Looper.getMainLooper());
        sApp.getContentResolver().registerContentObserver(Settings.Secure.getUriFor("navigation_mode"), false,
                new ContentObserver(sH) { @Override public void onChange(boolean self) { update(); } });
        watchSongate();
        update();
    }

    /**
     * Inside Songate, SongateOS feels like an OS: Songate can't be swiped away, and its bottom buttons get every touch.
     * So the strip (and its pill) is only there while another app is in front. Android tells us when Songate's uid
     * comes to the foreground or leaves it (ActivityManager.OnUidImportanceListener, a system API: reflection).
     */
    private static void watchSongate() {
        try {
            final int uid = sApp.getPackageManager().getApplicationInfo(Setup.SONGATE, 0).uid;
            android.app.ActivityManager am = sApp.getSystemService(android.app.ActivityManager.class);
            Class<?> li = Class.forName("android.app.ActivityManager$OnUidImportanceListener");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(li.getClassLoader(), new Class<?>[]{ li }, (o, m, a) -> {
                // Object's own methods: Android keeps listeners in a map (hashCode/equals) - beta.6 v58 failed here
                switch (m.getName()) {
                    case "hashCode": return System.identityHashCode(o);
                    case "equals": return a != null && a.length == 1 && o == a[0];
                    case "toString": return "SongateOS gesture strip: Songate foreground listener";
                }
                if ("onUidImportance".equals(m.getName()) && a != null && a.length >= 2 && (Integer) a[0] == uid) {
                    boolean front = (Integer) a[1] <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
                    if (front != sSongateFront) { sSongateFront = front; sH.post(GestureNav::update); }
                }
                return null;
            });
            android.app.ActivityManager.class.getMethod("addOnUidImportanceListener", li, int.class)
                    .invoke(am, proxy, android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
            int imp = (Integer) android.app.ActivityManager.class.getMethod("getUidImportance", int.class).invoke(am, uid);
            sSongateFront = imp <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        } catch (Throwable t) {
            Log.w(TAG, "can't watch Songate: the strip stays on everywhere", t);
        }
    }

    private static boolean gestural() {
        return Settings.Secure.getInt(sApp.getContentResolver(), "navigation_mode", 0) == 2;
    }

    private static void update() {
        boolean want = gestural() && !sSongateFront;
        WindowManager wm = sApp.getSystemService(WindowManager.class);
        try {
            if (want && sStrip == null) {
                float d = sApp.getResources().getDisplayMetrics().density;
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT, Math.round(STRIP_DP * d), TYPE_NAVIGATION_BAR_PANEL,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.BOTTOM;
                lp.setTitle("SongateOS gestures");
                lp.setFitInsetsTypes(0);
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                sStrip = new Strip(sApp);
                wm.addView(sStrip, lp);
                Log.i(TAG, "on (gesture navigation)");
            } else if (!want && sStrip != null && ((Strip) sStrip).mTracking) {
                // Songate comes up behind the card as soon as a swipe starts (Switcher, v86): keep the strip until the
                // finger is lifted, or the swipe loses its touches and the card freezes (user test 2026-11-19)
                Log.i(TAG, "off after this swipe");
            } else if (!want && sStrip != null) {
                wm.removeView(sStrip);
                sStrip = null;
                Log.i(TAG, "off (button navigation)");
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "strip", e);
            sStrip = null;
        }
    }

    static void goHome() { goHome(false); }

    /** instant: no Android transition (the switcher overlay animates the card itself, so the app just goes). */
    static void goHome(boolean instant) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (instant) i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try { sApp.startActivity(i); } catch (RuntimeException e) { Log.w(TAG, "home", e); }
    }

    static void openSwitcher() {
        Intent i = new Intent(SWITCHER).setPackage(Setup.SONGATE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            sApp.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Log.i(TAG, "Songate has no app switcher yet: home instead");
            goHome();
        } catch (RuntimeException e) {
            Log.w(TAG, "switcher", e);
        }
    }

    /**
     * The touch strip: decides home / switcher from one swipe that starts in it, and draws a pill like Android's
     * gesture handle so each stage shows (user, 2026-11-18):
     *   rest:              a thin light pill at the bottom edge (where to swipe);
     *   past the home distance: the pill widens and brightens, light tick - letting go now goes home;
     *   held still there:  the pill turns the system colour, firm tick, and Songate's app switcher opens.
     */
    private static final class Strip extends View {
        private float mY0, mLastY, mVel;
        private long mLastT, mStillSince;
        private boolean mTracking, mSwitcher, mLive;
        private int mStage;                       // 0 rest, 1 home armed, 2 switcher
        private boolean mBarHidden;               // the app in front hides the navigation bar
        private final float mDensity;
        private final android.graphics.Paint mPill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint mShade = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF mRect = new android.graphics.RectF();

        Strip(Context c) {
            super(c);
            mDensity = c.getResources().getDisplayMetrics().density;
            // Like Android's own handle: no pill while the app in front hides the navigation bar (full screen,
            // Songate's own screen); the swipe still works, and the pill shows during a swipe (user, 2026-11-18)
            setOnApplyWindowInsetsListener((v, in) -> {
                boolean hidden = !in.isVisible(android.view.WindowInsets.Type.navigationBars());
                if (hidden != mBarHidden) { mBarHidden = hidden; invalidate(); }
                return in;
            });
            mShade.setColor(0x40000000);          // a soft dark edge, so the pill shows on light screens too
        }

        private float screenH() { return getResources().getDisplayMetrics().heightPixels; }

        // drawn values, animated towards the stage's targets (Material curve, 200 ms) - user, 2026-11-19: smoother
        private float mW = -1, mH, mLift;
        private int mColour;
        private android.animation.ValueAnimator mAnim;

        private int targetColour(int st) {
            return st == 2 ? (0xff000000 | SystemTheme.currentColour(getContext())) : st == 1 ? 0xf2ffffff : 0x99ffffff;
        }

        private void stage(int st) {
            if (st == mStage) return;
            mStage = st;
            if (st == 1) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            final float w0 = mW, h0 = mH, w1 = (st == 0 ? 64 : 104) * mDensity, h1 = (st == 0 ? 4 : 5) * mDensity;
            final int c0 = mColour, c1 = targetColour(st);
            if (mAnim != null) mAnim.cancel();
            mAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).setDuration(200);
            mAnim.setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f));
            final android.animation.ArgbEvaluator ev = new android.animation.ArgbEvaluator();
            mAnim.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                mW = w0 + (w1 - w0) * f; mH = h0 + (h1 - h0) * f;
                mColour = (Integer) ev.evaluate(f, c0, c1);
                invalidate();
            });
            mAnim.start();
        }

        /** The pill rises a little with the finger (at most 6 dp), so it feels attached to the swipe. */
        private void lift(float up) {
            float l = Math.max(0, Math.min(6 * mDensity, up * 0.04f));
            if (l != mLift) { mLift = l; invalidate(); }
        }

        @Override
        protected void onDraw(android.graphics.Canvas cv) {
            if (NO_PILL) return;                                    // the strip works, but draws nothing (2026-11-19)
            if (mBarHidden && mStage == 0 && !mTracking) return;   // full screen: no pill at rest
            if (mW < 0) { mW = 64 * mDensity; mH = 4 * mDensity; mColour = targetColour(0); }
            float w = mW, h = mH;
            float cx = getWidth() / 2f, cy = getHeight() - 9 * mDensity - mLift;
            int colour = mColour;
            mRect.set(cx - w / 2 - mDensity, cy - h / 2 - mDensity, cx + w / 2 + mDensity, cy + h / 2 + mDensity);
            cv.drawRoundRect(mRect, h, h, mShade);
            mRect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
            mPill.setColor(colour);
            cv.drawRoundRect(mRect, h / 2, h / 2, mPill);
        }

        private final Runnable mHoldCheck = new Runnable() {
            @Override public void run() {
                if (!mTracking || mSwitcher) return;
                float dy = mY0 - mLastY;
                long now = SystemClock.uptimeMillis();
                if (mStillSince == 0 && now - mLastT >= 30) mStillSince = mLastT;   // no movement at all
                if (dy >= HOLD_MIN * screenH() && mStillSince > 0 && now - mStillSince >= HOLD_MS) {
                    mSwitcher = true;
                    stage(2);
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    Switcher.settle();                    // the live card glides into the row
                    postDelayed(() -> stage(0), 450);
                } else {
                    postDelayed(this, 16);
                }
            }
        };

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float y = e.getRawY();
            long t = e.getEventTime();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mY0 = mLastY = y; mLastT = t; mVel = 0; mStillSince = 0;
                    mTracking = true; mSwitcher = false; mLive = false;
                    invalidate();
                    postDelayed(mHoldCheck, 16);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (!mTracking) return true;
                    long dt = Math.max(1, t - mLastT);
                    float v = (mLastY - y) * 1000f / dt;        // px/s, upwards positive
                    mVel = 0.6f * mVel + 0.4f * v;
                    mLastY = y; mLastT = t;
                    boolean still = Math.abs(mVel) < STILL_DP_S * mDensity;
                    if (!still) mStillSince = 0; else if (mStillSince == 0) mStillSince = SystemClock.uptimeMillis();
                    if (!mSwitcher) stage(mY0 - y >= HOME_MIN * screenH() ? 1 : 0);
                    lift(mY0 - y);
                    // the current app follows the finger, like Android's recents
                    float up = mY0 - y;
                    if (!mLive && up > 0.02f * screenH()) { mLive = true; Switcher.begin(); }
                    if (mLive && !mSwitcher) Switcher.drag(up / (0.30f * screenH()));
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    removeCallbacks(mHoldCheck);
                    if (mTracking && !mSwitcher) {
                        float dy = mY0 - y;
                        boolean flick = mVel > FLING_DP_S * mDensity && dy > 12 * mDensity;
                        lift(0);
                        if (dy >= HOME_MIN * screenH() || flick) { if (mLive) Switcher.releaseHome(mVel); else goHome(); }
                        else if (mLive) Switcher.cancel();
                        stage(0);
                    }
                    mTracking = false;
                    invalidate();
                    sH.post(GestureNav::update);              // a hide that waited for this swipe
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(mHoldCheck);
                    if (mLive && !mSwitcher) Switcher.cancel();
                    mTracking = false;
                    if (!mSwitcher) stage(0);
                    sH.post(GestureNav::update);
                    return true;
            }
            return true;
        }
    }
}

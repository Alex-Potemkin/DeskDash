package com.deskdash;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The PC on the phone during a call: its screen full size, the webcam in a corner on top (drag it
 * anywhere, tap to swap the two), and the call's buttons — mic, camera, screen share, hand, leave.
 */
public class StreamActivity extends Activity {
    private static final int BAR = 0xCC141414, BTN = 0x33FFFFFF, RED = 0xFFE5534B, WHITE = 0xFFFFFFFF;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private PcClient pc;
    private float dp;
    private boolean hq, camOn = true, swapped;

    private ImageView main, pip;
    private FrameLayout pipBox;
    private TextView status, callInfo;
    private LinearLayout top, controls;
    private TextView micBtn, camBtn, shareBtn, handBtn, leaveBtn, pipBtn;
    private MjpegStream screen, camera;
    private PcClient.Call call;
    private Boolean muted;
    private boolean leaveArmed;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dp = getResources().getDisplayMetrics().density;
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        Prefs prefs = new Prefs(this);
        pc = new PcClient(prefs.pcHost(), prefs.pcPort(), prefs.pcToken());

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        main = new ImageView(this);
        main.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(main, new FrameLayout.LayoutParams(-1, -1));
        root.setOnClickListener(v -> toggleChrome());

        pipBox = new FrameLayout(this);
        pipBox.setBackground(round(0xFF202020, 16));
        pipBox.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 16 * dp);
            }
        });
        pipBox.setClipToOutline(true);
        pipBox.setElevation(10 * dp);
        pip = new ImageView(this);
        pip.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pipBox.addView(pip, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(px(200), px(150), Gravity.BOTTOM | Gravity.END);
        plp.setMargins(0, 0, px(16), px(92));
        root.addView(pipBox, plp);
        dragPip();

        status = new TextView(this);
        status.setTextColor(WHITE);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        status.setGravity(Gravity.CENTER);
        status.setPadding(px(20), px(12), px(20), px(12));
        status.setBackground(round(BAR, 14));
        status.setText("Подключаюсь к ПК…");
        root.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(px(8), px(6), px(14), px(6));
        top.setBackground(round(BAR, 18));
        TextView back = button("‹  Назад", v -> finish());
        top.addView(back);
        callInfo = new TextView(this);
        callInfo.setTextColor(WHITE);
        callInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        callInfo.setSingleLine();
        callInfo.setEllipsize(TextUtils.TruncateAt.END);
        callInfo.setPadding(px(12), 0, px(4), 0);
        top.addView(callInfo, new LinearLayout.LayoutParams(-2, -2));
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        tlp.setMargins(px(16), px(14), px(16), 0);
        root.addView(top, tlp);

        controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(px(10), px(8), px(10), px(8));
        controls.setBackground(round(BAR, 24));
        micBtn = button("Микрофон", v -> callAction(muted != null && muted ? "unmute" : "mute"));
        camBtn = button("Камера", v -> callAction("camera"));
        shareBtn = button("Демонстрация", v -> callAction("share"));
        handBtn = button("Рука", v -> callAction("hand"));
        leaveBtn = button("Выйти", v -> leave());
        pipBtn = button("Вебка", v -> {
            camOn = !camOn;
            applyPip();
        });
        TextView quality = button("Чётче", null);
        quality.setOnClickListener(v -> {
            hq = !hq;
            quality.setText(hq ? "Быстрее" : "Чётче");
            restartScreen();
        });
        for (TextView b : new TextView[]{micBtn, camBtn, shareBtn, handBtn, leaveBtn, pipBtn, quality}) {
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, px(44));
            l.setMargins(px(4), 0, px(4), 0);
            controls.addView(b, l);
        }
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        clp.bottomMargin = px(18);
        root.addView(controls, clp);
        setContentView(root);
        renderCall();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
        restartScreen();
        applyPip();
        ui.post(poll);
        showChrome();
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(poll);
        ui.removeCallbacks(hideChrome);
        if (screen != null) screen.stop();
        if (camera != null) camera.stop();
        screen = camera = null;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    // ---------------------------------------------------------------- streams

    private void restartScreen() {
        if (screen != null) screen.stop();
        int w = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        String q = hq ? "w=" + Math.min(2560, w) + "&q=75&fps=6" : "w=" + Math.min(1280, w) + "&q=55&fps=10";
        screen = new MjpegStream(pc.base() + "/api/stream/screen?" + q, pc.token(), new MjpegStream.Listener() {
            @Override
            public void onFrame(Bitmap b) {
                (swapped ? pip : main).setImageBitmap(b);
            }

            @Override
            public void onStatus(String msg) {
                showStatus(msg);
            }
        });
        screen.start();
    }

    private void applyPip() {
        pipBox.setVisibility(camOn ? View.VISIBLE : View.GONE);
        pipBtn.setBackground(round(camOn ? 0x66FFFFFF : BTN, 22));
        if (camOn && camera == null && screen != null) {
            camera = new MjpegStream(pc.base() + "/api/stream/camera", pc.token(), new MjpegStream.Listener() {
                @Override
                public void onFrame(Bitmap b) {
                    (swapped ? main : pip).setImageBitmap(b);
                }

                @Override
                public void onStatus(String msg) {
                    if (msg != null && !swapped) pip.setImageBitmap(null);
                    if (msg != null) pipBtn.setText("Вебка ✕");
                    else pipBtn.setText("Вебка");
                }
            });
            camera.start();
        } else if (!camOn && camera != null) {
            camera.stop();
            camera = null;
            if (swapped) swap();
        }
    }

    private void swap() {
        swapped = !swapped;
        android.graphics.drawable.Drawable a = main.getDrawable(), b = pip.getDrawable();
        main.setImageDrawable(b);
        pip.setImageDrawable(a);
        main.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pip.setScaleType(swapped ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
    }

    private void showStatus(String msg) {
        status.setVisibility(msg == null ? View.GONE : View.VISIBLE);
        if (msg != null) status.setText(msg);
    }

    /** The webcam window follows the finger; a tap without moving swaps it with the screen. */
    private void dragPip() {
        float[] start = new float[4];
        boolean[] moved = new boolean[1];
        pipBox.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    start[0] = e.getRawX();
                    start[1] = e.getRawY();
                    start[2] = v.getTranslationX();
                    start[3] = v.getTranslationY();
                    moved[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - start[0], dy = e.getRawY() - start[1];
                    if (Math.abs(dx) + Math.abs(dy) > 12 * dp) moved[0] = true;
                    if (moved[0]) {
                        View p = (View) v.getParent();
                        float minX = -(v.getLeft()), maxX = p.getWidth() - v.getRight();
                        float minY = -(v.getTop()), maxY = p.getHeight() - v.getBottom();
                        v.setTranslationX(Math.max(minX, Math.min(maxX, start[2] + dx)));
                        v.setTranslationY(Math.max(minY, Math.min(maxY, start[3] + dy)));
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!moved[0]) swap();
                    return true;
                default:
                    return true;
            }
        });
    }

    // ---------------------------------------------------------------- call

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            io.execute(() -> {
                try {
                    PcClient.Calls c = pc.calls();
                    ui.post(() -> {
                        call = c.calls.isEmpty() ? null : c.calls.get(0);
                        muted = c.micMuted;
                        renderCall();
                    });
                } catch (Exception ignored) {
                    // the screen stream shows connection trouble already
                }
            });
            ui.postDelayed(this, 2000);
        }
    };

    private void renderCall() {
        String name = new Prefs(this).pcHost();
        if (call == null) {
            callInfo.setText("Экран ПК" + (name.isEmpty() ? "" : " · " + name));
        } else {
            long s = Math.max(0, (System.currentTimeMillis() - call.since) / 1000);
            String t = s >= 3600 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                    : String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60);
            callInfo.setText("● " + call.name + (call.title.isEmpty() ? "" : " · " + call.title) + "   " + t);
            callInfo.setTextColor(call.color | 0xFF000000);
        }
        boolean m = muted != null && muted;
        micBtn.setText(muted == null ? "Микрофон" : m ? "Микрофон выкл" : "Микрофон вкл");
        micBtn.setBackground(round(m ? RED : BTN, 22));
        micBtn.setVisibility(muted == null ? View.GONE : View.VISIBLE);
        boolean has = call != null;
        camBtn.setVisibility(has && call.actions.contains("camera") ? View.VISIBLE : View.GONE);
        camBtn.setText(has && call.camera ? "Камера вкл" : "Камера выкл");
        shareBtn.setVisibility(has && call.actions.contains("share") ? View.VISIBLE : View.GONE);
        handBtn.setVisibility(has && call.actions.contains("hand") ? View.VISIBLE : View.GONE);
        leaveBtn.setVisibility(has && call.actions.contains("leave") ? View.VISIBLE : View.GONE);
    }

    private void callAction(String action) {
        controls.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        showChrome();
        String id = call == null ? "" : call.id;
        if (action.equals("mute") || action.equals("unmute")) {
            muted = action.equals("mute");
            renderCall();
        }
        io.execute(() -> {
            try {
                String note = pc.callAction(action, id);
                if (!note.isEmpty()) ui.post(() -> Toast.makeText(this, note, Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, SmartHome.msg(e), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void leave() {
        if (!leaveArmed) {
            leaveArmed = true;
            leaveBtn.setText("Точно выйти?");
            leaveBtn.setBackground(round(RED, 22));
            ui.postDelayed(() -> {
                leaveArmed = false;
                leaveBtn.setText("Выйти");
                leaveBtn.setBackground(round(0x99E5534B, 22));
            }, 3000);
            return;
        }
        leaveArmed = false;
        leaveBtn.setText("Выйти");
        callAction("leave");
    }

    // ---------------------------------------------------------------- chrome

    private final Runnable hideChrome = () -> {
        top.animate().alpha(0f).setDuration(250).start();
        controls.animate().alpha(0f).setDuration(250).withEndAction(() -> controls.setVisibility(View.INVISIBLE)).start();
    };

    private void showChrome() {
        controls.setVisibility(View.VISIBLE);
        top.animate().alpha(1f).setDuration(150).start();
        controls.animate().alpha(1f).setDuration(150).start();
        ui.removeCallbacks(hideChrome);
        ui.postDelayed(hideChrome, 5000);
    }

    private void toggleChrome() {
        if (controls.getVisibility() == View.VISIBLE && controls.getAlpha() > 0.5f) hideChrome.run();
        else showChrome();
    }

    @SuppressWarnings("deprecation")
    private void hideBars() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    // ---------------------------------------------------------------- helpers

    private TextView button(String label, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        t.setSingleLine();
        t.setPadding(px(16), 0, px(16), 0);
        t.setBackground(round(label.equals("Выйти") ? 0x99E5534B : BTN, 22));
        if (l != null) t.setOnClickListener(l);
        return t;
    }

    private GradientDrawable round(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r * dp);
        return g;
    }

    private int px(float v) {
        return Math.round(v * dp);
    }
}

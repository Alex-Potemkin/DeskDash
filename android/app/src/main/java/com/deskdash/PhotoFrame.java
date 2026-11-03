package com.deskdash;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * The "Фото" widget: photos from the NAS folder the hub hands out, one after another every 20 seconds
 * with a slow crossfade and a gentle zoom, as the photo view of StandBy does.
 */
final class PhotoFrame extends FrameLayout {
    private static final long EVERY_MS = 20_000;

    private final Prefs prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ImageView[] views = new ImageView[2];
    private final TextView hint;
    private final List<String> photos = new ArrayList<>();
    private int front, next;
    private boolean live;

    PhotoFrame(Context c, DashTheme th, Prefs prefs) {
        super(c);
        this.prefs = prefs;
        float dp = c.getResources().getDisplayMetrics().density;
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.R_WIDGET * dp);
            }
        });
        setClipToOutline(true);
        setBackgroundColor(th.surface);
        for (int i = 0; i < 2; i++) {
            ImageView v = new ImageView(c);
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setAlpha(0f);
            addView(v, new LayoutParams(-1, -1));
            views[i] = v;
        }
        hint = Ui.text(c, th.dim, 15, 400);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(Math.round(20 * dp), 0, Math.round(20 * dp), 0);
        hint.setText(HubClient.configured(prefs) ? "Загружаю фото с хаба…" : "Фото с NAS показываются через хаб:\nНастройки → «Умный дом» → хаб");
        addView(hint, new LayoutParams(-1, -1, Gravity.CENTER));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        live = true;
        if (!HubClient.configured(prefs)) return;
        new Thread(() -> {
            List<String> l;
            try {
                l = new HubClient(prefs).photos();
            } catch (Exception e) {
                ui.post(() -> hint.setText("Хаб: " + SmartHome.msg(e)));
                return;
            }
            ui.post(() -> {
                photos.clear();
                photos.addAll(l);
                if (photos.isEmpty()) hint.setText("В хабе не выбрана папка с фото:\nвеб-приложение хаба → Настройки → Фото");
                else show();
            });
        }).start();
    }

    @Override
    protected void onDetachedFromWindow() {
        live = false;
        ui.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    private final Runnable tick = this::show;

    private void show() {
        if (!live || photos.isEmpty()) return;
        String rel = photos.get(next++ % photos.size());
        String url = new HubClient(prefs).photoUrl(rel);
        int w = Math.max(400, getWidth()), h = Math.max(300, getHeight());
        new Thread(() -> {
            Bitmap b = load(url, w, h);
            ui.post(() -> {
                if (!live) return;
                if (b != null) {
                    hint.setVisibility(GONE);
                    ImageView in = views[1 - front], out = views[front];
                    in.setImageBitmap(b);
                    in.setScaleX(1f);
                    in.setScaleY(1f);
                    in.animate().alpha(1f).setDuration(1500).start();
                    // a slow zoom while the photo is on screen
                    in.animate().scaleX(1.08f).scaleY(1.08f).setDuration(EVERY_MS + 1500).start();
                    out.animate().alpha(0f).setDuration(1500).start();
                    front = 1 - front;
                }
                ui.postDelayed(tick, EVERY_MS);
            });
        }).start();
    }

    /** Downloads and decodes a photo no bigger than needed for the widget. */
    private static Bitmap load(String url, int w, int h) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(15000);
            byte[] data;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                data = b.toByteArray();
            } finally {
                c.disconnect();
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int s = 1;
            while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = s;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception e) {
            return null;
        }
    }
}

package com.deskdash;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A "Умный дом" block: tiles for the devices picked in the desk editor. */
final class HomePanel extends LinearLayout {
    interface Listener {
        void onTap(HomeDevice d);

        void onLong(HomeDevice d);

        void onSetup();

        /** Camera / printer picture for a tile, null while there is none; may start loading it. */
        Bitmap image(HomeDevice d);
    }

    private static final int ALERT = 0xFFE5534B;

    private final DashTheme th;
    private final float dp;
    private final List<String[]> items;
    private final Listener listener;
    private final TileGrid grid;
    private final TextView empty;
    private final Map<String, Tile> tiles = new HashMap<>();

    HomePanel(Context c, DashTheme th, String title, List<String[]> items, Listener l) {
        super(c);
        this.th = th;
        this.items = items;
        listener = l;
        dp = c.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);

        if (!title.isEmpty()) {
            TextView t = text(th.dim, 12);
            t.setText(title.toUpperCase(Locale.getDefault()));
            t.setLetterSpacing(0.16f);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setSingleLine();
            LayoutParams lp = new LayoutParams(-1, -2);
            lp.setMargins(px(4), px(6), 0, px(8));
            addView(t, lp);
        }

        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(OVER_SCROLL_NEVER);
        sv.setVerticalFadingEdgeEnabled(true);
        sv.setFadingEdgeLength(px(20));
        grid = new TileGrid(c, px(108), px(78), px(8));
        sv.addView(grid, new ScrollView.LayoutParams(-1, -2));
        addView(sv, new LayoutParams(-1, 0, 1));

        empty = text(th.dim, 14);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(px(16), px(12), px(16), px(12));
        empty.setBackground(rounded(th.surface, 16));
        empty.setVisibility(GONE);
        addView(empty, new LayoutParams(-1, -2));

        for (String[] it : items) {
            Tile t = new Tile(c, it[0], it[1]);
            tiles.put(it[0], t);
            grid.addView(t.root);
        }
    }

    /** Height for "as tall as the content" blocks: ScrollView with weight would collapse to 0. */
    @Override
    protected void onMeasure(int w, int h) {
        if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED || MeasureSpec.getMode(h) == MeasureSpec.AT_MOST) {
            View sv = getChildAt(getChildCount() - 2);
            LayoutParams lp = (LayoutParams) sv.getLayoutParams();
            if (lp.weight > 0) {
                lp.height = -2;
                lp.weight = 0;
            }
        }
        super.onMeasure(w, h);
    }

    void render(Map<String, HomeDevice> devs, boolean configured, String error) {
        if (!configured) {
            showEmpty("Подключи Home Assistant или Яндекс\nв настройках → «Умный дом»");
            return;
        }
        if (items.isEmpty()) {
            showEmpty("Нажми, чтобы выбрать устройства");
            return;
        }
        boolean any = false;
        for (String[] it : items) {
            HomeDevice d = devs.get(it[0]);
            tiles.get(it[0]).bind(d);
            if (d != null) any = true;
        }
        if (!any && error != null) showEmpty(error);
        else empty.setVisibility(GONE);
    }

    private void showEmpty(String msg) {
        empty.setText(msg);
        empty.setVisibility(VISIBLE);
        empty.setOnClickListener(v -> listener.onSetup());
    }

    // ---------------------------------------------------------------- tile

    private final class Tile {
        final FrameLayout root;
        final LinearLayout box;
        final ImageView icon, pic;
        final View scrim;
        final TextView name, status;
        final Progress bar;
        HomeDevice dev;

        Tile(Context c, String id, String savedName) {
            root = new FrameLayout(c);
            root.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 16 * dp);
                }
            });
            root.setClipToOutline(true);
            pic = new ImageView(c);
            pic.setScaleType(ImageView.ScaleType.CENTER_CROP);
            pic.setVisibility(GONE);
            root.addView(pic, new FrameLayout.LayoutParams(-1, -1));
            scrim = new View(c);
            scrim.setBackground(new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xB0000000}));
            scrim.setVisibility(GONE);
            root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
            box = new LinearLayout(c);
            box.setOrientation(VERTICAL);
            box.setPadding(px(12), px(10), px(10), px(8));
            root.addView(box, new FrameLayout.LayoutParams(-1, -1));
            icon = new ImageView(c);
            box.addView(icon, new LayoutParams(px(22), px(22)));
            name = text(th.text, 13);
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setText(savedName.isEmpty() ? id : savedName);
            LayoutParams nl = new LayoutParams(-1, 0, 1);
            nl.topMargin = px(4);
            name.setGravity(Gravity.BOTTOM);
            box.addView(name, nl);
            status = text(th.dim, 11);
            status.setSingleLine();
            status.setEllipsize(TextUtils.TruncateAt.END);
            status.setText("…");
            box.addView(status, new LayoutParams(-1, -2));
            bar = new Progress(c);
            bar.setVisibility(GONE);
            LayoutParams bl = new LayoutParams(-1, px(3));
            bl.topMargin = px(4);
            box.addView(bar, bl);
            root.setOnClickListener(v -> {
                if (dev == null) return;
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                listener.onTap(dev);
            });
            root.setOnLongClickListener(v -> {
                if (dev == null) return false;
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                listener.onLong(dev);
                return true;
            });
            bind(null);
        }

        void bind(HomeDevice d) {
            dev = d;
            boolean lit = d != null && d.active();
            boolean alert = d != null && d.alert;
            int bg = alert ? DashTheme.blend(th.surface, ALERT, 0.45f)
                    : lit ? DashTheme.blend(th.surface, th.accent, th.light ? 0.30f : 0.38f) : th.surface;
            root.setBackground(rounded(bg, 16));
            root.setAlpha(d == null || !d.available ? 0.45f : 1f);
            Bitmap img = d != null && !d.image.isEmpty() ? listener.image(d) : null;
            boolean photo = img != null;
            pic.setVisibility(photo ? VISIBLE : GONE);
            scrim.setVisibility(photo ? VISIBLE : GONE);
            if (photo) pic.setImageBitmap(img);
            int fg = photo ? 0xFFFFFFFF : lit || alert ? DashTheme.onColor(bg) : th.text;
            icon.setImageResource(iconFor(d));
            int ic = photo ? 0xFFFFFFFF : alert ? DashTheme.onColor(bg) : lit ? th.accent : th.dim;
            if (lit && !photo && DashTheme.onColor(bg) != DashTheme.onColor(th.accent)) ic = DashTheme.onColor(bg);
            icon.setImageTintList(ColorStateList.valueOf(ic));
            if (d != null) {
                name.setText(d.name);
                status.setText(d.status());
            } else {
                status.setText("…");
            }
            name.setTextColor(fg);
            status.setTextColor(photo ? 0xD9FFFFFF : lit || alert ? DashTheme.blend(fg, bg, 0.3f) : th.dim);
            boolean job = d != null && d.progress >= 0 && d.on;
            bar.setVisibility(job ? VISIBLE : GONE);
            if (job) bar.set(d.progress / 100f, photo ? 0x55FFFFFF : DashTheme.blend(bg, fg, 0.2f), photo ? 0xFFFFFFFF : th.accent);
        }
    }

    /** Thin job progress line at the bottom of a tile. */
    private static final class Progress extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float frac;
        int track, fg;

        Progress(Context c) {
            super(c);
        }

        void set(float f, int track, int fg) {
            frac = f;
            this.track = track;
            this.fg = fg;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float r = getHeight() / 2f;
            p.setColor(track);
            c.drawRoundRect(0, 0, getWidth(), getHeight(), r, r, p);
            p.setColor(fg);
            c.drawRoundRect(0, 0, getWidth() * frac, getHeight(), r, r, p);
        }
    }

    private static int iconFor(HomeDevice d) {
        if (d == null) return R.drawable.ic_home;
        switch (d.kind) {
            case HomeDevice.LIGHT: return R.drawable.ic_bulb;
            case HomeDevice.CLIMATE: return R.drawable.ic_thermo;
            case HomeDevice.COVER: return R.drawable.ic_blinds;
            case HomeDevice.LOCK: return R.drawable.ic_lock;
            case HomeDevice.SCENE: return R.drawable.ic_star;
            case HomeDevice.FAN: return R.drawable.ic_fan;
            case HomeDevice.MEDIA: return R.drawable.ic_speaker;
            case HomeDevice.PRINTER: return R.drawable.ic_printer;
            case HomeDevice.VACUUM: return R.drawable.ic_vacuum;
            case HomeDevice.CAMERA: return R.drawable.ic_camera;
            case HomeDevice.APPLIANCE: return R.drawable.ic_washer;
            case HomeDevice.SENSOR: {
                String s = d.state;
                if (s.contains("Открыто") || s.contains("Закрыто")) return R.drawable.ic_door;
                if (s.contains("Протечка") || s.contains("Сухо")) return R.drawable.ic_drop;
                if (s.contains("Дома") || s.contains("Никого")) return R.drawable.ic_home;
                return R.drawable.ic_thermo;
            }
            default: return R.drawable.ic_power;
        }
    }

    // ---------------------------------------------------------------- helpers

    private int px(float v) {
        return Math.round(v * dp);
    }

    private TextView text(int color, float sp) {
        TextView t = new TextView(getContext());
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp * dp);
        return g;
    }

    /** Equal-width tiles, as many per row as fit. */
    private static final class TileGrid extends ViewGroup {
        private final int minW, tileH, gap;
        private int cols = 1, cellW;

        TileGrid(Context c, int minW, int tileH, int gap) {
            super(c);
            this.minW = minW;
            this.tileH = tileH;
            this.gap = gap;
        }

        @Override
        protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            cols = Math.max(1, (width + gap) / (minW + gap));
            cellW = Math.max(0, (width - gap * (cols - 1)) / cols);
            int n = getChildCount();
            for (int i = 0; i < n; i++) {
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(tileH, MeasureSpec.EXACTLY));
            }
            int rows = (n + cols - 1) / cols;
            int height = rows == 0 ? 0 : rows * tileH + (rows - 1) * gap;
            setMeasuredDimension(width, resolveSize(height, h));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            for (int i = 0; i < getChildCount(); i++) {
                int x = (i % cols) * (cellW + gap), y = (i / cols) * (tileH + gap);
                getChildAt(i).layout(x, y, x + cellW, y + tileH);
            }
        }
    }
}

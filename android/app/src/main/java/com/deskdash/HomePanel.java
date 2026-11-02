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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Locale;
import java.util.Map;

/**
 * A "Умный дом" block, after Apple's Home app: wide tiles with a round icon, dark while off and light
 * with a coloured icon while on. The devices picked in the desk editor, or (setAuto) every device with
 * chips to pick a room. The header sums the block up (temperature at home, lights on) and turns
 * everything in it off with one tap.
 */
final class HomePanel extends LinearLayout {
    interface Listener {
        void onTap(HomeDevice d);

        void onLong(HomeDevice d);

        void onSetup();

        /** Camera / printer picture for a tile, null while there is none; may start loading it. */
        Bitmap image(HomeDevice d);

        /** "Выключить всё": the lights, plugs and fans of this block that are on. */
        void onAllOff(List<HomeDevice> on);
    }

    private static final Pattern DEGREES = Pattern.compile("(-?\\d+(?:[.,]\\d+)?)\\s*°");

    private static final int ALERT = 0xFFE5534B;

    private final DashTheme th;
    private final float dp;
    private List<String[]> items;
    private final Listener listener;
    private final TileGrid grid;
    private final TextView empty, summary, allOff;
    private final Map<String, Tile> tiles = new HashMap<>();
    private boolean auto;
    private String room = "";
    private LinearLayout rooms;
    private String roomsSig = "", idsSig = "";

    HomePanel(Context c, DashTheme th, String title, List<String[]> items, Listener l) {
        super(c);
        this.th = th;
        this.items = items;
        listener = l;
        dp = c.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);

        summary = new TextView(c);
        LinearLayout head = Ui.header(c, th, R.drawable.ic_home, th.isNight() ? th.accent : Ui.ORANGE,
                title.isEmpty() ? "Дом" : title, summary);
        allOff = Ui.pill(c, "Выключить всё", Ui.fill(th), th.accent, null);
        allOff.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        allOff.setPadding(px(12), px(5), px(12), px(5));
        allOff.setVisibility(GONE);
        head.addView(allOff);
        LayoutParams hl = new LayoutParams(-1, -2);
        hl.setMargins(0, 0, 0, px(10));
        addView(head, hl);

        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(OVER_SCROLL_NEVER);
        sv.setVerticalFadingEdgeEnabled(true);
        sv.setFadingEdgeLength(px(20));
        grid = new TileGrid(c, px(150), px(64), px(8));
        sv.addView(grid, new ScrollView.LayoutParams(-1, -2));
        addView(sv, new LayoutParams(-1, 0, 1));

        empty = text(th.dim, 15);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(px(16), px(14), px(16), px(14));
        empty.setBackground(rounded(Ui.fill(th), Ui.R_TILE));
        empty.setVisibility(GONE);
        addView(empty, new LayoutParams(-1, -2));

        setTiles(items);
    }

    /** Every device, grouped by room, with room chips above the tiles (the "Дом" section of the dock). */
    void setAuto() {
        auto = true;
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(getContext());
        hs.setHorizontalScrollBarEnabled(false);
        rooms = new LinearLayout(getContext());
        hs.addView(rooms);
        LayoutParams l = new LayoutParams(-1, -2);
        l.bottomMargin = px(8);
        addView(hs, 1, l);
    }

    View roomStrip() {
        return rooms == null ? null : (View) rooms.getParent();
    }

    private void setTiles(List<String[]> list) {
        items = list;
        grid.removeAllViews();
        tiles.clear();
        for (String[] it : list) {
            Tile t = new Tile(getContext(), it[0], it[1]);
            tiles.put(it[0], t);
            grid.addView(t.root);
        }
    }

    /** Auto mode: the devices of the chosen room, rooms in their own order, scenes last. */
    private void autoItems(Map<String, HomeDevice> devs) {
        Set<String> names = new LinkedHashSet<>();
        for (HomeDevice d : devs.values()) if (!d.room.isEmpty()) names.add(d.room);
        if (!names.contains(room)) room = "";
        String rsig = String.join("|", names) + "#" + room;
        if (!rsig.equals(roomsSig)) {
            roomsSig = rsig;
            rooms.removeAllViews();
            List<String> all = new ArrayList<>();
            all.add("");
            all.addAll(names);
            for (String r : all) {
                boolean sel = r.equals(room);
                TextView chip = text(sel ? (th.light ? 0xFFFFFFFF : 0xFF000000) : th.text, 14);
                chip.setTypeface(Ui.font(600));
                chip.setText(r.isEmpty() ? "Все" : r);
                chip.setSingleLine();
                chip.setPadding(px(14), px(6), px(14), px(6));
                chip.setBackground(rounded(sel ? th.text : Ui.fill(th), 100));
                chip.setOnClickListener(v -> {
                    room = r;
                    roomsSig = "";
                    render(devs, true, null);
                });
                LayoutParams cl = new LayoutParams(-2, -2);
                cl.rightMargin = px(6);
                rooms.addView(chip, cl);
            }
            ((View) rooms.getParent()).setVisibility(names.isEmpty() ? GONE : VISIBLE);
        }
        List<HomeDevice> list = new ArrayList<>();
        for (HomeDevice d : devs.values()) if (room.isEmpty() || room.equals(d.room)) list.add(d);
        List<String> order = new ArrayList<>(names);
        list.sort((a, b) -> {
            int sa = a.kind == HomeDevice.SCENE ? 1 : 0, sb = b.kind == HomeDevice.SCENE ? 1 : 0;
            if (sa != sb) return sa - sb;
            int ra = order.indexOf(a.room), rb = order.indexOf(b.room);
            if (ra != rb) return (ra < 0 ? 999 : ra) - (rb < 0 ? 999 : rb);
            return a.name.compareToIgnoreCase(b.name);
        });
        StringBuilder sig = new StringBuilder();
        List<String[]> next = new ArrayList<>();
        for (HomeDevice d : list) {
            sig.append(d.id).append('|');
            next.add(new String[]{d.id, d.name});
        }
        if (!sig.toString().equals(idsSig)) {
            idsSig = sig.toString();
            setTiles(next);
        }
    }

    /** "22,5° дома · свет: 2" and the all-off button. */
    private void renderSummary(Map<String, HomeDevice> devs) {
        List<HomeDevice> on = new ArrayList<>();
        double sum = 0;
        int temps = 0, lights = 0;
        for (String[] it : items) {
            HomeDevice d = devs.get(it[0]);
            if (d == null || !d.available) continue;
            if (d.active() && (d.kind == HomeDevice.LIGHT || d.kind == HomeDevice.SWITCH || d.kind == HomeDevice.FAN)) {
                on.add(d);
                if (d.kind == HomeDevice.LIGHT) lights++;
            }
            double v = Double.NaN;
            if (d.kind == HomeDevice.CLIMATE) v = d.current;
            else if (d.kind == HomeDevice.SENSOR) {
                Matcher m = DEGREES.matcher(d.state);
                if (m.find()) v = Double.parseDouble(m.group(1).replace(',', '.'));
            }
            if (!Double.isNaN(v)) {
                sum += v;
                temps++;
            }
        }
        StringBuilder sb = new StringBuilder();
        if (temps > 0) sb.append(HomeDevice.deg(Math.round(sum / temps * 10) / 10.0)).append(" дома");
        if (lights > 0) sb.append(sb.length() > 0 ? " · " : "").append("свет: ").append(lights);
        summary.setText(sb);
        allOff.setVisibility(on.isEmpty() ? GONE : VISIBLE);
        allOff.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            listener.onAllOff(on);
        });
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
        if (auto) autoItems(devs);
        renderSummary(devs);
        if (items.isEmpty()) {
            if (auto) showEmpty(error != null ? error : "Устройств пока нет");
            else showEmpty("Нажми, чтобы выбрать устройства");
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
        final FrameLayout bubble;
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
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.R_TILE * dp);
                }
            });
            root.setClipToOutline(true);
            pic = new ImageView(c);
            pic.setScaleType(ImageView.ScaleType.CENTER_CROP);
            pic.setVisibility(GONE);
            root.addView(pic, new FrameLayout.LayoutParams(-1, -1));
            scrim = new View(c);
            scrim.setBackground(new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xB0000000, 0x30000000}));
            scrim.setVisibility(GONE);
            root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
            box = new LinearLayout(c);
            box.setGravity(Gravity.CENTER_VERTICAL);
            box.setPadding(px(10), 0, px(10), 0);
            root.addView(box, new FrameLayout.LayoutParams(-1, -1));
            // the round icon on the left, as in Apple's Home
            bubble = new FrameLayout(c);
            icon = new ImageView(c);
            bubble.addView(icon, new FrameLayout.LayoutParams(px(19), px(19), Gravity.CENTER));
            box.addView(bubble, new LayoutParams(px(38), px(38)));
            LinearLayout words = new LinearLayout(c);
            words.setOrientation(VERTICAL);
            name = text(th.text, 14);
            name.setTypeface(Ui.font(600));
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setText(savedName.isEmpty() ? id : savedName);
            words.addView(name);
            status = text(th.dim, 12);
            status.setSingleLine();
            status.setEllipsize(TextUtils.TruncateAt.END);
            status.setText("…");
            words.addView(status);
            bar = new Progress(c);
            bar.setVisibility(GONE);
            LayoutParams bl = new LayoutParams(-1, px(3));
            bl.topMargin = px(4);
            words.addView(bar, bl);
            LayoutParams wl = new LayoutParams(0, -2, 1);
            wl.leftMargin = px(10);
            box.addView(words, wl);
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
            int kc = th.isNight() ? th.accent : kindColor(d);
            int bg;
            if (alert) bg = th.isNight() ? DashTheme.blend(th.surface, th.accent, 0.4f) : Ui.RED;
            else if (lit) bg = th.isNight() ? DashTheme.blend(th.surface, th.accent, 0.3f)
                    : th.light ? DashTheme.blend(0xFFFFFFFF, kc, 0.22f) : 0xFFF2F2F7;
            else bg = Ui.fill(th);
            root.setBackground(rounded(bg, Ui.R_TILE));
            root.setAlpha(d == null || !d.available ? 0.45f : 1f);
            Bitmap img = d != null && !d.image.isEmpty() ? listener.image(d) : null;
            boolean photo = img != null;
            pic.setVisibility(photo ? VISIBLE : GONE);
            scrim.setVisibility(photo ? VISIBLE : GONE);
            if (photo) pic.setImageBitmap(img);
            int fg = photo || alert ? 0xFFFFFFFF : lit && !th.isNight() ? 0xFF000000 : th.text;
            icon.setImageResource(iconFor(d));
            GradientDrawable ring = new GradientDrawable();
            ring.setShape(GradientDrawable.OVAL);
            if (photo || alert) ring.setColor(0x40FFFFFF);
            else if (lit) ring.setColor(kc);
            else ring.setColor(DashTheme.blend(Ui.fill(th), th.text, th.light ? 0.08f : 0.12f));
            bubble.setBackground(ring);
            int ic = photo || alert ? 0xFFFFFFFF : lit ? DashTheme.onColor(kc) : th.dim;
            icon.setImageTintList(ColorStateList.valueOf(ic));
            if (d != null) {
                name.setText(d.name);
                status.setText(d.status());
            } else {
                status.setText("…");
            }
            name.setTextColor(fg);
            status.setTextColor(photo || alert ? 0xD9FFFFFF : lit && !th.isNight() ? 0x99000000 : th.dim);
            boolean job = d != null && d.progress >= 0 && d.on;
            bar.setVisibility(job ? VISIBLE : GONE);
            if (job) bar.set(d.progress / 100f, photo ? 0x55FFFFFF : DashTheme.blend(bg, fg, 0.15f), photo ? 0xFFFFFFFF : kc);
        }
    }

    /** The colour of a kind of device when it is on: yellow lamps, orange heat, blue blinds… */
    private static int kindColor(HomeDevice d) {
        if (d == null) return Ui.ORANGE;
        switch (d.kind) {
            case HomeDevice.LIGHT: return Ui.YELLOW;
            case HomeDevice.CLIMATE: return Ui.ORANGE;
            case HomeDevice.COVER: return Ui.CYAN;
            case HomeDevice.LOCK: return Ui.TEAL;
            case HomeDevice.SCENE: return Ui.INDIGO;
            case HomeDevice.FAN: return Ui.MINT;
            case HomeDevice.MEDIA: return Ui.PINK;
            case HomeDevice.PRINTER: return Ui.GREEN;
            case HomeDevice.VACUUM: return Ui.TEAL;
            case HomeDevice.APPLIANCE: return Ui.BLUE;
            default: return Ui.GREEN;
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

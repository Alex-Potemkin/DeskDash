package com.deskdash;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * First start: pick a ready-made dashboard (with a live preview), a look (the same preview in every
 * theme) and connect what's around — PC, hub, calendar, music, 3D printer. Three short steps, each
 * can be skipped; all of it can be changed later in the settings.
 */
public class WelcomeActivity extends Activity {
    private Prefs prefs;
    private float dp;
    private int step;
    private Presets.Preset preset = Presets.ALL[0];
    private String theme = preset.theme, clock = preset.clock;
    private DashTheme th;
    private FrameLayout root;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        dp = getResources().getDisplayMetrics().density;
        root = new FrameLayout(this);
        setContentView(root);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (step == 2) render(); // back from a permission screen: show the new state
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        render();
    }

    @Override
    public void onBackPressed() {
        if (step > 0) {
            step--;
            render();
        } else {
            super.onBackPressed();
        }
    }

    // ---------------------------------------------------------------- steps

    private void render() {
        th = DashTheme.byId(theme);
        root.removeAllViews();
        root.setBackgroundColor(th.bg);
        getWindow().setStatusBarColor(th.bg);
        getWindow().setNavigationBarColor(th.bg);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(px(28), px(14), px(28), px(10));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < 3; i++) {
            View d = new View(this);
            d.setBackground(round(i == step ? th.accent : th.line, 4));
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(px(i == step ? 22 : 8), px(8));
            l.rightMargin = px(6);
            head.addView(d, l);
        }
        View sp = new View(this);
        head.addView(sp, new LinearLayout.LayoutParams(0, 1, 1));
        TextView skip = text(th.dim, 14);
        skip.setText(step == 2 ? "" : "Пропустить");
        skip.setPadding(px(10), px(6), 0, px(6));
        skip.setOnClickListener(v -> finishWelcome());
        head.addView(skip);
        page.addView(head);

        TextView title = text(th.text, 24);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        TextView sub = text(th.dim, 14);
        sub.setLineSpacing(0, 1.15f);
        page.addView(title, lp(8, 2));
        page.addView(sub, lp(0, 10));

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        sv.addView(body);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        switch (step) {
            case 0:
                title.setText("Привет! Каким будет стол?");
                sub.setText("Выбери готовый вариант — потом любой блок можно передвинуть, убрать или добавить");
                presets(body);
                break;
            case 1:
                title.setText("Как он будет выглядеть");
                sub.setText("Тема и часы. На столе тема меняется двойным тапом по часам, стиль часов — свайпом по ним");
                looks(body);
                break;
            default:
                title.setText("Что подключить");
                sub.setText("Всё необязательно и есть в Настройках. Компьютер нужен для созвонов, статистики и экрана ПК на телефоне");
                connections(body);
                break;
        }

        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        if (step > 0) {
            TextView back = text(th.dim, 15);
            back.setText("Назад");
            back.setPadding(px(4), px(12), px(20), px(12));
            back.setOnClickListener(v -> {
                step--;
                render();
            });
            nav.addView(back);
        }
        nav.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        TextView next = text(DashTheme.onColor(th.accent), 16);
        next.setText(step == 2 ? "Готово" : "Дальше");
        next.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        next.setPadding(px(28), px(12), px(28), px(12));
        next.setBackground(round(th.accent, 24));
        next.setOnClickListener(v -> {
            if (step == 2) {
                finishWelcome();
            } else {
                step++;
                render();
            }
        });
        nav.addView(next);
        page.addView(nav, lp(10, 0));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
    }

    private void presets(LinearLayout body) {
        LinearLayout row = new LinearLayout(this);
        for (Presets.Preset p : Presets.ALL) {
            boolean sel = p == preset;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(px(10), px(10), px(10), px(12));
            GradientDrawable bg = round(sel ? DashTheme.blend(th.surface, th.accent, 0.18f) : th.surface, 18);
            if (sel) bg.setStroke(px(2), th.accent);
            card.setBackground(bg);
            Presets.Preview pv = new Presets.Preview(this);
            pv.set(p, DashTheme.byId(p.theme));
            card.addView(pv, new LinearLayout.LayoutParams(px(220), px(96)));
            LinearLayout name = new LinearLayout(this);
            name.setGravity(Gravity.CENTER_VERTICAL);
            ImageView ic = new ImageView(this);
            ic.setImageResource(p.icon);
            ic.setImageTintList(ColorStateList.valueOf(sel ? th.accent : th.dim));
            name.addView(ic, new LinearLayout.LayoutParams(px(18), px(18)));
            TextView n = text(th.text, 16);
            n.setText(p.name);
            n.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            n.setPadding(px(8), 0, 0, 0);
            name.addView(n);
            card.addView(name, lp(10, 2));
            TextView about = text(th.dim, 12);
            about.setText(p.about);
            about.setMaxLines(3);
            about.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(about, new LinearLayout.LayoutParams(px(220), -2));
            card.setOnClickListener(v -> {
                preset = p;
                theme = p.theme;
                clock = p.clock;
                render();
            });
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
            l.rightMargin = px(12);
            row.addView(card, l);
        }
        body.addView(scroll(row));
    }

    private void looks(LinearLayout body) {
        LinearLayout row = new LinearLayout(this);
        for (DashTheme t : DashTheme.ALL) {
            if (t.id.equals("night")) continue;
            boolean sel = t.id.equals(theme);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(px(8), px(8), px(8), px(10));
            GradientDrawable bg = round(t.surface, 16);
            bg.setStroke(px(sel ? 2 : 1), sel ? th.accent : th.line);
            card.setBackground(bg);
            Presets.Preview pv = new Presets.Preview(this);
            pv.set(preset, t);
            card.addView(pv, new LinearLayout.LayoutParams(px(150), px(72)));
            TextView n = text(t.text, 14);
            n.setText(t.name);
            n.setGravity(Gravity.CENTER);
            card.addView(n, lp(8, 0));
            card.setOnClickListener(v -> {
                theme = t.id;
                render();
            });
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
            l.rightMargin = px(10);
            row.addView(card, l);
        }
        body.addView(scroll(row));

        TextView cap = text(th.dim, 13);
        cap.setText("ЧАСЫ");
        cap.setLetterSpacing(0.14f);
        body.addView(cap, lp(12, 6));
        LinearLayout clocks = new LinearLayout(this);
        for (String[] c : FancyClock.STYLES) {
            boolean sel = c[0].equals(clock);
            TextView chip = text(sel ? DashTheme.onColor(th.accent) : th.text, 14);
            chip.setText(c[1]);
            chip.setPadding(px(16), px(9), px(16), px(9));
            chip.setBackground(round(sel ? th.accent : th.surface, 18));
            chip.setOnClickListener(v -> {
                clock = c[0];
                render();
            });
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
            l.rightMargin = px(8);
            clocks.addView(chip, l);
        }
        body.addView(scroll(clocks));
    }

    private void connections(LinearLayout body) {
        boolean pcSet = !prefs.pcHost().isEmpty(), hubSet = HubClient.configured(prefs);
        connRow(body, R.drawable.ic_desktop, "Компьютер", pcSet ? "Подключён: " + prefs.pcHost()
                        : "Запусти DeskDash.exe на ПК: макросы, созвоны, статистика, экран на телефоне",
                pcSet ? "Заново" : "Найти", v -> Setup.findPc(this, prefs, this::render));
        connRow(body, R.drawable.ic_calendar, "Календарь", CalendarRepo.hasPermission(this) ? "Доступ есть" : "Встречи, напоминания и ссылки на созвоны",
                CalendarRepo.hasPermission(this) ? "" : "Разрешить",
                v -> requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, 1));
        connRow(body, R.drawable.ic_music, "Музыка", MusicWatcher.hasAccess(this) ? "Доступ есть" : "Что играет в Spotify и других плеерах",
                MusicWatcher.hasAccess(this) ? "" : "Разрешить",
                v -> open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        connRow(body, R.drawable.ic_home, "Хаб Raspberry Pi", hubSet ? "Подключён: " + prefs.hubUrl()
                        : "Умный дом и ПК через хаб, если он есть",
                hubSet ? "Заново" : "Найти", v -> Setup.findHub(this, prefs, this::render));
        int printers = Bambu.load(prefs).size();
        connRow(body, R.drawable.ic_printer, "3D-принтер Bambu Lab", printers > 0 ? "Добавлено: " + printers
                        : "Прогресс печати, камера и пауза прямо с дашборда",
                printers > 0 ? "Ещё" : "Найти", v -> Setup.findPrinter(this, prefs, this::render));
        TextView n = text(th.dim, 13);
        n.setText("Home Assistant и Яндекс подключаются в Настройках → «Умный дом»");
        body.addView(n, lp(8, 0));
    }

    private void connRow(LinearLayout body, int icon, String title, String sub, String action, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(px(14), px(10), px(12), px(10));
        r.setBackground(round(th.surface, 16));
        ImageView ic = new ImageView(this);
        ic.setImageResource(icon);
        ic.setImageTintList(ColorStateList.valueOf(th.accent));
        r.addView(ic, new LinearLayout.LayoutParams(px(24), px(24)));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        TextView a = text(th.text, 15);
        a.setText(title);
        t.addView(a);
        TextView b = text(th.dim, 12);
        b.setText(sub);
        t.addView(b);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1);
        tl.setMargins(px(14), 0, px(10), 0);
        r.addView(t, tl);
        if (!action.isEmpty()) {
            TextView btn = text(th.text, 14);
            btn.setText(action);
            btn.setPadding(px(16), px(8), px(16), px(8));
            btn.setBackground(round(th.bg, 16));
            btn.setOnClickListener(l);
            r.addView(btn);
        } else {
            TextView ok = text(th.accent, 18);
            ok.setText("✓");
            r.addView(ok);
        }
        body.addView(r, lp(0, 8));
    }

    private void finishWelcome() {
        Presets.apply(prefs, preset, false);
        prefs.setTheme(theme);
        prefs.setClockStyle(clock);
        Presets.setWelcomed(this);
        finish();
    }

    // ---------------------------------------------------------------- helpers

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            android.widget.Toast.makeText(this, "Экран настроек недоступен на этой прошивке", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private HorizontalScrollView scroll(View v) {
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.setClipToPadding(false);
        h.addView(v);
        return h;
    }

    private TextView text(int color, float sp) {
        TextView t = new TextView(this);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private GradientDrawable round(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r * dp);
        return g;
    }

    private LinearLayout.LayoutParams lp(float top, float bottom) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
        l.setMargins(0, px(top), 0, px(bottom));
        return l;
    }

    private int px(float v) {
        return Math.round(v * dp);
    }
}

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

import java.util.ArrayList;
import java.util.List;

/**
 * First start: pick a ready-made dashboard (with a live preview), a look (five ways to lay the screen
 * out, the theme and the clock), the weather and the night, then connect what's around — "find
 * everything" looks for the PC, the hub, Home Assistant and printers at once. Every step can be skipped;
 * all of it can be changed later in the settings. After an update, or from the settings, the same
 * wizard runs as a tour without the first step, so the desks stay as they are. Laid out like the iPhone's
 * setup: a coloured icon, a large centred title, the choices, and one big "Продолжить" button.
 */
public class WelcomeActivity extends Activity {
    static final String EXTRA_TOUR = "tour";

    private Prefs prefs;
    private float dp;
    private int step;
    private boolean tour, located;
    private String locateMsg = "";
    private final List<String> steps = new ArrayList<>();
    private Presets.Preset preset = Presets.ALL[0];
    private String theme = preset.theme, clock = preset.clock, style = "classic";
    private DashTheme th;
    private FrameLayout root;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        dp = getResources().getDisplayMetrics().density;
        tour = getIntent().getBooleanExtra(EXTRA_TOUR, false);
        if (!tour) steps.add("preset");
        steps.add("look");
        steps.add("night");
        steps.add("connect");
        if (tour) {
            theme = prefs.theme();
            clock = prefs.clockStyle();
            style = prefs.uiStyle();
        }
        root = new FrameLayout(this);
        setContentView(root);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (step > 0) render(); // back from a permission or sign-in screen: show the new state
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
        } else if (tour) {
            finishWelcome(); // the tour is offered once: closing it keeps everything as it is
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
        boolean last = step == steps.size() - 1;

        if (th.light) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        else getWindow().getDecorView().setSystemUiVisibility(0);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(px(24), px(10), px(24), px(14));

        // "‹ Назад" on the left, "Пропустить" on the right, both in the accent colour
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text(th.accent, 17);
        back.setText(step > 0 ? "‹ Назад" : "");
        back.setPadding(0, px(8), px(16), px(8));
        back.setOnClickListener(v -> {
            if (step == 0) return;
            step--;
            render();
        });
        head.addView(back);
        head.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        TextView skip = text(th.accent, 17);
        skip.setText(last ? "" : tour ? "Закрыть" : "Пропустить");
        skip.setPadding(px(16), px(8), 0, px(8));
        skip.setOnClickListener(v -> finishWelcome());
        head.addView(skip);
        page.addView(head);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout center = new FrameLayout(this);
        int colW = Math.min(getResources().getDisplayMetrics().widthPixels - px(48), px(820));
        center.addView(body, new FrameLayout.LayoutParams(colW, -2, Gravity.CENTER_HORIZONTAL));
        sv.addView(center);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        ImageView badge = new ImageView(this);
        int[][] icons = {{R.drawable.ic_dashboard, Ui.BLUE}, {R.drawable.ic_palette, Ui.INDIGO},
                {R.drawable.ic_night, Ui.PURPLE}, {R.drawable.ic_devices, Ui.GREEN}};
        String stepId = steps.get(step);
        int[] ic = icons["preset".equals(stepId) ? 0 : "look".equals(stepId) ? 1 : "night".equals(stepId) ? 2 : 3];
        badge.setImageResource(ic[0]);
        badge.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        badge.setPadding(px(9), px(9), px(9), px(9));
        badge.setBackground(round(ic[1], 12));
        body.addView(badge, new LinearLayout.LayoutParams(px(44), px(44)));
        TextView title = text(th.text, 27);
        title.setTypeface(Ui.font(700));
        title.setGravity(Gravity.CENTER);
        TextView sub = text(th.dim, 15);
        sub.setGravity(Gravity.CENTER);
        sub.setLineSpacing(0, 1.1f);
        body.addView(title, lp(8, 2));
        body.addView(sub, lp(0, 14));

        switch (steps.get(step)) {
            case "preset":
                title.setText("Привет! Каким будет стол?");
                sub.setText("Выбери готовый вариант — потом любой блок можно передвинуть, убрать или добавить");
                presets(body);
                break;
            case "look":
                title.setText(tour ? "Новое: вид экрана, погода, ночь" : "Как он будет выглядеть");
                sub.setText("Вид экрана, тема и часы. На столе тема меняется двойным тапом по часам, стиль часов — свайпом по ним");
                looks(body);
                break;
            case "night":
                title.setText("Погода и ночь");
                sub.setText("Погода под часами и отдельным блоком. Ночью экран гаснет до тусклых часов, тап по ним показывает стол");
                weatherAndNight(body);
                break;
            default:
                title.setText("Что подключить");
                sub.setText("Всё необязательно и есть в Настройках. Созвоны работают и без компьютера: из календаря, по ссылке и на телефоне");
                connections(body);
                break;
        }

        // one big button at the bottom, as in the iPhone's setup
        TextView next = text(DashTheme.onColor(th.accent), 17);
        next.setText(last ? "Готово" : "Продолжить");
        next.setTypeface(Ui.font(600));
        next.setGravity(Gravity.CENTER);
        next.setBackground(round(th.accent, Ui.R_BUTTON));
        next.setOnClickListener(v -> {
            if (last) {
                finishWelcome();
            } else {
                step++;
                render();
            }
        });
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(Math.min(colW, px(360)), px(50));
        nl.gravity = Gravity.CENTER_HORIZONTAL;
        nl.topMargin = px(10);
        page.addView(next, nl);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
    }

    private void presets(LinearLayout body) {
        LinearLayout row = new LinearLayout(this);
        for (Presets.Preset p : Presets.ALL) {
            boolean sel = p == preset;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(px(10), px(10), px(10), px(12));
            GradientDrawable bg = round(th.surface, 18);
            if (sel) bg.setStroke(px(2.5f), th.accent);
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
            n.setTypeface(Ui.font(600));
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
        body.addView(caption("ВИД ЭКРАНА"), lp(0, 6));
        LinearLayout styles = new LinearLayout(this);
        for (String[] l : DashboardView.LOOKS) {
            boolean sel = l[0].equals(style);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(px(8), px(8), px(8), px(10));
            GradientDrawable bg = round(th.surface, 16);
            if (sel) bg.setStroke(px(2.5f), th.accent);
            card.setBackground(bg);
            Presets.LookPreview pv = new Presets.LookPreview(this);
            pv.set(l[0], th);
            card.addView(pv, new LinearLayout.LayoutParams(px(150), px(84)));
            TextView n = text(sel ? th.accent : th.text, 15);
            n.setTypeface(Ui.font(sel ? 600 : 400));
            n.setText(l[1]);
            n.setGravity(Gravity.CENTER);
            card.addView(n, lp(6, 0));
            card.setOnClickListener(v -> {
                style = l[0];
                render();
            });
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-2, -2);
            ll.rightMargin = px(10);
            styles.addView(card, ll);
        }
        body.addView(scroll(styles));
        TextView about = text(th.dim, 14);
        about.setGravity(Gravity.CENTER);
        about.setText(SettingsActivity.lookAbout(style));
        body.addView(about, lp(6, 0));

        body.addView(caption("ТЕМА"), lp(14, 6));
        LinearLayout row = new LinearLayout(this);
        for (DashTheme t : DashTheme.ALL) {
            if (t.isNight()) continue;
            boolean sel = t.id.equals(theme);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(px(8), px(8), px(8), px(10));
            GradientDrawable bg = round(t.bg, 16);
            bg.setStroke(px(sel ? 2.5f : 1), sel ? th.accent : th.line);
            card.setBackground(bg);
            Presets.LookPreview pv = new Presets.LookPreview(this);
            pv.set(style, t);
            card.addView(pv, new LinearLayout.LayoutParams(px(120), px(64)));
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

        body.addView(caption("ЧАСЫ"), lp(12, 6));
        List<String[]> cs = new ArrayList<>();
        for (String[] c : FancyClock.STYLES) cs.add(c);
        body.addView(chips(cs.toArray(new String[0][]), clock, id -> clock = id));
    }

    private void weatherAndNight(LinearLayout body) {
        if (!Weather.configured(prefs) && !located) {
            located = true;
            locateMsg = "Определяю город…";
            new Thread(() -> {
                try {
                    String[] r = Weather.locate();
                    Weather.setPlace(prefs, r[0], Double.parseDouble(r[1]), Double.parseDouble(r[2]));
                    locateMsg = "";
                } catch (Exception e) {
                    locateMsg = "Город не определился: выбери его сам";
                }
                runOnUiThread(this::render);
            }).start();
        }
        boolean wx = Weather.configured(prefs);
        connRow(body, R.drawable.ic_weather, "Погода", wx ? Weather.place(prefs) + " · Open-Meteo, без ключей" : locateMsg,
                wx ? "Другой город" : "Выбрать", v -> SettingsActivity.searchCity(this, prefs, this::render));

        body.addView(caption("КОГДА НОЧЬ"), lp(14, 6));
        String trig = prefs.nightMode() ? prefs.nightTrigger() : "off";
        body.addView(chips(new String[][]{{"time", "По часам, 23–7"}, {"sun", "От заката до рассвета"},
                {"light", "Когда в комнате темно"}, {"off", "Не нужна"}}, trig, id -> {
            if ("off".equals(id)) {
                prefs.raw().edit().putBoolean("night_mode", false).apply();
            } else {
                prefs.raw().edit().putBoolean("night_mode", true).putString("night_trigger", id).apply();
            }
        }));
        if (prefs.nightMode()) {
            body.addView(caption("КАК ВЫГЛЯДИТ"), lp(12, 6));
            body.addView(chips(new String[][]{{"clock", "Только часы"}, {"desk", "Стол в ночных цветах"}, {"dim", "Свой стол, тише"}},
                    prefs.nightLook(), id -> prefs.raw().edit().putString("night_look", id).apply()));
            body.addView(caption("ЦВЕТ"), lp(12, 6));
            body.addView(chips(new String[][]{{"red", "Красный"}, {"amber", "Янтарный"}, {"gray", "Серый"}},
                    prefs.nightTint(), id -> prefs.raw().edit().putString("night_tint", id).apply()));
            TextView n = text(th.dim, 12);
            n.setText("Время, яркость, плавное затемнение и выключение света на ночь — в Настройках → «Экран и ночь»");
            body.addView(n, lp(8, 0));
        }
    }

    private void connections(LinearLayout body) {
        TextView all = text(th.accent, 17);
        all.setText("Найти всё в сети: компьютер, хаб, Home Assistant, принтер");
        all.setTypeface(Ui.font(600));
        all.setGravity(Gravity.CENTER);
        all.setPadding(px(16), px(13), px(16), px(13));
        all.setBackground(round(th.surface, Ui.R_GROUP));
        all.setOnClickListener(v -> Setup.findAll(this, prefs, this::render));
        body.addView(all, lp(0, 10));

        boolean pcSet = !prefs.pcHost().isEmpty(), hubSet = HubClient.configured(prefs);
        connRow(body, R.drawable.ic_desktop, "Компьютер", pcSet ? "Подключён: " + prefs.pcHost()
                        : "Запусти DeskDash.exe на ПК: макросы, звонки на ПК, статистика, экран на телефоне",
                pcSet ? "Заново" : "Найти", v -> Setup.findPc(this, prefs, this::render));
        connRow(body, R.drawable.ic_calendar, "Календарь", CalendarRepo.hasPermission(this) ? "Доступ есть" : "Встречи, напоминания и ссылки на созвоны",
                CalendarRepo.hasPermission(this) ? "" : "Разрешить",
                v -> requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, 1));
        connRow(body, R.drawable.ic_music, "Музыка и звонки на телефоне", MusicWatcher.hasAccess(this) ? "Доступ есть"
                        : "Что играет, и звонки Telegram, WhatsApp, Zoom с кнопками на дашборде",
                MusicWatcher.hasAccess(this) ? "" : "Разрешить",
                v -> open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        connRow(body, R.drawable.ic_home, "Хаб Raspberry Pi", hubSet ? "Подключён: " + prefs.hubUrl()
                        : "Умный дом и ПК через хаб, если он есть",
                hubSet ? "Заново" : "Найти", v -> Setup.findHub(this, prefs, this::render));
        boolean ha = HomeAssistant.configured(prefs);
        connRow(body, R.drawable.ic_bulb, "Home Assistant", ha ? "Подключён: " + prefs.haUrl() : "Найдём его в сети и откроем вход",
                ha ? "Заново" : "Найти", v -> Setup.findHa(this, prefs));
        boolean ya = YandexHome.configured(prefs);
        connRow(body, R.drawable.ic_speaker, "Яндекс Умный дом", ya ? "Вход выполнен" : "Свет, розетки и сценарии из приложения «Дом с Алисой»",
                ya ? "" : "Настроить", v -> startActivity(new Intent(this, SettingsActivity.class).putExtra(SettingsActivity.EXTRA_PAGE, "home")));
        int printers = Bambu.load(prefs).size();
        connRow(body, R.drawable.ic_printer, "3D-принтер Bambu Lab", printers > 0 ? "Добавлено: " + printers
                        : "Прогресс печати, камера и пауза прямо с дашборда",
                printers > 0 ? "Ещё" : "Найти", v -> Setup.findPrinter(this, prefs, this::render));
    }

    /** A row of the iOS-style group of connections: a coloured icon square, two lines, an action or a tick. */
    private void connRow(LinearLayout body, int icon, String title, String sub, String action, View.OnClickListener l) {
        LinearLayout g = null;
        View last = body.getChildCount() == 0 ? null : body.getChildAt(body.getChildCount() - 1);
        if (last instanceof LinearLayout && "group".equals(last.getTag())) {
            g = (LinearLayout) last;
            View line = new View(this);
            line.setBackgroundColor(th.line);
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, Math.max(1, (int) (dp * 0.6f)));
            ll.leftMargin = px(58);
            g.addView(line, ll);
        } else {
            g = new LinearLayout(this);
            g.setOrientation(LinearLayout.VERTICAL);
            g.setTag("group");
            g.setBackground(round(th.surface, Ui.R_GROUP));
            body.addView(g, lp(0, 8));
        }
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(px(14), px(10), px(14), px(10));
        ImageView ic = new ImageView(this);
        ic.setImageResource(icon);
        ic.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        ic.setPadding(px(5), px(5), px(5), px(5));
        ic.setBackground(round(iconColor(icon), 8));
        r.addView(ic, new LinearLayout.LayoutParams(px(30), px(30)));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        TextView a = text(th.text, 17);
        a.setText(title);
        t.addView(a);
        TextView b = text(th.dim, 13);
        b.setText(sub);
        t.addView(b);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1);
        tl.setMargins(px(14), 0, px(10), 0);
        r.addView(t, tl);
        if (!action.isEmpty()) {
            TextView btn = text(th.accent, 15);
            btn.setTypeface(Ui.font(600));
            btn.setText(action);
            btn.setPadding(px(14), px(6), px(14), px(6));
            btn.setBackground(round(Ui.fill(th), 100));
            btn.setOnClickListener(l);
            r.addView(btn);
        } else {
            TextView ok = text(Ui.GREEN, 20);
            ok.setTypeface(Ui.font(700));
            ok.setText("✓");
            r.addView(ok);
        }
        g.addView(r, new LinearLayout.LayoutParams(-1, -2));
    }

    private static int iconColor(int icon) {
        if (icon == R.drawable.ic_desktop) return Ui.GRAY;
        if (icon == R.drawable.ic_calendar) return Ui.RED;
        if (icon == R.drawable.ic_music) return Ui.PINK;
        if (icon == R.drawable.ic_home) return Ui.ORANGE;
        if (icon == R.drawable.ic_bulb) return Ui.TEAL;
        if (icon == R.drawable.ic_speaker) return Ui.PURPLE;
        if (icon == R.drawable.ic_printer) return Ui.BROWN;
        if (icon == R.drawable.ic_weather) return Ui.BLUE;
        return Ui.INDIGO;
    }

    private void finishWelcome() {
        if (!tour) Presets.apply(prefs, preset, false);
        prefs.setTheme(theme);
        prefs.setClockStyle(clock);
        prefs.setUiStyle(style);
        Presets.setWelcomed(this);
        finish();
    }

    // ---------------------------------------------------------------- helpers

    private interface Picked {
        void on(String id);
    }

    /** One-of choice: {id, label} chips; picking one re-renders the step. */
    private View chips(String[][] options, String cur, Picked p) {
        LinearLayout row = new LinearLayout(this);
        for (String[] o : options) {
            boolean sel = o[0].equals(cur);
            TextView chip = text(sel ? DashTheme.onColor(th.accent) : th.text, 15);
            chip.setTypeface(Ui.font(sel ? 600 : 400));
            chip.setText(o[1]);
            chip.setPadding(px(16), px(9), px(16), px(9));
            chip.setBackground(round(sel ? th.accent : Ui.fill(th), 100));
            chip.setOnClickListener(v -> {
                p.on(o[0]);
                render();
            });
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
            l.rightMargin = px(8);
            row.addView(chip, l);
        }
        return scroll(row);
    }

    private TextView caption(String s) {
        TextView cap = text(th.dim, 13);
        cap.setText(s);
        cap.setPadding(px(16), 0, 0, 0);
        return cap;
    }

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

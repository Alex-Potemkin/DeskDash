package com.deskdash;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings of the active profile, laid out like iOS Settings: a large title, grouped rounded lists with
 * coloured icon squares, iOS switches, explanations under the groups. The first page is a map: search,
 * the profile, and sections with what is set up in each of them right now; a section opens its own page.
 * Colours follow the profile's theme, light or dark.
 */
public class SettingsActivity extends Activity {
    static final String EXTRA_PAGE = "page";
    /** The profile's theme, set in onCreate. */
    private DashTheme th;
    private int ACCENT, TEXT, DIM, SURFACE, BG, LINE;

    /** Colour of each section's icon square, as in iOS Settings. */
    private static int iconColor(String id) {
        switch (id) {
            case "look": return Ui.BLUE;
            case "desks": return Ui.INDIGO;
            case "screen": return Ui.PURPLE;
            case "weather": return Ui.TEAL;
            case "calendar": return Ui.RED;
            case "calls": return Ui.GREEN;
            case "music": return Ui.PINK;
            case "stats": return Ui.ORANGE;
            case "pc": return Ui.GRAY;
            case "home": return Ui.ORANGE;
            default: return Ui.BROWN;
        }
    }

    /** id, title, icon, words to search for. */
    private static final Object[][] PAGES = {
            {"look", "Вид и часы", R.drawable.ic_palette, "вид интерфейс плитки фокус док лента колонки тема цвет часы стиль шрифт секунды 24 формат обложка акцент google цвета событий"},
            {"desks", "Столы и готовые варианты", R.drawable.ic_dashboard, "столы блоки колонки варианты пресеты шаблоны рабочий умный дом минимализм"},
            {"screen", "Экран и ночь", R.drawable.ic_night, "ночь ночной режим закат рассвет датчик света темно часы будильник яркость выгорание заставка живая строка сверху"},
            {"weather", "Погода", R.drawable.ic_weather, "погода город температура дождь снег прогноз ветер"},
            {"calendar", "Календарь", R.drawable.ic_calendar, "календарь google ics ссылки события доступ"},
            {"calls", "Созвоны", R.drawable.ic_camera, "созвоны звонки zoom meet teams телемост discord telegram whatsapp телефон ссылка буфер напоминание звук подключиться экран вебка камера"},
            {"music", "Музыка", R.drawable.ic_music, "музыка spotify уведомления плеер"},
            {"stats", "Статистика дня", R.drawable.ic_chart, "статистика день время за компьютером приложения неделя"},
            {"pc", "Компьютер", R.drawable.ic_desktop, "компьютер пк ip токен макросы deskdash.exe найти разбудить wake"},
            {"home", "Умный дом", R.drawable.ic_home, "умный дом home assistant яндекс хаб raspberry pi свет устройства автоматизации рассвет созвон выключить всё"},
            {"printer", "3D-принтер", R.drawable.ic_printer, "3d принтер bambu lab печать код доступа камера octoprint klipper"},
    };

    private Prefs prefs;
    private SharedPreferences sp;
    private LinearLayout list;
    private float dp;
    private String page;
    private final List<Runnable> savers = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        sp = prefs.raw();
        dp = getResources().getDisplayMetrics().density;
        page = getIntent().getStringExtra(EXTRA_PAGE);
        th = DashTheme.byId(prefs.theme());
        ACCENT = th.accent;
        TEXT = th.text;
        DIM = th.dim;
        SURFACE = th.surface;
        BG = th.bg;
        LINE = th.line;
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setBackgroundColor(BG);
        if (th.light) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | (android.os.Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0));
        }
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        // a column of readable width in the middle, as iPad Settings do in landscape
        list.setPadding(px(20), px(8), px(20), px(48));
        FrameLayout center = new FrameLayout(this);
        center.addView(list, new FrameLayout.LayoutParams(Math.min(getResources().getDisplayMetrics().widthPixels, px(720)), -2,
                Gravity.CENTER_HORIZONTAL));
        sv.addView(center);
        setContentView(sv);
        String title = "Настройки";
        if (page != null) for (Object[] pg : PAGES) if (pg[0].equals(page)) title = (String) pg[1];
        navBar(page == null ? null : "Настройки", title);
        if (page == null) home();
        else build(page);
    }

    @Override
    protected void onPause() {
        saveAll();
        super.onPause();
    }

    /** Back from the login page, the desk editor or a section: show what they changed. */
    @Override
    protected void onRestart() {
        super.onRestart();
        // fields were saved when we left; saving them again would overwrite a token the login page just stored
        savers.clear();
        recreate();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        recreate();
    }

    private void saveAll() {
        for (Runnable r : savers) r.run();
    }

    private void openPage(String id) {
        saveAll();
        startActivity(new Intent(this, SettingsActivity.class).putExtra(EXTRA_PAGE, id));
    }

    // ---------------------------------------------------------------- the map

    private LinearLayout cards;
    private LinearLayout results;

    private void home() {
        EditText search = new EditText(this);
        search.setHint("Поиск");
        search.setHintTextColor(DIM);
        search.setTextColor(TEXT);
        search.setSingleLine();
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        search.setBackground(round(th.light ? 0xFFE3E3E8 : SURFACE, 11));
        search.setPadding(px(10), px(8), px(12), px(8));
        search.setCompoundDrawablePadding(px(6));
        android.graphics.drawable.Drawable icon = getDrawable(R.drawable.ic_search);
        if (icon != null) {
            icon.setTint(DIM);
            icon.setBounds(0, 0, px(20), px(20));
            search.setCompoundDrawablesRelative(icon, null, null, null);
        }
        list.addView(search, lpm(0, 8));
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        list.addView(results);
        cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.VERTICAL);
        list.addView(cards);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable e) {
                search(e.toString());
            }
        });

        LinearLayout keep = list;
        list = cards;
        profileCard();
        section("Экран");
        pageCard("look", DashboardView.lookName(prefs.uiStyle()) + " · " + DashTheme.byId(prefs.theme()).name + " · " + FancyClock.name(prefs.clockStyle()) + " часы");
        JSONArray desks = Desks.load(prefs);
        int blocks = 0;
        for (int i = 0; i < desks.length(); i++) {
            JSONArray cols = desks.optJSONObject(i).optJSONArray("cols");
            for (int j = 0; cols != null && j < cols.length(); j++) {
                JSONArray b = cols.optJSONObject(j).optJSONArray("blocks");
                blocks += b == null ? 0 : b.length();
            }
        }
        pageCard("desks", plural(desks.length(), "стол", "стола", "столов") + " · " + plural(blocks, "блок", "блока", "блоков"));
        pageCard("screen", nightSummary() + (prefs.live() ? " · живая строка" : ""));
        section("Данные");
        pageCard("weather", Weather.configured(prefs) ? Weather.place(prefs) : "город не выбран");
        String cal = prefs.calSource();
        pageCard("calendar", "ics".equals(cal) ? icsCount() : !CalendarRepo.hasPermission(this) ? "нет доступа — нажми, чтобы дать"
                : "both".equals(cal) ? "аккаунт и " + icsCount() : "Google-аккаунт на телефоне");
        pageCard("calls", "напомнить за " + prefs.callRemind() + " мин · подключаться " + ("pc".equals(prefs.callJoin()) ? "на ПК" : "с телефона"));
        pageCard("music", MusicWatcher.hasAccess(this) ? "доступ есть" : "нужен доступ к уведомлениям");
        pageCard("stats", prefs.pcHost().isEmpty() ? "музыка и умный дом; ПК не подключён" : "ПК, созвоны, музыка, умный дом");
        section("Подключения");
        pageCard("pc", prefs.pcHost().isEmpty() ? "не подключён" : prefs.pcHost() + (prefs.pcToken().isEmpty() ? " · нет токена" : ""));
        StringBuilder h = new StringBuilder();
        if (HubClient.configured(prefs)) h.append("через хаб");
        else {
            if (HomeAssistant.configured(prefs)) h.append("Home Assistant");
            if (YandexHome.configured(prefs)) h.append(h.length() > 0 ? " + " : "").append("Яндекс");
        }
        pageCard("home", h.length() == 0 ? "не подключён" : h.toString());
        List<Bambu.Config> pr = Bambu.load(prefs);
        pageCard("printer", pr.isEmpty() ? "Bambu Lab по сети или из Home Assistant" : pr.get(0).name + (pr.size() > 1 ? " и ещё " + (pr.size() - 1) : ""));
        section("");
        button("Настроить всё по шагам (мастер)", v -> startActivity(new Intent(this, WelcomeActivity.class)
                .putExtra(WelcomeActivity.EXTRA_TOUR, true)));
        button("Найти всё в сети: ПК, хаб, Home Assistant, принтер", v -> Setup.findAll(this, prefs, this::recreate));
        list = keep;
    }

    private String nightSummary() {
        if (!prefs.nightMode()) return "ночной режим выключен";
        String when;
        switch (prefs.nightTrigger()) {
            case "sun": when = "ночь от заката до рассвета"; break;
            case "light": when = "ночь, когда в комнате темно"; break;
            default: when = "ночь " + hm(prefs.nightStart()) + "–" + hm(prefs.nightEnd()); break;
        }
        return when + " · " + ("clock".equals(prefs.nightLook()) ? "только часы" : "dim".equals(prefs.nightLook()) ? "тихий стол" : "ночной стол");
    }

    private static String hm(int minutes) {
        return String.format(Locale.ROOT, "%d:%02d", minutes / 60 % 24, minutes % 60);
    }

    private String icsCount() {
        int n = 0;
        for (String u : prefs.icsUrls().split("\\s+")) if (!u.trim().isEmpty()) n++;
        return n == 0 ? "ICS-ссылок нет" : plural(n, "ICS-ссылка", "ICS-ссылки", "ICS-ссылок");
    }

    private void search(String q) {
        results.removeAllViews();
        String s = q.trim().toLowerCase(Locale.getDefault());
        cards.setVisibility(s.isEmpty() ? View.VISIBLE : View.GONE);
        if (s.isEmpty()) return;
        LinearLayout keep = list;
        list = results;
        int n = 0;
        for (Object[] pg : PAGES) {
            String hay = ((String) pg[1] + " " + pg[3]).toLowerCase(Locale.getDefault());
            boolean hit = true;
            for (String w : s.split("\\s+")) hit &= hay.contains(w);
            if (hit) {
                pageCard((String) pg[0], hint((String) pg[3], s));
                n++;
            }
        }
        if (n == 0) note("Ничего не нашлось. Попробуй другое слово: «тема», «ночь», «токен», «Zoom».");
        list = keep;
    }

    /** Which of the section's words matched, for the search result line. */
    private static String hint(String words, String q) {
        StringBuilder sb = new StringBuilder();
        for (String w : words.split(" ")) {
            for (String part : q.split("\\s+")) {
                if (w.contains(part) && sb.indexOf(w) < 0) sb.append(sb.length() > 0 ? ", " : "").append(w);
            }
        }
        return sb.toString();
    }

    private void pageCard(String id, String status) {
        Object[] pg = null;
        for (Object[] x : PAGES) if (x[0].equals(id)) pg = x;
        if (pg == null) return;
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(px(14), px(10), px(14), px(10));
        ImageView ic = new ImageView(this);
        ic.setImageResource((int) pg[2]);
        ic.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        ic.setPadding(px(5), px(5), px(5), px(5));
        ic.setBackground(round(iconColor(id), 8));
        r.addView(ic, new LinearLayout.LayoutParams(px(30), px(30)));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        TextView a = text((String) pg[1], 17, TEXT);
        t.addView(a);
        TextView b = text(status, 13, DIM);
        b.setSingleLine();
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.addView(b);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1);
        tl.setMargins(px(14), 0, px(8), 0);
        r.addView(t, tl);
        r.addView(chevron());
        r.setOnClickListener(v -> openPage(id));
        addRow(r, 58);
    }

    private void profileCard() {
        List<Profiles.Profile> ps = Profiles.list(this);
        String act = Profiles.active(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(px(16), px(14), px(16), px(12));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        String name = Profiles.name(this, act);
        TextView av = text(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.getDefault()), 24, 0xFFFFFFFF);
        av.setGravity(Gravity.CENTER);
        av.setTypeface(Ui.font(600));
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColors(new int[]{DashTheme.blend(ACCENT, 0xFFFFFFFF, 0.25f), ACCENT});
        g.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        av.setBackground(g);
        top.addView(av, new LinearLayout.LayoutParams(px(58), px(58)));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        TextView nm = text(name, 21, TEXT);
        nm.setTypeface(Ui.font(600));
        t.addView(nm);
        t.addView(text("Профиль: своя тема, столы, календарь, ПК и умный дом", 13, DIM));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1);
        tl.leftMargin = px(14);
        top.addView(t, tl);
        card.addView(top);
        LinearLayout row = new LinearLayout(this);
        for (Profiles.Profile p : ps) {
            if (p.id.equals(act)) continue;
            row.addView(chip("→ " + p.name, false, v -> {
                saveAll();
                Profiles.setActive(this, p.id);
                recreate();
            }), chipLp());
        }
        row.addView(chip("+ Новый", false, v -> newProfile()), chipLp());
        row.addView(chip("Переименовать", false, v -> Setup.ask(this, "Имя профиля", name, false, s -> {
            Profiles.rename(this, act, s);
            recreate();
        })), chipLp());
        if (ps.size() > 1) row.addView(chip("Удалить", false, v -> new AlertDialog.Builder(this)
                .setMessage("Удалить профиль «" + name + "» со всеми его настройками и столами?")
                .setPositiveButton("Удалить", (d, w) -> {
                    savers.clear();
                    Profiles.remove(this, act);
                    recreate();
                })
                .setNegativeButton("Отмена", null).show()), chipLp());
        card.addView(scroll(row), lpm(12, 0));
        addRow(card, 0);
    }

    private void newProfile() {
        Setup.ask(this, "Новый профиль", "", false, name -> new AlertDialog.Builder(this)
                .setTitle("С чего начать?")
                .setItems(new String[]{"Копия текущего профиля", "С нуля (выбрать вариант стола)"}, (d, i) -> {
                    saveAll();
                    String id = Profiles.add(this, name, i == 0);
                    Profiles.setActive(this, id);
                    if (i == 1) startActivity(new Intent(this, WelcomeActivity.class));
                    recreate();
                }).show());
    }

    // ---------------------------------------------------------------- sections

    private void build(String id) {
        switch (id) {
            case "look": look(); break;
            case "desks": desks(); break;
            case "screen": screen(); break;
            case "weather": weather(); break;
            case "calendar": calendar(); break;
            case "calls": calls(); break;
            case "music": music(); break;
            case "stats": stats(); break;
            case "pc": pc(); break;
            case "home": smartHome(); break;
            case "printer": printer(); break;
            default: home(); break;
        }
    }

    private void look() {
        section("Вид экрана");
        LinearLayout looks = new LinearLayout(this);
        for (String[] l : DashboardView.LOOKS) {
            boolean sel = l[0].equals(prefs.uiStyle());
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(px(6), px(6), px(6), px(8));
            GradientDrawable bg = round(BG, 16);
            bg.setStroke(px(sel ? 2.5f : 1), sel ? ACCENT : LINE);
            card.setBackground(bg);
            Presets.LookPreview pv = new Presets.LookPreview(this);
            pv.set(l[0], DashTheme.byId(prefs.theme()));
            card.addView(pv, new LinearLayout.LayoutParams(px(150), px(84)));
            TextView n = text(l[1], 14, sel ? ACCENT : TEXT);
            n.setTypeface(Ui.font(sel ? 600 : 400));
            n.setGravity(Gravity.CENTER);
            card.addView(n, lpm(6, 0));
            card.setOnClickListener(v -> {
                prefs.setUiStyle(l[0]);
                recreate();
            });
            looks.addView(card, chipLp());
        }
        add(scroll(looks), 4, 4);
        note(lookAbout(prefs.uiStyle()));

        section("Тема");
        LinearLayout row = new LinearLayout(this);
        JSONObject desk = Desks.load(prefs).optJSONObject(Math.max(0, Math.min(prefs.deskIndex(), Desks.load(prefs).length() - 1)));
        for (DashTheme t : DashTheme.ALL) {
            if (t.isNight()) continue;
            boolean sel = t.id.equals(th.id);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(px(6), px(6), px(6), px(8));
            GradientDrawable bg = round(t.surface, 14);
            bg.setStroke(px(sel ? 2 : 1), sel ? ACCENT : LINE);
            card.setBackground(bg);
            Presets.Preview pv = new Presets.Preview(this);
            pv.set(desk, t);
            card.addView(pv, new LinearLayout.LayoutParams(px(150), px(76)));
            TextView n = text(t.name, 13, t.text);
            n.setGravity(Gravity.CENTER);
            card.addView(n, lpm(6, 0));
            card.setOnClickListener(v -> {
                prefs.setTheme(t.id);
                recreate();
            });
            row.addView(card, chipLp());
        }
        add(scroll(row), 4, 4);
        note("На столе: двойной тап по часам — следующая тема.");

        section("Часы");
        chips("clock_style", "digital", FancyClock.STYLES);
        note("Свайп по часам влево/вправо тоже меняет стиль.");
        label("Шрифт цифр");
        chips("clock_font", "apple", new String[][]{{"apple", "Как в iPhone"}, {"thin", "Тонкий"}, {"light", "Лёгкий"},
                {"condensed", "Узкий"}, {"serif", "С засечками"}, {"mono", "Моно"}, {"bold", "Жирный"}});
        toggle("h24", "24-часовой формат", true);
        toggle("seconds", "Показывать секунды", false);

        section("Цвета");
        toggle("google_colors", "Цвета событий как в Google Календаре (выкл. — палитра темы)", true);
        toggle("art_accent", "Акцент из обложки трека", true);
    }

    static String lookAbout(String id) {
        switch (id) {
            case "standby": return "StandBy, как у iPhone на подставке: большие часы слева, справа стопка виджетов — "
                    + "календарь, погода, музыка, дом, созвоны, день — листается вверх и вниз. Тап по часам — столы на минуту.";
            case "dock": return "Док: слева разделы (Стол, Дом, Календарь, Созвоны, Погода, Музыка, День), справа один раздел целиком. "
                    + "«Дом» показывает все устройства по комнатам.";
            case "ribbon": return "Лента дня: часы и погода сверху, ближайшие часы полосой с событиями и прогнозом, "
                    + "внизу музыка и избранные плитки умного дома.";
            default: return "Виджеты: каждый блок — отдельный виджет, столы из «Столов и блоков» листаются вбок.";
        }
    }

    private void desks() {
        section("Свои столы");
        primary("Открыть редактор столов", v -> {
            saveAll();
            startActivity(new Intent(this, DeskEditorActivity.class));
        });
        note("Столы листаются свайпом вбок. Каждый — колонки с блоками: часы, календарь, музыка, компьютер, "
                + "умный дом, созвоны, статистика дня. Там же ⋮ на экране: столы, профиль, настройки.");

        section("Готовые варианты");
        note("Заменяют столы этого профиля. Потом всё можно поправить в редакторе.");
        LinearLayout row = new LinearLayout(this);
        for (Presets.Preset p : Presets.ALL) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(px(8), px(8), px(8), px(10));
            card.setBackground(round(BG, 16));
            Presets.Preview pv = new Presets.Preview(this);
            pv.set(p, DashTheme.byId(prefs.theme()));
            card.addView(pv, new LinearLayout.LayoutParams(px(190), px(96)));
            TextView n = text(p.name, 15, TEXT);
            n.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            card.addView(n, lpm(8, 0));
            TextView a = text(p.about, 12, DIM);
            a.setMaxLines(3);
            card.addView(a, new LinearLayout.LayoutParams(px(190), -2));
            card.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("«" + p.name + "»")
                    .setMessage("Заменить столы профиля «" + Profiles.name(this, Profiles.active(this)) + "» этим вариантом?")
                    .setPositiveButton("Заменить", (d, w) -> {
                        Presets.apply(prefs, p, false);
                        Toast.makeText(this, "Готово: «" + p.name + "»", Toast.LENGTH_SHORT).show();
                        recreate();
                    })
                    .setNeutralButton("С его темой и часами", (d, w) -> {
                        Presets.apply(prefs, p, true);
                        Toast.makeText(this, "Готово: «" + p.name + "»", Toast.LENGTH_SHORT).show();
                        recreate();
                    })
                    .setNegativeButton("Отмена", null).show());
            row.addView(card, chipLp());
        }
        add(scroll(row), 4, 4);
    }

    private void screen() {
        section("Живая строка");
        toggle("live", "Строка сверху: идущий созвон, созвон через пару минут, печать, стирка, тревоги", true);
        note("Тап по строке: созвон — открыть экран ПК, встреча — подключиться, устройство — его карточка.");

        section("Яркость и экран");
        toggle("burn_in", "Защита от выгорания (экран чуть сдвигается раз в минуту)", true);
        button("Яркость как в системе", v -> {
            prefs.setBrightness(-1f);
            Toast.makeText(this, "Яркость снова управляется системой", Toast.LENGTH_SHORT).show();
        });
        note("Свайп вверх/вниз у правого края — громкость, у левого — яркость.");

        section("Ночь");
        toggle("night_mode", "Ночной режим", true);
        label("Когда начинается");
        chips("night_trigger", "time", new String[][]{{"time", "По часам"}, {"sun", "Закат — рассвет"}, {"light", "Темно в комнате"}}, true);
        String trig = prefs.nightTrigger();
        if ("time".equals(trig)) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(text("с ", 15, TEXT));
            row.addView(chip(hm(prefs.nightStart()), false, v -> pickTime("night_start", prefs.nightStart())), chipLp());
            row.addView(text("до ", 15, TEXT));
            row.addView(chip(hm(prefs.nightEnd()), false, v -> pickTime("night_end", prefs.nightEnd())), chipLp());
            add(row, 4, 4);
        } else if ("sun".equals(trig)) {
            Weather w = Weather.cached(prefs);
            Weather.Day d = w == null ? null : w.today();
            if (!Weather.configured(prefs)) note("Время заката берётся из погоды: выбери город в разделе «Погода». Пока его нет — ночь по часам.");
            else if (d != null && d.sunset > 0) note("Сегодня ночь с " + Weather.hour(d.sunset + 30 * 60_000L) + " (полчаса после заката) до рассвета в " + Weather.hour(d.sunrise) + ".");
            else note("Время заката появится, когда загрузится погода.");
        } else {
            note("Датчик света телефона: темно 45 секунд — ночь, светло 8 секунд — день. Если датчика нет, ночь по часам.");
            LinearLayout row = new LinearLayout(this);
            int cur = prefs.nightLux();
            String[][] lux = {{"2", "Совсем темно"}, {"5", "Темно"}, {"15", "Полумрак"}, {"40", "Ночник"}};
            for (String[] l : lux) {
                int v0 = Integer.parseInt(l[0]);
                row.addView(chip(l[1], v0 == cur, v -> {
                    sp.edit().putInt("night_lux", v0).apply();
                    recreate();
                }), chipLp());
            }
            add(scroll(row), 4, 4);
        }
        label("Как выглядит");
        chips("night_look", "clock", new String[][]{{"clock", "Только часы"}, {"desk", "Стол в ночных цветах"}, {"dim", "Свой стол, тише"}});
        note("«Только часы»: большие тусклые часы, будильник и погода на утро. Тап — стол на 30 секунд.");
        label("Цвет ночью");
        chips("night_tint", "red", new String[][]{{"red", "Красный"}, {"amber", "Янтарный"}, {"gray", "Серый"}});
        label("Яркость ночью");
        LinearLayout br = new LinearLayout(this);
        int nb = prefs.nightBright();
        for (int v0 : new int[]{1, 3, 5, 10, 20}) {
            br.addView(chip(v0 + " %", v0 == nb, v -> {
                sp.edit().putInt("night_bright", v0).apply();
                recreate();
            }), chipLp());
        }
        add(scroll(br), 4, 4);
        toggle("night_fade", "Плавно гасить экран за полчаса до ночи", true);
        note("Ночью свайп у левого края меняет ночную яркость, живая строка показывает только звонки и тревоги. "
                + "Выключить свет на ночь и «рассвет» лампой — в «Умный дом» → «Автоматизации».");

        section("Заставка");
        button("Открыть настройки заставки", v -> open(new Intent(Settings.ACTION_DREAM_SETTINGS)));
        note("Выбери DeskDash и «Во время зарядки»: дашборд будет включаться сам, когда телефон на подставке.");
    }

    private void weather() {
        section("Город");
        note(Weather.configured(prefs) ? "Сейчас: " + Weather.place(prefs) : "Город не выбран.");
        primary("Определить по сети", v -> {
            Toast.makeText(this, "Определяю…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try {
                    String[] r = Weather.locate();
                    Weather.setPlace(prefs, r[0], Double.parseDouble(r[1]), Double.parseDouble(r[2]));
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Город: " + r[0], Toast.LENGTH_SHORT).show();
                        recreate();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "Не вышло: " + SmartHome.msg(e), Toast.LENGTH_LONG).show());
                }
            }).start();
        });
        button("Найти город по названию", v -> searchCity(this, prefs, this::recreate));
        note("Прогноз Open-Meteo: бесплатно, без ключей и регистрации. Город по сети определяется по адресу подключения, "
                + "без доступа к геолокации.");
        section("На экране");
        toggle("weather_clock", "Строка погоды под часами", true);
        toggle("weather_rain", "Предупреждать о дожде и снеге в живой строке", true);
        note("Блок «Погода» (сейчас, по часам, на 3 дня) добавляется в «Столы и блоки». Тап по нему обновляет прогноз.");
    }

    /** Search a place by name and save the picked one; shared with the first-start wizard. */
    static void searchCity(Activity a, Prefs p, Runnable done) {
        Setup.ask(a, "Город", "", false, q -> new Thread(() -> {
            try {
                List<String[]> found = Weather.search(q);
                a.runOnUiThread(() -> {
                    if (found.isEmpty()) {
                        Toast.makeText(a, "Ничего не нашлось", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String[] names = new String[found.size()];
                    for (int i = 0; i < names.length; i++) names[i] = found.get(i)[0];
                    new AlertDialog.Builder(a).setTitle("Выбери").setItems(names, (d, i) -> {
                        String[] r = found.get(i);
                        Weather.setPlace(p, r[0].split(",")[0], Double.parseDouble(r[1]), Double.parseDouble(r[2]));
                        if (done != null) done.run();
                    }).show();
                });
            } catch (Exception e) {
                a.runOnUiThread(() -> Toast.makeText(a, "Поиск: " + SmartHome.msg(e), Toast.LENGTH_LONG).show());
            }
        }).start());
    }

    private void pickTime(String key, int minutes) {
        new TimePickerDialog(this, (tp, h, m) -> {
            sp.edit().putInt(key, h * 60 + m).apply();
            recreate();
        }, minutes / 60 % 24, minutes % 60, true).show();
    }

    private void calendar() {
        section("Откуда брать события");
        chips("cal_source", "system", new String[][]{{"system", "Google-аккаунт"}, {"ics", "ICS-ссылки"}, {"both", "Оба"}});
        if (!CalendarRepo.hasPermission(this)) {
            primary("Дать доступ к календарю", v -> requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, 1));
        } else {
            note("Доступ к календарю телефона есть.");
        }
        note("Через аккаунт: нужен Google-аккаунт с синхронизацией календаря (GApps или DAVx⁵). Цвета событий будут как в Google.");
        section("ICS-ссылки");
        edit("ics_urls", "https://calendar.google.com/calendar/ical/…/basic.ics",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_MULTI_LINE, false);
        note("Без Google-сервисов (чистый LineageOS): calendar.google.com → Настройки → твой календарь → «Интеграция календаря» → «Закрытый адрес в формате iCal». Можно несколько ссылок, каждая с новой строки. Обновление раз в 10 минут.");
    }

    private void calls() {
        section("Без компьютера и календаря");
        toggle("phone_calls", "Звонки на этом телефоне: Telegram, WhatsApp, Zoom, Meet, обычные", true);
        if (!MusicWatcher.hasAccess(this)) {
            primary("Дать доступ к уведомлениям", v -> open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        }
        note("Звонок на телефоне виден по уведомлению приложения: кто, сколько идёт, кнопки микрофона, громкой связи, "
                + "«Завершить» и «Ответить». Нужен доступ к уведомлениям (тот же, что для музыки).");
        note("«+ Звонок по ссылке» в блоке «Созвоны»: вставь ссылку или скопируй её где угодно — она сама появится в блоке. "
                + "Там же недавние ссылки и «Новая встреча» в Телемосте, Meet или Jitsi.");
        toggle("call_sound", "Звук уведомления, когда встреча вот-вот начнётся", true);
        section("Откуда берутся");
        note("Встречи — из календаря: в событии ищется ссылка Zoom, Google Meet, Teams, Телемоста, Jitsi, VK Звонков, "
                + "SberJazz, Контур.Толка, Webex, Skype, Discord, Slack и других. Идущий звонок на ПК DeskDash.exe "
                + "видит сам, в любом приложении: кто занял микрофон — Zoom, Teams, Discord, Telegram, браузер с Meet.");
        section("Напоминать");
        LinearLayout row = new LinearLayout(this);
        int cur = prefs.callRemind();
        for (int m : new int[]{1, 3, 5, 10, 15}) {
            row.addView(chip("за " + m + " мин", m == cur, v -> {
                sp.edit().putInt("call_remind", m).apply();
                recreate();
            }), chipLp());
        }
        add(scroll(row), 4, 4);
        note("Живая строка сверху и блок «Созвоны» подсвечивают встречу и дают кнопку «Подключиться».");
        section("Подключаться");
        chips("call_join", "pc", new String[][]{{"pc", "На компьютере"}, {"phone", "С этого телефона"}});
        note("Строка сверху открывает встречу там, что выбрано. В блоке «Созвоны» есть обе кнопки.");
        section("Экран ПК на телефоне");
        note("Во время созвона телефон показывает экран компьютера, поверх — вебку (её можно двигать и поменять местами "
                + "тапом), и кнопки: микрофон, камера, демонстрация, рука, выйти. На ПК это можно выключить в DeskDash → Настройки.");
        if (!prefs.pcHost().isEmpty()) button("Открыть экран ПК", v -> startActivity(new Intent(this, StreamActivity.class)));
        else note("Сначала подключи компьютер.");
        toggle("live", "Показывать идущий созвон в живой строке", true);
    }

    private void music() {
        section("Музыка");
        if (MusicWatcher.hasAccess(this)) note("Доступ к уведомлениям есть: видно, что играет.");
        else primary("Дать доступ к уведомлениям", v -> open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        note("Включи DeskDash в списке, тогда будет видно, что играет в Spotify. Выбор устройства (кнопка рядом с плеером) появится, когда войдёшь в Spotify во вкладке Spotify в DeskDash.exe на ПК.");
    }

    private void stats() {
        section("Что считается");
        note("Компьютер (DeskDash.exe): сколько ты за ним по часам, в каких приложениях, созвоны с длительностью, неделя для сравнения. "
                + "Две минуты без мыши и клавиатуры ещё считаются, созвон считается всегда.");
        note("Дашборд: сколько играла музыка и сколько треков, нажатия по умному дому. Календарь: сколько встреч по плану.");
        section("Где смотреть");
        note("Блок «День» (добавь в «Столы и блоки») и ⋮ → «Статистика дня» на экране.");
        JSONObject d = DayStats.today(this);
        note("Сегодня на дашборде: музыка " + HomeDevice.minutes(d.optInt("music_sec") / 60) + ", "
                + plural(d.optInt("tracks"), "трек", "трека", "треков") + ", "
                + plural(d.optInt("home"), "нажатие", "нажатия", "нажатий") + " по умному дому.");
    }

    private void pc() {
        section("Найти автоматически");
        primary(prefs.pcHost().isEmpty() ? "Найти ПК в сети" : "Найти заново", v -> {
            saveAll();
            Setup.findPc(this, prefs, this::recreate);
        });
        note("На ПК запусти DeskDash.exe: он покажет токен. Кнопки-макросы настраиваются там во вкладке «Макросы» и сами появятся на экране.");
        section("Вручную");
        edit("pc_host", "IP компьютера, например 192.168.1.10", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, false);
        edit("pc_port", "Порт (8765)", InputType.TYPE_CLASS_NUMBER, true);
        edit("pc_token", "Токен из окна агента", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, false);
        button("Проверить соединение", v -> {
            saveAll();
            if (prefs.pcHost().isEmpty()) {
                Toast.makeText(this, "Сначала укажи IP", Toast.LENGTH_SHORT).show();
                return;
            }
            Setup.checkPc(this, prefs, null);
        });
        note("Когда ПК выключен, тап по строке статуса на дашборде будит его (Wake-on-LAN должен быть включён в BIOS).");
    }

    private void smartHome() {
        section("Хаб (Raspberry Pi)");
        primary(HubClient.configured(prefs) ? "Найти хаб заново" : "Найти хаб в сети", v -> {
            saveAll();
            Setup.findHub(this, prefs, this::recreate);
        });
        edit("hub_url", "Адрес хаба, например 192.168.1.5", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, false);
        edit("hub_token", "Токен доступа к хабу", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, false);
        LinearLayout hr = new LinearLayout(this);
        hr.addView(chip("Проверить хаб", false, v -> testHub()), chipLp());
        hr.addView(chip("Перенести умный дом и ПК на хаб", false, v -> importToHub()), chipLp());
        add(scroll(hr), 6, 2);
        note("Хаб держит входы в Home Assistant и Яндекс, следит за компьютерами и запускает общие сценарии. "
                + "Когда он указан, блок «Умный дом» берёт всё с хаба. Перенос отдаёт хабу вход в Home Assistant, "
                + "Яндекс и ПК из этого профиля (нужен токен администратора).");

        section("Home Assistant");
        edit("ha_url", "Адрес, например http://192.168.1.20:8123", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, false);
        primary(prefs.haRefresh().isEmpty() ? "Войти в Home Assistant" : "Войти заново", v -> {
            saveAll();
            if (prefs.haUrl().isEmpty()) {
                Toast.makeText(this, "Сначала укажи адрес Home Assistant", Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(new Intent(this, LoginActivity.class).putExtra(LoginActivity.EXTRA_MODE, "ha"));
        });
        edit("ha_token", "Или долгосрочный токен (необязательно)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, false);
        note((prefs.haRefresh().isEmpty() ? "" : "Вход выполнен. ")
                + "Адрес такой же, как в браузере. Если вход не работает, создай токен: профиль пользователя в HA → «Безопасность» → «Долгосрочные токены доступа». "
                + "Принтеры (Bambu Lab, OctoPrint, Klipper), пылесосы, камеры и стиральные машины из HA становятся отдельными плитками.");
        LinearLayout ha = new LinearLayout(this);
        ha.addView(chip("Проверить", false, v -> testHome(true)), chipLp());
        if (!prefs.haRefresh().isEmpty()) ha.addView(chip("Выйти", false, v -> {
            sp.edit().remove("ha_access").remove("ha_refresh").remove("ha_expires").apply();
            recreate();
        }), chipLp());
        add(scroll(ha), 6, 2);

        section("Яндекс");
        edit("ya_client_id", "ClientID приложения Яндекс OAuth", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, false);
        primary(prefs.yaToken().isEmpty() ? "Войти в Яндекс" : "Войти заново", v -> {
            saveAll();
            if (prefs.yaClientId().isEmpty()) {
                Toast.makeText(this, "Сначала вставь ClientID", Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(new Intent(this, LoginActivity.class).putExtra(LoginActivity.EXTRA_MODE, "ya"));
        });
        edit("ya_token", "OAuth-токен (заполнится сам после входа)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, false);
        note("Один раз: oauth.yandex.ru → «Создать приложение» → веб-сервисы, Redirect URI "
                + "https://oauth.yandex.ru/verification_code, доступы «Умный дом»: просмотр и управление (iot:view, iot:control). "
                + "Скопируй ClientID сюда и нажми «Войти в Яндекс». Токен живёт год.");
        button("Проверить Яндекс", v -> testHome(false));

        section("На экране");
        note("Устройства на экран добавляются в «Столы и блоки» → блок «Умный дом» → «Устройства…». "
                + "Тап по плитке включает/выключает, долгий тап — яркость, цвет, температура, пауза печати. "
                + "В заголовке блока — температура дома, сколько света горит и «Выключить всё».");

        section("Автоматизации");
        if (!SmartHome.configured(prefs)) {
            note("Сначала подключи хаб, Home Assistant или Яндекс.");
            return;
        }
        autoRow("Во время созвона включать", "auto_call", false, d -> d.kind == HomeDevice.LIGHT || d.kind == HomeDevice.SWITCH
                || d.kind == HomeDevice.SCENE);
        note("Лампа у стола или сценарий: включается, когда начался звонок на телефоне или ПК, и гаснет после.");
        autoRow("Когда наступает ночь, выключать", "auto_night", true, d -> d.canToggle && d.kind != HomeDevice.LOCK
                || d.kind == HomeDevice.SCENE);
        autoRow("Рассвет: лампа плавно загорается к концу ночи", "auto_dawn", false, d -> d.kind == HomeDevice.LIGHT);
        LinearLayout dr = new LinearLayout(this);
        int dm = prefs.dawnMinutes();
        for (int v0 : new int[]{10, 20, 30, 45}) {
            dr.addView(chip("за " + v0 + " мин", v0 == dm, v -> {
                sp.edit().putInt("dawn_min", v0).apply();
                recreate();
            }), chipLp());
        }
        add(scroll(dr), 4, 4);
        note("Рассвет заканчивается вместе с ночью (Экран и ночь → «до»). Автоматизации работают, пока дашборд открыт.");
    }

    private interface DeviceFilter {
        boolean ok(HomeDevice d);
    }

    /** "Title: what is chosen" with a picker of the devices that fit. */
    private void autoRow(String title, String key, boolean multi, DeviceFilter filter) {
        String names = sp.getString(key + "_names", "");
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(px(16), px(10), px(12), px(10));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(text(title, 16, TEXT));
        t.addView(text(sp.getString(key, "").isEmpty() ? "не выбрано" : names, 12, sp.getString(key, "").isEmpty() ? DIM : ACCENT));
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(chip("Выбрать", false, v -> pickAuto(title, key, multi, filter)));
        addRow(r, 0);
    }

    private void pickAuto(String title, String key, boolean multi, DeviceFilter filter) {
        saveAll();
        Toast.makeText(this, "Загружаю устройства…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            StringBuilder err = new StringBuilder();
            List<HomeDevice> all = new ArrayList<>();
            for (HomeDevice d : SmartHome.listAll(prefs, err)) if (filter.ok(d)) all.add(d);
            runOnUiThread(() -> {
                if (all.isEmpty()) {
                    Toast.makeText(this, err.length() > 0 ? err.toString() : "Подходящих устройств нет", Toast.LENGTH_LONG).show();
                    return;
                }
                String[] labels = new String[all.size() + (multi ? 0 : 1)];
                int off = multi ? 0 : 1;
                if (!multi) labels[0] = "— не нужно —";
                for (int i = 0; i < all.size(); i++) {
                    HomeDevice d = all.get(i);
                    labels[i + off] = d.name + (d.room.isEmpty() ? "" : " · " + d.room);
                }
                List<String> cur = java.util.Arrays.asList(sp.getString(key, "").split(","));
                AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(title);
                if (multi) {
                    boolean[] checked = new boolean[all.size()];
                    for (int i = 0; i < all.size(); i++) checked[i] = cur.contains(all.get(i).id);
                    b.setMultiChoiceItems(labels, checked, (d, i, on) -> checked[i] = on)
                            .setPositiveButton("Сохранить", (d, w) -> {
                                StringBuilder ids = new StringBuilder(), ns = new StringBuilder();
                                for (int i = 0; i < all.size(); i++) {
                                    if (!checked[i]) continue;
                                    ids.append(ids.length() > 0 ? "," : "").append(all.get(i).id);
                                    ns.append(ns.length() > 0 ? ", " : "").append(all.get(i).name);
                                }
                                sp.edit().putString(key, ids.toString()).putString(key + "_names", ns.toString()).apply();
                                recreate();
                            })
                            .setNegativeButton("Отмена", null);
                } else {
                    int sel = 0;
                    for (int i = 0; i < all.size(); i++) if (cur.contains(all.get(i).id)) sel = i + 1;
                    b.setSingleChoiceItems(labels, sel, (d, i) -> {
                        if (i == 0) sp.edit().remove(key).remove(key + "_names").apply();
                        else sp.edit().putString(key, all.get(i - 1).id).putString(key + "_names", all.get(i - 1).name).apply();
                        d.dismiss();
                        recreate();
                    });
                }
                b.show();
            });
        }).start();
    }

    private void printer() {
        section("Bambu Lab по сети");
        List<Bambu.Config> list0 = Bambu.load(prefs);
        for (Bambu.Config c : list0) {
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(px(16), px(10), px(12), px(10));
            LinearLayout t = new LinearLayout(this);
            t.setOrientation(LinearLayout.VERTICAL);
            t.addView(text(c.name, 16, TEXT));
            t.addView(text(c.host + " · " + c.serial, 12, DIM));
            r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(chip("Изменить", false, v -> Setup.printerForm(this, prefs, new String[]{c.host, c.serial, c.name, c.code}, this::recreate)));
            r.addView(chip("Удалить", false, v -> {
                List<Bambu.Config> l = Bambu.load(prefs);
                for (int i = 0; i < l.size(); i++) if (l.get(i).serial.equals(c.serial)) l.remove(i--);
                Bambu.save(prefs, l);
                recreate();
            }));
            addRow(r, 0);
        }
        primary("Найти принтер", v -> Setup.findPrinter(this, prefs, this::recreate));
        button("Добавить вручную", v -> Setup.printerForm(this, prefs, new String[]{"", "", "", ""}, this::recreate));
        note("Дашборд подключается к принтеру напрямую, как Bambu Studio в локальной сети: облако и ПК не нужны. "
                + "Если на ПК запущен DeskDash.exe, код доступа подтянется из Bambu Studio сам. Иначе он на экране принтера: "
                + "Настройки → WLAN. Если принтер не пускает, включи на нём «LAN-режим» или «Режим разработчика».");
        note("На плитке: процент, сколько осталось и картинка с камеры (P1, A1). Долгий тап: пауза, продолжить, стоп, подсветка. "
                + "Когда печать закончится, это появится в живой строке сверху.");
        section("Из Home Assistant");
        note("Принтеры интеграций Bambu Lab, OctoPrint и Moonraker/Klipper появляются в списке устройств блока «Умный дом» сами.");
    }

    // ---------------------------------------------------------------- actions

    private void testHome(boolean ha) {
        saveAll();
        if (ha ? !HomeAssistant.configured(prefs) : !YandexHome.configured(prefs)) {
            Toast.makeText(this, ha ? "Укажи адрес и войди в Home Assistant" : "Сначала войди в Яндекс", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Проверяю…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String msg;
            try {
                List<HomeDevice> l = ha ? new HomeAssistant(prefs).states() : new YandexHome(prefs).devices();
                msg = "Работает: " + l.size() + " устройств и сценариев";
            } catch (Exception e) {
                msg = "Ошибка: " + SmartHome.msg(e);
            }
            String m = msg;
            runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        }).start();
    }

    private void testHub() {
        saveAll();
        if (!HubClient.configured(prefs)) {
            Toast.makeText(this, "Укажи адрес и токен хаба", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Проверяю…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String msg;
            try {
                msg = "Работает: " + new HubClient(prefs).devices().size() + " плиток";
            } catch (Exception e) {
                msg = "Ошибка: " + SmartHome.msg(e);
            }
            String m = msg;
            runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        }).start();
    }

    private void importToHub() {
        saveAll();
        if (!HubClient.configured(prefs)) {
            Toast.makeText(this, "Укажи адрес и токен хаба", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            String msg;
            try {
                String done = new HubClient(prefs).importSettings(prefs);
                msg = done.isEmpty() ? "Переносить нечего: в профиле нет входов и ПК" : "Хаб получил: " + done;
            } catch (Exception e) {
                msg = "Ошибка: " + SmartHome.msg(e);
            }
            String m = msg;
            runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        }).start();
    }

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Экран настроек недоступен на этой прошивке", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- form builders, iOS style

    /** The rounded group that rows go into; a header, a footnote or a new list starts the next one. */
    private LinearLayout group;

    /** "‹ Назад" in the accent colour and the large title. */
    private void navBar(String back, String title) {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setMinimumHeight(px(44));
        if (back != null) {
            TextView b = text("‹ " + back, 17, ACCENT);
            b.setPadding(0, px(8), px(16), px(8));
            b.setOnClickListener(v -> finish());
            bar.addView(b);
        }
        list.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        TextView t = text(title, 34, TEXT);
        t.setTypeface(Ui.font(700));
        list.addView(t, lpm(0, 12));
    }

    private LinearLayout openGroup() {
        if (group == null || group.getParent() != list) {
            group = new LinearLayout(this);
            group.setOrientation(LinearLayout.VERTICAL);
            group.setBackground(round(SURFACE, Ui.R_GROUP));
            group.setClipToOutline(true);
            list.addView(group, lpm(0, 0));
        }
        return group;
    }

    private void closeGroup() {
        group = null;
    }

    /** A row that brings its own padding; minDp is its minimum height. */
    private void addRow(View v, int minDp) {
        LinearLayout g = openGroup();
        if (g.getChildCount() > 0) {
            View line = new View(this);
            line.setBackgroundColor(LINE);
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, Math.max(1, (int) (dp * 0.6f)));
            ll.leftMargin = px(16);
            g.addView(line, ll);
        }
        if (minDp > 0) v.setMinimumHeight(px(minDp));
        g.addView(v, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Anything else (a field, a row of chips, previews) as a padded row of the current group. */
    private void add(View v, int topDp, int bottomDp) {
        FrameLayout f = new FrameLayout(this);
        f.setPadding(px(16), px(10), px(16), px(10));
        f.addView(v, new FrameLayout.LayoutParams(-1, -2));
        addRow(f, 44);
    }

    private void section(String title) {
        closeGroup();
        TextView t = text(title.toUpperCase(Locale.getDefault()), 13, DIM);
        t.setPadding(px(16), 0, px(16), 0);
        list.addView(t, lpm(title.isEmpty() ? 14 : 26, title.isEmpty() ? 0 : 7));
    }

    private void label(String s) {
        section(s);
    }

    /** The grey explanation under a group. */
    private void note(String s) {
        closeGroup();
        TextView t = text(s, 13, DIM);
        t.setLineSpacing(0, 1.15f);
        t.setPadding(px(16), 0, px(16), 0);
        list.addView(t, lpm(7, 4));
    }

    private TextView chevron() {
        TextView c = text("›", 24, DIM);
        c.setAlpha(0.6f);
        return c;
    }

    private void toggle(String key, String label, boolean def) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(px(16), px(8), px(14), px(8));
        TextView t = text(label, 17, TEXT);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        Ui.Switch sw = new Ui.Switch(this, th, sp.getBoolean(key, def));
        sw.setListener(on -> sp.edit().putBoolean(key, on).apply());
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.leftMargin = px(12);
        r.addView(sw, sl);
        r.setOnClickListener(v -> sw.toggle());
        addRow(r, 48);
    }

    private void chips(String key, String def, String[][] options, boolean reload) {
        LinearLayout row = new LinearLayout(this);
        String cur = sp.getString(key, def);
        for (String[] o : options) {
            row.addView(chip(o[1], o[0].equals(cur), v -> {
                sp.edit().putString(key, o[0]).apply();
                if (reload) recreate();
            }), chipLp());
        }
        add(scroll(row), 4, 4);
    }

    /** One-of choice as a row of chips. */
    private void chips(String key, String def, String[][] options) {
        LinearLayout row = new LinearLayout(this);
        String cur = sp.getString(key, def);
        List<TextView> all = new ArrayList<>();
        for (String[] o : options) {
            TextView c = chip(o[1], o[0].equals(cur), null);
            all.add(c);
            c.setOnClickListener(v -> {
                sp.edit().putString(key, o[0]).apply();
                for (int i = 0; i < all.size(); i++) styleChip(all.get(i), options[i][0].equals(o[0]));
            });
            row.addView(c, chipLp());
        }
        add(scroll(row), 4, 4);
    }

    private TextView chip(String label, boolean selected, View.OnClickListener l) {
        TextView t = text(label, 15, TEXT);
        t.setSingleLine();
        t.setPadding(px(14), px(7), px(14), px(7));
        styleChip(t, selected);
        if (l != null) t.setOnClickListener(l);
        return t;
    }

    private void styleChip(TextView t, boolean selected) {
        t.setTextColor(selected ? DashTheme.onColor(ACCENT) : TEXT);
        t.setTypeface(Ui.font(selected ? 600 : 400));
        t.setBackground(round(selected ? ACCENT : Ui.fill(th), 100));
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
        l.rightMargin = px(8);
        return l;
    }

    private HorizontalScrollView scroll(View v) {
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.addView(v);
        return h;
    }

    private EditText intField(String key, String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(DIM);
        e.setTextColor(TEXT);
        e.setBackground(null);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setGravity(Gravity.CENTER);
        if (sp.contains(key)) e.setText(String.valueOf(sp.getInt(key, 0)));
        savers.add(() -> {
            try {
                sp.edit().putInt(key, Math.max(0, Math.min(23, Integer.parseInt(e.getText().toString().trim())))).apply();
            } catch (NumberFormatException ex) {
                sp.edit().remove(key).apply();
            }
        });
        return e;
    }

    private EditText edit(String key, String hint, int type, boolean isInt) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(DIM);
        e.setTextColor(TEXT);
        e.setBackground(null);
        e.setPadding(0, 0, 0, 0);
        e.setInputType(type);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        if (isInt) {
            if (sp.contains(key)) e.setText(String.valueOf(sp.getInt(key, 0)));
        } else {
            e.setText(sp.getString(key, ""));
        }
        savers.add(() -> {
            String v = e.getText().toString().trim();
            SharedPreferences.Editor ed = sp.edit();
            if (isInt) {
                try {
                    ed.putInt(key, Integer.parseInt(v));
                } catch (NumberFormatException ex) {
                    ed.remove(key);
                }
            } else {
                ed.putString(key, v);
            }
            ed.apply();
        });
        add(e, 6, 2);
        return e;
    }

    /** An action row in the accent colour, as iOS lists have them. */
    private void button(String label, View.OnClickListener l) {
        TextView b = text(label, 17, ACCENT);
        b.setPadding(px(16), px(12), px(16), px(12));
        b.setOnClickListener(l);
        addRow(b, 48);
    }

    /** The main action of a section: the same row, in semibold. */
    private void primary(String label, View.OnClickListener l) {
        TextView b = text(label, 17, ACCENT);
        b.setTypeface(Ui.font(600));
        b.setPadding(px(16), px(12), px(16), px(12));
        b.setOnClickListener(l);
        addRow(b, 48);
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private GradientDrawable round(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r * dp);
        return g;
    }

    private LinearLayout.LayoutParams lpm(float top, float bottom) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
        l.setMargins(0, px(top), 0, px(bottom));
        return l;
    }

    private static String plural(int n, String one, String few, String many) {
        return StatsPanel.plural(n, one, few, many);
    }

    private int px(float v) {
        return Math.round(v * dp);
    }
}

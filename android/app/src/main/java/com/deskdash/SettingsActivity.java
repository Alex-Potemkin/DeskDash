package com.deskdash;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends Activity {
    private static final int ACCENT = 0xFFC8A27C, TEXT = 0xFFF1E6DA, DIM = 0xFF9C8B7E;

    private Prefs prefs;
    private SharedPreferences sp;
    private LinearLayout list;
    private float dp;
    private final List<Runnable> savers = new ArrayList<>();
    private EditText hostE, portE, tokenE;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        sp = prefs.raw();
        dp = getResources().getDisplayMetrics().density;
        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int p = px(24);
        list.setPadding(p, px(12), p, px(40));
        sv.addView(list);
        setContentView(sv);

        section("Стиль");
        String[][] themes = new String[DashTheme.ALL.length - 1][];
        for (int i = 0; i < themes.length; i++) themes[i] = new String[]{DashTheme.ALL[i].id, DashTheme.ALL[i].name};
        label("Тема");
        radio("theme", "mocha", themes);
        label("Часы");
        radio("clock_style", "digital", FancyClock.STYLES);
        label("Шрифт часов");
        radio("clock_font", "thin", new String[][]{{"thin", "Тонкий"}, {"light", "Лёгкий"},
                {"condensed", "Узкий"}, {"serif", "С засечками"}, {"mono", "Моно"}, {"bold", "Жирный"}});
        toggle("h24", "24-часовой формат", true);
        toggle("seconds", "Показывать секунды", false);
        toggle("google_colors", "Цвета событий как в Google Календаре (выкл. — палитра темы)", true);
        toggle("art_accent", "Акцент из обложки трека", true);
        toggle("burn_in", "Защита от выгорания (экран чуть сдвигается раз в минуту)", true);
        note("Двойной тап по часам — тема, свайп по часам влево/вправо — стиль часов, долгий тап — настройки. "
                + "Свайп вверх/вниз у правого края — громкость, у левого — яркость.");
        button("Яркость как в системе", v -> {
            prefs.setBrightness(-1f);
            Toast.makeText(this, "Яркость снова управляется системой", Toast.LENGTH_SHORT).show();
        });

        section("Google Календарь");
        label("Откуда брать события");
        radio("cal_source", "system", new String[][]{{"system", "Google-аккаунт на телефоне"},
                {"ics", "Закрытые ICS-ссылки"}, {"both", "Оба источника"}});
        button("Дать доступ к календарю", v -> requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, 1));
        note("Через аккаунт: нужен Google-аккаунт с синхронизацией календаря (GApps или DAVx⁵). Цвета событий будут как в Google.");
        edit("ics_urls", "https://calendar.google.com/calendar/ical/…/basic.ics",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_MULTI_LINE, false);
        note("Без Google-сервисов (чистый LineageOS): calendar.google.com → Настройки → твой календарь → «Интеграция календаря» → «Закрытый адрес в формате iCal». Можно несколько ссылок, каждая с новой строки. Обновление раз в 10 минут.");

        section("Музыка");
        button("Дать доступ к уведомлениям", v -> open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        note("Включи DeskDash в списке, тогда будет видно, что играет в Spotify. Выбор устройства (кнопка рядом с плеером) появится, когда войдёшь в Spotify во вкладке Spotify в DeskDash.exe на ПК.");

        section("Компьютер");
        hostE = edit("pc_host", "IP компьютера, например 192.168.1.10", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, false);
        portE = edit("pc_port", "Порт (8765)", InputType.TYPE_CLASS_NUMBER, true);
        tokenE = edit("pc_token", "Токен из окна агента", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, false);
        button("Найти ПК в сети", v -> discover());
        button("Проверить соединение", v -> test());
        note("На ПК запусти DeskDash.exe. Кнопки-макросы настраиваются там во вкладке «Макросы» и сами появятся на экране.");

        section("Ночь");
        toggle("night_mode", "Ночной режим: тёмно-красная тема и минимальная яркость", true);
        edit("night_from", "С какого часа (23)", InputType.TYPE_CLASS_NUMBER, true);
        edit("night_to", "До какого часа (7)", InputType.TYPE_CLASS_NUMBER, true);

        section("Заставка");
        button("Открыть настройки заставки", v -> open(new Intent(Settings.ACTION_DREAM_SETTINGS)));
        note("Выбери DeskDash и «Во время зарядки»: дашборд будет включаться сам, когда телефон на подставке.");
    }

    @Override
    protected void onPause() {
        for (Runnable r : savers) r.run();
        super.onPause();
    }

    // ---------------------------------------------------------------- actions

    private void discover() {
        Toast.makeText(this, "Ищу…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<String[]> found = PcClient.discover(1500);
            runOnUiThread(() -> {
                if (found.isEmpty()) {
                    Toast.makeText(this, "Не найден. Агент запущен? Телефон и ПК в одной Wi-Fi сети?", Toast.LENGTH_LONG).show();
                } else if (found.size() == 1) {
                    fill(found.get(0));
                } else {
                    String[] names = new String[found.size()];
                    for (int i = 0; i < names.length; i++) names[i] = found.get(i)[2] + "  (" + found.get(i)[0] + ")";
                    new AlertDialog.Builder(this).setTitle("Выбери компьютер")
                            .setItems(names, (d, i) -> fill(found.get(i))).show();
                }
            });
        }).start();
    }

    private void fill(String[] r) {
        hostE.setText(r[0]);
        portE.setText(r[1]);
        Toast.makeText(this, "Найден " + r[2] + ". Введи токен из окна агента.", Toast.LENGTH_LONG).show();
        tokenE.requestFocus();
    }

    private void test() {
        for (Runnable r : savers) r.run();
        String h = prefs.pcHost();
        if (h.isEmpty()) {
            Toast.makeText(this, "Сначала укажи IP", Toast.LENGTH_SHORT).show();
            return;
        }
        PcClient c = new PcClient(h, prefs.pcPort(), prefs.pcToken());
        new Thread(() -> {
            String msg;
            try {
                PcClient.Info i = c.info();
                msg = "Подключено: " + i.name + ", макросов: " + i.macros.size();
            } catch (Exception e) {
                msg = "Ошибка: " + e.getMessage();
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

    // ---------------------------------------------------------------- form builders

    private void section(String title) {
        TextView t = text(title, 13, ACCENT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setLetterSpacing(0.12f);
        t.setAllCaps(true);
        add(t, 28, 6);
    }

    private void label(String s) {
        add(text(s, 15, TEXT), 12, 2);
    }

    private void note(String s) {
        TextView t = text(s, 13, DIM);
        t.setLineSpacing(0, 1.15f);
        add(t, 4, 6);
    }

    private void toggle(String key, String label, boolean def) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(TEXT);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        s.setChecked(sp.getBoolean(key, def));
        s.setOnCheckedChangeListener((b, on) -> sp.edit().putBoolean(key, on).apply());
        add(s, 8, 8);
    }

    private void radio(String key, String def, String[][] options) {
        RadioGroup g = new RadioGroup(this);
        String cur = sp.getString(key, def);
        for (String[] o : options) {
            RadioButton b = new RadioButton(this);
            b.setId(View.generateViewId());
            b.setText(o[1]);
            b.setTextColor(TEXT);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            g.addView(b);
            if (o[0].equals(cur)) b.setChecked(true);
            b.setOnCheckedChangeListener((v, on) -> {
                if (on) sp.edit().putString(key, o[0]).apply();
            });
        }
        add(g, 0, 4);
    }

    private EditText edit(String key, String hint, int type, boolean isInt) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(0xFF6B5D52);
        e.setTextColor(TEXT);
        e.setInputType(type);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
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

    private void button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        add(b, 6, 2);
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private void add(View v, int topDp, int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, px(topDp), 0, px(bottomDp));
        list.addView(v, lp);
    }

    private int px(float v) {
        return Math.round(v * dp);
    }
}

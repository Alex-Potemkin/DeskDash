package com.deskdash;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Connecting things in a few taps, shared by the first-start wizard and the settings: find the PC,
 * the hub or a 3D printer on the network, ask only for what can't be found (a token, an access code),
 * check it and save it to the active profile.
 */
final class Setup {
    private Setup() {}

    // ---------------------------------------------------------------- PC

    static void findPc(Activity a, Prefs p, Runnable done) {
        toast(a, "Ищу компьютер с DeskDash.exe…");
        new Thread(() -> {
            List<String[]> found = PcClient.discover(1500);
            a.runOnUiThread(() -> {
                if (found.isEmpty()) {
                    toast(a, "Не найден. DeskDash.exe запущен? Телефон и ПК в одной Wi-Fi сети?");
                    return;
                }
                pick(a, "Выбери компьютер", found, r -> ask(a, "Токен из окна DeskDash на ПК «" + r[2] + "»", "", false, token -> {
                    p.raw().edit().putString("pc_host", r[0]).putInt("pc_port", Integer.parseInt(r[1]))
                            .putString("pc_token", token).apply();
                    checkPc(a, p, done);
                }));
            });
        }).start();
    }

    static void checkPc(Activity a, Prefs p, Runnable done) {
        PcClient c = new PcClient(p.pcHost(), p.pcPort(), p.pcToken());
        new Thread(() -> {
            String msg;
            try {
                PcClient.Info i = c.info();
                msg = "Подключено: " + i.name + ", макросов: " + i.macros.size()
                        + (i.features.contains("calls") ? "" : ". Обнови DeskDash.exe для созвонов и статистики");
            } catch (Exception e) {
                msg = "Ошибка: " + SmartHome.msg(e);
            }
            String m = msg;
            a.runOnUiThread(() -> {
                Toast.makeText(a, m, Toast.LENGTH_LONG).show();
                if (done != null) done.run();
            });
        }).start();
    }

    // ---------------------------------------------------------------- hub

    static void findHub(Activity a, Prefs p, Runnable done) {
        toast(a, "Ищу хаб…");
        new Thread(() -> {
            List<String[]> found = HubClient.discover(1500);
            a.runOnUiThread(() -> {
                if (found.isEmpty()) {
                    toast(a, "Хаб не найден. Он запущен? Телефон в той же сети?");
                    return;
                }
                pick(a, "Выбери хаб", found, r -> ask(a, "Токен доступа к хабу «" + r[2] + "»", "", false, token -> {
                    p.raw().edit().putString("hub_url", "8780".equals(r[1]) ? r[0] : r[0] + ":" + r[1])
                            .putString("hub_token", token).apply();
                    new Thread(() -> {
                        String msg;
                        try {
                            msg = "Хаб работает: " + new HubClient(p).devices().size() + " плиток";
                        } catch (Exception e) {
                            msg = "Ошибка: " + SmartHome.msg(e);
                        }
                        String m = msg;
                        a.runOnUiThread(() -> {
                            Toast.makeText(a, m, Toast.LENGTH_LONG).show();
                            if (done != null) done.run();
                        });
                    }).start();
                }));
            });
        }).start();
    }

    // ---------------------------------------------------------------- 3D printer

    /** Looks for Bambu Lab printers from the phone and, when a PC is set up, asks it for Bambu Studio's codes. */
    static void findPrinter(Activity a, Prefs p, Runnable done) {
        toast(a, "Ищу принтеры Bambu Lab…");
        new Thread(() -> {
            List<String[]> found = new ArrayList<>();
            for (String[] r : Bambu.discover(a, 4000)) found.add(new String[]{r[0], r[1], r[2], ""});
            if (!p.pcHost().isEmpty()) {
                try {
                    JSONArray pcs = new PcClient(p.pcHost(), p.pcPort(), p.pcToken()).bambu();
                    for (int i = 0; pcs != null && i < pcs.length(); i++) {
                        JSONObject o = pcs.getJSONObject(i);
                        String serial = o.optString("serial");
                        String[] hit = null;
                        for (String[] r : found) if (r[1].equals(serial)) hit = r;
                        if (hit == null) {
                            if (o.optString("ip").isEmpty()) continue;
                            found.add(new String[]{o.optString("ip"), serial, o.optString("name", serial), o.optString("code")});
                        } else if (hit[3].isEmpty()) {
                            hit[3] = o.optString("code");
                        }
                    }
                } catch (Exception ignored) {
                    // PC off or an older DeskDash.exe: the code is typed by hand
                }
            }
            a.runOnUiThread(() -> {
                if (found.isEmpty()) {
                    new AlertDialog.Builder(a).setTitle("Принтер не найден")
                            .setMessage("Принтер включён и в той же сети? Можно ввести адрес вручную: на экране принтера "
                                    + "Настройки → WLAN (IP и код доступа), серийный номер — Настройки → Устройство.")
                            .setPositiveButton("Ввести вручную", (d, w) -> printerForm(a, p, new String[]{"", "", "", ""}, done))
                            .setNegativeButton("Отмена", null).show();
                    return;
                }
                pick(a, "Выбери принтер", found, r -> printerForm(a, p, r, done));
            });
        }).start();
    }

    /** r: {ip, serial, name, code}. */
    static void printerForm(Activity a, Prefs p, String[] r, Runnable done) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText name = field(a, box, "Название", r[2].isEmpty() ? "3D-принтер" : r[2], InputType.TYPE_CLASS_TEXT);
        EditText ip = field(a, box, "IP-адрес", r[0], InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText serial = field(a, box, "Серийный номер", r[1], InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        EditText code = field(a, box, "Код доступа (экран принтера → WLAN)", r[3], InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        new AlertDialog.Builder(a).setTitle("3D-принтер Bambu Lab").setView(box)
                .setPositiveButton("Сохранить", (d, w) -> {
                    Bambu.Config c = new Bambu.Config();
                    c.name = name.getText().toString().trim();
                    c.host = ip.getText().toString().trim();
                    c.serial = serial.getText().toString().trim().toUpperCase(java.util.Locale.ROOT);
                    c.code = code.getText().toString().trim();
                    if (c.host.isEmpty() || c.serial.isEmpty() || c.code.isEmpty()) {
                        toast(a, "Нужны IP, серийный номер и код доступа");
                        return;
                    }
                    List<Bambu.Config> list = Bambu.load(p);
                    for (int i = 0; i < list.size(); i++) if (list.get(i).serial.equals(c.serial)) list.remove(i--);
                    list.add(c);
                    Bambu.save(p, list);
                    toast(a, "Принтер «" + c.name + "» добавлен. Плитка — в «Столы и блоки» → «Умный дом» → «Устройства…»");
                    if (done != null) done.run();
                })
                .setNegativeButton("Отмена", null).show();
    }

    // ---------------------------------------------------------------- helpers

    interface Pick {
        void on(String[] r);
    }

    interface Text {
        void on(String s);
    }

    /** One result: use it; several: let the user choose. r[0] = address, r[2] = name. */
    private static void pick(Activity a, String title, List<String[]> found, Pick p) {
        if (found.size() == 1) {
            p.on(found.get(0));
            return;
        }
        String[] names = new String[found.size()];
        for (int i = 0; i < names.length; i++) names[i] = found.get(i)[2] + "  (" + found.get(i)[0] + ")";
        new AlertDialog.Builder(a).setTitle(title).setItems(names, (d, i) -> p.on(found.get(i))).show();
    }

    static void ask(Activity a, String title, String value, boolean number, Text t) {
        EditText e = new EditText(a);
        e.setText(value);
        e.setSingleLine();
        e.setInputType(number ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout wrap = new LinearLayout(a);
        int pad = Math.round(20 * a.getResources().getDisplayMetrics().density);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(e, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(a).setTitle(title).setView(wrap)
                .setPositiveButton("OK", (d, w) -> {
                    String s = e.getText().toString().trim();
                    if (!s.isEmpty()) t.on(s);
                })
                .setNegativeButton("Отмена", null).show();
    }

    private static EditText field(Activity a, LinearLayout box, String hint, String value, int type) {
        EditText e = new EditText(a);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine();
        e.setInputType(type);
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    private static void toast(Activity a, String s) {
        Toast.makeText(a, s, Toast.LENGTH_LONG).show();
    }
}

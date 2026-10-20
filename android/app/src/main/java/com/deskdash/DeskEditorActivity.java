package com.deskdash;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Desks of the active profile: add / rename / reorder desks, columns and blocks, pick smart home devices. */
public class DeskEditorActivity extends Activity {
    private static final int ACCENT = 0xFFC8A27C, TEXT = 0xFFF1E6DA, DIM = 0xFF9C8B7E, SURFACE = 0xFF231B17, BG = 0xFF17120F;

    private Prefs prefs;
    private JSONArray desks;
    private int sel;
    private LinearLayout root;
    private float dp;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        dp = getResources().getDisplayMetrics().density;
        desks = Desks.load(prefs);
        sel = Math.max(0, Math.min(prefs.deskIndex(), desks.length() - 1));
        setTitle("Столы · " + Profiles.name(this, Profiles.active(this)));
        ScrollView sv = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(20), px(8), px(20), px(40));
        sv.addView(root);
        setContentView(sv);
        render();
    }

    /** The profile may have been switched in the settings meanwhile: show the active one. */
    @Override
    protected void onRestart() {
        super.onRestart();
        recreate();
    }

    private JSONObject desk() {
        return desks.optJSONObject(sel);
    }

    private JSONArray cols() {
        JSONArray c = desk().optJSONArray("cols");
        if (c == null) {
            c = new JSONArray();
            put(desk(), "cols", c);
        }
        return c;
    }

    private void save() {
        Desks.save(prefs, desks);
        prefs.setDeskIndex(sel);
        render();
    }

    // ---------------------------------------------------------------- UI

    private void render() {
        root.removeAllViews();
        section("Столы");
        LinearLayout tabs = row();
        for (int i = 0; i < desks.length(); i++) {
            int idx = i;
            String name = desks.optJSONObject(i).optString("name", "Стол " + (i + 1));
            tabs.addView(chip(name, i == sel, v -> {
                sel = idx;
                save();
            }), chipLp());
        }
        tabs.addView(chip("+ Стол", false, v -> addDesk()), chipLp());
        root.addView(scrollRow(tabs));

        LinearLayout acts = row();
        acts.addView(chip("Переименовать", false, v -> ask("Название стола", desk().optString("name"), s -> {
            put(desk(), "name", s);
            save();
        })), chipLp());
        if (sel > 0) acts.addView(chip("← Раньше", false, v -> moveDesk(-1)), chipLp());
        if (sel < desks.length() - 1) acts.addView(chip("Позже →", false, v -> moveDesk(1)), chipLp());
        if (desks.length() > 1) acts.addView(chip("Удалить стол", false, v -> confirm("Удалить стол «" + desk().optString("name") + "»?", () -> {
            List<Object> l = Desks.toList(desks);
            l.remove(sel);
            desks = Desks.fromList(l);
            sel = Math.max(0, sel - 1);
            save();
        })), chipLp());
        root.addView(scrollRow(acts), lp(8, 0));
        note("На экране столы листаются свайпом влево/вправо по свободному месту или точками внизу. "
                + "Там же кнопка ⋮: столы, профиль и настройки.");

        section("Колонки и блоки");
        LinearLayout colsRow = row();
        colsRow.setGravity(Gravity.TOP);
        JSONArray cols = cols();
        for (int i = 0; i < cols.length(); i++) colsRow.addView(columnCard(i));
        TextView addCol = chip("+ Колонка", false, v -> {
            try {
                cols().put(Desks.column(40));
            } catch (JSONException ignored) {
                // literal values
            }
            save();
        });
        colsRow.addView(addCol, chipLp());
        root.addView(scrollRow(colsRow));
        note("Ширина колонки — её доля экрана. Высота блока: «авто» — по содержимому, ×1…×3 — доля свободного места в колонке. "
                + "Блок «Умный дом» можно ставить несколько раз, например «Кухня» и «Спальня».");
    }

    private View columnCard(int ci) {
        JSONObject col = cols().optJSONObject(ci);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(px(12), px(10), px(12), px(12));
        card.setBackground(rounded(SURFACE, 16));

        TextView h = text("Колонка " + (ci + 1) + " · ширина " + col.optInt("w", 50), 14, ACCENT);
        h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(h);
        LinearLayout ctl = row();
        ctl.addView(small("−", v -> setWidth(col, -5)));
        ctl.addView(small("+", v -> setWidth(col, 5)));
        if (ci > 0) ctl.addView(small("←", v -> moveCol(ci, -1)));
        if (ci < cols().length() - 1) ctl.addView(small("→", v -> moveCol(ci, 1)));
        if (cols().length() > 1) ctl.addView(small("✕", v -> confirm("Удалить колонку " + (ci + 1) + " со всеми блоками?", () -> {
            List<Object> l = Desks.toList(cols());
            l.remove(ci);
            put(desk(), "cols", Desks.fromList(l));
            save();
        })));
        card.addView(ctl, lp(4, 8));

        JSONArray blocks = blocks(col);
        for (int bi = 0; bi < blocks.length(); bi++) card.addView(blockCard(ci, bi), lp(0, 8));
        if (blocks.length() == 0) card.addView(text("Пусто", 13, DIM), lp(0, 8));
        card.addView(chip("+ Блок", false, v -> addBlock(col)));
        LinearLayout.LayoutParams outer = new LinearLayout.LayoutParams(px(250), -2);
        outer.rightMargin = px(10);
        card.setLayoutParams(outer);
        return card;
    }

    private View blockCard(int ci, int bi) {
        JSONObject col = cols().optJSONObject(ci);
        JSONObject b = blocks(col).optJSONObject(bi);
        String t = b.optString("t");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(10), px(8), px(10), px(8));
        box.setBackground(rounded(BG, 12));

        String title = Desks.typeName(t);
        if ("home".equals(t)) {
            String bt = b.optString("title");
            if (!bt.isEmpty()) title += " · " + bt;
            title += " (" + Desks.items(b).size() + ")";
        }
        box.addView(text(title, 15, TEXT));
        int hgt = b.optInt("h", Desks.defaultHeight(t));
        if (hgt <= 0 && Desks.needsHeight(t)) hgt = 1;
        box.addView(text("Высота: " + (hgt <= 0 ? "авто" : "×" + hgt), 12, DIM));

        LinearLayout ctl = row();
        if (bi > 0) ctl.addView(small("↑", v -> moveBlock(ci, bi, -1)));
        if (bi < blocks(col).length() - 1) ctl.addView(small("↓", v -> moveBlock(ci, bi, 1)));
        if (ci > 0) ctl.addView(small("←", v -> shiftBlock(ci, bi, -1)));
        if (ci < cols().length() - 1) ctl.addView(small("→", v -> shiftBlock(ci, bi, 1)));
        ctl.addView(small("↕", v -> {
            int cur = b.optInt("h", Desks.defaultHeight(t));
            int next = cur >= 3 ? (Desks.needsHeight(t) ? 1 : 0) : cur + 1;
            put(b, "h", next);
            save();
        }));
        ctl.addView(small("✕", v -> {
            List<Object> l = Desks.toList(blocks(col));
            l.remove(bi);
            put(col, "blocks", Desks.fromList(l));
            save();
        }));
        box.addView(ctl, lp(4, 0));

        if ("home".equals(t)) {
            LinearLayout hc = row();
            hc.addView(chip("Устройства…", false, v -> pickDevices(b)), chipLp());
            hc.addView(chip("Имя…", false, v -> ask("Заголовок блока (можно пустой)", b.optString("title"), s -> {
                put(b, "title", s);
                save();
            })), chipLp());
            box.addView(hc, lp(6, 0));
        }
        return box;
    }

    // ---------------------------------------------------------------- changes

    private void addDesk() {
        desks.put(Desks.newDesk("Стол " + (desks.length() + 1)));
        sel = desks.length() - 1;
        save();
        Toast.makeText(this, "Новый стол: часы и умный дом. Поменяй как хочешь.", Toast.LENGTH_SHORT).show();
    }

    private void moveDesk(int dir) {
        List<Object> l = Desks.toList(desks);
        Object d = l.remove(sel);
        sel += dir;
        l.add(sel, d);
        desks = Desks.fromList(l);
        save();
    }

    private void setWidth(JSONObject col, int delta) {
        put(col, "w", Math.max(10, Math.min(90, col.optInt("w", 50) + delta)));
        save();
    }

    private void moveCol(int ci, int dir) {
        List<Object> l = Desks.toList(cols());
        Object c = l.remove(ci);
        l.add(ci + dir, c);
        put(desk(), "cols", Desks.fromList(l));
        save();
    }

    private void moveBlock(int ci, int bi, int dir) {
        JSONObject col = cols().optJSONObject(ci);
        List<Object> l = Desks.toList(blocks(col));
        Object b = l.remove(bi);
        l.add(bi + dir, b);
        put(col, "blocks", Desks.fromList(l));
        save();
    }

    private void shiftBlock(int ci, int bi, int dir) {
        JSONObject from = cols().optJSONObject(ci), to = cols().optJSONObject(ci + dir);
        List<Object> l = Desks.toList(blocks(from));
        Object b = l.remove(bi);
        put(from, "blocks", Desks.fromList(l));
        blocks(to).put(b);
        save();
    }

    private void addBlock(JSONObject col) {
        Set<String> used = new HashSet<>();
        JSONArray cols = cols();
        for (int i = 0; i < cols.length(); i++) {
            JSONArray bl = blocks(cols.optJSONObject(i));
            for (int j = 0; j < bl.length(); j++) used.add(bl.optJSONObject(j).optString("t"));
        }
        List<String[]> free = new ArrayList<>();
        for (String[] t : Desks.TYPES) if (!Desks.single(t[0]) || !used.contains(t[0])) free.add(t);
        String[] names = new String[free.size()];
        for (int i = 0; i < names.length; i++) names[i] = free.get(i)[1];
        new AlertDialog.Builder(this).setTitle("Какой блок добавить?").setItems(names, (d, i) -> {
            try {
                JSONObject b = Desks.block(free.get(i)[0]);
                blocks(col).put(b);
                save();
                if ("home".equals(free.get(i)[0]) && SmartHome.configured(prefs)) pickDevices(b);
            } catch (JSONException ignored) {
                // literal values
            }
        }).show();
    }

    /** Loads everything from Home Assistant and Yandex and lets the user tick what goes into the block. */
    private void pickDevices(JSONObject block) {
        if (!SmartHome.configured(prefs)) {
            Toast.makeText(this, "Сначала подключи Home Assistant или Яндекс в настройках → «Умный дом»", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Загружаю устройства…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            StringBuilder errors = new StringBuilder();
            List<HomeDevice> all = SmartHome.listAll(prefs, errors);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (all.isEmpty()) {
                    new AlertDialog.Builder(this).setTitle("Устройства не найдены")
                            .setMessage(errors.length() > 0 ? errors.toString().trim() : "Список пуст")
                            .setPositiveButton("OK", null).show();
                    return;
                }
                if (errors.length() > 0) Toast.makeText(this, errors.toString().trim(), Toast.LENGTH_LONG).show();
                showPicker(block, all);
            });
        }).start();
    }

    private void showPicker(JSONObject block, List<HomeDevice> all) {
        Map<String, String> chosen = new LinkedHashMap<>();
        for (String[] it : Desks.items(block)) chosen.put(it[0], it[1]);
        String[] labels = new String[all.size()];
        boolean[] checked = new boolean[all.size()];
        for (int i = 0; i < labels.length; i++) {
            HomeDevice d = all.get(i);
            String where = (d.room.isEmpty() ? "" : d.room + " · ") + ("ha".equals(d.source) ? "HA" : "Яндекс");
            labels[i] = d.name + "  —  " + where;
            checked[i] = chosen.containsKey(d.id);
        }
        new AlertDialog.Builder(this).setTitle("Что показывать")
                .setMultiChoiceItems(labels, checked, (d, i, on) -> checked[i] = on)
                .setPositiveButton("Готово", (d, w) -> {
                    Map<String, String> picked = new LinkedHashMap<>();
                    // keep the old order for things that stay, new ones go to the end
                    for (Map.Entry<String, String> e : chosen.entrySet()) {
                        for (int i = 0; i < all.size(); i++) {
                            if (checked[i] && all.get(i).id.equals(e.getKey())) picked.put(e.getKey(), all.get(i).name);
                        }
                    }
                    for (int i = 0; i < all.size(); i++) {
                        if (checked[i] && !picked.containsKey(all.get(i).id)) picked.put(all.get(i).id, all.get(i).name);
                    }
                    JSONArray items = new JSONArray();
                    try {
                        for (Map.Entry<String, String> e : picked.entrySet()) {
                            items.put(new JSONObject().put("id", e.getKey()).put("n", e.getValue()));
                        }
                    } catch (JSONException ignored) {
                        // strings only
                    }
                    put(block, "items", items);
                    save();
                })
                .setNegativeButton("Отмена", null).show();
    }

    // ---------------------------------------------------------------- dialogs

    private interface TextResult {
        void accept(String s);
    }

    private void ask(String title, String value, TextResult r) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setSingleLine();
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setSelection(e.getText().length());
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(px(20), px(8), px(20), 0);
        wrap.addView(e, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle(title).setView(wrap)
                .setPositiveButton("OK", (d, w) -> r.accept(e.getText().toString().trim()))
                .setNegativeButton("Отмена", null).show();
    }

    private void confirm(String msg, Runnable yes) {
        new AlertDialog.Builder(this).setMessage(msg)
                .setPositiveButton("Удалить", (d, w) -> yes.run())
                .setNegativeButton("Отмена", null).show();
    }

    // ---------------------------------------------------------------- helpers

    private static JSONArray blocks(JSONObject col) {
        JSONArray b = col.optJSONArray("blocks");
        if (b == null) {
            b = new JSONArray();
            put(col, "blocks", b);
        }
        return b;
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (JSONException ignored) {
            // only valid values are put
        }
    }

    private void section(String title) {
        TextView t = text(title, 13, ACCENT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setLetterSpacing(0.12f);
        t.setAllCaps(true);
        root.addView(t, lp(24, 8));
    }

    private void note(String s) {
        TextView t = text(s, 13, DIM);
        t.setLineSpacing(0, 1.15f);
        root.addView(t, lp(8, 4));
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private View scrollRow(LinearLayout row) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row);
        return hs;
    }

    private TextView chip(String label, boolean selected, View.OnClickListener l) {
        TextView t = text(label, 14, selected ? BG : TEXT);
        t.setSingleLine();
        t.setPadding(px(14), px(9), px(14), px(9));
        t.setBackground(rounded(selected ? ACCENT : SURFACE, 14));
        if (!selected) {
            GradientDrawable g = rounded(SURFACE, 14);
            g.setStroke(Math.max(1, (int) dp), 0xFF3A2E28);
            t.setBackground(g);
        }
        t.setOnClickListener(l);
        return t;
    }

    private TextView small(String label, View.OnClickListener l) {
        TextView t = text(label, 16, TEXT);
        t.setGravity(Gravity.CENTER);
        t.setBackground(rounded(0xFF2E2420, 10));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(34), px(34));
        lp.rightMargin = px(6);
        t.setLayoutParams(lp);
        return t;
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = px(8);
        return lp;
    }

    private LinearLayout.LayoutParams lp(float top, float bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, px(top), 0, px(bottom));
        return lp;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp * dp);
        return g;
    }

    private int px(float v) {
        return Math.round(v * dp);
    }
}

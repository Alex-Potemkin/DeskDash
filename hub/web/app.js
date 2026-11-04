"use strict";
// DeskDash Дом: the phone side of the hub. Plain JS, no build step.

const KIND = { SWITCH: 0, LIGHT: 1, CLIMATE: 2, SENSOR: 3, SCENE: 4, COVER: 5, LOCK: 6, FAN: 7, MEDIA: 8,
  PRINTER: 9, VACUUM: 10, CAMERA: 11, APPLIANCE: 12 };
const ICON = ["⏻", "✹", "♨", "◉", "✦", "▤", "⚿", "✇", "♫", "⎙", "◍", "◫", "◎"];
const $ = (s, el = document) => el.querySelector(s);

let token = "";
let me = { admin: false };
let state = null;
let tab = store("dd_tab") || "home";
let search = "";
let sheetFor = null;          // device id whose card is open
let settingsDraft = null;     // config being edited on the settings tab
const busy = new Set();

function store(k, v) {
  try {
    if (v === undefined) return localStorage.getItem(k);
    if (v === null) localStorage.removeItem(k); else localStorage.setItem(k, v);
  } catch (e) { /* private mode */ }
  return null;
}

function esc(s) {
  return String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

function h(tag, attrs = {}, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
    else if (k === "html") el.innerHTML = v;
    else if (v === true) el.setAttribute(k, "");
    else if (v !== false && v != null) el.setAttribute(k, v);
  }
  for (const k of kids.flat()) if (k != null && k !== false) el.append(k.nodeType ? k : document.createTextNode(k));
  return el;
}

function toast(msg) {
  const t = $("#toast");
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toast.t);
  toast.t = setTimeout(() => (t.hidden = true), 2600);
}

async function api(path, body) {
  const r = await fetch(path, {
    method: body === undefined ? "GET" : "POST",
    headers: { "X-Token": token, ...(body === undefined ? {} : { "Content-Type": "application/json" }) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (r.status === 401) { logout(); throw new Error("неверный токен"); }
  const o = await r.json();
  if (o.ok === false) throw new Error(o.error || "ошибка");
  return o;
}

// ---------------------------------------------------------------- login and live updates

async function start() {
  const m = location.hash.match(/t=([^&]+)/);
  if (m) {
    store("dd_token", decodeURIComponent(m[1]));
    history.replaceState(null, "", location.pathname);
  }
  token = store("dd_token") || "";
  if (!token) return showLogin();
  try {
    me = await api("/api/me");
  } catch (e) {
    return showLogin(e.message);
  }
  $("#login").hidden = true;
  $("#app").hidden = false;
  $("#tab-settings").hidden = $("#tab-files").hidden = !me.admin;
  if ((tab === "settings" || tab === "files") && !me.admin) tab = "home";
  selectTab(tab);
  connect();
}

function showLogin(err) {
  $("#app").hidden = true;
  $("#login").hidden = false;
  $("#login-err").textContent = err && err !== "неверный токен" ? err : err ? "Токен не подошёл" : "";
}

function logout() {
  store("dd_token", null);
  token = "";
  if (window.es) window.es.close();
  showLogin("неверный токен");
}

$("#login-go").onclick = () => {
  store("dd_token", $("#login-token").value.trim());
  start();
};
$("#login-token").onkeydown = e => { if (e.key === "Enter") $("#login-go").click(); };
$("#refresh").onclick = () => reload();

function connect() {
  if (window.es) window.es.close();
  const es = new EventSource("/api/events?token=" + encodeURIComponent(token));
  window.es = es;
  es.onmessage = e => setState(JSON.parse(e.data));
  es.onerror = () => {
    setStatus("нет связи с хабом, переподключаюсь…");
    es.close();
    clearTimeout(connect.t);
    connect.t = setTimeout(connect, 3000);
  };
}

async function reload() {
  try { setState(await api("/api/state")); } catch (e) { toast(e.message); }
}

function setStatus(s) { $("#status").textContent = s; }

function setState(s) {
  state = s;
  document.title = s.name;
  $("#hub-name").textContent = s.name;
  const sys = s.system || {};
  setStatus([sys.temp != null ? `Pi ${sys.temp}°` : "", sys.cpu != null ? `ЦП ${sys.cpu}%` : "",
    `обновлено ${new Date().toLocaleTimeString("ru", { hour: "2-digit", minute: "2-digit" })}`].filter(Boolean).join(" · "));
  const errs = $("#errors");
  errs.innerHTML = "";
  const names = { ha: "Home Assistant", ya: "Яндекс" };
  for (const [k, v] of Object.entries(s.errors || {})) errs.append(h("div", { class: "card small" }, `${names[k] || k}: ${v}`));
  if (tab !== "settings") render();
  if (sheetFor) {
    const d = dev(sheetFor);
    if (d && !document.activeElement?.matches?.("#sheet input")) openSheet(d);
  }
}

const dev = id => state?.devices.find(d => d.id === id);

// ---------------------------------------------------------------- tabs

for (const b of document.querySelectorAll("#tabs button")) b.onclick = () => selectTab(b.dataset.tab);

function selectTab(t) {
  tab = t;
  store("dd_tab", t);
  for (const b of document.querySelectorAll("#tabs button")) b.classList.toggle("on", b.dataset.tab === t);
  if (t === "settings") settingsDraft = null;
  render();
  window.scrollTo(0, 0);
}

function render() {
  const v = $("#view");
  if (tab === "settings") return renderSettings(v);
  if (tab === "files") {  // lives on its own: a state update mustn't close the open folder
    if (!$("#files")) { v.innerHTML = ""; renderFiles(v); }
    return;
  }
  if (!state) { v.innerHTML = '<div class="empty">Загрузка…</div>'; return; }
  const list = $("#home-list");
  if (tab === "home" && list) return renderHomeList(list);  // keep the search box and its focus
  v.innerHTML = "";
  ({ home: renderHome, scenes: renderScenes, pcs: renderPcs, nas: renderNas }[tab] || renderHome)(v);
}

// ---------------------------------------------------------------- home

const favs = () => JSON.parse(store("dd_favs") || "[]");

function toggleFav(id) {
  const f = favs();
  store("dd_favs", JSON.stringify(f.includes(id) ? f.filter(x => x !== id) : [...f, id]));
  render();
}

function renderHome(v) {
  const box = h("input", { placeholder: "Поиск устройств", value: search, type: "search",
    oninput: e => { search = e.target.value; renderHomeList(list); } });
  const list = h("div", { id: "home-list" });
  v.append(box, list);
  renderHomeList(list);
}

function renderHomeList(list) {
  list.innerHTML = "";
  const q = search.trim().toLowerCase();
  const all = state.devices.filter(d => d.source === "ha" || d.source === "ya");
  const f = favs();
  if (!q && f.length) {
    const fd = f.map(dev).filter(Boolean);
    if (fd.length) list.append(h("h2", {}, "Избранное"), grid(fd));
  }
  const match = all.filter(d => !q || (d.name + " " + d.room).toLowerCase().includes(q));
  const rooms = new Map();
  for (const d of match.filter(d => d.kind !== KIND.SCENE)) {
    const r = d.room || "Без комнаты";
    if (!rooms.has(r)) rooms.set(r, []);
    rooms.get(r).push(d);
  }
  const order = [...rooms.keys()].sort((a, b) => (a === "Без комнаты") - (b === "Без комнаты") || a.localeCompare(b, "ru"));
  for (const r of order) {
    const ds = rooms.get(r).sort((a, b) => (a.kind === KIND.SENSOR) - (b.kind === KIND.SENSOR) || a.name.localeCompare(b.name, "ru"));
    list.append(h("h2", {}, r), grid(ds));
  }
  if (!match.length) {
    const c = state.configured;
    list.append(h("div", { class: "empty" }, q ? "Ничего не нашлось" :
      c.ha || c.ya ? "Устройства загружаются…" : "Умный дом не подключён. " + (me.admin ? "Открой «Настройки»." : "Попроси администратора подключить Home Assistant или Яндекс.")));
  }
}

function grid(ds) {
  return h("div", { class: "grid" }, ds.map(tile));
}

function status(d) {
  if (!d.available) return "Недоступно";
  switch (d.kind) {
    case KIND.SCENE: return "Запустить";
    case KIND.SENSOR: return d.state || "—";
    case KIND.CLIMATE: {
      const p = [];
      if (d.current != null) p.push(deg(d.current));
      if (d.target != null && (d.on || !d.canToggle)) p.push((p.length ? "→ " : "") + deg(d.target));
      if (d.canToggle && !d.on) p.push("выкл");
      return p.join(" ") || (d.on ? "Вкл" : "Выкл");
    }
    case KIND.LIGHT: return d.on ? (d.brightness >= 0 ? d.brightness + "%" : "Вкл") : "Выкл";
    case KIND.COVER: return d.on ? "Открыто" : "Закрыто";
    case KIND.LOCK: return d.on ? "Заперто" : "Открыто";
    case KIND.MEDIA: return d.state || (d.on ? "Играет" : "Пауза");
    case KIND.PRINTER:
    case KIND.APPLIANCE: {
      if (d.progress < 0 || !d.on) return d.state || "Ожидание";
      const s = d.progress + "%" + (d.remainingMin >= 0 ? " · " + minutes(d.remainingMin) : "");
      return d.state === "Пауза" ? "Пауза · " + s : s;
    }
    case KIND.VACUUM: return d.state || "—";
    default: return (d.on ? "Вкл" : "Выкл") + (d.state ? " · " + d.state : "");
  }
}

const deg = v => (Math.round(v * 10) / 10) + "°";
const minutes = m => { const h = Math.floor(m / 60), r = m % 60; return h && r ? `${h} ч ${r} мин` : h ? `${h} ч` : `${r} мин`; };
const lit = d => d.available && d.kind !== KIND.SENSOR && d.kind !== KIND.SCENE && d.on;

function tile(d) {
  const t = h("button", { class: "tile" + (lit(d) ? " on" : "") + (d.available ? "" : " off-line") + (busy.has(d.id) ? " busy" : "") },
    h("div", { class: "ico" }, d.source === "pc" && d.kind === KIND.SWITCH ? "▭" : ICON[d.kind] || "•"),
    favs().includes(d.id) ? h("span", { class: "star" }, "★") : null,
    h("div", {}, h("div", { class: "name" }, d.name), h("div", { class: "st" }, status(d))));
  let timer = 0, long = false;
  const down = () => { long = false; timer = setTimeout(() => { long = true; openSheet(d); }, 480); };
  const up = () => clearTimeout(timer);
  t.addEventListener("pointerdown", down);
  t.addEventListener("pointerup", up);
  t.addEventListener("pointerleave", up);
  t.addEventListener("contextmenu", e => e.preventDefault());
  t.onclick = () => { if (!long) tap(d); };
  return t;
}

function tap(d) {
  if (!d.available) return;
  if (d.kind === KIND.SCENE) return act(d, "run", null, `Запускаю «${d.name}»`);
  if (d.canToggle && d.kind !== KIND.LOCK && d.kind !== KIND.CLIMATE) return act(d, d.on ? "off" : "on");
  openSheet(d);
}

async function act(d, action, value, msg) {
  if (msg) toast(msg);
  busy.add(d.id);
  if (action === "on" || action === "off") d.on = action === "on";   // show it at once
  if (action === "brightness") Object.assign(d, { brightness: value, on: true });
  render();
  try {
    await api("/api/device/" + encodeURIComponent(d.id), { action, value });
  } catch (e) {
    toast(`${d.name}: ${e.message}`);
    reload();
  } finally {
    busy.delete(d.id);
    render();
  }
}

// ---------------------------------------------------------------- device card

function closeSheet() {
  sheetFor = null;
  $("#sheet").hidden = $("#sheet-bg").hidden = true;
}
$("#sheet-bg").onclick = closeSheet;

function openSheet(d) {
  sheetFor = d.id;
  const s = $("#sheet");
  s.innerHTML = "";
  s.append(h("div", { class: "grip" }), h("h3", {}, d.name),
    h("div", { class: "muted" }, [d.room, status(d)].filter(Boolean).join(" · ")));
  const words = { [KIND.COVER]: ["Открыть", "Закрыть"], [KIND.LOCK]: ["Запереть", "Отпереть"], [KIND.MEDIA]: ["Играть", "Пауза"],
    [KIND.VACUUM]: ["Убрать", "На базу"] }[d.kind]
    || ["Включить", "Выключить"];
  const btns = h("div", { class: "btns" });
  if (d.kind === KIND.SCENE) btns.append(h("button", { class: "primary", onclick: () => { act(d, "run", null, `Запускаю «${d.name}»`); closeSheet(); } }, "Запустить"));
  else if (d.canToggle) btns.append(
    h("button", { class: d.on ? "ghost" : "primary", onclick: () => act(d, "on") }, words[0]),
    h("button", { class: d.on ? "primary" : "ghost", onclick: () => act(d, "off") }, words[1]));
  s.append(btns);
  if (d.job || d.detail) s.append(h("div", { class: "muted", style: "white-space:pre-line;margin-top:8px" }, [d.job, d.detail].filter(Boolean).join("\n")));
  const cmds = [["resume", "Продолжить"], ["pause", "Пауза"], ["stop", "Остановить"], ["light", "Подсветка"]].filter(([k]) => d.cmds && d.cmds[k]);
  if (cmds.length) s.append(h("div", { class: "btns" }, cmds.map(([k, label]) =>
    h("button", { class: "ghost", onclick: () => { if (k !== "stop" || confirm("Остановить?")) { act(d, k); closeSheet(); } } }, label))));

  if (d.dimmable) {
    s.append(h("label", { class: "f" }, "Яркость"),
      h("input", { type: "range", min: 1, max: 100, value: d.brightness > 0 ? d.brightness : 100,
        onchange: e => act(d, "brightness", +e.target.value) }));
  }
  if (d.colorTemp) {
    s.append(h("label", { class: "f" }, "Тёплый — холодный"),
      h("input", { type: "range", class: "ct", min: d.minK, max: d.maxK, step: 50, value: d.tempK > 0 ? d.tempK : Math.round((d.minK + d.maxK) / 2),
        onchange: e => act(d, "color_temp", +e.target.value) }));
  }
  if (d.color) {
    const sw = h("div", { class: "swatches" });
    for (const c of ["#FF3B30", "#FF9500", "#FFCC00", "#34C759", "#00C7BE", "#007AFF", "#5856D6", "#AF52DE", "#FF2D55", "#FFFFFF"]) {
      sw.append(h("button", { style: `background:${c}`, onclick: () => act(d, "color", parseInt(c.slice(1), 16)) }));
    }
    s.append(h("label", { class: "f" }, "Цвет"), sw);
  }
  if (d.kind === KIND.CLIMATE && d.target != null) {
    let t = d.target;
    const val = h("b", {}, deg(t));
    let timer = 0;
    const step = k => {
      t = Math.min(d.tMax, Math.max(d.tMin, Math.round((t + k * d.tStep) * 10) / 10));
      val.textContent = deg(t);
      clearTimeout(timer);
      timer = setTimeout(() => act(d, "temperature", t), 700);
    };
    s.append(h("div", { class: "thermo" }, h("button", { onclick: () => step(-1) }, "−"), val, h("button", { onclick: () => step(1) }, "+")),
      d.current != null ? h("div", { class: "muted", style: "text-align:center" }, `Сейчас ${deg(d.current)}`) : null);
  }
  const fav = favs().includes(d.id);
  s.append(h("div", { class: "btns" }, h("button", { class: "ghost", onclick: () => { toggleFav(d.id); openSheet(dev(d.id) || d); } },
    fav ? "★ Убрать из избранного" : "☆ В избранное")));
  s.hidden = $("#sheet-bg").hidden = false;
}

// ---------------------------------------------------------------- scenes

function renderScenes(v) {
  const hub = state.devices.filter(d => d.source === "hub");
  v.append(h("h2", {}, "Сценарии хаба"));
  if (hub.length) v.append(grid(hub));
  else v.append(h("div", { class: "card muted" }, "Сценарии хаба собирают действия из всех систем: свет из Home Assistant, " +
    "лампы Яндекса, макросы ПК и его пробуждение. " + (me.admin ? "Создаются в «Настройки → Сценарии»." : "")));
  const other = state.devices.filter(d => (d.source === "ha" || d.source === "ya") && d.kind === KIND.SCENE);
  const by = { ha: "Home Assistant", ya: "Яндекс" };
  for (const src of ["ya", "ha"]) {
    const l = other.filter(d => d.source === src);
    if (l.length) v.append(h("h2", {}, by[src]), grid(l));
  }
}

// ---------------------------------------------------------------- computers

function fmtUptime(s) {
  if (!s) return "";
  const d = Math.floor(s / 86400), hh = Math.floor(s % 86400 / 3600), m = Math.floor(s % 3600 / 60);
  return d ? `${d} д ${hh} ч` : hh ? `${hh} ч ${m} мин` : `${m} мин`;
}

function renderPcs(v) {
  if (!state.pcs.length) {
    v.append(h("div", { class: "empty" }, "Компьютеров нет. " + (me.admin ? "Добавь в «Настройки → Компьютеры» (на ПК должен работать DeskDash)." : "")));
    return;
  }
  for (const p of state.pcs) {
    const st = p.stats || {};
    const c = h("div", { class: "card" },
      h("div", { class: "row" },
        h("div", {}, h("div", { style: "font-weight:600;font-size:17px" }, h("span", { class: "dot" + (p.online ? " ok" : "") }), p.name),
          h("div", { class: "muted small" }, p.online
            ? [`ЦП ${Math.round(st.cpu || 0)}%`, `ОЗУ ${st.ram || 0}%`, st.battery != null && st.battery >= 0 ? `батарея ${st.battery}%` : "", fmtUptime(st.uptime)].filter(Boolean).join(" · ")
            : p.error ? `не в сети (${p.error})` : "не в сети")),
        !p.online && p.canWake ? h("button", { class: "primary", onclick: () => pcDo(p, "pc:" + p.id, "on", `Бужу ${p.name}`) }, "Разбудить") : null));
    if (p.online && p.macros.length) {
      c.append(h("div", { class: "chips" }, p.macros.map(m => h("button", { class: "chip",
        onclick: () => {
          if (m.confirm && !confirm(`${m.label} на ${p.name}?`)) return;
          pcDo(p, `pc:${p.id}:${m.id}`, "run", `${m.label} → ${p.name}`);
        } }, m.label))));
    }
    v.append(c);
  }
}

async function pcDo(p, id, action, msg) {
  toast(msg);
  try { await api("/api/device/" + encodeURIComponent(id), { action }); } catch (e) { toast(`${p.name}: ${e.message}`); }
}

// ---------------------------------------------------------------- server

function size(b) {
  const u = ["Б", "КБ", "МБ", "ГБ", "ТБ"];
  let i = 0;
  while (b >= 1024 && i < u.length - 1) { b /= 1024; i++; }
  return (b >= 100 || i === 0 ? Math.round(b) : b.toFixed(1)) + " " + u[i];
}

function renderNas(v) {
  const s = state.system || {};
  const stat = (label, val) => val == null ? null : h("div", { class: "stat" }, h("span", { class: "muted small" }, label), h("b", {}, val));
  v.append(h("h2", {}, s.host || "Сервер"),
    h("div", { class: "stats" }, stat("Процессор", s.cpu != null ? s.cpu + "%" : null), stat("Температура", s.temp != null ? s.temp + "°" : null),
      stat("Память", s.mem != null ? s.mem + "%" : null), stat("Работает", s.uptime ? fmtUptime(s.uptime) : null)));
  if (s.temp >= 75) v.append(h("div", { class: "card err small", style: "margin-top:10px" }, "Pi перегревается: нужен радиатор или вентилятор."));
  v.append(h("h2", {}, "Диски"));
  for (const d of s.disks || []) {
    if (d.missing) { v.append(h("div", { class: "card" }, h("b", {}, d.path), h("div", { class: "err small" }, "не подключён"))); continue; }
    const pct = Math.round(100 * d.used / d.total);
    v.append(h("div", { class: "card" }, h("div", { class: "row" }, h("b", {}, d.name), h("span", { class: "muted small" }, d.path)),
      h("div", { class: "bar" + (pct >= 90 ? " warn" : "") }, h("div", { style: `width:${pct}%` })),
      h("div", { class: "muted small" }, `свободно ${size(d.free)} из ${size(d.total)}`)));
  }
  if (!(s.disks || []).length) v.append(h("div", { class: "card muted" }, "Папки NAS не указаны."));
}

// ---------------------------------------------------------------- NAS files (admin)

let filesPath = "";

const fileUrl = (p, dl) => `/api/file?p=${encodeURIComponent(p)}&token=${encodeURIComponent(token)}${dl ? "&dl=1" : ""}`;

function renderFiles(v) {
  const box = h("div", { id: "files" });
  v.append(box);
  const join = name => (filesPath ? filesPath + "/" : "") + name;
  const open = p => { filesPath = p; draw(); };
  const act = async (path, body, done) => {
    try { await api(path, body); if (done) toast(done); draw(); } catch (e) { toast(e.message); }
  };

  const upload = list => {
    const queue = [...list];
    const line = h("div", { class: "hint" });
    box.prepend(line);
    const next = () => {
      const f = queue.shift();
      if (!f) { toast("Загружено"); return draw(); }
      const x = new XMLHttpRequest();
      x.open("POST", `/api/file?p=${encodeURIComponent(filesPath)}&name=${encodeURIComponent(f.name)}`);
      x.setRequestHeader("X-Token", token);
      x.setRequestHeader("Content-Type", "application/octet-stream");
      x.upload.onprogress = e => { line.textContent = `${f.name}: ${Math.round(100 * e.loaded / (e.total || 1))} %`; };
      x.onload = () => {
        let o = {};
        try { o = JSON.parse(x.responseText); } catch (e) { /* the message below */ }
        if (o.ok) return next();
        toast(`${f.name}: ${o.error || "не загрузился"}`);
        draw();
      };
      x.onerror = () => { toast(`${f.name}: связь оборвалась`); draw(); };
      x.send(f);
    };
    next();
  };

  const row = it => {
    const p = join(it.name);
    const tap = () => it.dir ? open(p) : window.open(fileUrl(p), "_blank");
    const info = it.disk ? `свободно ${size(it.free)} из ${size(it.total)}` : it.dir ? "" : size(it.size);
    return h("div", { class: "file" },
      h("button", { class: "file-open", onclick: tap },
        h("span", { class: "file-ico" }, it.disk ? "◫" : it.dir ? "▣" : "▤"),
        h("span", { class: "file-name" }, it.name, info ? h("small", { class: "muted" }, info) : null)),
      it.disk ? null : h("div", { class: "file-btns" },
        it.dir ? null : h("a", { class: "chip", href: fileUrl(p, true), download: it.name, title: "Скачать" }, "↓"),
        h("button", { class: "chip", title: "Переименовать", onclick: () => {
          const name = prompt("Новое имя", it.name);
          if (name && name !== it.name) act("/api/files/rename", { p, name });
        } }, "✎"),
        h("button", { class: "chip", title: "Удалить", onclick: () => {
          if (confirm(`Удалить «${it.name}» навсегда?` + (it.dir ? " Вместе со всем, что внутри." : ""))) act("/api/files/delete", { p }, "Удалено");
        } }, "✕")));
  };

  async function draw() {
    box.innerHTML = "";
    const parts = filesPath ? filesPath.split("/") : [];
    const crumbs = h("div", { class: "chips" }, h("button", { class: "chip" + (parts.length ? "" : " sel"), onclick: () => open("") }, "Диски"),
      parts.map((n, i) => h("button", { class: "chip" + (i === parts.length - 1 ? " sel" : ""), onclick: () => open(parts.slice(0, i + 1).join("/")) }, n)));
    box.append(h("h2", {}, "Файлы"), crumbs);
    if (parts.length) {
      const pick = h("input", { type: "file", multiple: true, hidden: true, onchange: e => upload(e.target.files) });
      box.append(pick, h("div", { class: "btns" },
        h("button", { class: "ghost", onclick: () => pick.click() }, "Загрузить"),
        h("button", { class: "ghost", onclick: () => {
          const name = prompt("Имя папки");
          if (name) act("/api/files/mkdir", { p: filesPath, name });
        } }, "Новая папка")));
    }
    const list = h("div", { class: "card", style: "margin-top:12px;padding:6px 10px" }, "Загрузка…");
    box.append(list);
    try {
      const { items } = await api("/api/files?p=" + encodeURIComponent(filesPath));
      list.innerHTML = "";
      if (!items.length) list.append(h("div", { class: "empty" }, parts.length ? "Папка пуста" : "Диски не подключены. Вставь USB-диск в Pi."));
      list.append(...items.map(row));
    } catch (e) {
      list.textContent = e.message;
      if (parts.length) filesPath = "";
    }
  }
  draw();
}

// ---------------------------------------------------------------- settings (admin)

async function renderSettings(v) {
  if (!settingsDraft) {
    v.innerHTML = '<div class="empty">Загрузка…</div>';
    try { settingsDraft = await api("/api/config"); } catch (e) { v.innerHTML = `<div class="empty err">${esc(e.message)}</div>`; return; }
  }
  const c = settingsDraft;
  v.innerHTML = "";
  const field = (label, value, set, attrs = {}) => [h("label", { class: "f" }, label),
    h("input", { value: value ?? "", oninput: e => set(e.target.value), autocapitalize: "off", spellcheck: "false", ...attrs })];
  const save = async (part, msg = "Сохранено") => {
    try { settingsDraft = { ...(await api("/api/config", part)) }; toast(msg); render(); } catch (e) { toast(e.message); }
  };

  // hub
  v.append(h("h2", {}, "Хаб"), h("div", { class: "card" }, field("Название", c.name, x => (c.name = x)),
    h("div", { class: "btns" }, h("button", { class: "primary", onclick: () => save({ name: c.name }) }, "Сохранить"))));

  // Home Assistant
  const ha = { url: c.ha.url, token: "" };
  v.append(h("h2", {}, "Home Assistant"), h("div", { class: "card" },
    field("Адрес", ha.url, x => (ha.url = x), { placeholder: "http://192.168.1.20:8123" }),
    field(c.ha.hasToken || c.ha.hasLogin ? "Новый токен (сейчас сохранён)" : "Долгосрочный токен", "", x => (ha.token = x), { placeholder: "eyJ…" }),
    h("p", { class: "hint" }, "Home Assistant → профиль → «Безопасность» → «Долгосрочные токены доступа». Или нажми на дашборде «Перенести на хаб»."),
    h("div", { class: "btns" }, h("button", { class: "primary", onclick: () => save({ ha }) }, "Сохранить"),
      c.ha.hasToken || c.ha.hasLogin ? h("button", { class: "danger", onclick: () => save({ ha: { clear: true } }, "Home Assistant отключён") }, "Отключить") : null)));

  // Yandex
  const ya = { clientId: c.yandex.clientId, token: "" };
  const link = h("a", { target: "_blank", rel: "noopener", style: "color:var(--accent)" }, "Получить токен");
  const upd = () => { link.href = "https://oauth.yandex.ru/authorize?response_type=token&client_id=" + encodeURIComponent(ya.clientId || ""); };
  upd();
  v.append(h("h2", {}, "Яндекс Умный дом"), h("div", { class: "card" },
    field("ClientID приложения", ya.clientId, x => { ya.clientId = x; upd(); }),
    h("p", { class: "hint" }, "oauth.yandex.ru → «Создать приложение» → веб-сервисы, Redirect URI https://oauth.yandex.ru/verification_code, доступы iot:view и iot:control. Потом ", link, " и вставь его ниже."),
    field(c.yandex.hasToken ? "Новый токен (сейчас сохранён)" : "OAuth-токен", "", x => (ya.token = x)),
    h("div", { class: "btns" }, h("button", { class: "primary", onclick: () => save({ yandex: ya }) }, "Сохранить"),
      c.yandex.hasToken ? h("button", { class: "danger", onclick: () => save({ yandex: { clear: true } }, "Яндекс отключён") }, "Отключить") : null)));

  // PCs
  const pcBox = h("div");
  const drawPcs = () => {
    pcBox.innerHTML = "";
    c.pcs.forEach((p, i) => pcBox.append(h("div", { class: "card" },
      field("Имя", p.name, x => (p.name = x)), field("IP-адрес", p.host, x => (p.host = x)),
      field("Порт", p.port, x => (p.port = x), { inputmode: "numeric" }), field("Токен агента", p.token, x => (p.token = x)),
      field("MAC для пробуждения (необязательно)", p.mac, x => (p.mac = x), { placeholder: "AA:BB:CC:DD:EE:FF" }),
      h("div", { class: "btns" }, h("button", { class: "danger", onclick: () => { c.pcs.splice(i, 1); drawPcs(); } }, "Удалить")))));
  };
  drawPcs();
  v.append(h("h2", {}, "Компьютеры"), pcBox, h("div", { class: "btns" },
    h("button", { class: "ghost", onclick: async e => {
      e.target.disabled = true;
      try {
        const r = await api("/api/discover");
        const fresh = r.agents.filter(a => !c.pcs.some(p => p.host === a.host));
        fresh.forEach(a => c.pcs.push({ name: a.name, host: a.host, port: a.port, token: "", mac: "" }));
        toast(r.agents.length ? (fresh.length ? `Найдено: ${fresh.map(a => a.name).join(", ")}. Впиши токены.` : "Новых ПК нет") : "Агенты не найдены");
        drawPcs();
      } catch (er) { toast(er.message); }
      e.target.disabled = false;
    } }, "Найти в сети"),
    h("button", { class: "ghost", onclick: () => { c.pcs.push({ name: "", host: "", port: 8765, token: "", mac: "" }); drawPcs(); } }, "+ Вручную"),
    h("button", { class: "primary", onclick: () => save({ pcs: c.pcs }) }, "Сохранить")));

  // NAS
  let paths = (c.nas.paths || []).join("\n");
  v.append(h("h2", {}, "NAS"), h("div", { class: "card" },
    h("label", { class: "f" }, "Папки-диски, по одной в строке"),
    h("textarea", { rows: 3, style: "width:100%;background:var(--card2);border:1px solid var(--line);border-radius:12px;padding:10px;color:inherit;font:inherit",
      oninput: e => (paths = e.target.value) }, paths),
    h("p", { class: "hint" }, "Например / и /mnt/nas. Показывается свободное место на вкладке «Сервер»."),
    h("div", { class: "btns" }, h("button", { class: "primary", onclick: () => save({ nas: { paths: paths.split("\n") } }) }, "Сохранить"))));

  // scenes
  v.append(h("h2", {}, "Сценарии"), scenesEditor(c, save));

  // automations
  v.append(h("h2", {}, "Автоматизации"), rulesEditor(c, save));

  // place for sunset and sunrise
  const loc = { lat: c.location?.lat ?? "", lon: c.location?.lon ?? "" };
  v.append(h("h2", {}, "Место"), h("div", { class: "card" },
    field("Широта", loc.lat, x => (loc.lat = x), { inputmode: "decimal", placeholder: "55.75" }),
    field("Долгота", loc.lon, x => (loc.lon = x), { inputmode: "decimal", placeholder: "37.62" }),
    h("p", { class: "hint" }, "Для автоматизаций «на закате» и «на рассвете». Дашборд передаёт место сам, когда переносит автоматизации на хаб."),
    h("div", { class: "btns" }, h("button", { class: "primary", onclick: () => save(loc.lat === "" ? { location: {} } : { location: { lat: +loc.lat, lon: +loc.lon } }) }, "Сохранить"))));

  // photos for the dashboard's photo frame
  const ph = { path: c.photos?.path || "" };
  v.append(h("h2", {}, "Фото"), h("div", { class: "card" },
    field("Папка с фотографиями", ph.path, x => (ph.path = x), { placeholder: "/mnt/nas/Фото" }),
    h("p", { class: "hint" }, "Виджет «Фото» и StandBy на дашборде показывают их по очереди, вложенные папки тоже."),
    h("div", { class: "btns" }, h("button", { class: "primary", onclick: () => save({ photos: ph }) }, "Сохранить"))));

  // backups
  let bdir = c.backup_dir || "";
  const saved = h("div", { class: "hint" }, "…");
  api("/api/profiles").then(r => {
    saved.textContent = r.profiles.length ? "Настройки телефонов на хабе: " + r.profiles.map(p => `${p.name} (${new Date(p.at * 1000).toLocaleString("ru")})`).join(", ")
      : "Телефоны ещё не сохраняли настройки: дашборд делает это сам раз в день.";
  }, () => (saved.textContent = ""));
  v.append(h("h2", {}, "Резервная копия"), h("div", { class: "card" },
    field("Папка для копий (пусто — на первом диске NAS)", bdir, x => (bdir = x), { placeholder: "/mnt/nas/deskdash-backup" }),
    h("p", { class: "hint" }, "Каждую ночь в 3:30 хаб копирует свои настройки и настройки телефонов, хранит две недели."), saved,
    h("div", { class: "btns" },
      h("button", { class: "ghost", onclick: () => save({ backup_dir: bdir }) }, "Сохранить"),
      h("button", { class: "primary", onclick: async () => {
        try { const r = await api("/api/backup", {}); toast("Копия: " + r.path); } catch (e) { toast(e.message); }
      } }, "Сделать копию сейчас"))));

  // access
  const tokBox = h("div", { class: "card" });
  for (const t of c.tokens) {
    const url = `${location.origin}/#t=${t.token}`;
    tokBox.append(h("div", { class: "row", style: "margin-bottom:10px" },
      h("div", { style: "min-width:0" }, h("b", {}, t.name + (t.admin ? " · админ" : "")), h("div", { class: "mono muted" }, t.token)),
      h("div", { style: "display:flex;gap:6px" },
        h("button", { class: "ghost", onclick: () => share(t.name, url) }, "Ссылка"),
        t.token === token ? null : h("button", { class: "danger", onclick: async () => {
          if (!confirm(`Отключить «${t.name}»?`)) return;
          try { await api("/api/tokens/delete", { token: t.token }); settingsDraft = null; render(); } catch (e) { toast(e.message); }
        } }, "✕"))));
  }
  let newName = "";
  let newAdmin = false;
  tokBox.append(field("Новый доступ: имя телефона или человека", "", x => (newName = x)),
    h("label", { style: "display:flex;gap:8px;align-items:center;margin-top:8px" },
      h("input", { type: "checkbox", style: "width:auto", onchange: e => (newAdmin = e.target.checked) }), "может менять настройки"),
    h("div", { class: "btns" }, h("button", { class: "primary", onclick: async () => {
      try {
        const t = await api("/api/tokens", { name: newName, admin: newAdmin });
        settingsDraft = null;
        render();
        share(t.name, `${location.origin}/#t=${t.token}`);
      } catch (e) { toast(e.message); }
    } }, "Создать")),
    h("p", { class: "hint" }, "Отправь ссылку на телефон: откроется приложение уже со входом. В браузере: «Добавить на главный экран»."));
  v.append(h("h2", {}, "Доступ"), tokBox,
    h("div", { class: "btns", style: "margin-top:16px" }, h("button", { class: "danger", onclick: logout }, "Выйти на этом телефоне")));
}

async function share(name, url) {
  if (navigator.share) {
    try { await navigator.share({ title: `DeskDash: ${name}`, url }); return; } catch (e) { /* cancelled */ }
  }
  try { await navigator.clipboard.writeText(url); toast("Ссылка скопирована"); } catch (e) { prompt("Ссылка для входа", url); }
}

// Scene steps: {device, action, value} | {pc, macro} | {wake: pc} | {wait: ms}
function scenesEditor(c, save) {
  const box = h("div");
  const devices = (state?.devices || []).filter(d => d.source === "ha" || d.source === "ya");
  const pcs = state?.pcs || [];
  const actionsFor = d => {
    if (!d) return [["toggle", "переключить"]];
    if (d.kind === KIND.SCENE) return [["run", "запустить"]];
    const a = [];
    if (d.canToggle) a.push(["on", "включить"], ["off", "выключить"], ["toggle", "переключить"]);
    if (d.dimmable) a.push(["brightness", "яркость, %"]);
    if (d.colorTemp) a.push(["color_temp", "температура света, K"]);
    if (d.kind === KIND.CLIMATE) a.push(["temperature", "целевая температура"]);
    return a.length ? a : [["toggle", "переключить"]];
  };
  const select = (opts, value, set) => {
    const s = h("select", { onchange: e => set(e.target.value) }, opts.map(([v, l]) => h("option", { value: v, selected: v === value }, l)));
    if (value == null && opts.length) set(opts[0][0]);
    return s;
  };
  const stepType = st => "wait" in st ? "wait" : st.wake ? "wake" : st.pc ? "macro" : "device";

  const draw = () => {
    box.innerHTML = "";
    c.scenes.forEach((s, si) => {
      const card = h("div", { class: "card" });
      card.append(h("label", { class: "f" }, "Название"), h("input", { value: s.label, oninput: e => (s.label = e.target.value) }));
      (s.steps = s.steps || []).forEach((st, i) => {
        const type = stepType(st);
        const parts = [select([["device", "Устройство"], ["macro", "Макрос ПК"], ["wake", "Разбудить ПК"], ["wait", "Пауза"]], type, t => {
          if (t === type) return;
          s.steps[i] = t === "wait" ? { wait: 1000 } : t === "wake" ? { wake: pcs[0]?.id || "" } : t === "macro" ? { pc: pcs[0]?.id || "", macro: "" } : { device: devices[0]?.id || "", action: "toggle" };
          draw();
        })];
        if (type === "device") {
          const d = devices.find(x => x.id === st.device);
          parts.push(select(devices.map(x => [x.id, `${x.name}${x.room ? " · " + x.room : ""}`]), st.device, x => { st.device = x; st.action = actionsFor(devices.find(y => y.id === x))[0][0]; draw(); }));
          parts.push(select(actionsFor(d), st.action, x => { st.action = x; draw(); }));
          if (["brightness", "color_temp", "temperature"].includes(st.action)) {
            parts.push(h("input", { type: "number", value: st.value ?? "", placeholder: "значение", oninput: e => (st.value = +e.target.value) }));
          }
        } else if (type === "macro") {
          const p = pcs.find(x => x.id === st.pc);
          parts.push(select(pcs.map(x => [x.id, x.name]), st.pc, x => { st.pc = x; st.macro = ""; draw(); }));
          parts.push(p && p.macros.length ? select(p.macros.map(m => [m.id, m.label]), st.macro || null, x => (st.macro = x))
            : h("input", { value: st.macro || "", placeholder: "id макроса (ПК не в сети)", oninput: e => (st.macro = e.target.value) }));
        } else if (type === "wake") {
          parts.push(select(pcs.filter(x => x.canWake).map(x => [x.id, x.name]), st.wake, x => (st.wake = x)));
        } else {
          parts.push(h("input", { type: "number", value: st.wait / 1000, step: "0.5", min: 0, oninput: e => (st.wait = Math.round(+e.target.value * 1000)) }), h("span", { class: "muted small" }, "секунд"));
        }
        card.append(h("div", { class: "step" }, h("div", { style: "display:grid;gap:6px" }, parts),
          h("button", { class: "x", title: "Убрать шаг", onclick: () => { s.steps.splice(i, 1); draw(); } }, "✕")));
      });
      card.append(h("div", { class: "btns" },
        h("button", { class: "ghost", onclick: () => { s.steps.push({ device: devices[0]?.id || "", action: actionsFor(devices[0])[0][0] }); draw(); } }, "+ Шаг"),
        s.id ? h("button", { class: "ghost", onclick: async () => { await save({ scenes: c.scenes }, "Сохранено"); api("/api/scene/" + encodeURIComponent(s.id), {}).then(() => toast(`«${s.label}» запущен`), e => toast(e.message)); } }, "▶ Проверить") : null,
        h("button", { class: "danger", onclick: () => { c.scenes.splice(si, 1); draw(); } }, "Удалить сценарий")));
      box.append(card);
    });
    box.append(h("div", { class: "btns" },
      h("button", { class: "ghost", onclick: () => { c.scenes.push({ label: "Новый сценарий", icon: "star", steps: [] }); draw(); } }, "+ Сценарий"),
      h("button", { class: "primary", onclick: () => save({ scenes: c.scenes }) }, "Сохранить сценарии")),
      h("p", { class: "hint" }, "Пример «Кино»: свет в гостиной 20%, шторы закрыть, ПК: макрос «Пауза» → Spotify, пауза 1 с, телевизор включить. Сценарии хаба видны и на дашборде как плитки."));
  };
  draw();
  return box;
}

// Automations: {id, label, on, when: {at} | {sun, offset} | {device, to} | {call}, days, do: [{device, action}], dawn}
function rulesEditor(c, save) {
  const box = h("div");
  const devices = state?.devices || [];
  c.rules = c.rules || [];
  const days = ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"];
  const sel = (opts, value, set) => h("select", { onchange: e => set(e.target.value) },
    opts.map(([v, l]) => h("option", { value: v, selected: String(v) === String(value) }, l)));
  const kind = w => "at" in w ? "at" : "sun" in w ? "sun" : "device" in w ? "device" : "call";
  const draw = () => {
    box.innerHTML = "";
    c.rules.forEach((r, ri) => {
      r.when = r.when || { at: "07:00" };
      r.do = r.do || [];
      r.days = r.days || [];
      const card = h("div", { class: "card" });
      card.append(h("div", { class: "row" }, h("input", { value: r.label, oninput: e => (r.label = e.target.value) }),
        h("label", { class: "switch" }, h("input", { type: "checkbox", checked: r.on !== false, onchange: e => (r.on = e.target.checked) }), h("span"))));
      const k = kind(r.when);
      card.append(h("label", { class: "f" }, "Когда"), sel([["at", "В заданное время"], ["sun", "Закат или рассвет"], ["device", "Устройство включилось или выключилось"],
        ["call", "Созвон на ПК"]], k, x => { r.when = x === "at" ? { at: "07:00" } : x === "sun" ? { sun: "sunset", offset: 0 } : x === "device" ? { device: devices[0]?.id || "", to: "on" } : { call: "start" }; draw(); }));
      if (k === "at") card.append(h("input", { type: "time", value: r.when.at, oninput: e => (r.when.at = e.target.value) }));
      if (k === "sun") card.append(h("div", { class: "step" }, sel([["sunset", "Закат"], ["sunrise", "Рассвет"]], r.when.sun, x => (r.when.sun = x)),
        h("input", { type: "number", value: r.when.offset || 0, oninput: e => (r.when.offset = +e.target.value), title: "сдвиг, минут" })));
      if (k === "device") card.append(sel(devices.map(d => [d.id, d.name + (d.room ? " · " + d.room : "")]), r.when.device, x => (r.when.device = x)),
        sel([["on", "включилось / открылось"], ["off", "выключилось / закрылось"]], r.when.to, x => (r.when.to = x)));
      if (k === "call") card.append(sel([["start", "начался"], ["end", "закончился"]], r.when.call, x => (r.when.call = x)));
      if (k === "at" || k === "sun") {
        card.append(h("div", { class: "chips" }, days.map((d, i) => h("button", {
          class: "chip" + (!r.days.length || r.days.includes(i + 1) ? " sel" : ""),
          onclick: () => { if (!r.days.length) r.days = [1, 2, 3, 4, 5, 6, 7]; r.days = r.days.includes(i + 1) ? r.days.filter(x => x !== i + 1) : [...r.days, i + 1].sort(); if (r.days.length === 7) r.days = []; draw(); },
        }, d))));
      }
      card.append(h("label", { class: "f" }, "Что сделать"));
      r.do.forEach((st, i) => card.append(h("div", { class: "step" }, h("div", { style: "display:grid;gap:6px" },
        sel(devices.map(d => [d.id, d.name + (d.room ? " · " + d.room : "")]), st.device, x => (st.device = x)),
        sel([["on", "включить"], ["off", "выключить"], ["run", "запустить"], ["toggle", "переключить"]], st.action, x => (st.action = x))),
        h("button", { class: "x", onclick: () => { r.do.splice(i, 1); draw(); } }, "✕"))));
      const lamps = devices.filter(d => d.dimmable);
      card.append(h("div", { class: "btns" },
        h("button", { class: "ghost", onclick: () => { r.do.push({ device: devices[0]?.id || "", action: "on" }); draw(); } }, "+ Действие"),
        lamps.length && (k === "at" || k === "sun") ? h("button", { class: "ghost", onclick: () => { r.dawn = r.dawn?.device ? {} : { device: lamps[0].id, minutes: 20 }; draw(); } },
          r.dawn?.device ? "Без рассвета" : "+ Рассвет лампой") : null));
      if (r.dawn?.device) card.append(h("div", { class: "step" }, sel(lamps.map(d => [d.id, d.name]), r.dawn.device, x => (r.dawn.device = x)),
        h("input", { type: "number", value: r.dawn.minutes || 20, oninput: e => (r.dawn.minutes = +e.target.value), title: "минут" })),
        h("p", { class: "hint" }, "Лампа разгорается от 1 % до полной за столько минут и доходит до неё к заданному времени."));
      card.append(h("div", { class: "btns" },
        r.id ? h("button", { class: "ghost", onclick: async () => { await save({ rules: c.rules }); api("/api/rule/" + encodeURIComponent(r.id), {}).then(() => toast(`«${r.label}» выполнено`), e => toast(e.message)); } }, "▶ Проверить") : null,
        h("button", { class: "danger", onclick: () => { c.rules.splice(ri, 1); draw(); } }, "Удалить")));
      box.append(card);
    });
    box.append(h("div", { class: "btns" },
      h("button", { class: "ghost", onclick: () => { c.rules.push({ label: "Новая автоматизация", on: true, when: { at: "07:00" }, days: [], do: [] }); draw(); } }, "+ Автоматизация"),
      h("button", { class: "primary", onclick: () => save({ rules: c.rules }) }, "Сохранить автоматизации")),
      h("p", { class: "hint" }, "Работают на хабе, даже когда все экраны выключены. Примеры: в 23:00 выключить свет; на закате закрыть шторы; открылась дверь — включить свет в прихожей; начался созвон — лампа у стола."));
  };
  draw();
  return box;
}

if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").catch(() => {});
start();

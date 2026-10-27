"use strict";
// DeskDash Дом: the phone side of the hub. Plain JS, no build step.

const KIND = { SWITCH: 0, LIGHT: 1, CLIMATE: 2, SENSOR: 3, SCENE: 4, COVER: 5, LOCK: 6, FAN: 7, MEDIA: 8 };
const ICON = ["⏻", "✹", "♨", "◉", "✦", "▤", "⚿", "✇", "♫"];
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
  $("#tab-settings").hidden = !me.admin;
  if (tab === "settings" && !me.admin) tab = "home";
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
    default: return (d.on ? "Вкл" : "Выкл") + (d.state ? " · " + d.state : "");
  }
}

const deg = v => (Math.round(v * 10) / 10) + "°";
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
  const words = { [KIND.COVER]: ["Открыть", "Закрыть"], [KIND.LOCK]: ["Запереть", "Отпереть"], [KIND.MEDIA]: ["Играть", "Пауза"] }[d.kind]
    || ["Включить", "Выключить"];
  const btns = h("div", { class: "btns" });
  if (d.kind === KIND.SCENE) btns.append(h("button", { class: "primary", onclick: () => { act(d, "run", null, `Запускаю «${d.name}»`); closeSheet(); } }, "Запустить"));
  else if (d.canToggle) btns.append(
    h("button", { class: d.on ? "ghost" : "primary", onclick: () => act(d, "on") }, words[0]),
    h("button", { class: d.on ? "primary" : "ghost", onclick: () => act(d, "off") }, words[1]));
  s.append(btns);

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

if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").catch(() => {});
start();

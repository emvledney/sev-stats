"use strict";
/* Silverado Stats dashboard. Plain JavaScript, no build step; uPlot draws the charts. */

const SOURCES = { car: "Truck API", mx: "MX+" };
const RANGES = [["24h", 1], ["7d", 7], ["30d", 30], ["90d", 90], ["1y", 365], ["All", 0]];
const TABS = ["Overview", "Charts", "Cells", "Table", "Export", "Trucks"];
const SLOTS = 8;
const css = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();

const saved = (() => { try { return JSON.parse(localStorage.getItem("sev") || "{}"); } catch { return {}; } })();
const state = Object.assign({ tab: "Overview", source: "mx", days: 30, picks: {}, kind: null, cells: [] }, saved);
const keep = () => { try { localStorage.setItem("sev", JSON.stringify(state)); } catch { /* a private window: nothing is kept */ } };

// ---- small helpers -----------------------------------------------------------------------------

function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (k === "class") el.className = v;
    else if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
    else if (v !== false && v != null) el.setAttribute(k, v === true ? "" : v);
  }
  for (const kid of kids.flat()) if (kid != null && kid !== false) el.append(kid.nodeType ? kid : String(kid));
  return el;
}

async function api(path, body) {
  const opts = body === undefined ? {} : { method: "POST", headers: { "Content-Type": "application/json", "X-Sev": "1" }, body: JSON.stringify(body) };
  const r = await fetch(path, opts);
  if (r.status === 401) { boot(); throw new Error("Signed out"); }
  const data = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(data.detail || `HTTP ${r.status}`);
  return data;
}

const query = (o) => Object.entries(o).filter(([, v]) => v != null && v !== "").map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join("&");
const from = () => (state.days ? Date.now() - state.days * 86400000 : null);
const when = (ms) => (ms == null ? "never" : new Date(ms).toLocaleString([], { dateStyle: "medium", timeStyle: "short" }));

function ago(ms, now = Date.now()) {
  if (ms == null) return "never";
  const s = Math.max(0, (now - ms) / 1000);
  if (s < 90) return "just now";
  if (s < 5400) return `${Math.round(s / 60)} min ago`;
  if (s < 129600) return `${Math.round(s / 3600)} h ago`;
  return `${Math.round(s / 86400)} days ago`;
}

function num(v) {
  if (v == null) return "";
  const a = Math.abs(v);
  return v.toLocaleString("en-US", { maximumFractionDigits: a >= 1000 ? 1 : a >= 10 ? 2 : 4, useGrouping: false });
}

/* The unit a column is drawn in. Columns say their unit in their name; the three voltage scales
   (cell, 12 V, pack) are kept apart because one axis cannot show 3.7 V and 800 V. */
function unitOf(key) {
  if (key === "ah_deviation") return "Ah";
  if (key.startsWith("pack_v")) return "V, pack";
  if (key.startsWith("aux_12v")) return "V, 12 V system";
  const rules = [["_kmh", "km/h"], ["_kwh", "kWh"], ["_kw", "kW"], ["_mv", "mV"], ["_pct", "%"], ["_ah", "Ah"], ["_km", "km"],
    ["_minutes", "min"], ["_c", "°C"], ["_v", "V, cells"], ["_a", "A"]];
  for (const [suffix, unit] of rules) if (key.endsWith(suffix)) return unit;
  return key;
}

function segmented(options, value, onPick) {
  return h("div", { class: "seg" }, options.map(([label, v]) =>
    h("button", { class: v === value ? "on" : "", onclick: () => onPick(v) }, label)));
}

function filters(withSource = true) {
  return h("div", { class: "row filters" },
    withSource && segmented(Object.entries(SOURCES).map(([k, v]) => [v, k]), state.source, (v) => { state.source = v; keep(); render(); }),
    segmented(RANGES, state.days, (v) => { state.days = v; keep(); render(); }));
}

// ---- charts --------------------------------------------------------------------------------------

let plots = [];
function clearPlots() { plots.forEach((p) => p.destroy()); plots = []; }

/* The truck is off most of the day, and a line drawn across those hours would claim readings that
   were never taken. Where two rows are much further apart than usual, a break goes in (null). A
   value that is merely missing from a row is passed over instead (undefined), so a value read
   every few rows still makes a line. */
function withBreaks(t, columns) {
  const steps = [];
  for (let i = 1; i < t.length; i++) steps.push(t[i] - t[i - 1]);
  const usual = steps.slice().sort((a, b) => a - b)[steps.length >> 1] || 0;
  const limit = Math.max(usual * 3, 600);
  const outT = [], out = columns.map(() => []);
  for (let i = 0; i < t.length; i++) {
    if (i > 0 && t[i] - t[i - 1] > limit) { outT.push((t[i] + t[i - 1]) / 2); out.forEach((c) => c.push(null)); }
    outT.push(t[i]);
    columns.forEach((c, k) => out[k].push(c[i] == null ? undefined : c[i]));
  }
  return [outT, ...out];
}

/* One chart, one y axis. [series]: [{label, slot, data}] with slot 1..8 naming its colour. */
function chart(parent, title, note, t, series) {
  const box = h("div", { class: "panel chart" }, h("h3", null, title, note && h("small", null, note)));
  parent.append(box);
  if (!t.length) { box.append(h("div", { class: "dim" }, "No rows in this range.")); return; }
  const axis = { stroke: css("--dim"), grid: { stroke: css("--grid"), width: 1 }, ticks: { stroke: css("--grid"), width: 1 }, font: "12px system-ui" };
  const plot = new uPlot({
    width: Math.max(320, box.clientWidth - 34),
    height: 260,
    cursor: { sync: { key: "sev" }, drag: { x: true, y: false } },
    legend: { live: true },
    scales: { x: { time: true } },
    axes: [axis, Object.assign({ size: 64 }, axis)],
    series: [{ label: "Time", value: (u, v) => (v == null ? "" : new Date(v * 1000).toLocaleString([], { dateStyle: "short", timeStyle: "short" })) },
      ...series.map((s) => ({
        label: s.label, stroke: css(`--s${s.slot}`), width: 2,
        points: { size: 6, stroke: css("--surface"), width: 2, fill: css(`--s${s.slot}`) },
        value: (u, v) => num(v),
      }))],
  }, withBreaks(t, series.map((s) => s.data)), box);
  plots.push(plot);
}

// ---- pages ---------------------------------------------------------------------------------------

const pages = {};

pages.Overview = async (root) => {
  const o = await api("/api/overview");
  const lastUpload = o.uploads[0];
  const stale = lastUpload && o.now_ms - lastUpload.received_ms > 36 * 3600000;
  root.append(h("div", { class: "tiles" },
    h("div", { class: "tile" }, h("div", { class: "label" }, "Last upload"),
      h("div", { class: "value" }, lastUpload ? ago(lastUpload.received_ms, o.now_ms) : "none yet"),
      h("div", { class: "sub" + (stale ? " warn" : "") }, lastUpload ? (stale ? "⚠ over 36 h ago · " : "") + when(lastUpload.received_ms) : "Pair a truck on the Trucks tab")),
    o.sources.map((s) => h("div", { class: "tile" }, h("div", { class: "label" }, `${SOURCES[s.source] || s.source} rows`),
      h("div", { class: "value" }, s.rows.toLocaleString()),
      h("div", { class: "sub" }, `newest ${when(s.last_ms)}`))),
    o.arrays.map((a) => h("div", { class: "tile" }, h("div", { class: "label" }, `${a.kind} snapshots`),
      h("div", { class: "value" }, a.n.toLocaleString()), h("div", { class: "sub" }, `newest ${when(a.last_ms)}`)))));

  const cols = h("div", { class: "cols2" });
  for (const s of o.sources) {
    cols.append(h("div", { class: "panel" }, h("h2", null, `Latest values, ${SOURCES[s.source] || s.source}`),
      h("div", { class: "scroll" }, h("table", null,
        h("thead", null, h("tr", null, h("th", null, "Value"), h("th", { class: "num" }, "Reading"), h("th", null, "Unit"), h("th", null, "Read"), h("th", { class: "num" }, "Rows"))),
        h("tbody", null, s.latest.map((v) => {
          const unit = unitOf(v.col);
          return h("tr", null, h("td", null, v.col), h("td", { class: "num" }, num(v.value)),
            h("td", { class: "dim" }, unit === v.col ? "" : unit.split(",")[0]), h("td", { class: "dim" }, ago(v.at_ms, o.now_ms)), h("td", { class: "num dim" }, v.n.toLocaleString()));
        }))))));
  }
  root.append(cols);

  root.append(h("div", { class: "panel" }, h("h2", null, "Recent uploads"),
    o.uploads.length === 0 ? h("div", { class: "dim" }, "Nothing has been uploaded yet.") :
    h("div", { class: "scroll" }, h("table", null,
      h("thead", null, h("tr", null, h("th", null, "Received"), h("th", null, "Truck"), h("th", { class: "num" }, "Rows sent"), h("th", { class: "num" }, "New"), h("th", { class: "num" }, "Rejected"), h("th", null, "App"))),
      h("tbody", null, o.uploads.map((u) => h("tr", null, h("td", null, when(u.received_ms)), h("td", null, u.device),
        h("td", { class: "num" }, u.n_rows), h("td", { class: "num" }, u.n_new),
        h("td", { class: "num" + (u.n_rejected ? " warn" : "") }, u.n_rejected), h("td", { class: "dim" }, u.app_version || ""))))))));
};

pages.Charts = async (root) => {
  root.append(filters());
  const o = await api("/api/overview");
  const src = o.sources.find((s) => s.source === state.source);
  if (!src) { root.append(h("div", { class: "panel dim" }, `No ${SOURCES[state.source]} rows yet.`)); return; }

  // Group the columns that hold data by unit. A column keeps its colour slot while it stays picked.
  const groups = new Map();
  for (const v of src.latest) { const u = unitOf(v.col); if (!groups.has(u)) groups.set(u, []); groups.get(u).push(v.col); }
  const picks = (state.picks[state.source] ||= defaults(state.source, src.latest.map((v) => v.col)));
  for (const k of Object.keys(picks)) if (!src.latest.some((v) => v.col === k)) delete picks[k];

  const chooser = h("div", { class: "panel" }, h("h2", null, "Values"));
  const note = h("div", { class: "dim" });
  for (const [unit, cols] of groups) {
    chooser.append(h("div", { class: "chipgroup" }, h("div", { class: "unit" }, unit === cols[0] && cols.length === 1 ? "no unit" : unit),
      h("div", { class: "chips" }, cols.map((c) => h("button", {
        class: "chip" + (c in picks ? " on" : ""),
        onclick: () => {
          if (c in picks) delete picks[c];
          else {
            const used = new Set(cols.filter((x) => x in picks).map((x) => picks[x]));
            let slot = 1;
            while (used.has(slot)) slot += 1;
            if (slot > SLOTS) { note.textContent = `A chart holds at most ${SLOTS} lines: take one out of "${unit}" first.`; return; }
            picks[c] = slot;
          }
          keep(); render();
        },
      }, c)))));
  }
  chooser.append(note);
  root.append(chooser);

  const want = Object.keys(picks);
  if (!want.length) { root.append(h("div", { class: "panel dim" }, "Pick one or more values above.")); return; }
  const data = await api("/api/series?" + query({ source: state.source, cols: want.join(","), frm: from() }));
  const thinned = data.per_point > 1 ? `each point is the mean of ${data.per_point} rows` : "";
  for (const [unit, cols] of groups) {
    const shown = cols.filter((c) => c in picks);
    if (!shown.length) continue;
    chart(root, unit === shown[0] ? shown[0] : unit, [`${data.rows.toLocaleString()} rows`, thinned, "drag to zoom, double-click to reset"].filter(Boolean).join(" · "),
      data.t, shown.map((c) => ({ label: c, slot: picks[c], data: data.series[c] })));
  }
};

function defaults(source, have) {
  const wanted = source === "mx" ? ["soc_pct", "energy_kwh", "capacity_kwh", "module_temp_min_c", "module_temp_max_c"] : ["car_soc_pct", "car_energy_kwh", "outside_c"];
  const out = {}, perUnit = {};
  for (const c of wanted.filter((x) => have.includes(x))) { const u = unitOf(c); perUnit[u] = (perUnit[u] || 0) + 1; out[c] = perUnit[u]; }
  return out;
}

const mix = (a, b, f) => "#" + [0, 2, 4].map((i) => Math.round(parseInt(a.substr(i + 1, 2), 16) * (1 - f) + parseInt(b.substr(i + 1, 2), 16) * f).toString(16).padStart(2, "0")).join("");

pages.Cells = async (root) => {
  const o = await api("/api/overview");
  if (!o.arrays.length) {
    root.append(h("div", { class: "panel dim" }, "No per-cell snapshots yet. They arrive with MX+ rows once the truck runs an app version that uploads them."));
    return;
  }
  if (!o.arrays.some((a) => a.kind === state.kind)) { state.kind = o.arrays[0].kind; state.cells = []; }
  root.append(h("div", { class: "row filters" },
    segmented(o.arrays.map((a) => [a.kind, a.kind]), state.kind, (v) => { state.kind = v; state.cells = []; keep(); render(); }),
    segmented(RANGES, state.days, (v) => { state.days = v; keep(); render(); })));
  const d = await api("/api/arrays?" + query({ kind: state.kind, frm: from(), idx: state.cells.map((c) => c.i).join(",") }));
  if (!d.latest) { root.append(h("div", { class: "panel dim" }, "No snapshots in this range.")); return; }

  const vals = d.latest.vals, nums = vals.filter((v) => v != null);
  const lo = Math.min(...nums), hi = Math.max(...nums), mean = nums.reduce((a, b) => a + b, 0) / nums.length;
  const diverging = state.kind.endsWith("_v");
  const spread = Math.max(hi - mean, mean - lo) || 1;
  const colour = (v) => {
    if (v == null) return css("--surface-high");
    if (!diverging) return mix("#2b2a26", "#ffb020", hi > lo ? (v - lo) / (hi - lo) : 0);
    const f = (v - mean) / spread;
    return f < 0 ? mix("#22262d", "#00b8ff", -f) : mix("#22262d", "#ff5d5d", f);
  };
  const readout = h("div", { class: "readout dim" }, "Hover a position for its value; click to chart it over time.");
  const grid = h("div", { class: "grid192", style: `grid-template-columns: repeat(${vals.length > 48 ? 24 : Math.min(vals.length, 16)}, 1fr)` },
    vals.map((v, i) => h("div", {
      class: "cell" + (state.cells.some((c) => c.i === i + 1) ? " sel" : ""), style: `background:${colour(v)}`, title: `${i + 1}: ${num(v)}`,
      onmouseenter: () => { readout.textContent = `Position ${i + 1}: ${num(v)}` + (diverging && v != null ? ` (${v >= mean ? "+" : ""}${num((v - mean) * 1000)} mV from the mean)` : ""); },
      onclick: () => {
        const at = state.cells.findIndex((c) => c.i === i + 1);
        if (at >= 0) state.cells.splice(at, 1);
        else {
          const used = new Set(state.cells.map((c) => c.slot));
          let slot = 4;
          while (used.has(slot)) slot += 1;
          if (slot > SLOTS) { readout.textContent = "Five positions at most: click a chosen one to take it out."; return; }
          state.cells.push({ i: i + 1, slot });
        }
        keep(); render();
      },
    })));
  const legend = diverging
    ? h("div", { class: "dim" }, `low ${num(lo)}`, h("span", { class: "ramp", style: "background:linear-gradient(90deg,#00b8ff,#22262d,#ff5d5d)" }), `high ${num(hi)} · mean ${num(mean)} · spread ${num((hi - lo) * 1000)} mV`)
    : h("div", { class: "dim" }, `low ${num(lo)}`, h("span", { class: "ramp", style: "background:linear-gradient(90deg,#2b2a26,#ffb020)" }), `high ${num(hi)} · mean ${num(mean)}`);
  root.append(h("div", { class: "panel" }, h("h2", null, `Newest snapshot, ${when(d.latest.at_ms)}`), grid, readout, legend));

  chart(root, `${state.kind} over time`, d.every > 1 ? `every ${d.every}th snapshot` : `${d.t.length.toLocaleString()} snapshots`, d.t, [
    { label: "lowest", slot: 1, data: d.min }, { label: "mean", slot: 3, data: d.mean }, { label: "highest", slot: 2, data: d.max },
    ...state.cells.map((c) => ({ label: `position ${c.i}`, slot: c.slot, data: d.picked[c.i] || [] })),
  ]);
};

let offset = 0;
pages.Table = async (root) => {
  root.append(filters());
  const limit = 200;
  const d = await api("/api/rows?" + query({ source: state.source, frm: from(), limit, offset }));
  if (offset >= d.total && offset > 0) { offset = 0; return render(); }
  root.append(h("div", { class: "panel" },
    h("div", { class: "row", style: "margin-bottom:10px" },
      h("span", { class: "dim" }, d.total ? `Rows ${offset + 1} to ${Math.min(offset + limit, d.total)} of ${d.total.toLocaleString()}, newest first` : "No rows in this range."),
      h("span", { class: "spacer" }),
      offset > 0 && h("button", { class: "btn", onclick: () => { offset = Math.max(0, offset - limit); render(); } }, "Newer"),
      offset + limit < d.total && h("button", { class: "btn", onclick: () => { offset += limit; render(); } }, "Older")),
    h("div", { class: "scroll" }, h("table", null,
      h("thead", null, h("tr", null, h("th", null, "Time"), h("th", null, "Trigger"), d.columns.map((c) => h("th", { class: "num" }, c)))),
      h("tbody", null, d.rows.map((r) => h("tr", null, h("td", null, when(r[0])), h("td", { class: "dim" }, r[3]), r.slice(4).map((v) => h("td", { class: "num" }, num(v))))))))));
};

pages.Export = async (root) => {
  root.append(filters());
  const o = await api("/api/overview");
  const range = RANGES.find((r) => r[1] === state.days)[0];
  root.append(h("div", { class: "panel" }, h("h2", null, "Log rows, CSV"),
    h("p", { class: "dim" }, "The same layout as the app's own export: time_utc, tz_minutes, source, trigger, then one column per value."),
    h("div", { class: "row" },
      h("a", { class: "btn primary", href: "/api/export.csv?" + query({ source: state.source, frm: from() }) }, `${SOURCES[state.source]}, ${range === "All" ? "all time" : "last " + range}`),
      h("a", { class: "btn", href: "/api/export.csv" }, "Everything, both sources"))));
  if (o.arrays.length) {
    root.append(h("div", { class: "panel" }, h("h2", null, "Per-cell snapshots, CSV"),
      h("p", { class: "dim" }, "One line per snapshot, one column per position."),
      h("div", { class: "row" }, o.arrays.map((a) => h("a", { class: "btn", href: "/api/export-arrays.csv?" + query({ kind: a.kind, frm: from() }) }, `${a.kind}, ${range === "All" ? "all time" : "last " + range}`)))));
  }
  root.append(h("div", { class: "panel" }, h("h2", null, "Whole database"),
    h("p", { class: "dim" }, "A SQLite file with every row and snapshot. The login and the trucks' tokens are left out of it."),
    h("a", { class: "btn", href: "/api/export.sqlite" }, "Download SQLite file")));
};

pages.Trucks = async (root) => {
  const o = await api("/api/overview");
  const codeBox = h("div");
  const showCode = (c) => {
    codeBox.replaceChildren(h("div", { class: "panel" }, h("h2", null, "Pairing code"),
      h("div", { class: "code" }, c.code.slice(0, 4) + " " + c.code.slice(4)),
      h("div", { class: "dim" }, `On the truck, in Park: Settings, Upload, enter this code. It works once and expires at ${new Date(c.expires_ms).toLocaleTimeString([], { timeStyle: "short" })}.`)));
  };
  const act = async (fn) => { try { await fn(); } catch (e) { alert(e.message); } };
  const name = h("input", { type: "text", placeholder: "Truck name, e.g. Silverado", maxlength: 64 });
  root.append(h("div", { class: "panel" }, h("h2", null, "Trucks"),
    o.devices.length === 0 ? h("p", { class: "dim" }, "No truck yet. Add one to get a pairing code.") :
    h("div", { class: "scroll" }, h("table", null,
      h("thead", null, h("tr", null, h("th", null, "Name"), h("th", null, "State"), h("th", null, "Last upload"), h("th", { class: "num" }, "Rows"), h("th", null, "App"), h("th", null, ""))),
      h("tbody", null, o.devices.map((d) => h("tr", null, h("td", null, d.name),
        h("td", { class: d.paired ? "ok" : "dim" }, d.paired ? "✓ Paired" : "Not paired"),
        h("td", null, d.last_seen_ms ? `${ago(d.last_seen_ms, o.now_ms)} (${when(d.last_seen_ms)})` : "never"),
        h("td", { class: "num" }, d.n_rows.toLocaleString()), h("td", { class: "dim" }, d.app_version || ""),
        h("td", null, h("div", { class: "row" },
          h("button", { class: "btn", onclick: () => act(async () => showCode(await api("/api/pair-code", { device: d.id }))) }, d.paired ? "Pair again" : "Pairing code"),
          h("button", { class: "btn", onclick: () => act(async () => { const n = prompt("Name", d.name); if (n) { await api(`/api/devices/${d.id}/rename`, { name: n }); render(); } }) }, "Rename"),
          d.paired && h("button", { class: "btn danger", onclick: () => act(async () => { if (confirm(`Stop uploads from ${d.name}? Its rows stay.`)) { await api(`/api/devices/${d.id}/revoke`, {}); render(); } }) }, "Stop uploads")))))))),
    h("div", { class: "row", style: "margin-top:12px" }, name,
      h("button", { class: "btn primary", onclick: () => act(async () => { const c = await api("/api/pair-code", { name: name.value }); await render(); showCode(c); }) }, "Add truck"))));
  root.append(codeBox);

  const oldPw = h("input", { type: "password", placeholder: "Current password", autocomplete: "current-password" });
  const newPw = h("input", { type: "password", placeholder: "New password (10 or more)", autocomplete: "new-password" });
  const msg = h("span");
  root.append(h("div", { class: "panel" }, h("h2", null, "Dashboard password"),
    h("div", { class: "row" }, oldPw, newPw, h("button", { class: "btn", onclick: async () => {
      try { await api("/api/password", { old: oldPw.value, new: newPw.value }); msg.className = "ok"; msg.textContent = "✓ Changed. Other browsers are signed out."; oldPw.value = newPw.value = ""; }
      catch (e) { msg.className = "err"; msg.textContent = e.message; }
    } }, "Change"), msg)));
};

// ---- shell ---------------------------------------------------------------------------------------

const app = document.getElementById("app");
let drawing = 0;

async function render() {
  const mine = ++drawing;
  clearPlots();
  const main = h("main");
  app.replaceChildren(
    h("header", null, h("h1", null, "Silverado ", h("span", null, "Stats")),
      h("nav", null, TABS.map((t) => h("button", { class: t === state.tab ? "on" : "", onclick: () => { state.tab = t; offset = 0; keep(); render(); } }, t))),
      h("button", { class: "btn", onclick: async () => { await api("/api/logout", {}); boot(); } }, "Sign out")),
    main);
  try { await (pages[state.tab] || pages.Overview)(main); }
  catch (e) { if (mine === drawing) main.append(h("div", { class: "panel err" }, e.message)); }
}

function signIn(setup) {
  clearPlots();
  const name = h("input", { type: "text", placeholder: "Name", autocomplete: "username" });
  const pw = h("input", { type: "password", placeholder: setup ? "Password (10 or more characters)" : "Password", autocomplete: setup ? "new-password" : "current-password" });
  const msg = h("div", { class: "err", style: "min-height:22px" });
  const go = async () => {
    try { await api(setup ? "/api/setup" : "/api/login", { name: name.value, password: pw.value }); render(); }
    catch (e) { msg.textContent = e.message; }
  };
  const form = h("form", { class: "panel login", onsubmit: (e) => { e.preventDefault(); go(); } },
    h("h2", null, setup ? "Silverado Stats: create the login" : "Silverado Stats"),
    setup && h("p", { class: "dim" }, "First run. This name and password are the only login to this dashboard."),
    name, pw, msg, h("button", { class: "btn primary", type: "submit" }, setup ? "Create login" : "Sign in"));
  app.replaceChildren(form);
  name.focus();
}

async function boot() {
  const r = await fetch("/api/me").then((x) => x.json()).catch(() => null);
  if (!r) { app.replaceChildren(h("div", { class: "panel login err" }, "The server is not answering.")); return; }
  if (r.signed_in) render(); else signIn(r.setup);
}

let resize;
addEventListener("resize", () => { clearTimeout(resize); resize = setTimeout(() => { if (plots.length) render(); }, 250); });
boot();

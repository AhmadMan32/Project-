// ---------- Durum & kalıcılık ----------
const STORE_KEY = "fitai-v1";
const defaultState = () => ({
  workouts: [],          // {id, date, exerciseId, sets:[{kg, reps}]}
  customExercises: [],   // {id, name, cat, p:[], s:[]}
  nutrition: {},         // "YYYY-MM-DD": {protein:[{name,g}], creatine:0, water:0}
  bodyweight: {},        // "YYYY-MM-DD": kg
  profile: { weight: 75, goal: "muscle", creatineLoading: false, proteinOverride: "", onboarded: false },
  program: null,         // generateProgram() çıktısı
  apiKey: "",
});

let state = load();
if (state.program && !state.program.mode) state.program.mode = "auto"; // eski kayıtlar
function load() {
  try {
    const raw = localStorage.getItem(STORE_KEY);
    return raw ? { ...defaultState(), ...JSON.parse(raw) } : defaultState();
  } catch { return defaultState(); }
}
function save() {
  try { localStorage.setItem(STORE_KEY, JSON.stringify(state)); } catch {}
}

const $ = (s) => document.querySelector(s);
const $$ = (s) => [...document.querySelectorAll(s)];
const uid = () => Math.random().toString(36).slice(2, 10);
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const userName = () => state.profile.name || "";
const hey = (txt) => (userName() ? `${userName()}, ${txt.charAt(0).toLocaleLowerCase("tr")}${txt.slice(1)}` : txt);
function timeGreeting() {
  const h = new Date().getHours();
  return h < 6 ? "İyi geceler" : h < 12 ? "Günaydın" : h < 18 ? "İyi günler" : "İyi akşamlar";
}
const allExercises = () => [...EXERCISES, ...state.customExercises];
const exName = (id) => (findExercise(id, state.customExercises) || { name: id }).name;
const fmtDate = (d) => new Date(d + "T12:00:00").toLocaleDateString("tr-TR", { day: "numeric", month: "short", weekday: "short" });

function toast(msg) {
  const t = $("#toast");
  t.textContent = msg;
  t.classList.add("show");
  clearTimeout(toast._t);
  toast._t = setTimeout(() => t.classList.remove("show"), 1800);
}

function targets() {
  const t = nutritionTargets(state.profile);
  if (Number(state.profile.proteinOverride) > 0) t.protein = Number(state.profile.proteinOverride);
  return t;
}
function dayNut(d = todayStr()) {
  return (state.nutrition[d] ||= { protein: [], creatine: 0, water: 0 });
}

// ---------- Sekmeler ----------
$("#tabs").addEventListener("click", (e) => {
  const b = e.target.closest("button[data-tab]");
  if (!b) return;
  $$("#tabs button").forEach((x) => x.classList.toggle("active", x === b));
  $$(".tab").forEach((t) => t.classList.toggle("active", t.id === "tab-" + b.dataset.tab));
  render(b.dataset.tab);
  try { sessionStorage.setItem("fitai-tab", b.dataset.tab); } catch {}
});

// ---------- ANTRENMAN ----------
let selectedEx = null;
let selectedCat = "Tümü";
let editRows = [];

$("#wDate").value = todayStr();
$("#wDate").addEventListener("change", renderDayLog);
$("#exSearch").addEventListener("input", renderExList);
$("#showAllEx").addEventListener("change", renderExList);

function renderCats() {
  const cats = ["Tümü", "Makine", ...new Set(allExercises().map((e) => e.cat))];
  $("#catChips").innerHTML = cats.map((c) => `<button class="chip ${c === selectedCat ? "on" : ""}" data-cat="${esc(c)}">${esc(c)}</button>`).join("");
}
$("#catChips").addEventListener("click", (e) => {
  const c = e.target.closest("[data-cat]");
  if (!c) return;
  selectedCat = c.dataset.cat;
  renderCats(); renderExList();
});

function renderExList() {
  const q = $("#exSearch").value.trim().toLocaleLowerCase("tr");
  const showAll = $("#showAllEx").checked;
  const ok = new Set(allowedExercises(state.profile, state.customExercises).map((e) => e.id));
  const list = allExercises().filter((e) => (showAll || ok.has(e.id)) &&
    (selectedCat === "Tümü" || e.cat === selectedCat || (selectedCat === "Makine" && e.machine)) &&
    exMatches(e, q)
  );
  $("#exList").innerHTML = list.map((e) => `
    <div class="ex-item ${selectedEx?.id === e.id ? "sel" : ""}" data-id="${e.id}">
      <span>${esc(e.name)}${riskyFor(e.id, state.profile).length ? ` <span class="risk" title="Sakatlık riski">⚠️ ${riskyFor(e.id, state.profile).join(", ")}</span>` : ""}</span>
      <span class="tags">${e.p.map((m) => MUSCLES[m].name).join(", ")}</span>
    </div>`).join("") || `<div class="muted small">Sonuç yok. Ayarlar'dan özel hareket ekleyebilirsin.</div>`;
}
$("#exList").addEventListener("click", (e) => {
  const it = e.target.closest(".ex-item");
  if (!it) return;
  selectExercise(it.dataset.id);
});

function selectExercise(id, plan) {
  selectedEx = findExercise(id, state.customExercises);
  const last = [...state.workouts].reverse().find((w) => w.exerciseId === id);
  editRows = last ? last.sets.map((s) => ({ ...s })) : [{ kg: "", reps: "" }, { kg: "", reps: "" }, { kg: "", reps: "" }];
  if (plan) {
    const r = parseInt(plan.reps) || "";
    const kg = last ? Math.max(...last.sets.map((s) => s.kg || 0)) || "" : "";
    editRows = Array.from({ length: plan.sets }, (_, i) => ({ kg: last?.sets[i]?.kg ?? kg, reps: r }));
  }
  $("#selExTitle").textContent = selectedEx.name;
  $("#selExMuscles").innerHTML =
    `<b>Birincil:</b> ${selectedEx.p.map((m) => MUSCLES[m].name).join(", ")}` +
    (selectedEx.s.length ? ` · <b>İkincil:</b> ${selectedEx.s.map((m) => MUSCLES[m].name).join(", ")}` : "");
  $("#lastTime").textContent = last
    ? `Son sefer (${fmtDate(last.date)}): ${last.sets.map((s) => `${s.kg || 0}×${s.reps}`).join(", ")} — ağırlıklar oradan dolduruldu.`
    : (selectedEx.bw ? "Vücut ağırlığı hareketi: kilo alanına ek ağırlık girebilir veya boş bırakabilirsin." : "");
  $("#setEditor").classList.remove("hidden");
  renderSetRows(); renderExList();
}

function renderSetRows() {
  $("#setRows").innerHTML = editRows.map((r, i) => `
    <tr>
      <td class="muted">${i + 1}</td>
      <td><input type="number" step="0.5" inputmode="decimal" data-i="${i}" data-f="kg" value="${r.kg}" placeholder="kg"/></td>
      <td><input type="number" inputmode="numeric" data-i="${i}" data-f="reps" value="${r.reps}" placeholder="tekrar"/></td>
      <td><button class="icon-btn" data-del="${i}" title="Sil">✕</button></td>
    </tr>`).join("");
}
$("#setRows").addEventListener("input", (e) => {
  const t = e.target;
  if (t.dataset.i != null) editRows[t.dataset.i][t.dataset.f] = t.value;
});
$("#setRows").addEventListener("click", (e) => {
  const d = e.target.closest("[data-del]");
  if (!d) return;
  editRows.splice(Number(d.dataset.del), 1);
  renderSetRows();
});
$("#addSet").onclick = () => { editRows.push({ kg: "", reps: "" }); renderSetRows(); };
$("#copySet").onclick = () => { editRows.push({ ...(editRows[editRows.length - 1] || { kg: "", reps: "" }) }); renderSetRows(); };

$("#saveEx").onclick = () => {
  if (!selectedEx) return;
  const sets = editRows
    .map((r) => ({ kg: parseFloat(r.kg) || 0, reps: parseInt(r.reps) || 0 }))
    .filter((s) => s.reps > 0);
  if (!sets.length) return toast("En az bir set için tekrar sayısı gir");
  const date = $("#wDate").value || todayStr();
  const prevBest = Math.max(0, ...exerciseHistory(state.workouts, selectedEx.id).map((h) => h.best1rm));
  state.workouts.push({ id: uid(), date, exerciseId: selectedEx.id, sets });
  save();
  const newBest = Math.max(...sets.map((s) => e1rm(s.kg, s.reps)));
  toast(prevBest && newBest > prevBest ? `🏆 Helal ${userName() || "olsun"}! Yeni rekor: ~${newBest.toFixed(1)} kg 1RM` : `Kaydedildi ✓${userName() ? " Devam " + userName() + "!" : ""}`);
  renderDayLog(); renderHistory(); updateScore(); renderPlan();
  if (typeof onWorkoutSaved === "function") onWorkoutSaved();
};

function workoutItem(w, withDate) {
  return `<div class="log-item">
    <div>
      <div><b>${esc(exName(w.exerciseId))}</b>${withDate ? ` <span class="muted small">${fmtDate(w.date)}</span>` : ""}</div>
      <div class="log-sets">${w.sets.map((s) => `${s.kg || 0} kg × ${s.reps}`).join(" · ")}</div>
    </div>
    <button class="icon-btn" data-delw="${w.id}" title="Sil">🗑</button>
  </div>`;
}

function renderDayLog() {
  const d = $("#wDate").value;
  const items = state.workouts.filter((w) => w.date === d);
  $("#dayLog").innerHTML = items.length ? items.map((w) => workoutItem(w)).join("") : `<div class="muted small">Bu gün için kayıt yok.</div>`;
}

function renderHistory() {
  const byDate = {};
  for (const w of state.workouts) (byDate[w.date] ||= []).push(w);
  const dates = Object.keys(byDate).sort().reverse().slice(0, 14);
  $("#history").innerHTML = dates.length ? dates.map((d) => {
    const ws = byDate[d];
    const muscles = [...new Set(ws.flatMap((w) => (findExercise(w.exerciseId, state.customExercises) || { p: [] }).p))];
    const tonnage = ws.reduce((a, w) => a + w.sets.reduce((b, s) => b + s.kg * s.reps, 0), 0);
    return `<div class="day-group"><h4>${fmtDate(d)} · ${muscles.map((m) => MUSCLES[m].name).join(", ")} · ${Math.round(tonnage)} kg toplam</h4>
      ${ws.map((w) => workoutItem(w)).join("")}</div>`;
  }).join("") : `<div class="muted small">Henüz antrenman yok.</div>`;
}

document.addEventListener("click", (e) => {
  const d = e.target.closest("[data-delw]");
  if (!d) return;
  if (!confirm("Bu kaydı silmek istiyor musun?")) return;
  state.workouts = state.workouts.filter((w) => w.id !== d.dataset.delw);
  save(); renderDayLog(); renderHistory(); updateScore();
});

// ---------- ANALİZ ----------
let lastResult = null;
let focusMuscle = null;
const COLORS = { none: "var(--m-none)", low: "var(--m-low)", ok: "var(--m-ok)", high: "var(--m-high)" };
const STATUS_TXT = { none: "Hiç çalışmadı", low: "Yetersiz", ok: "İdeal aralıkta", high: "Fazla çalıştı" };

function renderAnalysis() {
  const r = (lastResult = analyze(state));
  const color = (m) => COLORS[r.statuses[m]];
  $("#bodyWrap").innerHTML = buildBodySVG("front", color) + buildBodySVG("back", color);
  if (focusMuscle) showMuscle(focusMuscle);

  $("#insights").innerHTML = (userName() ? `<p class="muted small" style="margin-top:0">${esc(userName())}, son 7 günün verilerine göre kararlarım:</p>` : "") + r.insights.map((i) => `
    <div class="insight ${i.type}"><b>${esc(i.title)}</b><p>${esc(i.text)}</p></div>`).join("");

  const order = Object.keys(MUSCLES).sort((a, b) => r.week.vol[b] - r.week.vol[a]);
  const maxV = Math.max(24, ...order.map((m) => r.week.vol[m]));
  $("#volBars").innerHTML = order.map((m) => {
    const v = r.week.vol[m], M = { ...MUSCLES[m], ...T(m) };
    return `<div class="vbar" data-m="${m}">
      <span>${M.name}</span>
      <div class="track">
        <div class="zone" style="left:${(M.min / maxV) * 100}%;width:${((M.max - M.min) / maxV) * 100}%"></div>
        <div class="fill" style="width:${(v / maxV) * 100}%;background:${COLORS[r.statuses[m]]}"></div>
      </div>
      <span class="muted">${fmt(v)} / ${M.min}-${M.max}</span>
    </div>`;
  }).join("");
}

function showMuscle(m) {
  focusMuscle = m;
  $$(".muscle").forEach((p) => p.classList.toggle("focus", p.dataset.muscle === m));
  const r = lastResult, M = { ...MUSCLES[m], ...T(m) };
  const last = r.week.lastHit[m];
  const ago = last ? Math.max(0, Math.round((Date.now() - last) / 86400000)) : null;
  const exs = [...new Set(state.workouts
    .filter((w) => dateToMs(w.date) > Date.now() - 7 * DAY)
    .filter((w) => { const e = findExercise(w.exerciseId, state.customExercises); return e && (e.p.includes(m) || e.s.includes(m)); })
    .map((w) => exName(w.exerciseId)))];
  $("#muscleInfo").innerHTML = `<b style="color:var(--text)">${M.name}</b> — ${STATUS_TXT[r.statuses[m]]}<br>
    Son 7 gün: <b>${fmt(r.week.vol[m])}</b> set (hedef ${M.min}-${M.max}) ·
    ${ago === null ? "Son 7 günde çalışılmadı" : ago === 0 ? "Bugün çalıştı" : `${ago} gün önce çalıştı`}<br>
    ${exs.length ? "Hareketler: " + exs.map(esc).join(", ") : "Öneri: " + suggest(m).join(", ")}`;
}
$("#bodyWrap").addEventListener("click", (e) => {
  const p = e.target.closest(".muscle");
  if (p) showMuscle(p.dataset.muscle);
});

$("#askClaude").onclick = async () => {
  const out = $("#claudeOut"), btn = $("#askClaude");
  btn.disabled = true; out.textContent = "Claude verilerini inceliyor…";
  try {
    out.textContent = await askClaudeCoach(state, lastResult || analyze(state));
  } catch (err) {
    out.textContent = "⚠️ " + (err.message || err);
  } finally { btn.disabled = false; }
};

function updateScore() {
  const r = analyze(state);
  $("#scorePill").textContent = `Denge: ${r.score}/100`;
  $("#scorePill").classList.toggle("hidden", !state.workouts.length); // kayıt yokken anlamsız
}

// ---------- GELİŞİM ----------
function lineChart(el, points, unit) {
  if (points.length < 1) { el.innerHTML = `<div class="muted small">Grafik için en az 1 kayıt gerekli.</div>`; return; }
  const W = 600, H = 240, P = { l: 44, r: 14, t: 14, b: 28 };
  const ys = points.map((p) => p.y);
  let min = Math.min(...ys), max = Math.max(...ys);
  if (min === max) { min -= 5; max += 5; }
  const pad = (max - min) * 0.1; min = Math.max(0, min - pad); max += pad;
  const x = (i) => P.l + (points.length === 1 ? (W - P.l - P.r) / 2 : (i / (points.length - 1)) * (W - P.l - P.r));
  const y = (v) => P.t + (1 - (v - min) / (max - min)) * (H - P.t - P.b);
  let grid = "";
  for (let k = 0; k <= 4; k++) {
    const v = min + ((max - min) * k) / 4, yy = y(v);
    grid += `<line class="grid-line" x1="${P.l}" x2="${W - P.r}" y1="${yy}" y2="${yy}"/><text class="axis" x="${P.l - 6}" y="${yy + 4}" text-anchor="end">${max - min < 10 ? v.toFixed(1) : Math.round(v)}</text>`;
  }
  const step = Math.ceil(points.length / 6);
  const labels = points.map((p, i) => (i % step === 0 || i === points.length - 1)
    ? `<text class="axis" x="${x(i)}" y="${H - 8}" text-anchor="middle">${new Date(p.x + "T12:00:00").toLocaleDateString("tr-TR", { day: "numeric", month: "short" })}</text>` : "").join("");
  const d = points.map((p, i) => `${i ? "L" : "M"}${x(i)},${y(p.y)}`).join(" ");
  const area = `${d} L${x(points.length - 1)},${H - P.b} L${x(0)},${H - P.b} Z`;
  const dots = points.map((p, i) => `<circle class="dot" cx="${x(i)}" cy="${y(p.y)}" r="3.5"><title>${p.x}: ${p.y.toFixed(1)} ${unit}</title></circle>`).join("");
  el.innerHTML = `<svg viewBox="0 0 ${W} ${H}" preserveAspectRatio="xMidYMid meet">${grid}<path class="area" d="${area}"/><path class="line" d="${d}"/>${dots}${labels}</svg>`;
}

function renderProgress() {
  const used = [...new Set(state.workouts.map((w) => w.exerciseId))];
  const sel = $("#progEx");
  const cur = sel.value;
  sel.innerHTML = used.length
    ? used.map((id) => `<option value="${id}">${esc(exName(id))}</option>`).join("")
    : `<option>Henüz kayıt yok</option>`;
  if (used.includes(cur)) sel.value = cur;
  renderExChart();

  // PR tablosu
  const rows = used.map((id) => {
    const h = exerciseHistory(state.workouts, id);
    const best = h.reduce((a, b) => (b.best1rm > a.best1rm ? b : a), h[0]);
    const top = Math.max(...h.map((x) => x.top));
    return { id, best, top, n: h.length };
  }).sort((a, b) => b.best.best1rm - a.best.best1rm);
  $("#prTable").innerHTML = rows.length ? `<table class="pr"><tr><th>Hareket</th><th>En ağır</th><th>Tahmini 1RM</th><th>Tarih</th><th>Seans</th></tr>
    ${rows.map((r) => `<tr><td>${esc(exName(r.id))}</td><td>${r.top} kg</td><td><b>${r.best.best1rm.toFixed(1)} kg</b></td><td class="muted">${fmtDate(r.best.date)}</td><td class="muted">${r.n}</td></tr>`).join("")}</table>`
    : `<div class="muted small">Henüz rekor yok.</div>`;

  const bw = Object.entries(state.bodyweight).sort(([a], [b]) => a.localeCompare(b)).map(([x, y]) => ({ x, y }));
  lineChart($("#bwChart"), bw, "kg");
}

function renderExChart() {
  const id = $("#progEx").value;
  const metric = $$("input[name=metric]").find((r) => r.checked).value;
  const h = exerciseHistory(state.workouts, id);
  lineChart($("#chart"), h.map((x) => ({ x: x.date, y: x[metric] })), "kg");
  if (!h.length) { $("#prStats").innerHTML = ""; return; }
  const first = h[0].best1rm, last = h[h.length - 1].best1rm;
  const ch = first ? ((last - first) / first) * 100 : 0;
  $("#prStats").innerHTML = `
    <div class="stat"><b>${Math.max(...h.map((x) => x.top))} kg</b><span>En ağır set</span></div>
    <div class="stat"><b>${Math.max(...h.map((x) => x.best1rm)).toFixed(1)} kg</b><span>En iyi tahmini 1RM</span></div>
    <div class="stat"><b style="color:${ch >= 0 ? "var(--accent)" : "var(--danger)"}">${ch >= 0 ? "+" : ""}${ch.toFixed(1)}%</b><span>İlk seanstan beri</span></div>
    <div class="stat"><b>${h.length}</b><span>Seans</span></div>`;
}
$("#progEx").addEventListener("change", renderExChart);
$$("input[name=metric]").forEach((r) => r.addEventListener("change", renderExChart));
$("#bwSave").onclick = () => {
  const v = parseFloat($("#bwInput").value);
  if (!v) return toast("Kilo gir");
  state.bodyweight[todayStr()] = v;
  state.profile.weight = v;
  save(); renderProgress(); toast("Kilo kaydedildi, protein hedefi güncellendi");
};

// ---------- BESLENME ----------
function ring(value, target, label, unit, color) {
  const r = 58, c = 2 * Math.PI * r, pct = target ? Math.min(1, value / target) : 0;
  const done = target && value >= target;
  return `<svg class="ring" width="160" height="160" viewBox="0 0 160 160">
    <circle class="bg" cx="80" cy="80" r="${r}" fill="none" stroke-width="14"/>
    <circle class="fg" cx="80" cy="80" r="${r}" fill="none" stroke="${color}" stroke-width="14" stroke-linecap="round"
      stroke-dasharray="${c}" stroke-dashoffset="${c * (1 - pct)}" transform="rotate(-90 80 80)"/>
    <text x="80" y="76" text-anchor="middle" font-size="26" font-weight="800">${value}${unit}</text>
    <text x="80" y="98" text-anchor="middle" font-size="12" fill="#8b93a5">hedef ${target}${unit} ${done ? "✓" : ""}</text>
  </svg>`;
}

function renderNutrition() {
  const t = targets(), d = dayNut();
  const p = d.protein.reduce((a, x) => a + x.g, 0);
  $("#ringProtein").innerHTML = ring(p, t.protein, "Protein", "g", "#4ade80");
  $("#creatineCard").classList.toggle("hidden", state.profile.creatine === "no");
  const kcal = calorieTarget(state.profile);
  const left = Math.max(0, targets().protein - dayNut().protein.reduce((a, x) => a + x.g, 0));
  $("#kcalNote").textContent = (left ? hey(`Bugün ${left} g protein daha almalısın.`) : hey("Bugünkü protein hedefini tamamladın! 🎉")) +
    (kcal ? ` Günlük kalori hedefin ~${kcal} kcal.` : "");
  $("#ringCreatine").innerHTML = ring(d.creatine, t.creatine, "Kreatin", "g", "#60a5fa");
  $("#ringWater").innerHTML = ring(+(d.water / 1000).toFixed(2), t.water / 1000, "Su", "L", "#38bdf8");
  $("#foodChips").innerHTML = PROTEIN_FOODS.map((f, i) => `<button class="chip" data-food="${i}">${esc(f.name)} · ${f.g}g</button>`).join("");
  $("#proteinLog").innerHTML = d.protein.map((x, i) => `<div class="log-item"><span>${esc(x.name)}</span><span>${x.g} g <button class="icon-btn" data-delp="${i}">✕</button></span></div>`).join("");

  // Kreatin serisi
  let streak = 0;
  for (let i = 0; i < 365; i++) {
    const k = todayStr(new Date(Date.now() - i * DAY));
    if ((state.nutrition[k]?.creatine || 0) >= 3) streak++;
    else if (i > 0) break;
  }
  $("#crStreak").textContent = `🔥 ${streak} günlük kreatin serisi. ${state.profile.creatineLoading ? "Yükleme fazı: gün içine 4 doza böl." : "Her gün aynı saatte 3-5 g yeterli, antrenman olmayan günler dahil."}`;

  // 7 gün
  const rows = [];
  for (let i = 6; i >= 0; i--) {
    const k = todayStr(new Date(Date.now() - i * DAY));
    const n = state.nutrition[k] || { protein: [], creatine: 0 };
    const pg = n.protein.reduce((a, x) => a + x.g, 0);
    rows.push(`<div class="nut-row"><span class="muted">${fmtDate(k)}</span>
      <div title="Protein ${pg}/${t.protein} g"><div class="mini"><div style="width:${Math.min(100, (pg / t.protein) * 100 || 0)}%;background:#4ade80"></div></div><span class="small muted">${pg} g protein</span></div>
      <div title="Kreatin"><div class="mini"><div style="width:${Math.min(100, (n.creatine / t.creatine) * 100 || 0)}%;background:#60a5fa"></div></div><span class="small muted">${n.creatine} g kreatin</span></div>
    </div>`);
  }
  $("#nutHistory").innerHTML = rows.join("");
}
$("#foodChips").addEventListener("click", (e) => {
  const f = e.target.closest("[data-food]");
  if (!f) return;
  const food = PROTEIN_FOODS[f.dataset.food];
  dayNut().protein.push({ name: food.name, g: food.g });
  save(); renderNutrition();
});
$("#addProtein").onclick = () => {
  const g = parseInt($("#customProtein").value);
  if (!g) return;
  dayNut().protein.push({ name: "Özel", g });
  $("#customProtein").value = "";
  save(); renderNutrition();
};
$("#proteinLog").addEventListener("click", (e) => {
  const d = e.target.closest("[data-delp]");
  if (!d) return;
  dayNut().protein.splice(Number(d.dataset.delp), 1);
  save(); renderNutrition();
});
$$("[data-cr]").forEach((b) => (b.onclick = () => { dayNut().creatine += Number(b.dataset.cr); save(); renderNutrition(); }));
$("#resetCr").onclick = () => { dayNut().creatine = 0; save(); renderNutrition(); };
$$("[data-water]").forEach((b) => (b.onclick = () => { dayNut().water += Number(b.dataset.water); save(); renderNutrition(); }));
$("#resetWater").onclick = () => { dayNut().water = 0; save(); renderNutrition(); };

// ---------- AYARLAR ----------
let cxP = new Set(), cxS = new Set();
function renderSettings() {
  const p = state.profile;
  $("#pProtein").value = p.proteinOverride || "";
  $("#apiKey").value = state.apiKey || "";
  const t = targets(), kcal = calorieTarget(p);
  $("#profileSummary").innerHTML = p.onboarded ? `
    <div class="kv"><span>Ad</span><b>${esc(p.name)}</b></div>
    <div class="kv"><span>Yaş / Boy / Kilo</span><b>${p.age} · ${p.height} cm · ${p.weight} kg</b></div>
    <div class="kv"><span>Hedef</span><b>${GOALS[goalKey(p.goal)].icon} ${GOALS[goalKey(p.goal)].name}</b></div>
    <div class="kv"><span>Seviye</span><b>${LEVELS[p.level]?.name || "-"}</b></div>
    <div class="kv"><span>Program</span><b>Haftada ${daysLabel(p)} gün · ${p.sessionMin} dk</b></div>
    <div class="kv"><span>Ekipman</span><b>${EQUIPMENT[p.equipment]?.name || "-"}</b></div>
    <div class="kv"><span>Öncelik</span><b>${(p.priorities || []).map((m) => MUSCLES[m].name).join(", ") || "Yok"}</b></div>
    <div class="kv"><span>Sakatlık</span><b>${(p.injuries || []).map((k) => INJURY_RISK[k].name).join(", ") || "Yok"}</b></div>` : `<p class="muted">Henüz soruları yanıtlamadın.</p>`;
  $("#targetsPreview").textContent = `Günlük hedef: ${t.protein} g protein${t.creatine ? ` · ${t.creatine} g kreatin` : ""}${kcal ? ` · ${kcal} kcal` : ""} · ${(t.water / 1000).toFixed(1)} L su`;
  const chips = (set, id) => Object.entries(MUSCLES).map(([k, m]) => `<button class="chip ${set.has(k) ? "on" : ""}" data-k="${k}">${m.name}</button>`).join("");
  $("#cxPrimary").innerHTML = chips(cxP);
  $("#cxSecondary").innerHTML = chips(cxS);
}
$("#cxPrimary").addEventListener("click", (e) => { const k = e.target.dataset.k; if (!k) return; cxP.has(k) ? cxP.delete(k) : cxP.add(k); renderSettings(); });
$("#cxSecondary").addEventListener("click", (e) => { const k = e.target.dataset.k; if (!k) return; cxS.has(k) ? cxS.delete(k) : cxS.add(k); renderSettings(); });
$("#cxSave").onclick = () => {
  const name = $("#cxName").value.trim();
  if (!name || !cxP.size) return toast("Ad ve en az bir birincil kas seç");
  state.customExercises.push({ id: "c_" + uid(), name, cat: "Özel", p: [...cxP], s: [...cxS].filter((k) => !cxP.has(k)) });
  cxP.clear(); cxS.clear(); $("#cxName").value = "";
  save(); renderSettings(); renderCats(); renderExList(); toast("Hareket eklendi");
};
$("#saveProfile").onclick = () => {
  state.profile.proteinOverride = $("#pProtein").value;
  save(); renderSettings(); toast("Kaydedildi");
};
$("#redoWizard").onclick = () => openWizard();
$("#regenProgram").onclick = () => {
  if (state.program?.mode === "manual" && !confirm("Kendi planın silinip otomatik plan oluşturulacak. Emin misin?")) return;
  state.program = generateProgram(state.profile, state.customExercises);
  save(); toast("Program yeniden oluşturuldu"); $(`#tabs button[data-tab="plan"]`).click();
};
$("#saveKey").onclick = () => { state.apiKey = $("#apiKey").value.trim(); save(); toast("API anahtarı kaydedildi"); };
$("#export").onclick = () => {
  const data = { ...state, apiKey: "" };
  if (window.Capacitor?.isNativePlatform?.()) {
    // Android uygulamasında indirme bağlantısı çalışmaz: yedeği panoya kopyala
    navigator.clipboard.writeText(JSON.stringify(data)).then(
      () => toast("Yedek panoya kopyalandı. Bir nota yapıştırıp sakla."),
      () => toast("Kopyalanamadı"));
    return;
  }
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }));
  a.download = `fitai-yedek-${todayStr()}.json`;
  a.click();
};
function importJson(text) {
  const data = JSON.parse(text);
  state = { ...defaultState(), ...data, apiKey: state.apiKey };
  save(); renderAll(); toast("Veriler içe aktarıldı");
}
$("#importText").onclick = () => {
  const t = prompt("Yedek metnini (JSON) buraya yapıştır:");
  if (!t) return;
  try { importJson(t); } catch { toast("Geçersiz yedek"); }
};
$("#import").onchange = async (e) => {
  const f = e.target.files[0];
  if (!f) return;
  try {
    const data = JSON.parse(await f.text());
    state = { ...defaultState(), ...data, apiKey: state.apiKey };
    save(); renderAll(); toast("Veriler içe aktarıldı");
  } catch { toast("Geçersiz dosya"); }
};
$("#reset").onclick = () => {
  if (!confirm("Tüm antrenman ve beslenme verileri silinecek. Emin misin?")) return;
  state = { ...defaultState(), apiKey: state.apiKey };
  save(); renderAll(); toast("Veriler silindi"); openWizard();
};
$("#demo").onclick = () => {
  if (state.workouts.length && !confirm("Mevcut verilerin üzerine örnek veri eklensin mi?")) return;
  loadDemo(); save(); renderAll(); toast("Örnek veri yüklendi");
};

// Örnek veri: 4 haftalık, bilerek dengesiz (omuz/göğüs fazla, bacak ve arka omuz az)
function loadDemo() {
  const plan = [
    { off: 0, ex: [["bench", 80, 8, 4], ["incline_db", 30, 10, 3], ["ohp", 50, 8, 4], ["lateral_raise", 10, 15, 4], ["pushdown", 30, 12, 3]] },
    { off: 2, ex: [["lat_pulldown", 60, 10, 3], ["bb_curl", 30, 10, 3], ["front_raise", 10, 12, 3], ["db_shoulder", 24, 10, 4]] },
    { off: 4, ex: [["bench", 82.5, 6, 4], ["cable_fly", 15, 12, 3], ["arnold", 18, 10, 4], ["lateral_raise", 10, 15, 4], ["leg_ext", 50, 12, 3]] },
  ];
  for (let wk = 3; wk >= 0; wk--) {
    for (const day of plan) {
      const date = todayStr(new Date(Date.now() - (wk * 7 + (6 - day.off)) * DAY));
      for (const [id, kg, reps, n] of day.ex) {
        const prog = (3 - wk) * (kg >= 40 ? 2.5 : 1);
        state.workouts.push({ id: uid(), date, exerciseId: id, sets: Array.from({ length: n }, (_, i) => ({ kg: kg + prog, reps: Math.max(1, reps - (i > 1 ? 1 : 0)) })) });
      }
    }
  }
  for (let i = 0; i < 7; i++) {
    const k = todayStr(new Date(Date.now() - i * DAY));
    state.nutrition[k] = { protein: [{ name: "Whey (1 ölçek)", g: 24 }, { name: "Tavuk göğsü (100g)", g: 31 * (1 + (i % 3)) }, { name: "Yumurta (1 adet)", g: 18 }], creatine: i % 4 === 3 ? 0 : 5, water: 2000 };
  }
  for (let i = 28; i >= 0; i -= 4) state.bodyweight[todayStr(new Date(Date.now() - i * DAY))] = +(76 - i * 0.03).toFixed(1);
}

// ---------- PROGRAM ----------
function weekWorkoutDays() {
  return new Set(state.workouts.filter((w) => dateToMs(w.date) > Date.now() - 7 * DAY).map((w) => w.date)).size;
}
function nextDayIndex() {
  const prog = state.program;
  const doneDates = new Set(state.workouts.filter((w) => w.date >= prog.created && w.date < todayStr()).map((w) => w.date));
  return doneDates.size % prog.days.length;
}
// renderPlan, plan düzenleyici ve plan kontrolü: planner.js

// ---------- Render ----------
function render(tab) {
  if (tab === "plan") renderPlan();
  if (tab === "workout" && typeof renderSessionBar === "function") renderSessionBar();
  if (tab === "workout") { renderCats(); renderExList(); renderDayLog(); renderHistory(); }
  if (tab === "analysis") renderAnalysis();
  if (tab === "progress") renderProgress();
  if (tab === "nutrition") renderNutrition();
  if (tab === "settings") renderSettings();
}
function renderAll() {
  const active = $("#tabs button.active").dataset.tab;
  render("workout"); render(active); updateScore();
}

let startTab = "plan";
try { startTab = sessionStorage.getItem("fitai-tab") || "plan"; } catch {}
const startBtn = $(`#tabs button[data-tab="${startTab}"]`);
if (startBtn) startBtn.click(); else render("workout");
renderAll();
initWizard();
initPlanner();
if (!state.profile.onboarded) openWizard();

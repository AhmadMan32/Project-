// Kişiselleştirme: başlangıç soruları, kişisel hedefler ve otomatik program
const GOALS = {
  muscle:   { name: "Kas kazanmak", icon: "💪", desc: "Hacim ve kas kütlesi", protein: 2.0, kcal: 300, reps: "8-12", sets: 3 , cheer: "Kas kazanmak için doğru yerdesin" },
  fat:      { name: "Yağ yakmak", icon: "🔥", desc: "Kasları koruyup yağ kaybetmek", protein: 2.2, kcal: -450, reps: "10-15", sets: 3 , cheer: "Yağ yakarken kaslarını koruyacağız" },
  strength: { name: "Güçlenmek", icon: "🏋️", desc: "Daha ağır kaldırmak", protein: 1.8, kcal: 200, reps: "4-6", sets: 4 , cheer: "Ağırlıkları birlikte artıracağız" },
  fit:      { name: "Formda kalmak", icon: "🏃", desc: "Genel sağlık ve kondisyon", protein: 1.6, kcal: 0, reps: "10-12", sets: 3 , cheer: "Sağlıklı ve formda kalman için plan yapacağız" },
};
const LEGACY_GOAL = { bulk: "muscle", cut: "fat", maintain: "fit" };
const goalKey = (g) => LEGACY_GOAL[g] || (GOALS[g] ? g : "muscle");

const LEVELS = {
  beginner:     { name: "Yeni başlıyorum", desc: "0-6 ay", scale: 0.7 },
  intermediate: { name: "Orta seviye", desc: "6 ay - 2 yıl", scale: 1.0 },
  advanced:     { name: "İleri seviye", desc: "2 yıldan fazla", scale: 1.2 },
};
const EQUIPMENT = {
  gym: { name: "Spor salonu", icon: "🏢", desc: "Halter, makineler, kablolar" },
  db:  { name: "Evde dumbbell", icon: "🏠", desc: "Dumbbell / kettlebell" },
  bw:  { name: "Ekipmansız", icon: "🤸", desc: "Sadece vücut ağırlığı" },
};

// Kişiye göre haftalık set hedefi
function muscleTarget(m, profile = {}) {
  const base = MUSCLES[m];
  const scale = (LEVELS[profile.level] || LEVELS.intermediate).scale;
  // Haftalık süre bütçesi: 4 gün × 60 dk (~96 set) tam hedefe yeter; daha az zamanda alt sınır orantılı düşer
  const budget = ((+profile.days || 4) * (+profile.sessionMin || 60)) / 2.5;
  const timeScale = Math.min(1, Math.max(0.6, budget / 96));
  // Küçük yardımcı kaslar (ön kol, trapez, bel, yan karın, iç bacak) çoğunlukla bileşik hareketlerden çalışır;
  // haftalık zaman kısıtlıysa zorunlu hedef sayılmaz
  let min = base.minor && timeScale < 0.8 ? 0 : Math.max(2, Math.round(base.min * scale * timeScale));
  let max = Math.round(base.max * scale);
  if ((profile.priorities || []).includes(m)) { min += 3; max += 4; }
  return { min, max };
}

// Ekipmana ve sakatlıklara göre yapılabilir hareketler
function allowedExercises(profile = {}, custom = []) {
  const eq = EQ_LEVEL[profile.equipment] ?? 2;
  const avoid = new Set((profile.injuries || []).flatMap((k) => INJURY_RISK[k]?.avoid || []));
  return [...EXERCISES, ...custom].filter((e) => (EQ_LEVEL[e.eq] ?? 2) <= eq && !avoid.has(e.id));
}
function riskyFor(exId, profile = {}) {
  return (profile.injuries || []).filter((k) => INJURY_RISK[k]?.avoid.includes(exId)).map((k) => INJURY_RISK[k].name);
}

// ---------- Program üretici ----------
const DAY_TEMPLATES = {
  fullA:  { name: "Tüm Vücut A", m: ["quads", "chest", "lats", "hamstrings", "side_delt", "abs", "biceps", "calves"] },
  fullB:  { name: "Tüm Vücut B", m: ["hamstrings", "upper_back", "front_delt", "quads", "triceps", "biceps", "obliques", "rear_delt"] },
  fullC:  { name: "Tüm Vücut C", m: ["glutes", "chest", "lats", "rear_delt", "quads", "calves", "abs", "triceps"] },
  upperA: { name: "Üst Vücut A", m: ["chest", "lats", "front_delt", "upper_back", "side_delt", "biceps", "triceps", "rear_delt"] },
  upperB: { name: "Üst Vücut B", m: ["upper_back", "chest", "lats", "side_delt", "rear_delt", "triceps", "biceps", "traps"] },
  lowerA: { name: "Alt Vücut A", m: ["quads", "hamstrings", "glutes", "calves", "abs", "adductors", "quads", "obliques"] },
  lowerB: { name: "Alt Vücut B", m: ["hamstrings", "quads", "glutes", "adductors", "calves", "obliques", "lower_back", "abs"] },
  push:   { name: "İtme (Push)", m: ["chest", "front_delt", "chest", "side_delt", "triceps", "triceps", "side_delt", "abs"] },
  pull:   { name: "Çekme (Pull)", m: ["lats", "upper_back", "lats", "rear_delt", "biceps", "biceps", "traps", "forearm"] },
  legs:   { name: "Bacak (Legs)", m: ["quads", "hamstrings", "glutes", "quads", "calves", "abs", "adductors", "obliques"] },
};
const SPLITS = {
  2: { name: "2 Gün Tüm Vücut", days: ["fullA", "fullB"] },
  3: { name: "3 Gün Tüm Vücut", days: ["fullA", "fullB", "fullC"] },
  4: { name: "Üst / Alt (4 gün)", days: ["upperA", "lowerA", "upperB", "lowerB"] },
  5: { name: "PPL + Üst/Alt (5 gün)", days: ["push", "pull", "legs", "upperA", "lowerA"] },
  6: { name: "Push / Pull / Legs ×2", days: ["push", "pull", "legs", "push", "pull", "legs"] },
};
const PER_SESSION = { 30: 4, 45: 5, 60: 6, 90: 8 };

// Her kas için en verimliden başlayarak tercih sırası (uygun olmayanlar elenir)
const PREFERRED = {
  chest: ["bench", "incline_db", "db_bench", "incline_bench", "chest_press", "cable_fly", "pec_deck", "db_fly", "dips_chest", "pushup"],
  front_delt: ["ohp", "db_shoulder", "shoulder_machine", "arnold", "front_raise", "pushup", "pike_pushup"],
  side_delt: ["lateral_raise", "cable_lateral", "upright_row", "arnold", "bag_lateral"],
  rear_delt: ["face_pull", "reverse_pec", "rear_fly", "prone_yt"],
  biceps: ["bb_curl", "incline_curl", "db_curl", "hammer", "cable_curl", "preacher", "concentration", "bag_curl"],
  triceps: ["rope_pushdown", "oh_ext", "close_bench", "skullcrusher", "pushdown", "kickback", "dips_tri", "diamond_pushup"],
  forearm: ["hammer", "wrist_curl", "farmer", "towel_hang"],
  traps: ["shrug", "farmer", "upright_row", "bag_shrug"],
  lats: ["pullup", "lat_pulldown", "db_row", "close_pulldown", "pullover", "chinup", "door_row"],
  upper_back: ["barbell_row", "cable_row", "db_row", "machine_row", "tbar_row", "rear_fly", "door_row", "prone_yt"],
  lower_back: ["hyperext", "rdl", "good_morning", "glute_bridge", "superman"],
  abs: ["cable_crunch", "leg_raise", "ab_wheel", "plank", "crunch", "mountain_climber"],
  obliques: ["woodchop", "russian_twist", "side_plank", "bicycle"],
  glutes: ["hip_thrust", "bulgarian", "rdl", "glute_bridge", "abductor", "kb_swing", "split_squat_bw"],
  quads: ["squat", "leg_press", "hack_squat", "goblet", "bulgarian", "front_squat", "lunge", "leg_ext", "step_up", "split_squat_bw", "bw_squat", "wall_sit"],
  hamstrings: ["rdl", "leg_curl", "seated_curl", "good_morning", "kb_swing", "sl_rdl_bw", "nordic", "glute_bridge"],
  adductors: ["adductor_m", "goblet", "lunge", "bw_squat"],
  calves: ["calf_raise", "seated_calf", "bw_calf"],
};

function generateProgram(profile, custom = []) {
  const split = SPLITS[profile.days] || SPLITS[3];
  const goal = GOALS[goalKey(profile.goal)];
  const pool = allowedExercises(profile, custom);
  const count = PER_SESSION[profile.sessionMin] || 6;
  const prio = profile.priorities || [];
  const lvlSets = profile.level === "beginner" ? -1 : profile.level === "advanced" ? 1 : 0;

  const days = split.days.map((key, di) => {
    const tpl = DAY_TEMPLATES[key];
    const repeat = split.days.slice(0, di).filter((k) => k === key).length; // PPL×2'de ikinci tur farklı olsun
    let slots = [...tpl.m];
    // Öncelikli kaslar: bu günde varsa öne al ve bir slot daha ekle
    for (const p of prio) if (slots.includes(p)) slots.splice(2, 0, p);
    slots = slots.slice(0, count);

    const used = new Set();
    const exercises = [];
    slots.forEach((m, si) => {
      const ok = new Map(pool.map((e) => [e.id, e]));
      let cands = (PREFERRED[m] || []).filter((id) => ok.has(id) && !used.has(id)).map((id) => ok.get(id));
      for (const e of pool) if (e.p[0] === m && !used.has(e.id) && !cands.includes(e)) cands.push(e);
      if (!cands.length) return;
      // aynı kas günde ikinci kez geliyorsa veya farklı günlerde çeşitlilik için sırayı kaydır
      const seen = slots.slice(0, si).filter((x) => x === m).length;
      const ex = cands[(seen + repeat) % Math.min(cands.length, 3)];
      used.add(ex.id);
      const compound = ex.p.length + ex.s.length >= 3;
      let sets = Math.max(2, goal.sets + lvlSets + (si === 0 ? 1 : 0) - (goal === GOALS.strength && !compound ? 1 : 0));
      const reps = goal === GOALS.strength && !compound ? "8-10" : ex.id === "plank" || ex.id === "side_plank" ? "30-60 sn" : goal.reps;
      exercises.push({ id: ex.id, sets, reps, muscle: m });
    });
    return { key, name: tpl.name, exercises };
  });
  const prog = { mode: "auto", split: split.name, days, created: todayStr() };
  balanceProgram(prog, profile, custom); // planner.js: plan kontrolünden geçene kadar dengele
  return prog;
}

function calorieTarget(p) {
  const w = +p.weight, h = +p.height, a = +p.age;
  if (!w || !h || !a) return null;
  const bmr = 10 * w + 6.25 * h - 5 * a + (p.sex === "f" ? -161 : 5);
  const tdee = bmr * (1.2 + 0.075 * (+p.days || 3));
  return Math.round((tdee + GOALS[goalKey(p.goal)].kcal) / 10) * 10;
}

// ---------- Başlangıç sihirbazı ----------
const WIZARD_STEPS = [
  { id: "name", title: () => "Merhaba! 👋", sub: () => "Ben senin FitAI koçunum. Sana özel bir program hazırlamak için birkaç soru soracağım. Önce tanışalım: adın ne?",
    render: (p) => `<input id="wz_name" placeholder="Adını yaz" autocomplete="given-name" value="${esc(p.name || "")}" autofocus />`,
    read: (p) => { p.name = niceName($("#wz_name").value); return !!p.name || "Sana nasıl hitap edeyim? Adını yaz"; } },
  { id: "body1", title: (p) => `Memnun oldum ${p.name}! 🤝`, sub: () => "Seni biraz tanıyalım. Kalori ve su hedefini hesaplamak için cinsiyetin ve yaşın lazım.",
    render: (p) => `${cards("sex", { m: { name: "Erkek", icon: "♂️" }, f: { name: "Kadın", icon: "♀️" } }, p.sex, true)}
      <label>Yaş <input type="number" id="wz_age" inputmode="numeric" value="${p.age || ""}" placeholder="örn. 25" /></label>`,
    read: (p) => { p.age = +$("#wz_age").value; return (!!p.sex && p.age >= 12 && p.age < 100) || "Cinsiyet ve yaşını gir"; } },
  { id: "body2", title: (p) => `${p.name}, boyun ve kilon kaç?`, sub: (p) => `${p.age} yaşındasın, güzel. Protein hedefini kilona göre belirleyeceğim.`,
    render: (p) => `<label>Boy (cm) <input type="number" id="wz_h" inputmode="numeric" value="${p.height || ""}" placeholder="örn. 178" /></label>
      <label>Kilo (kg) <input type="number" step="0.1" id="wz_w" inputmode="decimal" value="${p.weight || ""}" placeholder="örn. 75" /></label>`,
    read: (p) => { p.height = +$("#wz_h").value; p.weight = +$("#wz_w").value; return (p.height > 100 && p.weight > 30) || "Boy ve kilonu gir"; } },
  { id: "goal", title: (p) => `${p.name}, ana hedefin ne?`, sub: () => "Set, tekrar ve beslenme hedeflerini buna göre ayarlayacağım.",
    render: (p) => cards("goal", GOALS, goalKey(p.goal)), read: (p) => !!p.goal || "Bir hedef seç" },
  { id: "level", title: () => "Ne kadar süredir spor yapıyorsun?", sub: (p) => `${GOALS[p.goal].cheer}, ${p.name}. Şimdi antrenman hacmini seviyene göre ayarlayalım.`,
    render: (p) => cards("level", LEVELS, p.level), read: (p) => !!p.level || "Seviyeni seç" },
  { id: "days", title: (p) => `${p.name}, haftada kaç gün ayırabilirsin?`, sub: (p) => p.level === "beginner" ? "Yeni başlıyorsan 3 gün harika bir başlangıç. Programın bölünmesini buna göre seçeceğim." : "Programın bölünmesini gün sayısına göre seçeceğim.",
    render: (p) => `<div class="num-pick">${[2, 3, 4, 5, 6].map((d) => `<button class="opt ${p.days === d ? "on" : ""}" data-set="days" data-val="${d}">${d}</button>`).join("")}</div>
      <div class="muted small" style="margin:14px 0 6px">Bir antrenman ne kadar sürsün?</div>
      <div class="num-pick">${[30, 45, 60, 90].map((d) => `<button class="opt ${p.sessionMin === d ? "on" : ""}" data-set="sessionMin" data-val="${d}">${d} dk</button>`).join("")}</div>`,
    read: (p) => (!!p.days && !!p.sessionMin) || "Gün ve süre seç" },
  { id: "equipment", title: () => "Nerede antrenman yapıyorsun?", sub: (p) => `Haftada ${p.days} gün, ${p.sessionMin} dakika, not ettim. Sana sadece yapabileceğin hareketleri önereceğim.`,
    render: (p) => cards("equipment", EQUIPMENT, p.equipment), read: (p) => !!p.equipment || "Bir seçenek seç" },
  { id: "planMode", title: (p) => `${p.name}, programını nasıl oluşturalım?`,
    sub: () => "İki yol var. İstediğin zaman Program sekmesinden değiştirebilirsin.",
    render: (p) => cards("planMode", {
      auto: { name: "Benim için oluştur", icon: "🤖", desc: "Cevaplarına göre dengeli bir program hazırlarım. İstersen sonra düzenlersin." },
      manual: { name: "Kendim seçeceğim", icon: "✍️", desc: "Hareketleri sen seçersin. Ben eksik, fazla ve riskli noktaları anında gösteririm." },
    }, p.planMode), read: (p) => !!p.planMode || "Bir seçenek seç" },
  { id: "priorities", title: (p) => `${p.name}, özellikle geliştirmek istediğin bir bölge var mı?`, sub: () => "En fazla 3 tane seç, bu kaslara daha fazla hacim vereceğim. İstersen boş geçebilirsin.",
    render: (p) => `<div class="chips">${Object.entries(MUSCLES).map(([k, m]) => `<button class="chip ${(p.priorities || []).includes(k) ? "on" : ""}" data-multi="priorities" data-val="${k}">${m.name}</button>`).join("")}</div>`,
    read: () => true },
  { id: "injuries", title: () => "Sakatlık ya da ağrın var mı?", sub: (p) => `${p.name}, seçtiğin bölgeyi zorlayan hareketleri programına koymayacağım.`,
    render: (p) => `<div class="chips">${Object.entries(INJURY_RISK).map(([k, v]) => `<button class="chip ${(p.injuries || []).includes(k) ? "on" : ""}" data-multi="injuries" data-val="${k}">${v.name}</button>`).join("")}
      <button class="chip ${p.injuries && !p.injuries.length ? "on" : ""}" data-none="injuries">Yok</button></div>
      <p class="muted small">Ağrın devam ediyorsa bir doktora veya fizyoterapiste danış.</p>`,
    read: (p) => { p.injuries ||= []; return true; } },
  { id: "creatine", title: () => "Son soru: kreatin kullanıyor musun?", sub: () => "Günlük kreatin takibini buna göre ayarlayacağım.",
    render: (p) => cards("creatine", {
      yes: { name: "Evet, kullanıyorum", icon: "✅", desc: "Günde 5 g takip" },
      start: { name: "Başlamak istiyorum", icon: "🚀", desc: "İlk hafta yükleme (0.3 g/kg), sonra 5 g" },
      no: { name: "Hayır", icon: "➖", desc: "Kreatin takibi gizlenir" },
    }, p.creatine), read: (p) => !!p.creatine || "Bir seçenek seç" },
  { id: "summary", title: (p) => p.planMode === "manual" ? `${p.name}, her şey hazır! 🎉` : `${p.name}, planın hazır! 🎉`, sub: (p) => p.planMode === "manual" ? `${GOALS[p.goal].name} hedefin için hedeflerini belirledim:` : `${GOALS[p.goal].name} hedefin için cevaplarına göre hazırladığım plan:`, render: (p) => summaryHtml(p), read: () => true },
];

// "ali veli" -> "Ali Veli" (Türkçe büyük harf kurallarıyla: i -> İ)
function niceName(v) {
  return v.trim().replace(/\s+/g, " ").slice(0, 30)
    .split(" ").map((w) => w.charAt(0).toLocaleUpperCase("tr") + w.slice(1).toLocaleLowerCase("tr")).join(" ");
}

function cards(field, opts, cur, compact) {
  return `<div class="opt-cards ${compact ? "compact" : ""}">${Object.entries(opts).map(([k, o]) => `
    <button class="opt-card ${cur === k ? "on" : ""}" data-set="${field}" data-val="${k}">
      ${o.icon ? `<span class="oi">${o.icon}</span>` : ""}<span><b>${o.name}</b>${o.desc ? `<small>${o.desc}</small>` : ""}</span>
    </button>`).join("")}</div>`;
}

function summaryHtml(p) {
  const t = nutritionTargets(p);
  const kcal = calorieTarget(p);
  const statsHtml = `<div class="sum-grid">
      <div class="stat"><b>${t.protein} g</b><span>Günlük protein</span></div>
      ${p.creatine !== "no" ? `<div class="stat"><b>${t.creatine} g</b><span>Kreatin</span></div>` : ""}
      ${kcal ? `<div class="stat"><b>${kcal}</b><span>Kalori (kcal)</span></div>` : ""}
      <div class="stat"><b>${(t.water / 1000).toFixed(1)} L</b><span>Su</span></div>
    </div>`;
  if (p.planMode === "manual") {
    const big = ["chest", "lats", "upper_back", "quads", "hamstrings", "glutes", "side_delt"];
    return statsHtml + `<h3>Programını sen kuracaksın ✍️</h3>
      <p class="muted small">Program sekmesinde ${p.days} günlük boş bir plan seni bekliyor. Her güne hareket ekledikçe kas haritası ve uyarılar güncellenecek.
      Seviyene göre haftalık set hedeflerin (örnekler):</p>
      ${big.map((m) => `<div class="kv"><span>${MUSCLES[m].name}${(p.priorities || []).includes(m) ? " ⭐" : ""}</span><b>${muscleTarget(m, p).min}-${muscleTarget(m, p).max} set</b></div>`).join("")}`;
  }
  const prog = generateProgram(p, state.customExercises);
  return statsHtml + `
    <h3>${prog.split}</h3>
    ${prog.days.map((d, i) => `<div class="sum-day"><b>${i + 1}. Gün · ${d.name}</b><span class="muted small">${d.exercises.map((e) => exName(e.id)).join(", ")}</span></div>`).join("")}`;
}

let wz = null;
function openWizard() {
  wz = { i: 0, p: JSON.parse(JSON.stringify(state.profile.onboarded ? state.profile : {})) };
  if (wz.p.goal) wz.p.goal = goalKey(wz.p.goal);
  $("#wizard").classList.remove("hidden");
  renderWizard();
}
function renderWizard() {
  const s = WIZARD_STEPS[wz.i];
  $("#wzBar").style.width = ((wz.i + 1) / WIZARD_STEPS.length) * 100 + "%";
  $("#wzStep").textContent = `${wz.i + 1} / ${WIZARD_STEPS.length}`;
  $("#wzTitle").textContent = s.title(wz.p);
  $("#wzSub").textContent = s.sub(wz.p);
  $("#wzBody").innerHTML = s.render(wz.p);
  $("#wzBack").style.visibility = wz.i ? "visible" : "hidden";
  $("#wzNext").textContent = wz.i === WIZARD_STEPS.length - 1 ? "Başlayalım 🚀" : "Devam";
  $("#wzErr").textContent = "";
  $("#wzBody").querySelector("input")?.focus();
}
function wizardNext() {
  const s = WIZARD_STEPS[wz.i];
  const ok = s.read(wz.p);
  if (ok !== true) { $("#wzErr").textContent = ok; return; }
  if (wz.i < WIZARD_STEPS.length - 1) { wz.i++; renderWizard(); return; }
  finishWizard();
}
function finishWizard() {
  const p = wz.p;
  p.onboarded = true;
  p.creatineLoading = p.creatine === "start";
  p.loadingUntil = p.creatine === "start" ? todayStr(new Date(Date.now() + 7 * 86400000)) : null;
  p.proteinOverride = state.profile.proteinOverride || "";
  state.profile = p;
  if (p.weight) state.bodyweight[todayStr()] ||= p.weight;
  const prev = state.program;
  if (p.planMode === "manual") {
    // önceki kendi planını koru; yoksa seçilen gün sayısında boş plan aç
    state.program = prev?.mode === "manual" && prev.days.some((d) => d.exercises.length) ? prev : emptyProgram(p.days);
  } else {
    state.program = generateProgram(p, state.customExercises);
  }
  save();
  $("#wizard").classList.add("hidden");
  renderAll();
  $(`#tabs button[data-tab="plan"]`).click();
  toast(p.planMode === "manual" ? `Hoş geldin ${p.name}! Şimdi programını kur, ben kontrol edeceğim ✍️` : `Hoş geldin ${p.name}! Programın hazır, hadi başlayalım 💪`);
}

function initWizard() {
  $("#wzNext").onclick = wizardNext;
  $("#wzBack").onclick = () => { if (wz.i) { wz.i--; renderWizard(); } };
  $("#wzBody").addEventListener("keydown", (e) => { if (e.key === "Enter") wizardNext(); });
  $("#wzBody").addEventListener("click", (e) => {
    const b = e.target.closest("button");
    if (!b) return;
    const p = wz.p;
    if (b.dataset.set) {
      const v = b.dataset.val;
      p[b.dataset.set] = /^\d+$/.test(v) ? +v : v;
      // girilmiş alanları kaybetmeden yeniden çiz
      const inputs = [...$("#wzBody").querySelectorAll("input")].map((i) => [i.id, i.value]);
      renderWizard();
      for (const [id, val] of inputs) if ($("#" + id)) $("#" + id).value = val;
    } else if (b.dataset.multi) {
      const arr = (p[b.dataset.multi] ||= []);
      const k = b.dataset.val, ix = arr.indexOf(k);
      if (ix >= 0) arr.splice(ix, 1);
      else if (b.dataset.multi !== "priorities" || arr.length < 3) arr.push(k);
      else return toast("En fazla 3 bölge seçebilirsin");
      renderWizard();
    } else if (b.dataset.none) {
      p[b.dataset.none] = [];
      renderWizard();
    }
  });
}

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

// Haftalık gün aralığı: days = en fazla (programın döngü uzunluğu), daysMin = en az (yoksa days)
const daysMinOf = (p) => +p.daysMin || +p.days || 0;
const daysLabel = (p) => (daysMinOf(p) && daysMinOf(p) < +p.days ? `${daysMinOf(p)}-${p.days}` : `${p.days || "-"}`);

// Kişiye göre haftalık set hedefi
function muscleTarget(m, profile = {}) {
  const base = MUSCLES[m];
  const scale = (LEVELS[profile.level] || LEVELS.intermediate).scale;
  // Haftalık süre bütçesi: 4 gün × 60 dk (~96 set) tam hedefe yeter; daha az zamanda alt sınır orantılı düşer
  const budget = ((daysMinOf(profile) || 4) * (+profile.sessionMin || 60)) / 2.5; // az gelinen hafta esas alınır
  const timeScale = Math.min(1, Math.max(0.6, budget / 96));
  // Küçük yardımcı kaslar (ön kol, trapez, bel, yan karın, iç bacak) çoğunlukla bileşik hareketlerden çalışır;
  // haftalık zaman kısıtlıysa zorunlu hedef sayılmaz
  let min = base.minor && timeScale < 0.8 ? 0 : Math.max(2, Math.round(base.min * scale * timeScale));
  let max = Math.round(base.max * scale);
  const prio = profile.priorities || [];
  if (prio.includes(m)) {
    // 3 bölgeye kadar tam ek hacim; daha fazlasında ek hacim seçilenler arasında paylaştırılır
    const share = Math.min(1, 3 / prio.length);
    min += Math.max(1, Math.round(3 * share)); max += Math.max(1, Math.round(4 * share));
  }
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
    // çok bölge seçildiyse güne en fazla 1 ek slot (temel kaslar programdan düşmesin)
    let extra = 0;
    for (const p of prio) if (slots.includes(p) && extra < (prio.length > 3 ? 1 : 3)) { slots.splice(2, 0, p); extra++; }
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
  const tdee = bmr * (1.2 + 0.075 * (((daysMinOf(p) || 3) + (+p.days || 3)) / 2));
  return Math.round((tdee + GOALS[goalKey(p.goal)].kcal) / 10) * 10;
}


// ---------- Kreatin sorusu ----------
const CREATINE_OPTS = {
  yes:   { name: "Evet, kullanıyorum", desc: "Günlük 5 g takibini açarım", tone: "green",
           icon: '<path d="M6 12.5l4 4 8-9" />' },
  start: { name: "Başlamak istiyorum", desc: "Sana özel başlangıç planı hazırlarım", tone: "blue",
           icon: '<path d="M12 19V5M6 11l6-6 6 6" />' },
  no:    { name: "Hayır, kullanmıyorum", desc: "Kreatin takibini gizlerim", tone: "gray",
           icon: '<path d="M7 12h10" />' },
};
function creatineStepHtml(p) {
  const w = +p.weight || 75;
  const load = Math.min(20, Math.round(w * 0.3));
  const hero = `
    <div class="cr-hero">
      <svg viewBox="0 0 120 120" class="cr-jar" aria-hidden="true">
        <ellipse cx="60" cy="108" rx="34" ry="5" class="cr-shadow"/>
        <g class="cr-float">
          <rect x="30" y="34" width="60" height="68" rx="12" class="cr-body"/>
          <rect x="26" y="24" width="68" height="16" rx="6" class="cr-lid"/>
          <rect x="38" y="52" width="44" height="30" rx="6" class="cr-label"/>
          <text x="60" y="73" text-anchor="middle" class="cr-label-txt">C</text>
        </g>
        <g class="cr-scoop">
          <path d="M86 46 q14 -2 18 8 q-8 8 -20 2 z" class="cr-scoop-cup"/>
          <rect x="99" y="40" width="16" height="4" rx="2" transform="rotate(-35 99 40)" class="cr-scoop-cup"/>
          <circle cx="94" cy="49" r="3" class="cr-powder"/><circle cx="89" cy="51" r="2.2" class="cr-powder"/>
        </g>
        <circle cx="18" cy="30" r="2.5" class="cr-spark s1"/><circle cx="104" cy="22" r="2" class="cr-spark s2"/><circle cx="14" cy="76" r="1.8" class="cr-spark s3"/>
      </svg>
      <div class="cr-facts">
        <b>Kreatin ne işe yarar?</b>
        <span>💪 Daha fazla güç ve tekrar</span>
        <span>⚡ Daha hızlı toparlanma</span>
        <span>🔬 En çok araştırılmış takviye</span>
      </div>
    </div>`;
  const opts = `<div class="cr-opts">${Object.entries(CREATINE_OPTS).map(([k, o]) => `
    <button class="cr-opt tone-${o.tone} ${p.creatine === k ? "on" : ""}" data-set="creatine" data-val="${k}" aria-pressed="${p.creatine === k}">
      <span class="cr-ic"><svg viewBox="0 0 24 24">${o.icon}</svg></span>
      <span class="cr-txt"><b>${o.name}</b><small>${o.desc}</small></span>
      <span class="cr-radio"></span>
    </button>`).join("")}</div>`;
  let preview = "";
  if (p.creatine === "yes") {
    preview = `<div class="cr-preview tone-green">
      <div class="cr-big">5 g<small>/ gün</small></div>
      <p>Her gün, antrenman olmayan günler dahil. Saati önemli değil; her gün aynı saatte içmek alışkanlığı kolaylaştırır.</p>
    </div>`;
  } else if (p.creatine === "start") {
    preview = `<div class="cr-preview tone-blue">
      <b class="cr-ptitle">${esc(p.name || "Senin")} için başlangıç planı (${w} kg)</b>
      <div class="cr-timeline">
        <div class="cr-step"><span class="cr-dot"></span><div><b>1-7. gün · Yükleme</b><small>Günde ${load} g, ${Math.max(2, Math.round(load / 5))} parçaya bölerek (öğünlerle)</small></div></div>
        <div class="cr-step"><span class="cr-dot"></span><div><b>8. günden itibaren · Devam</b><small>Günde 5 g, her gün</small></div></div>
      </div>
      <p>Yükleme şart değil: direkt 5 g ile başlarsan aynı seviyeye 3-4 haftada ulaşırsın, mide rahatsızlığı da daha az olur.</p>
    </div>`;
  } else if (p.creatine === "no") {
    preview = `<div class="cr-preview tone-gray">
      <p>Kreatin takibi uygulamada gizlenecek. Fikrini değiştirirsen Ayarlar'dan soruları yeniden yanıtlayarak açabilirsin.</p>
    </div>`;
  }
  return hero + opts + preview + `<p class="muted small cr-note">Böbrek rahatsızlığın varsa ya da ilaç kullanıyorsan takviyeye başlamadan önce doktoruna danış.</p>`;
}

// ---------- Başlangıç sihirbazı ----------
const PRIORITY_GROUPS = [
  ["Göğüs ve omuz", ["chest", "front_delt", "side_delt", "rear_delt"]],
  ["Kollar", ["biceps", "triceps", "forearm"]],
  ["Sırt", ["lats", "upper_back", "traps", "lower_back"]],
  ["Karın", ["abs", "obliques"]],
  ["Bacak ve kalça", ["glutes", "quads", "hamstrings", "adductors", "calves"]],
];

const WIZARD_STEPS = [
  { id: "name", title: () => "Merhaba! 👋", sub: () => "Ben senin FitAI koçunum. Sana özel bir program hazırlamak için birkaç soru soracağım. Önce tanışalım: adın ne?",
    render: (p) => `<input id="wz_name" placeholder="Adını yaz" autocomplete="given-name" maxlength="30" enterkeyhint="next" value="${esc(p.name || "")}" autofocus />`,
    read: (p) => { p.name = niceName($("#wz_name").value); return !!p.name || "Sana nasıl hitap edeyim? Adını yaz"; } },
  { id: "body1", title: (p) => `Memnun oldum ${p.name}! 🤝`, sub: () => "Seni biraz tanıyalım. Kalori ve su hedefini hesaplamak için cinsiyetin ve yaşın lazım.",
    render: (p) => `${cards("sex", { m: { name: "Erkek", icon: "♂️" }, f: { name: "Kadın", icon: "♀️" } }, p.sex, true)}
      <label>Yaş <input type="number" id="wz_age" inputmode="numeric" value="${p.age || ""}" placeholder="örn. 25" /></label>`,
    read: (p) => { p.age = Math.round(num($("#wz_age").value)) || 0; if (!p.sex) return "Cinsiyetini seç"; return (p.age >= 12 && p.age < 100) || "Yaş 12 ile 99 arasında olmalı"; } },
  { id: "body2", title: (p) => `${p.name}, boyun ve kilon kaç?`, sub: (p) => `${p.age} yaşındasın, güzel. Protein hedefini kilona göre belirleyeceğim.`,
    render: (p) => `<label>Boy (cm) <input type="text" id="wz_h" inputmode="numeric" enterkeyhint="next" autocomplete="off" value="${p.height || ""}" placeholder="örn. 178" /></label>
      <label>Kilo (kg) <input type="text" id="wz_w" inputmode="decimal" enterkeyhint="next" autocomplete="off" value="${p.weight || ""}" placeholder="örn. 75" /></label>`,
    read: (p) => {
      p.height = Math.round(num($("#wz_h").value)) || 0; p.weight = +(num($("#wz_w").value) || 0).toFixed(1);
      if (!(p.height >= 120 && p.height <= 230)) return "Boy 120-230 cm arasında olmalı";
      return (p.weight >= 30 && p.weight <= 250) || "Kilo 30-250 kg arasında olmalı";
    } },
  { id: "goal", title: (p) => `${p.name}, ana hedefin ne?`, sub: () => "Set, tekrar ve beslenme hedeflerini buna göre ayarlayacağım.",
    render: (p) => cards("goal", GOALS, p.goal ? goalKey(p.goal) : null), read: (p) => !!p.goal || "Bir hedef seç" },
  { id: "level", title: () => "Ne kadar süredir spor yapıyorsun?", sub: (p) => `${GOALS[p.goal].cheer}, ${p.name}. Şimdi antrenman hacmini seviyene göre ayarlayalım.`,
    render: (p) => cards("level", LEVELS, p.level), read: (p) => !!p.level || "Seviyeni seç" },
  { id: "days", title: (p) => `${p.name}, haftada kaç gün ayırabilirsin?`, sub: (p) => p.level === "beginner" ? "Yeni başlıyorsan 3 gün harika bir başlangıç. Programın bölünmesini buna göre seçeceğim." : "Programın bölünmesini gün sayısına göre seçeceğim.",
    render: (p) => `<div class="num-pick days-pick">${[2, 3, 4, 5, 6].map((d) => {
        const lo = daysMinOf(p), hi = +p.days || 0;
        const cls = d === lo || d === hi ? "on" : d > lo && d < hi ? "in-range" : "";
        return `<button class="opt ${cls}" data-days="${d}">${d}</button>`;
      }).join("")}</div>
      <p class="days-hint muted small">${daysHint(p)}</p>
      <div class="muted small" style="margin:14px 0 6px">Bir antrenman ne kadar sürsün?</div>
      <div class="num-pick">${[30, 45, 60, 90].map((d) => `<button class="opt ${p.sessionMin === d ? "on" : ""}" data-set="sessionMin" data-val="${d}">${d} dk</button>`).join("")}</div>`,
    read: (p) => (!!p.days && !!p.sessionMin) || "Gün sayısını ve süreyi seç" },
  { id: "equipment", title: () => "Nerede antrenman yapıyorsun?", sub: (p) => `Haftada ${daysLabel(p)} gün, ${p.sessionMin} dakika, not ettim. Sana sadece yapabileceğin hareketleri önereceğim.`,
    render: (p) => cards("equipment", EQUIPMENT, p.equipment), read: (p) => !!p.equipment || "Bir seçenek seç" },
  { id: "planMode", title: (p) => `${p.name}, programını nasıl oluşturalım?`,
    sub: () => "İki yol var. İstediğin zaman Program sekmesinden değiştirebilirsin.",
    render: (p) => cards("planMode", {
      auto: { name: "Benim için oluştur", icon: "🤖", desc: "Cevaplarına göre dengeli bir program hazırlarım. İstersen sonra düzenlersin." },
      manual: { name: "Kendim seçeceğim", icon: "✍️", desc: "Hareketleri sen seçersin. Ben eksik, fazla ve riskli noktaları anında gösteririm." },
    }, p.planMode), read: (p) => !!p.planMode || "Bir seçenek seç" },
  { id: "priorities", title: (p) => `${p.name}, özellikle geliştirmek istediğin bir bölge var mı?`, sub: () => "Seçtiğin kaslara daha fazla hacim vereceğim. İstediğin kadar seçebilir ya da boş geçebilirsin.",
    render: (p) => {
      const sel = p.priorities || [];
      return `<p class="pick-count small ${sel.length ? "has" : ""}">${sel.length ? `Seçilen (${sel.length}): ${sel.map((k) => MUSCLES[k].name).join(", ")}` : "Henüz seçim yok"}</p>` +
        (sel.length > 3 ? `<p class="pick-note small">${sel.length === Object.keys(MUSCLES).length
          ? "Tüm bölgeleri seçtin; bu durumda hepsi eşit çalışır, öne çıkan bir bölge olmaz."
          : `${sel.length} bölge seçtin. Antrenman süren sınırlı olduğu için ek hacmi bu bölgeler arasında paylaştıracağım; her biri biraz daha az ek set alır.`}</p>` : "") +
        PRIORITY_GROUPS.map(([title, keys]) => `<h3 class="mg-title">${title}</h3><div class="muscle-grid">${keys.map((k) => `
          <button class="mcard ${sel.includes(k) ? "on" : ""}" data-multi="priorities" data-val="${k}" aria-pressed="${sel.includes(k)}">
            ${muscleThumbSVG(k)}<span>${MUSCLES[k].name}</span>
          </button>`).join("")}</div>`).join("");
    },
    read: () => true },
  { id: "injuries", title: () => "Sakatlık ya da ağrın var mı?", sub: (p) => `${p.name}, seçtiğin bölgeyi zorlayan hareketleri programına koymayacağım.`,
    render: (p) => {
      const sel = p.injuries || [];
      return `<div class="muscle-grid">${Object.entries(INJURY_RISK).map(([k, v]) => `
          <button class="mcard inj ${sel.includes(k) ? "on" : ""}" data-multi="injuries" data-val="${k}" aria-pressed="${sel.includes(k)}">
            ${jointThumbSVG(k)}<span>${v.name}</span>
          </button>`).join("")}
          <button class="mcard none ${p.injuries && !sel.length ? "on" : ""}" data-none="injuries" aria-pressed="${!!(p.injuries && !sel.length)}">
            <span class="none-icon">✓</span><span>Yok</span>
          </button>
        </div>
        <p class="muted small">Ağrın devam ediyorsa bir doktora veya fizyoterapiste danış.</p>`;
    },
    read: (p) => { p.injuries ||= []; return true; } },
  { id: "creatine", title: (p) => `${p.name}, son soru: kreatin kullanıyor musun?`, sub: () => "Günlük kreatin takibini cevabına göre ayarlayacağım.",
    render: (p) => creatineStepHtml(p), read: (p) => !!p.creatine || "Bir seçenek seç" },
  { id: "summary", title: (p) => p.planMode === "manual" ? `${p.name}, her şey hazır! 🎉` : `${p.name}, planın hazır! 🎉`, sub: (p) => p.planMode === "manual" ? `${GOALS[p.goal].name} hedefin için hedeflerini belirledim:` : `${GOALS[p.goal].name} hedefin için cevaplarına göre hazırladığım plan:`, render: (p) => summaryHtml(p), read: () => true },
];

// "ali veli" -> "Ali Veli" (Türkçe büyük harf kurallarıyla: i -> İ)
function niceName(v) {
  return v.trim().replace(/\s+/g, " ").slice(0, 30)
    .split(" ").map((w) => w.charAt(0).toLocaleUpperCase("tr") + w.slice(1).toLocaleLowerCase("tr")).join(" ");
}

function daysHint(p) {
  const lo = daysMinOf(p), hi = +p.days;
  if (!hi) return "Tek bir sayı seçebilir ya da emin değilsen iki sayıya dokunup aralık seçebilirsin (örn. 3 ve 4).";
  if (lo < hi) return `Haftada ${lo}-${hi} gün: Programın ${hi} günlük bir döngü olacak. ${lo} gün gelebildiğin haftalarda kaldığın günden devam edersin; ${lo} günlük haftada da eksik kalmayacak şekilde ayarlarım.`;
  return `Haftada ${hi} gün. Emin değilsen bir sayıya daha dokunarak aralık seçebilirsin.`;
}

function cards(field, opts, cur, compact) {
  return `<div class="opt-cards ${compact ? "compact" : ""}">${Object.entries(opts).map(([k, o]) => `
    <button class="opt-card ${cur === k ? "on" : ""}" data-set="${field}" data-val="${k}">
      ${o.icon ? `<span class="oi">${o.icon}</span>` : ""}<span><b>${o.name}</b>${o.desc ? `<small>${o.desc}</small>` : ""}</span>
    </button>`).join("")}</div>`;
}

function summaryHtml(p) {
  // kreatine başlayacaksa ilk hafta yükleme dozu gösterilir (finishWizard da aynısını ayarlar)
  const t = nutritionTargets({ ...p, creatineLoading: p.creatine === "start", loadingUntil: null });
  const kcal = calorieTarget(p);
  const statsHtml = `<div class="sum-grid">
      <div class="stat"><b>${t.protein} g</b><span>Günlük protein</span></div>
      ${p.creatine !== "no" ? `<div class="stat"><b>${t.creatine} g</b><span>${p.creatine === "start" ? "Kreatin (ilk 7 gün)" : "Kreatin"}</span></div>` : ""}
      ${kcal ? `<div class="stat"><b>${kcal}</b><span>Kalori (kcal)</span></div>` : ""}
      <div class="stat"><b>${(t.water / 1000).toFixed(1)} L</b><span>Su</span></div>
    </div>`;
  if (p.planMode === "manual") {
    const big = ["chest", "lats", "upper_back", "quads", "hamstrings", "glutes", "side_delt"];
    return statsHtml + `<h3>Programını sen kuracaksın ✍️</h3>
      <p class="muted small">Program sekmesinde ${p.days} günlük boş bir plan seni bekliyor${daysMinOf(p) < p.days ? ` (haftada ${daysLabel(p)} gün geleceğin için ${daysMinOf(p)} günlük haftada da yeterli olacak şekilde kontrol edeceğim)` : ""}. Her güne hareket ekledikçe kas haritası ve uyarılar güncellenecek.
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
  // İlk açılışta önce animasyonlu karşılama ekranı; "Başlayalım"a basınca sorular gelir
  const first = !state.profile.onboarded;
  $("#wzWelcome").classList.toggle("hidden", !first);
  $("#wzWelcome").classList.remove("leaving");
  $("#wizard").classList.toggle("welcoming", first);
  $("#wzClose").classList.toggle("hidden", first); // soruları yeniden yanıtlarken vazgeçilebilir
  renderWizard();
  $("#wizard .wz-content").scrollTop = 0;
}
function startWizardQuestions() {
  const w = $("#wzWelcome");
  w.classList.add("leaving");
  setTimeout(() => {
    w.classList.add("hidden");
    $("#wizard").classList.remove("welcoming");
    $("#wizard").classList.add("entering");
    setTimeout(() => $("#wizard").classList.remove("entering"), 500);
    $("#wzBody").querySelector("input")?.focus();
  }, 350);
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
  if (!$("#wizard").classList.contains("welcoming")) $("#wzBody").querySelector("input")?.focus();
}
function wizardNext() {
  const s = WIZARD_STEPS[wz.i];
  const ok = s.read(wz.p);
  if (ok !== true) { $("#wzErr").textContent = ok; return; }
  if (wz.i < WIZARD_STEPS.length - 1) { wz.i++; renderWizard(); $("#wizard .wz-content").scrollTop = 0; return; }
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
  if (p.planMode === "manual" && !state.program.days.some((d) => d.exercises.length)) setTimeout(() => openPicker(0), 400);
  toast(p.planMode === "manual" ? `Hoş geldin ${p.name}! Şimdi programını kur, ben kontrol edeceğim ✍️` : `Hoş geldin ${p.name}! Programın hazır, hadi başlayalım 💪`);
}

// Geri'ye basınca o adımda yazılanlar (doğrulamadan) saklanır
function keepTyped() {
  const v = (id) => $(id)?.value.trim();
  const p = wz.p;
  if (v("#wz_name")) p.name = niceName(v("#wz_name"));
  if (v("#wz_age")) p.age = Math.round(num(v("#wz_age"))) || p.age;
  if (v("#wz_h")) p.height = Math.round(num(v("#wz_h"))) || p.height;
  if (v("#wz_w")) p.weight = num(v("#wz_w")) || p.weight;
}
function initWizard() {
  $("#wzStart").onclick = startWizardQuestions;
  $("#wzNext").onclick = wizardNext;
  $("#wzBack").onclick = () => { if (wz.i) { keepTyped(); wz.i--; renderWizard(); $("#wizard .wz-content").scrollTop = 0; } };
  $("#wzClose").onclick = () => { $("#wizard").classList.add("hidden"); wz = null; };
  $("#wzBody").addEventListener("keydown", (e) => {
    if (e.key !== "Enter") return;
    // boy alanında Enter kilo alanına geçer
    if (e.target.id === "wz_h" && !$("#wz_w").value) { e.preventDefault(); return $("#wz_w").focus(); }
    wizardNext();
  });
  $("#wzBody").addEventListener("click", (e) => {
    const b = e.target.closest("button");
    if (!b) return;
    const p = wz.p;
    if (b.dataset.days) {
      // tek sayı ya da aralık: seçim yokken veya aralık seçiliyken dokunulan sayı tek seçim olur,
      // tek seçim varken başka sayıya dokunmak aralık oluşturur
      const d = +b.dataset.days, lo = daysMinOf(p), hi = +p.days || 0;
      if (!hi || lo < hi || d === hi) { p.days = d; p.daysMin = d; }
      else { p.days = Math.max(d, hi); p.daysMin = Math.min(d, hi); }
      const inputs = [...$("#wzBody").querySelectorAll("input")].map((i) => [i.id, i.value]);
      renderWizard();
      for (const [id, val] of inputs) if ($("#" + id)) $("#" + id).value = val;
    } else if (b.dataset.set) {
      const v = b.dataset.val;
      p[b.dataset.set] = /^\d+$/.test(v) ? +v : v;
      // girilmiş alanları kaybetmeden yeniden çiz
      const inputs = [...$("#wzBody").querySelectorAll("input")].map((i) => [i.id, i.value]);
      renderWizard();
      for (const [id, val] of inputs) if ($("#" + id)) $("#" + id).value = val;
      // seçime göre açılan önizleme ekranın altında kalmasın
      $("#wzBody .cr-preview")?.scrollIntoView({ behavior: "smooth", block: "nearest" });
    } else if (b.dataset.multi) {
      const arr = (p[b.dataset.multi] ||= []);
      const k = b.dataset.val, ix = arr.indexOf(k);
      if (ix >= 0) arr.splice(ix, 1);
      else arr.push(k);
      renderWizard();
    } else if (b.dataset.none) {
      p[b.dataset.none] = [];
      renderWizard();
    }
  });
}

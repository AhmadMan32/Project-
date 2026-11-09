// Yerel akıllı analiz motoru: hacim, denge, toparlanma ve gelişim kararları üretir.
const DAY = 86400000;

function e1rm(kg, reps) {
  if (!kg || !reps) return 0;
  if (reps === 1) return kg;
  return kg * (1 + reps / 30); // Epley
}

function findExercise(id, custom) {
  return EXERCISES.find((e) => e.id === id) || (custom || []).find((e) => e.id === id);
}

function dateToMs(d) {
  return new Date(d + "T12:00:00").getTime();
}

// Belirli bir zaman penceresinde kas başına ağırlıklı set sayısı
function muscleVolume(workouts, custom, fromMs, toMs) {
  const vol = {};
  const lastHit = {};
  const days = {};
  for (const m of Object.keys(MUSCLES)) { vol[m] = 0; days[m] = new Set(); }
  for (const w of workouts) {
    const t = dateToMs(w.date);
    if (t < fromMs || t > toMs) continue;
    const ex = findExercise(w.exerciseId, custom);
    if (!ex) continue;
    const n = w.sets.length;
    for (const m of ex.p) {
      vol[m] += n;
      days[m].add(w.date);
      lastHit[m] = Math.max(lastHit[m] || 0, t);
    }
    for (const m of ex.s) {
      vol[m] += n * 0.5;
      lastHit[m] = Math.max(lastHit[m] || 0, t);
    }
  }
  return { vol, lastHit, days };
}

function statusOf(m, v) {
  const { min, max } = MUSCLES[m];
  if (v === 0) return "none";
  if (v < min) return "low";
  if (v > max) return "high";
  return "ok";
}

function exerciseHistory(workouts, exerciseId) {
  const byDate = {};
  for (const w of workouts) {
    if (w.exerciseId !== exerciseId) continue;
    const d = (byDate[w.date] ||= { date: w.date, top: 0, best1rm: 0, volume: 0, sets: 0 });
    for (const s of w.sets) {
      d.top = Math.max(d.top, s.kg || 0);
      d.best1rm = Math.max(d.best1rm, e1rm(s.kg || 0, s.reps || 0));
      d.volume += (s.kg || 0) * (s.reps || 0);
      d.sets += 1;
    }
  }
  return Object.values(byDate).sort((a, b) => a.date.localeCompare(b.date));
}

function analyze(state) {
  const now = Date.now();
  const { workouts, customExercises: custom } = state;
  const week = muscleVolume(workouts, custom, now - 7 * DAY, now + DAY);
  const recent = muscleVolume(workouts, custom, now - 2 * DAY, now + DAY);
  const insights = [];
  const statuses = {};

  if (!workouts.length) {
    return {
      statuses: Object.fromEntries(Object.keys(MUSCLES).map((m) => [m, "none"])),
      week, score: 0,
      insights: [{ type: "info", title: "Henüz veri yok", text: "Antrenman sekmesinden yaptığın hareketleri ekle, analiz otomatik oluşsun. İstersen Ayarlar'dan örnek veri yükleyebilirsin." }],
    };
  }

  const missing = [], low = [], high = [];
  for (const m of Object.keys(MUSCLES)) {
    const st = statusOf(m, week.vol[m]);
    statuses[m] = st;
    if (st === "none") missing.push(m);
    else if (st === "low") low.push(m);
    else if (st === "high") high.push(m);
  }

  // Aşırı çalışan kaslar
  for (const m of high) {
    insights.push({
      type: "danger", muscle: m,
      title: `${MUSCLES[m].name} fazla çalıştı`,
      text: `Son 7 günde ${fmt(week.vol[m])} set (önerilen en fazla ${MUSCLES[m].max}). Toparlanma bozulabilir, sakatlık riski artar. Bu hafta ${MUSCLES[m].name.toLowerCase()} hacmini %20-30 azalt.`,
    });
  }

  // Hiç çalışmayan kaslar
  if (missing.length) {
    insights.push({
      type: "warn",
      title: "Bu hafta hiç çalışmayan kaslar",
      text: missing.map((m) => MUSCLES[m].name).join(", ") + ". " +
        "Önerilen hareketler: " + missing.slice(0, 4).map((m) => `${MUSCLES[m].name} → ${suggest(m).join(" / ")}`).join("; ") + ".",
      muscles: missing,
    });
  }

  // Az çalışan kaslar
  for (const m of low) {
    insights.push({
      type: "warn", muscle: m,
      title: `${MUSCLES[m].name} yetersiz`,
      text: `Son 7 günde ${fmt(week.vol[m])} set, hedef ${MUSCLES[m].min}-${MUSCLES[m].max}. ${Math.ceil(MUSCLES[m].min - week.vol[m])} set daha ekle. Öneri: ${suggest(m).join(", ")}.`,
    });
  }

  // Denge kontrolleri
  const sum = (ms) => ms.reduce((a, m) => a + week.vol[m], 0);
  const push = sum(["chest", "front_delt", "triceps"]);
  const pull = sum(["lats", "upper_back", "rear_delt", "biceps"]);
  if (push > 0 && pull > 0) {
    const r = push / pull;
    if (r > 1.3) insights.push({ type: "warn", title: "İtme / Çekme dengesizliği", text: `İtme hacmin çekmenin ${r.toFixed(1)} katı. Omuz sağlığı ve duruş için sırt çalışmasını artır (row, pulldown, face pull).` });
    else if (r < 0.7) insights.push({ type: "warn", title: "Çekme / İtme dengesizliği", text: `Çekme hacmin itmenin ${(1 / r).toFixed(1)} katı. Göğüs ve triceps hacmini artırabilirsin.` });
  } else if (push > 0 && pull === 0) {
    insights.push({ type: "danger", title: "Sırt hiç çalışılmamış", text: "Sadece itme hareketleri yapılmış. Omuzun öne kapanmasını önlemek için mutlaka sırt hareketi ekle." });
  }
  const fd = week.vol.front_delt, rd = week.vol.rear_delt;
  if (fd > 6 && fd > rd * 2.2) {
    insights.push({ type: "warn", title: "Ön omuz arka omuza göre baskın", text: `Ön omuz ${fmt(fd)} set, arka omuz ${fmt(rd)} set. Face pull veya reverse fly ekleyerek dengele.` });
  }
  const q = week.vol.quads, h = week.vol.hamstrings;
  if (q > 6 && q > h * 1.7) {
    insights.push({ type: "warn", title: "Ön bacak / arka bacak dengesizliği", text: `Quad ${fmt(q)} set, hamstring ${fmt(h)} set. Diz sağlığı için RDL veya leg curl ekle.` });
  }
  const upper = sum(["chest", "lats", "upper_back", "front_delt", "side_delt", "biceps", "triceps"]);
  const legs = sum(["quads", "hamstrings", "glutes", "calves"]);
  if (upper > 20 && legs < upper * 0.35) {
    insights.push({ type: "warn", title: "Bacak günü atlanıyor", text: `Üst vücut ${fmt(upper)} set, bacak sadece ${fmt(legs)} set. Haftada en az 1-2 bacak antrenmanı planla.` });
  }

  // Toparlanma: son 48 saatte yoğun çalışan kaslar
  const tired = Object.keys(MUSCLES).filter((m) => recent.vol[m] >= 8);
  if (tired.length) {
    insights.push({ type: "info", title: "Dinlenmesi gereken kaslar", text: `Son 48 saatte yoğun çalıştı: ${tired.map((m) => MUSCLES[m].name).join(", ")}. Bugün bu kaslar yerine farklı bir bölge çalış.`, muscles: tired });
  }

  // Frekans: haftada sadece 1 gün çalışan büyük kaslar
  const once = ["chest", "lats", "quads", "upper_back", "glutes"].filter((m) => week.days[m].size === 1 && week.vol[m] >= 8);
  if (once.length) {
    insights.push({ type: "info", title: "Frekansı artır", text: `${once.map((m) => MUSCLES[m].name).join(", ")} haftada tek günde çalışılmış. Aynı hacmi 2 güne bölmek kas gelişimi için daha verimli.` });
  }

  // Gelişim analizi
  const exIds = [...new Set(workouts.map((w) => w.exerciseId))];
  const gains = [];
  for (const id of exIds) {
    const hist = exerciseHistory(workouts, id);
    if (hist.length < 3) continue;
    const ex = findExercise(id, custom);
    const last = hist[hist.length - 1];
    const prev = hist.slice(-4, -1);
    const prevBest = Math.max(...prev.map((x) => x.best1rm));
    if (!prevBest) continue;
    const change = ((last.best1rm - prevBest) / prevBest) * 100;
    if (change >= 2) {
      gains.push(`${ex.name} ${prevBest.toFixed(1)} → ${last.best1rm.toFixed(1)} kg (+%${change.toFixed(1)})`);
    } else if (hist.length >= 4 && Math.abs(change) < 2) {
      insights.push({ type: "info", title: `${ex.name}: plato`, text: `Son ${prev.length + 1} antrenmanda güç artışı yok. Tekrar aralığını değiştir, 2.5 kg ekle ya da bir hafta deload yap.` });
    } else if (change <= -5) {
      insights.push({ type: "warn", title: `${ex.name}: performans düştü`, text: `Tahmini 1RM %${Math.abs(change).toFixed(1)} düştü. Uyku, protein ve toparlanmayı kontrol et.` });
    }
  }

  if (gains.length) {
    insights.push({ type: "good", title: `${gains.length} harekette gelişme var 💪`, text: "Tahmini 1RM: " + gains.join(" · ") + ". Progressive overload'a devam." });
  }

  // Beslenme ile bağlantı
  const t = nutritionTargets(state.profile);
  const today = state.nutrition[todayStr()] || { protein: [], creatine: 0 };
  const p = today.protein.reduce((a, x) => a + x.g, 0);
  if (t.protein && p < t.protein * 0.5 && new Date().getHours() >= 15) {
    insights.push({ type: "warn", title: "Protein geride", text: `Bugün ${p} g protein aldın, hedef ${t.protein} g. Kas gelişimi için gün içinde ${t.protein - p} g daha al.` });
  }

  const okCount = Object.values(statuses).filter((s) => s === "ok").length;
  const score = Math.round((okCount / Object.keys(MUSCLES).length) * 100 - high.length * 3);
  if (!insights.some((i) => i.type === "danger" || i.type === "warn")) {
    insights.unshift({ type: "good", title: "Harika denge!", text: "Bu hafta tüm kas grupları önerilen aralıkta. Programına sadık kal ve ağırlıkları kademeli artır." });
  }

  const order = { danger: 0, warn: 1, info: 2, good: 3 };
  insights.sort((a, b) => order[a.type] - order[b.type]);
  return { statuses, week, insights, score: Math.max(0, score) };
}

function suggest(m) {
  return EXERCISES.filter((e) => e.p[0] === m).slice(0, 2).map((e) => e.name);
}

function fmt(n) {
  return Number.isInteger(n) ? n : n.toFixed(1);
}

function nutritionTargets(profile) {
  const w = Number(profile.weight) || 0;
  const factor = { cut: 2.2, maintain: 1.8, bulk: 2.0 }[profile.goal] || 1.8;
  return {
    protein: Math.round(w * factor),
    creatine: profile.creatineLoading ? Math.min(20, Math.round(w * 0.3)) : 5,
    water: Math.round(w * 35 / 100) * 100 || 2500,
  };
}

function todayStr(d = new Date()) {
  const z = new Date(d.getTime() - d.getTimezoneOffset() * 60000);
  return z.toISOString().slice(0, 10);
}

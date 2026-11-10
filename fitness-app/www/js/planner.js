// Plan modu: "auto" (uygulama oluşturur) veya "manual" (kullanıcı kendisi seçer, uygulama denetler)
const PLAN_MODES = {
  auto:   { name: "Benim için oluştur", icon: "🤖", desc: "Cevaplarına göre dengeli bir program hazırlarım" },
  manual: { name: "Kendim seçeceğim", icon: "✍️", desc: "Hareketleri sen seçersin, ben eksik ve fazlaları gösteririm" },
};
const SET_MINUTES = 2.5; // ısınma + set + dinlenme ortalaması

function emptyProgram(days) {
  return {
    mode: "manual", split: "Kendi programım", created: todayStr(),
    days: Array.from({ length: days || 3 }, (_, i) => ({ key: "custom", name: `${i + 1}. Gün`, exercises: [] })),
  };
}

// Planın haftalık kas hacmi (birincil 1, ikincil 0.5 set) ve kasların hangi günlerde çalıştığı
function planVolume(program, custom = []) {
  const vol = {}, primaryByDay = {}, days = {};
  for (const m of Object.keys(MUSCLES)) { vol[m] = 0; days[m] = new Set(); primaryByDay[m] = program.days.map(() => 0); }
  program.days.forEach((d, di) => {
    for (const e of d.exercises) {
      const ex = findExercise(e.id, custom);
      if (!ex) continue;
      for (const m of ex.p) { vol[m] += e.sets; days[m].add(di); primaryByDay[m][di] += e.sets; }
      for (const m of ex.s) { vol[m] += e.sets * 0.5; }
    }
  });
  return { vol, days, primaryByDay };
}

// Bir kası çalıştıran uygun hareketler, tercih sırasıyla (plandakiler sona)
function exerciseCandidates(m, profile, program, custom = []) {
  const ok = new Map(allowedExercises(profile, custom).map((e) => [e.id, e]));
  const inPlan = new Set(program.days.flatMap((d) => d.exercises.map((e) => e.id)));
  const ids = [...new Set([...(PREFERRED[m] || []), ...[...ok.values()].filter((e) => e.p[0] === m).map((e) => e.id)])].filter((id) => ok.has(id));
  return [...ids.filter((id) => !inPlan.has(id)), ...ids.filter((id) => inPlan.has(id))].map((id) => ok.get(id));
}

// Bir kası çalıştırmak için en iyi uygun hareket (plandakileri tekrar önermez)
function bestExerciseFor(m, profile, program, custom = []) {
  const ok = new Map(allowedExercises(profile, custom).map((e) => [e.id, e]));
  const inPlan = new Set(program.days.flatMap((d) => d.exercises.map((e) => e.id)));
  const order = [...(PREFERRED[m] || []), ...[...ok.values()].filter((e) => e.p[0] === m).map((e) => e.id)];
  return order.map((id) => ok.get(id)).find((e) => e && !inPlan.has(e.id)) || order.map((id) => ok.get(id)).find(Boolean) || null;
}

// Hareketin ekleneceği en uygun gün: aynı kas grubunu çalıştıran gün öncelikli, başka grupların yoğun olduğu gün cezalı,
// eşitlikte en az setli gün
function bestDayScore(m, day, custom) {
  const grp = MUSCLES[m].group;
  let same = 0, other = 0;
  for (const e of day.exercises) {
    const gs = (findExercise(e.id, custom)?.p || []).map((x) => MUSCLES[x].group);
    if (gs.includes(grp)) same++; else other++;
  }
  return same * 2 - other;
}
function bestDayFor(m, program, custom = []) {
  const setsOf = (d) => d.exercises.reduce((a, e) => a + e.sets, 0);
  return program.days.map((d, i) => i)
    .sort((a, b) => bestDayScore(m, program.days[b], custom) - bestDayScore(m, program.days[a], custom) || setsOf(program.days[a]) - setsOf(program.days[b]))[0] ?? 0;
}

// Gün aralığında (ör. 3-4) az gelinen haftada döngünün ne kadarı yapılır: 3/4
function lowWeekFactor(profile, program) {
  const len = program.days.length || 1, lo = daysMinOf(profile) || len;
  return Math.min(1, lo / len);
}
const volRange = (v, f) => (f < 1 && v ? `${fmt(+(v * f).toFixed(1))}-${fmt(v)}` : fmt(v));

// Kasın plana göre durumu (verilen profile göre; sihirbaz özetinde state.profile henüz kaydedilmemiş olabilir)
function planStatus(m, v, profile, f = 1) {
  const { min, max } = muscleTarget(m, profile);
  if (v === 0) return min === 0 ? "ok" : "none";
  // fazlalık: tam döngü yapılan haftada; eksiklik: az gelinen haftada
  return v > max ? "high" : v * f < min ? "low" : "ok";
}

// Otomatik planı kendi kontrolünden geçecek şekilde dengeler:
// 1) fazla kasın en büyük hareketinden set düşür, 2) en eksik kasa set ya da hareket ekle (seans süresi aşılmadan)
function balanceProgram(prog, profile, custom = []) {
  const cap = Math.floor((+profile.sessionMin || 60) / SET_MINUTES);
  const goal = GOALS[goalKey(profile.goal)];
  const daySets = (d) => d.exercises.reduce((a, e) => a + e.sets, 0);
  const exOf = (e) => findExercise(e.id, custom);
  const skip = new Set(), skipOver = new Set();
  const f = lowWeekFactor(profile, prog);
  for (let it = 0; it < 200; it++) {
    const { vol } = planVolume(prog, custom);
    const tgt = (m) => muscleTarget(m, profile);
    // 1) fazlalık: kası birincil ya da ikincil çalıştıran, kesilince başka kası hedef altına düşürmeyen en büyük hareketi azalt
    const over = Object.keys(MUSCLES).find((m) => !skipOver.has(m) && vol[m] > tgt(m).max);
    if (over) {
      let best = null;
      for (const d of prog.days) for (const e of d.exercises) {
        const x = exOf(e);
        if (!x || !(x.p.includes(over) || x.s.includes(over))) continue;
        const keepsMins = x.p.every((m) => m === over || (vol[m] - 1) * f >= tgt(m).min);
        const w = (x.p.includes(over) ? 1 : 0.5) * e.sets;
        if (keepsMins && (!best || w > best.w)) best = { e, d, w };
      }
      if (best && best.e.sets > 2) { best.e.sets--; continue; }
      if (best) { best.d.exercises.splice(best.d.exercises.indexOf(best.e), 1); continue; }
      skipOver.add(over);
      continue;
    }
    // 2) en büyük oransal eksik, yoksa denge kuralının istediği kas
    const deficits = Object.keys(MUSCLES).filter((m) => !skip.has(m) && vol[m] * f < tgt(m).min)
      .sort((a, b) => (tgt(b).min - vol[b] * f) / tgt(b).min - (tgt(a).min - vol[a] * f) / tgt(a).min);
    let need = deficits[0], forBalance = false;
    if (!need) {
      const fix = balanceInsights(vol, "plan").flatMap((b) => b.fix || []).find((m) => !skip.has(m) && vol[m] + 1 <= tgt(m).max);
      if (!fix) break;
      need = fix; forBalance = true;
    }
    const fitsAll = (ex, extra) => ex.p.every((m) => vol[m] + extra <= tgt(m).max);
    // a) mevcut harekete set ekle
    let done = false;
    for (const d of prog.days) {
      if (daySets(d) + 1 > cap) continue;
      const e = d.exercises.find((x) => exOf(x)?.p.includes(need) && x.sets < 5 && fitsAll(exOf(x), 1));
      if (e) { e.sets++; done = true; break; }
    }
    if (done) continue;
    // b) yeni hareket ekle: tercih sırasındaki ilk uygun hareket (başka bir kası hedef üstüne taşımayan)
    const sets = Math.max(2, goal.sets + (profile.level === "beginner" ? -1 : 0));
    let added = false;
    for (const ex of exerciseCandidates(need, profile, prog, custom)) {
      if (!fitsAll(ex, sets)) continue;
      const order = prog.days.map((d, i) => i).filter((i) => daySets(prog.days[i]) + sets <= cap && !prog.days[i].exercises.some((e) => e.id === ex.id))
        .sort((a, b) => bestDayScore(need, prog.days[b], custom) - bestDayScore(need, prog.days[a], custom) || daySets(prog.days[a]) - daySets(prog.days[b]));
      if (!order.length) continue;
      prog.days[order[0]].exercises.push({ id: ex.id, sets, reps: ex.id === "plank" || ex.id === "side_plank" ? "30-60 sn" : goal.reps, muscle: need });
      added = true;
      break;
    }
    if (added) continue;
    // c) denge için: karşı taraftaki fazlayı 1 set azalt (ör. çekme fazlaysa en büyük çekme hareketini)
    if (forBalance) {
      const opp = balanceInsights(vol, "plan").find((b) => (b.fix || []).includes(need));
      const heavy = opp && Object.keys(MUSCLES).filter((m) => !(opp.fix || []).includes(m)).sort((a, b) => vol[b] - vol[a]);
      let cut = null;
      if (heavy) for (const d of prog.days) for (const e of d.exercises) {
        const x = exOf(e);
        if (x && e.sets > 2 && x.p.some((m) => heavy.slice(0, 4).includes(m)) && x.p.every((m) => (vol[m] - 1) * f >= tgt(m).min) && (!cut || e.sets > cut.sets)) cut = e;
      }
      if (cut) { cut.sets--; skip.add(need); continue; }
    }
    skip.add(need); // bu bütçeyle karşılanamıyor
  }
  return prog;
}

function analyzePlan(program, profile, custom = []) {
  const { vol, days, primaryByDay } = planVolume(program, custom);
  const T = (m) => muscleTarget(m, profile);
  const f = lowWeekFactor(profile, program);
  const statusOf = (m, v) => planStatus(m, v, profile, f);
  const statuses = {}, insights = [];
  const goal = GOALS[goalKey(profile.goal)];
  const sets = Math.max(2, goal.sets + (profile.level === "beginner" ? -1 : 0));
  const addAction = (m) => {
    const ex = bestExerciseFor(m, profile, program, custom);
    if (!ex) return null;
    const di = bestDayFor(m, program, custom);
    return { kind: "add", id: ex.id, day: di, sets, reps: goal.reps, label: `+ ${ex.name} → ${program.days[di].name}` };
  };

  const total = program.days.reduce((a, d) => a + d.exercises.length, 0);
  if (!total) {
    return { vol, statuses: Object.fromEntries(Object.keys(MUSCLES).map((m) => [m, "none"])), score: 0,
      insights: [{ type: "info", title: "Planın henüz boş", text: "Her güne \"+ Hareket ekle\" ile hareket ekle. Eklediğin her harekette kas haritası ve uyarılar anında güncellenir." }] };
  }

  const missing = [];
  for (const m of Object.keys(MUSCLES)) {
    const t = T(m), v = vol[m];
    const st = statusOf(m, v);
    statuses[m] = st;
    if (st === "none") missing.push(m);
    else if (st === "low") {
      const a = addAction(m);
      insights.push({ type: "warn", title: `${MUSCLES[m].name} yetersiz`, text: `Haftalık ${volRange(v, f)} set planlanmış, hedefin ${t.min}-${t.max}.${f < 1 ? ` Haftada ${daysMinOf(profile)} gün geldiğinde ${fmt(+(v * f).toFixed(1))} sette kalıyor.` : ""} En az ${Math.ceil((t.min - v * f) / f)} set daha ekle.` +
        (a ? "" : " Ekipmanınla bu kas için uygun hareket bulamadım; mevcut hareketlerin set sayısını artırabilirsin."), actions: a ? [a] : [] });
    } else if (st === "high") {
      // en çok set veren hareketi bul ve azaltmayı öner
      let top = null;
      program.days.forEach((d, di) => d.exercises.forEach((e, ei) => {
        const ex = findExercise(e.id, custom);
        if (ex?.p.includes(m) && e.sets > 1 && (!top || e.sets > top.sets)) top = { di, ei, sets: e.sets, name: ex.name };
      }));
      insights.push({ type: "danger", title: `${MUSCLES[m].name} fazla`, text: `${f < 1 ? `Haftada ${profile.days} gün geldiğinde` : "Haftalık"} ${fmt(v)} set planlanmış, en fazla ${t.max} olmalı. Fazla hacim toparlanmayı bozar, gelişimi yavaşlatır.`,
        actions: top ? [{ kind: "reduce", day: top.di, idx: top.ei, label: `− ${top.name} 1 set azalt` }] : [] });
    }
  }
  if (missing.length) {
    insights.push({ type: "warn", title: "Planında hiç olmayan kaslar", text: missing.map((m) => MUSCLES[m].name).join(", ") + ".",
      actions: missing.slice(0, 4).map(addAction).filter(Boolean) });
  }

  // Denge kuralları (yapılan antrenman analiziyle aynı)
  for (const b of balanceInsights(vol, "plan")) {
    b.actions = (b.fix || []).filter((m) => statusOf(m, vol[m]) !== "high").slice(0, 2).map(addAction).filter(Boolean);
    insights.push(b);
  }

  // Sakatlık ve ekipman uyumu
  const allowed = new Set(allowedExercises(profile, custom).map((e) => e.id));
  program.days.forEach((d, di) => d.exercises.forEach((e, ei) => {
    const risk = riskyFor(e.id, profile);
    if (risk.length) insights.push({ type: "danger", title: `${exName(e.id)}: ${risk.join(", ")} için riskli`, text: `${risk.join(", ")} sakatlığın olduğunu söyledin. Bu hareketi daha güvenli bir alternatifle değiştirmeni öneririm.`, actions: [{ kind: "remove", day: di, idx: ei, label: "Plandan çıkar" }] });
    else if (!allowed.has(e.id)) insights.push({ type: "info", title: `${exName(e.id)}: ekipmanın yok olabilir`, text: `Bu hareket ${EQUIPMENT[profile.equipment]?.name.toLowerCase() || "seçtiğin ekipman"} ile yapılamayabilir.`, actions: [] });
  }));

  // Seans süresi ve günlük yük
  program.days.forEach((d, di) => {
    const s = d.exercises.reduce((a, e) => a + e.sets, 0);
    const min = Math.round(s * SET_MINUTES);
    if (profile.sessionMin && min > profile.sessionMin * 1.2) {
      insights.push({ type: "warn", title: `${d.name} çok uzun`, text: `${s} set ≈ ${min} dk sürer, sen ${profile.sessionMin} dk ayırabildiğini söyledin. Birkaç hareketi çıkar ya da başka güne taşı.`, actions: [] });
    }
    for (const m of Object.keys(MUSCLES)) {
      if (primaryByDay[m][di] > 12) insights.push({ type: "warn", title: `${d.name}: ${MUSCLES[m].name} tek seansta çok`, text: `Tek antrenmanda ${primaryByDay[m][di]} set. Seans başına ~10 setten sonrası verimsizleşir, hacmi başka güne böl.`, actions: [] });
    }
  });

  // Toparlanma: aynı kas art arda iki günde yoğun (2-3 günlük planlarda günler zaten aralıklı kabul edilir)
  if (program.days.length >= 4) for (const m of Object.keys(MUSCLES)) {
    for (let di = 0; di < program.days.length - 1; di++) {
      if (primaryByDay[m][di] >= 6 && primaryByDay[m][di + 1] >= 6) {
        insights.push({ type: "info", title: `${MUSCLES[m].name} art arda iki gün`, text: `${program.days[di].name} ve ${program.days[di + 1].name} günlerinde yoğun ${MUSCLES[m].name.toLowerCase()} var. Bu günleri arka arkaya yapacaksan araya dinlenme koy.`, actions: [] });
        break;
      }
    }
  }

  // Frekans: büyük kas haftada tek günde
  const once = ["chest", "lats", "upper_back", "quads", "glutes", "hamstrings"].filter((m) => days[m].size === 1 && vol[m] >= 8 && program.days.length >= 3);
  if (once.length) insights.push({ type: "info", title: "Frekansı artır", text: `${once.map((m) => MUSCLES[m].name).join(", ")} haftada tek günde. Aynı hacmi iki güne bölmek gelişim için daha verimli.`, actions: [] });

  // Zaman bütçesi kısıtlıysa bunu açıkça söyle: eksiklerin sebebi plan değil süre olabilir
  const weeklyMin = (daysMinOf(profile) || program.days.length) * (+profile.sessionMin || 60);
  const deficitCount = Object.values(statuses).filter((x) => x === "low" || x === "none").length;
  if (weeklyMin < 180 && deficitCount >= 2) {
    insights.push({ type: "info", title: "Haftalık süren kısıtlı", text: `Haftada ${daysMinOf(profile) || program.days.length} gün × ${profile.sessionMin || 60} dk ile her kası ideal hacimde çalıştırmak zor. En etkili çözüm bir gün ya da 15 dk eklemek; yoksa büyük kaslara öncelik ver.`, actions: [] });
  }

  const okCount = Object.values(statuses).filter((s) => s === "ok").length;
  const score = Math.max(0, Math.round((okCount / Object.keys(MUSCLES).length) * 100 - insights.filter((i) => i.type === "danger").length * 5));
  if (!insights.some((i) => i.type === "danger" || i.type === "warn")) {
    insights.unshift({ type: "good", title: "Planın dengeli 👌", text: "Tüm kas grupları hedef aralığında ve büyük bir dengesizlik yok. Şimdi sıra uygulamada!" });
  }
  const order = { danger: 0, warn: 1, info: 2, good: 3 };
  insights.sort((a, b) => order[a.type] - order[b.type]);
  return { vol, statuses, insights, score, f };
}

let planPickDay = null;

// ---------- Program sekmesi (yeni tasarım) ----------
const REGION_OF = {
  chest: "Göğüs", front_delt: "Omuz", side_delt: "Omuz", rear_delt: "Omuz",
  biceps: "Kol", triceps: "Kol", forearm: "Kol",
  lats: "Sırt", upper_back: "Sırt", traps: "Sırt", lower_back: "Bel",
  abs: "Karın", obliques: "Karın",
  glutes: "Bacak", quads: "Bacak", hamstrings: "Bacak", adductors: "Bacak", calves: "Bacak",
};
const planUI = { open: new Set(), checkOpen: false, init: false };

function daySets(day) { return day.exercises.reduce((a, e) => a + e.sets, 0); }
function dayRegions(day, n = 3) {
  const score = {};
  for (const e of day.exercises) {
    const ex = findExercise(e.id, state.customExercises);
    for (const m of ex?.p || []) score[REGION_OF[m]] = (score[REGION_OF[m]] || 0) + e.sets;
  }
  return Object.entries(score).sort((a, b) => b[1] - a[1]).slice(0, n).map(([r]) => r);
}
function loggedToday(id) { return state.workouts.some((w) => w.date === todayStr() && w.exerciseId === id); }
function lastWeight(id) {
  const last = [...state.workouts].reverse().find((w) => w.exerciseId === id);
  return last ? Math.max(...last.sets.map((s) => s.kg || 0)) : null;
}
function miniRing(value, target, color, label, text) {
  const r = 22, c = 2 * Math.PI * r, pct = target ? Math.min(1, value / target) : 0;
  return `<div class="mring"><svg viewBox="0 0 56 56" width="56" height="56">
      <circle cx="28" cy="28" r="${r}" fill="none" stroke="var(--card2)" stroke-width="6"/>
      <circle cx="28" cy="28" r="${r}" fill="none" stroke="${color}" stroke-width="6" stroke-linecap="round"
        stroke-dasharray="${c}" stroke-dashoffset="${c * (1 - pct)}" transform="rotate(-90 28 28)"/>
      ${pct >= 1 ? `<text x="28" y="33" text-anchor="middle" font-size="16" fill="${color}">✓</text>` : ""}
    </svg><div><b>${text}</b><small>${label}</small></div></div>`;
}
function dayBodyThumb(day) {
  const prim = new Set(), sec = new Set();
  for (const e of day.exercises) {
    const ex = findExercise(e.id, state.customExercises);
    (ex?.p || []).forEach((m) => prim.add(m));
    (ex?.s || []).forEach((m) => sec.add(m));
  }
  const color = (m) => (prim.has(m) ? "var(--accent)" : sec.has(m) ? "rgba(74,222,128,.38)" : "#2c313c");
  return buildBodySVG("front", color) + buildBodySVG("back", color);
}

// Antrenman oturumu: "Antrenmana başla" ile günün hareketleri sırayla açılır
function sessionDay() {
  const s = state.session, prog = state.program;
  if (!s || s.date !== todayStr() || !prog || !prog.days[s.day]) return null;
  return prog.days[s.day];
}
function sessionRemaining() {
  const d = sessionDay();
  return d ? d.exercises.filter((e) => !loggedToday(e.id)) : [];
}
function openSessionExercise() {
  const rem = sessionRemaining();
  if (!rem.length) return finishSession(true);
  $(`#tabs button[data-tab="workout"]`).click();
  $("#wDate").value = todayStr(); renderDayLog();
  selectExercise(rem[0].id, { sets: rem[0].sets, reps: rem[0].reps });
  renderSessionBar();
  $("#setEditor").scrollIntoView({ behavior: "smooth", block: "center" });
}
function startSession(dayIdx) {
  state.session = { date: todayStr(), day: dayIdx };
  save();
  openSessionExercise();
}
function finishSession(completed) {
  const d = sessionDay();
  state.session = null; save();
  renderSessionBar();
  if (completed && d) toast(`${d.name} tamamlandı 🎉${userName() ? " Helal " + userName() + "!" : ""}`);
  $(`#tabs button[data-tab="plan"]`).click();
}
function renderSessionBar() {
  const bar = $("#sessionBar");
  const d = sessionDay();
  $("#tab-workout").classList.toggle("in-session", !!d);
  if (!d) { bar.classList.add("hidden"); return; }
  const total = d.exercises.length, left = sessionRemaining();
  const doneN = total - left.length;
  bar.classList.remove("hidden");
  bar.innerHTML = `<div class="sb-top"><b>${esc(d.name)}</b><span>${doneN}/${total} hareket</span></div>
    <div class="sb-track"><div style="width:${(doneN / Math.max(1, total)) * 100}%"></div></div>
    <div class="sb-bottom"><span>${left.length ? `Şimdi: <b>${esc(exName(left[0].id))}</b>${left[1] ? ` · Sonra: ${esc(exName(left[1].id))}` : ""}` : "Hepsi bitti!"}</span>
      <button class="btn ghost small-btn" id="sbEnd">Bitir</button></div>`;
}
// app.js kaydettikten sonra çağırır
function onWorkoutSaved() {
  if (!sessionDay()) return;
  if (sessionRemaining().length) setTimeout(openSessionExercise, 600);
  else setTimeout(() => finishSession(true), 600);
}

function renderPlan() {
  const p = state.profile, prog = state.program;
  $("#greet").textContent = p.name ? `Merhaba ${p.name} 👋` : "";
  if (!prog) {
    $("#planBody").innerHTML = `<div class="card"><p>Henüz programın yok.</p><button class="btn primary" id="planStartWizard">Soruları yanıtla</button></div>`;
    return;
  }
  const manual = prog.mode === "manual";
  const len = prog.days.length;
  const next = len ? nextDayIndex() : 0;
  // kendi planında sıradaki gün ilk açılışta açık gelsin (hareket eklemek için)
  if (manual && !planUI.init) { planUI.open.add(next); planUI.init = true; }
  const nextDay = prog.days[next];
  const t = targets(), d = dayNut();
  const pg = d.protein.reduce((a, x) => a + x.g, 0);
  const done = weekWorkoutDays();
  const weekTarget = daysMinOf(p) || len;
  const todayDone = nextDay && nextDay.exercises.length > 0 && nextDay.exercises.every((e) => loggedToday(e.id));
  const anyToday = state.workouts.some((w) => w.date === todayStr());
  const active = sessionDay();
  const pa = analyzePlan(prog, p, state.customExercises);
  renderPlan._pa = pa;
  const warnN = pa.insights.filter((i) => i.type === "warn" || i.type === "danger").length;
  const dateTxt = new Date().toLocaleDateString("tr-TR", { weekday: "long", day: "numeric", month: "long" });
  const goal = GOALS[goalKey(p.goal)];

  // --- bugünün kartı
  let hero;
  if (!nextDay || !nextDay.exercises.length) {
    hero = `<section class="today-card empty">
      <span class="tc-tag">${manual ? "KENDİ PROGRAMIN" : "PROGRAM"}</span>
      <h2>${manual ? "Programını kuralım" : "Bu günde hareket yok"}</h2>
      <p class="tc-meta">${manual ? "Her güne hareket ekle; ben eksik ve fazlaları anında göstereceğim." : "Plan ayarlarından planı yeniden oluşturabilirsin."}</p>
      <button class="tc-start" data-goday="${next}">＋ ${esc(nextDay ? nextDay.name : "1. Gün")} için hareket ekle</button>
    </section>`;
  } else {
    const sets = daySets(nextDay), mins = Math.round(sets * SET_MINUTES);
    const doneN = nextDay.exercises.filter((e) => loggedToday(e.id)).length;
    hero = `<section class="today-card ${todayDone ? "done" : ""}">
      <div class="tc-text">
        <span class="tc-tag">${todayDone ? "BUGÜN TAMAMLANDI ✓" : active ? "ANTRENMAN DEVAM EDİYOR" : `BUGÜN · ${next + 1}. GÜN`}</span>
        <h2>${esc(nextDay.name)}</h2>
        <p class="tc-meta">${nextDay.exercises.length} hareket · ${sets} set · ~${mins} dk</p>
        <div class="tc-chips">${dayRegions(nextDay).map((r) => `<span>${r}</span>`).join("")}</div>
      </div>
      <div class="tc-body">${dayBodyThumb(nextDay)}</div>
      ${todayDone
        ? `<p class="tc-donetxt">Harika iş${p.name ? ", " + esc(p.name) : ""}! Şimdi sıra protein ve dinlenmede.</p>`
        : `<button class="tc-start" id="tcStart">${active || doneN ? `▶ Devam et · ${doneN}/${nextDay.exercises.length}` : "▶ Antrenmana başla"}</button>`}
    </section>`;
  }

  // --- haftalık şerit
  const week = `<section class="week">
    <div class="wk-head"><b>Döngün</b><span>Bu hafta ${done}/${daysLabel(p) !== "-" ? daysLabel(p) : len} antrenman</span></div>
    <div class="wk-days">${prog.days.map((day, i) => {
      const st = i < next ? "done" : i === next ? (todayDone ? "done" : "next") : "";
      return `<button class="wk-day ${st}" data-goday="${i}">
        <span class="wk-num">${st === "done" ? "✓" : i + 1}</span>
        <span class="wk-name">${esc(shortDayName(day.name))}</span>
      </button>`;
    }).join("")}</div>
  </section>`;

  // --- halkalar
  const L = (ml) => +(ml / 1000).toFixed(1);
  const rings = `<section class="rings">
    <button class="ring-btn" data-gotab="nutrition">${miniRing(pg, t.protein, "#4ade80", "g protein", `${pg}/${t.protein}`)}</button>
    ${t.creatine ? `<button class="ring-btn" data-gotab="nutrition">${miniRing(d.creatine, t.creatine, "#60a5fa", "g kreatin", `${d.creatine}/${t.creatine}`)}</button>` : ""}
    <button class="ring-btn" data-gotab="nutrition">${miniRing(L(d.water), L(t.water), "#38bdf8", "L su", `${L(d.water)}/${L(t.water)}`)}</button>
  </section>`;

  // --- bugünün hareketleri
  const todayList = nextDay && nextDay.exercises.length ? `<section class="card ex-card">
    <div class="sec-head"><h3>${todayDone ? "Bugün yaptıkların" : "Bugünün hareketleri"}</h3><span>${goal.reps} tekrar</span></div>
    ${nextDay.exercises.map((e, i) => exRow(e, i)).join("")}
  </section>` : "";

  // --- tüm günler (açılır kartlar)
  const days = `<section class="days">
    <div class="sec-title"><h3>${esc(prog.split)}</h3><span>${goal.name}</span></div>
    ${prog.days.map((day, i) => {
      const open = planUI.open.has(i);
      const sets = daySets(day);
      return `<details class="day-acc ${i === next ? "next" : ""}" data-acc="${i}" ${open ? "open" : ""}>
        <summary>
          <span class="da-num">${i + 1}</span>
          <span class="da-main"><b>${esc(day.name)}</b><small>${day.exercises.length ? `${day.exercises.length} hareket · ~${Math.round(sets * SET_MINUTES)} dk · ${dayRegions(day).join(", ")}` : "Henüz hareket yok"}</small></span>
          ${i === next ? `<span class="badge">Sıradaki</span>` : ""}
          <span class="da-chev">›</span>
        </summary>
        <div class="da-body">${manual ? dayEditor(day, i) : day.exercises.map((e, ei) => exRow(e, ei)).join("")}
          ${!manual && i !== next && day.exercises.length ? `<button class="btn ghost full" data-startday="${i}">▶ Bugün bu günü yap</button>` : ""}
        </div>
      </details>`;
    }).join("")}
    ${manual ? `<div class="row wrap"><button class="btn" id="addDay">+ Gün ekle</button><button class="btn ghost" id="clearPlan">Planı temizle</button></div>` : ""}
  </section>`;

  // --- plan kontrolü (özet + açılır detay)
  const showCheck = planUI.checkOpen;
  const color = (m) => COLORS[pa.statuses[m]];
  const scoreColor = pa.score >= 85 ? "var(--accent)" : pa.score >= 60 ? "var(--warn)" : "var(--danger)";
  const check = `<section class="card check-card">
    <button class="ck-row" id="ckToggle" aria-expanded="${showCheck}">
      ${miniRing(pa.score, 100, scoreColor, "", "")}
      <span class="ck-main"><b>Plan kontrolü · ${pa.score}/100</b><small>${warnN ? `${warnN} öneri var, dokun ve gör` : "Dengeli: tüm kaslar hedef aralığında"}</small></span>
      <span class="da-chev ${showCheck ? "open" : ""}">›</span>
    </button>
    ${showCheck ? `<div class="ck-detail">
      <div class="body-wrap small-map">${buildBodySVG("front", color)}${buildBodySVG("back", color)}</div>
      <div class="legend">
        <span><i style="background:var(--m-none)"></i>Yok</span><span><i style="background:var(--m-low)"></i>Eksik</span>
        <span><i style="background:var(--m-ok)"></i>İdeal</span><span><i style="background:var(--m-high)"></i>Fazla</span>
      </div>
      <div id="planMuscleInfo" class="muscle-info muted small">Bir kasa dokun: planda kaç set var, hedefin ne?</div>
      <div class="plan-insights">${pa.insights.map((ins, ii) => `
        <div class="insight ${ins.type}"><b>${esc(ins.title)}</b><p>${esc(ins.text)}</p>
          ${(ins.actions || []).length ? `<div class="act-row">${ins.actions.map((a, ai) => `<button class="btn act" data-act="${ii}:${ai}">${esc(a.label)}</button>`).join("")}</div>` : ""}
        </div>`).join("")}</div>
    </div>` : ""}
  </section>`;

  // --- plan ayarları
  const settings = `<section class="card plan-settings">
    <h3>Plan ayarları</h3>
    <div class="seg">${Object.entries(PLAN_MODES).map(([k, m]) => `<button class="${prog.mode === k ? "on" : ""}" data-mode="${k}">${m.icon} ${m.name}</button>`).join("")}</div>
    <p class="muted small mode-desc">${manual
      ? "Bu senin planın. Hareketleri sen seçiyorsun; günleri açıp düzenleyebilirsin, ben her değişiklikte kontrol ediyorum."
      : "Bu planı cevaplarına göre ben oluşturdum. \"Düzenle\" ile kendi planına çevirebilirsin."}</p>
    ${manual ? "" : `<div class="row wrap"><button class="btn" id="editPlan">✏️ Düzenle</button><button class="btn ghost" id="regenPlan">🔄 Yeniden oluştur</button></div>`}
    <p class="muted small tip">💡 Tüm setlerde tekrar aralığının üst sınırına ulaşınca bir sonraki antrenmanda ağırlığı 2.5 kg (küçük kaslarda 1-2 kg) artır.</p>
  </section>`;

  $("#planBody").innerHTML = `<div class="pg">
    <header class="pg-hello"><small>${dateTxt}</small><h1>${timeGreeting()}${p.name ? ", " + esc(p.name) : ""} 👋</h1></header>
    ${hero}${week}${rings}${todayList}${days}${check}${settings}
  </div>`;
}

function shortDayName(name) {
  return name.replace("Vücut ", "").replace("Tüm ", "Tüm ").replace(/\s*\((\w+)\)/, "").replace("İtme", "İtme").slice(0, 12);
}

function exRow(e, i) {
  const ex = findExercise(e.id, state.customExercises);
  const kg = lastWeight(e.id);
  const ok = loggedToday(e.id);
  const risk = riskyFor(e.id, state.profile);
  return `<button class="ex-row ${ok ? "ok" : ""}" data-planex="${e.id}" data-sets="${e.sets}" data-reps="${esc(e.reps)}">
    <span class="er-num">${ok ? "✓" : i + 1}</span>
    <span class="er-main"><b>${esc(exName(e.id))}${risk.length ? ` <span class="risk">⚠️</span>` : ""}</b>
      <small>${(ex?.p || []).map((m) => MUSCLES[m].name).join(", ")}${kg != null ? ` · Son: ${kg} kg` : " · Yeni"}</small></span>
    <span class="er-sets">${e.sets}×${esc(e.reps)}</span>
    <span class="da-chev">›</span>
  </button>`;
}

// Kendi planında günün düzenleme alanı
function dayEditor(day, i) {
  return `<div class="de-head">
      <input class="day-name" data-dayname="${i}" value="${esc(day.name)}" aria-label="Gün adı" />
      <button class="icon-btn" data-delday="${i}" title="Günü sil">🗑</button>
    </div>
    ${day.exercises.map((e, ei) => {
      const ex = findExercise(e.id, state.customExercises);
      const risk = riskyFor(e.id, state.profile);
      return `<div class="de-ex">
        <div class="de-top">
          <span class="er-num">${ei + 1}</span>
          <span class="er-main"><b>${esc(exName(e.id))}${risk.length ? ` <span class="risk">⚠️ ${risk.join(", ")}</span>` : ""}</b>
            <small>${(ex?.p || []).map((m) => MUSCLES[m].name).join(", ")}</small></span>
          <button class="btn small-btn" data-planex="${e.id}" data-sets="${e.sets}" data-reps="${esc(e.reps)}">Kaydet</button>
        </div>
        <div class="stepper">
          <button data-step="${i}:${ei}:-1" aria-label="Set azalt">−</button><span>${e.sets} set</span><button data-step="${i}:${ei}:1" aria-label="Set artır">+</button>
          <span class="muted">×</span><input class="reps-in" data-reps="${i}:${ei}" value="${esc(e.reps)}" aria-label="Tekrar" />
          <button class="icon-btn" data-up="${i}:${ei}" title="Yukarı taşı" ${ei ? "" : "disabled"}>↑</button>
          <button class="icon-btn" data-rmex="${i}:${ei}" title="Çıkar">✕</button>
        </div>
      </div>`;
    }).join("") || `<div class="muted small" style="padding:8px 0">Bu günde henüz hareket yok.</div>`}
    <button class="btn ghost full" data-pick="${i}">+ Hareket ekle</button>`;
}

// ---------- Hareket seçici (resimli, iki adım: bölge -> hareket) ----------
let pickMuscle = null;
const EQ_TAG = { gym: "Salon", db: "Dumbbell", bw: "Ekipmansız" };
const ST_TAG = { none: ["Yok", "st-none"], low: ["Eksik", "st-low"], ok: ["İdeal", "st-ok"], high: ["Fazla", "st-high"] };

// Hareketin fotoğrafı: başlangıç ve bitiş pozu arasında geçiş yapar; görseli olmayanlarda kas resmi
function exImageHTML(id) {
  const n = (typeof EX_IMAGES !== "undefined" && EX_IMAGES[id]) || 0;
  if (!n) {
    const ex = findExercise(id, state.customExercises);
    return `<span class="eximg noimg">${muscleThumbSVG(ex?.p?.[0] || "chest")}</span>`;
  }
  return `<span class="eximg ${n > 1 ? "anim" : ""}">
    <img src="img/ex/${id}_0.jpg" alt="" loading="lazy" decoding="async" />
    ${n > 1 ? `<img src="img/ex/${id}_1.jpg" alt="" loading="lazy" decoding="async" class="b" />` : ""}
  </span>`;
}

function openPicker(dayIdx, muscle = null) {
  planPickDay = dayIdx;
  pickMuscle = muscle;
  $("#pickSearch").value = "";
  $("#picker").classList.remove("hidden");
  $("#pickList").scrollTop = 0;
  renderPicker();
}

function renderPicker() {
  const pa = analyzePlan(state.program, state.profile, state.customExercises);
  const day = state.program.days[planPickDay];
  const inDay = new Set(day.exercises.map((e) => e.id));
  const q = $("#pickSearch").value.trim().toLocaleLowerCase("tr");
  const showAll = $("#pickAll").checked;
  const ok = new Set(allowedExercises(state.profile, state.customExercises).map((e) => e.id));
  const f = pa.f || 1;
  $("#pickDayInfo").innerHTML = `<b>${esc(day.name)}</b> · ${day.exercises.length} hareket · ${daySets(day)} set · ~${Math.round(daySets(day) * SET_MINUTES)} dk`;

  // 1) Bölge seçimi
  if (!pickMuscle && !q) {
    $("#pickTitle").textContent = "Hangi bölgeyi çalıştıracaksın?";
    $("#pickBack").classList.add("hidden");
    $("#pickAllRow").classList.add("hidden");
    const need = Object.keys(MUSCLES).filter((m) => pa.statuses[m] === "none" || pa.statuses[m] === "low");
    $("#pickList").innerHTML =
      (need.length ? `<p class="pick-hint small">⚠️ Planında eksik kalan bölgeler turuncu işaretli. Önce onlardan seçmeni öneririm.</p>` : `<p class="pick-hint ok small">✓ Planında eksik bölge kalmadı.</p>`) +
      PRIORITY_GROUPS.map(([title, keys]) => `<h3 class="mg-title">${title}</h3><div class="muscle-grid">${keys.map((m) => {
        const [label, cls] = ST_TAG[pa.statuses[m]];
        const nEx = allExercises().filter((e) => e.p.includes(m) && ok.has(e.id)).length;
        return `<button class="mcard pick-m ${cls}" data-pickm="${m}">
          ${muscleThumbSVG(m)}<span>${MUSCLES[m].name}</span>
          <small class="pm-st ${cls}">${label} · ${fmt(+(pa.vol[m] * f).toFixed(1))} set</small>
          <small class="pm-n">${nEx} hareket</small>
        </button>`;
      }).join("")}</div>`).join("");
    return;
  }

  // 2) Hareketler (seçilen bölge ya da arama)
  $("#pickBack").classList.remove("hidden");
  $("#pickAllRow").classList.remove("hidden");
  $("#pickTitle").textContent = pickMuscle && !q ? `${MUSCLES[pickMuscle].name} hareketleri` : "Arama sonuçları";
  let list = allExercises().filter((e) => (showAll || ok.has(e.id)) &&
    (q ? (e.name.toLocaleLowerCase("tr").includes(q) || e.p.some((m) => MUSCLES[m].name.toLocaleLowerCase("tr").includes(q))) : e.p.includes(pickMuscle)));
  // bölgeyi birincil olarak hedefleyenler önce, sonra tercih sırası
  if (pickMuscle && !q) {
    const pref = PREFERRED[pickMuscle] || [];
    const rank = (e) => (e.p[0] === pickMuscle ? 0 : 1) * 100 + (pref.includes(e.id) ? pref.indexOf(e.id) : 50);
    list.sort((a, b) => rank(a) - rank(b));
  }
  const st = pickMuscle ? pa.statuses[pickMuscle] : null;
  const head = pickMuscle && !q ? (() => {
    const t = muscleTarget(pickMuscle, state.profile);
    const [label, cls] = ST_TAG[st];
    return `<div class="pm-head ${cls}">${muscleThumbSVG(pickMuscle)}<div><b>${MUSCLES[pickMuscle].name}</b>
      <small>Planda haftalık ${volRange(pa.vol[pickMuscle], f)} set · hedefin ${t.min}-${t.max} · <span class="pm-st ${cls}">${label}</span></small></div></div>`;
  })() : "";
  $("#pickList").innerHTML = head + `<div class="ex-grid">${list.map((e) => {
    const added = inDay.has(e.id);
    const over = e.p.filter((m) => pa.statuses[m] === "high");
    const risk = riskyFor(e.id, state.profile);
    return `<button class="ex-card-pick ${added ? "added" : ""}" data-pickid="${e.id}" aria-pressed="${added}">
      ${exImageHTML(e.id)}
      <span class="ecp-badge">${added ? "✓ Eklendi" : "＋ Ekle"}</span>
      <span class="ecp-body">
        <b>${esc(e.name)}</b>
        <small>${e.p.map((m) => MUSCLES[m].name).join(", ")}</small>
        <span class="ecp-tags"><span>${EQ_TAG[e.eq] || "Özel"}</span>${risk.length ? `<span class="t-risk">⚠️ ${risk.join(", ")}</span>` : ""}${over.length ? `<span class="t-over">↑ ${over.map((m) => MUSCLES[m].name).join(", ")} fazla</span>` : ""}</span>
      </span>
    </button>`;
  }).join("")}</div>` + (list.length ? "" : `<div class="muted small" style="padding:12px 0">Uygun hareket bulamadım. "Uymayanları da göster"i açabilirsin.</div>`);
}

function addToPlan(dayIdx, id, sets, reps) {
  const goal = GOALS[goalKey(state.profile.goal)];
  state.program.days[dayIdx].exercises.push({
    id, sets: sets || Math.max(2, goal.sets + (state.profile.level === "beginner" ? -1 : 0)),
    reps: reps || (id === "plank" || id === "side_plank" || id === "wall_sit" ? "30-60 sn" : goal.reps),
  });
  save();
}

function toManual() {
  state.program.mode = "manual";
  if (!/Kendi/.test(state.program.split)) state.program.split = state.program.split + " (düzenlendi)";
}

function initPlanner() {
  $("#planBody").addEventListener("click", (e) => {
    const b = e.target.closest("button");
    if (!b) return;
    const prog = state.program;
    if (b.id === "planStartWizard") return openWizard();
    if (b.id === "tcStart") { const s = sessionDay(); return s ? openSessionExercise() : startSession(nextDayIndex()); }
    if (b.dataset.startday) return startSession(+b.dataset.startday);
    if (b.dataset.gotab) return $(`#tabs button[data-tab="${b.dataset.gotab}"]`).click();
    if (b.id === "ckToggle") { planUI.checkOpen = !planUI.checkOpen; return renderPlan(); }
    if (b.dataset.goday != null) {
      const i = +b.dataset.goday;
      planUI.open.add(i); renderPlan();
      const el = $(`#planBody [data-acc="${i}"]`);
      el?.scrollIntoView({ behavior: "smooth", block: "start" });
      if (state.program.mode === "manual" && !state.program.days[i]?.exercises.length) openPicker(i);
      return;
    }
    if (b.dataset.planex) {
      $(`#tabs button[data-tab="workout"]`).click();
      $("#wDate").value = todayStr(); renderDayLog();
      selectExercise(b.dataset.planex, { sets: +b.dataset.sets, reps: b.dataset.reps });
      $("#setEditor").scrollIntoView({ behavior: "smooth", block: "center" });
      return;
    }
    if (b.dataset.mode && b.dataset.mode !== prog.mode) {
      if (b.dataset.mode === "auto") {
        const has = prog.days.some((d) => d.exercises.length);
        if (has && !confirm("Kendi planının yerine cevaplarına göre otomatik plan oluşturulacak. Devam edilsin mi?")) return;
        state.program = { ...generateProgram(state.profile, state.customExercises), mode: "auto" };
        toast("Otomatik plan oluşturuldu 🤖");
      } else {
        const keep = confirm("Kendi planına geçiyorsun.\n\nTamam: mevcut planı başlangıç olarak kullan ve düzenle\nİptal: boş planla sıfırdan başla");
        if (keep) toManual(); else state.program = emptyProgram(state.profile.days);
        toast("Artık planı sen seçiyorsun, ben kontrol ediyorum ✍️");
        if (!keep) { planUI.init = false; save(); renderPlan(); return openPicker(0); }
      }
      planUI.init = false; save(); return renderPlan();
    }
    if (b.id === "editPlan") { toManual(); planUI.init = false; save(); toast("Düzenleme açık ✍️"); return renderPlan(); }
    if (b.id === "regenPlan") { state.program = { ...generateProgram(state.profile, state.customExercises), mode: "auto" }; save(); toast("Plan yeniden oluşturuldu"); return renderPlan(); }
    if (b.id === "addDay") { prog.days.push({ key: "custom", name: `${prog.days.length + 1}. Gün`, exercises: [] }); save(); return renderPlan(); }
    if (b.id === "clearPlan") { if (confirm("Tüm günlerdeki hareketler silinsin mi?")) { prog.days.forEach((d) => (d.exercises = [])); save(); renderPlan(); } return; }
    if (b.dataset.delday) {
      const i = +b.dataset.delday;
      if (prog.days[i].exercises.length && !confirm(`${prog.days[i].name} silinsin mi?`)) return;
      prog.days.splice(i, 1); save(); return renderPlan();
    }
    if (b.dataset.pick) return openPicker(+b.dataset.pick);
    if (b.dataset.step) {
      const [di, ei, dir] = b.dataset.step.split(":").map(Number);
      const ex = prog.days[di].exercises[ei];
      ex.sets = Math.min(10, Math.max(1, ex.sets + dir));
      save(); return renderPlan();
    }
    if (b.dataset.up) {
      const [di, ei] = b.dataset.up.split(":").map(Number);
      const arr = prog.days[di].exercises; [arr[ei - 1], arr[ei]] = [arr[ei], arr[ei - 1]];
      save(); return renderPlan();
    }
    if (b.dataset.rmex) {
      const [di, ei] = b.dataset.rmex.split(":").map(Number);
      prog.days[di].exercises.splice(ei, 1); save(); return renderPlan();
    }
    if (b.dataset.act) {
      const [ii, ai] = b.dataset.act.split(":").map(Number);
      const a = renderPlan._pa.insights[ii].actions[ai];
      if (prog.mode !== "manual") toManual();
      if (a.kind === "add") { addToPlan(a.day, a.id, a.sets, a.reps); toast(`${exName(a.id)} eklendi`); }
      if (a.kind === "reduce") { prog.days[a.day].exercises[a.idx].sets--; save(); toast("1 set azaltıldı"); }
      if (a.kind === "remove") { prog.days[a.day].exercises.splice(a.idx, 1); save(); toast("Plandan çıkarıldı"); }
      return renderPlan();
    }
  });
  // açılır günlerin açık/kapalı durumu yeniden çizimde korunur
  $("#planBody").addEventListener("toggle", (e) => {
    const d = e.target.closest?.("[data-acc]");
    if (!d) return;
    const i = +d.dataset.acc;
    if (d.open) planUI.open.add(i); else planUI.open.delete(i);
  }, true);
  document.addEventListener("click", (e) => { if (e.target.closest("#sbEnd")) finishSession(false); });
  $("#planBody").addEventListener("change", (e) => {
    const t = e.target;
    if (t.dataset.dayname != null) { state.program.days[+t.dataset.dayname].name = t.value.trim() || `${+t.dataset.dayname + 1}. Gün`; save(); renderPlan(); }
    if (t.dataset.reps) { const [di, ei] = t.dataset.reps.split(":").map(Number); state.program.days[di].exercises[ei].reps = t.value.trim() || "8-12"; save(); }
  });
  $("#planBody").addEventListener("click", (e) => {
    const p = e.target.closest(".muscle");
    if (!p) return;
    const m = p.dataset.muscle, pa = renderPlan._pa, t = T(m);
    $$("#planBody .muscle").forEach((x) => x.classList.toggle("focus", x.dataset.muscle === m));
    const exs = state.program.days.flatMap((d) => d.exercises.filter((e) => {
      const ex = findExercise(e.id, state.customExercises); return ex && (ex.p.includes(m) || ex.s.includes(m));
    }).map((e) => `${exName(e.id)} (${d.name})`));
    $("#planMuscleInfo").innerHTML = `<b style="color:var(--text)">${MUSCLES[m].name}</b> — ${STATUS_TXT[pa.statuses[m]]}<br>
      Planda haftalık <b>${volRange(pa.vol[m], lowWeekFactor(state.profile, state.program))}</b> set · hedefin ${t.min}-${t.max}<br>
      ${exs.length ? "Hareketler: " + exs.map(esc).join(", ") : "Bu kas için planında hareket yok."}`;
  });

  // seçici
  $("#pickClose").onclick = () => { $("#picker").classList.add("hidden"); planUI.open.add(planPickDay); renderPlan(); };
  $("#pickSearch").addEventListener("input", renderPicker);
  $("#pickAll").addEventListener("change", renderPicker);
  $("#pickBack").onclick = () => { pickMuscle = null; $("#pickSearch").value = ""; renderPicker(); $("#pickList").scrollTop = 0; };
  $("#pickList").addEventListener("click", (e) => {
    const m = e.target.closest("[data-pickm]");
    if (m) { pickMuscle = m.dataset.pickm; renderPicker(); $("#pickList").scrollTop = 0; return; }
    const it = e.target.closest("[data-pickid]");
    if (!it) return;
    const id = it.dataset.pickid, day = state.program.days[planPickDay];
    const at = day.exercises.findIndex((x) => x.id === id);
    if (at >= 0) { day.exercises.splice(at, 1); save(); toast(`${exName(id)} çıkarıldı`); }
    else { addToPlan(planPickDay, id); toast(`${exName(id)} eklendi ✓`); }
    renderPicker();
  });
}

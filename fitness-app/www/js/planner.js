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

// Kasın plana göre durumu (verilen profile göre; sihirbaz özetinde state.profile henüz kaydedilmemiş olabilir)
function planStatus(m, v, profile) {
  const { min, max } = muscleTarget(m, profile);
  if (v === 0) return min === 0 ? "ok" : "none";
  return v < min ? "low" : v > max ? "high" : "ok";
}

// Otomatik planı kendi kontrolünden geçecek şekilde dengeler:
// 1) fazla kasın en büyük hareketinden set düşür, 2) en eksik kasa set ya da hareket ekle (seans süresi aşılmadan)
function balanceProgram(prog, profile, custom = []) {
  const cap = Math.floor((+profile.sessionMin || 60) / SET_MINUTES);
  const goal = GOALS[goalKey(profile.goal)];
  const daySets = (d) => d.exercises.reduce((a, e) => a + e.sets, 0);
  const exOf = (e) => findExercise(e.id, custom);
  const skip = new Set(), skipOver = new Set();
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
        const keepsMins = x.p.every((m) => m === over || vol[m] - 1 >= tgt(m).min);
        const w = (x.p.includes(over) ? 1 : 0.5) * e.sets;
        if (keepsMins && (!best || w > best.w)) best = { e, d, w };
      }
      if (best && best.e.sets > 2) { best.e.sets--; continue; }
      if (best) { best.d.exercises.splice(best.d.exercises.indexOf(best.e), 1); continue; }
      skipOver.add(over);
      continue;
    }
    // 2) en büyük oransal eksik, yoksa denge kuralının istediği kas
    const deficits = Object.keys(MUSCLES).filter((m) => !skip.has(m) && vol[m] < tgt(m).min)
      .sort((a, b) => (tgt(b).min - vol[b]) / tgt(b).min - (tgt(a).min - vol[a]) / tgt(a).min);
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
    // b) yeni hareket ekle
    const ex = bestExerciseFor(need, profile, prog, custom);
    const sets = Math.max(2, goal.sets + (profile.level === "beginner" ? -1 : 0));
    if (ex && fitsAll(ex, sets)) {
      const order = prog.days.map((d, i) => i).filter((i) => daySets(prog.days[i]) + sets <= cap && !prog.days[i].exercises.some((e) => e.id === ex.id))
        .sort((a, b) => bestDayScore(need, prog.days[b], custom) - bestDayScore(need, prog.days[a], custom) || daySets(prog.days[a]) - daySets(prog.days[b]));
      if (order.length) {
        prog.days[order[0]].exercises.push({ id: ex.id, sets, reps: ex.id === "plank" || ex.id === "side_plank" ? "30-60 sn" : goal.reps, muscle: need });
        continue;
      }
    }
    // c) denge için: karşı taraftaki fazlayı 1 set azalt (ör. çekme fazlaysa en büyük çekme hareketini)
    if (forBalance) {
      const opp = balanceInsights(vol, "plan").find((b) => (b.fix || []).includes(need));
      const heavy = opp && Object.keys(MUSCLES).filter((m) => !(opp.fix || []).includes(m)).sort((a, b) => vol[b] - vol[a]);
      let cut = null;
      if (heavy) for (const d of prog.days) for (const e of d.exercises) {
        const x = exOf(e);
        if (x && e.sets > 2 && x.p.some((m) => heavy.slice(0, 4).includes(m)) && x.p.every((m) => vol[m] - 1 >= tgt(m).min) && (!cut || e.sets > cut.sets)) cut = e;
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
  const statusOf = (m, v) => planStatus(m, v, profile);
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
      insights.push({ type: "warn", title: `${MUSCLES[m].name} yetersiz`, text: `Haftalık ${fmt(v)} set planlanmış, hedefin ${t.min}-${t.max}. En az ${Math.ceil(t.min - v)} set daha ekle.` +
        (a ? "" : " Ekipmanınla bu kas için uygun hareket bulamadım; mevcut hareketlerin set sayısını artırabilirsin."), actions: a ? [a] : [] });
    } else if (st === "high") {
      // en çok set veren hareketi bul ve azaltmayı öner
      let top = null;
      program.days.forEach((d, di) => d.exercises.forEach((e, ei) => {
        const ex = findExercise(e.id, custom);
        if (ex?.p.includes(m) && e.sets > 1 && (!top || e.sets > top.sets)) top = { di, ei, sets: e.sets, name: ex.name };
      }));
      insights.push({ type: "danger", title: `${MUSCLES[m].name} fazla`, text: `Haftalık ${fmt(v)} set planlanmış, en fazla ${t.max} olmalı. Fazla hacim toparlanmayı bozar, gelişimi yavaşlatır.`,
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
  const weeklyMin = (+profile.days || program.days.length) * (+profile.sessionMin || 60);
  const deficitCount = Object.values(statuses).filter((x) => x === "low" || x === "none").length;
  if (weeklyMin < 180 && deficitCount >= 2) {
    insights.push({ type: "info", title: "Haftalık süren kısıtlı", text: `Haftada ${profile.days || program.days.length} gün × ${profile.sessionMin || 60} dk ile her kası ideal hacimde çalıştırmak zor. En etkili çözüm bir gün ya da 15 dk eklemek; yoksa büyük kaslara öncelik ver.`, actions: [] });
  }

  const okCount = Object.values(statuses).filter((s) => s === "ok").length;
  const score = Math.max(0, Math.round((okCount / Object.keys(MUSCLES).length) * 100 - insights.filter((i) => i.type === "danger").length * 5));
  if (!insights.some((i) => i.type === "danger" || i.type === "warn")) {
    insights.unshift({ type: "good", title: "Planın dengeli 👌", text: "Tüm kas grupları hedef aralığında ve büyük bir dengesizlik yok. Şimdi sıra uygulamada!" });
  }
  const order = { danger: 0, warn: 1, info: 2, good: 3 };
  insights.sort((a, b) => order[a.type] - order[b.type]);
  return { vol, statuses, insights, score };
}

// ---------- Program sekmesi ----------
let planPickDay = null;

function renderPlan() {
  const p = state.profile, prog = state.program;
  $("#greet").textContent = p.name ? `Merhaba ${p.name} 👋` : "";
  if (!prog) {
    $("#planBody").innerHTML = `<div class="card"><p>Henüz programın yok.</p><button class="btn primary" id="planStartWizard">Soruları yanıtla</button></div>`;
    return;
  }
  const manual = prog.mode === "manual";
  const done = weekWorkoutDays(), next = prog.days.length ? nextDayIndex() : 0;
  const t = targets(), d = dayNut();
  const pg = d.protein.reduce((a, x) => a + x.g, 0);
  const todayDone = state.workouts.some((w) => w.date === todayStr());
  const nextDay = prog.days[next];
  const pa = analyzePlan(prog, p, state.customExercises);
  const color = (m) => COLORS[pa.statuses[m]];

  $("#planBody").innerHTML = `
    <div class="hello">
      <h1>${timeGreeting()}${p.name ? ", " + esc(p.name) : ""}! 👋</h1>
      <p class="muted">${todayDone ? "Bugünkü antrenmanını yaptın, harikasın. Şimdi dinlenme ve beslenme zamanı."
        : nextDay?.exercises.length ? `Sıradaki antrenmanın: <b style="color:var(--text)">${esc(nextDay.name)}</b> · ${nextDay.exercises.length} hareket`
        : manual ? "Programını kurmaya başla; her eklediğin harekette eksik ve fazlaları göstereceğim." : ""}</p>
    </div>
    <div class="stat-row">
      <div class="stat big"><b>${done}/${prog.days.length}</b><span>Bu hafta antrenman</span><div class="mini"><div style="width:${Math.min(100, done / Math.max(1, prog.days.length) * 100)}%;background:var(--accent)"></div></div></div>
      <div class="stat big"><b>${pg}/${t.protein} g</b><span>Bugün protein</span><div class="mini"><div style="width:${Math.min(100, pg / t.protein * 100)}%;background:#4ade80"></div></div></div>
      ${t.creatine ? `<div class="stat big"><b>${d.creatine}/${t.creatine} g</b><span>Bugün kreatin</span><div class="mini"><div style="width:${Math.min(100, d.creatine / t.creatine * 100)}%;background:#60a5fa"></div></div></div>` : ""}
    </div>

    <div class="seg">
      ${Object.entries(PLAN_MODES).map(([k, m]) => `<button class="${prog.mode === k ? "on" : ""}" data-mode="${k}">${m.icon} ${m.name}</button>`).join("")}
    </div>
    <p class="muted small mode-desc">${manual
      ? "✍️ Bu senin planın. Hareketleri sen seçiyorsun; ben her değişiklikte eksik, fazla ve riskli noktaları aşağıda gösteriyorum."
      : "🤖 Bu planı cevaplarına göre ben oluşturdum. İstersen \"Düzenle\" ile kendi planına çevirebilirsin."}</p>

    <div class="grid two plan-grid">
      <div class="card">
        <h2>${esc(prog.split)} <span class="muted small">· ${GOALS[goalKey(p.goal)].name}</span></h2>
        ${todayDone ? `<div class="insight good"><b>Bugünkü antrenman kaydedildi ✓</b><p>Toparlanmak için protein hedefini tamamla.</p></div>` : ""}
        ${prog.days.map((day, i) => dayCard(day, i, i === next, manual)).join("")}
        <div class="row wrap">
          ${manual ? `<button class="btn" id="addDay">+ Gün ekle</button><button class="btn ghost" id="clearPlan">Planı temizle</button>`
                   : `<button class="btn primary" id="editPlan">✏️ Düzenle</button><button class="btn" id="regenPlan">🔄 Yeniden oluştur</button>`}
        </div>
        <p class="muted small">İpucu: Tüm setlerde tekrar aralığının üst sınırına ulaşınca bir sonraki antrenmanda ağırlığı 2.5 kg (küçük kaslarda 1-2 kg) artır.</p>
      </div>

      <div class="card">
        <h2>Plan Kontrolü <span class="score-chip">${pa.score}/100</span></h2>
        <p class="muted small" style="margin-top:-6px">Planladığın haftalık setler, ${p.name ? esc(p.name) + " için" : "senin için"} belirlediğim hedeflerle karşılaştırılıyor.</p>
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
      </div>
    </div>`;
  renderPlan._pa = pa;
}

function dayCard(day, i, isNext, manual) {
  const sets = day.exercises.reduce((a, e) => a + e.sets, 0);
  return `<div class="plan-day ${isNext ? "next" : ""}">
    <div class="plan-head">
      ${manual ? `<input class="day-name" data-dayname="${i}" value="${esc(day.name)}" />` : `<b>${i + 1}. Gün · ${esc(day.name)}</b>`}
      <span class="muted small">${sets} set · ~${Math.round(sets * SET_MINUTES)} dk</span>
      ${isNext ? `<span class="badge">Sıradaki</span>` : ""}
      ${manual ? `<button class="icon-btn" data-delday="${i}" title="Günü sil">🗑</button>` : ""}
    </div>
    ${day.exercises.map((e, ei) => {
      const last = [...state.workouts].reverse().find((w) => w.exerciseId === e.id);
      const lastTxt = last ? `Son: ${Math.max(...last.sets.map((s) => s.kg || 0))} kg` : "Yeni";
      const ex = findExercise(e.id, state.customExercises);
      const risk = riskyFor(e.id, state.profile);
      return `<div class="plan-ex">
        <div class="grow">
          <div>${esc(exName(e.id))}${risk.length ? ` <span class="risk">⚠️ ${risk.join(", ")}</span>` : ""}</div>
          <div class="muted small">${(ex?.p || []).map((m) => MUSCLES[m].name).join(", ")} · ${lastTxt}</div>
          ${manual ? `<div class="stepper">
              <button data-step="${i}:${ei}:-1">−</button><span>${e.sets} set</span><button data-step="${i}:${ei}:1">+</button>
              <span class="muted">×</span><input class="reps-in" data-reps="${i}:${ei}" value="${esc(e.reps)}" />
              <button class="icon-btn" data-up="${i}:${ei}" title="Yukarı taşı" ${ei ? "" : "disabled"}>↑</button>
              <button class="icon-btn" data-rmex="${i}:${ei}" title="Çıkar">✕</button>
            </div>` : `<div class="muted small">${e.sets} set × ${esc(e.reps)}</div>`}
        </div>
        <button class="btn small-btn" data-planex="${e.id}" data-sets="${e.sets}" data-reps="${esc(e.reps)}">Kaydet</button>
      </div>`;
    }).join("") || `<div class="muted small" style="padding:8px 0">Bu günde henüz hareket yok.</div>`}
    ${manual ? `<button class="btn ghost full" data-pick="${i}">+ Hareket ekle</button>` : ""}
  </div>`;
}

// ---------- Hareket seçici ----------
let pickCat = "Tümü";
function openPicker(dayIdx) {
  planPickDay = dayIdx;
  pickCat = "Tümü";
  $("#pickSearch").value = "";
  $("#pickTitle").textContent = `${state.program.days[dayIdx].name} için hareket seç`;
  $("#picker").classList.remove("hidden");
  renderPicker();
}
function renderPicker() {
  const pa = analyzePlan(state.program, state.profile, state.customExercises);
  const need = Object.keys(MUSCLES).filter((m) => pa.statuses[m] === "none" || pa.statuses[m] === "low");
  const showAll = $("#pickAll").checked;
  const ok = new Set(allowedExercises(state.profile, state.customExercises).map((e) => e.id));
  const inDay = new Set(state.program.days[planPickDay].exercises.map((e) => e.id));
  const q = $("#pickSearch").value.trim().toLocaleLowerCase("tr");
  const cats = ["Tümü", "Eksiklerim", ...new Set(allExercises().map((e) => e.cat))];
  $("#pickCats").innerHTML = cats.map((c) => `<button class="chip ${c === pickCat ? "on" : ""}" data-pcat="${esc(c)}">${c === "Eksiklerim" ? `⚠️ Eksiklerim (${need.length})` : esc(c)}</button>`).join("");
  const list = allExercises().filter((e) =>
    (showAll || ok.has(e.id)) &&
    (pickCat === "Tümü" || (pickCat === "Eksiklerim" ? e.p.some((m) => need.includes(m)) : e.cat === pickCat)) &&
    (!q || e.name.toLocaleLowerCase("tr").includes(q) || e.p.some((m) => MUSCLES[m].name.toLocaleLowerCase("tr").includes(q))));
  $("#pickList").innerHTML = list.map((e) => {
    const hits = e.p.filter((m) => need.includes(m));
    const over = e.p.filter((m) => pa.statuses[m] === "high");
    const risk = riskyFor(e.id, state.profile);
    return `<div class="ex-item ${inDay.has(e.id) ? "sel" : ""}" data-pickid="${e.id}">
      <span>${esc(e.name)}
        ${hits.length ? `<span class="tag-good">✓ eksik: ${hits.map((m) => MUSCLES[m].name).join(", ")}</span>` : ""}
        ${over.length ? `<span class="tag-bad">↑ zaten fazla: ${over.map((m) => MUSCLES[m].name).join(", ")}</span>` : ""}
        ${risk.length ? `<span class="risk">⚠️ ${risk.join(", ")}</span>` : ""}
      </span>
      <span class="tags">${e.p.map((m) => MUSCLES[m].name).join(", ")}</span>
    </div>`;
  }).join("") || `<div class="muted small">Sonuç yok.</div>`;
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
      }
      save(); return renderPlan();
    }
    if (b.id === "editPlan") { toManual(); save(); toast("Düzenleme açık ✍️"); return renderPlan(); }
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
      Planda haftalık <b>${fmt(pa.vol[m])}</b> set · hedefin ${t.min}-${t.max}<br>
      ${exs.length ? "Hareketler: " + exs.map(esc).join(", ") : "Bu kas için planında hareket yok."}`;
  });

  // seçici
  $("#pickClose").onclick = () => { $("#picker").classList.add("hidden"); renderPlan(); };
  $("#pickSearch").addEventListener("input", renderPicker);
  $("#pickAll").addEventListener("change", renderPicker);
  $("#pickCats").addEventListener("click", (e) => { const c = e.target.closest("[data-pcat]"); if (c) { pickCat = c.dataset.pcat; renderPicker(); } });
  $("#pickList").addEventListener("click", (e) => {
    const it = e.target.closest("[data-pickid]");
    if (!it) return;
    addToPlan(planPickDay, it.dataset.pickid);
    toast(`${exName(it.dataset.pickid)} eklendi`);
    renderPicker();
  });
}

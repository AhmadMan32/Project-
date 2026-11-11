// Hareket animasyonları: eklemli bir figür iki poz (A <-> B) arasında gidip gelir.
// Yan görünüm açıları: 0 = yukarı, 90 = ileri (sağ), 180 = aşağı, 270/-90 = geri (sol).
const RIG = { torso: 28, neck: 3, head: 6, ua: 14, fa: 13, th: 20, sh: 20, ft: 7 };
const FLOOR = 93;

const rad = (a) => (a * Math.PI) / 180;
const step = (p, a, L) => [p[0] + Math.sin(rad(a)) * L, p[1] - Math.cos(rad(a)) * L];
const mid = (a, b, k = 0.5) => [a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k];

// ---- yan görünüm çözücü ----
function solveSide(P) {
  let hip, knee, ank;
  if (P.ank) { ank = P.ank; knee = step(ank, P.su, RIG.sh); hip = step(knee, P.tu, RIG.th); }
  else if (P.knee) { knee = P.knee; hip = step(knee, P.tu, RIG.th); ank = step(knee, P.sh, RIG.sh); }
  else { hip = P.hip; knee = step(hip, P.th, RIG.th); ank = step(knee, P.sh, RIG.sh); }
  const toe = step(ank, P.ft ?? 90, RIG.ft);
  const sho = step(hip, P.t, RIG.torso);
  const head = step(sho, P.t + (P.hd || 0), RIG.neck + RIG.head);
  const elb = step(sho, P.ua, RIG.ua), wr = step(elb, P.fa, RIG.fa);
  const elb2 = step(sho, P.ua2 ?? P.ua, RIG.ua), wr2 = step(elb2, P.fa2 ?? P.fa, RIG.fa);
  let knee2 = knee, ank2 = ank, toe2 = toe;
  if (P.th2 != null) { knee2 = step(hip, P.th2, RIG.th); ank2 = step(knee2, P.sh2 ?? P.th2, RIG.sh); toe2 = step(ank2, P.ft2 ?? P.ft ?? 90, RIG.ft); }
  return { hip, knee, ank, toe, sho, head, elb, wr, elb2, wr2, knee2, ank2, toe2 };
}

// ---- ön görünüm çözücü (yana açma hareketleri) ----
// a/b: kol ve önkol açısı (0 aşağı, 90 yana yatay, 180 yukarı, negatif içeri), g/g2: bacak açıları
function solveFront(P) {
  const py = P.py ?? 54, sy = py - 29 + (P.lift || 0);
  const shoL = [51, sy], shoR = [69, sy], hipL = [55, py], hipR = [65, py];
  const arm = (sho, s) => {
    const elb = [sho[0] + s * Math.sin(rad(P.a)) * RIG.ua, sho[1] + Math.cos(rad(P.a)) * RIG.ua];
    const b = P.b ?? P.a;
    const wr = [elb[0] + s * Math.sin(rad(b)) * RIG.fa, elb[1] + Math.cos(rad(b)) * RIG.fa];
    return [elb, wr];
  };
  const leg = (hip, s) => {
    const thL = P.seated ? RIG.th * 0.35 : RIG.th;
    const g = P.g ?? 4, g2 = P.g2 ?? g;
    const knee = [hip[0] + s * Math.sin(rad(g)) * thL, hip[1] + Math.cos(rad(g)) * thL];
    const ank = [knee[0] + s * Math.sin(rad(g2)) * RIG.sh, knee[1] + Math.cos(rad(g2)) * RIG.sh];
    return [knee, ank];
  };
  const [elbL, wrL] = arm(shoL, -1), [elbR, wrR] = arm(shoR, 1);
  const [kneeL, ankL] = leg(hipL, -1);
  const [kneeR, ankR] = P.gR != null ? (() => { const k = [hipR[0] + Math.sin(rad(P.gR)) * RIG.th, hipR[1] + Math.cos(rad(P.gR)) * RIG.th]; return [k, [k[0] + Math.sin(rad(P.gR)) * RIG.sh, k[1] + Math.cos(rad(P.gR)) * RIG.sh]]; })() : leg(hipR, 1);
  return { front: true, head: [60, sy - 10], neck: [60, sy - 3], shoL, shoR, hipL, hipR, elbL, wrL, elbR, wrR, kneeL, ankL, kneeR, ankR, pel: [60, py], chest: [60, sy] };
}

// ---- çizim yardımcıları ----
const C = {
  body: "#e3e8f0", bodyShade: "#c3cbd9", far: "#7c879c", farShade: "#667085",
  shorts: "#2f3e5c", shortsFar: "#243049", shoe: "#f4f6fa", shoeFar: "#8f9bb0", sole: "#64748b", hair: "#3b4458",
  hl: "#4ade80", hlGlow: "rgba(74,222,128,.26)", hlFar: "#2f7a4c",
  prop: "#9fb2cf", plate: "#60a5fa", plateDark: "#1e3a5f", dark: "#3a4354", metal: "#566178", padC: "#46536b", padHi: "#5d6c88",
};
const C_BASE = { ...C };
// Çizim tarzları: palet + bayraklar. ANIM_STYLE ile seçilir.
const STYLES = {
  manken: {},
  neon: { body: "#1a2130", bodyShade: "#1a2130", far: "#131924", farShade: "#131924", shorts: "#1a2130", shortsFar: "#131924", shoe: "#1a2130", shoeFar: "#131924", sole: "#7dd3fc", hair: "#1a2130",
    hl: "#39ff88", hlGlow: "rgba(57,255,136,.35)", stroke: "#8fd3ff", strokeFar: "#3f6b8f" },
  sporcu: { body: "#f1c4a5", bodyShade: "#dba888", far: "#bd8f74", farShade: "#a67a61", shirt: "#3b82f6", shorts: "#1f2a44", shortsFar: "#161f33", shoe: "#ffffff", shoeFar: "#b9c2d3", sole: "#ef4444", hair: "#3b2a20" },
  cizgi: { stick: true },
  // kas gösteren figürler için: tamamen beyaz insan, renk sadece kaslarda
  beyaz: { stroke: "#b3bccb", strokeFar: "#8d97a9", strokeW: 0.42, body: "#f4f6fa", bodyShade: "#dfe4ec", far: "#aab3c3", farShade: "#98a2b4", shorts: "#dde2ea", shortsFar: "#b9c1cf", shoe: "#f4f6fa", shoeFar: "#aab3c3", sole: "#b4bdcc", hair: "#cfd6e1" },
};
const BODY_STYLE = "beyaz";
let ANIM_STYLE = "sporcu";
let STY = {};
const f1 = (n) => n.toFixed(1);
const seg = (a, b, w, c) => `<line x1="${f1(a[0])}" y1="${f1(a[1])}" x2="${f1(b[0])}" y2="${f1(b[1])}" stroke="${c}" stroke-width="${w}" stroke-linecap="round"/>`;
const dot = (p, r, c, extra = "") => `<circle${STY.stroke && !extra && (c === C.body || c === C.bodyShade || c === C.shorts || c === C.hair) ? ` stroke="${STY.stroke}" stroke-width="${STY.strokeW || 0.9}"` : ""} cx="${f1(p[0])}" cy="${f1(p[1])}" r="${r}" fill="${c}" ${extra}/>`;
const rect = (x, y, w, h, c, r = 1.5) => `<rect x="${f1(x)}" y="${f1(y)}" width="${f1(w)}" height="${f1(h)}" rx="${r}" fill="${c}"/>`;
const along = (a, b, k) => [a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k];
const angOf = (a, b) => (Math.atan2(b[0] - a[0], -(b[1] - a[1])) * 180) / Math.PI; // a -> b yönü (0 = yukarı)

// İncelen kapsül: a ucunda r1, b ucunda r2 yarıçaplı yuvarlak uçlu şekil
function cap(a, b, r1, r2, fill, extra = "") {
  if (STY.stroke && !extra && (fill === C.body || fill === C.bodyShade || fill === C.shorts || fill === C.shoe || fill === C.hair)) extra = `stroke="${STY.stroke}" stroke-width="${STY.strokeW || 0.9}"`;
  else if (STY.stroke && !extra && (fill === C.far || fill === C.farShade || fill === C.shortsFar || fill === C.shoeFar)) extra = `stroke="${STY.strokeFar}" stroke-width="${STY.strokeW || 0.8}"`;
  const dx = b[0] - a[0], dy = b[1] - a[1], len = Math.hypot(dx, dy);
  if (len < 0.05) return dot(a, Math.max(r1, r2), fill, extra);
  const nx = -dy / len, ny = dx / len;
  const p1 = [a[0] + nx * r1, a[1] + ny * r1], p2 = [b[0] + nx * r2, b[1] + ny * r2];
  const p3 = [b[0] - nx * r2, b[1] - ny * r2], p4 = [a[0] - nx * r1, a[1] - ny * r1];
  return `<path d="M${f1(p1[0])} ${f1(p1[1])}L${f1(p2[0])} ${f1(p2[1])}A${r2} ${r2} 0 0 0 ${f1(p3[0])} ${f1(p3[1])}L${f1(p4[0])} ${f1(p4[1])}A${r1} ${r1} 0 0 0 ${f1(p1[0])} ${f1(p1[1])}Z" fill="${fill}" ${extra}/>`;
}
// cap() ile aynı şeklin sadece yol verisi
function capD(a, b, r1, r2) {
  const dx = b[0] - a[0], dy = b[1] - a[1], len = Math.hypot(dx, dy) || 0.01;
  const nx = -dy / len, ny = dx / len, P = (p, k, r) => `${f1(p[0] + nx * r * k)} ${f1(p[1] + ny * r * k)}`;
  return `M${P(a, 1, r1)}L${P(b, 1, r2)}A${r2} ${r2} 0 0 0 ${P(b, -1, r2)}L${P(a, -1, r1)}A${r1} ${r1} 0 0 0 ${P(a, 1, r1)}Z`;
}

// ---- kas boyama: tek renk yerine renk geçişli dolgu ----
// Seçenekler önizlemede karşılaştırılır; MUSCLE_STYLE uygulamanın her yerinde kullanılır.
const MUSCLE_STYLES = {
  anatomik: "Anatomik geçiş",  // üstten açık, alta doğru koyulaşan ton + ince kenar
  hacim:    "Hacimli",          // ışık kasın üst-iç kısmında, kenarlara doğru koyulaşır
  silindir: "Silindir ışık",    // uzvun bir yanından vuran ışık, karşı kenar gölgede
  lifli:    "Kas lifli",        // anatomik geçiş + kas lifi yönünde ince çizgiler
  yumusak:  "Yumuşak geçiş",    // ortası dolu, kenarları vücuda doğru eriyen ton
};
let MUSCLE_STYLE = "anatomik";
const MUSCLE_ALPHA = 0.8;
let MSEQ = 0;

// d: kas şekli, a -> b: kasın ekseni (lif yönü), w: yarı genişlik, c: kas rengi
function mPaint(d, a, b, w, c = C.hl, style = MUSCLE_STYLE) {
  const id = "mg" + (++MSEQ).toString(36);
  const L = tint(c, 0.42), M = c, D = tint(c, -0.28), DD = tint(c, -0.42);
  const dx = b[0] - a[0], dy = b[1] - a[1], len = Math.hypot(dx, dy) || 1;
  const nx = (-dy / len) * w, ny = (dx / len) * w, cx = (a[0] + b[0]) / 2, cy = (a[1] + b[1]) / 2;
  const st = (o, col, op = 1) => `<stop offset="${o}" stop-color="${col}"${op < 1 ? ` stop-opacity="${op}"` : ""}/>`;
  const lin = (x1, y1, x2, y2, stops) => `<linearGradient id="${id}" gradientUnits="userSpaceOnUse" x1="${f1(x1)}" y1="${f1(y1)}" x2="${f1(x2)}" y2="${f1(y2)}">${stops}</linearGradient>`;
  // kasın sınırı her stilde belirgin: koyu ince kontur; dolgu hafif saydam (vücudun gölgesi altından seçilir)
  const edge = (op) => ` stroke="${tint(c, -0.5)}" stroke-opacity="${op}" stroke-width=".42" stroke-linejoin="round"`;
  let defs, extra = edge(0.85) + ` fill-opacity="${MUSCLE_ALPHA}"`, over = "";
  if (style === "hacim") {
    defs = `<radialGradient id="${id}" cx=".45" cy=".4" r=".68" fx=".4" fy=".3">${st(0, L)}${st(0.5, M)}${st(1, DD)}</radialGradient>`;
  } else if (style === "silindir") {
    defs = lin(cx + nx, cy + ny, cx - nx, cy - ny, st(0, D) + st(0.3, L) + st(0.62, M) + st(1, DD));
  } else if (style === "yumusak") {
    defs = `<radialGradient id="${id}" cx=".5" cy=".45" r=".6">${st(0, tint(c, 0.12))}${st(0.55, M)}${st(0.85, M, 0.7)}${st(1, M, 0.25)}</radialGradient>`;
  } else {
    defs = lin(a[0], a[1], b[0], b[1], st(0, L) + st(0.5, M) + st(1, D));
    if (style === "lifli") {
      const pid = id + "f";
      defs += `<pattern id="${pid}" patternUnits="userSpaceOnUse" width="1.15" height="6" patternTransform="rotate(${f1(angOf(a, b))} ${f1(cx)} ${f1(cy)})"><rect width=".3" height="6" fill="#fff" fill-opacity=".32"/></pattern>`;
      over = `<path d="${d}" fill="url(#${pid})"/>`;
    }
  }
  return `<defs>${defs}</defs><path d="${d}" fill="url(#${id})"${extra}/>${over}`;
}

// Uzvun içine tam oturan kas şekli: a -> b uzvunun (yarıçap r1 -> r2) t1..t2 arası,
// enine lo..hi (-1..1, uzvun bir kenarından diğerine). Ortası dolgun, uçları hafif sivri.
function mBand(a, b, t1, t2, r1, r2, o = {}) {
  const { lo = -1, hi = 1, e = 0.55, ins = 0.35, b0 = 0.5, b1 = 0.5, prof } = o;
  const dx = b[0] - a[0], dy = b[1] - a[1], len = Math.hypot(dx, dy) || 0.01, ux = dx / len, uy = dy / len, nx = -uy, ny = ux;
  const N = 12;
  const P = (t, f, s) => {
    const r = Math.max(0.3, r1 + (r2 - r1) * t - ins) * (prof ? prof(s) : e + (1 - e) * Math.sin(Math.PI * s));
    return [a[0] + dx * t + nx * f * r, a[1] + dy * t + ny * f * r];
  };
  const H = [], Lo = [];
  for (let i = 0; i <= N; i++) { const s = i / N, t = t1 + (t2 - t1) * s; H.push(P(t, hi, s)); Lo.push(P(t, lo, s)); }
  const pt = (p) => `${f1(p[0])} ${f1(p[1])}`;
  const ctl = (p, q, dir, bulge) => {
    const hw = Math.hypot(p[0] - q[0], p[1] - q[1]) / 2;
    return [(p[0] + q[0]) / 2 + ux * dir * bulge * hw * 2, (p[1] + q[1]) / 2 + uy * dir * bulge * hw * 2];
  };
  return `M${pt(H[0])}` + H.slice(1).map((p) => `L${pt(p)}`).join("") +
    `Q${pt(ctl(H[N], Lo[N], 1, b1))} ${pt(Lo[N])}` + Lo.slice(0, N).reverse().map((p) => `L${pt(p)}`).join("") +
    `Q${pt(ctl(Lo[0], H[0], -1, b0))} ${pt(H[0])}Z`;
}
// Bir şekli verilen kırpma şekli (ör. gövde) içinde boya: kenarlar vücut hattını birebir izler
function mClip(clipInner, body) {
  const id = "mc" + (++MSEQ).toString(36);
  return `<defs><clipPath id="${id}">${clipInner}</clipPath></defs><g clip-path="url(#${id})">${body}</g>`;
}
const DELT_PROF = (s) => 1 - 0.68 * Math.pow(s, 1.5);           // omuzda geniş, kola doğru sivrilen
const FOREARM_PROF = (s) => (s < 0.3 ? 0.78 + 0.22 * (s / 0.3) : 1 - 0.55 * Math.pow((s - 0.3) / 0.7, 1.3)); // dirsek altında dolgun
const THIGH_PROF = (s) => 1 - 0.42 * Math.pow(s, 2.2);            // üstte (şort altında) tam, dize doğru incelen
const CALF_PROF = (s) => (s < 0.3 ? 0.72 + 0.28 * (s / 0.3) : 1 - 0.68 * Math.pow((s - 0.3) / 0.7, 1.2)); // baldır: üstte dolgun, aşil tendonuna sivrilen
const mGlow = (d) => (C.hlGlow && C.hlGlow !== "transparent" ? `<path d="${d}" fill="none" stroke="${C.hlGlow}" stroke-width="2" stroke-linejoin="round"/>` : "");
// Kas vurgusu: uzvun (yarıçap r1 -> r2) ön ya da arka yarısına oturan şekil
function muscle(a, b, k1, k2, normalAng, r1, r2, o = {}) {
  const dx = b[0] - a[0], dy = b[1] - a[1], len = Math.hypot(dx, dy) || 1;
  const side = (-dy / len) * Math.sin(rad(normalAng)) + (dx / len) * -Math.cos(rad(normalAng)) >= 0 ? 1 : -1;
  const d = mBand(a, b, k1, k2, r1, r2, { lo: side > 0 ? -0.05 : -1, hi: side > 0 ? 1 : 0.05, ...o });
  return mGlow(d) + mPaint(d, along(a, b, k1), along(a, b, k2), (r1 + r2) / 2);
}

// ---- ekipman ----
const plate = (p, r = 8) => dot(p, r, C.plateDark, `stroke="${C.plate}" stroke-width="2"`) + dot(p, r * 0.55, "none", `stroke="${C.plate}" stroke-width="0.9" opacity=".7"`) + dot(p, 1.6, C.prop);
const dumbbell = (p) => seg([p[0] - 4, p[1]], [p[0] + 4, p[1]], 1.8, C.prop)
  + rect(p[0] - 7, p[1] - 3.6, 3.4, 7.2, C.plate, 1.2) + rect(p[0] + 3.6, p[1] - 3.6, 3.4, 7.2, C.plate, 1.2)
  + rect(p[0] - 6.4, p[1] - 3.6, 1, 7.2, "rgba(255,255,255,.3)", 0.5) + rect(p[0] + 4.2, p[1] - 3.6, 1, 7.2, "rgba(255,255,255,.3)", 0.5);
const kettle = (p) => `<path d="M${f1(p[0] - 3.2)} ${f1(p[1] + 2)} q3.2 -7 6.4 0" fill="none" stroke="${C.prop}" stroke-width="1.9" stroke-linecap="round"/>` + dot([p[0], p[1] + 6], 5, C.plate) + dot([p[0] - 1.5, p[1] + 4.5], 1.3, "rgba(255,255,255,.35)");
const cable = (p, anchor) => seg(anchor, p, 1, C.prop) + dot(anchor, 3, C.dark, `stroke="${C.prop}" stroke-width="1"`) + dot(anchor, 1, C.prop) + rect(p[0] - 3.2, p[1] - 1.6, 6.4, 3.2, C.plate, 1.4);
const bag = (p) => rect(p[0] - 5.5, p[1], 11, 12, C.plate, 3.5) + rect(p[0] - 3.5, p[1] + 5, 7, 4.5, "rgba(255,255,255,.22)", 1.5) + `<path d="M${f1(p[0] - 2.5)} ${f1(p[1] + 0.5)} q2.5 -4 5 0" fill="none" stroke="${C.prop}" stroke-width="1.4"/>`;
const floor = () => `<rect x="2" y="${FLOOR + 3}" width="116" height="1.6" rx=".8" fill="${C.dark}"/>`;
const handle = (p) => rect(p[0] - 1.6, p[1] - 5, 3.2, 10, C.plate, 1.4);
const pad = (p, w = 10, h = 4) => rect(p[0] - w / 2, p[1] - h / 2, w, h, C.padHi, 1.8);
const smithRails = () => [22, 95].map((x) => rect(x, -12, 3, 108, C.metal, 1) + rect(x + 0.6, -12, 0.8, 108, "rgba(255,255,255,.18)", 0.4)).join("");
// minder: üstünde açık renk dikiş çizgisi olan dolgulu yüzey
const cushion = (a, b, w = 6) => seg(a, b, w, C.padC) + seg(step(a, angOf(a, b) - 90, w * 0.22), step(b, angOf(a, b) - 90, w * 0.22), 1, C.padHi);
const leg2 = (x, y1) => rect(x, y1, 3.2, FLOOR + 3 - y1, C.metal, 1) + rect(x - 2.4, FLOOR + 1.6, 8, 1.8, C.metal, 0.8);

// mevcut bir kalıbı farklı ekipmanla kullan: props birleştirilir (back/front sırası korunur)
function variant(base, extra = {}, over = {}) {
  const b = PATTERNS[base];
  const props = { ...b.props };
  if (extra.back) { const ob = b.props.back; props.back = (J, P) => (extra.back(J, P) + (ob ? ob(J, P) : "")); }
  if (extra.front) props.front = extra.front;
  if (extra.addFront) { const of = b.props.front; props.front = (J, P) => ((of ? of(J, P) : "") + extra.addFront(J, P)); }
  return { ...b, ...over, props };
}

// ---- figürü çiz (yan görünüm) ----
const R = { sho: 6.1, hipR: 5.2, ua1: 3.3, ua2: 2.7, fa1: 2.7, fa2: 2.05, hand: 2.3, th1: 4.6, th2: 3.4, sh1: 3.4, sh2: 2.35, foot: 2.5 };
function drawSide(J, hl, props, P, flip) {
  const has = (k) => hl.includes(k);
  const t = P.t ?? 0;
  const frontT = flip ? t - 90 : t + 90;            // gövdenin ön yüzünün baktığı yön
  const limbFront = (a, b) => angOf(a, b) + (flip ? 90 : -90);
  let s = "";
  // zemin gölgesi
  const cx = (J.hip[0] + J.sho[0]) / 2;
  s += `<ellipse cx="${f1(cx)}" cy="${FLOOR + 3.6}" rx="24" ry="2.6" fill="rgba(0,0,0,.32)"/>`;
  if (props.back) s += props.back(J, P);

  // --- uzak taraf (koyu ton) ---
  s += cap(J.hip, J.knee2, R.th1, R.th2, C.far) + cap(J.hip, along(J.hip, J.knee2, 0.46), R.th1 + 0.5, R.th1 * 0.93, C.shortsFar);
  s += cap(J.knee2, J.ank2, R.sh1, R.sh2, C.far) + cap(J.ank2, J.toe2, R.foot, R.foot * 0.8, C.shoeFar);
  s += cap(J.sho, J.elb2, R.ua1, R.ua2, C.far) + cap(J.elb2, J.wr2, R.fa1, R.fa2, C.far) + dot(J.wr2, R.hand, C.farShade);
  if (props.far) s += props.far(J, P);

  // --- gövde ---
  const neck = step(J.sho, t + (P.hd || 0), RIG.neck + 1.5);
  s += cap(J.sho, neck, 2.5, 2.3, C.bodyShade);
  s += cap(J.sho, J.hip, R.sho, R.hipR, C.body);
  if (STY.shirt) s += cap(along(J.hip, J.sho, 0.9), J.hip, R.sho * 0.97, R.hipR, STY.shirt) + cap(along(J.hip, J.sho, 0.9), along(J.hip, J.sho, 0.5), R.sho * 0.97, 5.5, "rgba(255,255,255,.08)");
  // gövde kasları
  if (has("back")) s += muscle(J.hip, J.sho, 0.16, 0.94, frontT + 180, R.hipR, R.sho, { e: 0.6 });
  if (has("chest")) s += muscle(J.hip, J.sho, 0.58, 0.93, frontT, R.hipR, R.sho, { e: 0.5 });
  if (has("abs")) s += muscle(J.hip, J.sho, 0.1, 0.54, frontT, R.hipR, R.sho, { e: 0.6 });
  if (has("traps")) { const a = step(J.sho, frontT + 180, 1.8), b = step(neck, frontT + 180, 0.8), d = mBand(a, b, -0.1, 1.05, 2.9, 2.2, { e: 0.7, ins: 0.4 }); s += mGlow(d) + mPaint(d, b, a, 2.5); }
  // şort + kalça
  s += dot(J.hip, R.hipR + 0.3, C.shorts);
  if (has("glute")) { const g = step(J.hip, frontT + 180, 1.6), d = mBand(step(g, t, 3.6), step(g, t + 180, 4.2), 0, 1, 3.6, 3.2, { e: 0.75, ins: 0, b0: 0.7, b1: 0.8 }); s += mGlow(d) + mPaint(d, step(g, t, 3.6), step(g, t + 180, 4.2), 3.6); }

  // --- baş ---
  s += dot(step(J.head, frontT + 180, 1.3), RIG.head, C.hair) + dot(step(J.head, t, 1.2), RIG.head - 0.4, C.hair);
  s += dot(step(J.head, frontT, 0.9), RIG.head - 0.5, C.body);

  // --- yakın bacak ---
  s += cap(J.hip, J.knee, R.th1, R.th2, C.body);
  const tf = limbFront(J.hip, J.knee);
  if (has("th")) { const d = mBand(J.hip, J.knee, 0.4, 0.95, R.th1, R.th2, { prof: THIGH_PROF, b1: 0.6 }); s += mGlow(d) + mPaint(d, along(J.hip, J.knee, 0.4), along(J.hip, J.knee, 0.94), R.th1); }
  s += cap(J.hip, along(J.hip, J.knee, 0.46), R.th1 + 0.5, R.th1 * 0.95, C.shorts);
  if (has("quads")) s += muscle(J.hip, J.knee, 0.4, 0.95, tf, R.th1, R.th2, { prof: THIGH_PROF, b1: 0.6 });
  if (has("hams")) s += muscle(J.hip, J.knee, 0.4, 0.95, tf + 180, R.th1, R.th2, { prof: THIGH_PROF, b1: 0.6 });
  s += cap(J.knee, J.ank, R.sh1, R.sh2, C.body);
  if (has("calf")) s += muscle(J.knee, J.ank, 0.06, 0.66, limbFront(J.knee, J.ank) + 180, R.sh1, R.sh2, { prof: CALF_PROF, b0: 0.35 });
  s += cap(J.ank, J.toe, R.foot, R.foot * 0.8, C.shoe) + seg(step(J.ank, angOf(J.ank, J.toe) + (flip ? -90 : 90), R.foot * 0.75), step(J.toe, angOf(J.ank, J.toe) + (flip ? -90 : 90), R.foot * 0.6), 0.9, C.sole);

  // --- yakın kol ---
  s += cap(J.sho, J.elb, R.ua1, R.ua2, C.body);
  const af = limbFront(J.sho, J.elb);
  if (has("biceps")) s += muscle(J.sho, J.elb, 0.24, 0.9, af, R.ua1, R.ua2, { e: 0.4 });
  if (has("triceps")) s += muscle(J.sho, J.elb, 0.2, 0.9, af + 180, R.ua1, R.ua2, { e: 0.4 });
  s += cap(J.elb, J.wr, R.fa1, R.fa2, C.body);
  if (has("forearm")) { const d = mBand(J.elb, J.wr, 0.06, 0.74, R.fa1, R.fa2, { prof: FOREARM_PROF }); s += mGlow(d) + mPaint(d, J.elb, along(J.elb, J.wr, 0.74), R.fa1); }
  if (has("delt")) { const d = mBand(J.sho, J.elb, 0, 0.42, R.ua1, R.ua2, { ins: 0.4, prof: DELT_PROF, b0: 0.95, b1: 0.7 }); s += mGlow(d) + mPaint(d, J.sho, along(J.sho, J.elb, 0.42), R.ua1); }
  s += dot(J.wr, R.hand, C.bodyShade);
  if (props.front) s += props.front(J, P);
  return s;
}

// ---- sade çizgi tarzı (yan görünüm) ----
function drawSideStick(J, hl, props, P) {
  const has = (...k) => k.some((x) => hl.includes(x));
  const B = "#e8eaf0", F = "#6b7385", G = C.hl, GF = "#2f7a4c";
  let s = props.back ? props.back(J, P) : "";
  const thG = has("th", "quads", "hams"), uaG = has("biceps", "triceps"), faG = has("forearm"), shG = has("calf");
  s += seg(J.hip, J.knee2, 5.5, thG ? GF : F) + seg(J.knee2, J.ank2, 5, shG ? GF : F) + seg(J.ank2, J.toe2, 3.5, F);
  s += seg(J.sho, J.elb2, 4.5, uaG ? GF : F) + seg(J.elb2, J.wr2, 4, faG ? GF : F);
  const m = mid(J.hip, J.sho);
  s += seg(J.hip, m, 8, has("abs", "back") ? G : B) + seg(m, J.sho, 8, has("chest", "back") ? G : B);
  if (has("glute")) s += dot(step(J.hip, (P.t ?? 0) - 90, 1.5), 4.6, G);
  s += dot(J.head, RIG.head, B);
  s += seg(J.hip, J.knee, 6, thG ? G : B) + seg(J.knee, J.ank, 5.5, shG ? G : B) + seg(J.ank, J.toe, 4, B);
  if (has("delt", "traps")) s += dot(J.sho, 4.2, G);
  s += seg(J.sho, J.elb, 5, uaG ? G : B) + seg(J.elb, J.wr, 4.5, faG ? G : B);
  if (props.front) s += props.front(J, P);
  return s;
}

// ---- figürü çiz (ön görünüm) ----
// Renk tonu: k > 0 beyaza, k < 0 siyaha doğru karıştırır (#rrggbb)
function tint(hex, k) {
  if (!/^#[0-9a-f]{6}$/i.test(hex)) return hex;
  const n = parseInt(hex.slice(1), 16), to = k > 0 ? 255 : 0, a = Math.abs(k);
  const ch = (v) => Math.round(v + (to - v) * a);
  return "#" + [n >> 16, (n >> 8) & 255, n & 255].map((v) => ch(v).toString(16).padStart(2, "0")).join("");
}
let GRAD_SEQ = 0;

function drawFront(J, hl, props, P) {
  const baseHl = C.hl, baseGlow = C.hlGlow;
  // kas açık mı? Açıksa o kasın rengini (varsa P.hlColors) etkinleştirir
  const has = (k) => {
    if (!hl.includes(k)) return false;
    const c = P.hlColors && P.hlColors[k];
    C.hl = c || baseHl; C.hlGlow = P.noGlow ? "transparent" : c ? c + "50" : baseGlow;
    return true;
  };
  // uzva oturan kas: a -> b uzvunun t1..t2 arası (yarıçap uzvun kendisinden)
  const limb = (a, b, t1, t2, r1, r2, o) => { const d = mBand(a, b, t1, t2, r1, r2, o); return mGlow(d) + mPaint(d, along(a, b, t1), along(a, b, t2), (r1 + r2) / 2); };
  // serbest şekil (eksen a -> b, yarı genişlik w)
  const shape = (d, a, b, w) => mGlow(d) + mPaint(d, a, b, w);
  const Q = (x, y) => `${f1(x)} ${f1(y)}`;
  // köşeleri yuvarlatılmış dörtgen yol (karın kasları)
  const rr = (x, y, w, h, r) => `M${Q(x + r, y)}H${f1(x + w - r)}Q${Q(x + w, y)} ${Q(x + w, y + r)}V${f1(y + h - r)}Q${Q(x + w, y + h)} ${Q(x + w - r, y + h)}H${f1(x + r)}Q${Q(x, y + h)} ${Q(x, y + h - r)}V${f1(y + r)}Q${Q(x, y)} ${Q(x + r, y)}Z`;

  const sy = J.shoL[1], py = J.pel[1];
  let s = `<ellipse cx="60" cy="${FLOOR + 3.6}" rx="24" ry="2.6" fill="rgba(0,0,0,.32)"/>`;
  if (props.back) s += props.back(J, P);
  // bacaklar
  for (const [hip, knee, ank, sgn] of [[J.hipL, J.kneeL, J.ankL, -1], [J.hipR, J.kneeR, J.ankR, 1]]) {
    s += cap(hip, knee, R.th1, R.th2, C.body);
    if (has("th")) s += limb(hip, knee, 0.4, 0.95, R.th1, R.th2, { prof: THIGH_PROF, b1: 0.6 });
    if (has("adductors")) s += limb(hip, knee, 0.4, 0.84, R.th1, R.th2, { lo: Math.min(sgn * 0.12, sgn), hi: Math.max(sgn * 0.12, sgn), prof: (u) => 1 - 0.75 * Math.pow(u, 1.4), b1: 0.9 });
    s += cap(hip, along(hip, knee, 0.46), R.th1 + 0.5, R.th1 * 0.95, C.shorts);
    s += cap(knee, ank, R.sh1, R.sh2, C.body) + cap(along(knee, ank, 0.12), along(knee, ank, 0.55), R.sh1 + 0.5, R.sh1 - 0.3, C.body);
    if (has("calves")) s += limb(along(knee, ank, 0.12), along(knee, ank, 0.55), -0.05, 1.25, R.sh1 + 0.5, R.sh1 - 0.3, { prof: CALF_PROF, b0: 0.35, b1: 0.6 });
    s += `<ellipse cx="${f1(ank[0] + sgn * 1.1)}" cy="${f1(ank[1] + 1.7)}" rx="3.7" ry="2.3" fill="${C.shoe}"/>` + rect(ank[0] + sgn * 1.1 - 3.6, ank[1] + 3.1, 7.2, 1, C.sole, 0.5);
  }
  s += cap(J.hipL, J.hipR, 5, 5, C.shorts);
  if (P.tiltDeg) s += `<g transform="rotate(${f1(P.tiltDeg)} 60 ${f1(py)})">`;
  // gövde: omuzda geniş, belde dar
  const torsoD = `M${f1(J.shoL[0] - 2.2)} ${f1(sy - 2.2)} Q60 ${f1(sy - 4.2)} ${f1(J.shoR[0] + 2.2)} ${f1(sy - 2.2)} Q${f1(J.shoR[0] + 3)} ${f1(sy + 6)} 66.5 ${f1(py - 9)} L67.5 ${f1(py + 1)} L52.5 ${f1(py + 1)} L53.5 ${f1(py - 9)} Q${f1(J.shoL[0] - 3)} ${f1(sy + 6)} ${f1(J.shoL[0] - 2.2)} ${f1(sy - 2.2)}Z`;
  s += `<path d="${torsoD}" fill="${C.body}"${STY.stroke ? ` stroke="${STY.stroke}" stroke-width="${STY.strokeW || 0.9}"` : ""}/>`;
  const inTorso = (body) => mClip(`<path d="${torsoD}"/>`, body);
  // kalça: şortun içine oturan iki yarım, altta kalça kıvrımı
  if (has("glutes") || has("glute")) {
    const clip = cap(J.hipL, J.hipR, 5, 5, "#000") + cap(J.hipL, along(J.hipL, J.kneeL, 0.46), R.th1 + 0.5, R.th1 * 0.95, "#000") + cap(J.hipR, along(J.hipR, J.kneeR, 0.46), R.th1 + 0.5, R.th1 * 0.95, "#000");
    let g = "";
    for (const sgn of [-1, 1]) g += shape(`M${Q(60 + sgn * 0.45, py + 0.9)}L${Q(60 + sgn * 13, py + 0.9)}L${Q(60 + sgn * 13, py + 8.6)}Q${Q(60 + sgn * 5.5, py + 10.4)} ${Q(60 + sgn * 0.45, py + 7.6)}Z`, [60 + sgn * 2, py + 1], [60 + sgn * 5.5, py + 9], 5);
    s += mClip(clip, g);
  }
  if (STY.shirt) s += `<path d="M${f1(J.shoL[0] + 1.2)} ${f1(sy - 2.6)} Q60 ${f1(sy + 3.2)} ${f1(J.shoR[0] - 1.2)} ${f1(sy - 2.6)} Q${f1(J.shoR[0] + 1.4)} ${f1(sy + 7)} 66.5 ${f1(py - 9)} L67.5 ${f1(py + 1)} L52.5 ${f1(py + 1)} L53.5 ${f1(py - 9)} Q${f1(J.shoL[0] - 1.4)} ${f1(sy + 7)} ${f1(J.shoL[0] + 1.2)} ${f1(sy - 2.6)}Z" fill="${STY.shirt}"/>`;
  s += cap([60, sy - 2], [60, sy - 6.5], 2.6, 2.4, C.bodyShade);
  // trapez: boyundan omuzlara inen yumuşak üçgen
  if (has("traps")) s += shape(`M60 ${f1(sy - 7.4)} Q${f1(63.5)} ${f1(sy - 4.4)} ${f1(J.shoR[0] - 0.6)} ${f1(sy - 2.9)} Q60 ${f1(sy - 0.6)} ${f1(J.shoL[0] + 0.6)} ${f1(sy - 2.9)} Q${f1(56.5)} ${f1(sy - 4.4)} 60 ${f1(sy - 7.4)}Z`, [60, sy - 7.4], [60, sy - 1], 5);
  // gövde kasları gövde şekliyle kırpılır: dış kenarları vücut hattını birebir izler
  let tm = "";
  // göğüs: göğüs kemiğinden koltuk altına, altta kavisli sınır
  if (has("chest")) for (const sgn of [-1, 1]) tm += shape(`M${Q(60 + sgn * 0.5, sy + 0.4)}Q${Q(60 + sgn * 5, sy - 0.8)} ${Q(60 + sgn * 13, sy - 0.6)}L${Q(60 + sgn * 13, sy + 5.2)}Q${Q(60 + sgn * 8.6, sy + 9.8)} ${Q(60 + sgn * 4.4, sy + 9.3)}Q${Q(60 + sgn * 0.6, sy + 9)} ${Q(60 + sgn * 0.5, sy + 6.5)}Z`,
    [60 + sgn * 1.5, sy + 1], [60 + sgn * 7.5, sy + 8.5], 4.5);
  // orta sırt: kürek kemikleri arası, aşağı doğru daralan
  if (has("back")) tm += shape(`M${Q(60, sy - 1)}Q${Q(66, sy - 0.6)} ${Q(73, sy + 2.2)}L${Q(73, sy + 5)}Q${Q(66.6, sy + 8.6)} ${Q(64, sy + 13.4)}Q${Q(60, sy + 16)} ${Q(56, sy + 13.4)}Q${Q(53.4, sy + 8.6)} ${Q(47, sy + 5)}L${Q(47, sy + 2.2)}Q${Q(54, sy - 0.6)} ${Q(60, sy - 1)}Z`,
    [60, sy - 1], [60, sy + 15], 7);
  // kanat: koltuk altından bele inen, gövde kenarını izleyen geniş kas
  if (has("lats")) for (const sgn of [-1, 1]) tm += shape(`M${Q(60 + sgn * 5.4, sy + 5.2)}Q${Q(60 + sgn * 9, sy + 3.4)} ${Q(60 + sgn * 13, sy + 3.2)}L${Q(60 + sgn * 13, py - 11)}Q${Q(60 + sgn * 8.4, py - 6.2)} ${Q(60 + sgn * 4.4, py - 7)}Q${Q(60 + sgn * 3.5, sy + 11.5)} ${Q(60 + sgn * 5.4, sy + 5.2)}Z`,
    [60 + sgn * 9, sy + 4], [60 + sgn * 5, py - 7], 4);
  // yan karın: gövde kenarı boyunca
  if (has("abs")) for (const sgn of [-1, 1]) tm += shape(`M${Q(60 + sgn * 3.9, sy + 10.8)}L${Q(60 + sgn * 13, sy + 9.6)}L${Q(60 + sgn * 13, py + 2)}L${Q(60 + sgn * 5.6, py - 0.8)}Q${Q(60 + sgn * 3.7, py - 6)} ${Q(60 + sgn * 3.9, sy + 10.8)}Z`,
    [60 + sgn * 6, sy + 10], [60 + sgn * 5, py], 2.5);
  // bel: omurganın iki yanında uzun kaslar
  if (has("lower_back")) for (const sgn of [-1, 1]) tm += shape(`M${Q(60 + sgn * 0.6, py - 15)}Q${Q(60 + sgn * 3.7, py - 15.6)} ${Q(60 + sgn * 4, py - 11)}L${Q(60 + sgn * 3.6, py - 0.6)}L${Q(60 + sgn * 0.6, py - 0.6)}Z`,
    [60 + sgn * 2.2, py - 15], [60 + sgn * 2.2, py - 1], 1.7);
  if (tm) s += inTorso(tm);
  // karın: 2 x 3 kas, köşeleri hafif yuvarlak
  if (has("absc")) {
    const top = sy + 11.2, bot = py - 2.2, gap = 0.75, h = (bot - top - gap * 2) / 3;
    for (let i = 0; i < 3; i++) for (const sgn of [-1, 1]) {
      const y = top + i * (h + gap), w = 3.1 - (i === 0 ? 0.25 : 0), x = sgn > 0 ? 60.45 : 59.55 - w;
      s += shape(rr(x, y, w, h, Math.min(1, h / 3)), [x + w / 2, y], [x + w / 2, y + h], w / 2);
    }
  }
  // baş
  s += P.backView ? dot([60, J.head[1] - 0.4], RIG.head + 0.2, C.hair) + cap([60, J.head[1] + 4.6], [60, J.head[1] + 6.2], 2.4, 2.4, C.bodyShade)
    : dot([60, J.head[1] - 1.1], RIG.head, C.hair) + dot([60, J.head[1] + 0.9], RIG.head - 0.6, C.body);
  // kollar
  for (const [sho, elb, wr, sgn] of [[J.shoL, J.elbL, J.wrL, -1], [J.shoR, J.elbR, J.wrR, 1]]) {
    s += cap(sho, elb, R.ua1, R.ua2, C.body);
    if (has("biceps")) s += limb(sho, elb, 0.28, 0.92, R.ua1, R.ua2, { e: 0.45 });
    s += cap(elb, wr, R.fa1, R.fa2, C.body);
    if (has("forearm")) s += limb(elb, wr, 0.04, 0.74, R.fa1, R.fa2, { prof: FOREARM_PROF });
    if (has("side_delt")) s += limb(sho, elb, 0, 0.4, R.ua1, R.ua2, { lo: Math.min(-sgn * 0.1, -sgn), hi: Math.max(-sgn * 0.1, -sgn), ins: 0.4, prof: DELT_PROF, b0: 0.85, b1: 0.7 });
    if (has("delt")) s += limb(sho, elb, 0, 0.42, R.ua1, R.ua2, { ins: 0.4, prof: DELT_PROF, b0: 0.95, b1: 0.7 });
    s += dot(wr, R.hand, C.bodyShade);
  }
  if (props.front) s += props.front(J, P);
  if (P.tiltDeg) s += "</g>";
  C.hl = baseHl; C.hlGlow = baseGlow;
  return s;
}

// Kalıptaki genel vurguyu doğru kasa çevirir (ör. "ua": curl'de biceps, pushdown'da triceps)
const HL_HAMS = new Set(["rdl", "good_morning", "sl_rdl", "leg_curl", "leg_curl_seated", "nordic", "pull_through", "standing_leg_curl", "rdl_smith", "kb_swing"]);
const HL_QUADS = new Set(["squat_bar", "squat_front", "squat_bw", "wall_sit", "hack_squat", "leg_press", "lunge", "bulgarian", "step_up", "leg_ext", "thruster", "belt_squat", "squat_smith", "lunge_smith", "burpee"]);
const HL_TRI = new Set(["bench", "bench_db", "bench_smith", "decline", "decline_machine", "chest_press", "cable_press", "pushup", "pike_pushup", "dips", "dips_assist", "bench_dips", "ohp", "ohp_smith", "press_seated",
  "pushdown", "kickback", "kickback_cable", "oh_ext", "oh_ext_cable", "skull", "tri_machine", "seated_dip"]);
function normHL(key, hl, front) {
  const out = [];
  for (const h of hl) {
    if (h === "torso") out.push("back");
    else if (h === "sh") out.push("calf");
    else if (h === "fa") out.push("forearm");
    else if (h === "ua") out.push(!front && HL_TRI.has(key) ? "triceps" : "biceps");
    else if (h === "th") out.push(front ? "th" : HL_HAMS.has(key) ? "hams" : HL_QUADS.has(key) ? "quads" : "th");
    else out.push(h);
  }
  return out;
}

// ---- kalıplar ----
const STAND = { ank: [60, FLOOR], su: 0, tu: 0, t: 0, ft: 90 };
const LIE = { hip: [60, 68], t: -90, th: 115, sh: 172, ft: 90 }; // sehpada sırtüstü (baş solda)
const bench = () => leg2(20, 77) + leg2(66, 77) + cushion([16, 74], [74, 74], 6);
const seat = (x, y, w = 24) => leg2(x + w / 2 - 1.6, y + 5) + cushion([x + 2, y + 2.5], [x + w - 2, y + 2.5], 5.5);
const backPad = (x1, y1, x2, y2) => cushion([x1, y1], [x2, y2], 6);
const atWrist = (fn) => (J) => fn(J.wr);
const both = (fn) => (J) => fn(J.wr2) + fn(J.wr);

const PATTERNS = {
  squat_bar: { hl: ["th", "glute"], A: { ...STAND, ua: 205, fa: 25 }, B: { ...STAND, su: 30, tu: -78, t: 42, ua: 250, fa: 70 },
    props: { front: (J, P) => plate(step(step(J.sho, P.t + 180, 4), P.t - 90, 3), 7) } },
  squat_front: { hl: ["th", "glute"], A: { ...STAND, ua: 150, fa: 10 }, B: { ...STAND, su: 30, tu: -78, t: 30, ua: 175, fa: 40 },
    props: { front: atWrist((p) => dumbbell(p)) } },
  squat_bw: { hl: ["th", "glute"], A: { ...STAND, ua: 178, fa: 178 }, B: { ...STAND, su: 30, tu: -78, t: 35, ua: 92, fa: 92 }, props: {} },
  wall_sit: { hl: ["th"], A: { ank: [72, FLOOR], su: 0, tu: -88, t: -3, ua: 180, fa: 180 }, B: { ank: [72, FLOOR], su: 0, tu: -86, t: -2, ua: 180, fa: 180 },
    props: { back: () => rect(46, 10, 4, 86, C.dark, 1) } },
  hack_squat: { hl: ["th"], A: { ank: [70, FLOOR], su: -12, tu: -12, t: -12, ua: 190, fa: 160 }, B: { ank: [70, FLOOR], su: 28, tu: -70, t: -6, ua: 200, fa: 150 },
    props: { back: (J) => backPad(J.hip[0] - 6, J.hip[1] + 6, J.sho[0] - 6, J.sho[1] - 4) } },
  leg_press: { hl: ["th", "glute"], A: { hip: [44, 70], t: -58, th: 30, sh: 150, ua: 150, fa: 100, ft: 60 }, B: { hip: [44, 70], t: -58, th: 68, sh: 78, ua: 150, fa: 100, ft: 340 },
    props: { back: (J) => backPad(36, 76, 16, 50) + seg([J.ank[0] + 3, J.ank[1] - 12], [J.ank[0] + 9, J.ank[1] + 10], 5, C.prop) } },
  lunge: { hl: ["th", "glute"], A: { hip: [58, 52], t: 0, th: 178, sh: 180, th2: 182, sh2: 180, ft2: 90, ua: 180, fa: 180 },
    B: { hip: [58, 66], t: 2, th: 100, sh: 180, th2: 205, sh2: 262, ft2: 160, ua: 180, fa: 180 }, props: { front: both(dumbbell) } },
  bulgarian: { hl: ["th", "glute"], A: { hip: [60, 54], t: 4, th: 172, sh: 182, th2: 205, sh2: 290, ft2: 250, ua: 180, fa: 180 },
    B: { hip: [58, 66], t: 8, th: 105, sh: 182, th2: 225, sh2: 275, ft2: 250, ua: 180, fa: 180 }, props: { back: () => rect(4, 62, 26, 5, C.dark) + rect(14, 67, 4, 29, C.dark, 1), front: both(dumbbell) } },
  step_up: { hl: ["th", "glute"], A: { hip: [50, 52], t: 4, th: 115, sh: 185, th2: 182, sh2: 178, ft2: 90, ua: 180, fa: 180 },
    B: { hip: [70, 40], t: 2, th: 178, sh: 182, th2: 200, sh2: 182, ft2: 90, ua: 180, fa: 180 }, props: { back: () => rect(58, 80, 36, 16, C.dark), front: both(dumbbell) } },
  deadlift: { hl: ["torso", "glute", "th"], A: { ank: [60, FLOOR], su: 14, tu: -52, t: 68, ua: 180, fa: 180 }, B: { ...STAND, ua: 180, fa: 180 },
    props: { front: atWrist((p) => plate(p, 9)) } },
  rdl: { hl: ["th", "glute"], A: { ...STAND, ua: 180, fa: 180 }, B: { ank: [60, FLOOR], su: 6, tu: -30, t: 78, ua: 180, fa: 180 }, props: { front: atWrist((p) => plate(p, 8)) } },
  good_morning: { hl: ["torso", "th"], A: { ...STAND, ua: 205, fa: 25 }, B: { ank: [60, FLOOR], su: 6, tu: -25, t: 82, ua: 290, fa: 110 },
    props: { front: (J, P) => plate(step(step(J.sho, P.t + 180, 4), P.t - 90, 3), 7) } },
  sl_rdl: { hl: ["th", "glute"], A: { ...STAND, th2: 180, sh2: 180, ft2: 90, ua: 180, fa: 180 }, B: { ank: [60, FLOOR], su: 6, tu: -15, t: 82, th2: 262, sh2: 262, ft2: 180, ua: 180, fa: 180 }, props: {} },
  kb_swing: { hl: ["glute", "th"], A: { ank: [60, FLOOR], su: 10, tu: -35, t: 62, ua: 205, fa: 210 }, B: { ...STAND, ua: 88, fa: 88 }, props: { front: atWrist(kettle) } },
  clean: { hl: ["torso", "th", "traps"], A: { ank: [60, FLOOR], su: 14, tu: -52, t: 68, ua: 180, fa: 180 }, B: { ank: [60, FLOOR], su: 10, tu: -12, t: 2, ua: 118, fa: 318 },
    props: { front: atWrist((p) => plate(p, 7)) } },
  hip_thrust: { hl: ["glute"], A: { ank: [82, FLOOR], su: -22, tu: -112, t: -62, ua: 120, fa: 90 }, B: { ank: [82, FLOOR], su: -22, tu: -80, t: -88, ua: 110, fa: 95 },
    props: { back: () => rect(4, 66, 26, 6, C.dark) + rect(12, 72, 4, 24, C.dark, 1), front: (J) => plate([J.hip[0] + 2, J.hip[1] - 7], 7) } },
  glute_bridge: { hl: ["glute"], A: { ank: [82, FLOOR], su: -22, tu: -102, t: -112, ua: 200, fa: 200 }, B: { ank: [82, FLOOR], su: -22, tu: -74, t: -124, ua: 210, fa: 210 }, props: {} },
  calf: { hl: ["sh"], A: { ...STAND, ua: 180, fa: 180 }, B: { ...STAND, ank: [60, FLOOR - 5], ft: 135, ua: 180, fa: 180 }, props: { back: () => rect(50, FLOOR - 1, 30, 4, C.dark, 1), front: both(dumbbell) } },
  calf_seated: { hl: ["sh"], A: { hip: [50, 68], t: 0, th: 90, sh: 180, ft: 90, ua: 160, fa: 100 }, B: { hip: [50, 64], t: 0, th: 98, sh: 178, ft: 130, ua: 160, fa: 100 },
    props: { back: () => seat(36, 72), front: (J) => rect(J.knee[0] - 6, J.knee[1] - 7, 14, 4, C.prop, 1) } },
  leg_ext: { hl: ["th"], A: { hip: [48, 62], t: -8, th: 90, sh: 180, ft: 90, ua: 170, fa: 150 }, B: { hip: [48, 62], t: -8, th: 90, sh: 96, ft: 10, ua: 170, fa: 150 },
    props: { back: () => seat(34, 66) + backPad(38, 64, 34, 32), front: (J) => dot(J.ank, 3.5, C.prop) } },
  leg_curl_seated: { hl: ["th"], A: { hip: [48, 62], t: -8, th: 90, sh: 100, ft: 10, ua: 170, fa: 150 }, B: { hip: [48, 62], t: -8, th: 90, sh: 195, ft: 100, ua: 170, fa: 150 },
    props: { back: () => seat(34, 66) + backPad(38, 64, 34, 32), front: (J) => dot(step(J.ank, 0, 3), 3.5, C.prop) } },
  leg_curl: { hl: ["th"], A: { hip: [58, 68], t: -90, th: 90, sh: 90, ft: 180, ua: -125, fa: -60 }, B: { hip: [58, 68], t: -90, th: 90, sh: 15, ft: 300, ua: -125, fa: -60 },
    props: { back: () => rect(16, 72, 62, 5, C.dark) + rect(40, 77, 4, 19, C.dark, 1), front: (J) => dot(step(J.ank, J.ank[1] < 60 ? 270 : 0, 3), 3.5, C.prop) } },
  nordic: { hl: ["th"], A: { knee: [52, 88], tu: 0, sh: 270, ft: 180, t: 0, ua: 165, fa: 150 }, B: { knee: [52, 88], tu: 52, sh: 270, ft: 180, t: 52, ua: 120, fa: 110 },
    props: { back: () => rect(20, 84, 14, 4, C.prop, 1) } },
  bench: { hl: ["chest", "ua"], A: { ...LIE, ua: 172, fa: 12 }, B: { ...LIE, ua: 2, fa: 0 }, props: { back: bench, front: atWrist((p) => plate(p, 8)) } },
  bench_db: { hl: ["chest", "ua"], A: { ...LIE, ua: 172, fa: 12 }, B: { ...LIE, ua: 2, fa: 0 }, props: { back: bench, front: atWrist(dumbbell) } },
  incline: { hl: ["chest", "delt"], A: { hip: [56, 66], t: -45, th: 100, sh: 178, ft: 90, ua: 135, fa: 40 }, B: { hip: [56, 66], t: -45, th: 100, sh: 178, ft: 90, ua: 30, fa: 30 },
    props: { back: () => backPad(56, 72, 30, 44) + seat(44, 70, 22), front: atWrist(dumbbell) } },
  decline: { hl: ["chest", "ua"], A: { hip: [62, 62], t: -100, th: 125, sh: 175, ft: 90, ua: 168, fa: 15 }, B: { hip: [62, 62], t: -100, th: 125, sh: 175, ft: 90, ua: 355, fa: 355 },
    props: { back: () => seg([16, 72], [70, 64], 5, C.dark) + rect(40, 70, 4, 26, C.dark, 1), front: atWrist((p) => plate(p, 8)) } },
  chest_press: { hl: ["chest", "ua"], A: { hip: [46, 66], t: -4, th: 90, sh: 180, ft: 90, ua: 160, fa: 75 }, B: { hip: [46, 66], t: -4, th: 90, sh: 180, ft: 90, ua: 92, fa: 90 },
    props: { back: () => seat(32, 70) + backPad(38, 68, 36, 34), front: atWrist((p) => rect(p[0] - 1.5, p[1] - 6, 3, 12, C.plate, 1)) } },
  crossover: { hl: ["chest"], view: "front", A: { a: 125, b: 115 }, B: { a: 20, b: -8 },
    props: { back: () => dot([10, 6], 2.5, C.dark) + dot([110, 6], 2.5, C.dark), front: (J) => seg([10, 6], J.wrL, 1.1, C.prop) + seg([110, 6], J.wrR, 1.1, C.prop) } },
  rev_fly: { hl: ["delt"], view: "front", A: { a: 15, b: -10 }, B: { a: 92, b: 92 }, props: { front: (J) => dumbbell(J.wrL) + dumbbell(J.wrR) } },
  pushup: { hl: ["chest", "ua"], A: { hip: [64, 73], t: -74, th: 106, sh: 106, ft: 180, ua: 180, fa: 180 }, B: { hip: [64, 81], t: -82, th: 99, sh: 99, ft: 180, ua: 140, fa: 226 }, props: {} },
  pike_pushup: { hl: ["delt", "ua"], A: { hip: [62, 46], t: -150, th: 150, sh: 150, ft: 180, ua: 215, fa: 215 }, B: { hip: [62, 50], t: -142, th: 146, sh: 146, ft: 180, ua: 160, fa: 230 }, props: {} },
  mountain: { hl: ["abs", "th"], A: { hip: [64, 73], t: -74, th: 106, sh: 106, th2: 106, sh2: 106, ft: 180, ua: 180, fa: 180 }, B: { hip: [64, 71], t: -72, th: 235, sh: 115, th2: 106, sh2: 106, ft: 180, ua: 180, fa: 180 }, props: {} },
  burpee: { hl: ["th", "chest"], A: { ank: [60, FLOOR], su: 34, tu: -85, t: 55, ua: 170, fa: 175 }, B: { ank: [60, FLOOR - 6], su: 0, tu: 0, t: 0, ua: 5, fa: 5, ft: 150 }, props: {} },
  dips: { hl: ["chest", "ua"], A: { hip: [60, 48], t: 4, th: 170, sh: 250, ft: 180, ua: 180, fa: 180 }, B: { hip: [62, 58], t: 18, th: 166, sh: 250, ft: 180, ua: 212, fa: 162 },
    props: { back: () => seg([46, 50], [82, 50], 3, C.prop) + rect(76, 50, 3, 46, C.dark, 1) } },
  bench_dips: { hl: ["ua"], A: { hip: [56, 62], t: 0, th: 100, sh: 100, ft: 10, ua: 200, fa: 180 }, B: { hip: [56, 74], t: 6, th: 100, sh: 100, ft: 10, ua: 238, fa: 128 },
    props: { back: () => rect(24, 74, 28, 5, C.dark) + rect(36, 79, 4, 17, C.dark, 1) } },
  pullup: { hl: ["torso", "ua"], A: { hip: [60, 52], t: 0, th: 175, sh: 245, ft: 180, ua: 0, fa: 0 }, B: { hip: [60, 32], t: 0, th: 175, sh: 245, ft: 180, ua: 110, fa: 318 },
    props: { back: () => seg([38, -3], [82, -3], 3, C.prop) } },
  leg_raise: { hl: ["abs"], A: { hip: [60, 52], t: 0, th: 180, sh: 180, ft: 90, ua: 0, fa: 0 }, B: { hip: [60, 52], t: -6, th: 85, sh: 85, ft: 0, ua: 0, fa: 0 },
    props: { back: () => seg([38, -3], [82, -3], 3, C.prop) } },
  pulldown: { hl: ["torso", "ua"], A: { hip: [56, 70], t: -10, th: 88, sh: 180, ft: 90, ua: 8, fa: 4 }, B: { hip: [56, 70], t: -16, th: 88, sh: 180, ft: 90, ua: 150, fa: 352 },
    props: { back: () => seat(42, 74), front: (J) => seg([60, -6], J.wr, 1.2, C.prop) + seg([J.wr[0] - 10, J.wr[1]], [J.wr[0] + 10, J.wr[1]], 2.5, C.plate) + rect(J.knee[0] - 4, J.knee[1] - 7, 8, 4, C.prop, 1) } },
  straight_pulldown: { hl: ["torso"], A: { ank: [60, FLOOR], su: 5, tu: -8, t: 22, ua: 70, fa: 70 }, B: { ank: [60, FLOOR], su: 5, tu: -8, t: 22, ua: 172, fa: 172 },
    props: { front: (J) => cable(J.wr, [104, 2]) } },
  row_bent: { hl: ["torso", "ua"], A: { ank: [60, FLOOR], su: 10, tu: -25, t: 62, ua: 180, fa: 180 }, B: { ank: [60, FLOOR], su: 10, tu: -25, t: 62, ua: 252, fa: 178 },
    props: { front: atWrist((p) => plate(p, 8)) } },
  row_db: { hl: ["torso", "ua"], A: { ank: [64, FLOOR], su: 8, tu: -12, t: 80, ua: 180, fa: 180, ua2: 175, fa2: 175 }, B: { ank: [64, FLOOR], su: 8, tu: -12, t: 80, ua: 262, fa: 182, ua2: 175, fa2: 175 },
    props: { back: () => rect(86, 72, 26, 5, C.dark) + rect(97, 77, 4, 19, C.dark, 1), front: atWrist(dumbbell) } },
  row_seated: { hl: ["torso", "ua"], A: { hip: [42, 74], t: 22, th: 80, sh: 102, ft: 10, ua: 96, fa: 96 }, B: { hip: [42, 74], t: -6, th: 80, sh: 102, ft: 10, ua: 205, fa: 92 },
    props: { back: () => rect(28, 78, 30, 5, C.dark) + seg([88, 62], [88, 84], 5, C.dark), front: (J) => cable(J.wr, [114, 70]) } },
  face_pull: { hl: ["delt", "torso"], A: { ...STAND, ua: 75, fa: 75 }, B: { ...STAND, ua: 268, fa: 40 }, props: { front: (J) => cable(J.wr, [114, 14]) } },
  inverted_row: { hl: ["torso", "ua"], A: { hip: [70, 81], t: -73, th: 106, sh: 106, ft: 180, ua: 2, fa: 0 }, B: { hip: [68, 71], t: -64, th: 114, sh: 114, ft: 180, ua: 62, fa: -48 },
    props: { back: () => seg([30, 46], [58, 46], 3, C.prop) + rect(32, 46, 3, 50, C.dark, 1) } },
  ohp: { hl: ["delt", "ua"], A: { ...STAND, ua: 158, fa: 2 }, B: { ...STAND, ua: 2, fa: 0 }, props: { front: atWrist((p) => plate(p, 8)) } },
  press_seated: { hl: ["delt", "ua"], A: { hip: [52, 66], t: 0, th: 90, sh: 180, ft: 90, ua: 158, fa: 2 }, B: { hip: [52, 66], t: 0, th: 90, sh: 180, ft: 90, ua: 2, fa: 0 },
    props: { back: () => seat(38, 70) + backPad(46, 68, 46, 34), front: atWrist(dumbbell) } },
  thruster: { hl: ["th", "delt"], A: { ...STAND, su: 30, tu: -78, t: 30, ua: 160, fa: 20 }, B: { ...STAND, ua: 2, fa: 0 }, props: { front: atWrist(kettle) } },
  front_raise: { hl: ["delt"], A: { ...STAND, ua: 176, fa: 176 }, B: { ...STAND, ua: 90, fa: 90 }, props: { front: atWrist(dumbbell) } },
  lateral: { hl: ["delt"], view: "front", A: { a: 12, b: 10 }, B: { a: 86, b: 88 }, props: { front: (J) => dumbbell(J.wrL) + dumbbell(J.wrR) } },
  lateral_cable: { hl: ["delt"], view: "front", A: { a: -12, b: -14 }, B: { a: 84, b: 86 }, props: { back: () => dot([104, FLOOR], 2.5, C.dark), front: (J) => seg([104, FLOOR], J.wrL, 1.1, C.prop) + rect(J.wrL[0] - 2, J.wrL[1] - 1.5, 4, 3, C.plate, 1) } },
  lateral_bag: { hl: ["delt"], view: "front", A: { a: 12, b: 10 }, B: { a: 86, b: 88 }, props: { front: (J) => bag(J.wrL) + bag(J.wrR) } },
  upright_row: { hl: ["delt", "traps"], view: "front", A: { a: 8, b: -14 }, B: { a: 100, b: -42 }, props: { front: (J) => seg(J.wrL, J.wrR, 2.5, C.prop) + plate([J.wrL[0] - 4, J.wrL[1]], 6) + plate([J.wrR[0] + 4, J.wrR[1]], 6) } },
  shrug: { hl: ["traps"], view: "front", A: { a: 4, b: 4, lift: 0 }, B: { a: 4, b: 4, lift: -5 }, props: { front: (J) => dumbbell(J.wrL) + dumbbell(J.wrR) } },
  shrug_bag: { hl: ["traps"], view: "front", A: { a: 4, b: 4, lift: 0 }, B: { a: 4, b: 4, lift: -5 }, props: { front: (J) => bag(J.wrL) + bag(J.wrR) } },
  abductor: { hl: ["th"], view: "front", A: { a: 30, b: 10, seated: true, g: 12, g2: 4, py: 64 }, B: { a: 30, b: 10, seated: true, g: 45, g2: 20, py: 64 },
    props: { back: () => rect(38, 64, 44, 6, C.dark) + rect(57, 70, 6, 26, C.dark, 1) } },
  adductor: { hl: ["th"], view: "front", A: { a: 30, b: 10, seated: true, g: 45, g2: 20, py: 64 }, B: { a: 30, b: 10, seated: true, g: 10, g2: 4, py: 64 },
    props: { back: () => rect(38, 64, 44, 6, C.dark) + rect(57, 70, 6, 26, C.dark, 1) } },
  rear_bent: { hl: ["delt"], A: { ank: [60, FLOOR], su: 8, tu: -25, t: 75, ua: 180, fa: 180 }, B: { ank: [60, FLOOR], su: 8, tu: -25, t: 75, ua: 255, fa: 260 }, props: { front: atWrist(dumbbell) } },
  prone_yt: { hl: ["delt", "torso"], A: { hip: [64, 86], t: -90, th: 90, sh: 90, ft: 180, ua: 180, fa: 180 }, B: { hip: [64, 86], t: -84, th: 92, sh: 92, ft: 180, ua: -60, fa: -60 }, props: {} },
  curl: { hl: ["ua"], A: { ...STAND, ua: 170, fa: 172 }, B: { ...STAND, ua: 168, fa: 18 }, props: { front: atWrist(dumbbell) } },
  curl_bar: { hl: ["ua"], A: { ...STAND, ua: 170, fa: 172 }, B: { ...STAND, ua: 168, fa: 18 }, props: { front: atWrist((p) => plate(p, 7)) } },
  curl_bag: { hl: ["ua"], A: { ...STAND, ua: 170, fa: 172 }, B: { ...STAND, ua: 168, fa: 18 }, props: { front: atWrist(bag) } },
  curl_cable: { hl: ["ua"], A: { ...STAND, ua: 170, fa: 160 }, B: { ...STAND, ua: 168, fa: 18 }, props: { front: (J) => cable(J.wr, [86, FLOOR]) } },
  preacher: { hl: ["ua"], A: { hip: [48, 66], t: 12, th: 90, sh: 180, ft: 90, ua: 135, fa: 140 }, B: { hip: [48, 66], t: 12, th: 90, sh: 180, ft: 90, ua: 135, fa: 10 },
    props: { back: () => seat(34, 70) + seg([58, 40], [74, 58], 6, C.dark), front: atWrist((p) => plate(p, 7)) } },
  curl_incline: { hl: ["ua"], A: { hip: [58, 66], t: -35, th: 100, sh: 178, ft: 90, ua: 190, fa: 190 }, B: { hip: [58, 66], t: -35, th: 100, sh: 178, ft: 90, ua: 190, fa: 25 },
    props: { back: () => backPad(56, 72, 40, 42) + seat(46, 70, 22), front: atWrist(dumbbell) } },
  concentration: { hl: ["ua"], A: { hip: [50, 66], t: 38, th: 90, sh: 180, ft: 90, ua: 172, fa: 172 }, B: { hip: [50, 66], t: 38, th: 90, sh: 180, ft: 90, ua: 172, fa: 32 },
    props: { back: () => seat(36, 70), front: atWrist(dumbbell) } },
  wrist_curl: { hl: ["fa"], A: { hip: [50, 66], t: 30, th: 90, sh: 180, ft: 90, ua: 150, fa: 100 }, B: { hip: [50, 66], t: 30, th: 90, sh: 180, ft: 90, ua: 150, fa: 78 },
    props: { back: () => seat(36, 70), front: atWrist(dumbbell) } },
  pushdown: { hl: ["ua"], A: { ...STAND, t: 8, ua: 175, fa: 40 }, B: { ...STAND, t: 8, ua: 176, fa: 176 }, props: { front: (J) => cable(J.wr, [78, -6]) } },
  kickback: { hl: ["ua"], A: { ank: [60, FLOOR], su: 10, tu: -25, t: 68, ua: 250, fa: 180 }, B: { ank: [60, FLOOR], su: 10, tu: -25, t: 68, ua: 250, fa: 252 }, props: { front: atWrist(dumbbell) } },
  oh_ext: { hl: ["ua"], A: { ...STAND, ua: 350, fa: 200 }, B: { ...STAND, ua: 352, fa: 355 }, props: { front: atWrist(dumbbell) } },
  skull: { hl: ["ua"], A: { ...LIE, ua: 345, fa: 255 }, B: { ...LIE, ua: 350, fa: 355 }, props: { back: bench, front: atWrist((p) => plate(p, 7)) } },
  crunch: { hl: ["abs"], A: { hip: [62, 87], t: -90, th: 45, sh: 160, ft: 90, ua: -55, fa: 70 }, B: { hip: [62, 87], t: -58, th: 45, sh: 160, ft: 90, ua: -25, fa: 100 }, props: {} },
  crunch_cable: { hl: ["abs"], A: { knee: [62, 90], tu: 0, sh: 270, ft: 180, t: 5, ua: 20, fa: 200 }, B: { knee: [62, 90], tu: 5, sh: 270, ft: 180, t: 100, ua: 115, fa: 290 },
    props: { front: (J) => cable(J.wr, [74, -6]) } },
  bicycle: { hl: ["abs"], A: { hip: [64, 86], t: -62, th: 40, sh: 110, th2: 90, sh2: 92, ft: 90, ua: -40, fa: 80 }, B: { hip: [64, 86], t: -62, th: 90, sh: 92, th2: 40, sh2: 110, ft: 90, ua: -20, fa: 110 }, props: {} },
  twist: { hl: ["abs"], A: { hip: [56, 86], t: -28, th: 55, sh: 105, ft: 90, ua: 130, fa: 75 }, B: { hip: [56, 86], t: -28, th: 55, sh: 105, ft: 90, ua: 175, fa: 120 }, props: { front: atWrist((p) => plate(p, 5)) } },
  plank: { hl: ["abs"], A: { hip: [62, 79], t: -82, th: 98, sh: 98, ft: 180, ua: 180, fa: 270 }, B: { hip: [62, 77.5], t: -81, th: 98, sh: 98, ft: 180, ua: 180, fa: 270 }, props: {} },
  ab_wheel: { hl: ["abs"], A: { knee: [86, 89], tu: -15, sh: 90, ft: 180, t: -55, ua: 190, fa: 190 }, B: { knee: [86, 89], tu: -55, sh: 90, ft: 180, t: -82, ua: -88, fa: -88 },
    props: { front: (J) => dot([J.wr[0], Math.max(J.wr[1] + 4, FLOOR - 2)], 5, "none", `stroke="${C.plate}" stroke-width="2.5"`) } },
  hyperext: { hl: ["torso", "glute"], A: { hip: [58, 48], t: 205, th: 125, sh: 125, ft: 200, ua: 210, fa: 150 }, B: { hip: [58, 48], t: 300, th: 125, sh: 125, ft: 200, ua: 330, fa: 30 },
    props: { back: (J) => seg([J.hip[0] + 4, J.hip[1] + 6], [J.hip[0] + 12, J.hip[1] - 2], 6, C.dark) + seg([86, 72], [92, 68], 5, C.dark) + seg([66, 56], [72, 96], 4, C.dark) } },
  superman: { hl: ["torso", "glute"], A: { hip: [62, 87], t: -90, th: 90, sh: 90, ft: 180, ua: -90, fa: -90 }, B: { hip: [62, 87], t: -76, th: 80, sh: 80, ft: 160, ua: -72, fa: -72 }, props: {} },
  farmer: { hl: ["fa", "traps"], A: { hip: [60, 53], t: 2, th: 160, sh: 185, th2: 198, sh2: 175, ua: 180, fa: 180 }, B: { hip: [60, 51], t: 2, th: 198, sh: 175, th2: 160, sh2: 185, ua: 180, fa: 180 }, props: { front: both(dumbbell) } },
};

// yüzüstü ya da sola dönük pozlarda vücudun ön yüzü ters taraftadır
for (const k of ["pushup", "pike_pushup", "mountain", "plank", "leg_curl", "superman", "prone_yt", "hyperext", "ab_wheel"]) PATTERNS[k].flip = true;

// ---- makine / kablo kalıpları ----
Object.assign(PATTERNS, {
  crossover_mid: { hl: ["chest"], view: "front", A: { a: 92, b: 96 }, B: { a: 22, b: -12 },
    props: { back: () => dot([6, 26], 2.5, C.dark) + dot([114, 26], 2.5, C.dark), front: (J) => seg([6, 26], J.wrL, 1.1, C.prop) + seg([114, 26], J.wrR, 1.1, C.prop) } },
  crossover_low: { hl: ["chest", "delt"], A: { ank: [62, FLOOR], su: 4, tu: -4, t: 8, ua: 205, fa: 200 }, B: { ank: [62, FLOOR], su: 4, tu: -4, t: 8, ua: 50, fa: 45 },
    props: { front: (J) => cable(J.wr, [28, FLOOR]) } },
  cable_press: { hl: ["chest", "ua"], A: { ank: [62, FLOOR], su: 6, tu: -6, t: 10, ua: 205, fa: 80 }, B: { ank: [62, FLOOR], su: 6, tu: -6, t: 10, ua: 92, fa: 90 },
    props: { front: (J) => cable(J.wr, [8, 28]) } },
  high_row: { hl: ["torso", "ua"], A: { hip: [46, 70], t: 4, th: 88, sh: 180, ft: 90, ua: 38, fa: 38 }, B: { hip: [46, 70], t: -4, th: 88, sh: 180, ft: 90, ua: 205, fa: 70 },
    props: { back: () => seat(32, 74) + rect(84, 10, 4, 86, C.dark, 1), front: (J) => handle(J.wr) + pad([J.knee[0] - 2, J.knee[1] - 5], 8) } },
  chest_sup_row: { hl: ["torso", "ua"], A: { ank: [64, FLOOR], su: 10, tu: -20, t: 52, ua: 180, fa: 180 }, B: { ank: [64, FLOOR], su: 10, tu: -20, t: 52, ua: 245, fa: 175 },
    props: { back: (J) => seg(step(step(J.hip, 52, 8), 142, 6), step(step(J.hip, 52, 26), 142, 6), 6, C.dark) + rect(80, 62, 4, 34, C.dark, 1), front: atWrist(handle) } },
  back_ext_seated: { hl: ["torso", "glute"], A: { hip: [48, 66], t: 40, th: 90, sh: 180, ft: 90, ua: 150, fa: 30 }, B: { hip: [48, 66], t: -12, th: 90, sh: 180, ft: 90, ua: 160, fa: 20 },
    props: { back: (J) => seat(34, 70) + pad(step(J.hip, -35, 20), 4, 12) } },
  reverse_hyper: { hl: ["glute", "torso"], A: { hip: [56, 56], t: -90, th: 182, sh: 182, ft: 90, ua: 182, fa: 182 }, B: { hip: [56, 56], t: -90, th: 98, sh: 98, ft: 0, ua: 182, fa: 182 },
    props: { back: () => rect(18, 59, 40, 5, C.dark) + rect(34, 64, 4, 32, C.dark, 1), front: (J) => dot(J.ank, 3, C.prop) } },
  lateral_machine: { hl: ["delt"], view: "front", A: { a: 14, b: 40, py: 64 }, B: { a: 88, b: 120, py: 64 },
    props: { back: () => rect(44, 66, 32, 5, C.dark) + rect(57, 71, 6, 25, C.dark, 1), front: (J) => pad(J.elbL, 6, 6) + pad(J.elbR, 6, 6) } },
  cable_y: { hl: ["delt", "traps"], view: "front", A: { a: -14, b: -14 }, B: { a: 145, b: 148 },
    props: { back: () => dot([104, FLOOR], 2.5, C.dark), front: (J) => seg([104, FLOOR], J.wrL, 1.1, C.prop) + rect(J.wrL[0] - 2, J.wrL[1] - 1.5, 4, 3, C.plate, 1) } },
  cable_front_raise: { hl: ["delt"], A: { ...STAND, ua: 172, fa: 172 }, B: { ...STAND, ua: 92, fa: 92 }, props: { front: (J) => cable(J.wr, [40, FLOOR]) } },
  cable_rear: { hl: ["delt"], view: "front", A: { a: -20, b: -30 }, B: { a: 92, b: 92 },
    props: { back: () => dot([10, 26], 2.5, C.dark) + dot([110, 26], 2.5, C.dark), front: (J) => seg([110, 26], J.wrL, 1.1, C.prop) + seg([10, 26], J.wrR, 1.1, C.prop) } },
  rear_delt_row: { hl: ["delt", "torso"], A: { ...STAND, ua: 88, fa: 88 }, B: { ...STAND, ua: 280, fa: 95 }, props: { front: (J) => cable(J.wr, [114, 26]) } },
  high_cable_curl: { hl: ["ua"], view: "front", A: { a: 88, b: 90 }, B: { a: 88, b: 172 },
    props: { back: () => dot([6, 20], 2.5, C.dark) + dot([114, 20], 2.5, C.dark), front: (J) => seg([6, 20], J.wrL, 1.1, C.prop) + seg([114, 20], J.wrR, 1.1, C.prop) } },
  bayesian: { hl: ["ua"], A: { ank: [64, FLOOR], su: 4, tu: -4, t: 8, ua: 205, fa: 205 }, B: { ank: [64, FLOOR], su: 4, tu: -4, t: 8, ua: 205, fa: 40 }, props: { front: (J) => cable(J.wr, [18, FLOOR]) } },
  tri_machine: { hl: ["ua"], A: { hip: [48, 66], t: 4, th: 90, sh: 180, ft: 90, ua: 125, fa: 22 }, B: { hip: [48, 66], t: 4, th: 90, sh: 180, ft: 90, ua: 125, fa: 128 },
    props: { back: () => seat(34, 70) + seg([56, 44], [72, 58], 6, C.dark), front: atWrist(handle) } },
  seated_dip: { hl: ["ua"], A: { hip: [50, 66], t: 0, th: 90, sh: 180, ft: 90, ua: 210, fa: 25 }, B: { hip: [50, 66], t: 0, th: 90, sh: 180, ft: 90, ua: 182, fa: 182 },
    props: { back: () => seat(36, 70) + backPad(44, 68, 42, 34), front: atWrist(handle) } },
  belt_squat: { hl: ["th", "glute"], A: { ...STAND, ua: 160, fa: 140 }, B: { ...STAND, su: 30, tu: -78, t: 22, ua: 150, fa: 120 },
    props: { front: (J) => seg(J.hip, [J.hip[0], J.hip[1] + 8], 1.5, C.prop) + plate([J.hip[0], J.hip[1] + 13], 6) } },
  standing_leg_curl: { hl: ["th"], A: { hip: [60, 52], t: 10, th: 182, sh: 182, th2: 180, sh2: 180, ft: 90, ft2: 90, ua: 150, fa: 110 },
    B: { hip: [60, 52], t: 10, th: 172, sh: 292, th2: 180, sh2: 180, ft: 200, ft2: 90, ua: 150, fa: 110 },
    props: { back: () => rect(70, 22, 4, 74, C.dark, 1), front: (J) => pad([J.ank[0] - 2, J.ank[1] - 3], 8) + pad([J.knee[0] + 4, J.knee[1] - 6], 4, 8) } },
  glute_kick: { hl: ["glute"], A: { hip: [62, 56], t: 48, th: 140, sh: 205, th2: 178, sh2: 182, ft: 180, ft2: 90, ua: 130, fa: 110 },
    B: { hip: [62, 56], t: 48, th: 272, sh: 258, th2: 178, sh2: 182, ft: 200, ft2: 90, ua: 130, fa: 110 },
    props: { back: (J) => seg(step(J.hip, 48, 10), step(J.hip, 48, 26), 6, C.dark) + rect(84, 40, 4, 56, C.dark, 1) } },
  pull_through: { hl: ["glute", "th"], A: { ank: [62, FLOOR], su: 8, tu: -32, t: 72, ua: 205, fa: 210 }, B: { ...STAND, ank: [62, FLOOR], ua: 182, fa: 186 }, props: { front: (J) => cable(J.wr, [14, FLOOR]) } },
  cable_abd: { hl: ["glute"], view: "front", A: { a: 30, b: 20, gR: 4 }, B: { a: 30, b: 20, gR: 34 },
    props: { back: () => rect(28, 10, 4, 86, C.dark, 1), front: (J) => seg([30, FLOOR], J.ankR, 1.1, C.prop) } },
  cable_add: { hl: ["th"], view: "front", A: { a: 10, b: 10, gR: 30 }, B: { a: 10, b: 10, gR: -6 },
    props: { back: () => rect(108, 10, 4, 86, C.dark, 1), front: (J) => seg([110, FLOOR], J.ankR, 1.1, C.prop) } },
  leg_press_calf: { hl: ["sh"], A: { hip: [44, 70], t: -58, th: 68, sh: 78, ua: 150, fa: 100, ft: 345 }, B: { hip: [44, 70], t: -58, th: 68, sh: 78, ua: 150, fa: 100, ft: 300 },
    props: { back: (J) => backPad(36, 76, 16, 50) + seg([J.toe[0] + 3, J.toe[1] - 12], [J.toe[0] + 9, J.toe[1] + 10], 5, C.prop) } },
  donkey_calf: { hl: ["sh"], A: { ank: [62, FLOOR], su: 0, tu: -2, t: 82, ua: 172, fa: 172 }, B: { ank: [62, FLOOR - 5], su: 0, tu: -2, t: 82, ua: 172, fa: 172, ft: 135 },
    props: { back: (J) => rect(52, FLOOR - 1, 26, 4, C.dark, 1) + seg(step(J.hip, 82, 8), step(J.hip, 82, 22), 6, C.dark) } },
  ab_machine: { hl: ["abs"], A: { hip: [50, 66], t: -4, th: 90, sh: 180, ft: 90, ua: 10, fa: 150 }, B: { hip: [50, 66], t: 42, th: 90, sh: 180, ft: 90, ua: 60, fa: 190 },
    props: { back: () => seat(36, 70), front: atWrist(handle) } },
  captain_chair: { hl: ["abs"], A: { hip: [56, 54], t: 0, th: 180, sh: 180, ft: 90, ua: 120, fa: 90 }, B: { hip: [56, 54], t: -4, th: 88, sh: 182, ft: 90, ua: 120, fa: 90 },
    props: { back: (J) => backPad(J.sho[0] - 7, J.sho[1] - 4, J.hip[0] - 7, J.hip[1] + 6) + rect(46, 34, 26, 4, C.dark, 1) + rect(40, 34, 4, 62, C.dark, 1) } },
  pallof: { hl: ["abs"], A: { ...STAND, ua: 165, fa: 30 }, B: { ...STAND, ua: 92, fa: 92 }, props: { front: (J) => cable(J.wr, [114, 40]) } },
  side_bend: { hl: ["abs"], view: "front", A: { a: 6, b: 6, lift: 0 }, B: { a: 6, b: 6, lift: 0 },
    props: { back: (J, P) => { // kablo, eğilmiş gövdedeki ele bağlanır (grubun dışında çizilir)
      const r = rad(P.tiltDeg || 0), c = [60, J.pel[1]], d = [J.wrR[0] - c[0], J.wrR[1] - c[1]];
      const w = [c[0] + d[0] * Math.cos(r) - d[1] * Math.sin(r), c[1] + d[0] * Math.sin(r) + d[1] * Math.cos(r)];
      return dot([110, FLOOR], 2.5, C.dark) + seg([110, FLOOR], w, 1.1, C.prop);
    } } },
});
PATTERNS.reverse_hyper.flip = true;
// yan eğilme: gövde ön görünümde yana yatar
PATTERNS.side_bend.tilt = -16; // kablonun tersine eğilir

// varyantlar: aynı hareket, farklı ekipman
Object.assign(PATTERNS, {
  incline_machine: variant("incline", { front: atWrist(handle) }),
  decline_machine: variant("decline", { front: atWrist(handle) }),
  bench_smith: variant("bench", { back: smithRails }),
  incline_smith: variant("incline", { back: smithRails, front: atWrist((p) => plate(p, 7)) }),
  dips_assist: variant("dips", { addFront: (J) => pad([J.knee[0], J.knee[1] + 4], 12) }),
  pullup_assist: variant("pullup", { addFront: (J) => pad([J.knee[0], J.knee[1] + 4], 12) }),
  row_machine: variant("row_seated", { front: atWrist(handle) }),
  row_smith: variant("row_bent", { back: smithRails }),
  squat_smith: variant("squat_bar", { back: smithRails }),
  ohp_smith: variant("ohp", { back: smithRails }),
  lunge_smith: variant("lunge", { back: smithRails, front: (J, P) => plate(step(step(J.sho, P.t + 180, 4), P.t - 90, 3), 7) }),
  rdl_smith: variant("rdl", { back: smithRails }),
  calf_smith: variant("calf", { back: smithRails, front: (J, P) => plate(step(step(J.sho, P.t + 180, 4), P.t - 90, 3), 7) }),
  shrug_cable: variant("shrug", { back: () => dot([60, FLOOR], 2.5, C.dark), front: (J) => seg([60, FLOOR], J.wrL, 1.1, C.prop) + seg([60, FLOOR], J.wrR, 1.1, C.prop) }),
  shrug_smith: variant("shrug", { back: smithRails, front: (J) => seg([J.wrL[0] - 6, J.wrL[1]], [J.wrR[0] + 6, J.wrR[1]], 2.5, C.prop) }),
  upright_cable: variant("upright_row", { back: () => dot([60, FLOOR], 2.5, C.dark), front: (J) => seg([60, FLOOR], mid(J.wrL, J.wrR), 1.1, C.prop) + seg(J.wrL, J.wrR, 2.5, C.prop) }),
  curl_machine: variant("preacher", { front: atWrist(handle) }),
  kickback_cable: variant("kickback", { front: (J) => cable(J.wr, [104, FLOOR]) }),
  oh_ext_cable: variant("oh_ext", { front: (J) => cable(J.wr, [40, FLOOR]) }),
  wrist_curl_cable: variant("wrist_curl", { front: (J) => cable(J.wr, [72, FLOOR]) }),
  rope_pullover: variant("straight_pulldown", { front: (J) => cable(J.wr, [104, 2]) + seg([J.wr[0] - 3, J.wr[1] + 2], [J.wr[0] + 3, J.wr[1] + 5], 2, C.plate) }),
  kick_cable: variant("glute_kick", { front: (J) => cable(J.ank, [110, FLOOR]) }),
  torso_rotation: variant("twist", { back: () => seat(30, 84) }),
});

// Hareket -> kalıp
const EX_ANIM = {
  bench: "bench", db_bench: "bench_db", close_bench: "bench", incline_bench: "incline", incline_db: "incline", decline_bench: "decline",
  chest_press: "chest_press", db_fly: "crossover", cable_fly: "crossover", pec_deck: "crossover", pushup: "pushup", diamond_pushup: "pushup",
  pike_pushup: "pike_pushup", dips_chest: "dips", dips_tri: "bench_dips",
  deadlift: "deadlift", rack_pull: "rdl", rdl: "rdl", good_morning: "good_morning", sl_rdl_bw: "sl_rdl", kb_swing: "kb_swing", clean: "clean",
  pullup: "pullup", chinup: "pullup", leg_raise: "leg_raise", lat_pulldown: "pulldown", close_pulldown: "pulldown", pullover: "straight_pulldown",
  barbell_row: "row_bent", tbar_row: "row_bent", db_row: "row_db", cable_row: "row_seated", machine_row: "row_seated", face_pull: "face_pull", door_row: "inverted_row",
  hyperext: "hyperext", superman: "superman", shrug: "shrug", bag_shrug: "shrug_bag", farmer: "farmer", towel_hang: "farmer",
  ohp: "ohp", db_shoulder: "press_seated", arnold: "press_seated", shoulder_machine: "press_seated", thruster: "thruster",
  lateral_raise: "lateral", cable_lateral: "lateral_cable", bag_lateral: "lateral_bag", front_raise: "front_raise",
  rear_fly: "rear_bent", reverse_pec: "rev_fly", prone_yt: "prone_yt", upright_row: "upright_row",
  bb_curl: "curl_bar", db_curl: "curl", hammer: "curl", preacher: "preacher", incline_curl: "curl_incline", cable_curl: "curl_cable",
  concentration: "concentration", bag_curl: "curl_bag", wrist_curl: "wrist_curl",
  pushdown: "pushdown", rope_pushdown: "pushdown", skullcrusher: "skull", oh_ext: "oh_ext", kickback: "kickback",
  squat: "squat_bar", front_squat: "squat_front", goblet: "squat_front", hack_squat: "hack_squat", bw_squat: "squat_bw", wall_sit: "wall_sit",
  leg_press: "leg_press", lunge: "lunge", bulgarian: "bulgarian", split_squat_bw: "lunge", step_up: "step_up",
  leg_ext: "leg_ext", seated_curl: "leg_curl_seated", leg_curl: "leg_curl", nordic: "nordic",
  hip_thrust: "hip_thrust", glute_bridge: "glute_bridge", abductor: "abductor", adductor_m: "adductor",
  calf_raise: "calf", bw_calf: "calf", seated_calf: "calf_seated",
  crunch: "crunch", cable_crunch: "crunch_cable", bicycle: "bicycle", russian_twist: "twist", plank: "plank", side_plank: "plank",
  ab_wheel: "ab_wheel", mountain_climber: "mountain", woodchop: "twist", burpee: "burpee",
  // makine / kablo / Smith
  cable_fly_mid: "crossover_mid", cable_fly_high: "crossover", cable_fly_low: "crossover_low", single_cable_fly: "crossover_mid",
  cable_chest_press: "cable_press", incline_chest_press: "incline_machine", decline_chest_press: "decline_machine", hammer_chest: "chest_press",
  smith_bench: "bench_smith", smith_incline: "incline_smith", assisted_dip: "dips_assist",
  assisted_pullup: "pullup_assist", machine_pulldown: "pulldown", reverse_pulldown: "pulldown", single_pulldown: "pulldown",
  hammer_row: "row_machine", low_row: "row_machine", high_row: "high_row", chest_sup_row: "chest_sup_row", single_cable_row: "row_seated",
  smith_row: "row_smith", rope_pullover: "rope_pullover", back_ext_machine: "back_ext_seated", reverse_hyper: "reverse_hyper",
  cable_shrug: "shrug_cable", smith_shrug: "shrug_smith",
  lateral_machine: "lateral_machine", cable_y_raise: "cable_y", cable_front_raise: "cable_front_raise", cable_rear_fly: "cable_rear",
  cable_upright_row: "upright_cable", smith_ohp: "ohp_smith", rear_delt_row: "rear_delt_row",
  cable_hammer: "curl_cable", biceps_machine: "curl_machine", preacher_machine: "curl_machine", high_cable_curl: "high_cable_curl",
  bayesian_curl: "bayesian", triceps_machine: "tri_machine", dip_machine: "seated_dip", cable_kickback: "kickback_cable",
  single_pushdown: "pushdown", cable_oh_ext: "oh_ext_cable", cable_wrist_curl: "wrist_curl_cable",
  smith_squat: "squat_smith", pendulum_squat: "hack_squat", belt_squat: "belt_squat", single_leg_press: "leg_press",
  smith_lunge: "lunge_smith", smith_rdl: "rdl_smith", standing_leg_curl: "standing_leg_curl", glute_ham: "nordic",
  glute_kick_machine: "glute_kick", cable_glute_kick: "kick_cable", hip_thrust_machine: "hip_thrust", cable_pull_through: "pull_through",
  cable_abduction: "cable_abd", cable_adduction: "cable_add", leg_press_calf: "leg_press_calf", smith_calf: "calf_smith", donkey_calf: "donkey_calf",
  ab_machine: "ab_machine", torso_rotation: "torso_rotation", captain_chair: "captain_chair", pallof_press: "pallof", cable_side_bend: "side_bend",
  converging_press: "chest_press", vertical_chest_press: "chest_press", incline_machine_fly: "crossover", machine_pullover: "straight_pulldown",
  iso_pulldown: "pulldown", iso_low_row: "row_machine", machine_shrug: "shrug_smith", iso_shoulder_press: "press_seated", machine_rear_row: "rear_delt_row",
  machine_oh_tri: "oh_ext", machine_hammer_curl: "curl_machine", horizontal_leg_press: "leg_press", vertical_leg_press: "leg_press", v_squat: "hack_squat",
  squat_press_machine: "hack_squat", sissy_squat_machine: "hack_squat", single_leg_ext: "leg_ext", kneeling_leg_curl: "standing_leg_curl", multi_hip: "glute_kick",
  standing_calf_machine: "calf", calf_press_machine: "leg_press_calf", ab_coaster: "captain_chair", oblique_machine: "torso_rotation",
};

// Özel (kullanıcının eklediği) hareketler için birincil kasa göre kalıp
const ANIM_BY_MUSCLE = { chest: "bench", front_delt: "ohp", side_delt: "lateral", rear_delt: "rear_bent", biceps: "curl", triceps: "pushdown", forearm: "wrist_curl",
  traps: "shrug", lats: "pulldown", upper_back: "row_bent", lower_back: "hyperext", abs: "crunch", obliques: "twist", glutes: "hip_thrust",
  quads: "squat_bw", hamstrings: "leg_curl", adductors: "adductor", calves: "calf" };

function animKeyFor(id) {
  if (EX_ANIM[id]) return EX_ANIM[id];
  const ex = typeof findExercise === "function" ? findExercise(id, (typeof state !== "undefined" && state.customExercises) || []) : null;
  return ANIM_BY_MUSCLE[ex?.p?.[0]] || "squat_bw";
}

function lerpPose(A, B, k) {
  const out = {};
  for (const key of new Set([...Object.keys(A), ...Object.keys(B)])) {
    const a = A[key] ?? B[key], b = B[key] ?? A[key];
    if (Array.isArray(a)) out[key] = [a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k];
    else if (typeof a === "number") out[key] = a + (b - a) * k;
    else out[key] = k < 0.5 ? a : b;
  }
  return out;
}

function animFrame(key, k, style) {
  const pat = PATTERNS[key] || PATTERNS.squat_bw;
  const P = lerpPose(pat.A, pat.B, k);
  if (pat.tilt) P.tiltDeg = pat.tilt * k;
  const front = pat.view === "front";
  const J = front ? solveFront(P) : solveSide(P);
  const props = pat.props || {};
  const hl = normHL(key, pat.hl || [], front);
  // seçili tarzın paletini uygula, çiz, geri al
  const st = STYLES[style || ANIM_STYLE] || {};
  Object.assign(C, C_BASE, st); STY = st;
  const out = floor() + (front ? drawFront(J, hl, props, P) : st.stick ? drawSideStick(J, hl, props, P) : drawSide(J, hl, props, P, !!pat.flip));
  Object.assign(C, C_BASE); STY = {};
  return out;
}

function exAnimHTML(id) {
  const key = animKeyFor(id);
  return `<span class="eximg anim-fig"><svg viewBox="1 -14 118 112" data-anim="${key}" aria-hidden="true">${animFrame(key, 0)}</svg></span>`;
}

// ---- Bölge (kas) kartı resmi: sporcu figürü, ilgili kasa yakınlaştırılmış, önden ya da arkadan ----
const ATHLETE_THUMB = {
  chest:      { keys: ["chest"],      box: "36 10 48 40" },
  front_delt: { keys: ["delt"],       box: "36 10 48 40" },
  side_delt:  { keys: ["side_delt"],  box: "36 10 48 40" },
  rear_delt:  { keys: ["delt"],       box: "36 10 48 40", back: true },
  biceps:     { keys: ["biceps"],     box: "34 14 52 44" },
  triceps:    { keys: ["biceps"],     box: "34 14 52 44", back: true },
  forearm:    { keys: ["forearm"],    box: "32 24 56 46" },
  traps:      { keys: ["traps"],      box: "38 6 44 36", back: true },
  lats:       { keys: ["lats"],       box: "36 14 48 40", back: true },
  upper_back: { keys: ["back"],       box: "36 12 48 40", back: true },
  lower_back: { keys: ["lower_back"], box: "38 26 44 36", back: true },
  abs:        { keys: ["absc"],       box: "38 22 44 36" },
  obliques:   { keys: ["abs"],        box: "38 22 44 36" },
  glutes:     { keys: ["glutes"],     box: "38 38 44 36", back: true },
  quads:      { keys: ["th"],         box: "36 46 48 40" },
  hamstrings: { keys: ["th"],         box: "36 46 48 40", back: true },
  adductors:  { keys: ["adductors"],  box: "36 46 48 40" },
  calves:     { keys: ["calves"],     box: "38 64 44 36", back: true },
};
function athleteThumbSVG(m, color = "#4ade80") {
  const t = ATHLETE_THUMB[m] || ATHLETE_THUMB.chest;
  const P = { a: 16, b: 8, g: 5, backView: !!t.back };
  const st = STYLES[BODY_STYLE] || {};
  Object.assign(C, C_BASE, st, { hl: color, hlGlow: color + "55" }); STY = st;
  const body = drawFront(solveFront(P), t.keys, {}, P);
  Object.assign(C, C_BASE); STY = {};
  return `<svg viewBox="${t.box}" class="thumb-svg ath" aria-hidden="true">${body}</svg>`;
}

// ---- Tam boy sporcu figürü: uygulamadaki 18 kası kendi renkleriyle gösterir ----
// colors: { chest: "#4ade80", lats: "rgba(...)", ... } ; view: "front" | "back"
const BODY_KEYS = {
  front: { chest: "chest", front_delt: "delt", side_delt: "side_delt", biceps: "biceps", forearm: "forearm", abs: "absc", obliques: "abs", quads: "th", adductors: "adductors", calves: "calves" },
  back: { rear_delt: "delt", side_delt: "side_delt", triceps: "biceps", forearm: "forearm", traps: "traps", lats: "lats", upper_back: "back", lower_back: "lower_back", glutes: "glutes", hamstrings: "th", calves: "calves" },
};
function athleteBodySVG(view, colors) {
  const map = BODY_KEYS[view], keys = [], hlColors = {};
  for (const [m, key] of Object.entries(map)) if (colors[m]) { keys.push(key); hlColors[key] = colors[m]; }
  const P = { a: 13, b: 7, g: 5, backView: view === "back", hlColors, noGlow: true };
  const st = STYLES[BODY_STYLE] || {};
  Object.assign(C, C_BASE, st); STY = st;
  const body = drawFront(solveFront(P), keys, {}, P);
  Object.assign(C, C_BASE); STY = {};
  return `<svg viewBox="33 5 54 95" class="ath-body" role="img" aria-label="${view === "front" ? "Önden" : "Arkadan"} görünüm">${body}</svg>`;
}

// Tek döngü: ekranda görünen tüm animasyonları günceller (görünmeyenler atlanır)
const ANIM_PERIOD = 2600;
let animRunning = false;
function animLoop(now) {
  const reduce = window.matchMedia && matchMedia("(prefers-reduced-motion: reduce)").matches;
  const svgs = document.querySelectorAll("svg[data-anim]");
  if (!svgs.length) { animRunning = false; return; }
  // 0 -> 1 -> 0, uçlarda kısa bekleme ile yumuşak geçiş
  const ph = (now % ANIM_PERIOD) / ANIM_PERIOD;
  const tri = ph < 0.5 ? ph * 2 : 2 - ph * 2;
  const k = reduce ? 1 : 0.5 - 0.5 * Math.cos(Math.PI * Math.min(1, Math.max(0, (tri - 0.08) / 0.84)));
  for (const svg of svgs) {
    if (!svg.isConnected || svg.getBoundingClientRect().bottom < 0 || svg.getBoundingClientRect().top > innerHeight) continue;
    svg.innerHTML = animFrame(svg.dataset.anim, k);
  }
  requestAnimationFrame(animLoop);
}
function startAnimations() {
  if (animRunning) return;
  animRunning = true;
  requestAnimationFrame(animLoop);
}

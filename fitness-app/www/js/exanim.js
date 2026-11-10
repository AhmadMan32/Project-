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
const C = { body: "#e8eaf0", far: "#6b7385", hl: "#4ade80", prop: "#8fa3c0", plate: "#60a5fa", dark: "#3a4150" };
const seg = (a, b, w, c) => `<line x1="${a[0].toFixed(1)}" y1="${a[1].toFixed(1)}" x2="${b[0].toFixed(1)}" y2="${b[1].toFixed(1)}" stroke="${c}" stroke-width="${w}" stroke-linecap="round"/>`;
const dot = (p, r, c, extra = "") => `<circle cx="${p[0].toFixed(1)}" cy="${p[1].toFixed(1)}" r="${r}" fill="${c}" ${extra}/>`;
const rect = (x, y, w, h, c, r = 1.5) => `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${r}" fill="${c}"/>`;
const plate = (p, r = 8) => dot(p, r, "none", `stroke="${C.plate}" stroke-width="3"`) + dot(p, 2, C.plate);
const dumbbell = (p) => seg([p[0] - 4, p[1]], [p[0] + 4, p[1]], 2.2, C.prop) + rect(p[0] - 6, p[1] - 3.5, 3, 7, C.plate, 1) + rect(p[0] + 3, p[1] - 3.5, 3, 7, C.plate, 1);
const kettle = (p) => `<path d="M${(p[0] - 3).toFixed(1)} ${(p[1] + 1).toFixed(1)} q3 -6 6 0" fill="none" stroke="${C.prop}" stroke-width="1.8"/>` + dot([p[0], p[1] + 5], 4.5, C.plate);
const cable = (p, anchor) => seg(anchor, p, 1.2, C.prop) + dot(anchor, 2.5, C.dark, `stroke="${C.prop}" stroke-width="1"`) + rect(p[0] - 3, p[1] - 1.5, 6, 3, C.plate, 1);
const bag = (p) => rect(p[0] - 5, p[1], 10, 11, C.plate, 3) + seg([p[0] - 2, p[1]], [p[0] + 2, p[1]], 1.5, C.prop);
const floor = () => seg([4, FLOOR + 3], [116, FLOOR + 3], 1.5, C.dark);
const handle = (p) => rect(p[0] - 1.5, p[1] - 5, 3, 10, C.plate, 1);
const pad = (p, w = 10, h = 4) => rect(p[0] - w / 2, p[1] - h / 2, w, h, C.prop, 1.5);
const smithRails = () => rect(22, -8, 3, 104, C.dark, 1) + rect(95, -8, 3, 104, C.dark, 1);
// mevcut bir kalıbı farklı ekipmanla kullan: props birleştirilir (back/front sırası korunur)
function variant(base, extra = {}, over = {}) {
  const b = PATTERNS[base];
  const props = { ...b.props };
  if (extra.back) { const ob = b.props.back; props.back = (J, P) => (extra.back(J, P) + (ob ? ob(J, P) : "")); }
  if (extra.front) props.front = extra.front;
  if (extra.addFront) { const of = b.props.front; props.front = (J, P) => ((of ? of(J, P) : "") + extra.addFront(J, P)); }
  return { ...b, ...over, props };
}

// ---- figürü çiz ----
function drawSide(J, hl, props, P) {
  const isHL = (k) => hl.includes(k);
  const col = (k) => (isHL(k) ? C.hl : C.body);
  const farCol = (k) => (isHL(k) ? "#2f7a4c" : C.far);
  let s = props.back ? props.back(J, P) : "";
  // uzak uzuvlar
  s += seg(J.hip, J.knee2, 5.5, farCol("th")) + seg(J.knee2, J.ank2, 5, farCol("sh")) + seg(J.ank2, J.toe2, 3.5, C.far);
  s += seg(J.sho, J.elb2, 4.5, farCol("ua")) + seg(J.elb2, J.wr2, 4, farCol("fa"));
  if (props.far) s += props.far(J, P);
  // gövde (göğüs = üst yarı, karın = alt yarı)
  const m = mid(J.hip, J.sho);
  s += seg(J.hip, m, 8, isHL("abs") || isHL("torso") ? C.hl : C.body) + seg(m, J.sho, 8, isHL("chest") || isHL("torso") ? C.hl : C.body);
  if (isHL("glute")) s += dot(step(J.hip, (P.t ?? 0) - 90, 1.5), 4.6, C.hl);
  s += dot(J.head, RIG.head, C.body);
  // yakın uzuvlar
  s += seg(J.hip, J.knee, 6, col("th")) + seg(J.knee, J.ank, 5.5, col("sh")) + seg(J.ank, J.toe, 4, C.body);
  if (isHL("delt")) s += dot(J.sho, 4.2, C.hl);
  s += seg(J.sho, J.elb, 5, col("ua")) + seg(J.elb, J.wr, 4.5, col("fa"));
  if (props.front) s += props.front(J, P);
  return s;
}
function drawFront(J, hl, props, P) {
  const isHL = (k) => hl.includes(k);
  let s = props.back ? props.back(J, P) : "";
  const legC = isHL("th") ? C.hl : C.body;
  for (const [hip, knee, ank] of [[J.hipL, J.kneeL, J.ankL], [J.hipR, J.kneeR, J.ankR]]) s += seg(hip, knee, 6, legC) + seg(knee, ank, 5.5, C.body);
  s += seg(J.hipL, J.hipR, 7, C.body);
  if (P.tiltDeg) s += `<g transform="rotate(${P.tiltDeg.toFixed(1)} 60 ${J.pel[1]})">`;
  s += seg(J.pel, J.chest, 10, isHL("torso") || isHL("chest") ? C.hl : C.body) + seg(J.shoL, J.shoR, 7, isHL("traps") ? C.hl : C.body);
  if (isHL("traps")) s += seg([55, J.shoL[1] - 2], [65, J.shoR[1] - 2], 5, C.hl);
  s += seg(J.neck, J.chest, 4, C.body) + dot(J.head, RIG.head, C.body);
  const armC = isHL("ua") ? C.hl : C.body;
  for (const [sho, elb, wr] of [[J.shoL, J.elbL, J.wrL], [J.shoR, J.elbR, J.wrR]]) {
    if (isHL("delt")) s += dot(sho, 4.4, C.hl);
    s += seg(sho, elb, 5, armC) + seg(elb, wr, 4.5, C.body);
  }
  if (props.front) s += props.front(J, P);
  if (P.tiltDeg) s += "</g>";
  return s;
}

// ---- kalıplar ----
const STAND = { ank: [60, FLOOR], su: 0, tu: 0, t: 0, ft: 90 };
const LIE = { hip: [60, 68], t: -90, th: 115, sh: 172, ft: 90 }; // sehpada sırtüstü (baş solda)
const bench = () => rect(14, 71, 62, 5, C.dark) + rect(20, 76, 4, 17, C.dark, 1) + rect(66, 76, 4, 17, C.dark, 1);
const seat = (x, y, w = 24) => rect(x, y, w, 5, C.dark) + rect(x + w / 2 - 2, y + 5, 4, FLOOR + 3 - y - 5, C.dark, 1);
const backPad = (x1, y1, x2, y2) => seg([x1, y1], [x2, y2], 6, C.dark);
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
  glute_bridge: { hl: ["glute"], A: { ank: [82, FLOOR], su: -22, tu: -102, t: -112, ua: 200, fa: 200 }, B: { ank: [82, FLOOR], su: -22, tu: -74, t: -124, ua: 210, fa: 210 }, props: { back: floor } },
  calf: { hl: ["sh"], A: { ...STAND, ua: 180, fa: 180 }, B: { ...STAND, ank: [60, FLOOR - 5], ft: 135, ua: 180, fa: 180 }, props: { back: () => rect(50, FLOOR - 1, 30, 4, C.dark, 1), front: both(dumbbell) } },
  calf_seated: { hl: ["sh"], A: { hip: [50, 68], t: 0, th: 90, sh: 180, ft: 90, ua: 160, fa: 100 }, B: { hip: [50, 64], t: 0, th: 98, sh: 178, ft: 130, ua: 160, fa: 100 },
    props: { back: () => seat(36, 72), front: (J) => rect(J.knee[0] - 6, J.knee[1] - 7, 14, 4, C.prop, 1) } },
  leg_ext: { hl: ["th"], A: { hip: [48, 62], t: -8, th: 90, sh: 180, ft: 90, ua: 170, fa: 150 }, B: { hip: [48, 62], t: -8, th: 90, sh: 96, ft: 10, ua: 170, fa: 150 },
    props: { back: () => seat(34, 66) + backPad(38, 64, 34, 32), front: (J) => dot(J.ank, 3.5, C.prop) } },
  leg_curl_seated: { hl: ["th"], A: { hip: [48, 62], t: -8, th: 90, sh: 100, ft: 10, ua: 170, fa: 150 }, B: { hip: [48, 62], t: -8, th: 90, sh: 195, ft: 100, ua: 170, fa: 150 },
    props: { back: () => seat(34, 66) + backPad(38, 64, 34, 32), front: (J) => dot(step(J.ank, 0, 3), 3.5, C.prop) } },
  leg_curl: { hl: ["th"], A: { hip: [58, 68], t: -90, th: 90, sh: 90, ft: 180, ua: -125, fa: -60 }, B: { hip: [58, 68], t: -90, th: 90, sh: 15, ft: 300, ua: -125, fa: -60 },
    props: { back: () => rect(16, 72, 62, 5, C.dark) + rect(40, 77, 4, 19, C.dark, 1), front: (J) => dot(step(J.ank, J.ank[1] < 60 ? 270 : 0, 3), 3.5, C.prop) } },
  nordic: { hl: ["th"], A: { knee: [62, 88], tu: 0, sh: 270, ft: 180, t: 0, ua: 165, fa: 150 }, B: { knee: [62, 88], tu: 52, sh: 270, ft: 180, t: 52, ua: 120, fa: 110 },
    props: { back: () => rect(30, 84, 14, 4, C.prop, 1) + floor() } },
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
  pushup: { hl: ["chest", "ua"], A: { hip: [64, 73], t: -74, th: 106, sh: 106, ft: 180, ua: 180, fa: 180 }, B: { hip: [64, 81], t: -82, th: 99, sh: 99, ft: 180, ua: 140, fa: 226 }, props: { back: floor } },
  pike_pushup: { hl: ["delt", "ua"], A: { hip: [62, 46], t: -150, th: 150, sh: 150, ft: 180, ua: 215, fa: 215 }, B: { hip: [62, 50], t: -142, th: 146, sh: 146, ft: 180, ua: 160, fa: 230 }, props: { back: floor } },
  mountain: { hl: ["abs", "th"], A: { hip: [64, 73], t: -74, th: 106, sh: 106, th2: 106, sh2: 106, ft: 180, ua: 180, fa: 180 }, B: { hip: [64, 71], t: -72, th: 235, sh: 115, th2: 106, sh2: 106, ft: 180, ua: 180, fa: 180 }, props: { back: floor } },
  burpee: { hl: ["th", "chest"], A: { ank: [60, FLOOR], su: 34, tu: -85, t: 55, ua: 170, fa: 175 }, B: { ank: [60, FLOOR - 6], su: 0, tu: 0, t: 0, ua: 5, fa: 5, ft: 150 }, props: { back: floor } },
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
    props: { back: () => seg([30, 46], [58, 46], 3, C.prop) + rect(32, 46, 3, 50, C.dark, 1) + floor() } },
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
  prone_yt: { hl: ["delt", "torso"], A: { hip: [64, 86], t: -90, th: 90, sh: 90, ft: 180, ua: 180, fa: 180 }, B: { hip: [64, 86], t: -84, th: 92, sh: 92, ft: 180, ua: -60, fa: -60 }, props: { back: floor } },
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
  crunch: { hl: ["abs"], A: { hip: [62, 87], t: -90, th: 45, sh: 160, ft: 90, ua: -55, fa: 70 }, B: { hip: [62, 87], t: -58, th: 45, sh: 160, ft: 90, ua: -25, fa: 100 }, props: { back: floor } },
  crunch_cable: { hl: ["abs"], A: { knee: [62, 90], tu: 0, sh: 270, ft: 180, t: 5, ua: 20, fa: 200 }, B: { knee: [62, 90], tu: 5, sh: 270, ft: 180, t: 100, ua: 115, fa: 290 },
    props: { back: floor, front: (J) => cable(J.wr, [74, -6]) } },
  bicycle: { hl: ["abs"], A: { hip: [64, 86], t: -62, th: 40, sh: 110, th2: 90, sh2: 92, ft: 90, ua: -40, fa: 80 }, B: { hip: [64, 86], t: -62, th: 90, sh: 92, th2: 40, sh2: 110, ft: 90, ua: -20, fa: 110 }, props: { back: floor } },
  twist: { hl: ["abs"], A: { hip: [56, 86], t: -28, th: 55, sh: 105, ft: 90, ua: 130, fa: 75 }, B: { hip: [56, 86], t: -28, th: 55, sh: 105, ft: 90, ua: 175, fa: 120 }, props: { back: floor, front: atWrist((p) => plate(p, 5)) } },
  plank: { hl: ["abs"], A: { hip: [62, 79], t: -82, th: 98, sh: 98, ft: 180, ua: 180, fa: 270 }, B: { hip: [62, 77.5], t: -81, th: 98, sh: 98, ft: 180, ua: 180, fa: 270 }, props: { back: floor } },
  ab_wheel: { hl: ["abs"], A: { knee: [86, 89], tu: -15, sh: 90, ft: 180, t: -55, ua: 190, fa: 190 }, B: { knee: [86, 89], tu: -55, sh: 90, ft: 180, t: -82, ua: -88, fa: -88 },
    props: { back: floor, front: (J) => dot([J.wr[0], Math.max(J.wr[1] + 4, FLOOR - 2)], 5, "none", `stroke="${C.plate}" stroke-width="2.5"`) } },
  hyperext: { hl: ["torso", "glute"], A: { hip: [58, 48], t: 205, th: 125, sh: 125, ft: 200, ua: 210, fa: 150 }, B: { hip: [58, 48], t: 300, th: 125, sh: 125, ft: 200, ua: 330, fa: 30 },
    props: { back: (J) => seg([J.hip[0] + 4, J.hip[1] + 6], [J.hip[0] + 12, J.hip[1] - 2], 6, C.dark) + seg([86, 72], [92, 68], 5, C.dark) + seg([66, 56], [72, 96], 4, C.dark) } },
  superman: { hl: ["torso", "glute"], A: { hip: [62, 87], t: -90, th: 90, sh: 90, ft: 180, ua: -90, fa: -90 }, B: { hip: [62, 87], t: -76, th: 80, sh: 80, ft: 160, ua: -72, fa: -72 }, props: { back: floor } },
  farmer: { hl: ["fa", "traps"], A: { hip: [60, 53], t: 2, th: 160, sh: 185, th2: 198, sh2: 175, ua: 180, fa: 180 }, B: { hip: [60, 51], t: 2, th: 198, sh: 175, th2: 160, sh2: 185, ua: 180, fa: 180 }, props: { front: both(dumbbell) } },
};

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

function animFrame(key, k) {
  const pat = PATTERNS[key] || PATTERNS.squat_bw;
  const P = lerpPose(pat.A, pat.B, k);
  if (pat.tilt) P.tiltDeg = pat.tilt * k;
  const J = pat.view === "front" ? solveFront(P) : solveSide(P);
  const props = pat.props || {};
  return (pat.view === "front" ? drawFront(J, pat.hl || [], props, P) : drawSide(J, pat.hl || [], props, P)) + (props.back === floor ? "" : floor());
}

function exAnimHTML(id) {
  const key = animKeyFor(id);
  return `<span class="eximg anim-fig"><svg viewBox="8 -14 104 112" data-anim="${key}" aria-hidden="true">${animFrame(key, 0)}</svg></span>`;
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

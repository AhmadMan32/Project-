import type { Event, Frame, Line, Run, ScenarioInput, Terrain, Zone } from '@workspace/api-client-react';

/**
 * Browser port of artifacts/api-server/firebreak/simulation.py for the offline demo build.
 * Same rules (10 s steps, 40 s residence, wind/slope/fuel/moisture/wetting factors, line strategies)
 * but a different random generator, so seeds do not reproduce server runs exactly.
 */

const BURNABLE = new Set([10, 20, 30, 40, 90, 95, 100]);
const DT = 10;

function mulberry32(seed: number) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

type SimLine = Line & { r: number; c: number; dr: number; dc: number };

export function simulate(s: ScenarioInput, t: Terrain, ignitions: { row: number; col: number }[], maxSteps: number) {
  const n = t.rows;
  const zs = Math.floor(n / s.zones);
  const cell = t.cellSize;
  const fuel = t.fuel, elev = t.elevation;
  const N = n * n;
  let state = new Uint8Array(N);
  for (let i = 0; i < N; i++) state[i] = BURNABLE.has(fuel[i]) ? 0 : 3;
  const age = new Uint8Array(N);
  const wet = new Float64Array(N);
  const rng = mulberry32((s.seed * 2654435761) ^ 0x9e3779b9);
  const events: Event[] = [];
  const frames: Frame[] = [];
  const seen = new Set<string>(), initial = new Set<string>();
  const zn = (r: number, c: number) => `${String.fromCharCode(65 + Math.floor(r / zs))}${Math.floor(c / zs) + 1}`;
  for (const ig of ignitions) {
    if (ig.row >= n || ig.col >= n || state[ig.row * n + ig.col] === 3)
      throw new Error('Ignition must be inside the study area on a burnable WorldCover cell.');
    state[ig.row * n + ig.col] = 1;
    const name = zn(ig.row, ig.col);
    if (!seen.has(name)) events.push({ time: 0, kind: 'ignition', target: name });
    seen.add(name); initial.add(name);
  }
  const lines: SimLine[] = [];
  for (let r = 0; r < s.zones; r++)
    for (let c = 0; c < s.zones; c++)
      for (const [dr, dc] of [[0, 1], [1, 0]])
        if (r + dr < s.zones && c + dc < s.zones) {
          const a = zn(r * zs, c * zs), b = zn((r + dr) * zs, (c + dc) * zs);
          lines.push({ id: `${a}-${b}`, from: a, to: b, on: false, waterL: 0, r, c, dr, dc });
        }
  const ang = (((s.windDir + 180) % 360) * Math.PI) / 180;
  const wx = Math.sin(ang), wy = -Math.cos(ang);
  let crossing: number | null = null;
  let step = 0;

  const frame = (): Frame => {
    const zones: Zone[] = [];
    for (let r = 0; r < s.zones; r++)
      for (let c = 0; c < s.zones; c++) {
        let any1 = false, any2 = false, anyWet = false, burnt = 0;
        for (let i = r * zs; i < (r + 1) * zs; i++)
          for (let j = c * zs; j < (c + 1) * zs; j++) {
            const v = state[i * n + j];
            if (v === 1) { any1 = true; burnt++; } else if (v === 2) { any2 = true; burnt++; }
            if (wet[i * n + j] > 0) anyWet = true;
          }
        zones.push({ id: zn(r * zs, c * zs), state: any1 ? 'burning' : any2 ? 'burned' : 'safe', burnedPct: Math.round((burnt / (zs * zs)) * 10000) / 100, wet: anyWet });
      }
    let burned = 0, burning = false;
    for (let i = 0; i < N; i++) { if (state[i] === 1) { burned++; burning = true; } else if (state[i] === 2) burned++; }
    return {
      step, time: step * DT, state: Array.from(state), wet: Array.from(wet, (w) => Math.round(w * 100) / 100),
      waterL: lines.reduce((a, l) => a + l.waterL, 0), burnedHa: (burned * cell * cell) / 10000, zones,
      lines: lines.map(({ id, from, to, on, waterL }) => ({ id, from, to, on, waterL })),
      events: [...events], finished: !burning,
    };
  };

  frames.push(frame());
  const dirs: [number, number][] = [];
  for (const dr of [-1, 0, 1]) for (const dc of [-1, 0, 1]) if (dr || dc) dirs.push([dr, dc]);
  const ffac = (f: number) => (f === 10 ? 1 : f === 20 ? 1.1 : f === 30 ? 1.25 : 0.8);
  const rnd = new Float64Array(8 * N);
  let broke = false;
  for (step = 1; step <= maxSteps; step++) {
    const burning: number[] = [];
    for (let i = 0; i < N; i++) if (state[i] === 1) burning.push(i);
    if (!burning.length) { step -= 1; broke = true; break; }
    for (let i = 0; i < N; i++) wet[i] = Math.max(0, wet[i] - 1 / 6);
    for (const line of lines) {
      const { r, c, dr, dc } = line;
      const inA: number[] = [], inB: number[] = [];
      for (const i of burning) {
        const br = Math.floor(Math.floor(i / n) / zs), bc = Math.floor((i % n) / zs);
        if (br === r && bc === c) inA.push(i);
        else if (br === r + dr && bc === c + dc) inB.push(i);
      }
      let on = s.strategy === 'all' || (s.strategy === 'fixed' && step * DT >= (s.fixedDelay ?? 60));
      if (s.strategy === 'adjacent') on = inA.length > 0 || inB.length > 0;
      else if (s.strategy === 'smart') {
        let near = false;
        for (const i of [...inA, ...inB]) {
          const d = dr ? Math.abs(Math.floor(i / n) - (r + 1) * zs + 0.5) : Math.abs((i % n) - (c + 1) * zs + 0.5);
          if (d < 3) { near = true; break; }
        }
        const down = (inA.length > 0 && dc * wx + dr * wy > 0.3) || (inB.length > 0 && -dc * wx - dr * wy > 0.3);
        on = near || (down && s.moisture < 65);
      }
      if (on !== line.on) events.push({ time: step * DT, kind: on ? 'line_on' : 'line_off', target: line.id });
      line.on = on;
      if (on) {
        line.waterL += ((s.flowLpm ?? 120) * DT) / 60;
        const [r0, r1, c0, c1] = dr
          ? [Math.max(0, (r + 1) * zs - 2), Math.min(n, (r + 1) * zs + 2), c * zs, (c + 1) * zs]
          : [r * zs, (r + 1) * zs, Math.max(0, (c + 1) * zs - 2), Math.min(n, (c + 1) * zs + 2)];
        for (let i = r0; i < r1; i++) for (let j = c0; j < c1; j++) wet[i * n + j] = 1;
      }
    }
    // Every cell and direction draws a number each step, regardless of strategy (common random numbers).
    for (let k = 0; k < rnd.length; k++) rnd[k] = rng();
    const next = state.slice();
    for (let i = 0; i < N; i++) if (state[i] === 1) { age[i]++; if (age[i] >= 4) next[i] = 2; }
    dirs.forEach(([dr, dc], k) => {
      const h = Math.hypot(dr, dc);
      const wind = Math.exp((0.08 * s.windSpeed * (dc * wx + dr * wy)) / h);
      const base = 0.25 * wind * (1 - (0.8 * s.moisture) / 100);
      for (const i of burning) {
        const sr = Math.floor(i / n), sc = i % n;
        const tr = sr + dr, tc = sc + dc;
        if (tr < 0 || tc < 0 || tr >= n || tc >= n) continue;
        const ti = tr * n + tc;
        if (state[ti] !== 0) continue;
        const grade = (elev[ti] - elev[i]) / (cell * h);
        const p = Math.min(0.95, Math.max(0, base * Math.exp(Math.min(2, Math.max(-2, grade * 3))) * ffac(fuel[ti]) * (1 - 0.9 * wet[ti])));
        if (rnd[k * N + ti] < p) next[ti] = 1;
      }
    });
    for (let i = 0; i < N; i++) {
      if (next[i] === 1 && state[i] === 0) {
        const name = zn(Math.floor(i / n), i % n);
        if (!seen.has(name)) {
          seen.add(name);
          events.push({ time: step * DT, kind: 'crossing', target: name });
          if (!initial.has(name) && crossing === null) crossing = step * DT;
        }
      }
    }
    state = next;
    if (!state.includes(1)) for (const l of lines) if (l.on) { l.on = false; events.push({ time: step * DT, kind: 'line_off', target: l.id }); }
    const f = frame();
    frames.push(f);
    if (f.finished) { broke = true; break; }
  }
  if (!broke) step = maxSteps;
  const fin = frame();
  return { frames, waterL: fin.waterL, burnedHa: fin.burnedHa, duration: step * DT, crossingTime: crossing, status: fin.finished ? 'completed' : 'truncated' } satisfies Partial<Run>;
}

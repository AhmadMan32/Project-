import type { Event, Frame, Line, Run, ScenarioInput, Terrain } from '@workspace/api-client-react';

export const DEFAULT_SCENARIO: ScenarioInput = {
  name: 'Marmaris deneme',
  lat: 36.85,
  lon: 28.25,
  widthM: 2000,
  cellSize: 60,
  zones: 3,
  windDir: 270,
  windSpeed: 8,
  moisture: 30,
  strategy: 'smart',
  seed: 1,
  flowLpm: 120,
  fixedDelay: 60,
};

export const STRATEGIES = ['none', 'all', 'fixed', 'adjacent', 'smart'] as const;
export const BURNABLE = new Set([10, 20, 30, 40, 90, 95, 100]);

export const FUEL_NAMES: Record<number, string> = {
  10: 'Tree cover', 20: 'Shrubland', 30: 'Grassland', 40: 'Cropland', 50: 'Built-up',
  60: 'Bare / sparse', 70: 'Snow / ice', 80: 'Water', 90: 'Herbaceous wetland', 95: 'Mangroves', 100: 'Moss / lichen',
};

export const FUEL_NAMES_TR: Record<number, string> = {
  10: 'Ağaç örtüsü (orman)', 20: 'Çalılık / maki', 30: 'Otlak / çayır', 40: 'Tarım alanı', 50: 'Yerleşim (yapılı)',
  60: 'Çıplak / seyrek bitki', 70: 'Kar / buz', 80: 'Su', 90: 'Sulak alan (bataklık)', 95: 'Mangrov', 100: 'Yosun / liken',
};

export const fuelName = (code: number, lang: 'tr' | 'en') =>
  (lang === 'tr' ? FUEL_NAMES_TR : FUEL_NAMES)[code] ?? (lang === 'tr' ? `Sınıf ${code}` : `Class ${code}`);

export type Bounds = [number, number, number, number];

export function areaBounds(lat: number, lon: number, widthM: number): Bounds {
  const dLat = widthM / 2 / 111320;
  const dLon = widthM / 2 / (111320 * Math.cos((lat * Math.PI) / 180));
  return [lon - dLon, lat - dLat, lon + dLon, lat + dLat];
}

export function lngLatToCell(lng: number, lat: number, b: Bounds, rows: number, cols: number) {
  const [w, s, e, n] = b;
  if (lng < w || lng > e || lat < s || lat > n) return null;
  const col = Math.min(cols - 1, Math.floor(((lng - w) / (e - w)) * cols));
  const row = Math.min(rows - 1, Math.floor(((n - lat) / (n - s)) * rows));
  return { row, col };
}

export function cellCenter(row: number, col: number, b: Bounds, rows: number, cols: number): [number, number] {
  const [w, s, e, n] = b;
  return [w + ((col + 0.5) / cols) * (e - w), n - ((row + 0.5) / rows) * (n - s)];
}

export function fmtTime(sec: number) {
  const s = Math.max(0, Math.round(sec));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const r = s % 60;
  const mm = String(m).padStart(2, '0');
  const ss = String(r).padStart(2, '0');
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
}

export const fmtL = (l: number) =>
  l >= 10000 ? `${(l / 1000).toLocaleString(undefined, { maximumFractionDigits: 1 })} m³` : `${Math.round(l).toLocaleString()} L`;
export const fmtHa = (h: number) => `${h.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ha`;

export function compassLabel(deg: number) {
  const n = ['N', 'NNE', 'NE', 'ENE', 'E', 'ESE', 'SE', 'SSE', 'S', 'SSW', 'SW', 'WSW', 'W', 'WNW', 'NW', 'NNW'];
  return n[Math.round((((deg % 360) + 360) % 360) / 22.5) % 16];
}

export type FireStatus = 'idle' | 'fire' | 'extinguished' | 'truncated';

export function fireStatus(run: Run | null, frames: Frame[], idx: number): FireStatus {
  if (!run || frames.length === 0) return 'idle';
  const f = frames[Math.min(idx, frames.length - 1)];
  const burning = f.state.some((v) => v === 1);
  const isLast = idx >= frames.length - 1;
  if (isLast && run.status === 'truncated') return 'truncated';
  if (burning) return 'fire';
  if (f.finished || isLast) return run.status === 'completed' ? 'extinguished' : 'truncated';
  return 'idle';
}

export { SimRenderer } from '@/lib/fire-renderer';

export function zoneRowLetter(i: number) { return String.fromCharCode(65 + i); }

/** Geometry of zone grid lines and sprinkler lines (ids like A1-A2: letter=row, number=col). */
export function zoneGeometry(b: Bounds, n: number) {
  const [w, s, e, nn] = b;
  const lon = (c: number) => w + (c / n) * (e - w);
  const lat = (r: number) => nn - (r / n) * (nn - s);
  const parse = (id: string) => {
    const m = /^([A-Z])(\d+)$/.exec(id.trim());
    return m ? { r: m[1].charCodeAt(0) - 65, c: Number(m[2]) - 1 } : null;
  };
  const segment = (from: string, to: string): [number, number][] | null => {
    const a = parse(from), z = parse(to);
    if (!a || !z) return null;
    if (a.r === z.r && Math.abs(a.c - z.c) === 1) {
      const c = Math.max(a.c, z.c);
      return [[lon(c), lat(a.r)], [lon(c), lat(a.r + 1)]];
    }
    if (a.c === z.c && Math.abs(a.r - z.r) === 1) {
      const r = Math.max(a.r, z.r);
      return [[lon(a.c), lat(r)], [lon(a.c + 1), lat(r)]];
    }
    return null;
  };
  const labels = [] as { id: string; pos: [number, number] }[];
  for (let r = 0; r < n; r++)
    for (let c = 0; c < n; c++)
      labels.push({ id: `${zoneRowLetter(r)}${c + 1}`, pos: [lon(c + 0.5), lat(r + 0.5)] });
  const grid: [number, number][][] = [];
  for (let i = 1; i < n; i++) {
    grid.push([[lon(i), nn], [lon(i), s]]);
    grid.push([[w, lat(i)], [e, lat(i)]]);
  }
  return { segment, labels, grid };
}

/** Zone id (e.g. "B2") of a terrain cell. Mirrors the backend's zs = rows // zones block layout. */
export function zoneIdAt(row: number, col: number, rows: number, zones: number) {
  const zs = Math.max(1, Math.floor(rows / zones));
  const r = Math.min(zones - 1, Math.floor(row / zs));
  const c = Math.min(zones - 1, Math.floor(col / zs));
  return `${zoneRowLetter(r)}${c + 1}`;
}

export type ZoneClass = { code: number; name: string; pct: number; cells: number; burnable: boolean };

export type ZoneDetail = {
  id: string;
  cells: number;
  side: number;          // cells per zone side
  areaHa: number;
  centerLat: number;
  centerLon: number;
  classes: ZoneClass[];  // all land-cover classes in the zone, largest first
  burnablePct: number;
  elevMin: number;
  elevMax: number;
  elevMean: number;
  slopeMean: number;
  slopeMax: number;
  aspectDeg: number | null;
  aspectLabel: string | null;
  sim: null | {
    state: string;
    burnedPct: number;
    burningCells: number;
    burnedCells: number;
    wetCells: number;
    wetPct: number;
    waterL: number;
    lines: Line[];
    events: Event[];
  };
};

/** Everything known about one zone: land cover, terrain, and (when a frame is given) the live simulation state. */
export function zoneDetails(terrain: Terrain, zones: number, id: string, frame: Frame | null, lang: 'tr' | 'en'): ZoneDetail | null {
  const m = /^([A-Z])(\d+)$/.exec(id);
  if (!m) return null;
  const zr = m[1].charCodeAt(0) - 65;
  const zc = Number(m[2]) - 1;
  const rows = terrain.rows, cols = terrain.cols;
  const zs = Math.max(1, Math.floor(rows / zones));
  if (zr >= zones || zc >= zones) return null;
  const r0 = zr * zs, c0 = zc * zs;
  const [w, s, e, n] = terrain.bounds;
  const cellsIdx: number[] = [];
  for (let r = r0; r < r0 + zs && r < rows; r++)
    for (let c = c0; c < c0 + zs && c < cols; c++) cellsIdx.push(r * cols + c);
  const total = cellsIdx.length || 1;

  const counts = new Map<number, number>();
  let burnable = 0, emin = Infinity, emax = -Infinity, esum = 0, ssum = 0, smax = 0;
  let sx = 0, sy = 0;
  for (const i of cellsIdx) {
    const f = terrain.fuel[i];
    counts.set(f, (counts.get(f) ?? 0) + 1);
    if (BURNABLE.has(f)) burnable++;
    const el = terrain.elevation[i];
    emin = Math.min(emin, el); emax = Math.max(emax, el); esum += el;
    const sl = terrain.slope[i] ?? 0;
    ssum += sl; smax = Math.max(smax, sl);
    // Aspect is averaged as a vector, weighted by slope, so 359° and 1° do not cancel out.
    const asp = ((terrain.aspect[i] ?? 0) * Math.PI) / 180;
    const wgt = Math.max(sl, 0.0001);
    sx += Math.sin(asp) * wgt; sy += Math.cos(asp) * wgt;
  }
  const classes: ZoneClass[] = [...counts.entries()]
    .map(([code, cells]) => ({ code, name: fuelName(code, lang), pct: (cells / total) * 100, cells, burnable: BURNABLE.has(code) }))
    .sort((a, b) => b.cells - a.cells);

  const aspectDeg = Math.hypot(sx, sy) > 1e-9 ? ((Math.atan2(sx, sy) * 180) / Math.PI + 360) % 360 : null;
  const cellM = terrain.cellSize;
  const midRow = r0 + zs / 2, midCol = c0 + zs / 2;
  const centerLon = w + (midCol / cols) * (e - w);
  const centerLat = n - (midRow / rows) * (n - s);

  let sim: ZoneDetail['sim'] = null;
  if (frame) {
    const zone = frame.zones.find((z) => z.id === id);
    let burning = 0, burned = 0, wet = 0;
    for (const i of cellsIdx) {
      if (frame.state[i] === 1) burning++;
      else if (frame.state[i] === 2) burned++;
      if ((frame.wet[i] ?? 0) > 0) wet++;
    }
    const lines = frame.lines.filter((l) => l.from === id || l.to === id);
    const events = frame.events
      .filter((ev) => ev.target === id || ev.target.split('-').includes(id))
      .slice(-30);
    sim = {
      state: zone?.state ?? 'safe',
      burnedPct: zone?.burnedPct ?? ((burned + burning) / total) * 100,
      burningCells: burning,
      burnedCells: burned,
      wetCells: wet,
      wetPct: (wet / total) * 100,
      waterL: lines.reduce((acc, l) => acc + l.waterL, 0),
      lines,
      events,
    };
  }

  return {
    id,
    cells: total,
    side: zs,
    areaHa: (total * cellM * cellM) / 10000,
    centerLat,
    centerLon,
    classes,
    burnablePct: (burnable / total) * 100,
    elevMin: emin,
    elevMax: emax,
    elevMean: esum / total,
    slopeMean: ssum / total,
    slopeMax: smax,
    aspectDeg,
    aspectLabel: aspectDeg == null ? null : compassLabel(aspectDeg),
    sim,
  };
}

export function errMsg(e: unknown): string {
  if (!e) return 'Unknown error';
  const anyE = e as { data?: { detail?: unknown }; message?: string };
  const det = anyE.data?.detail;
  if (typeof det === 'string') return det;
  if (Array.isArray(det)) return det.map((x) => (x as { msg?: string }).msg ?? JSON.stringify(x)).join('; ');
  return anyE.message ?? String(e);
}

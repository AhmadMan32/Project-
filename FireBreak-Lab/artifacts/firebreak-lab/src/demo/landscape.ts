import type { ScenarioInput, Terrain } from '@workspace/api-client-react';
import type { Bounds } from '@/lib/sim';

/**
 * Synthetic Mediterranean landscape for the offline demo. It is a continuous function of
 * longitude/latitude, so the terrain grid and the painted basemap always agree.
 * It is NOT real data: the demo labels it as synthetic everywhere it appears.
 */

const M_PER_DEG = 111320;

function h2(x: number, y: number, s: number) {
  let h = (Math.imul(x | 0, 374761393) + Math.imul(y | 0, 668265263) + Math.imul(s, 2246822519)) | 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  return ((h ^ (h >>> 16)) >>> 0) / 4294967296;
}

function vnoise(x: number, y: number, s: number) {
  const xi = Math.floor(x), yi = Math.floor(y);
  const xf = x - xi, yf = y - yi;
  const u = xf * xf * (3 - 2 * xf), v = yf * yf * (3 - 2 * yf);
  const a = h2(xi, yi, s), b = h2(xi + 1, yi, s), c = h2(xi, yi + 1, s), d = h2(xi + 1, yi + 1, s);
  return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v;
}

function fbm(x: number, y: number, s: number, oct: number) {
  let sum = 0, amp = 0.5, f = 1, norm = 0;
  for (let o = 0; o < oct; o++) { sum += vnoise(x * f, y * f, s + o * 17) * amp; norm += amp; amp *= 0.5; f *= 2.03; }
  return sum / norm;
}

/** Local metric coordinates (a fixed reference latitude keeps the field continuous). */
const toXY = (lon: number, lat: number) => [lon * M_PER_DEG * 0.8, lat * M_PER_DEG] as const;

export function elevationAt(x: number, y: number) {
  const ridge = 1 - Math.abs(fbm(x / 3400, y / 3400, 11, 4) * 2 - 1);
  return 120 + 620 * fbm(x / 2600, y / 2600, 3, 5) + 260 * ridge * ridge;
}

function riverAt(x: number, y: number) {
  return Math.abs(fbm(x / 2900 + 40, y / 2900 - 13, 29, 4) - 0.5);
}

/** ESA WorldCover-style class code at a point. */
export function landCoverAt(x: number, y: number, e = elevationAt(x, y)) {
  if (riverAt(x, y) < 0.0065) return 80;
  const m = fbm(x / 1100 + 7, y / 1100 + 3, 41, 4);
  const d = fbm(x / 260, y / 260, 53, 3);
  if (e > 830 && d > 0.55) return 60;
  if (e < 420 && fbm(x / 1700 + 90, y / 1700, 61, 3) > 0.7) return d > 0.45 ? 50 : 40;
  if (e < 330 && m < 0.47) return 40;
  if (m > 0.53) return 10;
  if (m > 0.36) return 20;
  return 30;
}

export function demoGeometry(s: ScenarioInput) {
  const n = Math.max(s.zones * 2, Math.round(s.widthM / s.cellSize / s.zones) * s.zones);
  const cell = s.widthM / n;
  const dy = s.widthM / M_PER_DEG / 2;
  const dx = dy / Math.cos((s.lat * Math.PI) / 180);
  return { n, cell, bounds: [s.lon - dx, s.lat - dy, s.lon + dx, s.lat + dy] as Bounds };
}

export const terrainIdentity = (s: Pick<ScenarioInput, 'lat' | 'lon' | 'widthM' | 'cellSize' | 'zones'>) =>
  'demo-' + JSON.stringify([s.lat, s.lon, s.widthM, s.cellSize, s.zones]);

export function demoTerrain(s: ScenarioInput): Terrain {
  const { n, cell, bounds } = demoGeometry(s);
  const [w, so, e, no] = bounds;
  const fuel: number[] = [], elevation: number[] = [];
  for (let r = 0; r < n; r++)
    for (let c = 0; c < n; c++) {
      const [x, y] = toXY(w + ((c + 0.5) / n) * (e - w), no - ((r + 0.5) / n) * (no - so));
      const el = elevationAt(x, y);
      elevation.push(Math.round(el * 100) / 100);
      fuel.push(landCoverAt(x, y, el));
    }
  // Same finite differences as numpy.gradient on the server: rows run north → south.
  const slope: number[] = [], aspect: number[] = [];
  const E = (r: number, c: number) => elevation[Math.min(n - 1, Math.max(0, r)) * n + Math.min(n - 1, Math.max(0, c))];
  for (let r = 0; r < n; r++)
    for (let c = 0; c < n; c++) {
      const gx = (E(r, c + 1) - E(r, c - 1)) / (cell * (c === 0 || c === n - 1 ? 1 : 2));
      const gy = (E(r + 1, c) - E(r - 1, c)) / (cell * (r === 0 || r === n - 1 ? 1 : 2));
      slope.push(Math.round(((Math.atan(Math.hypot(gx, gy)) * 180) / Math.PI) * 100) / 100);
      aspect.push(Math.round((((Math.atan2(-gx, gy) * 180) / Math.PI + 360) % 360) * 100) / 100);
    }
  return {
    id: terrainIdentity(s), rows: n, cols: n, cellSize: cell, bounds, fuel, elevation, slope, aspect,
    source: 'DEMO · sentetik arazi / synthetic terrain (gerçek veri değil)', fetchedAt: new Date().toISOString(),
  };
}

const PALETTE: Record<number, [number, number, number]> = {
  10: [36, 62, 32], 20: [74, 86, 48], 30: [124, 120, 80], 40: [120, 118, 70],
  50: [150, 142, 132], 60: [138, 126, 108], 80: [28, 52, 64],
};

/** Paints a satellite-like basemap (hillshade + land cover + canopy texture) as a PNG data URL. */
export function demoBasemap(lat: number, lon: number, extentM: number, px = 720): { url: string; bounds: Bounds } {
  const dy = extentM / M_PER_DEG / 2;
  const dx = dy / Math.cos((lat * Math.PI) / 180);
  const bounds: Bounds = [lon - dx, lat - dy, lon + dx, lat + dy];
  const cv = document.createElement('canvas');
  cv.width = cv.height = px;
  const g = cv.getContext('2d')!;
  const img = g.createImageData(px, px);
  const d = img.data;
  const mpp = extentM / px;
  const [x0, y1] = toXY(bounds[0], bounds[3]);
  // elevation on a coarse lattice, then bilinear: hillshade stays smooth and the paint stays fast
  const step = 4, gw = Math.ceil(px / step) + 2;
  const el = new Float32Array(gw * gw);
  for (let j = 0; j < gw; j++) for (let i = 0; i < gw; i++) el[j * gw + i] = elevationAt(x0 + i * step * mpp, y1 - j * step * mpp);
  const elev = (px_: number, py_: number) => {
    const fx = px_ / step, fy = py_ / step;
    const i = Math.min(gw - 2, Math.floor(fx)), j = Math.min(gw - 2, Math.floor(fy));
    const u = fx - i, v = fy - j;
    const a = el[j * gw + i], b = el[j * gw + i + 1], c = el[(j + 1) * gw + i], e = el[(j + 1) * gw + i + 1];
    return a + (b - a) * u + (c - a) * v + (a - b - c + e) * u * v;
  };
  for (let py = 0; py < px; py++) {
    for (let pxl = 0; pxl < px; pxl++) {
      const x = x0 + pxl * mpp, y = y1 - py * mpp;
      const e = elev(pxl, py);
      const cls = landCoverAt(x, y, e);
      let [R, G, B] = PALETTE[cls] ?? PALETTE[30];
      const fine = h2(Math.floor(x / 6), Math.floor(y / 6), 5);
      if (cls === 10 || cls === 20) {
        const crown = vnoise(x / 9, y / 9, 7);
        const k = 0.65 + crown * 0.6 + (fine - 0.5) * 0.15;
        R *= k; G *= k; B *= k;
      } else if (cls === 40) {
        const f = h2(Math.floor(x / 140), Math.floor(y / 95), 9);
        R = 105 + f * 70; G = 108 + f * 40; B = 62 + f * 20;
        if ((Math.abs(x % 140) < 3) || (Math.abs(y % 95) < 3)) { R *= 0.8; G *= 0.8; B *= 0.8; }
      } else if (cls === 50) {
        const roof = h2(Math.floor(x / 14), Math.floor(y / 12), 13);
        if (roof > 0.55) { R = 170 + roof * 40; G = 92 + roof * 30; B = 70; } else { R = 120 + fine * 40; G = R - 4; B = R - 10; }
      } else if (cls === 80) {
        const k = 0.9 + vnoise(x / 30, y / 30, 3) * 0.2;
        R *= k; G *= k; B *= k;
      } else {
        const k = 0.88 + fine * 0.24;
        R *= k; G *= k; B *= k;
      }
      // hillshade, light from the north-west
      const sx = (elev(pxl + 1, py) - elev(pxl - 1 < 0 ? 0 : pxl - 1, py)) / (2 * mpp);
      const sy = (elev(pxl, py + 1) - elev(pxl, py - 1 < 0 ? 0 : py - 1)) / (2 * mpp);
      const shade = Math.max(0.45, Math.min(1.35, 1 + (sx + sy) * 1.1));
      const o = (py * px + pxl) * 4;
      d[o] = R * shade; d[o + 1] = G * shade; d[o + 2] = B * shade; d[o + 3] = 255;
    }
  }
  g.putImageData(img, 0, 0);
  return { url: cv.toDataURL('image/jpeg', 0.86), bounds };
}

import type { ScenarioInput, Terrain } from '@workspace/api-client-react';
import type { Bounds } from '@/lib/sim';
import { fromUrl } from 'geotiff';
import { LIVE_MAP } from '@/demo/flag';

/**
 * Real open data bundled with the offline demo (demo-public/demo-data):
 * - ESA WorldCover 2021 v200 class rasters cut on the product's own 10 m grid (no resampling), ~12 km around each place,
 * - the AWS/Mapzen Terrarium z12 PNG tiles that cover the same square.
 * Terrain is sampled from them exactly the way artifacts/api-server/firebreak/terrain.py does
 * (the 10 m pixel under each cell centre for land cover, nearest tile pixel for elevation, numpy-style gradients).
 * The server reads WorldCover through GDAL, which may use the COG's overviews, so a few cells can differ.
 * Outside the bundled squares the demo refuses, like the server does when providers fail.
 *
 * The web build (LIVE_MAP) does not use the bundle: it reads the same open data straight from the providers in the
 * browser, for any place, the way terrain.py does on the server: the WorldCover COG window through Planetary Computer
 * (STAC search + SAS token) and the Terrarium tiles from AWS. All of them allow cross-origin reads.
 */

export const SOURCE = 'ESA WorldCover 2021 v200 (10 m; nearest-cell sampling) · AWS/Mapzen Terrarium DEM z12';
const M_PER_DEG = 111320;
const BASE = 'demo-data/';

type Grid = { x0: number; y0: number; dx: number; dy: number; w: number; h: number };
export type Place = { key: string; name: string; lat: number; lon: number; bounds: Bounds; grid: Grid; worldcover: string[]; demTiles: [number, number, number][] };
type Raster = { grid: Grid; data: ArrayLike<number> };
type Loaded = { grids: Raster[]; tiles: Map<string, Float64Array> };

let placesP: Promise<Place[]> | null = null;
export const places = () => (placesP ??= fetch(BASE + 'places.json').then((r) => r.json() as Promise<Place[]>));

async function pixels(url: string) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`${res.status} ${url}`);
  const blob = await res.blob();
  // No colour management: class codes and Terrarium RGB must arrive byte-exact.
  const bmp = await createImageBitmap(blob, { colorSpaceConversion: 'none', premultiplyAlpha: 'none' });
  const c = document.createElement('canvas');
  c.width = bmp.width; c.height = bmp.height;
  const g = c.getContext('2d', { willReadFrequently: true })!;
  g.drawImage(bmp, 0, 0);
  return { w: bmp.width, h: bmp.height, data: g.getImageData(0, 0, bmp.width, bmp.height).data };
}

const terrarium = (d: Uint8ClampedArray) => {
  const e = new Float64Array(256 * 256);
  for (let i = 0; i < e.length; i++) e[i] = d[i * 4] * 256 + d[i * 4 + 1] + d[i * 4 + 2] / 256 - 32768;
  return e;
};

const loaded = new Map<string, Promise<Loaded>>();
function load(place: Place): Promise<Loaded> {
  let p = loaded.get(place.key);
  if (!p) {
    p = (async () => {
      const wcPx = await pixels(`${BASE}wc-${place.key}.png`);
      const wc = new Uint8Array(wcPx.w * wcPx.h);
      for (let i = 0; i < wc.length; i++) wc[i] = wcPx.data[i * 4];
      const tiles = new Map<string, Float64Array>();
      await Promise.all(place.demTiles.map(async ([z, x, y]) => {
        tiles.set(`${z}/${x}/${y}`, terrarium((await pixels(`${BASE}dem-${z}-${x}-${y}.png`)).data));
      }));
      return { grids: [{ grid: place.grid, data: wc }], tiles };
    })();
    loaded.set(place.key, p);
  }
  return p;
}

async function fetchRetry(url: string, tries = 3): Promise<Response> {
  for (let k = 0; ; k++) {
    const r = await fetch(url);
    if (r.ok || r.status < 500 || k >= tries - 1) return r;
    await new Promise((z) => setTimeout(z, 1000 * (k + 1)));
  }
}

const tileX = (lon: number, z: number) => ((lon + 180) / 360) * 2 ** z;
const tileY = (lat: number, z: number) => ((1 - Math.asinh(Math.tan((lat * Math.PI) / 180)) / Math.PI) / 2) * 2 ** z;

/** Live read of the providers for any study area (web build). Mirrors terrain.load() on the server. */
const online = new Map<string, Promise<Loaded>>();
function loadOnline(b: Bounds): Promise<Loaded> {
  const key = b.join(',');
  let p = online.get(key);
  if (!p) {
    p = (async () => {
      const [w, s, e, n] = b;
      const stac = await fetchRetry(`https://planetarycomputer.microsoft.com/api/stac/v1/search?collections=esa-worldcover&bbox=${w},${s},${e},${n}&limit=20`);
      if (!stac.ok) throw new Error(`ESA WorldCover search failed (${stac.status}).`);
      const items = ((await stac.json()).features as { id: string; assets: { map: { href: string } } }[]).filter((f) => f.id.includes('2021'));
      if (!items.length) throw new Error('ESA WorldCover 2021 coverage is unavailable for this area.');
      const tokRes = await fetchRetry('https://planetarycomputer.microsoft.com/api/sas/v1/token/esa-worldcover');
      if (!tokRes.ok) throw new Error(`Planetary Computer token failed (${tokRes.status}).`);
      const token = (await tokRes.json()).token as string;
      const grids = (await Promise.all(items.map(async (it): Promise<Raster | null> => {
        // Only the window around the study area is fetched, with HTTP range requests on the cloud-optimised GeoTIFF.
        const img = await (await fromUrl(`${it.assets.map.href}?${token}`)).getImage();
        const [ox, oy] = img.getOrigin();
        const [rx, ry] = img.getResolution();
        const W = img.getWidth(), H = img.getHeight();
        const c0 = Math.max(0, Math.floor((w - ox) / rx) - 1), c1 = Math.min(W, Math.ceil((e - ox) / rx) + 1);
        const r0 = Math.max(0, Math.floor((n - oy) / ry) - 1), r1 = Math.min(H, Math.ceil((s - oy) / ry) + 1);
        if (c1 <= c0 || r1 <= r0) return null;
        const bands = (await img.readRasters({ window: [c0, r0, c1, r1], samples: [0] })) as unknown as ArrayLike<number>[];
        return { grid: { x0: ox + c0 * rx, y0: oy + r0 * ry, dx: rx, dy: -ry, w: c1 - c0, h: r1 - r0 }, data: bands[0] };
      }))).filter((g): g is Raster => g !== null);
      const z = 12, tiles = new Map<string, Float64Array>();
      const jobs: Promise<void>[] = [];
      for (let x = Math.floor(tileX(w, z)); x <= Math.floor(tileX(e, z)); x++)
        for (let y = Math.floor(tileY(n, z)); y <= Math.floor(tileY(s, z)); y++)
          jobs.push(pixels(`https://s3.amazonaws.com/elevation-tiles-prod/terrarium/${z}/${x}/${y}.png`).then((t) => { tiles.set(`${z}/${x}/${y}`, terrarium(t.data)); }));
      await Promise.all(jobs);
      return { grids, tiles };
    })();
    p.catch(() => online.delete(key));
    online.set(key, p);
  }
  return p;
}

/** Bundled place whose square fully contains these bounds (or contains the point, for the basemap). */
export async function placeFor(b: Bounds) {
  return (await places()).find((p) => b[0] >= p.bounds[0] && b[1] >= p.bounds[1] && b[2] <= p.bounds[2] && b[3] <= p.bounds[3]) ?? null;
}

export function geometry(s: ScenarioInput) {
  const n = Math.max(s.zones * 2, Math.round(s.widthM / s.cellSize / s.zones) * s.zones);
  const cell = s.widthM / n;
  const dy = s.widthM / M_PER_DEG / 2;
  const dx = dy / Math.cos((s.lat * Math.PI) / 180);
  return { n, cell, bounds: [s.lon - dx, s.lat - dy, s.lon + dx, s.lat + dy] as Bounds };
}

export const terrainIdentity = (s: Pick<ScenarioInput, 'lat' | 'lon' | 'widthM' | 'cellSize' | 'zones'>) =>
  'demo-' + JSON.stringify([s.lat, s.lon, s.widthM, s.cellSize, s.zones]);

function wcAt(L: Loaded, lon: number, lat: number) {
  for (const { grid: g, data } of L.grids) {
    const c = Math.floor((lon - g.x0) / g.dx), r = Math.floor((g.y0 - lat) / g.dy);
    if (c >= 0 && r >= 0 && c < g.w && r < g.h && data[r * g.w + c]) return data[r * g.w + c];
  }
  return 0;
}

/** Same as sample_dem() in terrain.py: zoom 12, nearest pixel of the Terrarium tile. */
function demAt(L: Loaded, lon: number, lat: number) {
  const z = 12;
  const tx = ((lon + 180) / 360) * 2 ** z;
  const ty = ((1 - Math.asinh(Math.tan((lat * Math.PI) / 180)) / Math.PI) / 2) * 2 ** z;
  const t = L.tiles.get(`${z}/${Math.floor(tx)}/${Math.floor(ty)}`);
  if (!t) return NaN;
  return t[Math.min(255, Math.floor((ty % 1) * 256)) * 256 + Math.min(255, Math.floor((tx % 1) * 256))];
}

/** Bilinear Terrarium elevation, for the painted relief only (the model uses demAt). */
function demSmooth(L: Loaded, lon: number, lat: number) {
  const z = 12;
  const fx = ((lon + 180) / 360) * 2 ** z * 256 - 0.5;
  const fy = ((1 - Math.asinh(Math.tan((lat * Math.PI) / 180)) / Math.PI) / 2) * 2 ** z * 256 - 0.5;
  const px = (X: number, Y: number) => {
    const t = L.tiles.get(`${z}/${Math.floor(X / 256)}/${Math.floor(Y / 256)}`);
    return t ? t[(Y % 256) * 256 + (X % 256)] : NaN;
  };
  const x0 = Math.floor(fx), y0 = Math.floor(fy), u = fx - x0, v = fy - y0;
  const a = px(x0, y0), b = px(x0 + 1, y0), c = px(x0, y0 + 1), d = px(x0 + 1, y0 + 1);
  return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v;
}

export async function realTerrain(s: ScenarioInput): Promise<Terrain> {
  const { n, cell, bounds } = geometry(s);
  let L: Loaded;
  if (LIVE_MAP) {
    try { L = await loadOnline(bounds); } catch (e) {
      throw new Error(`Gerçek arazi verisi alınamadı (${(e as Error).message}). İnternet bağlantısını kontrol edip tekrar deneyin. Yapay veri kullanılmadı.`);
    }
  } else {
    const place = await placeFor(bounds);
    if (!place) {
      const names = (await places()).map((p) => p.name.split(',')[0]).join(', ');
      throw new Error(`Bu çevrimdışı demo yalnızca şu bölgelerin gerçek verisini içerir: ${names}. Alanı bu bölgelerden birine taşıyın. Yapay veri kullanılmadı.`);
    }
    L = await load(place);
  }
  const [w, so, e, no] = bounds;
  const fuel: number[] = [], raw: number[] = [];
  for (let r = 0; r < n; r++)
    for (let c = 0; c < n; c++) {
      const lon = w + ((c + 0.5) / n) * (e - w), lat = no - ((r + 0.5) / n) * (no - so);
      const f = wcAt(L, lon, lat);
      if (!f) throw new Error('WorldCover contains missing cells; choose another study area.');
      fuel.push(f);
      raw.push(demAt(L, lon, lat));
    }
  const elevation = raw.map((v) => Math.round(v * 100) / 100);
  // numpy.gradient(elevation, cell): central differences inside, one-sided at the edges; rows run north → south.
  const slope: number[] = [], aspect: number[] = [];
  const E = (r: number, c: number) => raw[Math.min(n - 1, Math.max(0, r)) * n + Math.min(n - 1, Math.max(0, c))];
  for (let r = 0; r < n; r++)
    for (let c = 0; c < n; c++) {
      const gx = (E(r, c + 1) - E(r, c - 1)) / (cell * (c === 0 || c === n - 1 ? 1 : 2));
      const gy = (E(r + 1, c) - E(r - 1, c)) / (cell * (r === 0 || r === n - 1 ? 1 : 2));
      slope.push(Math.round(((Math.atan(Math.hypot(gx, gy)) * 180) / Math.PI) * 100) / 100);
      aspect.push(Math.round((((Math.atan2(-gx, gy) * 180) / Math.PI + 360) % 360) * 100) / 100);
    }
  return { id: terrainIdentity(s), rows: n, cols: n, cellSize: cell, bounds, fuel, elevation, slope, aspect, source: SOURCE, fetchedAt: new Date().toISOString() };
}

/** Natural tones per WorldCover class for the painted basemap. */
const TONE: Record<number, [number, number, number]> = {
  10: [38, 66, 34], 20: [86, 96, 56], 30: [138, 132, 88], 40: [128, 122, 74], 50: [148, 138, 128],
  60: [146, 134, 114], 70: [236, 238, 240], 80: [24, 54, 78], 90: [52, 96, 88], 95: [34, 86, 62], 100: [150, 146, 110],
};

function h2(x: number, y: number) {
  let h = (Math.imul(x | 0, 374761393) + Math.imul(y | 0, 668265263)) | 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  return ((h ^ (h >>> 16)) >>> 0) / 4294967296;
}

const basemaps = new Map<string, Promise<{ url: string; bounds: Bounds }>>();

/**
 * Basemap painted from the real WorldCover classes and Terrarium relief (it is a map, not satellite imagery).
 * Returns null when the point is outside every bundled place.
 */
export async function realBasemap(lat: number, lon: number) {
  const place = await placeFor([lon, lat, lon, lat]);
  if (!place) return null;
  let p = basemaps.get(place.key);
  if (!p) {
    p = (async () => {
      const L = await load(place);
      const G = place.grid, W = G.w, H = G.h, wc = L.grids[0].data;
      // elevation on a 4 px lattice, bilinear in between, so the relief is smooth
      const step = 4, gw = Math.ceil(W / step) + 2, gh = Math.ceil(H / step) + 2;
      const el = new Float32Array(gw * gh);
      for (let j = 0; j < gh; j++)
        for (let i = 0; i < gw; i++) {
          const v = demSmooth(L, G.x0 + i * step * G.dx, G.y0 - j * step * G.dy);
          el[j * gw + i] = Number.isFinite(v) ? v : 0;
        }
      const elev = (x: number, y: number) => {
        const fx = Math.max(0, x) / step, fy = Math.max(0, y) / step;
        const i = Math.min(gw - 2, Math.floor(fx)), j = Math.min(gh - 2, Math.floor(fy));
        const u = fx - i, v = fy - j;
        const a = el[j * gw + i], b = el[j * gw + i + 1], c = el[(j + 1) * gw + i], d = el[(j + 1) * gw + i + 1];
        return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v;
      };
      const mx = G.dx * M_PER_DEG * Math.cos((place.lat * Math.PI) / 180), my = G.dy * M_PER_DEG;
      const cv = document.createElement('canvas');
      cv.width = W; cv.height = H;
      const g = cv.getContext('2d')!;
      const img = g.createImageData(W, H);
      const d = img.data;
      for (let y = 0; y < H; y++)
        for (let x = 0; x < W; x++) {
          const cls = wc[y * W + x];
          let [R, G, B] = TONE[cls] ?? [90, 90, 90];
          const k = 0.86 + h2(x, y) * 0.28;
          R *= k; G *= k; B *= k;
          if (cls !== 80) {
            const sx = (elev(x + 1, y) - elev(x - 1, y)) / (2 * mx);
            const sy = (elev(x, y + 1) - elev(x, y - 1)) / (2 * my);
            const shade = Math.max(0.5, Math.min(1.35, 1 + (sx + sy) * 1.1));
            R *= shade; G *= shade; B *= shade;
          }
          const o = (y * W + x) * 4;
          d[o] = R; d[o + 1] = G; d[o + 2] = B; d[o + 3] = 255;
        }
      g.putImageData(img, 0, 0);
      return { url: cv.toDataURL('image/jpeg', 0.88), bounds: place.bounds };
    })();
    basemaps.set(place.key, p);
  }
  return p;
}

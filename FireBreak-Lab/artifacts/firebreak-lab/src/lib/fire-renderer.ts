import type { Frame } from '@workspace/api-client-react';

/**
 * Visual-only fire renderer. It paints the simulation raster onto one shared canvas that both
 * the 3D (MapLibre) and 2D (Leaflet) views display directly.
 *
 * Nothing here changes simulation results. Everything visual is derived from the saved frames:
 * - each cell's ignition and burn-out step is read once from the frame history, then jittered
 *   within its step so the fire front creeps smoothly instead of jumping 10 s at a time;
 * - flames, glowing embers, smoke, sparks, sprinkler mist and steam are drawn on top in a
 *   continuous animation loop that only runs while something on screen is moving.
 */

const BURNABLE = new Set([10, 20, 30, 40, 90, 95, 100]);
const NEVER = 1e9;

/** Faint land-cover tints (RGB) so the satellite image stays visible under the fuel map. */
const FUEL_TINT: Record<number, [number, number, number]> = {
  10: [38, 92, 48],   // tree cover
  20: [96, 118, 54],  // shrubland / maquis
  30: [146, 158, 78], // grassland
  40: [168, 146, 76], // cropland
  90: [62, 118, 108], // wetland
  95: [40, 104, 84],  // mangroves
  100: [120, 132, 96], // moss / lichen
};
const NONFUEL: [number, number, number] = [18, 28, 36];
const CHAR: [number, number, number] = [22, 19, 17];
const ASH: [number, number, number] = [84, 78, 72];
const EMBER: [number, number, number] = [214, 64, 14];
const FIRE_BED: [number, number, number] = [120, 28, 8];
const WET: [number, number, number] = [30, 84, 138];

export type Scene = {
  rows: number;
  cols: number;
  fuel?: number[];
  frames: Frame[];
  zones: number;
};

type Particle = {
  x: number; y: number; vx: number; vy: number;
  life: number; max: number; size: number; grow: number; seed: number;
};

/** Deterministic per-cell noise in [0, 1). */
function hash(i: number, salt = 0) {
  let h = (i * 374761393 + salt * 668265263) | 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  h ^= h >>> 16;
  return (h >>> 0) / 4294967296;
}

const smooth = (a: number, b: number, x: number) => {
  const t = Math.min(1, Math.max(0, (x - a) / (b - a)));
  return t * t * (3 - 2 * t);
};

function sprite(size: number, stops: [number, string][]) {
  const c = document.createElement('canvas');
  c.width = c.height = size;
  const g = c.getContext('2d')!;
  const grad = g.createRadialGradient(size / 2, size / 2, 0, size / 2, size / 2, size / 2);
  for (const [o, col] of stops) grad.addColorStop(o, col);
  g.fillStyle = grad;
  g.fillRect(0, 0, size, size);
  return c;
}

const reducedMotion = () => {
  try { return window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false; } catch { return false; }
};

export class SimRenderer {
  readonly canvas = document.createElement('canvas');
  private base = document.createElement('canvas');
  private small = document.createElement('canvas');
  private img: ImageData | null = null;
  private listeners = new Set<() => void>();

  private scene: Scene | null = null;
  private ignAt = new Float32Array(0);  // fractional frame index where a cell starts burning
  private outAt = new Float32Array(0);  // fractional frame index where it burns out
  private pos = 0;
  private baseDirty = true;
  private scale = 8;
  private sub = 1;

  private windDir = 270;
  private windSpeed = 8;
  private fx = true;

  // Cells that are visible as fire / embers at the current position.
  private burning: number[] = [];
  private burnI: number[] = [];
  private embers: number[] = [];
  private emberI: number[] = [];
  private wetCells: number[] = [];
  private wetW: number[] = [];
  private steamCells: number[] = [];
  private activeLines: { x0: number; y0: number; x1: number; y1: number }[] = [];

  private smoke: Particle[] = [];
  private sparks: Particle[] = [];
  private mist: Particle[] = [];
  private steam: Particle[] = [];

  private raf = 0;
  private last = 0;
  private reveal = 1;
  private revealKey = '';

  private sFlame = sprite(64, [[0, 'rgba(255,250,228,1)'], [0.18, 'rgba(255,214,110,0.95)'], [0.42, 'rgba(255,124,32,0.7)'], [0.7, 'rgba(200,42,10,0.28)'], [1, 'rgba(120,16,0,0)']]);
  private sGlow = sprite(64, [[0, 'rgba(255,140,40,0.55)'], [0.45, 'rgba(255,80,16,0.18)'], [1, 'rgba(255,60,0,0)']]);
  private sEmber = sprite(32, [[0, 'rgba(255,170,70,0.9)'], [0.4, 'rgba(220,60,12,0.45)'], [1, 'rgba(140,20,0,0)']]);
  private sSmoke = sprite(64, [[0, 'rgba(176,170,162,0.62)'], [0.45, 'rgba(150,144,138,0.34)'], [1, 'rgba(130,124,118,0)']]);
  private sSmokeDark = sprite(64, [[0, 'rgba(58,52,48,0.6)'], [0.5, 'rgba(66,60,56,0.3)'], [1, 'rgba(70,64,60,0)']]);
  private sSteam = sprite(48, [[0, 'rgba(236,244,250,0.7)'], [0.5, 'rgba(220,234,244,0.3)'], [1, 'rgba(220,234,244,0)']]);
  private sMist = sprite(24, [[0, 'rgba(200,232,255,0.9)'], [0.5, 'rgba(120,190,255,0.35)'], [1, 'rgba(90,170,255,0)']]);
  private sSpark = sprite(16, [[0, 'rgba(255,248,210,1)'], [0.4, 'rgba(255,170,60,0.8)'], [1, 'rgba(255,90,0,0)']]);

  onPaint(fn: () => void) {
    this.listeners.add(fn);
    return () => { this.listeners.delete(fn); };
  }

  private emit() {
    this.listeners.forEach((fn) => fn());
  }

  /** True while the animation loop is running (fire, smoke, water or a reveal is on screen). */
  get animating() { return this.raf !== 0; }

  setWind(dir: number, speed: number) {
    this.windDir = dir;
    this.windSpeed = speed;
  }

  setEffects(on: boolean) {
    this.fx = on;
    if (!on) { this.smoke = []; this.sparks = []; this.mist = []; this.steam = []; }
    this.kick();
  }

  clear() {
    this.scene = null;
    this.smoke = []; this.sparks = []; this.mist = []; this.steam = [];
    this.stop();
    this.canvas.getContext('2d')?.clearRect(0, 0, this.canvas.width, this.canvas.height);
    this.emit();
  }

  setScene(s: Scene | null) {
    if (!s || s.rows <= 0 || s.cols <= 0) { this.clear(); return; }
    const prev = this.scene;
    const sameGrid = prev && prev.rows === s.rows && prev.cols === s.cols;
    if (prev && sameGrid && prev.fuel === s.fuel && prev.frames === s.frames && prev.zones === s.zones) return;
    this.scene = s;
    const n = s.rows * s.cols;

    // Reading the history once gives every cell its own ignition / burn-out time.
    this.ignAt = new Float32Array(n).fill(NEVER);
    this.outAt = new Float32Array(n).fill(NEVER);
    s.frames.forEach((f, k) => {
      const st = f.state;
      for (let i = 0; i < n; i++) {
        const v = st[i];
        if (v === 1 && this.ignAt[i] === NEVER) this.ignAt[i] = k === 0 ? -2 : k - 1 + hash(i, 1) * 0.95;
        else if (v === 2 && this.outAt[i] === NEVER) {
          if (this.ignAt[i] === NEVER) this.ignAt[i] = k - 1;
          this.outAt[i] = k - 1 + 0.2 + hash(i, 2) * 0.8;
        }
      }
    });

    this.sub = Math.max(1, Math.min(3, Math.floor(160 / Math.max(s.rows, s.cols))));
    this.scale = Math.max(4, Math.min(16, Math.floor(768 / Math.max(s.rows, s.cols))));
    const W = s.cols * this.scale, H = s.rows * this.scale;
    if (this.canvas.width !== W || this.canvas.height !== H) { this.canvas.width = W; this.canvas.height = H; }
    if (this.base.width !== W || this.base.height !== H) { this.base.width = W; this.base.height = H; }
    const sw = s.cols * this.sub, sh = s.rows * this.sub;
    if (this.small.width !== sw || this.small.height !== sh) { this.small.width = sw; this.small.height = sh; this.img = null; }

    if (!sameGrid) { this.smoke = []; this.sparks = []; this.mist = []; this.steam = []; }
    // A freshly loaded terrain (new fuel array, no run yet) is revealed with a scan sweep.
    const key = `${s.rows}x${s.cols}:${s.fuel?.length ?? 0}:${s.fuel?.[0] ?? ''}:${s.fuel?.[Math.floor(n / 2)] ?? ''}`;
    if (s.fuel && s.frames.length === 0 && key !== this.revealKey && !reducedMotion()) this.reveal = 0;
    this.revealKey = key;
    this.baseDirty = true;
    this.update();
  }

  /** Fractional frame index (e.g. 12.4 = 40 % of the way from frame 12 to 13). */
  setPosition(pos: number) {
    if (pos === this.pos && !this.baseDirty) return;
    this.pos = pos;
    this.baseDirty = true;
    this.update();
  }

  private update() {
    if (!this.scene) return;
    this.computeState();
    this.baseDirty = true;
    // While the loop runs it repaints on the next display frame; painting here too would double the work.
    if (this.raf === 0) this.draw(performance.now(), 0);
    this.kick();
  }

  private kick() {
    if (!this.scene) return;
    if (this.raf === 0 && this.needsLoop()) {
      this.last = performance.now();
      this.raf = requestAnimationFrame(this.tick);
    }
  }

  private stop() {
    if (this.raf) cancelAnimationFrame(this.raf);
    this.raf = 0;
  }

  private needsLoop() {
    if (reducedMotion()) return this.reveal < 1;
    return this.reveal < 1 || this.burning.length > 0 || this.embers.length > 0 || this.activeLines.length > 0
      || this.smoke.length > 0 || this.sparks.length > 0 || this.mist.length > 0 || this.steam.length > 0
      || this.wetCells.length > 0;
  }

  private tick = (now: number) => {
    const dt = Math.min(0.05, Math.max(0, (now - this.last) / 1000));
    this.last = now;
    if (this.reveal < 1) {
      this.reveal = Math.min(1, this.reveal + dt / 1.4);
      if (this.reveal >= 1) this.baseDirty = true;
    }
    this.draw(now, dt);
    if (this.needsLoop()) this.raf = requestAnimationFrame(this.tick);
    else this.raf = 0;
  };

  /** Which cells burn, glow, or are wet at the current position. */
  private computeState() {
    const s = this.scene!;
    const n = s.rows * s.cols;
    const p = this.pos;
    this.burning = []; this.burnI = []; this.embers = []; this.emberI = [];
    this.wetCells = []; this.wetW = []; this.steamCells = []; this.activeLines = [];
    if (s.frames.length === 0) return;
    const k = Math.max(0, Math.min(s.frames.length - 1, Math.floor(p)));
    const fa = s.frames[k];
    const fb = s.frames[Math.min(s.frames.length - 1, k + 1)];
    const t = Math.min(1, Math.max(0, p - k));
    for (let i = 0; i < n; i++) {
      const ig = this.ignAt[i];
      if (ig === NEVER || p < ig) {
        // still unburned
      } else {
        const out = this.outAt[i];
        if (p < out) {
          const grow = smooth(0, 0.7, p - ig);
          const tail = out === NEVER ? 1 : 0.45 + 0.55 * smooth(0, 1.4, out - p);
          this.burning.push(i);
          this.burnI.push(Math.max(0.15, grow * tail));
        } else {
          const e = Math.exp(-(p - out) / 3.2);
          if (e > 0.04) { this.embers.push(i); this.emberI.push(e); }
        }
      }
      const w = (fa.wet[i] ?? 0) * (1 - t) + (fb.wet[i] ?? 0) * t;
      if (w > 0.02) {
        this.wetCells.push(i); this.wetW.push(Math.min(1, w));
        if (ig !== NEVER && p >= ig && p < this.outAt[i] + 1.5) this.steamCells.push(i);
      }
    }
    // Sprinkler lines that are spraying in the frame nearest the clock.
    const fl = t < 0.5 ? fa : fb;
    const zs = Math.max(1, Math.floor(s.rows / s.zones));
    for (const l of fl.lines) {
      if (!l.on) continue;
      const a = /^([A-Z])(\d+)$/.exec(l.from);
      if (!a) continue;
      const ar = a[1].charCodeAt(0) - 65, ac = Number(a[2]) - 1;
      // outer-edge lines spray along the study area border
      if (l.to === 'N' || l.to === 'S') { const r = l.to === 'N' ? 0 : s.rows; this.activeLines.push({ x0: ac * zs, y0: r, x1: Math.min(s.cols, (ac + 1) * zs), y1: r }); continue; }
      if (l.to === 'W' || l.to === 'E') { const c = l.to === 'W' ? 0 : s.cols; this.activeLines.push({ x0: c, y0: ar * zs, x1: c, y1: Math.min(s.rows, (ar + 1) * zs) }); continue; }
      const b = /^([A-Z])(\d+)$/.exec(l.to);
      if (!b) continue;
      const br = b[1].charCodeAt(0) - 65, bc = Number(b[2]) - 1;
      if (ar === br) {
        const c = Math.max(ac, bc) * zs;
        this.activeLines.push({ x0: c, y0: ar * zs, x1: c, y1: Math.min(s.rows, (ar + 1) * zs) });
      } else {
        const r = Math.max(ar, br) * zs;
        this.activeLines.push({ x0: ac * zs, y0: r, x1: Math.min(s.cols, (ac + 1) * zs), y1: r });
      }
    }
  }

  /** Ground layer: fuel tint, scorched earth, embers' red bed, burning bed, wet ground. */
  private paintBase() {
    const s = this.scene!;
    const { rows, cols, fuel } = s;
    const sub = this.sub;
    const sw = cols * sub, sh = rows * sub;
    const sctx = this.small.getContext('2d')!;
    if (!this.img) this.img = sctx.createImageData(sw, sh);
    const d = this.img.data;
    const p = this.pos;
    const running = s.frames.length > 0;
    const k = Math.max(0, Math.min(s.frames.length - 1, Math.floor(p)));
    const fa = running ? s.frames[k] : null;
    const fb = running ? s.frames[Math.min(s.frames.length - 1, k + 1)] : null;
    const t = Math.min(1, Math.max(0, p - k));

    for (let r = 0; r < rows; r++) {
      for (let c = 0; c < cols; c++) {
        const i = r * cols + c;
        const code = fuel ? fuel[i] : 0;
        const st0 = fa ? fa.state[i] : fuel ? (BURNABLE.has(code) ? 0 : 3) : 0;
        const ig = this.ignAt[i], out = this.outAt[i];
        const w = fa && fb ? Math.min(1, (fa.wet[i] ?? 0) * (1 - t) + (fb.wet[i] ?? 0) * t) : 0;
        for (let sy = 0; sy < sub; sy++) {
          for (let sx = 0; sx < sub; sx++) {
            const px = c * sub + sx, py = r * sub + sy;
            const nz = hash(py * sw + px, 7);
            let R = 0, G = 0, B = 0, A = 0;
            if (st0 === 3 || (fuel && !BURNABLE.has(code))) {
              [R, G, B] = NONFUEL; A = running ? 46 : 70;
            } else if (running && ig !== NEVER && p >= ig) {
              if (p < out) {
                // burning bed: dark red, flames are added on top
                const v = 0.75 + nz * 0.5;
                R = FIRE_BED[0] * v; G = FIRE_BED[1] * v; B = FIRE_BED[2] * v; A = 225;
              } else {
                // scorched: char with ash speckles, glowing red while the embers are hot
                const ash = nz > 0.82 ? (nz - 0.82) / 0.18 : 0;
                R = CHAR[0] + (ASH[0] - CHAR[0]) * ash; G = CHAR[1] + (ASH[1] - CHAR[1]) * ash; B = CHAR[2] + (ASH[2] - CHAR[2]) * ash;
                const e = Math.exp(-(p - out) / 3.2) * (0.55 + nz * 0.45);
                R += (EMBER[0] - R) * e; G += (EMBER[1] - G) * e; B += (EMBER[2] - B) * e;
                A = 222;
              }
            } else if (fuel) {
              const tint = FUEL_TINT[code] ?? FUEL_TINT[30];
              const v = 0.85 + nz * 0.3;
              R = tint[0] * v; G = tint[1] * v; B = tint[2] * v; A = running ? 34 : 78;
            }
            if (w > 0) {
              // soaked ground: darker, cooler, with a faint blue sheen rather than a solid blue paint
              R += (WET[0] - R) * w * 0.7; G += (WET[1] - G) * w * 0.7; B += (WET[2] - B) * w * 0.7;
              A += (Math.max(A, 92 + nz * 46) - A) * w;
            }
            const o = (py * sw + px) * 4;
            d[o] = R; d[o + 1] = G; d[o + 2] = B; d[o + 3] = A;
          }
        }
      }
    }
    sctx.putImageData(this.img, 0, 0);
    const bctx = this.base.getContext('2d')!;
    bctx.clearRect(0, 0, this.base.width, this.base.height);
    bctx.imageSmoothingEnabled = true;
    bctx.imageSmoothingQuality = 'high';
    // A slight blur turns the square cell edges into ragged burn scars (ignored where unsupported).
    bctx.filter = `blur(${Math.max(1, this.scale * 0.22).toFixed(1)}px)`;
    bctx.drawImage(this.small, 0, 0, this.base.width, this.base.height);
    bctx.filter = 'none';
    this.baseDirty = false;
  }

  private draw(now: number, dt: number) {
    const s = this.scene;
    if (!s) return;
    if (this.baseDirty) this.paintBase();
    const ctx = this.canvas.getContext('2d')!;
    const W = this.canvas.width, H = this.canvas.height, S = this.scale;
    const time = now / 1000;
    const still = reducedMotion();
    ctx.globalCompositeOperation = 'source-over';
    ctx.globalAlpha = 1;
    ctx.clearRect(0, 0, W, H);

    // Terrain reveal: the fuel map is uncovered by an expanding scan ring.
    if (this.reveal < 1) {
      const e = 1 - Math.pow(1 - this.reveal, 3);
      const R = Math.hypot(W, H) * 0.55 * e;
      ctx.save();
      ctx.beginPath(); ctx.arc(W / 2, H / 2, Math.max(1, R), 0, Math.PI * 2); ctx.clip();
      ctx.drawImage(this.base, 0, 0);
      ctx.restore();
      ctx.globalCompositeOperation = 'lighter';
      ctx.strokeStyle = `rgba(120,210,255,${0.85 * (1 - e)})`;
      ctx.lineWidth = Math.max(2, S * 0.6);
      ctx.beginPath(); ctx.arc(W / 2, H / 2, Math.max(1, R), 0, Math.PI * 2); ctx.stroke();
      ctx.strokeStyle = `rgba(120,210,255,${0.25 * (1 - e)})`;
      ctx.lineWidth = Math.max(6, S * 2.2);
      ctx.stroke();
      ctx.globalCompositeOperation = 'source-over';
    } else {
      ctx.drawImage(this.base, 0, 0);
    }

    const to = ((this.windDir + 180) % 360) * Math.PI / 180;
    const wx = Math.sin(to), wy = -Math.cos(to);
    const ws = Math.max(0, this.windSpeed);
    const lean = Math.min(0.5, ws * 0.035) * S;
    const cols = s.cols;
    const nB = this.burning.length;
    const thin = nB > 2500 ? 3 : nB > 1200 ? 2 : 1;

    // Wet ground glints.
    if (this.wetCells.length && !still) {
      ctx.globalCompositeOperation = 'lighter';
      ctx.fillStyle = 'rgb(190,228,255)';
      for (let j = 0; j < this.wetCells.length; j++) {
        const i = this.wetCells[j];
        const ph = hash(i, 11) * 6.283;
        const g = Math.pow(0.5 + 0.5 * Math.sin(time * (2.2 + hash(i, 12) * 2) + ph), 6) * this.wetW[j];
        if (g < 0.05) continue;
        ctx.globalAlpha = g * 0.55;
        const x = (i % cols + 0.2 + hash(i, 13) * 0.6) * S, y = (Math.floor(i / cols) + 0.2 + hash(i, 14) * 0.6) * S;
        const r = Math.max(0.8, S * 0.09);
        ctx.fillRect(x - r, y - r, r * 2, r * 2);
      }
    }

    // Embers breathing on freshly burned ground.
    ctx.globalCompositeOperation = 'lighter';
    for (let j = 0; j < this.embers.length; j++) {
      const i = this.embers[j];
      const breathe = still ? 0.8 : 0.6 + 0.4 * Math.sin(time * (1.3 + hash(i, 3)) + hash(i, 4) * 6.283);
      ctx.globalAlpha = Math.min(1, this.emberI[j] * breathe * 0.55);
      const x = (i % cols + 0.5) * S, y = (Math.floor(i / cols) + 0.5) * S, r = S * 0.75;
      ctx.drawImage(this.sEmber, x - r, y - r, r * 2, r * 2);
    }

    // Heat glow beneath the flames, then the flames themselves (flickering, leaning downwind).
    for (let j = 0; j < nB; j += thin) {
      const i = this.burning[j];
      const x = (i % cols + 0.5) * S, y = (Math.floor(i / cols) + 0.5) * S;
      const r = S * 2.4;
      ctx.globalAlpha = 0.4 * this.burnI[j];
      ctx.drawImage(this.sGlow, x - r, y - r, r * 2, r * 2);
    }
    for (let j = 0; j < nB; j++) {
      const i = this.burning[j];
      const h1 = hash(i, 5), h2 = hash(i, 6);
      const fl = still ? 0.85 : 0.72 + 0.18 * Math.sin(time * (9 + h1 * 7) + h2 * 6.283) + 0.12 * Math.sin(time * (17 + h2 * 9) + h1 * 6.283);
      const inten = this.burnI[j] * fl;
      const cx = (i % cols + 0.5) * S, cy = (Math.floor(i / cols) + 0.5) * S;
      // two tongues per cell: the base and a smaller one licking downwind
      let r = S * (0.85 + 0.6 * inten);
      ctx.globalAlpha = Math.min(1, 0.95 * inten);
      ctx.drawImage(this.sFlame, cx + wx * lean * 0.4 - r, cy + wy * lean * 0.4 - r, r * 2, r * 2);
      r *= 0.6;
      const wob = still ? 0 : Math.sin(time * (6 + h2 * 5) + h1 * 9) * 0.25 * S;
      ctx.globalAlpha = Math.min(1, 0.7 * inten);
      ctx.drawImage(this.sFlame, cx + wx * lean * 1.3 - wy * wob - r, cy + wy * lean * 1.3 + wx * wob - r, r * 2, r * 2);
    }
    ctx.globalAlpha = 1;
    ctx.globalCompositeOperation = 'source-over';

    if (this.fx && !still) {
      this.stepParticles(dt, S, wx, wy, ws);
      this.drawParticles(ctx);
    }
    ctx.globalAlpha = 1;
    ctx.globalCompositeOperation = 'source-over';
    this.emit();
  }

  private stepParticles(dt: number, S: number, wx: number, wy: number, ws: number) {
    if (dt <= 0) return;
    const s = this.scene!;
    const cols = s.cols;
    const drift = (0.35 + ws * 0.11) * S; // px / s
    const nB = this.burning.length;

    // smoke + sparks from the fire
    const smokeBudget = Math.max(0, 650 - this.smoke.length);
    const perCell = Math.min(1.6, 520 / Math.max(1, nB));
    let spawned = 0;
    for (let j = 0; j < nB && spawned < smokeBudget; j++) {
      const i = this.burning[j];
      if (Math.random() < perCell * this.burnI[j] * dt) {
        spawned++;
        const x = (i % cols + Math.random()) * S, y = (Math.floor(i / cols) + Math.random()) * S;
        const v = drift * (0.7 + Math.random() * 0.6);
        this.smoke.push({ x, y, vx: wx * v, vy: wy * v, life: 0, max: 3 + Math.random() * 3.5, size: S * (0.7 + Math.random() * 0.6), grow: S * (0.9 + Math.random() * 0.9), seed: Math.random() * 6.283 });
      }
      if (this.sparks.length < 260 && Math.random() < perCell * 0.5 * this.burnI[j] * dt) {
        const x = (i % cols + 0.5) * S, y = (Math.floor(i / cols) + 0.5) * S;
        const v = drift * (1.2 + Math.random() * 1.8);
        const a = (Math.random() - 0.5) * 1.6;
        const cx = Math.cos(a), sx = Math.sin(a);
        this.sparks.push({ x, y, vx: (wx * cx - wy * sx) * v, vy: (wy * cx + wx * sx) * v, life: 0, max: 0.5 + Math.random() * 0.9, size: S * (0.18 + Math.random() * 0.2), grow: 0, seed: Math.random() * 6.283 });
      }
    }
    // steam where water meets fire
    for (const i of this.steamCells) {
      if (this.steam.length > 220) break;
      if (Math.random() < 1.4 * dt) {
        const x = (i % cols + Math.random()) * S, y = (Math.floor(i / cols) + Math.random()) * S;
        const v = drift * (0.5 + Math.random() * 0.5);
        this.steam.push({ x, y, vx: wx * v, vy: wy * v, life: 0, max: 1.2 + Math.random() * 1.4, size: S * 0.5, grow: S * 1.4, seed: Math.random() * 6.283 });
      }
    }
    // sprinkler mist thrown sideways from each spraying line
    for (const l of this.activeLines) {
      const len = Math.hypot(l.x1 - l.x0, l.y1 - l.y0);
      const rate = len * 3.2;
      const nx = l.y1 - l.y0, ny = -(l.x1 - l.x0);
      const nl = Math.hypot(nx, ny) || 1;
      let k = rate * dt;
      while (k > 0 && this.mist.length < 420) {
        if (k < 1 && Math.random() > k) break;
        k -= 1;
        const u = Math.random();
        const side = Math.random() < 0.5 ? -1 : 1;
        const sp = S * (1.6 + Math.random() * 2.2);
        this.mist.push({
          x: (l.x0 + (l.x1 - l.x0) * u) * S, y: (l.y0 + (l.y1 - l.y0) * u) * S,
          vx: (nx / nl) * side * sp + wx * drift * 0.4, vy: (ny / nl) * side * sp + wy * drift * 0.4,
          life: 0, max: 0.55 + Math.random() * 0.5, size: S * (0.22 + Math.random() * 0.25), grow: S * 0.3, seed: Math.random() * 6.283,
        });
      }
    }

    const W = this.canvas.width, H = this.canvas.height;
    const advance = (arr: Particle[], drag: number, turb: number) => {
      let w = 0;
      for (let q = 0; q < arr.length; q++) {
        const p = arr[q];
        p.life += dt;
        if (p.life >= p.max) continue;
        const wob = Math.sin(p.life * 2.1 + p.seed) * turb;
        p.x += (p.vx - wy * wob) * dt;
        p.y += (p.vy + wx * wob) * dt;
        p.vx *= 1 - drag * dt; p.vy *= 1 - drag * dt;
        if (p.x < -S * 4 || p.y < -S * 4 || p.x > W + S * 4 || p.y > H + S * 4) continue;
        arr[w++] = p;
      }
      arr.length = w;
    };
    advance(this.smoke, 0.05, S * 0.6);
    advance(this.sparks, 0.9, S * 1.4);
    advance(this.mist, 2.6, S * 0.3);
    advance(this.steam, 0.4, S * 0.5);
  }

  private drawParticles(ctx: CanvasRenderingContext2D) {
    // mist and sparks glow additively; smoke and steam are drawn normally on top
    ctx.globalCompositeOperation = 'lighter';
    for (const p of this.mist) {
      const k = p.life / p.max;
      ctx.globalAlpha = 0.5 * (1 - k) * Math.min(1, k * 6);
      const r = p.size + p.grow * k;
      ctx.drawImage(this.sMist, p.x - r, p.y - r, r * 2, r * 2);
    }
    for (const p of this.sparks) {
      const k = p.life / p.max;
      ctx.globalAlpha = (1 - k) * (0.6 + 0.4 * Math.sin(p.life * 40 + p.seed));
      const r = p.size * (1 - k * 0.5);
      ctx.drawImage(this.sSpark, p.x - r, p.y - r, r * 2, r * 2);
    }
    ctx.globalCompositeOperation = 'source-over';
    for (const p of this.steam) {
      const k = p.life / p.max;
      ctx.globalAlpha = 0.5 * Math.min(1, k * 4) * (1 - k);
      const r = p.size + p.grow * k;
      ctx.drawImage(this.sSteam, p.x - r, p.y - r, r * 2, r * 2);
    }
    // young smoke is dark and dense near the fire, then billows out lighter and thinner
    for (const p of this.smoke) {
      const k = p.life / p.max;
      const a = 0.8 * smooth(0, 0.12, k) * (1 - k);
      const r = p.size + p.grow * k * 2.6;
      ctx.globalAlpha = a * (1 - smooth(0, 0.45, k));
      ctx.drawImage(this.sSmokeDark, p.x - r, p.y - r, r * 2, r * 2);
      ctx.globalAlpha = a * smooth(0.05, 0.4, k);
      ctx.drawImage(this.sSmoke, p.x - r, p.y - r, r * 2, r * 2);
    }
  }
}

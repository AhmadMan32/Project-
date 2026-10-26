import { zoneRowLetter, type Bounds } from '@/lib/sim';

/** What a map click did, so the map can play matching feedback where the user clicked. */
export type ClickFx = 'ignite' | 'remove' | 'inspect' | 'invalid' | null | void;

/** Line-dash frames that make a [dash, gap] pattern appear to flow along the line. */
export function dashSequence(dash: number, gap: number, steps: number): number[][] {
  const out: number[][] = [];
  for (let i = 0; i < steps; i++) {
    const o = ((dash + gap) * i) / steps;
    out.push(o < dash ? [dash - o, gap, o, 0] : [0, gap - (o - dash), dash, o - dash]);
  }
  return out;
}

/** Zone id under a point, using the same equal split as the drawn zone grid. */
export function zoneAtLngLat(lng: number, lat: number, b: Bounds, n: number): string | null {
  const [w, s, e, nn] = b;
  if (lng < w || lng > e || lat < s || lat > nn) return null;
  const c = Math.min(n - 1, Math.floor(((lng - w) / (e - w)) * n));
  const r = Math.min(n - 1, Math.floor(((nn - lat) / (nn - s)) * n));
  return `${zoneRowLetter(r)}${c + 1}`;
}

/** Closed ring [lng, lat][] of a zone. */
export function zoneRing(id: string, b: Bounds, n: number): [number, number][] | null {
  const m = /^([A-Z])(\d+)$/.exec(id);
  if (!m) return null;
  const r = m[1].charCodeAt(0) - 65, c = Number(m[2]) - 1;
  if (r >= n || c >= n) return null;
  const [w, s, e, nn] = b;
  const x0 = w + (c / n) * (e - w), x1 = w + ((c + 1) / n) * (e - w);
  const y0 = nn - (r / n) * (nn - s), y1 = nn - ((r + 1) / n) * (nn - s);
  return [[x0, y0], [x1, y0], [x1, y1], [x0, y1], [x0, y0]];
}

/** Viewfinder-style corner brackets around the study area. */
export function cornerBrackets(b: Bounds): [number, number][][] {
  const [w, s, e, n] = b;
  const kx = (e - w) * 0.09, ky = (n - s) * 0.09;
  return [
    [[w, n - ky], [w, n], [w + kx, n]],
    [[e - kx, n], [e, n], [e, n - ky]],
    [[e, s + ky], [e, s], [e - kx, s]],
    [[w + kx, s], [w, s], [w, s + ky]],
  ];
}

export function fmtWidth(widthM: number) {
  return widthM >= 1000 ? `${(widthM / 1000).toFixed(widthM % 1000 === 0 ? 0 : 2)} km` : `${Math.round(widthM)} m`;
}

/** DOM for an ignition marker. Animations live on children so map libraries can own the root transform. */
export function ignitionHtml(fresh: boolean) {
  return `<div class="fb-ign${fresh ? ' fb-ign-new' : ''}" aria-hidden="true"><span class="fb-ign-ring"></span><span class="fb-ign-ring fb-d2"></span><span class="fb-ign-glow"></span><span class="fb-ign-core"></span><span class="fb-ign-flame"></span></div>`;
}

export function handleHtml(kind: 'move' | 'resize', label: string) {
  const icon = kind === 'move'
    ? '<svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2v20M2 12h20M12 2l-3 3M12 2l3 3M12 22l-3-3M12 22l3-3M2 12l3-3M2 12l3 3M22 12l-3-3M22 12l-3 3"/></svg>'
    : '<svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><path d="M21 21L13 13M21 21v-6M21 21h-6M3 3l8 8M3 3v6M3 3h6"/></svg>';
  return `<div class="fb-handle fb-handle-${kind}" role="button" aria-label="${label}" title="${label}"><span class="fb-handle-pulse"></span><span class="fb-handle-body">${icon}</span></div>`;
}

export function rippleHtml(kind: Exclude<ClickFx, null | void>) {
  return `<div class="fb-ripple fb-ripple-${kind}" aria-hidden="true"><span></span><span class="fb-d2"></span>${kind === 'ignite' ? '<i></i><i></i><i></i><i></i><i></i><i></i>' : ''}</div>`;
}

export const prefersReducedMotion = () => {
  try { return window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false; } catch { return false; }
};

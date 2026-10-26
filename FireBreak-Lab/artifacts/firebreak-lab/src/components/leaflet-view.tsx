import { useEffect, useRef, useState } from 'react';
import * as L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import type { Line } from '@workspace/api-client-react';
import { zoneGeometry, cellCenter, type Bounds, type SimRenderer } from '@/lib/sim';
import type { FlyTarget } from '@/components/map-view';
import {
  cornerBrackets, fmtWidth, handleHtml, ignitionHtml, prefersReducedMotion, rippleHtml, zoneAtLngLat, zoneRing, type ClickFx,
} from '@/components/map-fx';

type Props = {
  center: [number, number];
  bounds: Bounds;
  locked: boolean;
  onMoveArea: (lng: number, lat: number) => void;
  onResizeArea: (widthM: number) => void;
  grid: { rows: number; cols: number } | null;
  renderer: SimRenderer;
  ignitions: { row: number; col: number }[];
  zonesN: number;
  lines: Line[];
  onMapClick: (lng: number, lat: number) => ClickFx;
  zonesActive: boolean;
  selectedZone: string | null;
  clickMode: 'ignite' | 'inspect';
  widthM: number;
  onCursor: (c: { lng: number; lat: number; elev: number | null } | null) => void;
  fly: FlyTarget | null;
  onError?: (msg: string) => void;
};

const ll = (lng: number, lat: number): L.LatLngTuple => [lat, lng];

type MapInternals = {
  _latLngBoundsToNewLayerBounds(b: L.LatLngBounds, zoom: number, center: L.LatLng): L.Bounds;
};

/**
 * Shows the shared simulation canvas inside Leaflet's overlay pane. The canvas is displayed
 * directly, so each repaint from the renderer appears without re-encoding an image.
 */
class SimCanvasLayer extends L.Layer {
  private map: L.Map | null = null;
  private bounds: L.LatLngBounds;
  private readonly el: HTMLCanvasElement;

  constructor(el: HTMLCanvasElement, bounds: L.LatLngBounds) {
    super();
    this.el = el;
    this.bounds = bounds;
  }

  setBounds(b: L.LatLngBounds) {
    this.bounds = b;
    this.reset();
  }

  onAdd(map: L.Map) {
    this.map = map;
    const s = this.el.style;
    this.el.classList.add('leaflet-zoom-animated');
    s.position = 'absolute';
    s.left = '0';
    s.top = '0';
    s.pointerEvents = 'none';
    s.opacity = '0.92';
    s.imageRendering = 'auto';
    map.getPanes().overlayPane.appendChild(this.el);
    map.on('zoom viewreset moveend', this.reset, this);
    map.on('zoomanim', this.animateZoom, this);
    this.reset();
    return this;
  }

  onRemove(map: L.Map) {
    map.off('zoom viewreset moveend', this.reset, this);
    map.off('zoomanim', this.animateZoom, this);
    this.el.remove();
    this.map = null;
    return this;
  }

  private reset = () => {
    const m = this.map;
    if (!m) return;
    const tl = m.latLngToLayerPoint(this.bounds.getNorthWest());
    const br = m.latLngToLayerPoint(this.bounds.getSouthEast());
    const size = br.subtract(tl);
    L.DomUtil.setPosition(this.el, tl);
    this.el.style.width = `${size.x}px`;
    this.el.style.height = `${size.y}px`;
  };

  private animateZoom = (e: L.ZoomAnimEvent) => {
    const m = this.map;
    if (!m) return;
    const scale = m.getZoomScale(e.zoom);
    const offset = (m as unknown as MapInternals)._latLngBoundsToNewLayerBounds(this.bounds, e.zoom, e.center).min!;
    L.DomUtil.setTransform(this.el, offset, scale);
  };
}

/** 2D satellite fallback for browsers without WebGL2. Flat Web Mercator, no terrain. */
export function LeafletView(p: Props) {
  const el = useRef<HTMLDivElement>(null);
  const map = useRef<L.Map | null>(null);
  const [ready, setReady] = useState(false);
  const layers = useRef<{
    area: L.Rectangle; areaGlow: L.Rectangle; corners: L.LayerGroup; sizeLabel: L.Marker;
    grid: L.LayerGroup; lines: L.LayerGroup; ign: L.LayerGroup; labels: L.LayerGroup;
    hover: L.Polygon; sel: L.Polygon;
    sim: SimCanvasLayer; center: L.Marker; corner: L.Marker;
  } | null>(null);
  const cb = useRef(p);
  cb.current = p;

  useEffect(() => {
    if (!el.current) return;
    let m: L.Map;
    try {
      m = L.map(el.current, { zoomControl: false, attributionControl: true }).setView(ll(...p.center), 14);
    } catch (e) {
      cb.current.onError?.(e instanceof Error ? e.message : String(e));
      return;
    }
    const tiles = L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', {
      maxZoom: 19,
      maxNativeZoom: 17,
      attribution: 'Imagery © Esri, Maxar, Earthstar Geographics (visual only)',
    }).addTo(m);
    const osmTiles = L.tileLayer('https://basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '© OpenStreetMap contributors © CARTO',
    });
    let esriLoaded = false;
    let tileErrs = 0;
    let osmErrs = 0;
    let usingOsm = false;
    let imageryTimer: number | undefined;
    const switchToOsm = (message: string) => {
      if (usingOsm) return;
      usingOsm = true;
      if (imageryTimer !== undefined) window.clearTimeout(imageryTimer);
      m.removeLayer(tiles);
      osmTiles.addTo(m);
      osmTiles.bringToBack();
      cb.current.onError?.(message);
    };
    imageryTimer = window.setTimeout(() => {
      if (!esriLoaded) switchToOsm('Uydu görüntüsü yanıt vermedi; açık harita tabanı gösteriliyor.');
    }, 7000);
    tiles.once('load', () => {
      esriLoaded = true;
      if (imageryTimer !== undefined) window.clearTimeout(imageryTimer);
    });
    tiles.on('tileerror', () => {
      if (++tileErrs >= 6) switchToOsm('Uydu görüntüsü yüklenemedi; açık harita tabanı gösteriliyor.');
    });
    osmTiles.on('tileerror', () => {
      if (++osmErrs === 6) cb.current.onError?.('Harita karoları yüklenemedi; internet bağlantısını kontrol edin.');
    });
    L.control.zoom({ position: 'topright' }).addTo(m);
    L.control.scale({ metric: true, imperial: false, position: 'bottomright' }).addTo(m);
    const [w, s, e, n] = p.bounds;
    const b = L.latLngBounds(ll(w, s), ll(e, n));
    const sim = new SimCanvasLayer(cb.current.renderer.canvas, b);
    sim.addTo(m);
    const areaGlow = L.rectangle(b, { color: '#ff8a3d', weight: 8, opacity: 0.18, fill: false, interactive: false, className: 'fb-blur' }).addTo(m);
    const area = L.rectangle(b, { color: '#ffc58f', weight: 1.8, dashArray: '6 4', fillColor: '#ff6a2b', fillOpacity: 0.04, interactive: false, className: 'fb-ants' }).addTo(m);
    const corners = L.layerGroup().addTo(m);
    const sizeLabel = L.marker(ll(w, n), { interactive: false, keyboard: false, icon: L.divIcon({ className: '', html: '' }) }).addTo(m);
    const grid = L.layerGroup().addTo(m);
    const hover = L.polygon([], { color: '#fff1dc', weight: 1.5, opacity: 0.7, fillColor: '#fff1dc', fillOpacity: 0.07, interactive: false }).addTo(m);
    const sel = L.polygon([], { color: '#cfe7ff', weight: 2.4, fillColor: '#59b0ff', fillOpacity: 0.1, interactive: false, className: 'fb-zone-sel' }).addTo(m);
    const lines = L.layerGroup().addTo(m);
    const labels = L.layerGroup().addTo(m);
    const ign = L.layerGroup().addTo(m);
    const icon = (kind: 'move' | 'resize', label: string) =>
      L.divIcon({ className: '', html: handleHtml(kind, label), iconSize: [28, 28], iconAnchor: [14, 14] });
    const center = L.marker(ll(...p.center), { draggable: true, keyboard: true, icon: icon('move', 'Drag study area') }).addTo(m);
    const corner = L.marker(ll(e, s), { draggable: true, keyboard: true, icon: icon('resize', 'Resize study area') }).addTo(m);
    for (const mk of [center, corner]) {
      mk.on('dragstart', () => mk.getElement()?.classList.add('fb-dragging'));
      mk.on('dragend', () => mk.getElement()?.classList.remove('fb-dragging'));
    }
    center.on('dragend', () => { const q = center.getLatLng(); cb.current.onMoveArea(q.lng, q.lat); });
    corner.on('dragend', () => {
      const q = corner.getLatLng();
      const [clng, clat] = cb.current.center;
      const dx = Math.abs(q.lng - clng) * 111320 * Math.cos((clat * Math.PI) / 180);
      const dy = Math.abs(q.lat - clat) * 111320;
      cb.current.onResizeArea(Math.max(dx, dy) * 2);
    });
    m.on('click', (ev: L.LeafletMouseEvent) => {
      const fx = cb.current.onMapClick(ev.latlng.lng, ev.latlng.lat);
      if (fx && !prefersReducedMotion()) {
        const mk = L.marker(ev.latlng, { interactive: false, keyboard: false, icon: L.divIcon({ className: '', html: rippleHtml(fx), iconSize: [0, 0] }) }).addTo(m);
        window.setTimeout(() => mk.remove(), 1100);
      }
    });
    let hoverId: string | null = null;
    const container = m.getContainer();
    m.on('mousemove', (ev: L.LeafletMouseEvent) => {
      cb.current.onCursor({ lng: ev.latlng.lng, lat: ev.latlng.lat, elev: null });
      const z = cb.current.zonesActive ? zoneAtLngLat(ev.latlng.lng, ev.latlng.lat, cb.current.bounds, cb.current.zonesN) : null;
      container.style.cursor = z ? (cb.current.clickMode === 'ignite' && !cb.current.locked ? 'crosshair' : 'pointer') : '';
      if (z === hoverId) return;
      hoverId = z;
      const ring = z ? zoneRing(z, cb.current.bounds, cb.current.zonesN) : null;
      hover.setLatLngs(ring ? ring.map(([x, y]) => ll(x, y)) : []);
    });
    m.on('mouseout', () => { cb.current.onCursor(null); hoverId = null; hover.setLatLngs([]); });
    layers.current = { area, areaGlow, corners, sizeLabel, grid, lines, ign, labels, hover, sel, sim, center, corner };
    map.current = m;
    setReady(true);
    return () => {
      if (imageryTimer !== undefined) window.clearTimeout(imageryTimer);
      try { m.remove(); } catch { /* ignore */ }
      map.current = null;
      layers.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const L_ = layers.current;
    if (!ready || !L_) return;
    const [w, s, e, n] = p.bounds;
    const b = L.latLngBounds(ll(w, s), ll(e, n));
    L_.area.setBounds(b);
    L_.areaGlow.setBounds(b);
    L_.sim.setBounds(b);
    L_.area.getElement()?.classList.toggle('fb-ants-paused', p.locked);
    L_.corners.clearLayers();
    cornerBrackets(p.bounds).forEach((c) =>
      L.polyline(c.map(([x, y]) => ll(x, y)), { color: '#fff1dc', weight: 3.5, opacity: 0.95, lineCap: 'round', lineJoin: 'round', interactive: false }).addTo(L_.corners));
    L_.sizeLabel.setLatLng(ll((w + e) / 2, n));
    L_.sizeLabel.setIcon(L.divIcon({
      className: '', iconSize: [120, 16], iconAnchor: [60, 22],
      html: `<span class="num block text-center text-[11px] font-semibold text-[#ffd9b5] [text-shadow:0_0_3px_#0d1316,0_0_6px_#0d1316]">${fmtWidth(p.widthM)} × ${fmtWidth(p.widthM)}</span>`,
    }));
    L_.center.setLatLng(ll(...p.center));
    L_.corner.setLatLng(ll(e, s));
    for (const mk of [L_.center, L_.corner]) {
      if (p.locked) mk.dragging?.disable(); else mk.dragging?.enable();
      mk.getElement()?.classList.toggle('fb-locked', p.locked);
    }
    const g = zoneGeometry(p.bounds, p.zonesN);
    L_.grid.clearLayers();
    g.grid.forEach((c) => L.polyline(c.map(([x, y]) => ll(x, y)), { color: '#e8e1d4', opacity: 0.35, weight: 1, interactive: false }).addTo(L_.grid));
    L_.labels.clearLayers();
    g.labels.forEach((lb) =>
      L.marker(ll(...lb.pos), {
        interactive: false, keyboard: false,
        icon: L.divIcon({ className: '', html: `<span class="num text-[11px] font-semibold text-[#f1ebe0] [text-shadow:0_0_3px_#0d1316]">${lb.id}</span>`, iconSize: [30, 14], iconAnchor: [15, 7] }),
      }).addTo(L_.labels),
    );
  }, [ready, p.bounds, p.center, p.locked, p.zonesN, p.widthM]);

  useEffect(() => {
    const L_ = layers.current;
    if (!ready || !L_) return;
    L_.lines.clearLayers();
    const g = zoneGeometry(p.bounds, p.zonesN);
    for (const l of p.lines) {
      const seg = g.segment(l.from, l.to);
      if (!seg) continue;
      const pts = seg.map(([x, y]) => ll(x, y));
      if (l.on) {
        L.polyline(pts, { color: '#3a9bff', weight: 14, opacity: 0.32, interactive: false, className: 'fb-blur' }).addTo(L_.lines);
        L.polyline(pts, { color: '#1d5fa8', weight: 4.5, opacity: 1, lineCap: 'round', interactive: false }).addTo(L_.lines);
        L.polyline(pts, { color: '#bfe2ff', weight: 2.2, dashArray: '6 5', interactive: false, className: 'fb-flow' }).addTo(L_.lines);
      } else {
        L.polyline(pts, { color: '#7d93a6', weight: 1.5, opacity: 0.8, dashArray: '6 4', interactive: false }).addTo(L_.lines);
      }
    }
  }, [ready, p.lines, p.bounds, p.zonesN]);

  const ignMk = useRef(new Map<string, L.Marker>());
  useEffect(() => {
    const L_ = layers.current;
    const cur = ignMk.current;
    if (!ready || !L_) return;
    const want = new Map<string, L.LatLngTuple>();
    if (p.grid) {
      const { rows, cols } = p.grid;
      for (const i of p.ignitions) want.set(`${i.row}:${i.col}`, ll(...cellCenter(i.row, i.col, p.bounds, rows, cols)));
    }
    for (const [k, mk] of cur) if (!want.has(k)) { L_.ign.removeLayer(mk); cur.delete(k); }
    const fresh = cur.size > 0 || want.size === 1;
    for (const [k, pos] of want) {
      const old = cur.get(k);
      if (old) { old.setLatLng(pos); continue; }
      const mk = L.marker(pos, { interactive: false, keyboard: false, icon: L.divIcon({ className: '', html: ignitionHtml(fresh), iconSize: [0, 0] }) });
      mk.addTo(L_.ign);
      cur.set(k, mk);
    }
  }, [ready, p.ignitions, p.grid, p.bounds]);

  useEffect(() => {
    const L_ = layers.current;
    if (!ready || !L_) return;
    const ring = p.selectedZone && p.zonesActive ? zoneRing(p.selectedZone, p.bounds, p.zonesN) : null;
    L_.sel.setLatLngs(ring ? ring.map(([x, y]) => ll(x, y)) : []);
  }, [ready, p.selectedZone, p.zonesActive, p.bounds, p.zonesN]);

  useEffect(() => {
    if (!p.fly || !map.current) return;
    map.current.flyTo(ll(p.fly.lng, p.fly.lat), Math.round(p.fly.zoom ?? 14), { duration: 1.6, easeLinearity: 0.2 });
  }, [p.fly]);

  return <div ref={el} className="absolute inset-0 z-0 bg-[#0f1519]" data-testid="map-satellite-2d" aria-label="2D satellite map" role="application" />;
}

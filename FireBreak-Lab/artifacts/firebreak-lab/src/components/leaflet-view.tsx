import { useEffect, useRef, useState } from 'react';
import * as L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import type { Line } from '@workspace/api-client-react';
import { zoneGeometry, cellCenter, type Bounds, type SimRenderer } from '@/lib/sim';
import type { FlyTarget } from '@/components/map-view';

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
  onMapClick: (lng: number, lat: number) => void;
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
    area: L.Rectangle; grid: L.LayerGroup; lines: L.LayerGroup; ign: L.LayerGroup; labels: L.LayerGroup;
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
    const area = L.rectangle(b, { color: '#ffb36b', weight: 2, dashArray: '6 4', fillColor: '#ff6a2b', fillOpacity: 0.05, interactive: false }).addTo(m);
    const grid = L.layerGroup().addTo(m);
    const lines = L.layerGroup().addTo(m);
    const labels = L.layerGroup().addTo(m);
    const ign = L.layerGroup().addTo(m);
    const icon = (cls: string, label: string) =>
      L.divIcon({ className: '', html: `<div class="${cls}" role="button" aria-label="${label}" title="${label}"></div>`, iconSize: [20, 20], iconAnchor: [10, 10] });
    const center = L.marker(ll(...p.center), { draggable: true, keyboard: true, icon: icon('h-5 w-5 rounded-full border-2 border-[#fff1dc] bg-[#ff6a2b]/80 cursor-move', 'Drag study area') }).addTo(m);
    const corner = L.marker(ll(e, s), { draggable: true, keyboard: true, icon: icon('h-4 w-4 rotate-45 border-2 border-[#fff1dc] bg-[#ffb36b] cursor-nwse-resize', 'Resize study area') }).addTo(m);
    center.on('dragend', () => { const q = center.getLatLng(); cb.current.onMoveArea(q.lng, q.lat); });
    corner.on('dragend', () => {
      const q = corner.getLatLng();
      const [clng, clat] = cb.current.center;
      const dx = Math.abs(q.lng - clng) * 111320 * Math.cos((clat * Math.PI) / 180);
      const dy = Math.abs(q.lat - clat) * 111320;
      cb.current.onResizeArea(Math.max(dx, dy) * 2);
    });
    m.on('click', (ev: L.LeafletMouseEvent) => cb.current.onMapClick(ev.latlng.lng, ev.latlng.lat));
    m.on('mousemove', (ev: L.LeafletMouseEvent) => cb.current.onCursor({ lng: ev.latlng.lng, lat: ev.latlng.lat, elev: null }));
    m.on('mouseout', () => cb.current.onCursor(null));
    layers.current = { area, grid, lines, ign, labels, sim, center, corner };
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
    L_.sim.setBounds(b);
    L_.center.setLatLng(ll(...p.center));
    L_.corner.setLatLng(ll(e, s));
    for (const mk of [L_.center, L_.corner]) {
      if (p.locked) mk.dragging?.disable(); else mk.dragging?.enable();
      mk.setOpacity(p.locked ? 0.35 : 1);
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
  }, [ready, p.bounds, p.center, p.locked, p.zonesN]);

  useEffect(() => {
    const L_ = layers.current;
    if (!ready || !L_) return;
    L_.lines.clearLayers();
    const g = zoneGeometry(p.bounds, p.zonesN);
    for (const l of p.lines) {
      const seg = g.segment(l.from, l.to);
      if (!seg) continue;
      const pts = seg.map(([x, y]) => ll(x, y));
      if (l.on) L.polyline(pts, { color: '#3a9bff', weight: 10, opacity: 0.3, interactive: false }).addTo(L_.lines);
      L.polyline(pts, { color: l.on ? '#59b0ff' : '#7d93a6', weight: l.on ? 3.5 : 1.5, dashArray: '6 4', interactive: false }).addTo(L_.lines);
    }
  }, [ready, p.lines, p.bounds, p.zonesN]);

  useEffect(() => {
    const L_ = layers.current;
    if (!ready || !L_) return;
    L_.ign.clearLayers();
    if (!p.grid) return;
    const { rows, cols } = p.grid;
    p.ignitions.forEach((i) => {
      const c = ll(...cellCenter(i.row, i.col, p.bounds, rows, cols));
      L.circleMarker(c, { radius: 12, color: '#ff5a1f', weight: 0, fillOpacity: 0.25, interactive: false }).addTo(L_.ign);
      L.circleMarker(c, { radius: 5.5, color: '#fff1dc', weight: 2, fillColor: '#ff5a1f', fillOpacity: 1, interactive: false }).addTo(L_.ign);
    });
  }, [ready, p.ignitions, p.grid, p.bounds]);

  useEffect(() => {
    if (!p.fly || !map.current) return;
    map.current.flyTo(ll(p.fly.lng, p.fly.lat), Math.round(p.fly.zoom ?? 14), { duration: 1.2 });
  }, [p.fly]);

  return <div ref={el} className="absolute inset-0 z-0 bg-[#0f1519]" data-testid="map-satellite-2d" aria-label="2D satellite map" role="application" />;
}

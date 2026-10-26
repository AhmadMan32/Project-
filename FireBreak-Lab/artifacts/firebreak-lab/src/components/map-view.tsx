import { useEffect, useRef, useState } from 'react';
import * as maplibregl from 'maplibre-gl';
import maplibreWorkerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?url';
import type { CanvasSource, GeoJSONSource, Map as MLMap, MapMouseEvent, ErrorEvent } from 'maplibre-gl';
import type { Line } from '@workspace/api-client-react';
import type { FeatureCollection, Feature } from 'geojson';
import { zoneGeometry, cellCenter, type Bounds, type SimRenderer } from '@/lib/sim';
import {
  cornerBrackets, dashSequence, fmtWidth, handleHtml, ignitionHtml, prefersReducedMotion, rippleHtml, zoneAtLngLat, zoneRing,
  type ClickFx,
} from '@/components/map-fx';

maplibregl.setWorkerUrl(maplibreWorkerUrl);

export type FlyTarget = { lng: number; lat: number; zoom?: number; key: number };

type Props = {
  center: [number, number]; // lng, lat
  bounds: Bounds; // area or terrain bounds
  locked: boolean;
  onMoveArea: (lng: number, lat: number) => void;
  onResizeArea: (widthM: number) => void;
  grid: { rows: number; cols: number } | null;
  renderer: SimRenderer;
  ignitions: { row: number; col: number }[];
  zonesN: number;
  lines: Line[];
  onMapClick: (lng: number, lat: number) => ClickFx;
  /** Zones can be hovered / selected (terrain loaded and current). */
  zonesActive: boolean;
  selectedZone: string | null;
  clickMode: 'ignite' | 'inspect';
  widthM: number;
  onCursor: (c: { lng: number; lat: number; elev: number | null } | null) => void;
  fly: FlyTarget | null;
  terrain3d: boolean;
  onReady?: () => void;
  onError?: (msg: string) => void;
  onFatal?: (msg: string) => void;
};

export function hasWebGL2(): boolean {
  try {
    if (window.matchMedia?.('(max-width: 900px)').matches || window.innerWidth <= 900) return false;
    const c = document.createElement('canvas');
    const gl = c.getContext('webgl2');
    if (!gl) return false;
    gl.getExtension('WEBGL_lose_context')?.loseContext();
    return true;
  } catch {
    return false;
  }
}

const EMPTY: FeatureCollection = { type: 'FeatureCollection', features: [] };

export function MapView(p: Props) {
  const el = useRef<HTMLDivElement>(null);
  const map = useRef<MLMap | null>(null);
  const [loaded, setLoaded] = useState(false);
  const centerMk = useRef<maplibregl.Marker | null>(null);
  const cornerMk = useRef<maplibregl.Marker | null>(null);
  const cb = useRef(p);
  cb.current = p;

  useEffect(() => {
    if (!el.current) return;
    let m: MLMap;
    try {
    m = new maplibregl.Map({
      container: el.current,
      center: p.center,
      zoom: 13.2,
      pitch: 58,
      bearing: -18,
      maxPitch: 85,
      attributionControl: { compact: true },
      style: {
        version: 8,
        glyphs: 'https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf',
        sources: {
          esri: {
            type: 'raster',
            tiles: ['https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}'],
            tileSize: 256,
            maxzoom: 17,
            attribution: 'Imagery © Esri, Maxar, Earthstar Geographics (visual only)',
          },
          osm: {
            type: 'raster',
            tiles: ['https://basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png'],
            tileSize: 256,
            maxzoom: 19,
            attribution: '© OpenStreetMap contributors © CARTO',
          },
          dem: {
            type: 'raster-dem',
            tiles: ['https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png'],
            tileSize: 256,
            maxzoom: 15,
            encoding: 'terrarium',
            attribution: 'DEM: AWS Terrain Tiles (Terrarium)',
          },
        },
        layers: [
          { id: 'bg', type: 'background', paint: { 'background-color': '#0f1519' } },
          { id: 'esri', type: 'raster', source: 'esri', paint: { 'raster-saturation': -0.15, 'raster-contrast': 0.05 } },
          { id: 'osm-fallback', type: 'raster', source: 'osm', layout: { visibility: 'none' } },
          { id: 'hill', type: 'hillshade', source: 'dem', paint: { 'hillshade-exaggeration': 0.25, 'hillshade-shadow-color': '#0b1013' } },
        ],
        terrain: { source: 'dem', exaggeration: 1 },
        sky: { 'sky-color': '#1a2630', 'horizon-color': '#3a4a52', 'fog-color': '#1a2228', 'sky-horizon-blend': 0.6 },
      },
    });
    } catch (err) {
      cb.current.onFatal?.(err instanceof Error ? err.message : String(err));
      return;
    }
    m.addControl(new maplibregl.NavigationControl({ visualizePitch: true, showCompass: true }), 'top-right');
    m.addControl(new maplibregl.TerrainControl({ source: 'dem', exaggeration: 1 }), 'top-right');
    m.addControl(new maplibregl.ScaleControl({ unit: 'metric', maxWidth: 140 }), 'bottom-right');
    let esriTileErrors = 0;
    let osmTileErrors = 0;
    let esriLoaded = false;
    let imageryFallback = false;
    let styleReady = false;
    let overlaysInitialized = false;
    let fatalSent = false;
    let imageryTimer: number | undefined;
    let styleTimer: number | undefined;
    const failTo2d = (message: string) => {
      if (fatalSent) return;
      fatalSent = true;
      cb.current.onFatal?.(message);
    };
    const showOsmFallback = () => {
      if (imageryFallback || !m.getLayer('osm-fallback')) return;
      imageryFallback = true;
      if (imageryTimer !== undefined) window.clearTimeout(imageryTimer);
      m.setLayoutProperty('esri', 'visibility', 'none');
      m.setLayoutProperty('osm-fallback', 'visibility', 'visible');
      cb.current.onError?.('Uydu görüntüsü yüklenemedi; açık harita tabanı gösteriliyor.');
    };
    const initializeOverlays = () => {
      if (overlaysInitialized) return;
      overlaysInitialized = true;
      styleReady = true;
      if (styleTimer !== undefined) window.clearTimeout(styleTimer);
      if (!esriLoaded) {
        imageryTimer = window.setTimeout(() => {
          if (!esriLoaded) showOsmFallback();
        }, 7000);
      }
      m.addSource('sim', {
        type: 'canvas',
        canvas: cb.current.renderer.canvas,
        animate: true,
        coordinates: [[0, 0.001], [0.001, 0.001], [0.001, 0], [0, 0]],
      });
      m.addLayer({ id: 'sim', type: 'raster', source: 'sim', paint: { 'raster-opacity': 0.92, 'raster-fade-duration': 0, 'raster-resampling': 'linear' } });
      m.addSource('area', { type: 'geojson', data: EMPTY });
      m.addLayer({ id: 'area-fill', type: 'fill', source: 'area', paint: { 'fill-color': '#ff6a2b', 'fill-opacity': 0.04 } });
      m.addLayer({ id: 'area-glow', type: 'line', source: 'area', paint: { 'line-color': '#ff8a3d', 'line-width': 9, 'line-opacity': 0.18, 'line-blur': 7 } });
      m.addLayer({ id: 'area-line', type: 'line', source: 'area', paint: { 'line-color': '#ffc58f', 'line-width': 1.8, 'line-dasharray': [3, 2] } });
      m.addSource('area-corners', { type: 'geojson', data: EMPTY });
      m.addLayer({
        id: 'area-corners', type: 'line', source: 'area-corners',
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': '#fff1dc', 'line-width': 3.5, 'line-opacity': 0.95 },
      });
      m.addSource('area-label', { type: 'geojson', data: EMPTY });
      m.addLayer({
        id: 'area-label', type: 'symbol', source: 'area-label',
        layout: { 'text-field': ['get', 't'], 'text-font': ['Open Sans Semibold'], 'text-size': 11, 'text-offset': [0, -1.1], 'text-allow-overlap': true },
        paint: { 'text-color': '#ffd9b5', 'text-halo-color': '#0d1316', 'text-halo-width': 1.6 },
      });
      m.addSource('zgrid', { type: 'geojson', data: EMPTY });
      m.addLayer({ id: 'zgrid', type: 'line', source: 'zgrid', paint: { 'line-color': '#e8e1d4', 'line-opacity': 0.45, 'line-width': 1.1 } });
      m.addSource('zhover', { type: 'geojson', data: EMPTY });
      m.addLayer({ id: 'zhover-fill', type: 'fill', source: 'zhover', paint: { 'fill-color': '#fff1dc', 'fill-opacity': 0.07, 'fill-opacity-transition': { duration: 180 } } });
      m.addLayer({ id: 'zhover-line', type: 'line', source: 'zhover', paint: { 'line-color': '#fff1dc', 'line-width': 1.6, 'line-opacity': 0.7 } });
      m.addSource('zsel', { type: 'geojson', data: EMPTY });
      m.addLayer({ id: 'zsel-fill', type: 'fill', source: 'zsel', paint: { 'fill-color': '#59b0ff', 'fill-opacity': 0.1 } });
      m.addLayer({ id: 'zsel-glow', type: 'line', source: 'zsel', paint: { 'line-color': '#59b0ff', 'line-width': 10, 'line-opacity': 0.35, 'line-blur': 6 } });
      m.addLayer({ id: 'zsel-line', type: 'line', source: 'zsel', layout: { 'line-join': 'round' }, paint: { 'line-color': '#cfe7ff', 'line-width': 2.4 } });
      m.addSource('slines', { type: 'geojson', data: EMPTY });
      m.addLayer({
        id: 'slines-off', type: 'line', source: 'slines', filter: ['==', ['get', 'on'], false],
        paint: { 'line-color': '#7d93a6', 'line-width': 1.5, 'line-opacity': 0.8, 'line-dasharray': [2, 1.2] },
      });
      m.addLayer({
        id: 'slines-glow', type: 'line', source: 'slines', filter: ['==', ['get', 'on'], true],
        paint: { 'line-color': '#3a9bff', 'line-width': 14, 'line-opacity': 0.32, 'line-blur': 8 },
      });
      m.addLayer({
        id: 'slines-pipe', type: 'line', source: 'slines', filter: ['==', ['get', 'on'], true],
        layout: { 'line-cap': 'round' },
        paint: { 'line-color': '#1d5fa8', 'line-width': 4.5 },
      });
      m.addLayer({
        id: 'slines-on', type: 'line', source: 'slines', filter: ['==', ['get', 'on'], true],
        paint: { 'line-color': '#bfe2ff', 'line-width': 2.2, 'line-dasharray': [2, 1.5] },
      });
      m.addSource('zlabels', { type: 'geojson', data: EMPTY });
      m.addLayer({
        id: 'zlabels', type: 'symbol', source: 'zlabels',
        layout: { 'text-field': ['get', 'id'], 'text-font': ['Open Sans Semibold'], 'text-size': 12, 'text-allow-overlap': true },
        paint: { 'text-color': '#f1ebe0', 'text-halo-color': '#0d1316', 'text-halo-width': 1.4, 'text-opacity': 0.9 },
      });
      setLoaded(true);
      cb.current.onReady?.();
    };
    styleTimer = window.setTimeout(() => {
      if (!styleReady) failTo2d('3D harita başlatılamadı; uydu görünümüne geçiliyor.');
    }, 7000);
    const handleContextLost = (event: Event) => {
      event.preventDefault();
      failTo2d('3D harita GPU bağlantısı kesildi; uydu görünümüne geçiliyor.');
    };
    m.getCanvas().addEventListener('webglcontextlost', handleContextLost);
    m.on('sourcedata', (e) => {
      if (e.sourceId === 'esri' && e.isSourceLoaded) {
        esriLoaded = true;
        if (imageryTimer !== undefined) window.clearTimeout(imageryTimer);
      }
    });
    m.on('error', (e: ErrorEvent) => {
      const msg = (e.error && e.error.message) || 'Map error';
      const sourceId = (e as ErrorEvent & { sourceId?: string }).sourceId;
      if (sourceId === 'esri' || /server\.arcgisonline\.com/i.test(msg)) {
        if (++esriTileErrors >= 3) showOsmFallback();
        return;
      }
      if (sourceId === 'osm' && imageryFallback && ++osmTileErrors >= 3) {
        cb.current.onError?.('Açık harita karoları yüklenemedi; internet bağlantısını kontrol edin.');
        return;
      }
      if (
        /webgl|gpu|context lost/i.test(msg) ||
        (/worker|importscripts|dynamically imported module/i.test(msg) && /failed|error|fetch|load/i.test(msg))
      ) {
        failTo2d(msg);
        return;
      }
      if (!styleReady && !sourceId && !/tile|abort|failed to fetch/i.test(msg)) {
        failTo2d(msg);
        return;
      }
      if (!/tile|Failed to fetch|AbortError/i.test(msg)) cb.current.onError?.(msg);
    });
    m.on('style.load', initializeOverlays);
    m.on('load', initializeOverlays);
    if (m.isStyleLoaded()) initializeOverlays();
    m.on('click', (e: MapMouseEvent) => {
      const fx = cb.current.onMapClick(e.lngLat.lng, e.lngLat.lat);
      if (fx && !prefersReducedMotion()) {
        const d = document.createElement('div');
        d.innerHTML = rippleHtml(fx);
        const mk = new maplibregl.Marker({ element: d }).setLngLat(e.lngLat).addTo(m);
        window.setTimeout(() => mk.remove(), 1100);
      }
    });
    let hoverId: string | null = null;
    const setHover = (id: string | null) => {
      if (id === hoverId || !m.getSource('zhover')) return;
      hoverId = id;
      const ring = id ? zoneRing(id, cb.current.bounds, cb.current.zonesN) : null;
      (m.getSource('zhover') as GeoJSONSource).setData(ring
        ? { type: 'Feature', properties: {}, geometry: { type: 'Polygon', coordinates: [ring] } }
        : EMPTY);
    };
    m.on('mousemove', (e: MapMouseEvent) => {
      const elev = m.queryTerrainElevation ? m.queryTerrainElevation(e.lngLat) : null;
      cb.current.onCursor({ lng: e.lngLat.lng, lat: e.lngLat.lat, elev: elev ?? null });
      const z = cb.current.zonesActive ? zoneAtLngLat(e.lngLat.lng, e.lngLat.lat, cb.current.bounds, cb.current.zonesN) : null;
      setHover(z);
      m.getCanvas().style.cursor = z ? (cb.current.clickMode === 'ignite' && !cb.current.locked ? 'crosshair' : 'pointer') : '';
    });
    m.on('mouseout', () => { cb.current.onCursor(null); setHover(null); });

    // Marching-ants selection outline, flowing water in active lines and a breathing selected zone.
    const antSeq = dashSequence(3, 2, 16);
    const flowSeq = dashSequence(2, 1.5, 14);
    let tick = 0;
    const anim = window.setInterval(() => {
      if (!styleReady || prefersReducedMotion() || document.hidden) return;
      tick++;
      try {
        if (!cb.current.locked) m.setPaintProperty('area-line', 'line-dasharray', antSeq[tick % antSeq.length]);
        if (cb.current.lines.some((l) => l.on)) m.setPaintProperty('slines-on', 'line-dasharray', flowSeq[(flowSeq.length - (tick % flowSeq.length)) % flowSeq.length]);
        if (cb.current.selectedZone) m.setPaintProperty('zsel-glow', 'line-opacity', 0.22 + 0.2 * (1 + Math.sin(tick * 0.18)));
      } catch { /* style not ready */ }
    }, 70);

    const mkEl = (kind: 'move' | 'resize', label: string) => {
      const d = document.createElement('div');
      d.innerHTML = handleHtml(kind, label);
      return d;
    };
    const dragCls = (mk: maplibregl.Marker, on: boolean) => mk.getElement().classList.toggle('fb-dragging', on);
    const c = mkEl('move', 'Drag study area');
    centerMk.current = new maplibregl.Marker({ element: c, draggable: true }).setLngLat(p.center).addTo(m);
    centerMk.current.on('dragstart', () => dragCls(centerMk.current!, true));
    centerMk.current.on('dragend', () => {
      dragCls(centerMk.current!, false);
      const ll = centerMk.current!.getLngLat();
      cb.current.onMoveArea(ll.lng, ll.lat);
    });
    const k = mkEl('resize', 'Resize study area');
    cornerMk.current = new maplibregl.Marker({ element: k, draggable: true }).setLngLat([p.bounds[2], p.bounds[1]]).addTo(m);
    cornerMk.current.on('dragstart', () => dragCls(cornerMk.current!, true));
    cornerMk.current.on('dragend', () => {
      dragCls(cornerMk.current!, false);
      const ll = cornerMk.current!.getLngLat();
      const [clng, clat] = cb.current.center;
      const dx = Math.abs(ll.lng - clng) * 111320 * Math.cos((clat * Math.PI) / 180);
      const dy = Math.abs(ll.lat - clat) * 111320;
      cb.current.onResizeArea(Math.max(dx, dy) * 2);
    });
    map.current = m;
    return () => {
      window.clearInterval(anim);
      if (imageryTimer !== undefined) window.clearTimeout(imageryTimer);
      if (styleTimer !== undefined) window.clearTimeout(styleTimer);
      try { m.remove(); } catch { /* ignore */ }
      map.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Area outline + markers
  useEffect(() => {
    const m = map.current;
    if (!m || !loaded) return;
    const [w, s, e, n] = p.bounds;
    (m.getSource('area') as GeoJSONSource).setData({
      type: 'Feature', properties: {},
      geometry: { type: 'Polygon', coordinates: [[[w, n], [e, n], [e, s], [w, s], [w, n]]] },
    });
    centerMk.current?.setLngLat(p.center);
    cornerMk.current?.setLngLat([e, s]);
    centerMk.current?.setDraggable(!p.locked);
    cornerMk.current?.setDraggable(!p.locked);
    centerMk.current?.getElement().classList.toggle('fb-locked', p.locked);
    cornerMk.current?.getElement().classList.toggle('fb-locked', p.locked);
    (m.getSource('area-corners') as GeoJSONSource).setData({
      type: 'FeatureCollection',
      features: cornerBrackets(p.bounds).map((c) => ({ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: c } })),
    });
    (m.getSource('area-label') as GeoJSONSource).setData({
      type: 'Feature', properties: { t: `${fmtWidth(p.widthM)} × ${fmtWidth(p.widthM)}` },
      geometry: { type: 'Point', coordinates: [(w + e) / 2, n] },
    });
    m.setPaintProperty('area-line', 'line-opacity', p.locked ? 0.55 : 1);
    m.setPaintProperty('area-glow', 'line-opacity', p.locked ? 0.08 : 0.18);
    (m.getSource('sim') as CanvasSource).setCoordinates([[w, n], [e, n], [e, s], [w, s]]);
    const g = zoneGeometry(p.bounds, p.zonesN);
    (m.getSource('zgrid') as GeoJSONSource).setData({
      type: 'FeatureCollection',
      features: g.grid.map((c) => ({ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: c } })),
    });
    (m.getSource('zlabels') as GeoJSONSource).setData({
      type: 'FeatureCollection',
      features: g.labels.map((l) => ({ type: 'Feature', properties: { id: l.id }, geometry: { type: 'Point', coordinates: l.pos } })),
    });
  }, [loaded, p.bounds, p.center, p.locked, p.zonesN, p.widthM]);

  // Sprinkler lines
  useEffect(() => {
    const m = map.current;
    if (!m || !loaded) return;
    const g = zoneGeometry(p.bounds, p.zonesN);
    const feats: Feature[] = [];
    for (const l of p.lines) {
      const seg = g.segment(l.from, l.to);
      if (seg) feats.push({ type: 'Feature', properties: { on: l.on, id: l.id }, geometry: { type: 'LineString', coordinates: seg } });
    }
    (m.getSource('slines') as GeoJSONSource).setData({ type: 'FeatureCollection', features: feats });
  }, [loaded, p.lines, p.bounds, p.zonesN]);

  // Selected zone
  useEffect(() => {
    const m = map.current;
    if (!m || !loaded) return;
    const ring = p.selectedZone && p.zonesActive ? zoneRing(p.selectedZone, p.bounds, p.zonesN) : null;
    (m.getSource('zsel') as GeoJSONSource).setData(ring
      ? { type: 'Feature', properties: {}, geometry: { type: 'Polygon', coordinates: [ring] } }
      : EMPTY);
  }, [loaded, p.selectedZone, p.zonesActive, p.bounds, p.zonesN]);

  // Ignitions: animated DOM markers; only newly placed ones play the drop-in animation.
  const ignMk = useRef(new Map<string, maplibregl.Marker>());
  useEffect(() => {
    const m = map.current;
    const cur = ignMk.current;
    if (!m || !loaded) return;
    const want = new Map<string, [number, number]>();
    if (p.grid) {
      const { rows, cols } = p.grid;
      for (const i of p.ignitions) want.set(`${i.row}:${i.col}`, cellCenter(i.row, i.col, p.bounds, rows, cols));
    }
    for (const [k, mk] of cur) if (!want.has(k)) { mk.remove(); cur.delete(k); }
    const fresh = cur.size > 0 || want.size === 1;
    for (const [k, pos] of want) {
      const old = cur.get(k);
      if (old) { old.setLngLat(pos); continue; }
      const d = document.createElement('div');
      d.innerHTML = ignitionHtml(fresh);
      cur.set(k, new maplibregl.Marker({ element: d }).setLngLat(pos).addTo(m));
    }
  }, [loaded, p.ignitions, p.grid, p.bounds]);
  useEffect(() => () => { ignMk.current.forEach((mk) => mk.remove()); ignMk.current.clear(); }, []);

  // Raster overlay: the renderer paints the shared canvas; ask MapLibre to redraw after every paint.
  useEffect(() => {
    return p.renderer.onPaint(() => map.current?.triggerRepaint());
  }, [p.renderer]);

  useEffect(() => {
    if (!p.fly || !map.current) return;
    map.current.flyTo({ center: [p.fly.lng, p.fly.lat], zoom: p.fly.zoom ?? 13.2, speed: 1.1, curve: 1.5, essential: true });
  }, [p.fly]);

  useEffect(() => {
    const m = map.current;
    if (!m || !loaded) return;
    m.setTerrain(p.terrain3d ? { source: 'dem', exaggeration: 1 } : null);
  }, [p.terrain3d, loaded]);

  return <div ref={el} className="absolute inset-0" style={{ position: 'absolute', top: 0, right: 0, bottom: 0, left: 0, width: '100%', height: '100%' }} data-testid="map-satellite" aria-label="Satellite terrain map" role="application" />;
}

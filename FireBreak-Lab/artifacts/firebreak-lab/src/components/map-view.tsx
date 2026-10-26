import { useEffect, useRef, useState } from 'react';
import * as maplibregl from 'maplibre-gl';
import maplibreWorkerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?url';
import type { CanvasSource, GeoJSONSource, Map as MLMap, MapMouseEvent, ErrorEvent } from 'maplibre-gl';
import type { Line } from '@workspace/api-client-react';
import type { FeatureCollection, Feature } from 'geojson';
import { zoneGeometry, cellCenter, type Bounds, type SimRenderer } from '@/lib/sim';

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
  onMapClick: (lng: number, lat: number) => void;
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
      m.addLayer({ id: 'area-fill', type: 'fill', source: 'area', paint: { 'fill-color': '#ff6a2b', 'fill-opacity': 0.05 } });
      m.addLayer({ id: 'area-line', type: 'line', source: 'area', paint: { 'line-color': '#ffb36b', 'line-width': 2, 'line-dasharray': [3, 2] } });
      m.addSource('zgrid', { type: 'geojson', data: EMPTY });
      m.addLayer({ id: 'zgrid', type: 'line', source: 'zgrid', paint: { 'line-color': '#e8e1d4', 'line-opacity': 0.55, 'line-width': 1.25 } });
      m.addSource('slines', { type: 'geojson', data: EMPTY });
      m.addLayer({
        id: 'slines-glow', type: 'line', source: 'slines', filter: ['==', ['get', 'on'], true],
        paint: { 'line-color': '#3a9bff', 'line-width': 10, 'line-opacity': 0.3, 'line-blur': 6 },
      });
      m.addLayer({
        id: 'slines', type: 'line', source: 'slines',
        paint: {
          'line-color': ['case', ['get', 'on'], '#59b0ff', '#7d93a6'],
          'line-width': ['case', ['get', 'on'], 3.5, 1.5],
          'line-dasharray': [2, 1.2],
        },
      });
      m.addSource('zlabels', { type: 'geojson', data: EMPTY });
      m.addLayer({
        id: 'zlabels', type: 'symbol', source: 'zlabels',
        layout: { 'text-field': ['get', 'id'], 'text-font': ['Open Sans Semibold'], 'text-size': 12, 'text-allow-overlap': true },
        paint: { 'text-color': '#f1ebe0', 'text-halo-color': '#0d1316', 'text-halo-width': 1.4, 'text-opacity': 0.9 },
      });
      m.addSource('ign', { type: 'geojson', data: EMPTY });
      m.addLayer({ id: 'ign-halo', type: 'circle', source: 'ign', paint: { 'circle-radius': 13, 'circle-color': '#ff5a1f', 'circle-opacity': 0.25 } });
      m.addLayer({
        id: 'ign', type: 'circle', source: 'ign',
        paint: { 'circle-radius': 5.5, 'circle-color': '#ff5a1f', 'circle-stroke-color': '#fff1dc', 'circle-stroke-width': 2 },
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
    m.on('click', (e: MapMouseEvent) => cb.current.onMapClick(e.lngLat.lng, e.lngLat.lat));
    m.on('mousemove', (e: MapMouseEvent) => {
      const elev = m.queryTerrainElevation ? m.queryTerrainElevation(e.lngLat) : null;
      cb.current.onCursor({ lng: e.lngLat.lng, lat: e.lngLat.lat, elev: elev ?? null });
    });
    m.on('mouseout', () => cb.current.onCursor(null));

    const mkEl = (cls: string, label: string) => {
      const d = document.createElement('div');
      d.className = cls;
      d.setAttribute('role', 'button');
      d.setAttribute('aria-label', label);
      d.title = label;
      return d;
    };
    const c = mkEl('h-5 w-5 rounded-full border-2 border-[#fff1dc] bg-[#ff6a2b]/80 shadow-lg cursor-move', 'Drag study area');
    centerMk.current = new maplibregl.Marker({ element: c, draggable: true }).setLngLat(p.center).addTo(m);
    centerMk.current.on('dragend', () => {
      const ll = centerMk.current!.getLngLat();
      cb.current.onMoveArea(ll.lng, ll.lat);
    });
    const k = mkEl('h-4 w-4 rotate-45 border-2 border-[#fff1dc] bg-[#ffb36b] cursor-nwse-resize', 'Resize study area');
    cornerMk.current = new maplibregl.Marker({ element: k, draggable: true }).setLngLat([p.bounds[2], p.bounds[1]]).addTo(m);
    cornerMk.current.on('dragend', () => {
      const ll = cornerMk.current!.getLngLat();
      const [clng, clat] = cb.current.center;
      const dx = Math.abs(ll.lng - clng) * 111320 * Math.cos((clat * Math.PI) / 180);
      const dy = Math.abs(ll.lat - clat) * 111320;
      cb.current.onResizeArea(Math.max(dx, dy) * 2);
    });
    map.current = m;
    return () => {
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
    const op = p.locked ? '0.35' : '1';
    centerMk.current?.getElement().style.setProperty('opacity', op);
    cornerMk.current?.getElement().style.setProperty('opacity', op);
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
  }, [loaded, p.bounds, p.center, p.locked, p.zonesN]);

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

  // Ignitions
  useEffect(() => {
    const m = map.current;
    if (!m || !loaded || !p.grid) {
      if (m && loaded) (m.getSource('ign') as GeoJSONSource).setData(EMPTY);
      return;
    }
    const { rows, cols } = p.grid;
    (m.getSource('ign') as GeoJSONSource).setData({
      type: 'FeatureCollection',
      features: p.ignitions.map((i) => ({
        type: 'Feature', properties: {},
        geometry: { type: 'Point', coordinates: cellCenter(i.row, i.col, p.bounds, rows, cols) },
      })),
    });
  }, [loaded, p.ignitions, p.grid, p.bounds]);

  // Raster overlay: the renderer paints the shared canvas; ask MapLibre to redraw after every paint.
  useEffect(() => {
    return p.renderer.onPaint(() => map.current?.triggerRepaint());
  }, [p.renderer]);

  useEffect(() => {
    if (!p.fly || !map.current) return;
    map.current.flyTo({ center: [p.fly.lng, p.fly.lat], zoom: p.fly.zoom ?? 13.2, speed: 1.4, essential: true });
  }, [p.fly]);

  useEffect(() => {
    const m = map.current;
    if (!m || !loaded) return;
    m.setTerrain(p.terrain3d ? { source: 'dem', exaggeration: 1 } : null);
  }, [p.terrain3d, loaded]);

  return <div ref={el} className="absolute inset-0" style={{ position: 'absolute', top: 0, right: 0, bottom: 0, left: 0, width: '100%', height: '100%' }} data-testid="map-satellite" aria-label="Satellite terrain map" role="application" />;
}

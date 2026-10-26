import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { Link, useSearch } from 'wouter';
import { useQueryClient } from '@tanstack/react-query';
import {
  Mountain, Play, Pause, StepForward, Flame, Save, GitCompare, ChevronLeft, ChevronDown,
  AlertTriangle, Loader2, Layers, Download, RotateCcw, Radio, MapPin, CheckCircle2, Crosshair,
} from 'lucide-react';
import {
  useLoadTerrain, useCreateRun, useCompareStrategies, useSaveScenario, useListScenarios, useGetRun,
  getListScenariosQueryKey, getListRunsQueryKey, getGetRunQueryKey,
} from '@workspace/api-client-react';
import type { Frame, Run, ScenarioInput, Terrain } from '@workspace/api-client-react';
import { MapView, hasWebGL2, type FlyTarget } from '@/components/map-view';
import { LeafletView } from '@/components/leaflet-view';
import type { ClickFx } from '@/components/map-fx';
import { NavBar, SearchBox, StatusBanner, WindRose, ScenarioFields } from '@/components/bits';
import { useI18n, type Key } from '@/lib/i18n';
import {
  DEFAULT_SCENARIO, BURNABLE, FUEL_NAMES, areaBounds, lngLatToCell, fmtTime, fmtL, fmtHa, fireStatus, errMsg, type Bounds,
  SimRenderer, zoneDetails, zoneIdAt, type ZoneDetail,
} from '@/lib/sim';
import { Button } from '@/components/ui/button';
import { Slider } from '@/components/ui/slider';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Switch } from '@/components/ui/switch';
import { useToast } from '@/hooks/use-toast';
import { cn } from '@/lib/utils';

const SPEEDS = [1, 2, 5, 10, 20, 30, 60];
const FPS_OPTIONS = [90, 120] as const;
const terrainKeyOf = (s: ScenarioInput) => JSON.stringify([s.lat, s.lon, s.widthM, s.cellSize, s.zones]);
const EASE = [0.16, 1, 0.3, 1] as const;

/** Content of a panel tab: fades and lifts in each time the tab opens. */
function Pane({ children }: { children: ReactNode }) {
  return (
    <motion.div className="space-y-5" initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.35, ease: EASE }}>
      {children}
    </motion.div>
  );
}

const readFx = () => { try { return localStorage.getItem('fb-fx') !== '0'; } catch { return true; } };

export default function LabPage() {
  const { t, lang } = useI18n();
  const { toast } = useToast();
  const qc = useQueryClient();
  const search = useSearch();
  const params = useMemo(() => new URLSearchParams(search), [search]);
  const scenarioParam = params.get('scenario');
  const runParam = params.get('run');

  const [sc, setSc] = useState<ScenarioInput>(DEFAULT_SCENARIO);
  const set = (p: Partial<ScenarioInput>) => setSc((s) => ({ ...s, ...p }));
  const [terrain, setTerrain] = useState<Terrain | null>(null);
  const [terrainKey, setTerrainKey] = useState<string>('');
  const [ignitions, setIgnitions] = useState<{ row: number; col: number }[]>([]);
  const [run, setRun] = useState<Run | null>(null);
  const [frames, setFrames] = useState<Frame[]>([]);
  const [idx, setIdx] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [speed, setSpeed] = useState(10);
  const [fps, setFps] = useState<(typeof FPS_OPTIONS)[number]>(120);
  // Fractional frame position of the playback clock; kept in a ref so the 120 Hz loop never re-renders React.
  const posRef = useRef(0);
  const renderer = useMemo(() => new SimRenderer(), []);
  const [clickMode, setClickMode] = useState<'ignite' | 'inspect'>('ignite');
  const [selZone, setSelZone] = useState<string | null>(null);
  const [mode, setMode] = useState<'live' | 'replay'>('replay');
  const [compare, setCompare] = useState<Run[] | null>(null);
  const [cursor, setCursor] = useState<{ lng: number; lat: number; elev: number | null } | null>(null);
  const [fly, setFly] = useState<FlyTarget | null>(null);
  const [panelOpen, setPanelOpen] = useState(true);
  const [tab, setTab] = useState('setup');
  const [mapErr, setMapErr] = useState<string | null>(null);
  const wsRef = useRef<WebSocket | null>(null);
  const [mapMode, setMapMode] = useState<'3d' | '2d'>(() => (hasWebGL2() ? '3d' : '2d'));
  const [fallbackReason, setFallbackReason] = useState<string | null>(() => (hasWebGL2() ? null : 'uyumlu 2D görünüm'));
  const [fx, setFx] = useState(readFx);
  useEffect(() => { try { localStorage.setItem('fb-fx', fx ? '1' : '0'); } catch { /* storage blocked */ } }, [fx]);

  const loadTerrainM = useLoadTerrain();
  const createRunM = useCreateRun();
  const compareM = useCompareStrategies();
  const saveM = useSaveScenario();
  const scenarios = useListScenarios({ query: { queryKey: getListScenariosQueryKey(), enabled: !!scenarioParam } });
  const replayRun = useGetRun(runParam ?? '', { query: { queryKey: getGetRunQueryKey(runParam ?? ''), enabled: !!runParam } });

  const closeWs = useCallback(() => {
    if (wsRef.current) {
      wsRef.current.onclose = null;
      wsRef.current.close();
      wsRef.current = null;
    }
  }, []);
  useEffect(() => closeWs, [closeWs]);

  // Load scenario from URL
  const loadedScenarioFor = useRef<string | null>(null);
  useEffect(() => {
    if (!scenarioParam || !scenarios.data || loadedScenarioFor.current === scenarioParam) return;
    const found = scenarios.data.find((x) => x.id === scenarioParam);
    if (found) {
      loadedScenarioFor.current = scenarioParam;
      const { id: _id, createdAt: _c, ...rest } = found;
      void _id; void _c;
      setSc({ ...DEFAULT_SCENARIO, ...rest });
      setFly({ lng: found.lon, lat: found.lat, key: Date.now() });
      toast({ title: t('scenarios'), description: found.name });
    }
  }, [scenarioParam, scenarios.data, t, toast]);

  // Load run replay from URL
  const loadedRunFor = useRef<string | null>(null);
  useEffect(() => {
    const r = replayRun.data;
    if (!r || loadedRunFor.current === r.id) return;
    loadedRunFor.current = r.id;
    closeWs();
    setSc({ ...DEFAULT_SCENARIO, ...r.scenario });
    setRun(r);
    setFrames(r.frames ?? []);
    setIdx(0);
    setMode('replay');
    setPlaying(false);
    setTab('run');
    setFly({ lng: r.scenario.lon, lat: r.scenario.lat, key: Date.now() });
  }, [replayRun.data, closeWs]);

  const key = terrainKeyOf(sc);
  const terrainStale = !!terrain && terrainKey !== key;
  const areaB = useMemo(() => areaBounds(sc.lat, sc.lon, sc.widthM), [sc.lat, sc.lon, sc.widthM]);
  const terrainB = terrain && !terrainStale ? (terrain.bounds as Bounds) : null;

  // grid used for rendering: terrain, else replay frames
  const replayGrid = useMemo(() => {
    if (terrainB || frames.length === 0) return null;
    const n = Math.round(Math.sqrt(frames[0].state.length));
    return n * n === frames[0].state.length ? { rows: n, cols: n } : null;
  }, [terrainB, frames]);
  const grid = terrainB && terrain ? { rows: terrain.rows, cols: terrain.cols } : replayGrid;
  const bounds: Bounds = terrainB ?? areaB;
  const frame = frames.length ? frames[Math.min(idx, frames.length - 1)] : null;
  const status = fireStatus(run, frames, idx);
  const running = createRunM.isPending || compareM.isPending;
  const locked = running || status === 'fire' || playing;

  const terrainStats = useMemo(() => {
    if (!terrain) return null;
    const counts: Record<number, number> = {};
    let burn = 0, emin = Infinity, emax = -Infinity, ssum = 0;
    terrain.fuel.forEach((f, i) => {
      counts[f] = (counts[f] ?? 0) + 1;
      if (BURNABLE.has(f)) burn++;
      const e = terrain.elevation[i];
      if (e < emin) emin = e;
      if (e > emax) emax = e;
      ssum += terrain.slope[i] ?? 0;
    });
    const total = terrain.fuel.length;
    return {
      burnPct: (burn / total) * 100, emin, emax, meanSlope: ssum / total,
      classes: Object.entries(counts).map(([k, v]) => ({ code: Number(k), pct: (v / total) * 100 })).sort((a, b) => b.pct - a.pct),
    };
  }, [terrain]);

  const fuelArr = terrainB ? terrain?.fuel : undefined;
  const gridRows = grid?.rows ?? 0;
  const gridCols = grid?.cols ?? 0;
  const selected: ZoneDetail | null = useMemo(
    () => (terrain && !terrainStale && selZone ? zoneDetails(terrain, sc.zones, selZone, frame, lang) : null),
    [terrain, terrainStale, selZone, frame, sc.zones, lang],
  );

  // The renderer derives smooth fire-front motion, embers and smoke from the whole frame history.
  const scene = useMemo(
    () => (gridRows > 0 ? { rows: gridRows, cols: gridCols, fuel: fuelArr, frames, zones: sc.zones } : null),
    [gridRows, gridCols, fuelArr, frames, sc.zones],
  );
  useEffect(() => { renderer.setScene(scene); }, [renderer, scene]);
  useEffect(() => { renderer.setWind(sc.windDir, sc.windSpeed); }, [renderer, sc.windDir, sc.windSpeed]);
  useEffect(() => { renderer.setEffects(fx); }, [renderer, fx]);
  useEffect(() => () => renderer.clear(), [renderer]);

  // Replay playback clock. Simulated time advances by a fixed step per tick, so the clock runs at
  // the chosen FPS (90 or 120) and the fire front moves continuously between simulation steps.
  const looping = playing && mode === 'replay' && frames.length > 0;
  useEffect(() => {
    if (!looping) return;
    const last = frames.length - 1;
    const stepDt = last > 0 ? Math.max(1e-9, (frames[last].time - frames[0].time) / last) : 10;
    const interval = 1000 / fps;
    let pos = Math.min(posRef.current, last);
    let shown = Math.floor(pos);
    let acc = interval; // paint on the first tick
    let prev = performance.now();
    let lastTick = prev - interval;
    let raf = 0;
    const tick = (now: number) => {
      raf = requestAnimationFrame(tick);
      acc += now - prev;
      prev = now;
      if (acc < interval) return;
      // Keep the remainder so 90 FPS stays even on a 120 Hz display; clamp after a background tab.
      acc = Math.min(acc - interval, interval);
      // Advance by real elapsed time, so a slow GPU drops frames instead of slowing the clock.
      const elapsed = Math.min(100, now - lastTick);
      lastTick = now;
      pos = Math.min(last, pos + (speed * elapsed) / 1000 / stepDt);
      posRef.current = pos;
      const f = Math.floor(pos);
      if (gridRows > 0) renderer.setPosition(pos);
      if (f !== shown) { shown = f; setIdx(f); }
      if (pos >= last) setPlaying(false);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [looping, frames, speed, fps, gridRows, renderer]);

  // Keep the clock in step with frame changes made outside playback (scrubbing, live updates, new runs).
  useEffect(() => {
    if (!looping) posRef.current = idx;
  }, [idx, looping]);

  // Static position whenever playback is not driving the raster.
  useEffect(() => {
    if (looping || !scene) return;
    renderer.setPosition(Math.min(idx, Math.max(0, frames.length - 1)));
  }, [looping, scene, idx, frames.length, renderer]);

  const wsSend = (msg: object) => {
    const ws = wsRef.current;
    if (ws && ws.readyState === WebSocket.OPEN) { ws.send(JSON.stringify(msg)); return true; }
    return false;
  };

  const startLive = (r: Run, fr: Frame[]) => {
    closeWs();
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    let ws: WebSocket;
    try {
      ws = new WebSocket(`${proto}//${location.host}/api/ws/runs/${r.id}`);
    } catch {
      setMode('replay'); setPlaying(true); return;
    }
    wsRef.current = ws;
    setMode('live');
    setPlaying(true);
    ws.onopen = () => ws.send(JSON.stringify({ action: 'speed', speed }));
    ws.onmessage = (ev) => {
      try {
        const m = JSON.parse(ev.data);
        if (m.type === 'frame' && m.frame) {
          const f = m.frame as Frame;
          const i = fr.findIndex((x) => x.step === f.step);
          if (i >= 0) setIdx(i);
        } else if (m.type === 'complete') {
          setIdx(fr.length - 1);
          setPlaying(false);
          setMode('replay');
          closeWs();
        }
      } catch { /* ignore malformed */ }
    };
    ws.onerror = () => { setMode('replay'); };
    ws.onclose = () => { wsRef.current = null; setMode('replay'); };
  };

  const togglePlay = () => {
    if (!frames.length) return;
    if (mode === 'live' && wsSend({ action: playing ? 'pause' : 'play' })) { setPlaying(!playing); return; }
    if (!playing && idx >= frames.length - 1) { posRef.current = 0; setIdx(0); }
    setPlaying(!playing);
  };
  const stepOnce = () => {
    if (mode === 'live' && wsSend({ action: 'step' })) { setPlaying(false); return; }
    setPlaying(false);
    setIdx((i) => Math.min(i + 1, frames.length - 1));
  };
  const changeSpeed = (v: number) => { setSpeed(v); wsSend({ action: 'speed', speed: v }); };
  const scrub = (v: number) => { closeWs(); setMode('replay'); setPlaying(false); setIdx(v); };

  const doLoadTerrain = () => {
    closeWs();
    setRun(null); setFrames([]); setIdx(0); setPlaying(false); setIgnitions([]); setCompare(null);
    loadTerrainM.mutate({ data: sc }, {
      onSuccess: (tr) => {
        setTerrain(tr); setTerrainKey(key);
          const [west, south, east, north] = tr.bounds as Bounds;
          const zoom = Math.max(10, Math.min(16, 14 + Math.log2(2000 / sc.widthM)));
          setFly({ lng: (west + east) / 2, lat: (south + north) / 2, zoom, key: Date.now() });
        toast({ title: t('terrainReady'), description: `${tr.rows} × ${tr.cols} · ${tr.cellSize.toFixed(1)} m · ${tr.source}` });
      },
      onError: (e) => { setTerrain(null); setTerrainKey(''); toast({ title: t('terrainFail'), description: errMsg(e), variant: 'destructive' }); },
    });
  };

  const onMapClick = (lng: number, lat: number): ClickFx => {
    if (!terrain || terrainStale) return null;
    // While the run is locked (or in inspect mode) a click selects the zone under it instead of placing an ignition.
    const inspect = clickMode === 'inspect' || locked;
    const c = lngLatToCell(lng, lat, terrain.bounds as Bounds, terrain.rows, terrain.cols);
    if (!c) { if (!inspect) toast({ title: t('outside') }); return null; }
    if (inspect) {
      setSelZone(zoneIdAt(c.row, c.col, terrain.rows, sc.zones));
      setPanelOpen(true);
      setTab('zone');
      return 'inspect';
    }
    const f = terrain.fuel[c.row * terrain.cols + c.col];
    if (!BURNABLE.has(f)) { toast({ title: t('notBurnable'), description: `${FUEL_NAMES[f] ?? 'class'} (${f})` }); return 'invalid'; }
    if (run) { closeWs(); setRun(null); setFrames([]); setIdx(0); }
    const exists = ignitions.some((x) => x.row === c.row && x.col === c.col);
    setIgnitions((cur) => (cur.some((x) => x.row === c.row && x.col === c.col) ? cur.filter((x) => !(x.row === c.row && x.col === c.col)) : [...cur, c]));
    return exists ? 'remove' : 'ignite';
  };

  const canRun = !!terrain && !terrainStale && ignitions.length > 0 && !running && !loadTerrainM.isPending;
  const runInput = () => ({ scenario: sc, terrainId: terrain!.id, ignitions, maxSteps: 180, repeats: 5 });

  const doRun = () => {
    if (!canRun) return;
    setCompare(null);
    createRunM.mutate({ data: runInput() }, {
      onSuccess: (r) => {
        qc.invalidateQueries({ queryKey: getListRunsQueryKey() });
        const fr = r.frames ?? [];
        setRun(r); setFrames(fr); setIdx(0); setTab('run');
        if (fr.length) startLive(r, fr);
      },
      onError: (e) => toast({ title: t('runFailed'), description: errMsg(e), variant: 'destructive' }),
    });
  };
  const doCompare = () => {
    if (!canRun) return;
    compareM.mutate({ data: runInput() }, {
      onSuccess: (rs) => { setCompare(rs); setTab('data'); qc.invalidateQueries({ queryKey: getListRunsQueryKey() }); },
      onError: (e) => toast({ title: t('compareFailed'), description: errMsg(e), variant: 'destructive' }),
    });
  };
  const doSave = () => {
    saveM.mutate({ data: sc }, {
      onSuccess: (s) => { qc.invalidateQueries({ queryKey: getListScenariosQueryKey() }); toast({ title: t('saveScenario'), description: s.name }); },
      onError: (e) => toast({ title: t('saveFailed'), description: errMsg(e), variant: 'destructive' }),
    });
  };

  const events = useMemo(() => {
    // frame.events is cumulative: use only the current frame
    const f = frames.length ? frames[Math.min(idx, frames.length - 1)] : null;
    return (f?.events ?? []).map((e, j) => ({ ...e, k: `${j}-${e.time}-${e.kind}-${e.target}` })).reverse();
  }, [frames, idx]);

  const panel = (
    <Tabs value={tab} onValueChange={setTab} className="flex min-h-0 flex-1 flex-col">
      <TabsList className="mx-3 mt-2 grid grid-cols-4 bg-black/30">
        {([['setup', t('settings').split(' ')[0]], ['run', t('run').split(' ')[0]], ['zone', t('zoneTab')], ['data', t('zonesT')]] as const).map(([v, label]) => (
          <TabsTrigger key={v} value={v} data-testid={`tab-${v}`} className="relative data-[state=active]:bg-transparent data-[state=active]:shadow-none">
            {tab === v && (
              <motion.span layoutId="panel-tab" className="absolute inset-0 rounded-md bg-background shadow-[0_2px_10px_-2px_rgb(0_0_0/0.5),inset_0_0_0_1px_hsl(36_30%_92%/0.06)]" transition={{ type: 'spring', stiffness: 460, damping: 36 }} />
            )}
            <span className="relative">{label}</span>
            {v === 'run' && run && status === 'fire' && <span className="pulse-fire absolute right-1 top-1 h-1.5 w-1.5 rounded-full bg-[#ff5a1f]" />}
          </TabsTrigger>
        ))}
      </TabsList>
      <div className="min-h-0 flex-1 overflow-y-auto px-4 pb-5 pt-3">
        <TabsContent value="setup" className="mt-0">
          <Pane>
          <TerrainCard
            terrain={terrain} stale={terrainStale} pending={loadTerrainM.isPending} error={loadTerrainM.isError ? errMsg(loadTerrainM.error) : null}
            stats={terrainStats} onLoad={doLoadTerrain} disabled={locked}
          />
          <div className="grid grid-cols-2 gap-1 rounded-lg bg-black/30 p-1" role="radiogroup" aria-label="Harita tıklama modu">
            {([['ignite', Flame, t('clickIgnite'), 'bg-gradient-to-r from-[#ff5a1f]/35 to-[#ff8a3d]/20 shadow-[inset_0_0_0_1px_hsl(18_92%_55%/0.45),0_0_18px_-6px_hsl(18_92%_55%/0.8)]', 'text-[#ffd2bd]'],
               ['inspect', Crosshair, t('clickInspect'), 'bg-gradient-to-r from-[#3a9bff]/30 to-[#59b0ff]/15 shadow-[inset_0_0_0_1px_hsl(205_85%_58%/0.45),0_0_18px_-6px_hsl(205_85%_58%/0.8)]', 'text-[#cfe7ff]']] as const).map(([m, Icon, label, pill, txt]) => (
              <button key={m} role="radio" aria-checked={clickMode === m} onClick={() => setClickMode(m)} disabled={m === 'ignite' && locked}
                className={cn('relative h-8 rounded-md text-xs disabled:opacity-50', clickMode === m ? txt : 'text-muted-foreground hover:bg-white/5 hover:text-foreground')} data-testid={`button-mode-${m}`}>
                {clickMode === m && <motion.span layoutId="click-mode" className={cn('absolute inset-0 rounded-md', pill)} transition={{ type: 'spring', stiffness: 420, damping: 34 }} />}
                <span className="relative inline-flex items-center"><Icon className={cn('mr-1 h-3.5 w-3.5', clickMode === m && m === 'ignite' && 'fb-brand-flame')} />{label}</span>
              </button>
            ))}
          </div>
          <section className="space-y-3">
            <div className="flex items-center justify-between">
              <h3 className="eyebrow">{t('ignitions')} · <span className="num">{ignitions.length}</span></h3>
              {ignitions.length > 0 && (
                <button className="text-[11px] text-muted-foreground hover:text-foreground" onClick={() => setIgnitions([])} disabled={locked} data-testid="button-clear-ignitions">
                  {t('clear')}
                </button>
              )}
            </div>
            <p className="text-xs leading-relaxed text-muted-foreground">{t('ignitionHint')}</p>
            <ul className="flex flex-wrap gap-1.5">
              <AnimatePresence initial={false} mode="popLayout">
                {ignitions.map((i, n) => (
                  <motion.li key={`${i.row}-${i.col}`} layout
                    initial={{ opacity: 0, scale: 0.6, y: -6 }} animate={{ opacity: 1, scale: 1, y: 0 }} exit={{ opacity: 0, scale: 0.6 }}
                    transition={{ type: 'spring', stiffness: 500, damping: 30 }}
                    className="num flex items-center gap-1 rounded-md border border-[#ff5a1f]/40 bg-[#ff5a1f]/10 px-2 py-0.5 text-[11px] text-[#ffc6a8] shadow-[0_0_12px_-6px_#ff5a1f]">
                    <Flame className="fb-brand-flame h-3 w-3" /> r{i.row} c{i.col}
                    <button aria-label={t('remove')} className="ml-0.5 opacity-60 hover:scale-125 hover:opacity-100" disabled={locked} onClick={() => setIgnitions((c) => c.filter((_, k) => k !== n))} data-testid={`button-remove-ignition-${n}`}>×</button>
                  </motion.li>
                ))}
              </AnimatePresence>
            </ul>
          </section>
          <div className="grid grid-cols-2 gap-2">
            <Button onClick={doRun} disabled={!canRun} className="fb-cta col-span-2 h-10 font-semibold" data-testid="button-run">
              {createRunM.isPending ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <Play className="mr-2 h-4 w-4" />}
              {createRunM.isPending ? t('running') : t('run')}
            </Button>
            <Button variant="secondary" onClick={doCompare} disabled={!canRun} className="text-xs" data-testid="button-compare">
              {compareM.isPending ? <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" /> : <GitCompare className="mr-1.5 h-3.5 w-3.5" />}
              {compareM.isPending ? t('comparing') : t('compare')}
            </Button>
            <Button variant="secondary" onClick={doSave} disabled={saveM.isPending || !sc.name.trim()} className="text-xs" data-testid="button-save-scenario">
              {saveM.isPending ? <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" /> : <Save className="mr-1.5 h-3.5 w-3.5" />}
              {t('saveScenario')}
            </Button>
          </div>
          {!canRun && !running && (
            <p className="flex items-start gap-1.5 text-[11px] text-muted-foreground" data-testid="text-run-blocker">
              <AlertTriangle className="mt-0.5 h-3 w-3 shrink-0 text-[#e8b23a]" />
              {!terrain ? t('terrainNone') : terrainStale ? t('terrainStale') : t('ignitionHint')}
            </p>
          )}
          <section className="space-y-3 border-t border-white/10 pt-4">
            <h3 className="eyebrow">{t('settings')}</h3>
            <ScenarioFields s={sc} set={set} disabled={locked} />
          </section>
          </Pane>
        </TabsContent>

        <TabsContent value="run" className="mt-0">
          <Pane>
          {!run ? (
            <EmptyHint icon={<Play className="h-5 w-5" />} text={t('ignitionHint')} />
          ) : (
            <>
              <div className="flex items-center justify-between">
                <div>
                  <div className="eyebrow">{run.name}</div>
                  <div className="num text-[11px] text-muted-foreground">#{run.id.slice(0, 8)} · {t(`s_${run.strategy}` as Key) ?? run.strategy} · seed {run.seed}</div>
                </div>
                <a href={`/api/runs/${run.id}/export.csv`} download className="flex items-center gap-1 rounded-md border border-border px-2 py-1 text-[11px] hover:bg-white/5" data-testid="link-export-csv">
                  <Download className="h-3 w-3" /> CSV
                </a>
              </div>
              <div className="grid grid-cols-2 gap-2">
                <Metric i={0} label={t('time')} value={fmtTime(frame?.time ?? 0)} />
                <Metric i={1} label={t('burned')} value={fmtHa(frame?.burnedHa ?? 0)} tone="fire" active={status === 'fire'} />
                <Metric i={2} label={t('water')} value={fmtL(frame?.waterL ?? 0)} tone="water" active={!!frame?.lines.some((l) => l.on)} />
                <Metric i={3} label={t('crossing')} value={run.crossingTime == null ? t('noCrossing') : fmtTime(run.crossingTime)} small={run.crossingTime == null} />
              </div>
              <div className="rounded-lg border border-white/10 bg-black/20 p-3 text-[11px] text-muted-foreground">
                <span className="num">{t('status')}: {run.status === 'completed' ? t('completed') : t('truncatedS')}</span>
                <span className="num"> · {fmtTime(run.duration)} · {fmtHa(run.burnedHa)} · {fmtL(run.waterL)}</span>
              </div>
              <div className="flex items-center justify-between gap-2 rounded-lg border border-white/10 bg-black/20 p-2.5">
                <div className="min-w-0">
                  <label htmlFor="fps" className="eyebrow !text-[9px]">{t('fpsLabel')}</label>
                  <p className="text-[10px] leading-snug text-muted-foreground">{t('fpsNote')}</p>
                </div>
                <select id="fps" value={fps} onChange={(e) => setFps(Number(e.target.value) as (typeof FPS_OPTIONS)[number])}
                  className="fb-select num h-9 shrink-0 rounded-lg border border-border bg-black/30 px-2 text-xs" data-testid="select-fps">
                  {FPS_OPTIONS.map((f) => <option key={f} value={f}>{f} FPS</option>)}
                </select>
              </div>
              <section>
                <h3 className="eyebrow mb-2">{t('events')}</h3>
                {events.length === 0 ? (
                  <p className="text-xs text-muted-foreground">{t('noEvents')}</p>
                ) : (
                  <ol className="max-h-72 space-y-1 overflow-y-auto overflow-x-hidden pr-1" aria-live="polite" data-testid="list-events">
                    <AnimatePresence initial={false}>
                    {events.map((e) => (
                      <motion.li key={e.k} layout="position"
                        initial={{ opacity: 0, x: -14, backgroundColor: 'rgba(255,138,61,0.18)' }}
                        animate={{ opacity: 1, x: 0, backgroundColor: 'rgba(255,255,255,0.03)' }}
                        exit={{ opacity: 0, x: 14 }}
                        transition={{ duration: 0.35, ease: EASE, backgroundColor: { duration: 1.2 } }}
                        className="flex items-center gap-2 rounded-md px-2 py-1.5 text-xs">
                        <span className="num w-12 shrink-0 text-muted-foreground">{fmtTime(e.time)}</span>
                        <span className={cn('h-1.5 w-1.5 shrink-0 rounded-full', e.kind === 'ignition' ? 'bg-[#ff5a1f]' : e.kind === 'crossing' ? 'bg-[#e8b23a]' : e.kind === 'line_on' ? 'bg-[#3a9bff]' : 'bg-[#7d93a6]')} />
                        <span>{t(`ev_${e.kind}` as Key) ?? e.kind}</span>
                        <span className="num ml-auto text-muted-foreground">{e.target}</span>
                      </motion.li>
                    ))}
                    </AnimatePresence>
                  </ol>
                )}
              </section>
            </>
          )}
          </Pane>
        </TabsContent>

        <TabsContent value="zone" className="mt-0">
          <Pane>
          {!terrain || terrainStale ? (
            <EmptyHint icon={<Mountain className="h-5 w-5" />} text={t('terrainFirst')} />
          ) : !selected ? (
            <EmptyHint icon={<Crosshair className="h-5 w-5" />} text={`${t('noZone')} ${t('inspectHint')}`} />
          ) : (
            <AnimatePresence mode="wait">
              <motion.div key={selected.id} initial={{ opacity: 0, x: 18 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -18 }} transition={{ duration: 0.3, ease: EASE }}>
                <ZoneCard d={selected} t={t} />
              </motion.div>
            </AnimatePresence>
          )}
          </Pane>
        </TabsContent>

        <TabsContent value="data" className="mt-0">
          <Pane>
          {compare && <CompareTable runs={compare} />}
          {!frame ? (
            !compare && <EmptyHint icon={<Layers className="h-5 w-5" />} text={t('ignitionHint')} />
          ) : (
            <>
              <section>
                <h3 className="eyebrow mb-2">{t('zonesT')}</h3>
                <table className="w-full text-xs" data-testid="table-zones">
                  <thead className="text-left text-muted-foreground"><tr><th className="py-1 font-normal">ID</th><th className="font-normal">{t('status')}</th><th className="text-right font-normal">%</th><th className="text-right font-normal">{t('wetCol')}</th></tr></thead>
                  <tbody>
                    {frame.zones.map((z) => (
                      <tr key={z.id} onClick={() => { if (terrain && !terrainStale) { setSelZone(z.id); setTab('zone'); } }}
                        className={cn('cursor-pointer border-t border-white/5 transition-colors hover:bg-white/[0.04]', selZone === z.id && 'bg-[#59b0ff]/[0.07]')}>
                        <td className="num py-1.5">{z.id}</td>
                        <td><span className={cn('rounded px-1.5 py-0.5 text-[10px] transition-colors duration-500', z.state === 'burning' ? 'bg-[#ff5a1f]/20 text-[#ffb08a]' : z.state === 'burned' ? 'bg-white/10 text-[#b9b2a8]' : 'bg-emerald-500/10 text-emerald-300')}>{t(`st_${z.state}` as Key) ?? z.state}</span></td>
                        <td className="num text-right">
                          <span className="inline-flex items-center gap-1.5">
                            <span className="h-1 w-10 overflow-hidden rounded-full bg-white/5"><span className="block h-full rounded-full bg-gradient-to-r from-[#c9301a] to-[#ff8a3d] transition-[width] duration-300" style={{ width: `${Math.min(100, z.burnedPct)}%` }} /></span>
                            {z.burnedPct.toFixed(1)}
                          </span>
                        </td>
                        <td className="text-right">{z.wet ? <span className="inline-block animate-pulse text-[#59b0ff]">●</span> : <span className="text-muted-foreground">·</span>}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </section>
              <section>
                <h3 className="eyebrow mb-2">{t('linesT')}</h3>
                <table className="w-full text-xs" data-testid="table-lines">
                  <thead className="text-left text-muted-foreground"><tr><th className="py-1 font-normal">ID</th><th className="font-normal">{t('status')}</th><th className="text-right font-normal">{t('water')}</th></tr></thead>
                  <tbody>
                    {frame.lines.map((l) => (
                      <tr key={l.id} className="border-t border-white/5">
                        <td className="num py-1.5">{l.id}</td>
                        <td>{l.on ? <span className="num inline-flex items-center gap-1 rounded bg-[#3a9bff]/20 px-1.5 py-0.5 text-[10px] text-[#9fd0ff]"><span className="h-1.5 w-1.5 animate-ping rounded-full bg-[#59b0ff]" />{t('on')}</span> : <span className="text-[10px] text-muted-foreground">{t('off')}</span>}</td>
                        <td className="num text-right">{fmtL(l.waterL)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </section>
            </>
          )}
          </Pane>
        </TabsContent>
      </div>
    </Tabs>
  );

  return (
    <div className="relative h-[100dvh] w-full overflow-hidden bg-background">
      {mapMode === '3d' ? (
        <MapView
        center={[sc.lon, sc.lat]}
        bounds={bounds}
        locked={locked}
        onMoveArea={(lng, lat) => set({ lon: +lng.toFixed(5), lat: +lat.toFixed(5) })}
        onResizeArea={(w) => set({ widthM: Math.max(500, Math.min(4000, Math.round(w / 50) * 50)) })}
        grid={grid}
        renderer={renderer}
        ignitions={terrainB ? ignitions : []}
        zonesN={sc.zones}
        lines={frame?.lines ?? []}
        onMapClick={onMapClick}
        zonesActive={!!terrain && !terrainStale}
        selectedZone={selZone}
        clickMode={locked ? 'inspect' : clickMode}
        widthM={sc.widthM}
        onCursor={setCursor}
        fly={fly}
        terrain3d
        onError={setMapErr}
        onFatal={(msg) => { setFallbackReason(msg); setMapMode('2d'); }}
      />
      ) : (
        <LeafletView
        center={[sc.lon, sc.lat]}
        bounds={bounds}
        locked={locked}
        onMoveArea={(lng, lat) => set({ lon: +lng.toFixed(5), lat: +lat.toFixed(5) })}
        onResizeArea={(w) => set({ widthM: Math.max(500, Math.min(4000, Math.round(w / 50) * 50)) })}
        grid={grid}
        renderer={renderer}
        ignitions={terrainB ? ignitions : []}
        zonesN={sc.zones}
        lines={frame?.lines ?? []}
        onMapClick={onMapClick}
        zonesActive={!!terrain && !terrainStale}
        selectedZone={selZone}
        clickMode={locked ? 'inspect' : clickMode}
        widthM={sc.widthM}
        onCursor={setCursor}
        fly={fly}
        onError={setMapErr}
      />
      )}
      {mapMode === '2d' && (
        <motion.div initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: 0.4, duration: 0.4, ease: EASE }} role="alert" data-testid="banner-2d-fallback" className="glass absolute left-1/2 top-[10.5rem] z-20 max-w-[92vw] -translate-x-1/2 rounded-lg border-[#e8b23a]/40 px-3 py-2 text-[11px] text-[#ffe6a8] md:top-28 md:left-[calc(50%+200px)]">
          <AlertTriangle className="mr-1.5 inline h-3.5 w-3.5" />{t('fallback2d')}
          {fallbackReason && <span className="num ml-1 text-muted-foreground">({fallbackReason.slice(0, 60)})</span>}
        </motion.div>
      )}

      {/* terrain download: radar chip over the map */}
      <AnimatePresence>
        {loadTerrainM.isPending && (
          <motion.div
            className="pointer-events-none absolute left-1/2 top-1/2 z-20 md:left-[calc(50%+200px)]"
            initial={{ opacity: 0, scale: 0.9, x: '-50%', y: '-50%' }} animate={{ opacity: 1, scale: 1, x: '-50%', y: '-50%' }} exit={{ opacity: 0, scale: 0.95, x: '-50%', y: '-50%' }}
            transition={{ duration: 0.3, ease: EASE }}
          >
            <div className="glass flex items-center gap-3 rounded-2xl px-4 py-3 text-xs">
              <span className="fb-radar" />
              <div>
                <div className="font-medium">{t('loadingTerrain')}</div>
                <div className="num text-[10px] text-muted-foreground">ESA WorldCover · AWS Terrarium</div>
              </div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      {/* top bar */}
      <motion.div initial={{ opacity: 0, y: -14 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.5, ease: EASE }} className="pointer-events-none absolute inset-x-0 top-0 z-20 flex flex-col gap-2 p-3 md:flex-row md:items-start">
        <div className="pointer-events-auto md:w-[400px]"><NavBar floating /></div>
        <div className="pointer-events-auto w-full md:ml-3 md:w-80">
          <SearchBox onPick={(lng, lat) => { setFly({ lng, lat, key: Date.now() }); if (!locked) set({ lon: +lng.toFixed(5), lat: +lat.toFixed(5) }); }} />
        </div>
      </motion.div>

      {/* banner */}
      <div className="pointer-events-none absolute left-1/2 top-[7.5rem] z-20 -translate-x-1/2 md:top-16 md:left-[calc(50%+200px)]">
        <div className="pointer-events-auto flex items-center gap-2">
          <StatusBanner status={status} frame={frame} />
          <AnimatePresence>
          {run && (
            <motion.span initial={{ opacity: 0, scale: 0.8 }} animate={{ opacity: 1, scale: 1 }} exit={{ opacity: 0, scale: 0.8 }}
              className="glass num flex items-center gap-1 rounded-full px-2.5 py-1 text-[10px] text-muted-foreground">
              <Radio className={cn('h-3 w-3', mode === 'live' && 'fb-live-dot text-[#ff5a1f]')} /> {mode === 'live' ? t('live') : t('replay')}
            </motion.span>
          )}
          </AnimatePresence>
        </div>
      </div>

      {/* right instruments */}
      <motion.div initial={{ opacity: 0, x: 16 }} animate={{ opacity: 1, x: 0 }} transition={{ delay: 0.15, duration: 0.5, ease: EASE }} className="absolute right-14 top-3 z-20"><div className="hidden flex-col items-end gap-2 md:flex">
        <div className="glass rounded-xl p-2.5"><WindRose dir={sc.windDir} speed={sc.windSpeed} /></div>
        <Popover>
          <PopoverTrigger asChild>
            <button className="glass flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs hover:border-primary/40" data-testid="button-layers"><Layers className="h-3.5 w-3.5" /> {t('layers')}</button>
          </PopoverTrigger>
          <PopoverContent className="w-72 text-xs" align="end">
            <ul className="space-y-2">
              <li className="flex items-center gap-2"><CheckCircle2 className="h-3.5 w-3.5 text-emerald-400" /> {t('lyrEsri')}</li>
              <li className="flex items-center gap-2">{mapMode === '3d' ? <CheckCircle2 className="h-3.5 w-3.5 text-emerald-400" /> : <AlertTriangle className="h-3.5 w-3.5 text-[#e8b23a]" />} {mapMode === '3d' ? 'AWS Terrarium DEM · 3D' : t('dem3dOff')}</li>
              <li className="flex items-center gap-2"><CheckCircle2 className="h-3.5 w-3.5 text-emerald-400" /> {t('lyrSim')}</li>
              <li className="flex items-center gap-2 text-muted-foreground" data-testid="text-google-unavailable"><AlertTriangle className="h-3.5 w-3.5" /> {t('googleUnavailable')}</li>
            </ul>
            <div className="mt-3 flex items-start gap-3 border-t border-white/10 pt-3">
              <div className="min-w-0 flex-1">
                <label htmlFor="fx-toggle" className="font-medium">{t('fxLabel')}</label>
                <p className="mt-0.5 text-[10px] leading-snug text-muted-foreground">{t('fxNote')}</p>
              </div>
              <Switch id="fx-toggle" checked={fx} onCheckedChange={setFx} data-testid="switch-fx" />
            </div>
          </PopoverContent>
        </Popover>
      </div></motion.div>

      {/* cursor readout + legend */}
      <div className="absolute bottom-3 right-3 z-10 mb-8 hidden md:block">
        <div className="glass num rounded-lg px-3 py-2 text-[11px]" data-testid="text-cursor">
          <div className="eyebrow mb-0.5 !text-[9px]">{t('cursor')}</div>
          {cursor ? (
            <span>{cursor.lat.toFixed(5)}°, {cursor.lng.toFixed(5)}° · {cursor.elev == null ? (mapMode === '2d' ? t('noElev2d') : '— m') : `${Math.round(cursor.elev)} m`}</span>
          ) : (
            <span className="text-muted-foreground">—</span>
          )}
        </div>
        <Legend />
      </div>

      <AnimatePresence>
      {mapErr && (
        <motion.div initial={{ opacity: 0, y: 12, x: '-50%' }} animate={{ opacity: 1, y: 0, x: '-50%' }} exit={{ opacity: 0, y: 12, x: '-50%' }}
          className="glass absolute bottom-24 left-1/2 z-30 rounded-lg px-3 py-2 text-xs text-destructive" role="alert">
          {mapErr} <button className="ml-2 underline" onClick={() => setMapErr(null)}>×</button>
        </motion.div>
      )}
      </AnimatePresence>

      {/* side panel (desktop) / bottom sheet (mobile) */}
      <aside
        aria-label={t('panel')}
        className={cn(
          'glass absolute z-20 flex flex-col transition-transform duration-500 ease-[cubic-bezier(.16,1,.3,1)]',
          'inset-x-0 bottom-0 max-h-[62dvh] rounded-t-2xl md:inset-x-auto md:bottom-3 md:left-3 md:top-[4.25rem] md:max-h-none md:w-[400px] md:rounded-2xl',
          panelOpen ? 'translate-y-0 md:translate-x-0' : 'translate-y-[calc(100%-3.25rem)] md:translate-y-0 md:-translate-x-[calc(100%+0.75rem)]',
        )}
      >
        <button
          className="flex items-center justify-between px-4 py-3 md:hidden"
          onClick={() => setPanelOpen((o) => !o)}
          aria-expanded={panelOpen}
          aria-label={panelOpen ? t('collapse') : t('expand')}
          data-testid="button-toggle-sheet"
        >
          <span className="mx-auto h-1 w-10 rounded-full bg-white/25" />
          <ChevronDown className={cn('h-4 w-4 transition-transform duration-300', !panelOpen && 'rotate-180')} />
        </button>
        {panel}
        <button
          onClick={() => setPanelOpen((o) => !o)}
          className="glass absolute -right-9 top-3 hidden h-8 w-7 place-items-center rounded-r-lg md:grid"
          aria-label={panelOpen ? t('collapse') : t('expand')}
          aria-expanded={panelOpen}
          data-testid="button-toggle-panel"
        >
          <ChevronLeft className={cn('h-4 w-4 transition-transform duration-300', !panelOpen && 'rotate-180')} />
        </button>
      </aside>

      {/* timeline */}
      <AnimatePresence>
      {frames.length > 0 && (
        <motion.div initial={{ opacity: 0, y: 24 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: 24 }} transition={{ duration: 0.45, ease: EASE }}
          className={cn('absolute z-20 transition-[left,right] duration-500 ease-[cubic-bezier(.16,1,.3,1)]', 'inset-x-3 bottom-[3.75rem] md:bottom-3 md:right-[15rem]', panelOpen ? 'md:left-[27rem]' : 'md:left-3', panelOpen && 'max-md:hidden')}>
          <div className="glass flex items-center gap-3 rounded-xl px-3 py-2">
            <button onClick={togglePlay} className={cn('grid h-9 w-9 shrink-0 place-items-center rounded-lg bg-gradient-to-br from-[#ff8a3d] to-[#e2461a] text-primary-foreground hover:brightness-110', playing && 'fb-playing')} aria-label={playing ? t('pause') : t('play')} data-testid="button-play">
              <AnimatePresence mode="wait" initial={false}>
                <motion.span key={playing ? 'pause' : 'play'} initial={{ scale: 0.4, rotate: -90, opacity: 0 }} animate={{ scale: 1, rotate: 0, opacity: 1 }} exit={{ scale: 0.4, rotate: 90, opacity: 0 }} transition={{ duration: 0.18 }}>
                  {playing ? <Pause className="h-4 w-4" /> : <Play className="h-4 w-4" />}
                </motion.span>
              </AnimatePresence>
            </button>
            <button onClick={stepOnce} className="grid h-9 w-9 shrink-0 place-items-center rounded-lg border border-border hover:bg-white/5" aria-label={t('step')} data-testid="button-step">
              <StepForward className="h-4 w-4" />
            </button>
            <button onClick={() => scrub(0)} className="group hidden h-9 w-9 shrink-0 place-items-center rounded-lg border border-border hover:bg-white/5 sm:grid" aria-label={t('restart')} data-testid="button-restart">
              <RotateCcw className="h-4 w-4 transition-transform duration-500 group-hover:-rotate-[200deg]" />
            </button>
            <div className="min-w-0 flex-1">
              <Slider value={[idx]} min={0} max={Math.max(0, frames.length - 1)} step={1} onValueChange={([v]) => scrub(v)} aria-label={t('timeline')} data-testid="slider-timeline" />
              <div className="num mt-1 flex justify-between text-[10px] text-muted-foreground">
                <span>{t('step')} {frame?.step ?? 0}/{frames[frames.length - 1].step}</span>
                <span>{fmtTime(frame?.time ?? 0)} / {fmtTime(frames[frames.length - 1].time)}</span>
              </div>
            </div>
            <label className="sr-only" htmlFor="speed">{t('speed')}</label>
            <select id="speed" value={speed} onChange={(e) => changeSpeed(Number(e.target.value))} className="fb-select num h-9 rounded-lg border border-border bg-black/30 px-2 text-xs" data-testid="select-speed">
              {SPEEDS.map((s) => <option key={s} value={s}>{s}×</option>)}
            </select>
          </div>
        </motion.div>
      )}
      </AnimatePresence>

      {/* idle hint when no terrain */}
      <AnimatePresence>
      {!terrain && !run && !loadTerrainM.isPending && (
        <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: 10 }} transition={{ delay: 0.3, duration: 0.4, ease: EASE }}
          className={cn('pointer-events-none absolute bottom-6 z-10 hidden transition-[left] duration-500 md:block', panelOpen ? 'left-[27rem]' : 'left-6')}>
          <div className="glass flex items-center gap-2 rounded-lg px-3 py-2 text-xs text-muted-foreground">
            <motion.span animate={{ y: [0, -3, 0] }} transition={{ duration: 1.6, repeat: Infinity, ease: 'easeInOut' }}><MapPin className="h-3.5 w-3.5 text-[#ffb36b]" /></motion.span> {t('terrainNone')}
          </div>
        </motion.div>
      )}
      </AnimatePresence>
      {replayRun.isError && (
        <div className="glass absolute left-1/2 top-28 z-30 -translate-x-1/2 rounded-lg px-4 py-3 text-xs" role="alert">
          <span className="text-destructive">{errMsg(replayRun.error)}</span>
          <button className="ml-3 underline" onClick={() => replayRun.refetch()}>{t('retry')}</button>
          <Link href="/" className="ml-3 underline">×</Link>
        </div>
      )}
    </div>
  );
}

function TerrainCard(p: {
  terrain: Terrain | null; stale: boolean; pending: boolean; error: string | null; disabled: boolean;
  stats: { burnPct: number; emin: number; emax: number; meanSlope: number; classes: { code: number; pct: number }[] } | null;
  onLoad: () => void;
}) {
  const { t } = useI18n();
  const ok = p.terrain && !p.stale;
  return (
    <section className={cn('rounded-xl border p-3 transition-[border-color,background-color] duration-500', p.error ? 'border-destructive/50 bg-destructive/10' : ok ? 'border-emerald-400/25 bg-emerald-400/[0.04]' : 'border-[#ffb36b]/30 bg-[#ffb36b]/[0.05]')} data-testid="card-terrain">
      <div className="mb-2 flex items-center gap-2">
        <Mountain className="h-4 w-4 text-[#ffb36b]" />
        <h3 className="text-sm font-medium">{t('studyArea')}</h3>
        <AnimatePresence mode="wait" initial={false}>
          <motion.span key={p.pending ? 'p' : ok ? 'ok' : p.error ? 'e' : 'n'}
            initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -6 }} transition={{ duration: 0.2 }}
            className={cn('ml-auto flex items-center gap-1 text-[10px] num', ok ? 'text-emerald-300' : 'text-muted-foreground')}>
            {ok && !p.pending && (
              <motion.span initial={{ scale: 0, rotate: -45 }} animate={{ scale: 1, rotate: 0 }} transition={{ type: 'spring', stiffness: 500, damping: 18 }}>
                <CheckCircle2 className="h-3 w-3" />
              </motion.span>
            )}
            {p.pending ? t('loadingTerrain') : ok ? t('terrainReady') : p.error ? '!' : '—'}
          </motion.span>
        </AnimatePresence>
      </div>
      {p.pending && (
        <div className="space-y-1.5 py-1" aria-busy>
          <div className="fb-scan h-1.5 w-full rounded-full bg-[#59b0ff]/10" />
          <div className="fb-shimmer h-2 w-full rounded" />
          <div className="fb-shimmer h-2 w-2/3 rounded" />
        </div>
      )}
      {p.error && !p.pending && (
        <p className="mb-2 text-xs text-destructive" role="alert" data-testid="text-terrain-error">
          <AlertTriangle className="mr-1 inline h-3 w-3" />{t('terrainFail')}: {p.error}
        </p>
      )}
      {p.stale && !p.pending && <p className="mb-2 text-xs text-[#ffd48a]">{t('terrainStale')}</p>}
      {ok && p.stats && p.terrain && !p.pending && (
        <motion.dl initial={{ opacity: 0, height: 0 }} animate={{ opacity: 1, height: 'auto' }} transition={{ duration: 0.45, ease: EASE }} className="num mb-2 grid grid-cols-2 gap-x-3 gap-y-1 overflow-hidden text-[11px]">
          <dt className="text-muted-foreground">{t('grid')}</dt><dd className="text-right">{p.terrain.rows} × {p.terrain.cols}</dd>
          <dt className="text-muted-foreground">{t('cell')}</dt><dd className="text-right">{p.terrain.cellSize.toFixed(1)} m</dd>
          <dt className="text-muted-foreground">{t('elev')}</dt><dd className="text-right">{Math.round(p.stats.emin)}–{Math.round(p.stats.emax)} m</dd>
          <dt className="text-muted-foreground">{t('slopeMean')}</dt><dd className="text-right">{p.stats.meanSlope.toFixed(1)}°</dd>
          <dt className="text-muted-foreground">{t('burnable')}</dt><dd className="text-right">{p.stats.burnPct.toFixed(1)} %</dd>
          <dt className="text-muted-foreground">{t('source')}</dt><dd className="truncate text-right" title={p.terrain.source}>{p.terrain.source}</dd>
          <dd className="col-span-2 mt-1 flex h-1.5 overflow-hidden rounded-full bg-white/5">
            {p.stats.classes.map((c, k) => (
              <motion.span key={c.code} title={`${FUEL_NAMES[c.code] ?? c.code}: ${c.pct.toFixed(1)}%`}
                initial={{ width: 0 }} animate={{ width: `${c.pct}%` }} transition={{ delay: 0.15 + k * 0.06, duration: 0.6, ease: EASE }}
                style={{ background: BURNABLE.has(c.code) ? `hsl(${30 + (c.code % 50)} 60% ${35 + (c.code % 30)}%)` : '#2b3a44' }} />
            ))}
          </dd>
        </motion.dl>
      )}
      <Button onClick={p.onLoad} disabled={p.pending || p.disabled} variant={ok ? 'secondary' : 'default'} className={cn('h-8 w-full text-xs', !ok && !p.pending && 'fb-cta')} data-testid="button-load-terrain">
        {p.pending ? <span className="fb-radar mr-1.5 !h-3.5 !w-3.5" /> : <Mountain className="mr-1.5 h-3.5 w-3.5" />}
        {p.pending ? t('loadingTerrain') : p.error ? t('retry') : t('loadTerrain')}
      </Button>
    </section>
  );
}

function Metric({ label, value, tone, small, active, i = 0 }: { label: string; value: string; tone?: 'fire' | 'water'; small?: boolean; active?: boolean; i?: number }) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 10, scale: 0.97 }} animate={{ opacity: 1, y: 0, scale: 1 }} transition={{ delay: i * 0.06, duration: 0.4, ease: EASE }}
      className={cn('relative overflow-hidden rounded-lg border bg-black/20 p-2.5 transition-[border-color,box-shadow] duration-500',
        active && tone === 'fire' ? 'border-[#ff5a1f]/40 shadow-[0_0_20px_-8px_#ff5a1f]' : active && tone === 'water' ? 'border-[#3a9bff]/40 shadow-[0_0_20px_-8px_#3a9bff]' : 'border-white/10')}
    >
      {active && (
        <span className={cn('pointer-events-none absolute inset-x-0 bottom-0 h-0.5', tone === 'fire' ? 'fb-scan bg-[#ff5a1f]/30' : 'fb-scan bg-[#3a9bff]/30')} />
      )}
      <div className="eyebrow !text-[9px]">{label}</div>
      <div className={cn('num mt-0.5 font-semibold', small ? 'text-xs text-muted-foreground' : 'text-lg', tone === 'fire' && 'text-[#ff8a4a]', tone === 'water' && 'text-[#6cb8ff]')}>{value}</div>
    </motion.div>
  );
}

function ZoneCard({ d, t }: { d: ZoneDetail; t: (k: Key) => string }) {
  const top = d.classes[0];
  const st = d.sim?.state;
  return (
    <div className="space-y-4" data-testid="card-zone">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="eyebrow">{t('selZone')}</div>
          <motion.div initial={{ scale: 0.7, opacity: 0 }} animate={{ scale: 1, opacity: 1 }} transition={{ type: 'spring', stiffness: 400, damping: 22 }}
            className="num bg-gradient-to-br from-[#cfe7ff] to-[#59b0ff] bg-clip-text text-2xl font-semibold text-transparent" data-testid="text-zone-id">{d.id}</motion.div>
          <div className="num text-[11px] text-muted-foreground">
            {t('center')} {d.centerLat.toFixed(5)}°, {d.centerLon.toFixed(5)}°
          </div>
        </div>
        {st && (
          <span className={cn('shrink-0 rounded px-2 py-1 text-[11px] transition-colors duration-500', st === 'burning' && 'pulse-fire', st === 'burning' ? 'bg-[#ff5a1f]/20 text-[#ffb08a]' : st === 'burned' ? 'bg-white/10 text-[#b9b2a8]' : 'bg-emerald-500/10 text-emerald-300')}>
            {t(`st_${st}` as Key) ?? st}
          </span>
        )}
      </div>

      <section className="rounded-lg border border-emerald-400/25 bg-emerald-400/[0.05] p-3" data-testid="zone-vegetation">
        <div className="mb-1.5 flex items-center justify-between gap-2">
          <h4 className="eyebrow">{t('vegetation')}</h4>
          {top && (
            <span className={cn('rounded px-1.5 py-0.5 text-[10px]', top.burnable ? 'bg-[#ff5a1f]/15 text-[#ffb08a]' : 'bg-white/10 text-muted-foreground')}>
              {top.burnable ? t('burnableYes') : t('burnableNo')}
            </span>
          )}
        </div>
        {top && (
          <div className="flex items-baseline justify-between gap-2" data-testid="text-zone-species">
            <span className="text-base font-medium">{top.name}</span>
            <span className="num shrink-0 text-[11px] text-muted-foreground">{t('dominant')} · {top.code} · {top.pct.toFixed(1)} %</span>
          </div>
        )}
        <p className="mt-1 text-[10px] leading-relaxed text-muted-foreground">{t('vegNote')}</p>
        <div className="mt-2.5 text-[10px] text-muted-foreground">{t('classMix')}</div>
        <ul className="mt-1 space-y-1.5">
          {d.classes.map((c) => (
            <li key={c.code} className="text-[11px]">
              <div className="flex justify-between gap-2">
                <span className="truncate">{c.name}</span>
                <span className="num shrink-0 text-muted-foreground">{c.code} · {c.pct.toFixed(1)} % · {c.cells}</span>
              </div>
              <div className="mt-0.5 h-1 overflow-hidden rounded-full bg-white/5">
                <motion.div className="h-full rounded-full" initial={{ width: 0 }} animate={{ width: `${c.pct}%` }} transition={{ duration: 0.7, ease: EASE }}
                  style={{ background: c.burnable ? 'linear-gradient(90deg,#3f7a48,#8cc08f)' : '#2b3a44' }} />
              </div>
            </li>
          ))}
        </ul>
      </section>

      <section>
        <h4 className="eyebrow mb-1.5">{t('studyArea')}</h4>
        <dl className="num grid grid-cols-2 gap-x-3 gap-y-1.5 text-[11px]">
          <ZStat k={t('areaHa')} v={fmtHa(d.areaHa)} />
          <ZStat k={t('cellsN')} v={`${d.cells} (${d.side}×${d.side})`} />
          <ZStat k={t('elevRange')} v={`${Math.round(d.elevMin)}–${Math.round(d.elevMax)} m`} />
          <ZStat k={t('elevMean')} v={`${Math.round(d.elevMean)} m`} />
          <ZStat k={t('slopeMean')} v={`${d.slopeMean.toFixed(1)}°`} />
          <ZStat k={t('slopeMax')} v={`${d.slopeMax.toFixed(1)}°`} />
          <ZStat k={t('aspectL')} v={d.aspectDeg == null ? '—' : `${d.aspectLabel} · ${Math.round(d.aspectDeg)}°`} />
          <ZStat k={t('burnableShare')} v={`${d.burnablePct.toFixed(1)} %`} />
        </dl>
      </section>

      <section data-testid="zone-sim">
        <h4 className="eyebrow mb-1.5">{t('simNow')}</h4>
        {!d.sim ? (
          <p className="text-xs text-muted-foreground">{t('noSimYet')}</p>
        ) : (
          <>
            <dl className="num grid grid-cols-2 gap-x-3 gap-y-1.5 text-[11px]">
              <ZStat k={t('burned')} v={`${d.sim.burnedPct.toFixed(1)} %`} />
              <ZStat k={t('burningCells')} v={String(d.sim.burningCells)} />
              <ZStat k={t('burnedCells')} v={String(d.sim.burnedCells)} />
              <ZStat k={t('wetShare')} v={`${d.sim.wetPct.toFixed(1)} %`} />
              <ZStat k={t('lineWater')} v={fmtL(d.sim.waterL)} />
            </dl>
            <h5 className="mt-3 text-[10px] text-muted-foreground">{t('adjLines')}</h5>
            <ul className="mt-1 space-y-1">
              {d.sim.lines.map((l) => (
                <li key={l.id} className="flex items-center justify-between gap-2 rounded-md bg-white/[0.03] px-2 py-1 text-[11px]">
                  <span className="num">{l.id}</span>
                  <span className="flex items-center gap-2">
                    {l.on ? <span className="num rounded bg-[#3a9bff]/20 px-1.5 py-0.5 text-[10px] text-[#9fd0ff]">{t('on')}</span> : <span className="text-[10px] text-muted-foreground">{t('off')}</span>}
                    <span className="num text-[#6cb8ff]">{fmtL(l.waterL)}</span>
                  </span>
                </li>
              ))}
            </ul>
            <h5 className="mt-3 text-[10px] text-muted-foreground">{t('zoneEvents')}</h5>
            {d.sim.events.length === 0 ? (
              <p className="mt-1 text-xs text-muted-foreground">{t('noEvents')}</p>
            ) : (
              <ol className="mt-1 max-h-48 space-y-1 overflow-y-auto pr-1">
                {[...d.sim.events].reverse().map((e, j) => (
                  <li key={`${j}-${e.time}-${e.kind}-${e.target}`} className="flex items-center gap-2 rounded-md bg-white/[0.03] px-2 py-1 text-xs">
                    <span className="num w-12 shrink-0 text-muted-foreground">{fmtTime(e.time)}</span>
                    <span>{t(`ev_${e.kind}` as Key) ?? e.kind}</span>
                    <span className="num ml-auto text-muted-foreground">{e.target}</span>
                  </li>
                ))}
              </ol>
            )}
          </>
        )}
      </section>
    </div>
  );
}

function ZStat({ k, v }: { k: string; v: string }) {
  return (
    <>
      <dt className="text-muted-foreground">{k}</dt>
      <dd className="text-right">{v}</dd>
    </>
  );
}

function EmptyHint({ icon, text }: { icon: ReactNode; text: string }) {
  return (
    <div className="flex flex-col items-center gap-3 rounded-xl border border-dashed border-white/10 px-4 py-10 text-center">
      <motion.span animate={{ y: [0, -4, 0] }} transition={{ duration: 2.4, repeat: Infinity, ease: 'easeInOut' }}
        className="grid h-10 w-10 place-items-center rounded-full bg-white/5 text-muted-foreground shadow-[0_0_24px_-8px_hsl(36_30%_92%/0.3)]">{icon}</motion.span>
      <p className="max-w-[16rem] text-xs text-muted-foreground">{text}</p>
    </div>
  );
}

function Legend() {
  const { t } = useI18n();
  const items: [string, string][] = [
    ['linear-gradient(135deg,#265c30,#92a04e)', t('lgFuel')], ['#14202a', t('lgNonfuel')],
    ['radial-gradient(circle,#fffae4,#ffd66e 30%,#ff7c20 60%,#c82a0a)', t('lgFire')], ['radial-gradient(circle,#ffaa46,#d6400e 55%,#3a1a10)', t('lgEmber')],
    ['linear-gradient(135deg,#161311,#545048)', t('lgBurned')], ['radial-gradient(circle,#8a8682,#4a4644)', t('lgSmoke')],
    ['linear-gradient(135deg,#2674c4,#8fd0ff)', t('lgWet')], ['#59b0ff', t('lgLine')],
  ];
  return (
    <div className="glass mt-2 flex max-w-[15rem] flex-wrap gap-x-3 gap-y-1 rounded-lg px-3 py-2 text-[10px] text-muted-foreground">
      {items.map(([c, l], k) => (
        <motion.span key={l} initial={{ opacity: 0, y: 4 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: 0.3 + k * 0.04 }} className="flex items-center gap-1">
          <span className="h-2.5 w-2.5 rounded-sm border border-white/20" style={{ background: c }} />{l}
        </motion.span>
      ))}
    </div>
  );
}

export function CompareTable({ runs }: { runs: Run[] }) {
  const { t } = useI18n();
  const groups = useMemo(() => {
    const m = new Map<string, Run[]>();
    runs.forEach((r) => m.set(r.strategy, [...(m.get(r.strategy) ?? []), r]));
    return [...m.entries()].map(([strategy, rs]) => {
      const mean = (f: (r: Run) => number) => rs.reduce((a, r) => a + f(r), 0) / rs.length;
      const cross = rs.filter((r) => r.crossingTime != null);
      return {
        strategy, rs,
        burned: mean((r) => r.burnedHa), water: mean((r) => r.waterL), duration: mean((r) => r.duration),
        crossN: cross.length,
        crossMean: cross.length ? cross.reduce((a, r) => a + (r.crossingTime as number), 0) / cross.length : null,
      };
    });
  }, [runs]);
  const maxBurn = Math.max(...groups.map((g) => g.burned), 0.0001);
  return (
    <section data-testid="table-compare">
      <h3 className="eyebrow mb-2">{t('compareResults')} · <span className="num">{runs.length}</span> {t('runsN')}</h3>
      <div className="space-y-3">
        {groups.map((g, k) => (
          <motion.div key={g.strategy} initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: k * 0.07, duration: 0.4, ease: EASE }}
            className="rounded-lg border border-white/10 bg-black/20 p-2.5 transition-colors hover:border-white/20">
            <div className="flex items-baseline justify-between">
              <span className="text-sm font-medium">{t(`s_${g.strategy}` as Key) ?? g.strategy}</span>
              <span className="num text-[10px] text-muted-foreground">n={g.rs.length}</span>
            </div>
            <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-white/5">
              <motion.div className="h-full rounded-full bg-gradient-to-r from-[#c9301a] via-[#ff6a2b] to-[#ffb36b]" initial={{ width: 0 }} animate={{ width: `${(g.burned / maxBurn) * 100}%` }} transition={{ delay: 0.15 + k * 0.07, duration: 0.8, ease: EASE }} />
            </div>
            <div className="num mt-1.5 grid grid-cols-3 gap-1 text-[11px]">
              <span><span className="text-muted-foreground">{t('mean')} </span>{fmtHa(g.burned)}</span>
              <span className="text-[#6cb8ff]">{fmtL(g.water)}</span>
              <span className="text-right">{g.crossMean == null ? <span className="text-muted-foreground">{t('noCrossing')}</span> : `${fmtTime(g.crossMean)} (${g.crossN}/${g.rs.length})`}</span>
            </div>
            <details className="mt-1">
              <summary className="cursor-pointer text-[10px] text-muted-foreground">{t('perRun')}</summary>
              <table className="num mt-1 w-full text-[10px]">
                <tbody>
                  {g.rs.map((r) => (
                    <tr key={r.id} className="border-t border-white/5">
                      <td className="py-1">seed {r.seed}</td>
                      <td>{fmtHa(r.burnedHa)}</td>
                      <td>{fmtL(r.waterL)}</td>
                      <td>{r.crossingTime == null ? '—' : fmtTime(r.crossingTime)}</td>
                      <td className="text-right">{r.status === 'completed' ? t('completed') : t('truncatedS')}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </details>
          </motion.div>
        ))}
      </div>
      <p className="mt-2 text-[10px] leading-relaxed text-muted-foreground">{t('compareNote')}</p>
    </section>
  );
}

import type { Run, RunInput, Scenario, ScenarioInput, Terrain } from '@workspace/api-client-react';
import { places, realTerrain, terrainIdentity } from '@/demo/real-data';
import { simulate } from '@/demo/engine';
import { LIVE_MAP } from '@/demo/flag';

/**
 * Answers the app's /api requests inside the browser for the offline demo.
 * Scenarios are kept in this browser's storage; terrain and runs live for the session.
 * Terrain comes from the real WorldCover / Terrarium data bundled for a few places.
 */

const terrains = new Map<string, Terrain>();
const runs = new Map<string, Run>();
const uid = () => Math.random().toString(16).slice(2, 10) + Date.now().toString(16).slice(-8);
const now = () => new Date().toISOString();

const loadScenarios = (): Scenario[] => { try { return JSON.parse(localStorage.getItem('fb-demo-scenarios') ?? '[]'); } catch { return []; } };
const saveScenarios = (s: Scenario[]) => { try { localStorage.setItem('fb-demo-scenarios', JSON.stringify(s)); } catch { /* storage blocked */ } };

class HttpError extends Error { constructor(public status: number, msg: string) { super(msg); } }
const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

function prepare(req: RunInput) {
  const t = terrains.get(req.terrainId);
  if (!t || terrainIdentity(req.scenario) !== req.terrainId) throw new HttpError(409, 'Study area changed or terrain is missing. Load terrain again.');
  return t;
}

function execute(req: RunInput, t: Terrain, s: ScenarioInput = req.scenario): Run {
  let result;
  try { result = simulate(s, t, req.ignitions, req.maxSteps ?? 180); } catch (e) { throw new HttpError(422, (e as Error).message); }
  const run: Run = { id: uid(), name: s.name, strategy: s.strategy, seed: s.seed, createdAt: now(), scenario: s, terrainId: t.id, ...result } as Run;
  runs.set(run.id, run);
  return run;
}

const strip = ({ frames: _f, ...rest }: Run) => { void _f; return rest as Run; };

async function handle(method: string, path: string, query: URLSearchParams, body: unknown): Promise<unknown> {
  if (path === '/api/healthz') return { status: 'ok' };
  if (path === '/api/scenarios' && method === 'GET') return loadScenarios();
  if (path === '/api/scenarios' && method === 'POST') {
    const rec = { ...(body as ScenarioInput), id: uid(), createdAt: now() } as Scenario;
    saveScenarios([rec, ...loadScenarios()]);
    return rec;
  }
  const sm = /^\/api\/scenarios\/([^/]+)$/.exec(path);
  if (sm && method === 'DELETE') {
    const all = loadScenarios();
    if (!all.some((s) => s.id === sm[1])) throw new HttpError(404, 'Scenario not found');
    saveScenarios(all.filter((s) => s.id !== sm[1]));
    return { status: 'deleted' };
  }
  if (path === '/api/terrain') {
    await wait(500);
    let t: Terrain;
    try { t = await realTerrain(body as ScenarioInput); } catch (e) { throw new HttpError(502, (e as Error).message); }
    terrains.set(t.id, t);
    return t;
  }
  if (path === '/api/runs' && method === 'POST') { await wait(150); const req = body as RunInput; return execute(req, prepare(req)); }
  if (path === '/api/runs') return [...runs.values()].reverse().map(strip);
  const rm = /^\/api\/runs\/([^/]+)$/.exec(path);
  if (rm) { const r = runs.get(rm[1]); if (!r) throw new HttpError(404, 'Run not found'); return r; }
  if (path === '/api/compare') {
    await wait(150);
    const req = body as RunInput;
    const t = prepare(req);
    const out: Run[] = [];
    for (let seed = req.scenario.seed; seed < req.scenario.seed + (req.repeats ?? 5); seed++)
      for (const strategy of ['none', 'all', 'fixed', 'adjacent', 'smart'] as const)
        out.push(strip(execute(req, t, { ...req.scenario, strategy, seed })));
    return out;
  }
  if (path === '/api/search') {
    const q = (query.get('q') ?? '').trim();
    const m = /^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*$/.exec(q);
    if (m) return [{ name: q, lat: Number(m[1]), lon: Number(m[2]) }];
    if (LIVE_MAP && q.length >= 2) {
      // Same OpenStreetMap search the server uses; it allows browser requests.
      const r = await fetch(`https://nominatim.openstreetmap.org/search?format=jsonv2&limit=5&q=${encodeURIComponent(q.slice(0, 200))}`);
      if (!r.ok) throw new HttpError(502, 'Place search unavailable. Enter latitude, longitude instead.');
      return ((await r.json()) as { display_name: string; lat: string; lon: string }[]).map((p) => ({ name: p.display_name, lat: Number(p.lat), lon: Number(p.lon) }));
    }
    const k = q.toLocaleLowerCase('tr');
    return (await places()).filter((p) => p.name.toLocaleLowerCase('tr').includes(k)).map(({ name, lat, lon }) => ({ name, lat, lon }));
  }
  throw new HttpError(404, 'Not found');
}

export function installMockApi() {
  const real = window.fetch.bind(window);
  window.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url, location.href);
    if (url.origin !== location.origin || !url.pathname.includes('/api/')) return real(input, init);
    const path = url.pathname.slice(url.pathname.indexOf('/api/'));
    const method = (init?.method ?? (input instanceof Request ? input.method : 'GET')).toUpperCase();
    let body: unknown = undefined;
    if (typeof init?.body === 'string') { try { body = JSON.parse(init.body); } catch { body = init.body; } }
    try {
      const data = await handle(method, path, url.searchParams, body);
      return new Response(JSON.stringify(data), { status: 200, headers: { 'Content-Type': 'application/json' } });
    } catch (e) {
      const status = e instanceof HttpError ? e.status : 500;
      return new Response(JSON.stringify({ detail: (e as Error).message }), { status, headers: { 'Content-Type': 'application/json' } });
    }
  };
}

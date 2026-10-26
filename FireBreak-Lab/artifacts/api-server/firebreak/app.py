import asyncio
import csv
import io
import json
import logging
import os
import sqlite3
import uuid
from datetime import datetime, timezone
from pathlib import Path

import httpx
from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.responses import Response
from starlette.concurrency import run_in_threadpool

load_dotenv()
from .models import ScenarioInput, RunInput
from . import terrain
from .simulation import simulate

logger = logging.getLogger("firebreak")
app = FastAPI(title="FireBreak Lab", docs_url="/api/docs", openapi_url="/api/openapi.json")
DB = Path(os.getenv("FIREBREAK_DB", "generated-artifacts/firebreak.sqlite"))
DB.parent.mkdir(parents=True, exist_ok=True)


def connect():
    con = sqlite3.connect(DB, timeout=30)
    con.execute("PRAGMA journal_mode=WAL")
    return con


with connect() as con:
    con.execute("CREATE TABLE IF NOT EXISTS scenarios (id TEXT PRIMARY KEY, data TEXT NOT NULL)")
    con.execute("CREATE TABLE IF NOT EXISTS runs (id TEXT PRIMARY KEY, data TEXT NOT NULL)")

work_lock = asyncio.Semaphore(2)


def now():
    return datetime.now(timezone.utc).isoformat()


def store(table, record):
    with connect() as con:
        con.execute(f"INSERT INTO {table} (id,data) VALUES (?,?)", (record["id"], json.dumps(record)))


def get_run(id):
    with connect() as con:
        row = con.execute("SELECT data FROM runs WHERE id=?", (id,)).fetchone()
    if not row:
        raise HTTPException(404, "Run not found")
    return json.loads(row[0])


@app.get("/api/healthz")
def health():
    return {"status": "ok"}


@app.get("/api/scenarios")
def scenarios():
    with connect() as con:
        return [json.loads(r[0]) for r in con.execute("SELECT data FROM scenarios ORDER BY rowid DESC")]


@app.post("/api/scenarios")
def save(s: ScenarioInput):
    record = dict(**s.model_dump(), id=uuid.uuid4().hex, createdAt=now())
    store("scenarios", record)
    return record


@app.delete("/api/scenarios/{id}")
def delete(id: str):
    with connect() as con:
        cur = con.execute("DELETE FROM scenarios WHERE id=?", (id,))
        if not cur.rowcount:
            raise HTTPException(404, "Scenario not found")
    return {"status": "deleted"}


@app.post("/api/terrain")
async def load_terrain(s: ScenarioInput):
    async with work_lock:
        try:
            return await run_in_threadpool(terrain.load, s)
        except Exception:
            logger.exception("Open terrain data unavailable")
            raise HTTPException(502, "Gerçek arazi verisi alınamadı. ESA WorldCover / DEM servisi erişimini kontrol edin ve tekrar deneyin. Yapay veri kullanılmadı.")


def prepare(req):
    t = terrain.cached(req.terrainId)
    if not t or terrain.identity(req.scenario) != req.terrainId:
        raise HTTPException(409, "Study area changed or terrain is missing. Load terrain again.")
    return t


def execute(req, t, s=None, frames=True):
    s = s or req.scenario
    try:
        result = simulate(s, t, req.ignitions, req.maxSteps, frames)
    except ValueError as e:
        raise HTTPException(422, str(e))
    run = dict(id=uuid.uuid4().hex, name=s.name, strategy=s.strategy, seed=s.seed,
               createdAt=now(), scenario=s.model_dump(), terrainId=t["id"], **result)
    store("runs", run)
    return run


@app.post("/api/runs")
async def create_run(req: RunInput):
    t = prepare(req)
    async with work_lock:
        return await run_in_threadpool(execute, req, t)


@app.get("/api/runs")
def list_runs():
    with connect() as con:
        # SQLite extracts metadata without deserializing replay arrays.
        rows = con.execute("SELECT json_remove(data, '$.frames') FROM runs ORDER BY rowid DESC LIMIT 200")
        return [json.loads(row[0]) for row in rows]


@app.get("/api/runs/{id}")
def retrieve_run(id: str):
    return get_run(id)


@app.post("/api/compare")
async def compare(req: RunInput):
    t = prepare(req)
    if t["rows"] > 90 and req.repeats > 5:
        raise HTTPException(422, "For large study areas use at most 5 repeats.")
    def batch():
        result = []
        for seed in range(req.scenario.seed, req.scenario.seed+req.repeats):
            for strategy in ["none", "all", "fixed", "adjacent", "smart"]:
                s = req.scenario.model_copy(update={"strategy": strategy, "seed": seed})
                # Preserve replay for every saved experiment.
                r = execute(req, t, s, True)
                result.append({k:v for k,v in r.items() if k != "frames"})
        return result
    async with work_lock:
        return await run_in_threadpool(batch)


@app.get("/api/runs/{id}/export.csv")
def export_csv(id: str):
    run = get_run(id)
    out = io.StringIO()
    writer = csv.writer(out)
    writer.writerow(["run_id","strategy","seed","status","time_s","water_L","burned_ha","first_crossing_s",
                     "wind_from_deg","wind_m_s","fuel_moisture_pct","terrain_id","model"])
    for f in run.get("frames", []):
        writer.writerow([id,run["strategy"],run["seed"],run["status"],f["time"],f["waterL"],
                         f["burnedHa"],run["crossingTime"],run["scenario"]["windDir"],
                         run["scenario"]["windSpeed"],run["scenario"]["moisture"],run["terrainId"],
                         "illustrative-cellular-v1"])
    return Response("\ufeff"+out.getvalue(), media_type="text/csv",
                    headers={"Content-Disposition": f'attachment; filename="firebreak-{id}.csv"'})


search_lock = asyncio.Lock()
search_cache = {}
last_search = 0.


@app.get("/api/search")
async def search(q: str):
    global last_search
    q = q.strip()[:200]
    if len(q) < 2:
        return []
    try:
        lat, lon = [float(v.strip()) for v in q.split(",")]
        if -80 <= lat <= 80 and -179.8 <= lon <= 179.8:
            return [{"name":q, "lat":lat, "lon":lon}]
    except ValueError:
        pass
    async with search_lock:
        if q in search_cache:
            return search_cache[q]
        loop = asyncio.get_running_loop()
        await asyncio.sleep(max(0, 1.1-(loop.time()-last_search)))
        last_search = loop.time()
        try:
            async with httpx.AsyncClient(timeout=15) as client:
                r = await client.get("https://nominatim.openstreetmap.org/search",
                                     params={"q":q,"format":"jsonv2","limit":5},
                                     headers={"User-Agent":"FireBreakLab/1.0 educational wildfire research"})
                r.raise_for_status()
                result = [{"name":p["display_name"], "lat":float(p["lat"]), "lon":float(p["lon"])} for p in r.json()]
                if len(search_cache) > 500:
                    search_cache.clear()
                search_cache[q] = result
                return result
        except httpx.HTTPError:
            raise HTTPException(502, "Place search unavailable. Enter latitude, longitude instead.")


@app.websocket("/api/ws/runs/{id}")
async def stream(ws: WebSocket, id: str):
    await ws.accept()
    try:
        run = await run_in_threadpool(get_run, id)
    except HTTPException:
        await ws.close(code=1008)
        return
    frames = run.get("frames", [])
    index, speed, playing = 0, 10., True
    try:
        while index < len(frames):
            try:
                command = await asyncio.wait_for(ws.receive_json(), timeout=max(.02, 10/speed) if playing else 60)
                action = command.get("action")
                if action == "pause":
                    playing = False
                elif action == "play":
                    playing = True
                elif action == "speed":
                    speed = min(60., max(1., float(command.get("speed", 1))))
                elif action == "step":
                    playing = False
                    await ws.send_json({"type":"frame", "frame":frames[index]})
                    index += 1
            except asyncio.TimeoutError:
                if playing:
                    await ws.send_json({"type":"frame", "frame":frames[index]})
                    index += 1
        await ws.send_json({"type":"complete", "status":run["status"]})
        await ws.close()
    except (WebSocketDisconnect, RuntimeError, ValueError):
        return

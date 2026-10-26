"""Open-data sampling only. Satellite imagery is never classified."""
import hashlib
import io
import json
import math
import os
import time
from datetime import datetime, timezone
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor

import httpx
import numpy as np
import rasterio
from PIL import Image
from rasterio.windows import from_bounds
from rasterio.enums import Resampling

CACHE = Path(os.getenv("FIREBREAK_CACHE", "generated-artifacts/firebreak-cache"))
CACHE.mkdir(parents=True, exist_ok=True)


def geometry(s):
    n = max(s.zones * 2, round(s.widthM / s.cellSize / s.zones) * s.zones)
    cell = s.widthM / n
    dy = s.widthM / 111320 / 2
    dx = dy / math.cos(math.radians(s.lat))
    return n, cell, [s.lon - dx, s.lat - dy, s.lon + dx, s.lat + dy]


def identity(s):
    return hashlib.sha256(json.dumps([s.lat, s.lon, s.widthM, s.cellSize, s.zones]).encode()).hexdigest()[:24]


def cached(id):
    path = CACHE / f"{id}.json"
    return json.loads(path.read_text()) if path.exists() else None


def dem_tile(key):
    z, x, y = key
    path = CACHE / f"dem-{z}-{x}-{y}.png"
    if not path.exists():
        r = httpx.get(f"https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png", timeout=35)
        r.raise_for_status()
        # Decode before caching to avoid persisting service error pages.
        Image.open(io.BytesIO(r.content)).verify()
        path.write_bytes(r.content)
    rgb = np.asarray(Image.open(path).convert("RGB"), dtype=float)
    return rgb[:, :, 0] * 256 + rgb[:, :, 1] + rgb[:, :, 2] / 256 - 32768


def sample_dem(xs, ys):
    z = 12
    tx = (xs + 180) / 360 * 2**z
    ty = (1 - np.arcsinh(np.tan(np.radians(ys))) / np.pi) / 2 * 2**z
    keys = {(z, int(x), int(y)) for x, y in zip(tx.flat, ty.flat)}
    with ThreadPoolExecutor(max_workers=8) as pool:
        tiles = dict(zip(keys, pool.map(dem_tile, keys)))
    result = np.empty(xs.shape)
    for i, (x, y) in enumerate(zip(tx.flat, ty.flat)):
        a = tiles[(z, int(x), int(y))]
        result.flat[i] = a[min(255, int((y % 1)*256)), min(255, int((x % 1)*256))]
    return result


def load(s):
    id = identity(s)
    existing = cached(id)
    if existing:
        return existing
    n, cell, bounds = geometry(s)
    west, south, east, north = bounds
    with httpx.Client(timeout=45, headers={"User-Agent": "FireBreakLab/1.0 educational research"}) as client:
        response = client.get("https://planetarycomputer.microsoft.com/api/stac/v1/search",
                              params={"collections": "esa-worldcover", "bbox": ",".join(map(str, bounds)), "limit": 20})
        response.raise_for_status()
        items = [f for f in response.json()["features"] if "2021" in f["id"]]
        if not items:
            raise ValueError("ESA WorldCover 2021 coverage is unavailable for this area.")
        fuel = np.zeros((n, n), dtype=np.uint8)
        # Read only the needed raster windows; remote COG range reads are cached by GDAL.
        for item in items:
            href = item["assets"]["map"]["href"]
            # The signing service sometimes answers 5xx for a moment; try it a few times before giving up.
            for attempt in range(3):
                signed = client.get("https://planetarycomputer.microsoft.com/api/sas/v1/sign", params={"href": href})
                if signed.status_code < 500:
                    break
                time.sleep(1 + attempt)
            signed.raise_for_status()
            with rasterio.Env(GDAL_DISABLE_READDIR_ON_OPEN="EMPTY_DIR", GDAL_HTTP_TIMEOUT="45"):
                with rasterio.open(signed.json()["href"]) as ds:
                    window = from_bounds(west, south, east, north, ds.transform)
                    sampled = ds.read(1, window=window, out_shape=(n, n), boundless=True,
                                      fill_value=0, resampling=Resampling.nearest)
                    fuel[sampled != 0] = sampled[sampled != 0]
    if np.any(fuel == 0):
        raise ValueError("WorldCover contains missing cells; choose another study area.")
    xs, ys = np.meshgrid(np.linspace(west, east, n, endpoint=False)+(east-west)/n/2,
                         np.linspace(north, south, n, endpoint=False)-(north-south)/n/2)
    elevation = sample_dem(xs, ys)
    gy, gx = np.gradient(elevation, cell)
    slope = np.degrees(np.arctan(np.hypot(gx, gy)))
    aspect = (np.degrees(np.arctan2(-gx, gy)) + 360) % 360
    result = dict(id=id, rows=n, cols=n, cellSize=cell, bounds=bounds,
                  fuel=fuel.ravel().tolist(), elevation=np.round(elevation, 2).ravel().tolist(),
                  slope=np.round(slope, 2).ravel().tolist(), aspect=np.round(aspect, 2).ravel().tolist(),
                  source="ESA WorldCover 2021 v200 (10 m; nearest-cell sampling) · AWS/Mapzen Terrarium DEM z12",
                  fetchedAt=datetime.now(timezone.utc).isoformat())
    path = CACHE / f"{id}.json"
    temp = path.with_suffix(".tmp")
    temp.write_text(json.dumps(result))
    temp.replace(path)
    return result

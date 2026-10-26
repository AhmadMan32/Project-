# FireBreak Lab

Türkçe/English research application for comparing zone-boundary water line controls.

## Run

Install the root Python dependencies (`uv sync`) and JavaScript dependencies (`pnpm install`).
The managed API workflow runs `pnpm --filter @workspace/api-server dev`; the web workflow runs `pnpm --filter @workspace/firebreak-lab dev`.
Services use their `PORT` values and the web app uses `BASE_PATH`. FastAPI serves `/api`, including `/api/docs` and `/api/ws/runs/{id}`.
Python rasterio requires the system `expat` library (configured in Replit).

Optional `.env` configuration (no keys required):

- `FIREBREAK_DB`: SQLite path, default `generated-artifacts/firebreak.sqlite`, relative to the API working directory.
- `FIREBREAK_CACHE`: open-data cache directory, default `generated-artifacts/firebreak-cache`.

SQLite and terrain caches are local files. Back them up. A production host must provide persistent disk; an ephemeral/replicated deployment is not a durable storage configuration. This app has no account isolation: use a trusted research workspace, not a public multi-tenant service.

## Data and research limits

- ESA WorldCover 2021 v200 10 m categorical land cover, via Microsoft Planetary Computer STAC and remote COG windows. Sampled at simulation cell centers using nearest neighbor. Trees (10), shrubs (20), grass (30), crops (40), wetlands (90), mangroves (95), moss/lichen (100) are fuel. Water, built-up, bare and snow never ignite. Coarse sampling can miss narrow roads/water barriers; no claim is made that 2021 cover describes current conditions.
- AWS/Mapzen Terrarium DEM sampled at zoom 12. Slope/aspect are finite differences at simulation spacing. This is a composite elevation product, not a current survey.
- Esri satellite imagery is for visual navigation only; no imagery classification. Google imagery is never used for analysis. Google photorealistic tiles are not implemented.
- The starter's cellular automaton idea is retained. This is NOT Rothermel, NOT a calibrated wildfire forecast and NOT suitable for emergency decisions. 10 s/step, 40 s burning residence, fuel multipliers, grade response, wetting factor and drying rate are explicit **uncalibrated assumptions**.
- Wind direction is meteorological (where wind comes from). Fuel moisture is a manually controlled percentage, not ambient relative humidity. Smart control uses directional boundary proximity, downwind exposure and moisture threshold.
- Physical boundary lines are unique (not double-counted). Active lines wet two cells on both sides; flow is per line in L/min. Pump capacity, pressure and real nozzle reach are not modeled.
- Fixed-time control activates all lines after the configured delay. All-lines control activates every boundary on the first time step. Adjacent activates boundaries touching burning zones. None is the no-water baseline.
- Comparisons use identical seeds and per-cell/per-direction common random draws across strategies. First crossing is first ignition in a zone that was not initially ignited; it is not a per-boundary travel-time statistic. Null means **not observed within the run**, never zero. Truncated means the time limit was reached while fire remains; it does not mean extinction.
- Burned area includes actively burning cells. Zone burned percentage uses total zone area. Water use is the flow integral, not an inferred estimate from imagery.
- Saved frames and CSV contain experimental model outputs, not observed fires. Keep settings, terrain provenance, random seed and model version with reports. Field/bench validation and uncertainty analysis are still required for scientific claims.

The attached starter remains the conceptual source: zone boundaries, wet strips, wind-sensitive control, seeded strategy comparisons. This implementation replaces browser-only state with FastAPI/NumPy and SQLite.

## Visual layer

The map raster is drawn by `artifacts/firebreak-lab/src/lib/fire-renderer.ts`. It is visual only and never changes results:
each cell's ignition and burn-out step is read from the saved frames and jittered within its step so the fire front
moves continuously; flames, cooling embers, char/ash, smoke, sparks, sprinkler mist and steam are drawn on top.
Smoke/sparks/mist can be turned off under **Layers → Realistic effects**. All UI motion respects `prefers-reduced-motion`.

## Offline demo build

`pnpm --filter @workspace/firebreak-lab run build:demo` builds a static demo into `artifacts/firebreak-lab/dist/demo`.
It needs no API server: requests to `/api` are answered in the browser (`src/demo/mock-api.ts`) and the simulation is a
TypeScript port of `simulation.py` (`src/demo/engine.ts`, different random generator, so seeds do not match server runs).
Terrain is **real** open data bundled in `demo-public/demo-data`, only for Marmaris, Manavgat, Köyceğiz and Çeşme
(about 12 km around each): ESA WorldCover 2021 v200 cut on its native 10 m grid without resampling, and the AWS Terrarium
z12 tiles covering the same area. Cells are sampled like `terrain.py` (slope, aspect and elevation match it); the server
reads WorldCover through GDAL, which can use overviews, so about 9 % of land-cover cells can differ. Outside the bundled
areas the demo refuses to load terrain, as the server does when providers fail. The basemap is painted from the
WorldCover classes and DEM relief; it is not satellite imagery.

### GitHub Pages

`pnpm --filter @workspace/firebreak-lab run build:pages` writes the same demo to `docs/` at the repository root, with
`VITE_LIVE_MAP=1`: live Esri World Imagery and the 3D Terrarium DEM, like the full app, with the bundled terrain data and
in-browser simulation. Serve it with GitHub Pages (Settings → Pages → Deploy from a branch → `/docs`).

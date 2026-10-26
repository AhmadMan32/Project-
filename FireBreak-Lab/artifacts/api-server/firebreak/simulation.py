"""Seeded illustrative cellular automaton, NOT a validated wildfire forecast.

10 seconds/step and 40 seconds cell residence are model assumptions.
The starter's directional wind/moisture/wetting approach is retained.
"""
import math
import numpy as np

BURNABLE = [10, 20, 30, 40, 90, 95, 100]
DT = 10


def simulate(s, terrain, ignitions, max_steps, keep_frames=True):
    n = terrain["rows"]
    zs = n // s.zones
    cell = terrain["cellSize"]
    fuel = np.array(terrain["fuel"]).reshape(n, n)
    elev = np.array(terrain["elevation"]).reshape(n, n)
    state = np.where(np.isin(fuel, BURNABLE), 0, 3).astype(np.uint8)
    age = np.zeros_like(state)
    wet = np.zeros_like(state, dtype=float)
    rng = np.random.default_rng(s.seed)
    events, frames, seen, initial = [], [], set(), set()
    zn = lambda r, c: f"{chr(65+r//zs)}{c//zs+1}"
    for ign in ignitions:
        if ign.row >= n or ign.col >= n or state[ign.row, ign.col] == 3:
            raise ValueError("Ignition must be inside the study area on a burnable WorldCover cell.")
        state[ign.row, ign.col] = 1
        name = zn(ign.row, ign.col)
        if name not in seen:
            events.append(dict(time=0, kind="ignition", target=name))
        seen.add(name)
        initial.add(name)
    lines = []
    for r in range(s.zones):
        for c in range(s.zones):
            for dr, dc in [(0, 1), (1, 0)]:
                if r+dr < s.zones and c+dc < s.zones:
                    a, b = zn(r*zs, c*zs), zn((r+dr)*zs, (c+dc)*zs)
                    lines.append(dict(id=f"{a}-{b}", **{"from": a}, to=b, on=False, waterL=0.,
                                      r=r, c=c, dr=dr, dc=dc))
    a = math.radians((s.windDir+180) % 360)
    wx, wy = math.sin(a), -math.cos(a)
    crossing = None
    step = 0

    def frame():
        zones = []
        for r in range(s.zones):
            for c in range(s.zones):
                block = state[r*zs:(r+1)*zs, c*zs:(c+1)*zs]
                zones.append(dict(id=zn(r*zs, c*zs),
                                  state="burning" if np.any(block == 1) else "burned" if np.any(block == 2) else "safe",
                                  burnedPct=round(float(np.mean((block == 1)|(block == 2))*100), 2),
                                  wet=bool(np.any(wet[r*zs:(r+1)*zs, c*zs:(c+1)*zs] > 0))))
        return dict(step=step, time=step*DT, state=state.ravel().tolist(),
                    wet=np.round(wet, 2).ravel().tolist(), waterL=sum(l["waterL"] for l in lines),
                    burnedHa=float(np.count_nonzero((state == 1)|(state == 2))*cell*cell/10000),
                    zones=zones, lines=[{k: l[k] for k in ["id", "from", "to", "on", "waterL"]} for l in lines],
                    events=list(events), finished=not bool(np.any(state == 1)))

    frames.append(frame())
    for step in range(1, max_steps+1):
        burning = np.argwhere(state == 1)
        if not len(burning):
            step -= 1
            break
        wet[:] = np.maximum(0, wet - 1/6)
        for line in lines:
            r, c, dr, dc = (line[k] for k in ("r", "c", "dr", "dc"))
            in_a = (burning[:, 0]//zs == r)&(burning[:, 1]//zs == c)
            in_b = (burning[:, 0]//zs == r+dr)&(burning[:, 1]//zs == c+dc)
            on = s.strategy == "all" or (s.strategy == "fixed" and step*DT >= s.fixedDelay)
            if s.strategy == "adjacent":
                on = bool(np.any(in_a | in_b))
            elif s.strategy == "smart":
                # Distance to THIS boundary, not either edge of the zone.
                near = False
                for cells in [burning[in_a], burning[in_b]]:
                    if len(cells):
                        distance = np.abs(cells[:, 0] - (r+1)*zs + .5) if dr else np.abs(cells[:, 1] - (c+1)*zs + .5)
                        near |= bool(np.any(distance < 3))
                down = (np.any(in_a) and dc*wx+dr*wy > .3) or (np.any(in_b) and -dc*wx-dr*wy > .3)
                on = bool(near or (down and s.moisture < 65))
            if on != line["on"]:
                events.append(dict(time=step*DT, kind="line_on" if on else "line_off", target=line["id"]))
            line["on"] = on
            if on:
                line["waterL"] += s.flowLpm*DT/60
                if dr:
                    wet[max(0,(r+1)*zs-2):min(n,(r+1)*zs+2), c*zs:(c+1)*zs] = 1
                else:
                    wet[r*zs:(r+1)*zs, max(0,(c+1)*zs-2):min(n,(c+1)*zs+2)] = 1
        # All cell/direction random values are drawn each step, independent of
        # strategy, giving paired comparisons common random numbers.
        randoms = rng.random((8, n, n))
        next_state = state.copy()
        age[state == 1] += 1
        next_state[(state == 1)&(age >= 4)] = 2
        directions = [(dr, dc) for dr in (-1,0,1) for dc in (-1,0,1) if dr or dc]
        for k, (dr, dc) in enumerate(directions):
            sr = slice(max(0,-dr), min(n,n-dr)); sc = slice(max(0,-dc), min(n,n-dc))
            tr = slice(max(0,dr), min(n,n+dr)); tc = slice(max(0,dc), min(n,n+dc))
            eligible = (state[sr,sc] == 1)&(state[tr,tc] == 0)
            grade = (elev[tr,tc]-elev[sr,sc])/(cell*math.hypot(dr,dc))
            wind = math.exp(.08*s.windSpeed*(dc*wx+dr*wy)/math.hypot(dr,dc))
            fuel_factor = np.select([fuel[tr,tc]==10, fuel[tr,tc]==20, fuel[tr,tc]==30], [1.,1.1,1.25], default=.8)
            p = np.clip(.25*wind*(1-.8*s.moisture/100)*np.exp(np.clip(grade*3,-2,2))*fuel_factor*(1-.9*wet[tr,tc]),0,.95)
            next_state[tr,tc][eligible & (randoms[k,tr,tc] < p)] = 1
        newly = np.argwhere((next_state == 1)&(state == 0))
        for r, c in newly:
            name = zn(r,c)
            if name not in seen:
                seen.add(name)
                events.append(dict(time=step*DT, kind="crossing", target=name))
                if name not in initial and crossing is None:
                    crossing = step*DT
        state = next_state
        if not np.any(state == 1):
            for line in lines:
                if line["on"]:
                    line["on"] = False
                    events.append(dict(time=step*DT, kind="line_off", target=line["id"]))
        f = frame()
        if keep_frames:
            frames.append(f)
        if f["finished"]:
            break
    final = frame()
    return dict(frames=frames if keep_frames else [], waterL=final["waterL"], burnedHa=final["burnedHa"],
                duration=step*DT, crossingTime=crossing,
                status="completed" if final["finished"] else "truncated")

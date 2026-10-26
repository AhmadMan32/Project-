from typing import Literal
from pydantic import BaseModel, Field


class ScenarioInput(BaseModel):
    name: str = Field(min_length=1, max_length=120)
    lat: float = Field(ge=-80, le=80)
    lon: float = Field(ge=-179.8, le=179.8)
    widthM: float = Field(default=2000, ge=500, le=5000)
    cellSize: float = Field(default=60, ge=30, le=100)
    zones: int = Field(default=3, ge=2, le=6)
    windDir: float = Field(default=270, ge=0, le=360)
    windSpeed: float = Field(default=8, ge=0, le=40)
    moisture: float = Field(default=30, ge=0, le=100)
    strategy: Literal["none", "all", "fixed", "adjacent", "smart"] = "smart"
    seed: int = Field(default=1, ge=0, le=2147483647)
    flowLpm: float = Field(default=120, ge=1, le=10000)
    fixedDelay: float = Field(default=60, ge=0, le=3600)


class Ignition(BaseModel):
    row: int = Field(ge=0)
    col: int = Field(ge=0)


class RunInput(BaseModel):
    scenario: ScenarioInput
    terrainId: str = Field(pattern=r"^[a-f0-9]{24}$")
    ignitions: list[Ignition] = Field(min_length=1, max_length=20)
    maxSteps: int = Field(default=180, ge=1, le=360)
    repeats: int = Field(default=5, ge=1, le=20)

// Open-Meteo: anahtar gerektirmeyen ücretsiz hava durumu servisi.
import type { Weather } from './types';

const CODES: [number[], string][] = [
  [[0], 'Açık'],
  [[1, 2], 'Parçalı bulutlu'],
  [[3], 'Kapalı'],
  [[45, 48], 'Sisli'],
  [[51, 53, 55, 56, 57], 'Çisenti'],
  [[61, 63, 65, 66, 67, 80, 81, 82], 'Yağmurlu'],
  [[71, 73, 75, 77, 85, 86], 'Karlı'],
  [[95, 96, 99], 'Fırtına'],
];

export function weatherText(code: number): string {
  return CODES.find(([cs]) => cs.includes(code))?.[1] ?? 'Değişken';
}

type Fetch = typeof fetch;
const geoCache = new Map<string, { lat: number; lon: number; name: string }>();

export async function fetchWeather(city: string, f: Fetch = fetch): Promise<Weather> {
  let loc = geoCache.get(city);
  if (!loc) {
    const g = await f(
      `https://geocoding-api.open-meteo.com/v1/search?name=${encodeURIComponent(city)}&count=1&language=tr&format=json`,
    );
    if (!g.ok) throw new Error(`Konum servisi hatası (${g.status})`);
    const gj = (await g.json()) as { results?: { latitude: number; longitude: number; name: string }[] };
    const r = gj.results?.[0];
    if (!r) throw new Error(`"${city}" şehri bulunamadı`);
    loc = { lat: r.latitude, lon: r.longitude, name: r.name };
    geoCache.set(city, loc);
  }
  const url =
    `https://api.open-meteo.com/v1/forecast?latitude=${loc.lat}&longitude=${loc.lon}` +
    `&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m` +
    `&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max` +
    `&timezone=auto&forecast_days=4`;
  const res = await f(url);
  if (!res.ok) throw new Error(`Hava durumu servisi hatası (${res.status})`);
  const j = (await res.json()) as {
    current: { temperature_2m: number; relative_humidity_2m: number; weather_code: number; wind_speed_10m: number };
    daily: {
      time: string[];
      weather_code: number[];
      temperature_2m_max: number[];
      temperature_2m_min: number[];
      precipitation_probability_max: (number | null)[];
    };
  };
  return {
    city: loc.name,
    now: {
      temp: Math.round(j.current.temperature_2m),
      code: j.current.weather_code,
      text: weatherText(j.current.weather_code),
      wind: Math.round(j.current.wind_speed_10m),
      humidity: Math.round(j.current.relative_humidity_2m),
    },
    days: j.daily.time.map((date, i) => ({
      date,
      min: Math.round(j.daily.temperature_2m_min[i]),
      max: Math.round(j.daily.temperature_2m_max[i]),
      code: j.daily.weather_code[i],
      text: weatherText(j.daily.weather_code[i]),
      rainChance: j.daily.precipitation_probability_max[i] ?? 0,
    })),
  };
}

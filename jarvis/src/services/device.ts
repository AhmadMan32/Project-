import type { Device } from '../core/device';
import type { Weather } from '../core/types';
import { fetchWeather } from '../core/weather';
import { calendarPort } from './calendar';
import { notifierPort } from './notify';

// Hava durumu 30 dakika önbellekte tutulur.
let cache: { city: string; at: number; data: Weather } | null = null;

export const device: Device = {
  calendar: calendarPort,
  notifier: notifierPort,
  async weather(city: string) {
    if (cache && cache.city === city && Date.now() - cache.at < 30 * 60_000) return cache.data;
    const data = await fetchWeather(city);
    cache = { city, at: Date.now(), data };
    return data;
  },
};

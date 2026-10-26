// Web önizlemesinde sistem takvimi yoktur; durum açıkça "kullanılamaz" döner.
import type { CalendarPort } from '../core/device';

export const calendarPort: CalendarPort & { request(): Promise<'kullanilamaz'> } = {
  status: async () => 'kullanilamaz',
  request: async () => 'kullanilamaz',
  list: async () => [],
  create: async () => {
    throw new Error('Takvim yalnızca telefonda çalışır');
  },
  remove: async () => {
    throw new Error('Takvim yalnızca telefonda çalışır');
  },
  find: async () => null,
};

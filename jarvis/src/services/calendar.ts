// Telefonun sistem takvimi (Google/iCloud/Outlook hesapları buraya senkron olur).
import * as Calendar from 'expo-calendar';
import { Platform } from 'react-native';
import type { CalendarPort } from '../core/device';
import type { CalEvent, IntegrationStatus } from '../core/types';

function toEvent(e: Calendar.ExpoCalendarEvent): CalEvent {
  return {
    id: e.id,
    title: e.title || '(başlıksız)',
    start: new Date(e.startDate as string | Date).toISOString(),
    end: new Date(e.endDate as string | Date).toISOString(),
    allDay: !!e.allDay,
    location: e.location || undefined,
    notes: e.notes || undefined,
  };
}

async function writableCalendar(): Promise<Calendar.ExpoCalendar> {
  if (Platform.OS === 'ios') return Calendar.getDefaultCalendarSync();
  const cals = await Calendar.getCalendars(Calendar.EntityTypes.EVENT);
  const writable = cals.filter((c) => c.allowsModifications);
  const pick = writable.find((c) => c.isPrimary && c.isVisible) ?? writable.find((c) => c.isVisible) ?? writable[0];
  if (!pick) throw new Error('Yazılabilir bir takvim bulunamadı');
  return pick;
}

export const calendarPort: CalendarPort & { request(): Promise<IntegrationStatus> } = {
  async status() {
    const p = await Calendar.getCalendarPermissions();
    if (p.granted) return 'ok';
    return p.canAskAgain ? 'eksik' : 'izin_yok';
  },
  async request() {
    const p = await Calendar.requestCalendarPermissions();
    return p.granted ? 'ok' : 'izin_yok';
  },
  async list(from, to) {
    const cals = (await Calendar.getCalendars(Calendar.EntityTypes.EVENT)).filter((c) => c.isVisible !== false);
    if (!cals.length) return [];
    const evs = await Calendar.listEvents(cals, from, to);
    return evs.map(toEvent);
  },
  async create(e) {
    const cal = await writableCalendar();
    const ev = await cal.createEvent({
      title: e.title,
      startDate: e.start,
      endDate: e.end,
      notes: e.notes,
      location: e.location,
      timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    });
    return toEvent(ev);
  },
  async remove(id) {
    const ev = await Calendar.ExpoCalendarEvent.get(id);
    await ev.delete();
  },
  async find(id) {
    try {
      return toEvent(await Calendar.ExpoCalendarEvent.get(id));
    } catch {
      return null;
    }
  },
};

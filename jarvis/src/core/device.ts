// Çekirdeğin dış dünyaya açılan kapıları. Uygulamada gerçek modüllerle,
// testlerde sahte uygulamalarla doldurulur.
import type { CalEvent, IntegrationStatus, Weather } from './types';

export interface CalendarPort {
  status(): Promise<IntegrationStatus>;
  list(from: Date, to: Date): Promise<CalEvent[]>;
  create(e: { title: string; start: Date; end: Date; notes?: string; location?: string }): Promise<CalEvent>;
  remove(id: string): Promise<void>;
  find(id: string): Promise<CalEvent | null>;
}

export interface NotifierPort {
  status(): Promise<IntegrationStatus>;
  // at verilmezse hemen gösterir
  notify(title: string, body: string, at?: Date): Promise<string | null>;
}

export interface Device {
  calendar: CalendarPort;
  notifier: NotifierPort;
  weather(city: string): Promise<Weather>;
}

export class IntegrationError extends Error {
  constructor(public integration: 'calendar' | 'notifications' | 'weather' | 'claude', message: string, public fix?: string) {
    super(message);
  }
}

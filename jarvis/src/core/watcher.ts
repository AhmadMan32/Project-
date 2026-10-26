// Jarvis'in sürekli çalışan gözü. Uygulama açıkken birkaç dakikada bir,
// kapalıyken işletim sisteminin izin verdiği aralıklarla çalışır:
// takvimi ve hava durumunu okur, gecikenleri/duranları tespit eder,
// gerekli bildirimleri gönderir ve sabah brifingini hazırlar.

import { morningBrief } from './brief';
import type { Device } from './device';
import { isOverdue, stalledProjects } from './priority';
import type { Store } from './store';
import { addDays, dayKey, daysBetween, dueDate, parseHHMM, relativeDue, trTime } from './time';
import { Alert, CalEvent, Health, IntegrationStatus, Weather } from './types';

export interface WatchResult {
  alerts: Alert[];
  events: CalEvent[];
  weather: Weather | null;
  health: Health;
  brief?: string;
}

const LEVEL_RANK = { kritik: 0, uyari: 1, bilgi: 2 } as const;

export async function runWatch(
  store: Store,
  device: Device,
  opts: { claude: IntegrationStatus; microphone?: IntegrationStatus; voice?: 'premium' | 'cihaz'; background?: boolean },
): Promise<WatchResult> {
  const now = store.now();
  const s = store.state;
  const alerts: Alert[] = [];
  const health: Health = {
    claude: opts.claude,
    calendar: 'eksik',
    notifications: 'eksik',
    microphone: opts.microphone ?? 'eksik',
    weather: 'eksik',
    voice: opts.voice ?? 'cihaz',
    messages: {},
  };

  // Takvim, bildirim ve hava kontrolleri paralel; biri yavaşlarsa diğerlerini bekletmez
  let events: CalEvent[] = [];
  let weather: Weather | null = null;
  const checkCalendar = async () => {
    try {
      health.calendar = await withTimeout(device.calendar.status(), 8000);
      if (health.calendar === 'ok') {
        events = await withTimeout(device.calendar.list(addDays(now, -1), addDays(now, 7)), 8000);
        events.sort((a, b) => a.start.localeCompare(b.start));
      }
    } catch (e) {
      health.calendar = 'hata';
      health.messages.calendar = errText(e);
    }
  };
  const checkNotifications = async () => {
    try {
      health.notifications = await withTimeout(device.notifier.status(), 8000);
    } catch (e) {
      health.notifications = 'hata';
      health.messages.notifications = errText(e);
    }
  };
  const checkWeather = async () => {
    if (!s.profile.city.trim()) return;
    try {
      weather = await withTimeout(device.weather(s.profile.city.trim()), 8000);
      health.weather = 'ok';
    } catch (e) {
      health.weather = 'hata';
      health.messages.weather = errText(e);
    }
  };
  await Promise.all([checkCalendar(), checkNotifications(), checkWeather()]);

  // 1) Takvim
  if (health.calendar === 'izin_yok' || health.calendar === 'eksik')
    alerts.push({
      key: 'int:calendar',
      level: 'uyari',
      text: 'Takvimine erişimim yok, etkinliklerini takip edemiyorum.',
      fix: 'Ayarlar > Bağlantılar > Takvim iznini ver.',
    });
  else if (health.calendar === 'hata')
    alerts.push({
      key: 'int:calendar',
      level: 'uyari',
      text: `Takvim bağlantısına şu anda erişemiyorum (${health.messages.calendar}).`,
      fix: 'Telefonun takvim uygulamasının senkronize olduğunu kontrol et.',
    });

  // 2) Bildirim izni
  if (health.notifications !== 'ok' && health.notifications !== 'kullanilamaz')
    alerts.push({
      key: 'int:notifications',
      level: 'uyari',
      text: 'Bildirim iznim yok; uygulama kapalıyken seni uyaramam.',
      fix: 'Ayarlar > Bağlantılar > Bildirim iznini ver.',
    });

  if (opts.claude !== 'ok')
    alerts.push({
      key: 'int:claude',
      level: 'kritik',
      text:
        opts.claude === 'eksik'
          ? 'Yapay zekâ bağlantım kurulmadı; konuşma ve otomatik planlama kapalı.'
          : 'Yapay zekâ servisine şu anda ulaşamıyorum.',
      fix: opts.claude === 'eksik' ? 'Ayarlar > Claude API anahtarını gir.' : 'İnternet bağlantını ve API anahtarını kontrol et.',
    });

  // 4) İş takibi
  const overdue = s.tasks.filter((t) => isOverdue(t, now));
  for (const t of overdue.slice(0, 5))
    alerts.push({ key: `overdue:${t.id}`, level: 'uyari', text: `Gecikti: ${t.title} (${relativeDue(t.due!, now)})`, ref: t.id });
  if (overdue.length > 5)
    alerts.push({ key: 'overdue:more', level: 'uyari', text: `${overdue.length - 5} geciken görev daha var.` });

  for (const p of s.projects.filter((p) => p.status === 'aktif' && p.deadline)) {
    const left = daysBetween(now, new Date(p.deadline + 'T12:00:00'));
    if (left <= 3)
      alerts.push({
        key: `deadline:${p.id}:${dayKey(now)}`,
        level: 'kritik',
        text: left < 0 ? `${p.name} teslim tarihi ${-left} gün geçti!` : left === 0 ? `${p.name} teslimi bugün!` : `${p.name} teslimine ${left} gün kaldı.`,
        ref: p.id,
      });
  }

  for (const p of stalledProjects(s, now))
    alerts.push({
      key: `stalled:${p.id}:${p.lastProgressAt.slice(0, 10)}`,
      level: 'bilgi',
      text: `${p.name} ${daysBetween(new Date(p.lastProgressAt), now)} gündür ilerlemiyor.`,
      ref: p.id,
    });

  for (const e of events) {
    const mins = (new Date(e.start).getTime() - now.getTime()) / 60000;
    if (!e.allDay && mins > 0 && mins <= 30)
      alerts.push({ key: `event:${e.id}:${e.start}`, level: 'bilgi', text: `${Math.round(mins)} dk sonra: ${e.title}`, ref: e.id });
  }

  if (s.pending.length)
    alerts.push({ key: 'pending', level: 'bilgi', text: `${s.pending.length} işlem onayını bekliyor.`, fix: 'Ana ekrandaki onay kartından onayla ya da reddet.' });

  // 5) Bildirimler (her konu için yalnızca bir kez)
  const notified = { ...s.meta.notified };
  const sent: string[] = [];
  if (health.notifications === 'ok') {
    for (const a of alerts) {
      if (a.key.startsWith('int:') || a.key === 'pending' || a.key === 'overdue:more' || notified[a.key]) continue;
      try {
        await device.notifier.notify('Jarvis', a.text);
        notified[a.key] = now.toISOString();
        sent.push(a.text);
      } catch {
        // bildirim gönderilemezse panelde zaten görünüyor
      }
    }
    // Bugün saati belli olan görevler için zamanında hatırlatma planla
    for (const t of s.tasks) {
      if (t.status === 'tamam' || !t.due || t.due.length <= 10) continue;
      const at = dueDate(t.due);
      const key = `due:${t.id}:${t.due}`;
      if (at.getTime() > now.getTime() && at.getTime() - now.getTime() < 36 * 3600_000 && !notified[key]) {
        try {
          await device.notifier.notify('Jarvis hatırlatma', `${trTime(at)} – ${t.title}`, at);
          notified[key] = now.toISOString();
          sent.push(`${t.title} için ${trTime(at)} hatırlatması kuruldu`);
        } catch {
          /* yoksay */
        }
      }
    }
  }

  // 6) Sabah brifingi
  let brief: string | undefined;
  const { h, m } = parseHHMM(s.profile.briefTime);
  const briefAt = new Date(now.getFullYear(), now.getMonth(), now.getDate(), h, m);
  if (now.getTime() >= briefAt.getTime() && s.meta.lastBriefDate !== dayKey(now)) {
    brief = morningBrief(s, now, events, weather);
    if (health.notifications === 'ok') {
      try {
        await device.notifier.notify('Jarvis sabah brifingi', brief);
      } catch {
        /* yoksay */
      }
    }
  }

  // Eski bildirim kayıtlarını temizle
  for (const [k, v] of Object.entries(notified)) if (daysBetween(new Date(v), now) > 14) delete notified[k];

  store.update((st) => {
    st.meta.notified = notified;
    st.meta.lastWatchAt = now.toISOString();
    if (brief) {
      st.meta.lastBriefDate = dayKey(now);
      st.meta.lastBrief = brief;
    }
  });
  if (brief) store.log('jarvis', 'brief', 'Sabah brifingini hazırladı');
  if (sent.length) store.log('jarvis', 'notify', `Bildirim gönderdi: ${sent.slice(0, 3).join(' · ')}${sent.length > 3 ? ` (+${sent.length - 3})` : ''}`);

  alerts.sort((a, b) => LEVEL_RANK[a.level] - LEVEL_RANK[b.level]);
  return { alerts, events, weather, health, brief };
}

function withTimeout<T>(p: Promise<T>, ms: number): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('yanıt gelmedi (zaman aşımı)')), ms);
    p.then(
      (v) => {
        clearTimeout(t);
        resolve(v);
      },
      (e) => {
        clearTimeout(t);
        reject(e);
      },
    );
  });
}

function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

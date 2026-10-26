// Günlük yönetici özeti. AI olmadan da (arka planda, internet zayıfken)
// gerçek verilerden üretilebilsin diye kural tabanlıdır. Jarvis sohbet
// sırasında bu özeti ve ham verileri birlikte kullanır.

import { focusList, isDueToday, isOverdue, nextStep, projectProgress, stalledProjects } from './priority';
import { dayKey, trTime } from './time';
import { CalEvent, State, STAGE_LABEL, Weather } from './types';

export interface DaySummary {
  doneToday: string[];
  jarvisToday: string[];
  dueToday: string[];
  overdue: string[];
  events: string[];
  stalled: string[];
  focus: string[];
  projects: string[];
}

export function summarizeDay(s: State, now: Date, events: CalEvent[]): DaySummary {
  const today = dayKey(now);
  const acts = s.activity.filter((a) => dayKey(new Date(a.at)) === today);
  return {
    doneToday: s.tasks.filter((t) => t.doneAt && dayKey(new Date(t.doneAt)) === today).map((t) => t.title),
    jarvisToday: acts.filter((a) => a.actor === 'jarvis').map((a) => a.text),
    dueToday: s.tasks.filter((t) => t.status !== 'tamam' && isDueToday(t, now) && !isOverdue(t, now)).map((t) => t.title),
    overdue: s.tasks.filter((t) => isOverdue(t, now)).map((t) => t.title),
    events: events
      .filter((e) => dayKey(new Date(e.start)) === today)
      .map((e) => (e.allDay ? `${e.title} (tüm gün)` : `${trTime(new Date(e.start))} ${e.title}`)),
    stalled: stalledProjects(s, now).map((p) => p.name),
    focus: focusList(s, now, events, 3).map((f) => f.title),
    projects: s.projects
      .filter((p) => p.status === 'aktif')
      .map((p) => {
        const pr = projectProgress(s, p);
        const step = nextStep(s, p);
        return `${p.name} – ${STAGE_LABEL[p.stage]}, %${pr.pct}${step ? `, sıradaki: ${step.title}` : ''}`;
      }),
  };
}

const list = (xs: string[], max = 3) =>
  xs.length <= max ? xs.join(', ') : `${xs.slice(0, max).join(', ')} ve ${xs.length - max} tane daha`;

// Sesli okunacak, doğal Türkçe sabah brifingi.
export function morningBrief(s: State, now: Date, events: CalEvent[], weather?: Weather | null): string {
  const d = summarizeDay(s, now, events);
  const name = s.profile.address || s.profile.name;
  const parts: string[] = [];
  parts.push(`Günaydın${name ? ' ' + name : ''}.`);
  if (weather) parts.push(`${weather.city} ${weather.now.temp} derece, ${weather.now.text.toLocaleLowerCase('tr')}.`);
  if (d.events.length) parts.push(`Bugün takviminde ${d.events.length} etkinlik var: ${list(d.events)}.`);
  else parts.push('Bugün takviminde etkinlik görünmüyor.');
  if (d.overdue.length) parts.push(`Geciken ${d.overdue.length} işin var: ${list(d.overdue)}.`);
  if (d.dueToday.length) parts.push(`Bugün bitmesi gerekenler: ${list(d.dueToday)}.`);
  if (d.stalled.length) parts.push(`${list(d.stalled)} birkaç gündür ilerlemiyor.`);
  if (d.focus.length) parts.push(`Önerim önce şuna odaklanman: ${d.focus[0]}.`);
  if (!d.overdue.length && !d.dueToday.length && !d.events.length && !d.focus.length)
    parts.push('Bekleyen acil bir iş yok. Yeni bir proje ya da plan için hazırım.');
  return parts.join(' ');
}

// Öncelik motoru: neyin şu an önemli olduğunu kural tabanlı ve açıklanabilir
// şekilde hesaplar. Hem panel hem bildirimler hem de Jarvis'in bağlamı bunu kullanır.

import { daysBetween, dueDate, dueDay, dayKey, relativeDue } from './time';
import { CalEvent, Project, State, STAGE_LABEL, Task } from './types';

export interface FocusItem {
  kind: 'task' | 'event' | 'project';
  id: string;
  title: string;
  reason: string;
  score: number;
  projectName?: string;
}

export function openTasks(s: State): Task[] {
  return s.tasks.filter((t) => t.status !== 'tamam');
}

// Sadece tarih verilmişse gün bitene kadar gecikmiş sayılmaz; saat verilmişse o an geçince sayılır.
export function isOverdue(t: Task, now: Date): boolean {
  if (!t.due || t.status === 'tamam') return false;
  if (t.due.length > 10) return dueDate(t.due).getTime() < now.getTime();
  return dueDay(t.due) < dayKey(now);
}

export function isDueToday(t: Task, now: Date): boolean {
  return !!t.due && dueDay(t.due) === dayKey(now);
}

export function projectProgress(s: State, p: Project): { done: number; total: number; pct: number } {
  const ts = s.tasks.filter((t) => t.projectId === p.id);
  const done = ts.filter((t) => t.status === 'tamam').length;
  return { done, total: ts.length, pct: ts.length ? Math.round((done / ts.length) * 100) : 0 };
}

// Projenin bir sonraki somut adımı: mevcut aşamadaki ilk açık görev.
export function nextStep(s: State, p: Project): Task | undefined {
  const open = s.tasks
    .filter((t) => t.projectId === p.id && t.status !== 'tamam')
    .sort((a, b) => stageRank(a, p) - stageRank(b, p) || a.priority - b.priority || a.order - b.order);
  return open[0];
}

function stageRank(t: Task, p: Project): number {
  const order = ['fikir', 'plan', 'yapim', 'kontrol', 'teslim'];
  return order.indexOf(t.stage ?? p.stage);
}

export function stalledProjects(s: State, now: Date, days = 4): Project[] {
  return s.projects.filter(
    (p) => p.status === 'aktif' && daysBetween(new Date(p.lastProgressAt), now) >= days,
  );
}

export function taskScore(t: Task, s: State, now: Date): { score: number; reason: string } {
  let score = (4 - t.priority) * 10;
  const reasons: string[] = [];
  if (t.priority === 1) reasons.push('yüksek öncelik');
  if (t.due) {
    const d = dueDate(t.due);
    const diff = daysBetween(now, d);
    if (isOverdue(t, now)) {
      score += 50 + Math.min(20, -diff * 3);
      reasons.push(relativeDue(t.due, now));
    } else if (diff === 0) {
      score += 35;
      reasons.push(`bitiş ${relativeDue(t.due, now)}`);
    } else if (diff <= 2) {
      score += 20;
      reasons.push(`bitiş ${relativeDue(t.due, now)}`);
    }
  }
  const p = t.projectId ? s.projects.find((x) => x.id === t.projectId) : undefined;
  if (p) {
    if (p.status !== 'aktif') score -= 30;
    if (p.deadline) {
      const left = daysBetween(now, new Date(p.deadline + 'T23:59:00'));
      if (left <= 7) {
        score += 25 - Math.max(0, left) * 2;
        reasons.push(`proje teslimine ${left <= 0 ? 'süre doldu' : left + ' gün'}`);
      }
    }
    if (nextStep(s, p)?.id === t.id) {
      score += 8;
      reasons.push('projenin sıradaki adımı');
    }
  }
  if (t.status === 'devam') {
    score += 5;
    reasons.push('devam ediyor');
  }
  return { score, reason: reasons.join(', ') || 'açık görev' };
}

export function focusList(s: State, now: Date, events: CalEvent[] = [], limit = 5): FocusItem[] {
  const items: FocusItem[] = openTasks(s)
    .filter((t) => !t.due || daysBetween(now, dueDate(t.due)) <= 7 || t.priority === 1)
    .map((t) => {
      const { score, reason } = taskScore(t, s, now);
      const p = t.projectId ? s.projects.find((x) => x.id === t.projectId) : undefined;
      return { kind: 'task' as const, id: t.id, title: t.title, reason, score, projectName: p?.name };
    });

  for (const e of events) {
    const start = new Date(e.start);
    const mins = (start.getTime() - now.getTime()) / 60000;
    if (!e.allDay && mins > -5 && mins <= 120) {
      items.push({
        kind: 'event',
        id: e.id,
        title: e.title,
        reason: mins <= 0 ? 'şimdi başlıyor' : `${Math.round(mins)} dk sonra`,
        score: 70 - mins / 4,
      });
    }
  }

  for (const p of stalledProjects(s, now)) {
    const step = nextStep(s, p);
    items.push({
      kind: 'project',
      id: p.id,
      title: step ? `${p.name}: ${step.title}` : `${p.name}: sıradaki adımı belirle`,
      reason: `${daysBetween(new Date(p.lastProgressAt), now)} gündür ilerleme yok (${STAGE_LABEL[p.stage]})`,
      score: 30,
      projectName: p.name,
    });
  }

  return items.sort((a, b) => b.score - a.score).slice(0, limit);
}

// İş akışı işlemleri. Arayüz ve Jarvis (AI araçları) aynı fonksiyonları
// kullanır; böylece kim yaparsa yapsın aynı kurallar işler ve kayda geçer.

import { newId, Store } from './store';
import { addDays, dayKey, dueDate, startOfDay } from './time';
import {
  Actor,
  Memory,
  Note,
  PendingAction,
  PendingKind,
  Project,
  Recurrence,
  Stage,
  STAGE_LABEL,
  STAGES,
  Task,
  TaskStatus,
} from './types';

export interface TaskInput {
  title: string;
  projectId?: string;
  stage?: Stage;
  priority?: 1 | 2 | 3;
  due?: string;
  notes?: string;
  recurrence?: Recurrence;
}

export function createTask(store: Store, input: TaskInput, actor: Actor): Task {
  const now = store.now().toISOString();
  const project = input.projectId ? findProject(store, input.projectId) : undefined;
  if (input.projectId && !project) throw new Error(`Proje bulunamadı: ${input.projectId}`);
  const order = Math.max(0, ...store.state.tasks.map((t) => t.order)) + 1;
  const task: Task = {
    id: newId('t'),
    title: input.title.trim(),
    projectId: project?.id,
    stage: project ? input.stage ?? project.stage : undefined,
    priority: input.priority ?? 2,
    due: normalizeDue(input.due),
    status: 'acik',
    notes: input.notes,
    recurrence: input.recurrence,
    createdAt: now,
    order,
  };
  store.update((s) => {
    s.tasks.push(task);
  });
  store.log(actor, 'task_created', `Görev eklendi: ${task.title}${project ? ` (${project.name})` : ''}`, task.id);
  return task;
}

export interface TaskPatch {
  title?: string;
  status?: TaskStatus;
  priority?: 1 | 2 | 3;
  due?: string | null;
  notes?: string;
  stage?: Stage;
}

export interface UpdateResult {
  task: Task;
  effects: string[]; // zincirleme olarak gerçekleşen işler
}

export function updateTask(store: Store, id: string, patch: TaskPatch, actor: Actor): UpdateResult {
  const existing = store.state.tasks.find((t) => t.id === id);
  if (!existing) throw new Error(`Görev bulunamadı: ${id}`);
  const effects: string[] = [];
  const nowIso = store.now().toISOString();
  const completing = patch.status === 'tamam' && existing.status !== 'tamam';
  const reopening = patch.status && patch.status !== 'tamam' && existing.status === 'tamam';

  store.update((s) => {
    const t = s.tasks.find((x) => x.id === id)!;
    if (patch.title !== undefined) t.title = patch.title.trim();
    if (patch.priority !== undefined) t.priority = patch.priority;
    if (patch.notes !== undefined) t.notes = patch.notes;
    if (patch.stage !== undefined) t.stage = patch.stage;
    if (patch.due !== undefined) t.due = patch.due === null ? undefined : normalizeDue(patch.due);
    if (patch.status !== undefined) t.status = patch.status;
    if (completing) t.doneAt = nowIso;
    if (reopening) t.doneAt = undefined;
    if (t.projectId && (completing || patch.status === 'devam')) {
      const p = s.projects.find((x) => x.id === t.projectId);
      if (p) {
        p.lastProgressAt = nowIso;
        p.updatedAt = nowIso;
      }
    }
  });

  const task = store.state.tasks.find((t) => t.id === id)!;
  if (completing) {
    store.log(actor, 'task_done', `Tamamlandı: ${task.title}`, task.id);
    if (task.recurrence) {
      const next = createTask(
        store,
        {
          title: task.title,
          projectId: task.projectId,
          stage: task.stage,
          priority: task.priority,
          notes: task.notes,
          recurrence: task.recurrence,
          due: nextOccurrence(task, store.now()),
        },
        'jarvis',
      );
      effects.push(`Tekrarlayan görev için sonraki tarih açıldı: ${next.due}`);
    }
    if (task.projectId) {
      const adv = advanceProjectIfReady(store, task.projectId);
      if (adv) effects.push(adv);
    }
  } else if (Object.keys(patch).length) {
    store.log(actor, 'task_updated', `Görev güncellendi: ${task.title}`, task.id);
  }
  return { task, effects };
}

// Aşamadaki tüm görevler bittiğinde projeyi sonraki aşamaya geçirir.
export function advanceProjectIfReady(store: Store, projectId: string): string | null {
  const p = findProject(store, projectId);
  if (!p || p.status !== 'aktif') return null;
  const tasks = store.state.tasks.filter((t) => t.projectId === p.id);
  const inStage = tasks.filter((t) => (t.stage ?? p.stage) === p.stage);
  if (inStage.length === 0 || inStage.some((t) => t.status !== 'tamam')) return null;

  const idx = STAGES.indexOf(p.stage);
  const later = STAGES.slice(idx + 1);
  const nextWithWork = later.find((st) => tasks.some((t) => t.stage === st && t.status !== 'tamam'));
  if (nextWithWork) {
    setProjectStage(store, p.id, nextWithWork, 'jarvis');
    return `${p.name}: ${STAGE_LABEL[p.stage]} aşaması bitti, ${STAGE_LABEL[nextWithWork]} aşamasına geçildi.`;
  }
  if (tasks.every((t) => t.status === 'tamam')) {
    return `${p.name}: tüm görevler tamamlandı. Yeni aşama için plan gerekiyor ya da proje bitirilebilir.`;
  }
  return null;
}

function setProjectStage(store: Store, id: string, stage: Stage, actor: Actor) {
  const nowIso = store.now().toISOString();
  store.update((s) => {
    const p = s.projects.find((x) => x.id === id)!;
    p.stage = stage;
    p.updatedAt = nowIso;
    p.lastProgressAt = nowIso;
  });
  const p = findProject(store, id)!;
  store.log(actor, 'project_stage', `${p.name} → ${STAGE_LABEL[stage]} aşaması`, id);
}

export interface ProjectInput {
  name: string;
  goal?: string;
  stage?: Stage;
  deadline?: string;
  tasks?: Omit<TaskInput, 'projectId'>[];
}

export function createProject(store: Store, input: ProjectInput, actor: Actor): { project: Project; tasks: Task[] } {
  const nowIso = store.now().toISOString();
  const project: Project = {
    id: newId('p'),
    name: input.name.trim(),
    goal: input.goal?.trim() ?? '',
    stage: input.stage ?? (input.tasks?.length ? 'plan' : 'fikir'),
    status: 'aktif',
    deadline: input.deadline ? input.deadline.slice(0, 10) : undefined,
    createdAt: nowIso,
    updatedAt: nowIso,
    lastProgressAt: nowIso,
  };
  store.update((s) => {
    s.projects.push(project);
  });
  store.log(actor, 'project_created', `Yeni proje: ${project.name}`, project.id);
  const tasks = (input.tasks ?? []).map((t) => createTask(store, { ...t, projectId: project.id }, actor));
  return { project, tasks };
}

export interface ProjectPatch {
  name?: string;
  goal?: string;
  stage?: Stage;
  status?: Project['status'];
  deadline?: string | null;
}

export function updateProject(store: Store, id: string, patch: ProjectPatch, actor: Actor): Project {
  const p = findProject(store, id);
  if (!p) throw new Error(`Proje bulunamadı: ${id}`);
  if (patch.stage && patch.stage !== p.stage) setProjectStage(store, id, patch.stage, actor);
  const nowIso = store.now().toISOString();
  store.update((s) => {
    const x = s.projects.find((q) => q.id === id)!;
    if (patch.name !== undefined) x.name = patch.name.trim();
    if (patch.goal !== undefined) x.goal = patch.goal;
    if (patch.deadline !== undefined) x.deadline = patch.deadline ? patch.deadline.slice(0, 10) : undefined;
    if (patch.status !== undefined) x.status = patch.status;
    x.updatedAt = nowIso;
  });
  const updated = findProject(store, id)!;
  if (patch.status && patch.status !== p.status) {
    store.log(actor, 'project_status', `${updated.name}: durum ${patch.status}`, id);
  } else if (patch.name || patch.goal || patch.deadline !== undefined) {
    store.log(actor, 'project_updated', `${updated.name} güncellendi`, id);
  }
  return updated;
}

export function addNote(store: Store, input: { text: string; title?: string; projectId?: string }, actor: Actor): Note {
  const note: Note = {
    id: newId('n'),
    title: input.title?.trim() || undefined,
    text: input.text.trim(),
    projectId: input.projectId && findProject(store, input.projectId) ? input.projectId : undefined,
    createdAt: store.now().toISOString(),
  };
  store.update((s) => {
    s.notes.push(note);
  });
  store.log(actor, 'note_added', `Not kaydedildi: ${note.title ?? note.text.slice(0, 40)}`, note.id);
  return note;
}

export function remember(store: Store, text: string, kind: Memory['kind'], actor: Actor): Memory {
  const m: Memory = { id: newId('m'), kind, text: text.trim(), createdAt: store.now().toISOString() };
  store.update((s) => {
    s.memories.push(m);
  });
  store.log(actor, 'memory', `Hafızaya alındı: ${m.text}`, m.id);
  return m;
}

export function forget(store: Store, id: string, actor: Actor): boolean {
  const m = store.state.memories.find((x) => x.id === id);
  if (!m) return false;
  store.update((s) => {
    s.memories = s.memories.filter((x) => x.id !== id);
  });
  store.log(actor, 'memory_removed', `Hafızadan silindi: ${m.text}`, id);
  return true;
}

// Geri alınamaz işlemler doğrudan yapılmaz; onay kuyruğuna girer.
export function requestDeletion(
  store: Store,
  kind: PendingKind,
  targetId: string,
  summary: string,
  reason?: string,
): PendingAction {
  const existing = store.state.pending.find((p) => p.kind === kind && p.targetId === targetId);
  if (existing) return existing;
  const pa: PendingAction = { id: newId('pa'), at: store.now().toISOString(), kind, targetId, summary, reason };
  store.update((s) => {
    s.pending.push(pa);
  });
  store.log('jarvis', 'pending', `Onay bekliyor: ${summary}`, pa.id);
  return pa;
}

export async function resolvePending(
  store: Store,
  id: string,
  approve: boolean,
  deleteEvent?: (eventId: string) => Promise<void>,
): Promise<string> {
  const pa = store.state.pending.find((p) => p.id === id);
  if (!pa) throw new Error('Onay kaydı bulunamadı');
  store.update((s) => {
    s.pending = s.pending.filter((p) => p.id !== id);
  });
  if (!approve) {
    store.log('user', 'pending_rejected', `Reddedildi: ${pa.summary}`);
    return 'İptal edildi.';
  }
  switch (pa.kind) {
    case 'delete_task':
      store.update((s) => {
        s.tasks = s.tasks.filter((t) => t.id !== pa.targetId);
      });
      break;
    case 'delete_project':
      store.update((s) => {
        s.projects = s.projects.filter((p) => p.id !== pa.targetId);
        s.tasks = s.tasks.filter((t) => t.projectId !== pa.targetId);
        s.notes = s.notes.map((n) => (n.projectId === pa.targetId ? { ...n, projectId: undefined } : n));
      });
      break;
    case 'delete_note':
      store.update((s) => {
        s.notes = s.notes.filter((n) => n.id !== pa.targetId);
      });
      break;
    case 'delete_event':
      if (!deleteEvent) throw new Error('Takvim bağlantısı yok');
      await deleteEvent(pa.targetId);
      break;
  }
  store.log('user', 'pending_approved', `Onaylandı ve yapıldı: ${pa.summary}`);
  return 'Tamamlandı.';
}

export function findProject(store: Store, idOrName: string): Project | undefined {
  const s = store.state;
  return (
    s.projects.find((p) => p.id === idOrName) ??
    s.projects.find((p) => p.name.toLocaleLowerCase('tr') === idOrName.toLocaleLowerCase('tr'))
  );
}

export function normalizeDue(due?: string | null): string | undefined {
  if (!due) return undefined;
  const m = due.match(/^(\d{4}-\d{2}-\d{2})(?:[T ](\d{2}):(\d{2}))?/);
  if (!m) throw new Error(`Tarih biçimi anlaşılamadı: ${due} (YYYY-MM-DD veya YYYY-MM-DDTHH:mm)`);
  return m[2] ? `${m[1]}T${m[2]}:${m[3]}` : m[1];
}

export function nextOccurrence(task: Pick<Task, 'due' | 'recurrence'>, now: Date): string {
  const time = task.due && task.due.length > 10 ? task.due.slice(10) : '';
  let d = task.due ? startOfDay(dueDate(task.due)) : startOfDay(now);
  const today = startOfDay(now);
  const step = () => {
    if (task.recurrence === 'haftalik') d = addDays(d, 7);
    else {
      d = addDays(d, 1);
      if (task.recurrence === 'hafta_ici') while (d.getDay() === 0 || d.getDay() === 6) d = addDays(d, 1);
    }
  };
  step();
  while (d.getTime() <= today.getTime()) step();
  return dayKey(d) + time;
}

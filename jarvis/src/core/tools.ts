// Jarvis'in aksiyon araçları. Claude bu araçları çağırarak gerçek veriyi
// okur ve değiştirir; her çağrı ops.ts üzerinden kayda geçer.

import type Anthropic from '@anthropic-ai/sdk';
import type { Device } from './device';
import {
  addNote,
  createProject,
  createTask,
  findProject,
  forget,
  normalizeDue,
  remember,
  requestDeletion,
  updateProject,
  updateTask,
} from './ops';
import { isDueToday, isOverdue, nextStep, projectProgress } from './priority';
import type { Store } from './store';
import { addDays, dayKey, dueDate, trTime } from './time';
import { PendingKind, Recurrence, Stage, STAGES, Task, TaskStatus } from './types';

type Tool = Anthropic.Beta.BetaToolUnion;

const stageEnum = { type: 'string', enum: STAGES, description: 'fikir | plan | yapim | kontrol | teslim' } as const;
const priorityProp = { type: 'integer', enum: [1, 2, 3], description: '1 yüksek, 2 normal, 3 düşük' } as const;
const dueProp = {
  type: 'string',
  description: 'Bitiş: "YYYY-MM-DD" (gün içinde) veya saatli "YYYY-MM-DDTHH:mm" (yerel saat)',
} as const;

export const TOOLS: Tool[] = [
  {
    name: 'list_tasks',
    description:
      'Görevleri listeler. Bağlamdaki özet yetmediğinde (tamamlananlar, belirli proje, tüm liste) kullan.',
    input_schema: {
      type: 'object',
      properties: {
        scope: { type: 'string', enum: ['bugun', 'geciken', 'acik', 'tamamlanan', 'hepsi'] },
        project_id: { type: 'string' },
      },
      required: ['scope'],
    },
  },
  {
    name: 'create_task',
    description:
      'Yeni görev ekler. Kişisel işler için project_id verme. Alışkanlıklar (spor, İngilizce çalışma vb.) için recurrence kullan.',
    input_schema: {
      type: 'object',
      properties: {
        title: { type: 'string' },
        project_id: { type: 'string' },
        stage: stageEnum,
        priority: priorityProp,
        due: dueProp,
        notes: { type: 'string' },
        recurrence: { type: 'string', enum: ['gunluk', 'hafta_ici', 'haftalik'] },
      },
      required: ['title'],
    },
  },
  {
    name: 'update_task',
    description:
      'Görevi günceller veya tamamlar (status=tamam). Tamamlanınca tekrarlayan görevin sonrakini açma ve proje aşamasını ilerletme otomatik yapılır; sonuçta "effects" olarak döner.',
    input_schema: {
      type: 'object',
      properties: {
        task_id: { type: 'string' },
        status: { type: 'string', enum: ['acik', 'devam', 'bekliyor', 'tamam'] },
        title: { type: 'string' },
        priority: priorityProp,
        due: { type: 'string', description: dueProp.description + '. Kaldırmak için boş string.' },
        notes: { type: 'string' },
        stage: stageEnum,
      },
      required: ['task_id'],
    },
  },
  {
    name: 'get_project',
    description: 'Bir projenin tüm görevlerini, notlarını ve ilerlemesini getirir.',
    input_schema: { type: 'object', properties: { project_id: { type: 'string' } }, required: ['project_id'] },
  },
  {
    name: 'create_project',
    description:
      'Yeni proje veya fikir oluşturur. Kullanıcı bir fikir anlattığında projeyi oluştur ve mümkünse aşamalara bölünmüş somut ilk görevleri (tasks) de ekle.',
    input_schema: {
      type: 'object',
      properties: {
        name: { type: 'string' },
        goal: { type: 'string', description: 'Projenin amacı / başarı tanımı' },
        deadline: { type: 'string', description: 'YYYY-MM-DD' },
        stage: stageEnum,
        tasks: {
          type: 'array',
          items: {
            type: 'object',
            properties: { title: { type: 'string' }, stage: stageEnum, priority: priorityProp, due: dueProp },
            required: ['title'],
          },
        },
      },
      required: ['name', 'goal'],
    },
  },
  {
    name: 'update_project',
    description: 'Projenin adını, hedefini, aşamasını, durumunu (aktif/beklemede/tamamlandi/iptal) veya teslim tarihini değiştirir.',
    input_schema: {
      type: 'object',
      properties: {
        project_id: { type: 'string' },
        name: { type: 'string' },
        goal: { type: 'string' },
        stage: stageEnum,
        status: { type: 'string', enum: ['aktif', 'beklemede', 'tamamlandi', 'iptal'] },
        deadline: { type: 'string', description: 'YYYY-MM-DD, kaldırmak için boş string' },
      },
      required: ['project_id'],
    },
  },
  {
    name: 'add_note',
    description: 'Not, taslak, plan, fikir, alışveriş listesi veya hazırlanan bir metni kaydeder.',
    input_schema: {
      type: 'object',
      properties: { title: { type: 'string' }, text: { type: 'string' }, project_id: { type: 'string' } },
      required: ['text'],
    },
  },
  {
    name: 'search_notes',
    description: 'Notlarda arama yapar (boş sorgu son notları getirir).',
    input_schema: { type: 'object', properties: { query: { type: 'string' } }, required: ['query'] },
  },
  {
    name: 'get_calendar',
    description: 'Telefon takvimindeki etkinlikleri getirir.',
    input_schema: {
      type: 'object',
      properties: { from: { type: 'string', description: 'YYYY-MM-DD' }, to: { type: 'string', description: 'YYYY-MM-DD (dahil)' } },
      required: ['from', 'to'],
    },
  },
  {
    name: 'create_calendar_event',
    description: 'Telefon takvimine etkinlik ekler (toplantı, spor, çalışma bloğu vb.).',
    input_schema: {
      type: 'object',
      properties: {
        title: { type: 'string' },
        start: { type: 'string', description: 'YYYY-MM-DDTHH:mm yerel saat' },
        end: { type: 'string', description: 'YYYY-MM-DDTHH:mm; verilmezse 1 saat' },
        notes: { type: 'string' },
        location: { type: 'string' },
      },
      required: ['title', 'start'],
    },
  },
  {
    name: 'set_reminder',
    description: 'Belirli bir zamanda telefona hatırlatma bildirimi kurar.',
    input_schema: {
      type: 'object',
      properties: { text: { type: 'string' }, at: { type: 'string', description: 'YYYY-MM-DDTHH:mm yerel saat' } },
      required: ['text', 'at'],
    },
  },
  {
    name: 'remember',
    description:
      'Kullanıcının kalıcı talimatını, tercihini veya önemli bir bilgiyi hafızaya alır ("bundan sonra...", "unutma ki..." gibi).',
    input_schema: {
      type: 'object',
      properties: { text: { type: 'string' }, kind: { type: 'string', enum: ['talimat', 'bilgi', 'tercih'] } },
      required: ['text', 'kind'],
    },
  },
  {
    name: 'forget',
    description: 'Hafızadaki bir kaydı siler.',
    input_schema: { type: 'object', properties: { memory_id: { type: 'string' } }, required: ['memory_id'] },
  },
  {
    name: 'get_activity',
    description: 'Geçmiş işlem kaydını (Jarvis ve kullanıcının yaptıkları) getirir.',
    input_schema: { type: 'object', properties: { days: { type: 'integer', description: 'Kaç gün geriye (varsayılan 1)' } } },
  },
  {
    name: 'get_weather',
    description: 'Kullanıcının şehri için güncel hava ve 4 günlük tahmin.',
    input_schema: { type: 'object', properties: {} },
  },
  {
    name: 'request_deletion',
    description:
      'Silme geri alınamaz olduğu için doğrudan yapılmaz: bu araç kullanıcıya onay kartı açar. Kullanıcıya onaylaması gerektiğini söyle.',
    input_schema: {
      type: 'object',
      properties: {
        kind: { type: 'string', enum: ['task', 'project', 'note', 'calendar_event'] },
        id: { type: 'string' },
        reason: { type: 'string' },
      },
      required: ['kind', 'id'],
    },
  },
  { type: 'web_search_20260209', name: 'web_search', max_uses: 3 },
];

// Kullanıcıya "şu an ne yapıyorum" göstermek için
export const TOOL_LABEL: Record<string, string> = {
  list_tasks: 'Görevlere bakıyorum',
  create_task: 'Görev ekliyorum',
  update_task: 'Görevi güncelliyorum',
  get_project: 'Projeyi inceliyorum',
  create_project: 'Projeyi kuruyorum',
  update_project: 'Projeyi güncelliyorum',
  add_note: 'Not alıyorum',
  search_notes: 'Notlarda arıyorum',
  get_calendar: 'Takvimi okuyorum',
  create_calendar_event: 'Takvime ekliyorum',
  set_reminder: 'Hatırlatma kuruyorum',
  remember: 'Hafızaya alıyorum',
  forget: 'Hafızadan siliyorum',
  get_activity: 'Kayıtlara bakıyorum',
  get_weather: 'Hava durumuna bakıyorum',
  request_deletion: 'Onay hazırlıyorum',
  web_search: 'İnternette araştırıyorum',
};

export interface ToolContext {
  store: Store;
  device: Device;
}

type Input = Record<string, unknown>;

const str = (v: unknown): string | undefined => (typeof v === 'string' && v.trim() !== '' ? v : undefined);
function req(i: Input, k: string): string {
  const v = str(i[k]);
  if (!v) throw new Error(`"${k}" alanı gerekli`);
  return v;
}
const prio = (v: unknown) => (v === 1 || v === 2 || v === 3 ? v : undefined);
const stage = (v: unknown) => (STAGES.includes(v as Stage) ? (v as Stage) : undefined);

function taskView(t: Task, ctx: ToolContext) {
  const now = ctx.store.now();
  const p = t.projectId ? ctx.store.state.projects.find((x) => x.id === t.projectId) : undefined;
  return {
    id: t.id,
    title: t.title,
    status: t.status,
    priority: t.priority,
    due: t.due,
    overdue: isOverdue(t, now) || undefined,
    project: p ? `${p.name} (${p.id})` : undefined,
    stage: t.stage,
    recurrence: t.recurrence,
    notes: t.notes,
    doneAt: t.doneAt,
  };
}

function localDate(s: string): Date {
  const n = normalizeDue(s)!;
  return n.length > 10 ? dueDate(n) : new Date(`${n}T00:00:00`);
}

export async function executeTool(name: string, input: Input, ctx: ToolContext): Promise<unknown> {
  const { store, device } = ctx;
  const now = store.now();
  switch (name) {
    case 'list_tasks': {
      const scope = str(input.scope) ?? 'acik';
      const pid = str(input.project_id);
      let ts = store.state.tasks.filter((t) => !pid || t.projectId === pid);
      if (scope === 'bugun') ts = ts.filter((t) => t.status !== 'tamam' && t.due && (isDueToday(t, now) || isOverdue(t, now)));
      else if (scope === 'geciken') ts = ts.filter((t) => isOverdue(t, now));
      else if (scope === 'acik') ts = ts.filter((t) => t.status !== 'tamam');
      else if (scope === 'tamamlanan') ts = ts.filter((t) => t.status === 'tamam').slice(-40);
      return ts.slice(0, 80).map((t) => taskView(t, ctx));
    }
    case 'create_task': {
      const t = createTask(
        store,
        {
          title: req(input, 'title'),
          projectId: str(input.project_id),
          stage: stage(input.stage),
          priority: prio(input.priority),
          due: str(input.due),
          notes: str(input.notes),
          recurrence: str(input.recurrence) as Recurrence | undefined,
        },
        'jarvis',
      );
      return { ok: true, task: taskView(t, ctx) };
    }
    case 'update_task': {
      const due = input.due === '' ? null : str(input.due);
      const r = updateTask(
        store,
        req(input, 'task_id'),
        {
          status: str(input.status) as TaskStatus | undefined,
          title: str(input.title),
          priority: prio(input.priority),
          due,
          notes: str(input.notes),
          stage: stage(input.stage),
        },
        'jarvis',
      );
      return { ok: true, task: taskView(r.task, ctx), effects: r.effects };
    }
    case 'get_project': {
      const p = findProject(store, req(input, 'project_id'));
      if (!p) throw new Error('Proje bulunamadı');
      return {
        ...p,
        progress: projectProgress(store.state, p),
        nextStep: nextStep(store.state, p)?.title,
        tasks: store.state.tasks.filter((t) => t.projectId === p.id).map((t) => taskView(t, ctx)),
        notes: store.state.notes.filter((n) => n.projectId === p.id).map((n) => ({ id: n.id, title: n.title, text: n.text.slice(0, 600) })),
      };
    }
    case 'create_project': {
      const tasks = Array.isArray(input.tasks) ? (input.tasks as Input[]) : [];
      const r = createProject(
        store,
        {
          name: req(input, 'name'),
          goal: str(input.goal),
          deadline: str(input.deadline),
          stage: stage(input.stage),
          tasks: tasks
            .filter((t) => str(t.title))
            .map((t) => ({ title: str(t.title)!, stage: stage(t.stage), priority: prio(t.priority), due: str(t.due) })),
        },
        'jarvis',
      );
      return { ok: true, project: r.project, tasks: r.tasks.map((t) => taskView(t, ctx)) };
    }
    case 'update_project': {
      const p = updateProject(
        store,
        req(input, 'project_id'),
        {
          name: str(input.name),
          goal: str(input.goal),
          stage: stage(input.stage),
          status: str(input.status) as 'aktif' | undefined,
          deadline: input.deadline === '' ? null : str(input.deadline),
        },
        'jarvis',
      );
      return { ok: true, project: p };
    }
    case 'add_note': {
      const n = addNote(store, { text: req(input, 'text'), title: str(input.title), projectId: str(input.project_id) }, 'jarvis');
      return { ok: true, note_id: n.id };
    }
    case 'search_notes': {
      const q = (str(input.query) ?? '').toLocaleLowerCase('tr');
      const ns = store.state.notes
        .filter((n) => !q || `${n.title ?? ''} ${n.text}`.toLocaleLowerCase('tr').includes(q))
        .slice(-15)
        .reverse();
      return ns.map((n) => ({ id: n.id, title: n.title, text: n.text.slice(0, 800), createdAt: n.createdAt, projectId: n.projectId }));
    }
    case 'get_calendar': {
      const st = await device.calendar.status();
      if (st !== 'ok') throw new Error('Takvim izni yok. Kullanıcı Ayarlar > Bağlantılar bölümünden izin vermeli.');
      const from = localDate(req(input, 'from'));
      const to = addDays(localDate(req(input, 'to')), 1);
      const evs = await device.calendar.list(from, to);
      return evs.map((e) => ({
        id: e.id,
        title: e.title,
        start: e.allDay ? dayKey(new Date(e.start)) : `${dayKey(new Date(e.start))} ${trTime(new Date(e.start))}`,
        end: e.allDay ? undefined : trTime(new Date(e.end)),
        allDay: e.allDay || undefined,
        location: e.location,
      }));
    }
    case 'create_calendar_event': {
      const st = await device.calendar.status();
      if (st !== 'ok') throw new Error('Takvim izni yok. Kullanıcı Ayarlar > Bağlantılar bölümünden izin vermeli.');
      const start = localDate(req(input, 'start'));
      const end = str(input.end) ? localDate(str(input.end)!) : new Date(start.getTime() + 3600_000);
      const ev = await device.calendar.create({ title: req(input, 'title'), start, end, notes: str(input.notes), location: str(input.location) });
      store.log('jarvis', 'event_created', `Takvime eklendi: ${ev.title} (${dayKey(start)} ${trTime(start)})`, ev.id);
      return { ok: true, event_id: ev.id };
    }
    case 'set_reminder': {
      const at = localDate(req(input, 'at'));
      if (at.getTime() <= now.getTime()) throw new Error('Hatırlatma zamanı geçmişte olamaz');
      if ((await device.notifier.status()) !== 'ok') throw new Error('Bildirim izni yok. Kullanıcı Ayarlar > Bağlantılar bölümünden izin vermeli.');
      const text = req(input, 'text');
      await device.notifier.notify('Jarvis hatırlatma', text, at);
      store.log('jarvis', 'reminder', `Hatırlatma kuruldu: ${text} (${dayKey(at)} ${trTime(at)})`);
      return { ok: true };
    }
    case 'remember': {
      const kind = (str(input.kind) as 'talimat' | 'bilgi' | 'tercih') ?? 'bilgi';
      const m = remember(store, req(input, 'text'), kind, 'jarvis');
      return { ok: true, memory_id: m.id };
    }
    case 'forget':
      return { ok: forget(store, req(input, 'memory_id'), 'jarvis') };
    case 'get_activity': {
      const days = typeof input.days === 'number' ? Math.min(30, Math.max(1, input.days)) : 1;
      const since = addDays(now, -days).toISOString();
      return store.state.activity
        .filter((a) => a.at >= since)
        .slice(-120)
        .map((a) => ({ at: `${dayKey(new Date(a.at))} ${trTime(new Date(a.at))}`, who: a.actor, what: a.text }));
    }
    case 'get_weather':
      return device.weather(store.state.profile.city || 'İstanbul');
    case 'request_deletion': {
      const kind = req(input, 'kind');
      const id = req(input, 'id');
      const map: Record<string, PendingKind> = {
        task: 'delete_task',
        project: 'delete_project',
        note: 'delete_note',
        calendar_event: 'delete_event',
      };
      let summary: string;
      if (kind === 'task') {
        const t = store.state.tasks.find((x) => x.id === id);
        if (!t) throw new Error('Görev bulunamadı');
        summary = `Görevi sil: ${t.title}`;
      } else if (kind === 'project') {
        const p = findProject(store, id);
        if (!p) throw new Error('Proje bulunamadı');
        summary = `Projeyi ve görevlerini sil: ${p.name}`;
      } else if (kind === 'note') {
        const n = store.state.notes.find((x) => x.id === id);
        if (!n) throw new Error('Not bulunamadı');
        summary = `Notu sil: ${n.title ?? n.text.slice(0, 40)}`;
      } else {
        const e = await device.calendar.find(id);
        if (!e) throw new Error('Etkinlik bulunamadı');
        summary = `Takvimden sil: ${e.title} (${dayKey(new Date(e.start))})`;
      }
      const pa = requestDeletion(store, map[kind], kind === 'project' ? findProject(store, id)!.id : id, summary, str(input.reason));
      return { ok: true, pending_id: pa.id, note: 'Kullanıcı onayı bekleniyor; ana ekranda onay kartı açıldı.' };
    }
    default:
      throw new Error(`Bilinmeyen araç: ${name}`);
  }
}

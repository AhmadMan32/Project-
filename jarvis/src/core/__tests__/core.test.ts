import { describe, expect, it, vi } from 'vitest';
import { askJarvis } from '../agent';
import type { Device } from '../device';
import { createProject, createTask, nextOccurrence, resolvePending, updateTask } from '../ops';
import { focusList, isOverdue } from '../priority';
import { buildSnapshot } from '../prompt';
import { seedIfNeeded } from '../seed';
import { memoryPersistence, Store } from '../store';
import { executeTool } from '../tools';
import type { CalEvent } from '../types';
import { stripWake } from '../wake';
import { runWatch } from '../watcher';

// 26 Ekim 2026 Pazartesi 21:00 yerel
const NOW = new Date(2026, 9, 26, 21, 0);

async function makeStore(now = NOW) {
  const p = memoryPersistence();
  const store = new Store(p, () => now);
  await store.load();
  return { store, p };
}

function fakeDevice(over: Partial<{ cal: 'ok' | 'izin_yok'; notif: 'ok' | 'izin_yok'; events: CalEvent[] }> = {}) {
  const notified: { title: string; body: string; at?: Date }[] = [];
  const created: CalEvent[] = [];
  const device: Device = {
    calendar: {
      status: async () => over.cal ?? 'ok',
      list: async () => [...(over.events ?? []), ...created],
      create: async (e) => {
        const ev = { id: `ev${created.length}`, title: e.title, start: e.start.toISOString(), end: e.end.toISOString(), allDay: false };
        created.push(ev);
        return ev;
      },
      remove: async () => {},
      find: async (id) => created.find((e) => e.id === id) ?? null,
    },
    notifier: {
      status: async () => over.notif ?? 'ok',
      notify: async (title, body, at) => {
        notified.push({ title, body, at });
        return 'n';
      },
    },
    weather: async (city) => ({
      city,
      now: { temp: 18, code: 0, text: 'Açık', wind: 12, humidity: 62 },
      days: [],
    }),
  };
  return { device, notified, created };
}

describe('görevler ve iş akışı', () => {
  it('tekrarlayan görev tamamlanınca bir sonraki açılır', async () => {
    const { store } = await makeStore();
    const t = createTask(store, { title: 'Spor', due: '2026-10-26T19:00', recurrence: 'gunluk' }, 'user');
    const r = updateTask(store, t.id, { status: 'tamam' }, 'user');
    expect(r.effects[0]).toContain('2026-10-27T19:00');
    const open = store.state.tasks.filter((x) => x.status !== 'tamam');
    expect(open).toHaveLength(1);
    expect(open[0].due).toBe('2026-10-27T19:00');
    expect(store.state.activity.some((a) => a.kind === 'task_done')).toBe(true);
  });

  it('hafta içi tekrar hafta sonunu atlar', () => {
    // 30 Ekim 2026 Cuma -> sonraki 2 Kasım Pazartesi
    expect(nextOccurrence({ due: '2026-10-30', recurrence: 'hafta_ici' }, new Date(2026, 9, 30, 10))).toBe('2026-11-02');
  });

  it('aşamadaki tüm görevler bitince proje sonraki aşamaya geçer', async () => {
    const { store } = await makeStore();
    const { project, tasks } = createProject(
      store,
      { name: 'YouTube kanalı', goal: 'İlk video', tasks: [{ title: 'Konu seç', stage: 'plan' }, { title: 'Çekim', stage: 'yapim' }] },
      'user',
    );
    expect(project.stage).toBe('plan');
    const r = updateTask(store, tasks[0].id, { status: 'tamam' }, 'user');
    expect(r.effects.join(' ')).toContain('Yapım');
    expect(store.state.projects[0].stage).toBe('yapim');
  });

  it('gecikme: tarihli görev gün bitene kadar gecikmiş sayılmaz, saatli görev saat geçince sayılır', async () => {
    const { store } = await makeStore();
    const a = createTask(store, { title: 'a', due: '2026-10-26' }, 'user');
    const b = createTask(store, { title: 'b', due: '2026-10-26T20:00' }, 'user');
    const c = createTask(store, { title: 'c', due: '2026-10-25' }, 'user');
    expect(isOverdue(a, NOW)).toBe(false);
    expect(isOverdue(b, NOW)).toBe(true);
    expect(isOverdue(c, NOW)).toBe(true);
    expect(focusList(store.state, NOW)[0].title).toBe('c');
  });

  it('veriler kalıcı olarak kaydedilip yüklenir', async () => {
    const { store, p } = await makeStore();
    createTask(store, { title: 'kalıcı' }, 'user');
    await store.flush();
    const again = new Store(p, () => NOW);
    await again.load();
    expect(again.state.tasks[0].title).toBe('kalıcı');
  });

  it('ilk açılışta depodaki TÜBİTAK projesi bir kez içe aktarılır', async () => {
    const { store } = await makeStore();
    expect(seedIfNeeded(store)).toBe(true);
    expect(seedIfNeeded(store)).toBe(false);
    expect(store.state.projects).toHaveLength(1);
    expect(store.state.tasks.length).toBeGreaterThan(5);
  });
});

describe('izleyici', () => {
  it('eksik bağlantıları açıkça bildirir', async () => {
    const { store } = await makeStore();
    const { device } = fakeDevice({ cal: 'izin_yok', notif: 'izin_yok' });
    const r = await runWatch(store, device, { claude: 'eksik' });
    const keys = r.alerts.map((a) => a.key);
    expect(keys).toEqual(expect.arrayContaining(['int:claude', 'int:calendar', 'int:notifications']));
    expect(r.alerts[0].level).toBe('kritik');
    expect(r.alerts.find((a) => a.key === 'int:calendar')?.fix).toContain('Ayarlar');
  });

  it('geciken işler için bir kez bildirim gönderir ve sabah brifingini bir kez hazırlar', async () => {
    const { store } = await makeStore();
    createTask(store, { title: 'Rapor taslağı', due: '2026-10-24', priority: 1 }, 'user');
    const { device, notified } = fakeDevice({
      events: [{ id: 'e1', title: 'Spor Salonu', start: new Date(2026, 9, 26, 21, 20).toISOString(), end: new Date(2026, 9, 26, 22, 20).toISOString(), allDay: false }],
    });
    const r1 = await runWatch(store, device, { claude: 'ok' });
    expect(r1.brief).toContain('Günaydın');
    expect(r1.brief).toContain('Rapor taslağı');
    const bodies = notified.map((n) => n.body).join(' | ');
    expect(bodies).toContain('Gecikti: Rapor taslağı');
    expect(bodies).toContain('Spor Salonu');
    const count = notified.length;
    const r2 = await runWatch(store, device, { claude: 'ok' });
    expect(r2.brief).toBeUndefined();
    expect(notified.length).toBe(count);
    expect(store.state.activity.some((a) => a.actor === 'jarvis' && a.kind === 'brief')).toBe(true);
  });
});

describe('Jarvis araçları', () => {
  it('fikri projeye çevirir, takvime ekler, silmeyi onaya bağlar', async () => {
    const { store } = await makeStore();
    const { device, created } = fakeDevice();
    const ctx = { store, device };
    const res = (await executeTool(
      'create_project',
      { name: 'Mobil oyun', goal: 'Basit bir bulmaca oyunu yayınlamak', tasks: [{ title: 'Oyun fikrini netleştir', stage: 'fikir', priority: 1 }] },
      ctx,
    )) as { project: { id: string } };
    expect(store.state.projects[0].name).toBe('Mobil oyun');

    await executeTool('create_calendar_event', { title: 'Oyun çalışması', start: '2026-10-28T19:00' }, ctx);
    expect(created[0].title).toBe('Oyun çalışması');
    expect(new Date(created[0].end).getTime() - new Date(created[0].start).getTime()).toBe(3600_000);

    const del = (await executeTool('request_deletion', { kind: 'project', id: res.project.id }, ctx)) as { pending_id: string };
    expect(store.state.projects).toHaveLength(1); // hemen silinmez
    expect(store.state.pending).toHaveLength(1);
    await resolvePending(store, del.pending_id, true);
    expect(store.state.projects).toHaveLength(0);
    expect(store.state.tasks).toHaveLength(0);
  });

  it('takvim izni yoksa anlaşılır hata verir', async () => {
    const { store } = await makeStore();
    const { device } = fakeDevice({ cal: 'izin_yok' });
    await expect(executeTool('get_calendar', { from: '2026-10-26', to: '2026-10-27' }, { store, device })).rejects.toThrow('Takvim izni yok');
  });
});

describe('ajan döngüsü', () => {
  it('Claude araç çağırır, sonuç döner, cevap sesli metin olarak kaydedilir', async () => {
    const { store } = await makeStore();
    const { device } = fakeDevice();
    const create = vi
      .fn()
      .mockResolvedValueOnce({
        stop_reason: 'tool_use',
        content: [{ type: 'tool_use', id: 'tu1', name: 'create_task', input: { title: 'İngilizce çalış', due: '2026-10-27T20:00', recurrence: 'gunluk' } }],
      })
      .mockResolvedValueOnce({
        stop_reason: 'end_turn',
        content: [{ type: 'text', text: '**Tamam.** Yarın akşam 20:00 için İngilizce çalışmayı her güne ekledim.' }],
      });
    const client = { beta: { messages: { create } } } as never;
    const events: string[] = [];
    const r = await askJarvis('Her akşam sekizde İngilizce çalışmamı hatırlat', {
      store,
      device,
      client,
      events: [],
      alerts: [],
      onEvent: (e) => events.push(e.type === 'tool' ? e.label : e.message),
    });
    expect(r.status).toBe('ok');
    expect(r.text).toBe('Tamam. Yarın akşam 20:00 için İngilizce çalışmayı her güne ekledim.');
    expect(store.state.tasks[0].recurrence).toBe('gunluk');
    expect(events).toEqual(['Görev ekliyorum']);
    // İkinci istekte araç sonucu doğru sırayla gönderilmiş olmalı
    const second = create.mock.calls[1][0];
    expect(second.messages[1].role).toBe('assistant');
    expect(second.messages[2].content[0]).toMatchObject({ type: 'tool_result', tool_use_id: 'tu1' });
    expect(second.model).toBe('claude-opus-5-5');
    expect(second.fallbacks).toBe('default');
    expect(store.state.conversation.map((t) => t.role)).toEqual(['user', 'jarvis']);
  });

  it('bağlam gerçek verileri içerir', async () => {
    const { store } = await makeStore();
    seedIfNeeded(store);
    const snap = buildSnapshot({ state: store.state, now: NOW, events: [], alerts: [] });
    expect(snap).toContain('Orman Yangını');
    expect(snap).toContain('<simdi>26 Ekim 2026 Pazartesi 21:00');
    expect(snap).toContain('sıradaki: Akıllı stratejiyi');
  });
});

describe('uyandırma kelimesi', () => {
  it('farklı yazımları yakalar ve komutu ayırır', () => {
    expect(stripWake('Hey Jarvis bugün neler oldu')).toEqual({ woke: true, rest: 'bugün neler oldu' });
    expect(stripWake('hey çarvis, şu an öncelikli ne')).toEqual({ woke: true, rest: 'şu an öncelikli ne' });
    expect(stripWake('Carvis')).toEqual({ woke: true, rest: '' });
    expect(stripWake('servis bugün gelmedi').woke).toBe(false);
    expect(stripWake('jarvisle konuştum').woke).toBe(false);
  });
});

describe('ön plan / arka plan senkronu', () => {
  it('arka planın yazdığı daha yeni durumu alır, kaydedilmemiş yerel değişikliği ezmez', async () => {
    const p = memoryPersistence();
    const fg = new Store(p, () => NOW);
    await fg.load();
    createTask(fg, { title: 'ön plan' }, 'user');
    await fg.flush();

    // Arka plan görevi diske yazar
    await new Promise((r) => setTimeout(r, 5));
    const bg = new Store(p, () => NOW);
    await bg.load();
    bg.log('jarvis', 'notify', 'arka plan bildirimi');
    await bg.flush();
    expect(await fg.reloadIfNewer()).toBe(true);
    expect(fg.state.activity.some((a) => a.text === 'arka plan bildirimi')).toBe(true);

    // Ön planda kaydedilmemiş daha yeni değişiklik varken eski disk verisi yüklenmez
    await new Promise((r) => setTimeout(r, 5));
    createTask(fg, { title: 'kaydedilmedi' }, 'user');
    expect(await fg.reloadIfNewer()).toBe(false);
    expect(fg.state.tasks.some((t) => t.title === 'kaydedilmedi')).toBe(true);
  });
});

describe('izleyici dayanıklılığı', () => {
  it('yavaş bir servis (hava) diğer kontrolleri ve uyarıları bekletmez', async () => {
    const { store } = await makeStore();
    const { device } = fakeDevice();
    device.weather = () => new Promise(() => {}); // hiç cevap vermeyen servis
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    try {
      const p = runWatch(store, device, { claude: 'eksik' });
      await vi.advanceTimersByTimeAsync(8100);
      const r = await p;
      expect(r.health.weather).toBe('hata');
      expect(r.health.messages.weather).toContain('zaman aşımı');
      expect(r.alerts.some((a) => a.key === 'int:claude')).toBe(true);
    } finally {
      vi.useRealTimers();
    }
  });
});

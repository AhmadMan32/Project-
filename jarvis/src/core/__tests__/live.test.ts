// GERÇEK Claude API ile uçtan uca test. Yalnızca ANTHROPIC_API_KEY tanımlıysa çalışır:
//   ANTHROPIC_API_KEY=sk-ant-... npm run test:live
// Takvim/bildirim telefona ait olduğu için burada kayıt tutan test kopyalarıdır;
// yapay zekâ, araç seçimi, veri değişiklikleri ve cevaplar gerçektir.
import { describe, expect, it } from 'vitest';
import { askJarvis, makeClient } from '../agent';
import type { Device } from '../device';
import { seedIfNeeded } from '../seed';
import { memoryPersistence, Store } from '../store';
import { addDays, dayKey } from '../time';
import type { CalEvent } from '../types';
import { fetchWeather } from '../weather';

const KEY = process.env.ANTHROPIC_API_KEY;

describe.skipIf(!KEY)('canlı Claude testi', () => {
  const created: CalEvent[] = [];
  const reminders: { body: string; at?: Date }[] = [];
  const device: Device = {
    calendar: {
      status: async () => 'ok',
      list: async () => created,
      create: async (e) => {
        const ev = { id: `ev${created.length}`, title: e.title, start: e.start.toISOString(), end: e.end.toISOString(), allDay: false };
        created.push(ev);
        return ev;
      },
      remove: async () => {},
      find: async (id) => created.find((e) => e.id === id) ?? null,
    },
    notifier: {
      status: async () => 'ok',
      notify: async (_t, body, at) => {
        reminders.push({ body, at });
        return 'n';
      },
    },
    weather: (city) => fetchWeather(city), // gerçek Open-Meteo
  };
  const store = new Store(memoryPersistence());
  const client = makeClient(KEY!);
  const ask = (q: string) => askJarvis(q, { store, device, client, events: created, alerts: [], onEvent: (e) => console.log('  ⚙', e.type === 'tool' ? e.label : e.message) });

  it('takvime ekler ve tekrarlayan görev kurar', async () => {
    await store.load();
    seedIfNeeded(store);
    const r = await ask('Yarın akşam 19:00 da spor salonuna gideceğim, takvimime ekle. Bir de her akşam 21:00 de İngilizce çalışmamı görev olarak ekle.');
    console.log('JARVIS:', r.text);
    expect(r.status).toBe('ok');
    const tomorrow = dayKey(addDays(new Date(), 1));
    expect(created.some((e) => /spor/i.test(e.title) && dayKey(new Date(e.start)) === tomorrow && new Date(e.start).getHours() === 19)).toBe(true);
    const eng = store.state.tasks.find((t) => /ingilizce/i.test(t.title));
    expect(eng?.recurrence).toBeTruthy();
    expect(eng?.due?.endsWith('T21:00')).toBe(true);
    expect(r.text).not.toMatch(/\*\*|^#|^- /m);
  }, 180_000);

  it('önceliği gerçek verilere göre söyler', async () => {
    const r = await ask('Şu an öncelikli olan ne?');
    console.log('JARVIS:', r.text);
    expect(r.status).toBe('ok');
    expect(r.text.length).toBeGreaterThan(20);
  }, 180_000);

  it('silmeyi doğrudan yapmaz, onaya gönderir', async () => {
    const r = await ask('TÜBİTAK projesini tamamen sil.');
    console.log('JARVIS:', r.text);
    expect(store.state.projects.some((p) => p.name.includes('TÜBİTAK'))).toBe(true);
    expect(store.state.pending.some((p) => p.kind === 'delete_project')).toBe(true);
  }, 180_000);

  it('bugün ne yaptığını anlatır', async () => {
    const r = await ask('Hey Jarvis, bugün neler yaptın?');
    console.log('JARVIS:', r.text);
    expect(r.status).toBe('ok');
    expect(r.text).toMatch(/spor|İngilizce|takvim/i);
  }, 180_000);
});

import type { Activity, Actor, State } from './types';

// Kalıcı depolama soyutlaması: uygulamada expo-sqlite, testlerde bellek.
export interface Persistence {
  load(): Promise<string | null>;
  save(json: string): Promise<void>;
}

export function memoryPersistence(initial: string | null = null): Persistence & { data: string | null } {
  const p = {
    data: initial,
    async load() {
      return p.data;
    },
    async save(json: string) {
      p.data = json;
    },
  };
  return p;
}

export function emptyState(): State {
  return {
    version: 1,
    profile: { name: '', address: '', city: 'İstanbul', briefTime: '08:30', rules: '' },
    voice: { rate: 1.0, pitch: 0.95, wakeWord: false },
    projects: [],
    tasks: [],
    notes: [],
    memories: [],
    activity: [],
    pending: [],
    conversation: [],
    meta: { seeded: false, notified: {} },
  };
}

const MAX_ACTIVITY = 400;
const MAX_TURNS = 40;

let counter = 0;
export function newId(prefix: string): string {
  counter = (counter + 1) % 1000;
  return `${prefix}_${Date.now().toString(36)}${counter.toString(36)}${Math.random().toString(36).slice(2, 5)}`;
}

type Listener = (s: State) => void;

export class Store {
  state: State = emptyState();
  private listeners = new Set<Listener>();
  private saveTimer: ReturnType<typeof setTimeout> | null = null;
  private loaded = false;

  constructor(private persistence: Persistence, private clock: () => Date = () => new Date()) {}

  now(): Date {
    return this.clock();
  }

  async load(): Promise<void> {
    const parsed = parse(await this.persistence.load());
    if (parsed) this.state = parsed;
    this.loaded = true;
    this.emit();
  }

  // Uygulama ön plana dönünce: arka plan görevi diske daha yeni bir durum
  // yazdıysa onu al; bellekte henüz kaydedilmemiş daha yeni değişiklik varsa koru.
  async reloadIfNewer(): Promise<boolean> {
    const parsed = parse(await this.persistence.load());
    if (!parsed || (parsed.meta.changedAt ?? 0) <= (this.state.meta.changedAt ?? 0)) return false;
    this.state = parsed;
    this.emit();
    return true;
  }

  subscribe(fn: Listener): () => void {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  // Tüm değişiklikler buradan geçer: kopyala, değiştir, kaydet, bildir.
  update(mutator: (s: State) => void): void {
    const next = structuredCloneSafe(this.state);
    mutator(next);
    next.meta.changedAt = Math.max(Date.now(), (this.state.meta.changedAt ?? 0) + 1);
    if (next.activity.length > MAX_ACTIVITY) next.activity = next.activity.slice(-MAX_ACTIVITY);
    if (next.conversation.length > MAX_TURNS) next.conversation = next.conversation.slice(-MAX_TURNS);
    this.state = next;
    this.scheduleSave();
    this.emit();
  }

  log(actor: Actor, kind: string, text: string, ref?: string): Activity {
    const a: Activity = { id: newId('act'), at: this.now().toISOString(), actor, kind, text, ref };
    this.update((s) => {
      s.activity.push(a);
    });
    return a;
  }

  async flush(): Promise<void> {
    if (this.saveTimer) {
      clearTimeout(this.saveTimer);
      this.saveTimer = null;
    }
    if (this.loaded) await this.persistence.save(JSON.stringify(this.state));
  }

  private scheduleSave() {
    if (!this.loaded) return;
    if (this.saveTimer) clearTimeout(this.saveTimer);
    this.saveTimer = setTimeout(() => {
      this.saveTimer = null;
      void this.persistence.save(JSON.stringify(this.state));
    }, 250);
  }

  private emit() {
    for (const l of this.listeners) l(this.state);
  }
}

function parse(raw: string | null): State | null {
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as Partial<State>;
    const base = emptyState();
    return {
      ...base,
      ...parsed,
      profile: { ...base.profile, ...parsed.profile },
      voice: { ...base.voice, ...parsed.voice },
      meta: { ...base.meta, ...parsed.meta },
    } as State;
  } catch {
    // Bozuk kayıt: boş durumla devam et.
    return null;
  }
}

function structuredCloneSafe<T>(v: T): T {
  return typeof structuredClone === 'function' ? structuredClone(v) : JSON.parse(JSON.stringify(v));
}

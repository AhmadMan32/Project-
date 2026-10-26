// Jarvis veri modeli. Kişisel düzen (görevler, alışkanlıklar, takvim) ve
// birden çok proje (fikirden teslime) aynı yapıda takip edilir.

export type Stage = 'fikir' | 'plan' | 'yapim' | 'kontrol' | 'teslim';
export const STAGES: Stage[] = ['fikir', 'plan', 'yapim', 'kontrol', 'teslim'];
export const STAGE_LABEL: Record<Stage, string> = {
  fikir: 'Fikir',
  plan: 'Planlama',
  yapim: 'Yapım',
  kontrol: 'Kontrol',
  teslim: 'Teslim',
};

export type ProjectStatus = 'aktif' | 'beklemede' | 'tamamlandi' | 'iptal';
export type TaskStatus = 'acik' | 'devam' | 'bekliyor' | 'tamam';
export type Recurrence = 'gunluk' | 'hafta_ici' | 'haftalik';
export type Actor = 'jarvis' | 'user' | 'system';

export interface Project {
  id: string;
  name: string;
  goal: string;
  stage: Stage;
  status: ProjectStatus;
  deadline?: string; // YYYY-MM-DD
  createdAt: string;
  updatedAt: string;
  lastProgressAt: string;
}

export interface Task {
  id: string;
  title: string;
  projectId?: string;
  stage?: Stage; // projenin hangi aşamasına ait
  priority: 1 | 2 | 3; // 1 = yüksek
  due?: string; // YYYY-MM-DD veya YYYY-MM-DDTHH:mm
  status: TaskStatus;
  notes?: string;
  recurrence?: Recurrence;
  createdAt: string;
  doneAt?: string;
  order: number;
}

export interface Note {
  id: string;
  title?: string;
  text: string;
  projectId?: string;
  createdAt: string;
}

export interface Memory {
  id: string;
  kind: 'talimat' | 'bilgi' | 'tercih';
  text: string;
  createdAt: string;
}

export interface Activity {
  id: string;
  at: string;
  actor: Actor;
  kind: string;
  text: string;
  ref?: string;
}

export type AlertLevel = 'kritik' | 'uyari' | 'bilgi';
export interface Alert {
  key: string; // tekilleştirme anahtarı
  level: AlertLevel;
  text: string;
  fix?: string; // kullanıcı ne yapmalı
  ref?: string;
}

export type PendingKind = 'delete_task' | 'delete_project' | 'delete_note' | 'delete_event';
export interface PendingAction {
  id: string;
  at: string;
  kind: PendingKind;
  targetId: string;
  summary: string;
  reason?: string;
}

export interface Turn {
  at: string;
  role: 'user' | 'jarvis';
  text: string;
}

export interface Profile {
  name: string; // "Ahmet"
  address: string; // nasıl hitap edilsin: "Ahmet Bey", "efendim" ...
  city: string;
  briefTime: string; // "08:30"
  rules: string; // serbest metin: öncelikler, çalışma şekli, kurallar
}

export interface VoiceSettings {
  deviceVoice?: string; // expo-speech identifier
  rate: number;
  pitch: number;
  wakeWord: boolean; // "Hey Jarvis" ile ön planda sürekli dinleme
  elevenVoiceId?: string;
}

export interface Meta {
  seeded: boolean;
  lastWatchAt?: string;
  lastBriefDate?: string;
  lastBrief?: string;
  notified: Record<string, string>; // alert key -> tarih (tekrar bildirim engeli)
  changedAt?: number; // son değişiklik (ön plan / arka plan senkronu için)
}

export interface State {
  version: 1;
  profile: Profile;
  voice: VoiceSettings;
  projects: Project[];
  tasks: Task[];
  notes: Note[];
  memories: Memory[];
  activity: Activity[];
  pending: PendingAction[];
  conversation: Turn[];
  meta: Meta;
}

export interface CalEvent {
  id: string;
  title: string;
  start: string; // ISO
  end: string; // ISO
  allDay: boolean;
  location?: string;
  notes?: string;
}

export interface Weather {
  city: string;
  now: { temp: number; code: number; text: string; wind: number; humidity: number };
  days: { date: string; min: number; max: number; code: number; text: string; rainChance: number }[];
}

export type IntegrationStatus = 'ok' | 'eksik' | 'izin_yok' | 'hata' | 'kullanilamaz';
export interface Health {
  claude: IntegrationStatus;
  calendar: IntegrationStatus;
  notifications: IntegrationStatus;
  microphone: IntegrationStatus;
  weather: IntegrationStatus;
  voice: 'premium' | 'cihaz';
  messages: Partial<Record<'claude' | 'calendar' | 'notifications' | 'microphone' | 'weather', string>>;
}

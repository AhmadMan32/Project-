// Uygulamanın çalışma zamanı: veri deposu, izleyici döngüsü, konuşma akışı
// (dinle → anla → veriye bak → aksiyon al → cevapla → sesli oku) ve orb durumu.

import { createContext, ReactNode, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { AppState } from 'react-native';
import { SharedValue, useSharedValue } from 'react-native-reanimated';
import { askJarvis, makeClient, testClaude } from '../core/agent';
import { resolvePending } from '../core/ops';
import { seedIfNeeded } from '../core/seed';
import { Store } from '../core/store';
import type { Health, IntegrationStatus, State } from '../core/types';
import { runWatch, WatchResult } from '../core/watcher';
import { registerBackground } from '../services/background';
import { calendarPort } from '../services/calendar';
import { device } from '../services/device';
import { microphoneStatus, requestMicrophone, useListener } from '../services/listen';
import { notifierPort } from '../services/notify';
import { persistence, secrets } from '../services/persist';
import { bestTurkishVoice, speak, stopSpeaking, testEleven } from '../services/speak';
import type { OrbMode } from '../ui/Orb';

export const store = new Store(persistence);

const EMPTY_HEALTH: Health = {
  claude: 'eksik',
  calendar: 'eksik',
  notifications: 'eksik',
  microphone: 'eksik',
  weather: 'eksik',
  voice: 'cihaz',
  messages: {},
};

interface Ctx {
  ready: boolean;
  checked: boolean;
  state: State;
  watch: WatchResult;
  mode: OrbMode;
  level: SharedValue<number>;
  partial: string;
  heard: string;
  reply: string;
  working: string;
  wakeActive: boolean;
  keys: { anthropic: boolean; eleven: boolean };
  ask(text: string): Promise<void>;
  talk(): void;
  stop(): void;
  say(text: string): void;
  refresh(): Promise<void>;
  setKey(k: 'anthropic' | 'elevenlabs', v: string | null): Promise<{ ok: boolean; message: string }>;
  grant(kind: 'calendar' | 'notifications' | 'microphone'): Promise<IntegrationStatus>;
  decide(pendingId: string, approve: boolean): Promise<void>;
}

const JarvisCtx = createContext<Ctx | null>(null);

export function useJarvis(): Ctx {
  const c = useContext(JarvisCtx);
  if (!c) throw new Error('JarvisProvider eksik');
  return c;
}

// İzleyiciyi yalnızca iş verisi değiştiğinde yeniden çalıştırmak için imza
const signature = (s: State) =>
  JSON.stringify([s.tasks.map((t) => [t.id, t.status, t.due]), s.projects.map((p) => [p.id, p.stage, p.status, p.deadline]), s.pending.length, s.profile]);

export function JarvisProvider({ children }: { children: ReactNode }) {
  const [ready, setReady] = useState(false);
  const [state, setState] = useState<State>(store.state);
  const [watch, setWatch] = useState<WatchResult>({ alerts: [], events: [], weather: null, health: EMPTY_HEALTH });
  const [checked, setChecked] = useState(false); // ilk izleme turu bitti mi
  const [mode, setMode] = useState<OrbMode>('idle');
  const [heard, setHeard] = useState('');
  const [reply, setReply] = useState('');
  const [working, setWorking] = useState('');
  const [keys, setKeys] = useState({ anthropic: false, eleven: false });
  const keyCache = useRef<{ anthropic: string | null; eleven: string | null }>({ anthropic: null, eleven: null });
  const claudeStatus = useRef<IntegrationStatus>('eksik');
  const voiceKind = useRef<'premium' | 'cihaz'>('cihaz');
  const micStatus = useRef<IntegrationStatus>('eksik');
  const level = useSharedValue(0);
  const busy = useRef(false);
  const lastSig = useRef('');
  const watching = useRef(false);
  const again = useRef(false);
  const autoVoice = useRef<string | undefined>(undefined);

  // İzleyici çalışırken yeni bir tazeleme istenirse bitince bir tur daha döner
  const refresh = useCallback(async () => {
    if (watching.current) {
      again.current = true;
      return;
    }
    watching.current = true;
    try {
      do {
        again.current = false;
        micStatus.current = await microphoneStatus();
        const r = await runWatch(store, device, { claude: claudeStatus.current, microphone: micStatus.current, voice: voiceKind.current });
        lastSig.current = signature(store.state);
        setWatch(r);
        setChecked(true);
      } while (again.current);
    } finally {
      watching.current = false;
    }
  }, []);

  // Açılış: veriyi yükle, anahtarları oku, ilk kurulum, izinler, arka plan
  useEffect(() => {
    const unsub = store.subscribe(setState);
    (async () => {
      await store.load();
      keyCache.current = { anthropic: await secrets.get('anthropic'), eleven: await secrets.get('elevenlabs') };
      setKeys({ anthropic: !!keyCache.current.anthropic, eleven: !!keyCache.current.eleven });
      claudeStatus.current = keyCache.current.anthropic ? 'ok' : 'eksik';
      voiceKind.current = keyCache.current.eleven ? 'premium' : 'cihaz';
      const firstRun = seedIfNeeded(store);
      if (firstRun) {
        await notifierPort.request().catch(() => null);
        await calendarPort.request().catch(() => null);
      }
      registerBackground().catch(() => false);
      bestTurkishVoice().then((v) => {
        autoVoice.current = v?.identifier;
      });
      setReady(true);
      await refresh();
    })();
    return () => {
      unsub();
    };
  }, [refresh]);

  // Ön planda periyodik izleme + uygulamaya dönüşte tazeleme
  useEffect(() => {
    if (!ready) return;
    const iv = setInterval(() => void refresh(), 5 * 60_000);
    const sub = AppState.addEventListener('change', async (st) => {
      if (st === 'active') {
        await store.reloadIfNewer(); // arka plan görevi veriyi güncellemiş olabilir
        await refresh();
      } else {
        await store.flush();
      }
    });
    return () => {
      clearInterval(iv);
      sub.remove();
    };
  }, [ready, refresh]);

  // Veri değişince (görev bitti, proje eklendi...) paneller ve uyarılar güncellensin
  useEffect(() => {
    if (!ready) return;
    const sig = signature(state);
    if (sig === lastSig.current) return;
    const t = setTimeout(() => void refresh(), 800);
    return () => clearTimeout(t);
  }, [state, ready, refresh]);

  const sayRef = useRef<(t: string) => void>(() => {});
  const say = useCallback((text: string) => {
    const v = store.state.voice;
    setReply(text);
    let started = false;
    // Ses motoru hiç başlamazsa (TTS yok / sessiz hata) orb "düşünüyor"da kalmasın
    const watchdog = setTimeout(() => {
      if (!started) setMode((m) => (m === 'thinking' ? 'idle' : m));
    }, 6000);
    speak(
      text,
      { deviceVoice: v.deviceVoice ?? autoVoice.current, rate: v.rate, pitch: v.pitch, elevenKey: keyCache.current.eleven, elevenVoiceId: v.elevenVoiceId },
      {
        onStart: () => {
          started = true;
          clearTimeout(watchdog);
          setMode('speaking');
        },
        onDone: () => {
          clearTimeout(watchdog);
          level.set(0);
          setMode('idle');
        },
        onLevel: (x) => {
          level.set(x);
        },
        onError: (m) => {
          voiceKind.current = 'cihaz';
          store.log('system', 'voice_error', m);
        },
      },
    ).then((kind) => {
      voiceKind.current = kind;
    });
  }, [level]);
  useEffect(() => {
    sayRef.current = say;
  }, [say]);

  const ask = useCallback(
    async (text: string) => {
      const q = text.trim();
      if (!q || busy.current) return;
      busy.current = true;
      stopSpeaking();
      setHeard(q);
      setReply('');
      setMode('thinking');
      setWorking('Düşünüyorum');
      try {
        if (!keyCache.current.anthropic) {
          sayRef.current('Yapay zekâ bağlantım henüz kurulmadı. Ayarlar bölümünden Claude API anahtarını girersen hemen başlayabiliriz.');
          return;
        }
        const r = await askJarvis(q, {
          store,
          device,
          client: makeClient(keyCache.current.anthropic),
          events: watch.events,
          alerts: watch.alerts,
          onEvent: (e) => setWorking(e.type === 'tool' ? e.label : 'Bir adım başarısız oldu, devam ediyorum'),
        });
        claudeStatus.current = r.status;
        sayRef.current(r.text);
      } finally {
        busy.current = false;
        setWorking('');
        void refresh();
      }
    },
    [watch.events, watch.alerts, refresh],
  );

  const listener = useListener({
    onCommand: (t) => void ask(t),
    onLevel: (v) => {
      level.set(v);
    },
    onError: (m) => {
      setMode('idle');
      setReply(m);
    },
    wakeEnabled: ready && state.voice.wakeWord,
    paused: mode === 'thinking' || mode === 'speaking',
  });

  // Dinleme durumu konuşma tanıma motorundan gelir; orb modu ondan türetilir
  const orbMode: OrbMode = listener.listening && mode === 'idle' ? 'listening' : mode;

  const talk = useCallback(() => {
    if (mode === 'speaking') {
      stopSpeaking();
      setMode('idle');
      return;
    }
    if (listener.listening) {
      listener.stop();
      return;
    }
    if (mode === 'thinking') return;
    setReply('');
    void listener.listen();
  }, [mode, listener]);

  const stop = useCallback(() => {
    stopSpeaking();
    listener.stop();
    level.set(0);
    setMode('idle');
  }, [listener, level]);

  const setKey = useCallback(
    async (k: 'anthropic' | 'elevenlabs', v: string | null) => {
      const val = v?.trim() || null;
      let result = { ok: true, message: 'Kaldırıldı.' };
      if (val) {
        result = k === 'anthropic' ? await testClaude(makeClient(val)) : await testEleven(val, store.state.voice.elevenVoiceId);
        if (!result.ok) return result;
      }
      await secrets.set(k, val);
      if (k === 'anthropic') {
        keyCache.current.anthropic = val;
        claudeStatus.current = val ? 'ok' : 'eksik';
      } else {
        keyCache.current.eleven = val;
        voiceKind.current = val ? 'premium' : 'cihaz';
      }
      setKeys({ anthropic: !!keyCache.current.anthropic, eleven: !!keyCache.current.eleven });
      store.log('user', 'settings', k === 'anthropic' ? (val ? 'Claude bağlantısı kuruldu' : 'Claude anahtarı kaldırıldı') : val ? 'Premium ses bağlandı' : 'Premium ses kaldırıldı');
      void refresh();
      return result;
    },
    [refresh],
  );

  const grant = useCallback(
    async (kind: 'calendar' | 'notifications' | 'microphone') => {
      const r = kind === 'calendar' ? await calendarPort.request() : kind === 'notifications' ? await notifierPort.request() : await requestMicrophone();
      await refresh();
      return r;
    },
    [refresh],
  );

  const decide = useCallback(
    async (id: string, approve: boolean) => {
      try {
        await resolvePending(store, id, approve, (eid) => calendarPort.remove(eid));
      } catch (e) {
        setReply(`İşlem yapılamadı: ${e instanceof Error ? e.message : e}`);
      }
      void refresh();
    },
    [refresh],
  );

  const value = useMemo<Ctx>(
    () => ({
      ready,
      checked,
      state,
      watch,
      mode: orbMode,
      level,
      partial: listener.partial,
      heard,
      reply,
      working,
      wakeActive: listener.wakeActive,
      keys,
      ask,
      talk,
      stop,
      say,
      refresh,
      setKey,
      grant,
      decide,
    }),
    [ready, checked, state, watch, orbMode, level, listener.partial, listener.wakeActive, heard, reply, working, keys, ask, talk, stop, say, refresh, setKey, grant, decide],
  );

  return <JarvisCtx.Provider value={value}>{children}</JarvisCtx.Provider>;
}

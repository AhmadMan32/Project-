// Türkçe konuşma tanıma. Mikrofon seviyesi orb'a canlı olarak aktarılır.
// "Hey Jarvis" modu açıkken uygulama ön plandayken sürekli dinler ve
// uyandırma kelimesini duyunca komutu alır.

import { ExpoSpeechRecognitionModule, useSpeechRecognitionEvent } from 'expo-speech-recognition';
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { IntegrationStatus } from '../core/types';
import { stripWake } from '../core/wake';

const ERRORS: Record<string, string> = {
  'not-allowed': 'Mikrofon veya konuşma tanıma izni yok. Ayarlar bölümünden izin verebilirsin.',
  'no-speech': 'Seni duyamadım.',
  network: 'Konuşma tanıma için internet bağlantısı gerekiyor.',
  'audio-capture': 'Mikrofona erişilemiyor.',
  'service-not-allowed': 'Bu cihazda konuşma tanıma servisi kapalı. Google uygulaması / Siri ayarlarını kontrol et.',
  'language-not-supported': 'Bu cihazda Türkçe konuşma tanıma paketi yok. Ayarlardan Türkçe dil paketini indir.',
  busy: 'Mikrofon şu an başka bir uygulama tarafından kullanılıyor.',
};

type Mode = 'off' | 'command' | 'wake';

export function useListener(opts: {
  onCommand: (text: string) => void;
  onLevel: (v: number) => void;
  onError: (message: string) => void;
  wakeEnabled: boolean;
  paused: boolean; // Jarvis düşünürken/konuşurken dinleme
}) {
  const [listening, setListening] = useState(false);
  const [partial, setPartial] = useState('');
  const mode = useRef<Mode>('off');
  // Bir oturum durdurulup hemen yenisi başlatıldığında eski oturumun geç gelen
  // "end" olayı yeni oturumu kapatmasın diye
  const starting = useRef(false);
  const [activeMode, setActiveMode] = useState<Mode>('off');
  const optsRef = useRef(opts);
  useLayoutEffect(() => {
    optsRef.current = opts;
  });

  const startRecognition = useCallback(async (m: Mode) => {
    const perm = await ExpoSpeechRecognitionModule.requestPermissionsAsync();
    if (!perm.granted) {
      optsRef.current.onError(ERRORS['not-allowed']);
      return false;
    }
    mode.current = m;
    starting.current = true;
    setPartial('');
    try {
      ExpoSpeechRecognitionModule.start({
        lang: 'tr-TR',
        interimResults: true,
        continuous: m === 'wake',
        addsPunctuation: true,
        volumeChangeEventOptions: { enabled: true, intervalMillis: 70 },
        androidIntentOptions: { EXTRA_LANGUAGE_MODEL: 'web_search' },
        contextualStrings: ['Jarvis', 'Hey Jarvis'],
      });
    } catch (e) {
      starting.current = false;
      mode.current = 'off';
      optsRef.current.onError(`Konuşma tanıma başlatılamadı: ${e instanceof Error ? e.message : e}`);
      return false;
    }
    return true;
  }, []);

  const listen = useCallback(async () => {
    if (mode.current !== 'off') ExpoSpeechRecognitionModule.abort();
    await startRecognition('command');
  }, [startRecognition]);

  const stop = useCallback(() => {
    if (mode.current === 'command') ExpoSpeechRecognitionModule.stop();
    else if (mode.current !== 'off') ExpoSpeechRecognitionModule.abort();
  }, []);

  useSpeechRecognitionEvent('start', () => {
    starting.current = false;
    setActiveMode(mode.current);
    setListening(true);
  });
  useSpeechRecognitionEvent('end', () => {
    if (starting.current) return;
    setListening(false);
    setActiveMode('off');
    optsRef.current.onLevel(0);
    mode.current = 'off';
  });
  useSpeechRecognitionEvent('volumechange', (e) => {
    if (mode.current === 'command') optsRef.current.onLevel(Math.max(0, Math.min(1, (e.value + 2) / 12)));
  });
  useSpeechRecognitionEvent('result', (e) => {
    const text = e.results[0]?.transcript ?? '';
    if (mode.current === 'command') {
      setPartial(text);
      if (e.isFinal && text.trim()) {
        setPartial('');
        optsRef.current.onCommand(stripWake(text).rest || text);
      }
    } else if (mode.current === 'wake' && e.isFinal) {
      const { woke, rest } = stripWake(text);
      if (!woke) return;
      ExpoSpeechRecognitionModule.abort();
      if (rest.length > 3) optsRef.current.onCommand(rest);
      else setTimeout(() => void startRecognition('command'), 250);
    }
  });
  useSpeechRecognitionEvent('error', (e) => {
    const wasWake = mode.current === 'wake';
    if (!starting.current) {
      // hata durumunda motor "end" göndermeyebilir
      setListening(false);
      setActiveMode('off');
      optsRef.current.onLevel(0);
      mode.current = 'off';
    }
    if (wasWake && (e.error === 'no-speech' || e.error === 'aborted' || e.error === 'speech-timeout')) return;
    if (e.error === 'aborted') return;
    optsRef.current.onError(ERRORS[e.error] ?? `Konuşma tanıma hatası: ${e.message || e.error}`);
  });

  // "Hey Jarvis" modu: boştayken sürekli dinlemeyi yeniden başlat
  useEffect(() => {
    if (!opts.wakeEnabled || opts.paused || listening) return;
    const t = setTimeout(() => {
      if (mode.current === 'off') void startRecognition('wake');
    }, 600);
    return () => clearTimeout(t);
  }, [opts.wakeEnabled, opts.paused, listening, startRecognition]);

  useEffect(() => {
    if (opts.paused && mode.current === 'wake') ExpoSpeechRecognitionModule.abort();
  }, [opts.paused]);

  return { listening: listening && activeMode === 'command', wakeActive: listening && activeMode === 'wake', partial, listen, stop };
}

export async function microphoneStatus(): Promise<IntegrationStatus> {
  try {
    if (!ExpoSpeechRecognitionModule.isRecognitionAvailable()) return 'kullanilamaz';
    const p = await ExpoSpeechRecognitionModule.getPermissionsAsync();
    if (p.granted) return 'ok';
    return p.canAskAgain ? 'eksik' : 'izin_yok';
  } catch {
    return 'kullanilamaz';
  }
}

export async function requestMicrophone(): Promise<IntegrationStatus> {
  const p = await ExpoSpeechRecognitionModule.requestPermissionsAsync();
  return p.granted ? 'ok' : 'izin_yok';
}

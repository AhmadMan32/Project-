// Jarvis'in sesi. ElevenLabs anahtarı girilmişse premium çok dilli ses,
// yoksa cihazın en kaliteli Türkçe sesi kullanılır. Konuşma animasyonu
// sabit süreye değil gerçek sesin başlangıç/bitiş olaylarına bağlıdır.

import { createAudioPlayer, setAudioModeAsync, type AudioPlayer } from 'expo-audio';
import * as Speech from 'expo-speech';
import { File, Paths } from 'expo-file-system';
import { Platform } from 'react-native';

export interface SpeakHandlers {
  onStart: () => void;
  onDone: () => void;
  onLevel: (v: number) => void; // 0..1
  onError?: (message: string) => void;
}

export interface VoiceConfig {
  deviceVoice?: string;
  rate: number;
  pitch: number;
  elevenKey?: string | null;
  elevenVoiceId?: string;
}

// ElevenLabs hazır sesi "Daniel": sakin, derin, Jarvis karakterine yakın; çok dilli modelle Türkçe konuşur.
export const DEFAULT_ELEVEN_VOICE = 'onwK4e9ZLuTAKqWW03F9';
const ELEVEN_MODEL = 'eleven_multilingual_v2';

let player: AudioPlayer | null = null;
let stopFlag = 0;

// Orb'un konuşma ritmi. Gerçek veri (ses örneği / kelime sınırı) geldikçe onu
// kullanır; gelmiyorsa ses çaldığı sürece hece ritmine benzer bir dalga üretir.
function startRhythm(onLevel: (v: number) => void) {
  let level = 0;
  let lastReal = 0;
  let t = 0;
  const iv = setInterval(() => {
    t += 0.05;
    if (Date.now() - lastReal > 350) {
      const syllable = Math.abs(Math.sin(t * 7.3)) * (0.55 + 0.45 * Math.abs(Math.sin(t * 1.9)));
      const pause = Math.sin(t * 0.83) > 0.92 ? 0.25 : 1; // cümle arası kısa duraksama hissi
      level = 0.18 + 0.55 * syllable * pause;
    } else level *= 0.85;
    onLevel(level);
  }, 50);
  return {
    pulse(v: number) {
      lastReal = Date.now();
      level = Math.max(level, v);
      onLevel(level);
    },
    stop() {
      clearInterval(iv);
      onLevel(0);
    },
  };
}

export async function bestTurkishVoice(): Promise<Speech.Voice | undefined> {
  try {
    const voices = await Speech.getAvailableVoicesAsync();
    const tr = voices.filter((v) => v.language?.toLowerCase().startsWith('tr'));
    return (
      tr.find((v) => v.quality === Speech.VoiceQuality.Enhanced && /male|erkek|cem|tolga/i.test(v.name)) ??
      tr.find((v) => v.quality === Speech.VoiceQuality.Enhanced) ??
      tr[0]
    );
  } catch {
    return undefined;
  }
}

export async function listTurkishVoices(): Promise<Speech.Voice[]> {
  try {
    return (await Speech.getAvailableVoicesAsync()).filter((v) => v.language?.toLowerCase().startsWith('tr'));
  } catch {
    return [];
  }
}

export function stopSpeaking() {
  stopFlag++;
  Speech.stop();
  if (player) {
    try {
      player.pause();
      player.remove();
    } catch {
      /* zaten kapalı */
    }
    player = null;
  }
}

export async function speak(text: string, cfg: VoiceConfig, h: SpeakHandlers): Promise<'premium' | 'cihaz'> {
  stopSpeaking();
  const token = stopFlag;
  if (Platform.OS !== 'web') {
    try {
      // iPhone sessiz moddayken de Jarvis duyulabilsin
      await setAudioModeAsync({ playsInSilentMode: true });
    } catch {
      /* yoksay */
    }
  }
  if (cfg.elevenKey && Platform.OS !== 'web') {
    try {
      await speakEleven(text, cfg, h, token);
      return 'premium';
    } catch (e) {
      if (token !== stopFlag) return 'premium';
      h.onError?.(`Premium ses kullanılamadı (${e instanceof Error ? e.message : e}); cihaz sesine geçtim.`);
    }
  }
  await speakDevice(text, cfg, h, token);
  return 'cihaz';
}

function speakDevice(text: string, cfg: VoiceConfig, h: SpeakHandlers, token: number): Promise<void> {
  return new Promise((resolve) => {
    let finished = false;
    let rhythm: ReturnType<typeof startRhythm> | null = null;
    const finish = () => {
      if (finished) return;
      finished = true;
      rhythm?.stop();
      h.onDone();
      resolve();
    };
    Speech.speak(text, {
      language: 'tr-TR',
      voice: cfg.deviceVoice,
      rate: cfg.rate,
      pitch: cfg.pitch,
      onStart: () => {
        if (token !== stopFlag) return;
        h.onStart();
        rhythm = startRhythm(h.onLevel);
      },
      // Kelime sınırı olayı iOS'ta gelir; Android'de ritim dalgası devreye girer
      onBoundary: () => rhythm?.pulse(0.6 + Math.random() * 0.4),
      onDone: finish,
      onStopped: finish,
      onError: (e) => {
        h.onError?.(`Ses çıkışı hatası: ${e.message}`);
        finish();
      },
    });
  });
}

async function speakEleven(text: string, cfg: VoiceConfig, h: SpeakHandlers, token: number): Promise<void> {
  const voiceId = cfg.elevenVoiceId?.trim() || DEFAULT_ELEVEN_VOICE;
  const res = await fetch(`https://api.elevenlabs.io/v1/text-to-speech/${voiceId}?output_format=mp3_44100_128`, {
    method: 'POST',
    headers: { 'xi-api-key': cfg.elevenKey!, 'Content-Type': 'application/json', Accept: 'audio/mpeg' },
    body: JSON.stringify({
      text,
      model_id: ELEVEN_MODEL,
      language_code: 'tr',
      voice_settings: { stability: 0.55, similarity_boost: 0.8, style: 0.15, use_speaker_boost: true },
    }),
  });
  if (!res.ok) {
    const body = await res.text().catch(() => '');
    throw new Error(res.status === 401 ? 'anahtar geçersiz' : `HTTP ${res.status} ${body.slice(0, 120)}`);
  }
  const bytes = new Uint8Array(await res.arrayBuffer());
  if (token !== stopFlag) return;
  const file = new File(Paths.cache, `jarvis-tts-${Date.now()}.mp3`);
  file.write(bytes);

  return new Promise((resolve) => {
    const p = createAudioPlayer({ uri: file.uri });
    player = p;
    let started = false;
    let done = false;
    let rhythm: ReturnType<typeof startRhythm> | null = null;
    const finish = () => {
      if (done) return;
      done = true;
      rhythm?.stop();
      h.onDone();
      try {
        p.remove();
      } catch {
        /* yoksay */
      }
      if (player === p) player = null;
      try {
        file.delete();
      } catch {
        /* yoksay */
      }
      resolve();
    };
    p.addListener('playbackStatusUpdate', (s) => {
      if (token !== stopFlag) return finish();
      if (s.playing && !started) {
        started = true;
        h.onStart();
        rhythm = startRhythm(h.onLevel);
      }
      if (s.didJustFinish) finish();
    });
    try {
      p.setAudioSamplingEnabled(true);
      p.addListener('audioSampleUpdate', (sample) => {
        const frames = sample.channels[0]?.frames ?? [];
        if (!frames.length) return;
        let sum = 0;
        for (let i = 0; i < frames.length; i += 4) sum += frames[i] * frames[i];
        const rms = Math.sqrt(sum / Math.ceil(frames.length / 4));
        rhythm?.pulse(Math.min(1, rms * 3.2));
      });
    } catch {
      // Örnekleme desteklenmiyorsa orb yine oynatma süresince konuşma modunda kalır
    }
    p.play();
  });
}

// Ayarlardaki "Sesi test et" için: anahtar gerçekten çalışıyor mu?
export async function testEleven(key: string, voiceId?: string): Promise<{ ok: boolean; message: string }> {
  try {
    const res = await fetch(`https://api.elevenlabs.io/v1/voices/${voiceId?.trim() || DEFAULT_ELEVEN_VOICE}`, {
      headers: { 'xi-api-key': key },
    });
    if (res.status === 401) return { ok: false, message: 'ElevenLabs anahtarı geçersiz.' };
    if (!res.ok) return { ok: false, message: `ElevenLabs hata kodu ${res.status}.` };
    const j = (await res.json()) as { name?: string };
    return { ok: true, message: `Bağlandı. Ses: ${j.name ?? voiceId}` };
  } catch (e) {
    return { ok: false, message: `ElevenLabs'a ulaşılamadı: ${e instanceof Error ? e.message : e}` };
  }
}

// Ayarlar: yalnızca Jarvis'in işleyişini değiştiren şeyler — kim olduğun,
// kuralların, hafıza, bağlantılar ve ses.
import { useEffect, useState } from 'react';
import { Platform, Switch, Text, View } from 'react-native';
import { forget } from '../core/ops';
import type { IntegrationStatus } from '../core/types';
import { store, useJarvis } from '../runtime/JarvisProvider';
import { DEFAULT_ELEVEN_VOICE, listTurkishVoices } from '../services/speak';
import { Btn, Chip, Dot, Empty, Field, Glass } from '../ui/kit';
import { C, font } from '../ui/theme';

const STATUS_TEXT: Record<IntegrationStatus, [string, string]> = {
  ok: ['Bağlı', C.green],
  eksik: ['Kurulmadı', C.amber],
  izin_yok: ['İzin reddedildi – telefon ayarlarından aç', C.red],
  hata: ['Hata', C.red],
  kullanilamaz: ['Bu cihazda yok', C.muted],
};

export default function Settings() {
  const j = useJarvis();
  const st = j.state;
  const [profile, setProfile] = useState(st.profile);
  const [saved, setSaved] = useState(false);
  const [claudeKey, setClaudeKey] = useState('');
  const [elevenKey, setElevenKey] = useState('');
  const [elevenVoice, setElevenVoice] = useState(st.voice.elevenVoiceId ?? '');
  const [msg, setMsg] = useState<{ claude?: string; eleven?: string }>({});
  const [busy, setBusy] = useState<string | null>(null);
  const [voices, setVoices] = useState<{ identifier: string; name: string; quality: string }[]>([]);

  useEffect(() => {
    if (Platform.OS !== 'web') void listTurkishVoices().then((v) => setVoices(v.map((x) => ({ identifier: x.identifier, name: x.name, quality: String(x.quality) }))));
  }, []);

  const saveProfile = () => {
    const brief = /^\d{1,2}:\d{2}$/.test(profile.briefTime) ? profile.briefTime : '08:30';
    store.update((s) => {
      s.profile = { ...profile, briefTime: brief };
    });
    store.log('user', 'settings', 'Profil ve çalışma kuralları güncellendi');
    setSaved(true);
    setTimeout(() => setSaved(false), 2000);
  };

  const setVoice = (patch: Partial<typeof st.voice>) =>
    store.update((s) => {
      s.voice = { ...s.voice, ...patch };
    });

  const h = j.watch.health;
  return (
    <View>
      <Glass title="SEN VE ÇALIŞMA ŞEKLİN">
        <Field label="Adın" value={profile.name} onChangeText={(v) => setProfile({ ...profile, name: v })} placeholder="Ahmet" />
        <Field label="Jarvis sana nasıl hitap etsin?" value={profile.address} onChangeText={(v) => setProfile({ ...profile, address: v })} placeholder="ör. Ahmet Bey, efendim, Ahmet" />
        <Field label="Şehir (hava durumu için)" value={profile.city} onChangeText={(v) => setProfile({ ...profile, city: v })} placeholder="İstanbul" />
        <Field label="Sabah brifingi saati" value={profile.briefTime} onChangeText={(v) => setProfile({ ...profile, briefTime: v })} placeholder="08:30" />
        <Field
          label="Kurallar, öncelikler, çalışma şeklin"
          value={profile.rules}
          onChangeText={(v) => setProfile({ ...profile, rules: v })}
          multiline
          placeholder={'ör.\n- Hafta içi 09-17 okul, proje işlerini akşama koy\n- TÜBİTAK teslimi her şeyden önce gelir\n- Spor günleri: Pzt, Çar, Cum 19:00\n- Gece 23:00 sonrası iş planlama'}
        />
        <Btn label={saved ? 'Kaydedildi ✓' : 'Kaydet'} onPress={saveProfile} />
      </Glass>

      <Glass title="BAĞLANTILAR">
        <Status label="Yapay zekâ (Claude)" status={j.keys.anthropic ? h.claude : 'eksik'} note={h.messages.claude} />
        {!j.keys.anthropic ? (
          <Text style={[font.sub, { marginBottom: 8 }]}>
            Anahtarı console.anthropic.com → API Keys bölümünden oluşturup buraya yapıştır. Anahtar yalnızca bu telefonda şifreli saklanır.
          </Text>
        ) : null}
        <Field value={claudeKey} onChangeText={setClaudeKey} placeholder={j.keys.anthropic ? 'Yeni anahtar ile değiştir (sk-ant-…)' : 'sk-ant-…'} secureTextEntry autoCapitalize="none" autoCorrect={false} />
        <View style={{ flexDirection: 'row', gap: 8, marginBottom: 6 }}>
          <Btn
            small
            label={busy === 'claude' ? 'Test ediliyor…' : 'Test et ve kaydet'}
            disabled={!claudeKey.trim() || busy === 'claude'}
            onPress={async () => {
              setBusy('claude');
              const r = await j.setKey('anthropic', claudeKey);
              setMsg((m) => ({ ...m, claude: r.message }));
              if (r.ok) setClaudeKey('');
              setBusy(null);
            }}
          />
          {j.keys.anthropic ? <Btn small kind="danger" label="Kaldır" onPress={() => void j.setKey('anthropic', null)} /> : null}
        </View>
        {msg.claude ? <Text style={[font.sub, { color: C.cyanSoft, marginBottom: 8 }]}>{msg.claude}</Text> : null}

        <Status label="Takvim" status={h.calendar} note={h.messages.calendar} action={h.calendar === 'eksik' ? () => void j.grant('calendar') : undefined} />
        <Status label="Bildirimler" status={h.notifications} note={h.messages.notifications} action={h.notifications === 'eksik' ? () => void j.grant('notifications') : undefined} />
        <Status label="Mikrofon ve konuşma tanıma" status={h.microphone} action={h.microphone === 'eksik' ? () => void j.grant('microphone') : undefined} />
        <Status label="Hava durumu (Open-Meteo)" status={h.weather} note={h.messages.weather} />
        <Btn small kind="ghost" label="Bağlantıları yeniden kontrol et" onPress={() => void j.refresh()} />
      </Glass>

      <Glass title="SES">
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
          <View style={{ flex: 1, paddingRight: 10 }}>
            <Text style={font.body}>“Hey Jarvis” ile uyandırma</Text>
            <Text style={font.sub}>Uygulama açıkken sürekli dinler. Pil tüketimini artırır.</Text>
          </View>
          <Switch value={st.voice.wakeWord} onValueChange={(v) => setVoice({ wakeWord: v })} trackColor={{ true: C.cyan, false: '#1E3A5F' }} />
        </View>

        <Text style={[font.tiny, { marginBottom: 6 }]}>KONUŞMA HIZI</Text>
        <View style={{ flexDirection: 'row', flexWrap: 'wrap' }}>
          {[0.85, 0.95, 1.0, 1.1].map((r) => (
            <Chip key={r} label={r === 1 ? 'Normal' : r < 1 ? (r < 0.9 ? 'Yavaş' : 'Sakin') : 'Hızlı'} active={st.voice.rate === r} onPress={() => setVoice({ rate: r })} />
          ))}
        </View>

        {voices.length ? (
          <>
            <Text style={[font.tiny, { marginVertical: 6 }]}>CİHAZ SESİ (TÜRKÇE)</Text>
            <View style={{ flexDirection: 'row', flexWrap: 'wrap' }}>
              <Chip label="Otomatik" active={!st.voice.deviceVoice} onPress={() => setVoice({ deviceVoice: undefined })} />
              {voices.map((v) => (
                <Chip key={v.identifier} label={`${v.name}${v.quality === 'Enhanced' ? ' ★' : ''}`} active={st.voice.deviceVoice === v.identifier} onPress={() => setVoice({ deviceVoice: v.identifier })} />
              ))}
            </View>
          </>
        ) : null}

        <Text style={[font.tiny, { marginTop: 10, marginBottom: 6 }]}>PREMIUM SES (ELEVENLABS, OPSİYONEL)</Text>
        <Text style={[font.sub, { marginBottom: 8 }]}>
          {j.keys.eleven
            ? 'Premium ses aktif.'
            : 'elevenlabs.io → Profile → API Keys bölümünden anahtar alırsan Jarvis çok daha doğal bir sesle konuşur. Yoksa telefonun Türkçe sesi kullanılır.'}
        </Text>
        <Field value={elevenKey} onChangeText={setElevenKey} placeholder="ElevenLabs API anahtarı" secureTextEntry autoCapitalize="none" autoCorrect={false} />
        <Field
          label={`Ses kimliği (boş = ${DEFAULT_ELEVEN_VOICE} “Daniel”)`}
          value={elevenVoice}
          onChangeText={setElevenVoice}
          onEndEditing={() => setVoice({ elevenVoiceId: elevenVoice.trim() || undefined })}
          autoCapitalize="none"
        />
        <View style={{ flexDirection: 'row', gap: 8, flexWrap: 'wrap' }}>
          <Btn
            small
            label={busy === 'eleven' ? 'Test ediliyor…' : 'Test et ve kaydet'}
            disabled={!elevenKey.trim() || busy === 'eleven'}
            onPress={async () => {
              setBusy('eleven');
              setVoice({ elevenVoiceId: elevenVoice.trim() || undefined });
              const r = await j.setKey('elevenlabs', elevenKey);
              setMsg((m) => ({ ...m, eleven: r.message }));
              if (r.ok) setElevenKey('');
              setBusy(null);
            }}
          />
          {j.keys.eleven ? <Btn small kind="danger" label="Kaldır" onPress={() => void j.setKey('elevenlabs', null)} /> : null}
          <Btn small kind="ghost" label="Sesi dene" onPress={() => j.say(`Merhaba${st.profile.address ? ' ' + st.profile.address : ''}. Ben Jarvis. Tüm sistemler hazır, nasıl yardımcı olabilirim?`)} />
        </View>
        {msg.eleven ? <Text style={[font.sub, { color: C.cyanSoft, marginTop: 8 }]}>{msg.eleven}</Text> : null}
      </Glass>

      <Glass title="JARVIS'İN HAFIZASI">
        <Text style={[font.sub, { marginBottom: 8 }]}>Konuşurken “bunu unutma”, “bundan sonra …” dediklerin burada tutulur ve her kararında kullanılır.</Text>
        {st.memories.length ? (
          st.memories.map((m) => (
            <View key={m.id} style={{ flexDirection: 'row', alignItems: 'center', paddingVertical: 6 }}>
              <Dot color={m.kind === 'talimat' ? C.cyan : m.kind === 'tercih' ? C.purple : C.blue} />
              <Text style={[font.body, { flex: 1 }]}>{m.text}</Text>
              <Btn small kind="ghost" label="Sil" onPress={() => forget(store, m.id, 'user')} />
            </View>
          ))
        ) : (
          <Empty text="Henüz kayıt yok." />
        )}
      </Glass>

      <Text style={[font.tiny, { textAlign: 'center', marginVertical: 12 }]}>
        {st.tasks.length} görev · {st.projects.length} proje · {st.notes.length} not · {st.activity.length} kayıt — tüm veriler bu cihazda
      </Text>
    </View>
  );
}

function Status({ label, status, note, action }: { label: string; status: IntegrationStatus; note?: string; action?: () => void }) {
  const [text, color] = STATUS_TEXT[status];
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', paddingVertical: 8 }}>
      <Dot color={color} />
      <View style={{ flex: 1 }}>
        <Text style={font.body}>{label}</Text>
        <Text style={[font.sub, { color }]}>{note ? `${text}: ${note}` : text}</Text>
      </View>
      {action ? <Btn small label="İzin ver" onPress={action} /> : null}
    </View>
  );
}

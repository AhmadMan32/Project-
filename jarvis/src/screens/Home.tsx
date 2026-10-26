// Komuta merkezi: üstte canlı durum kartları, ortada orb ve konuşma,
// altta (geniş ekranda yanlarda) aksiyon gerektiren paneller.

import * as Haptics from 'expo-haptics';
import { useState } from 'react';
import { Platform, Pressable, StyleSheet, Text, TextInput, useWindowDimensions, View } from 'react-native';
import { updateTask } from '../core/ops';
import { focusList, isDueToday, isOverdue, stalledProjects } from '../core/priority';
import { dayKey, dueDate, relativeDue, trTime } from '../core/time';
import { store, useJarvis } from '../runtime/JarvisProvider';
import { Btn, Check, Chip, Dot, Empty, Glass, s as ks } from '../ui/kit';
import { useGo } from '../ui/nav';
import Orb from '../ui/Orb';
import { C, font, levelColor } from '../ui/theme';

const SUGGEST = ['Bugün neler oldu?', 'Şu an öncelikli ne?', 'Yeni bir proje fikrim var', 'Yarınımı planla'];

export default function Home() {
  const go = useGo();
  const { width, height } = useWindowDimensions();
  const wide = width >= 900;
  const j = useJarvis();
  const now = new Date();
  const st = j.state;

  const doneToday = st.tasks.filter((t) => t.doneAt && dayKey(new Date(t.doneAt)) === dayKey(now)).length;
  const openToday = st.tasks.filter((t) => t.status !== 'tamam' && t.due && isDueToday(t, now)).length;
  const overdue = st.tasks.filter((t) => isOverdue(t, now));
  const nextEvent = j.watch.events.find((e) => new Date(e.end).getTime() > now.getTime() && !e.allDay);
  const active = st.projects.filter((p) => p.status === 'aktif');
  const stalled = stalledProjects(st, now);
  const focus = focusList(st, now, j.watch.events, 5);

  const orbSize = Math.round(Math.min(wide ? 460 : width - 40, height * (wide ? 0.6 : 0.42), 480));

  const metrics = (
    <View style={[styles.metrics, wide && styles.metricsWide]}>
      <Metric wide={wide} label="BUGÜN" value={`${doneToday}/${doneToday + openToday}`} sub={openToday ? `${openToday} iş kaldı` : doneToday ? 'bugünün işleri bitti' : 'bugüne planlı iş yok'} onPress={() => go('plan')} />
      <Metric wide={wide} label="GECİKEN" value={String(overdue.length)} sub={overdue[0]?.title ?? 'gecikme yok'} color={overdue.length ? C.red : C.green} onPress={() => go('plan')} />
      <Metric
        wide={wide}
        label="SIRADAKİ"
        value={nextEvent ? trTime(new Date(nextEvent.start)) : '—'}
        sub={nextEvent ? `${dayKey(new Date(nextEvent.start)) === dayKey(now) ? '' : 'yarın+ · '}${nextEvent.title}` : j.watch.health.calendar === 'ok' ? 'takvim boş' : 'takvim bağlı değil'}
        onPress={() => go('plan')}
      />
      <Metric wide={wide} label="PROJELER" value={String(active.length)} sub={stalled.length ? `${stalled.length} proje duruyor` : active.length ? 'hepsi ilerliyor' : 'aktif proje yok'} color={stalled.length ? C.amber : C.cyan} onPress={() => go('projects')} />
    </View>
  );

  const center = (
    <View style={{ alignItems: 'center' }}>
      <Pressable
        onPress={() => {
          if (Platform.OS !== 'web') void Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
          j.talk();
        }}
        accessibilityLabel="Jarvis ile konuş"
      >
        <Orb size={orbSize} mode={j.mode} level={j.level} />
      </Pressable>
      <Text style={styles.status}>
        {j.mode === 'listening'
          ? 'DİNLİYORUM…'
          : j.mode === 'thinking'
            ? `${(j.working || 'Düşünüyorum').toLocaleUpperCase('tr')}…`
            : j.mode === 'speaking'
              ? 'KONUŞUYOR · durdurmak için dokun'
              : j.wakeActive
                ? '“HEY JARVIS” DİYEBİLİRSİN'
                : 'KONUŞMAK İÇİN ORB’A DOKUN'}
      </Text>
      {j.partial ? <Text style={styles.heard}>“{j.partial}”</Text> : j.heard ? <Text style={styles.heard}>Sen: “{j.heard}”</Text> : null}
      {j.reply ? (
        <Glass style={{ width: '100%', marginTop: 10 }}>
          <Text style={[font.body, { fontSize: 16, lineHeight: 23 }]}>{j.reply}</Text>
        </Glass>
      ) : null}
      <Composer onSend={(t) => void j.ask(t)} onMic={j.talk} listening={j.mode === 'listening'} />
      <View style={styles.suggest}>
        {SUGGEST.map((q) => (
          <Chip key={q} label={q} onPress={() => void j.ask(q)} />
        ))}
      </View>
    </View>
  );

  const pending = st.pending.length ? (
    <Glass title="ONAY BEKLEYEN">
      {st.pending.map((p) => (
        <View key={p.id} style={{ marginBottom: 10 }}>
          <Text style={font.body}>{p.summary}</Text>
          {p.reason ? <Text style={font.sub}>{p.reason}</Text> : null}
          <View style={{ flexDirection: 'row', gap: 8, marginTop: 8 }}>
            <Btn small label="Onayla" kind="danger" onPress={() => void j.decide(p.id, true)} />
            <Btn small label="Vazgeç" kind="ghost" onPress={() => void j.decide(p.id, false)} />
          </View>
        </View>
      ))}
    </Glass>
  ) : null;

  const brief =
    st.meta.lastBrief && st.meta.lastBriefDate === dayKey(now) ? (
      <Glass title="SABAH BRİFİNGİ" right={<Btn small kind="ghost" label="Dinle" onPress={() => j.say(st.meta.lastBrief!)} />}>
        <Text style={font.sub}>{st.meta.lastBrief}</Text>
      </Glass>
    ) : null;

  const focusPanel = (
    <Glass title="ŞU AN ÖNCELİKLİ">
      {focus.length ? (
        focus.map((f) => (
          <View key={f.kind + f.id} style={ks.row}>
            {f.kind === 'task' ? (
              <Check done={false} label={`Tamamla: ${f.title}`} onPress={() => updateTask(store, f.id, { status: 'tamam' }, 'user')} />
            ) : (
              <Dot color={f.kind === 'event' ? C.purple : C.amber} />
            )}
            <View style={{ flex: 1 }}>
              <Text style={font.body}>{f.title}</Text>
              <Text style={font.sub}>
                {f.projectName && f.kind === 'task' ? `${f.projectName} · ` : ''}
                {f.reason}
              </Text>
            </View>
          </View>
        ))
      ) : (
        <Empty text="Acil bir iş yok. Jarvis'e yeni bir plan ya da proje anlatabilirsin." />
      )}
    </Glass>
  );

  const upcoming = (
    <Glass title="YAKLAŞAN">
      {(() => {
        const evs = j.watch.events.filter((e) => new Date(e.end).getTime() > now.getTime()).slice(0, 4);
        const ts = st.tasks
          .filter((t) => t.status !== 'tamam' && t.due && !isOverdue(t, now) && dueDate(t.due).getTime() - now.getTime() < 4 * 86400000)
          .sort((a, b) => a.due!.localeCompare(b.due!))
          .slice(0, 5);
        if (!evs.length && !ts.length)
          return <Empty text={j.watch.health.calendar === 'ok' ? 'Önümüzdeki günlerde planlı bir şey yok.' : 'Takvim bağlanınca etkinliklerin burada görünecek.'} />;
        return (
          <>
            {evs.map((e) => (
              <View key={e.id} style={ks.row}>
                <Dot color={C.purple} />
                <View style={{ flex: 1 }}>
                  <Text style={font.body}>{e.title}</Text>
                  <Text style={font.sub}>{e.allDay ? `${dayKey(new Date(e.start))} · tüm gün` : `${relativeDue(`${dayKey(new Date(e.start))}T${trTime(new Date(e.start))}`, now)}`}</Text>
                </View>
              </View>
            ))}
            {ts.map((t) => (
              <View key={t.id} style={ks.row}>
                <Dot color={t.priority === 1 ? C.red : C.cyan} />
                <View style={{ flex: 1 }}>
                  <Text style={font.body}>{t.title}</Text>
                  <Text style={font.sub}>{relativeDue(t.due!, now)}</Text>
                </View>
              </View>
            ))}
          </>
        );
      })()}
    </Glass>
  );

  const alerts = (
    <Glass title="UYARILAR & SİSTEM">
      {j.watch.alerts.length ? (
        j.watch.alerts.slice(0, 7).map((a) => (
          <View key={a.key} style={ks.row}>
            <Dot color={levelColor[a.level]} />
            <View style={{ flex: 1 }}>
              <Text style={font.body}>{a.text}</Text>
              {a.fix ? <Text style={[font.sub, { color: C.cyanSoft }]}>{a.fix}</Text> : null}
            </View>
          </View>
        ))
      ) : (
        <Empty text={j.checked ? 'Tüm sistemler çalışıyor, önemli bir uyarı yok.' : 'Sistemler kontrol ediliyor…'} />
      )}
      {j.watch.weather ? (
        <Text style={[font.sub, { marginTop: 6 }]}>
          {j.watch.weather.city}: {j.watch.weather.now.temp}°C, {j.watch.weather.now.text.toLocaleLowerCase('tr')} · rüzgâr {j.watch.weather.now.wind} km/s
        </Text>
      ) : null}
    </Glass>
  );

  const log = (
    <Glass title="JARVIS NE YAPTI">
      {(() => {
        // Jarvis'in kendi işlemleri + önemli sistem olayları (içe aktarımın tek tek görev kayıtları hariç)
        const acts = st.activity
          .filter((a) => a.actor === 'jarvis' || (a.actor === 'system' && a.kind !== 'task_created' && a.kind !== 'project_created'))
          .slice(-8)
          .reverse();
        if (!acts.length) return <Empty text="Henüz bir işlem yok." />;
        return acts.map((a) => (
          <View key={a.id} style={[ks.row, { paddingVertical: 5 }]}>
            <Text style={[font.tiny, { width: 46, marginTop: 2 }]}>{dayKey(new Date(a.at)) === dayKey(now) ? trTime(new Date(a.at)) : `${new Date(a.at).getDate()}.${new Date(a.at).getMonth() + 1}`}</Text>
            <Text style={[font.sub, { flex: 1, color: C.text }]}>{a.text}</Text>
          </View>
        ));
      })()}
    </Glass>
  );

  if (wide) {
    return (
      <View>
        {metrics}
        <View style={{ flexDirection: 'row', gap: 16 }}>
          <View style={{ flex: 1 }}>
            {pending}
            {focusPanel}
            {upcoming}
          </View>
          <View style={{ flex: 1.35 }}>{center}</View>
          <View style={{ flex: 1 }}>
            {brief}
            {alerts}
            {log}
          </View>
        </View>
      </View>
    );
  }
  return (
    <View>
      {metrics}
      {center}
      <View style={{ height: 16 }} />
      {pending}
      {focusPanel}
      {alerts}
      {brief}
      {upcoming}
      {log}
    </View>
  );
}

function Metric({ label, value, sub, color = C.cyan, onPress, wide }: { label: string; value: string; sub: string; color?: string; onPress: () => void; wide?: boolean }) {
  return (
    <Pressable onPress={onPress} style={({ pressed }) => [styles.metric, wide && { flexBasis: 0 }, { opacity: pressed ? 0.75 : 1 }]}>
      <Text style={font.tiny}>{label}</Text>
      <Text style={[styles.metricValue, { color }]}>{value}</Text>
      <Text style={[font.sub, { fontSize: 12 }]} numberOfLines={1}>
        {sub}
      </Text>
    </Pressable>
  );
}

function Composer({ onSend, onMic, listening }: { onSend: (t: string) => void; onMic: () => void; listening: boolean }) {
  const [text, setText] = useState('');
  const send = () => {
    if (!text.trim()) return;
    onSend(text);
    setText('');
  };
  return (
    <View style={styles.composer}>
      <TextInput
        value={text}
        onChangeText={setText}
        onSubmitEditing={send}
        placeholder="Jarvis'e yaz ya da sor…"
        placeholderTextColor={C.muted}
        style={styles.composerInput}
        returnKeyType="send"
      />
      <Pressable onPress={text.trim() ? send : onMic} style={[styles.mic, listening && { backgroundColor: C.cyan }]} accessibilityLabel={text.trim() ? 'Gönder' : 'Mikrofon'}>
        <Text style={{ color: listening ? C.bg : C.cyan, fontSize: 18, fontWeight: '800' }}>{text.trim() ? '➤' : '🎙'}</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  metrics: { flexDirection: 'row', flexWrap: 'wrap', gap: 10, marginBottom: 14 },
  metricsWide: { flexWrap: 'nowrap' },
  metric: {
    flexGrow: 1,
    flexBasis: '45%',
    minWidth: 140,
    backgroundColor: C.panel,
    borderColor: C.border,
    borderWidth: 1,
    borderRadius: 16,
    padding: 14,
  },
  metricValue: { fontSize: 28, fontWeight: '300', marginVertical: 2, letterSpacing: 0.5 },
  status: { ...font.tiny, color: C.cyanSoft, marginTop: 4, letterSpacing: 2.4, textAlign: 'center' },
  heard: { ...font.sub, fontStyle: 'italic', marginTop: 8, textAlign: 'center' },
  composer: {
    flexDirection: 'row',
    alignItems: 'center',
    width: '100%',
    marginTop: 12,
    borderWidth: 1,
    borderColor: C.border,
    borderRadius: 16,
    backgroundColor: 'rgba(2,10,24,0.7)',
    paddingLeft: 14,
  },
  composerInput: { flex: 1, color: C.text, fontSize: 15, paddingVertical: 13 },
  mic: { width: 46, height: 46, borderRadius: 14, margin: 4, alignItems: 'center', justifyContent: 'center', borderWidth: 1, borderColor: C.cyan },
  suggest: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'center', marginTop: 12 },
});

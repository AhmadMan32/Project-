// Planım: kişisel düzen. Geciken, bugün, yaklaşan, alışkanlıklar ve takvim.
import { useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import { createTask, updateTask } from '../core/ops';
import { isDueToday, isOverdue } from '../core/priority';
import { addDays, dayKey, relativeDue, shortDay, trTime } from '../core/time';
import type { Recurrence, Task } from '../core/types';
import { store, useJarvis } from '../runtime/JarvisProvider';
import { Btn, Check, Chip, Dot, Empty, Field, Glass, s as ks } from '../ui/kit';
import { C, font } from '../ui/theme';

const REC_LABEL: Record<Recurrence, string> = { gunluk: 'her gün', hafta_ici: 'hafta içi', haftalik: 'her hafta' };

export default function Plan() {
  const j = useJarvis();
  const now = new Date();
  const [scope, setScope] = useState<'kisisel' | 'hepsi'>('hepsi');
  const tasks = j.state.tasks.filter((t) => scope === 'hepsi' || !t.projectId);
  const open = tasks.filter((t) => t.status !== 'tamam');
  const overdue = open.filter((t) => isOverdue(t, now));
  const today = open.filter((t) => isDueToday(t, now) && !isOverdue(t, now));
  const soon = open.filter((t) => t.due && !isDueToday(t, now) && !isOverdue(t, now) && t.due.slice(0, 10) <= dayKey(addDays(now, 7))).sort((a, b) => a.due!.localeCompare(b.due!));
  const later = open.filter((t) => !t.due || t.due.slice(0, 10) > dayKey(addDays(now, 7)));
  const habits = j.state.tasks.filter((t) => t.recurrence && t.status !== 'tamam');
  const doneToday = tasks.filter((t) => t.doneAt && dayKey(new Date(t.doneAt)) === dayKey(now));

  return (
    <View>
      <QuickAdd />
      <View style={{ flexDirection: 'row', marginBottom: 6 }}>
        <Chip label="Tümü" active={scope === 'hepsi'} onPress={() => setScope('hepsi')} />
        <Chip label="Sadece kişisel" active={scope === 'kisisel'} onPress={() => setScope('kisisel')} />
      </View>
      {overdue.length ? <Section title="GECİKEN" color={C.red} items={overdue} /> : null}
      <Section title="BUGÜN" items={today} empty="Bugün için tarihli iş yok." />
      <Glass title="TAKVİM · 7 GÜN">
        {j.watch.health.calendar !== 'ok' ? (
          <View>
            <Empty text={j.watch.health.calendar === 'kullanilamaz' ? 'Takvim yalnızca telefonda çalışır.' : 'Takvim izni verilmedi; etkinliklerini göremiyorum.'} />
            {j.watch.health.calendar !== 'kullanilamaz' ? <Btn small label="Takvim iznini ver" onPress={() => void j.grant('calendar')} /> : null}
          </View>
        ) : j.watch.events.filter((e) => new Date(e.end) > now).length ? (
          j.watch.events
            .filter((e) => new Date(e.end) > now)
            .slice(0, 14)
            .map((e) => (
              <View key={e.id} style={ks.row}>
                <Dot color={C.purple} />
                <View style={{ flex: 1 }}>
                  <Text style={font.body}>{e.title}</Text>
                  <Text style={font.sub}>
                    {shortDay(new Date(e.start))} · {e.allDay ? 'tüm gün' : `${trTime(new Date(e.start))}–${trTime(new Date(e.end))}`}
                    {e.location ? ` · ${e.location}` : ''}
                  </Text>
                </View>
              </View>
            ))
        ) : (
          <Empty text="Önümüzdeki 7 günde etkinlik yok." />
        )}
      </Glass>
      <Section title="YAKLAŞAN (7 GÜN)" items={soon} empty="Yaklaşan tarihli iş yok." />
      {habits.length ? <Section title="ALIŞKANLIKLAR" items={habits} /> : null}
      <Section title="TARİHSİZ / İLERİ" items={later} empty="Tarihsiz iş yok." />
      <Glass title={`BUGÜN TAMAMLANAN · ${doneToday.length}`}>
        {doneToday.length ? (
          doneToday.map((t) => <TaskRow key={t.id} t={t} />)
        ) : (
          <Empty text="Henüz bir şey tamamlanmadı." />
        )}
      </Glass>
    </View>
  );
}

function Section({ title, items, empty, color }: { title: string; items: Task[]; empty?: string; color?: string }) {
  return (
    <Glass title={`${title} · ${items.length}`} style={color ? { borderColor: `${color}66` } : undefined}>
      {items.length ? items.map((t) => <TaskRow key={t.id} t={t} />) : <Empty text={empty ?? ''} />}
    </Glass>
  );
}

export function TaskRow({ t, hideProject }: { t: Task; hideProject?: boolean }) {
  const { state } = useJarvis();
  const [open, setOpen] = useState(false);
  const [confirm, setConfirm] = useState(false);
  const now = new Date();
  const p = t.projectId ? state.projects.find((x) => x.id === t.projectId) : undefined;
  const done = t.status === 'tamam';
  const meta = [
    t.due ? relativeDue(t.due, now) : null,
    !hideProject && p ? p.name : null,
    t.recurrence ? REC_LABEL[t.recurrence] : null,
    t.priority === 1 ? 'yüksek öncelik' : null,
    t.status === 'devam' ? 'devam ediyor' : t.status === 'bekliyor' ? 'bekliyor' : null,
  ].filter(Boolean);
  return (
    <View>
      <Pressable onPress={() => setOpen((o) => !o)} style={ks.row}>
        <Check done={done} label={`${done ? 'Geri al' : 'Tamamla'}: ${t.title}`} color={t.priority === 1 ? C.red : C.cyan} onPress={() => updateTask(store, t.id, { status: done ? 'acik' : 'tamam' }, 'user')} />
        <View style={{ flex: 1 }}>
          <Text style={[font.body, done && { color: C.muted, textDecorationLine: 'line-through' }]}>{t.title}</Text>
          {meta.length ? <Text style={[font.sub, t.due && isOverdue(t, now) && { color: C.red }]}>{meta.join(' · ')}</Text> : null}
          {open && t.notes ? <Text style={[font.sub, { marginTop: 4 }]}>{t.notes}</Text> : null}
        </View>
      </Pressable>
      {open && !done ? (
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginLeft: 34, marginBottom: 8 }}>
          <Btn small kind="ghost" label="Başladım" onPress={() => updateTask(store, t.id, { status: 'devam' }, 'user')} />
          <Btn small kind="ghost" label="Yarına ertele" onPress={() => updateTask(store, t.id, { due: dayKey(addDays(now, 1)) + (t.due && t.due.length > 10 ? t.due.slice(10) : '') }, 'user')} />
          <Btn small kind="ghost" label={t.priority === 1 ? 'Önceliği düşür' : 'Öncelikli yap'} onPress={() => updateTask(store, t.id, { priority: t.priority === 1 ? 2 : 1 }, 'user')} />
          <Btn
            small
            kind="danger"
            label={confirm ? 'Emin misin? Sil' : 'Sil'}
            onPress={() => {
              if (!confirm) return setConfirm(true);
              store.update((s) => {
                s.tasks = s.tasks.filter((x) => x.id !== t.id);
              });
              store.log('user', 'task_deleted', `Görev silindi: ${t.title}`);
            }}
          />
        </View>
      ) : null}
    </View>
  );
}

function QuickAdd() {
  const [title, setTitle] = useState('');
  const [when, setWhen] = useState<'bugun' | 'yarin' | 'yok'>('bugun');
  const [rec, setRec] = useState<Recurrence | undefined>();
  const [prio, setPrio] = useState<1 | 2>(2);
  const add = () => {
    if (!title.trim()) return;
    const now = new Date();
    const due = when === 'yok' ? undefined : dayKey(when === 'bugun' ? now : addDays(now, 1));
    createTask(store, { title, due, recurrence: rec, priority: prio }, 'user');
    setTitle('');
    setRec(undefined);
    setPrio(2);
  };
  return (
    <Glass title="HIZLI EKLE">
      <Field value={title} onChangeText={setTitle} placeholder="Ne yapılacak? (ör. Alışveriş listesi)" onSubmitEditing={add} returnKeyType="done" />
      <View style={{ flexDirection: 'row', flexWrap: 'wrap' }}>
        <Chip label="Bugün" active={when === 'bugun'} onPress={() => setWhen('bugun')} />
        <Chip label="Yarın" active={when === 'yarin'} onPress={() => setWhen('yarin')} />
        <Chip label="Tarihsiz" active={when === 'yok'} onPress={() => setWhen('yok')} />
        <Chip label="Her gün" active={rec === 'gunluk'} onPress={() => setRec(rec === 'gunluk' ? undefined : 'gunluk')} color={C.purple} />
        <Chip label="Öncelikli" active={prio === 1} onPress={() => setPrio(prio === 1 ? 2 : 1)} color={C.red} />
      </View>
      <Btn label="Ekle" onPress={add} disabled={!title.trim()} />
    </Glass>
  );
}

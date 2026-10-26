// Projeler: fikirden teslime aşamalı takip. Jarvis'ten planlama ve özet istenebilir.
import { useRouter } from 'expo-router';
import { useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import { addNote, createProject, createTask, updateProject } from '../core/ops';
import { nextStep, projectProgress, stalledProjects } from '../core/priority';
import { daysBetween, shortDay } from '../core/time';
import { Project, STAGE_LABEL, STAGES, Stage } from '../core/types';
import { store, useJarvis } from '../runtime/JarvisProvider';
import { Btn, Chip, Empty, Field, Glass } from '../ui/kit';
import { useGo } from '../ui/nav';
import { C, font } from '../ui/theme';
import { TaskRow } from './Plan';

const STATUS_LABEL = { aktif: 'Aktif', beklemede: 'Beklemede', tamamlandi: 'Tamamlandı', iptal: 'İptal' } as const;

export default function Projects() {
  const j = useJarvis();
  const router = useRouter();
  const [showClosed, setShowClosed] = useState(false);

  const now = new Date();
  const stalled = new Set(stalledProjects(j.state, now).map((p) => p.id));
  const list = j.state.projects.filter((p) => showClosed || p.status === 'aktif' || p.status === 'beklemede');

  return (
    <View>
      <NewProject />
      {list.length ? (
        list.map((p) => {
          const pr = projectProgress(j.state, p);
          const step = nextStep(j.state, p);
          const left = p.deadline ? daysBetween(now, new Date(p.deadline + 'T12:00')) : null;
          return (
            <Pressable key={p.id} onPress={() => router.push({ pathname: '/projects/[id]', params: { id: p.id } })}>
              <Glass title={p.name.toLocaleUpperCase('tr')} right={<Text style={{ color: statusColor(p), fontSize: 12 }}>{STATUS_LABEL[p.status]}</Text>}>
                <Pipeline stage={p.stage} />
                <Bar pct={pr.pct} />
                <Text style={font.sub}>
                  %{pr.pct} · {pr.done}/{pr.total} görev
                  {left !== null ? ` · teslim ${shortDay(new Date(p.deadline! + 'T12:00'))}${left >= 0 ? ` (${left} gün)` : ' (geçti)'}` : ''}
                </Text>
                <Text style={[font.body, { marginTop: 6 }]}>Sıradaki: {step?.title ?? 'adım belirlenmedi'}</Text>
                {stalled.has(p.id) ? <Text style={[font.sub, { color: C.amber, marginTop: 4 }]}>Birkaç gündür ilerleme yok</Text> : null}
              </Glass>
            </Pressable>
          );
        })
      ) : (
        <Glass>
          <Empty text="Aktif proje yok. Bir fikrini Jarvis'e anlat, projeye çevirsin." />
        </Glass>
      )}
      {j.state.projects.some((p) => p.status === 'tamamlandi' || p.status === 'iptal') ? (
        <Chip label={showClosed ? 'Kapananları gizle' : 'Kapanan projeleri göster'} onPress={() => setShowClosed(!showClosed)} />
      ) : null}
    </View>
  );
}

function statusColor(p: Project) {
  return p.status === 'aktif' ? C.green : p.status === 'beklemede' ? C.amber : C.muted;
}

function Pipeline({ stage, onPick }: { stage: Stage; onPick?: (s: Stage) => void }) {
  const idx = STAGES.indexOf(stage);
  return (
    <View style={{ flexDirection: 'row', gap: 4, marginBottom: 10 }}>
      {STAGES.map((st, i) => (
        <Pressable key={st} onPress={onPick ? () => onPick(st) : undefined} style={{ flex: 1 }}>
          <View style={{ height: 4, borderRadius: 2, backgroundColor: i < idx ? C.blue : i === idx ? C.cyanSoft : 'rgba(56,189,248,0.15)' }} />
          <Text style={{ color: i === idx ? C.cyanSoft : C.muted, fontSize: 10, marginTop: 4, textAlign: 'center' }}>{STAGE_LABEL[st]}</Text>
        </Pressable>
      ))}
    </View>
  );
}

function Bar({ pct }: { pct: number }) {
  return (
    <View style={{ height: 6, borderRadius: 3, backgroundColor: 'rgba(56,189,248,0.12)', marginBottom: 6, overflow: 'hidden' }}>
      <View style={{ width: `${pct}%`, height: 6, backgroundColor: C.cyan }} />
    </View>
  );
}

function NewProject() {
  const j = useJarvis();
  const go = useGo();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState('');
  const [goal, setGoal] = useState('');
  const [deadline, setDeadline] = useState('');
  if (!open)
    return (
      <View style={{ marginBottom: 14 }}>
        <Btn label="+ Yeni proje / fikir" onPress={() => setOpen(true)} />
      </View>
    );
  const validDeadline = !deadline || /^\d{4}-\d{2}-\d{2}$/.test(deadline);
  return (
    <Glass title="YENİ PROJE">
      <Field label="Adı" value={name} onChangeText={setName} placeholder="ör. YouTube kanalı açmak" />
      <Field label="Ne yapmak istiyorsun?" value={goal} onChangeText={setGoal} placeholder="Amaç, fikir, aklındakiler…" multiline />
      <Field label="Teslim tarihi (opsiyonel, YYYY-AA-GG)" value={deadline} onChangeText={setDeadline} placeholder="2026-12-15" />
      <View style={{ flexDirection: 'row', gap: 8, flexWrap: 'wrap' }}>
        <Btn
          label="Jarvis planlasın"
          disabled={!name.trim() || !validDeadline}
          onPress={() => {
            void j.ask(
              `Yeni bir proje başlatmak istiyorum. Adı: ${name}. Amacım: ${goal || '(belirtmedim, sen netleştir)'}.${deadline ? ` Teslim tarihi: ${deadline}.` : ''} Projeyi oluştur, aşamalara böl ve ilk somut görevleri ekle.`,
            );
            setOpen(false);
            setName('');
            setGoal('');
            setDeadline('');
            go('home');
          }}
        />
        <Btn
          kind="ghost"
          label="Sadece ekle"
          disabled={!name.trim() || !validDeadline}
          onPress={() => {
            createProject(store, { name, goal, deadline: deadline || undefined }, 'user');
            setOpen(false);
            setName('');
            setGoal('');
            setDeadline('');
          }}
        />
        <Btn kind="ghost" label="Vazgeç" onPress={() => setOpen(false)} />
      </View>
    </Glass>
  );
}

export function ProjectDetail({ id }: { id: string }) {
  const j = useJarvis();
  const router = useRouter();
  const p = j.state.projects.find((x) => x.id === id);
  if (!p)
    return (
      <Glass>
        <Empty text="Bu proje artık yok." />
        <Btn small kind="ghost" label="‹ Projeler" onPress={() => router.navigate('/projects')} />
      </Glass>
    );
  return <Detail p={p} />;
}

function Detail({ p }: { p: Project }) {
  const j = useJarvis();
  const router = useRouter();
  const go = useGo();
  const back = () => (router.canGoBack() ? router.back() : router.navigate('/projects'));
  const [task, setTask] = useState('');
  const [note, setNote] = useState('');
  const [confirm, setConfirm] = useState(false);
  const [deadline, setDeadline] = useState(p.deadline ?? '');
  const tasks = j.state.tasks.filter((t) => t.projectId === p.id);
  const notes = j.state.notes.filter((n) => n.projectId === p.id).slice().reverse();
  const pr = projectProgress(j.state, p);
  const askAbout = (q: string) => {
    void j.ask(q);
    go('home');
  };
  return (
    <View>
      <Btn kind="ghost" small label="‹ Projeler" onPress={back} />
      <View style={{ height: 10 }} />
      <Glass title={p.name.toLocaleUpperCase('tr')}>
        {p.goal ? <Text style={[font.sub, { marginBottom: 10 }]}>{p.goal}</Text> : null}
        <Pipeline stage={p.stage} onPick={(st) => updateProject(store, p.id, { stage: st }, 'user')} />
        <Bar pct={pr.pct} />
        <Text style={font.sub}>
          %{pr.pct} · {pr.done}/{pr.total} görev · aşamaya dokunarak değiştirebilirsin
        </Text>
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', marginTop: 10 }}>
          {(['aktif', 'beklemede', 'tamamlandi', 'iptal'] as const).map((s) => (
            <Chip key={s} label={STATUS_LABEL[s]} active={p.status === s} onPress={() => updateProject(store, p.id, { status: s }, 'user')} />
          ))}
        </View>
        <Field
          label="Teslim tarihi (YYYY-AA-GG)"
          value={deadline}
          onChangeText={setDeadline}
          onEndEditing={() => {
            if (!deadline || /^\d{4}-\d{2}-\d{2}$/.test(deadline)) updateProject(store, p.id, { deadline: deadline || null }, 'user');
          }}
          placeholder="belirlenmedi"
        />
        <View style={{ flexDirection: 'row', gap: 8, flexWrap: 'wrap' }}>
          <Btn small label="Jarvis: sıradaki adımları planla" onPress={() => askAbout(`"${p.name}" projesinin durumuna bak ve sıradaki adımları planla; gerekli görevleri ekle.`)} />
          <Btn small kind="ghost" label="Durumu özetle" onPress={() => askAbout(`"${p.name}" projesi ne durumda?`)} />
        </View>
      </Glass>

      {STAGES.map((st) => {
        const ts = tasks.filter((t) => (t.stage ?? p.stage) === st);
        if (!ts.length && st !== p.stage) return null;
        return (
          <Glass key={st} title={`${STAGE_LABEL[st].toLocaleUpperCase('tr')} · ${ts.filter((t) => t.status === 'tamam').length}/${ts.length}`} style={st === p.stage ? { borderColor: C.borderBright } : undefined}>
            {ts.length ? ts.map((t) => <TaskRow key={t.id} t={t} hideProject />) : <Empty text="Bu aşamada görev yok." />}
            {st === p.stage ? (
              <View style={{ marginTop: 8 }}>
                <Field
                  value={task}
                  onChangeText={setTask}
                  placeholder="Bu aşamaya görev ekle…"
                  onSubmitEditing={() => {
                    if (!task.trim()) return;
                    createTask(store, { title: task, projectId: p.id, stage: st }, 'user');
                    setTask('');
                  }}
                />
              </View>
            ) : null}
          </Glass>
        );
      })}

      <Glass title="PROJE NOTLARI">
        <Field
          value={note}
          onChangeText={setNote}
          placeholder="Not, fikir, karar…"
          multiline
        />
        <Btn
          small
          label="Notu kaydet"
          disabled={!note.trim()}
          onPress={() => {
            addNote(store, { text: note, projectId: p.id }, 'user');
            setNote('');
          }}
        />
        {notes.map((n) => (
          <View key={n.id} style={{ marginTop: 12 }}>
            {n.title ? <Text style={[font.body, { fontWeight: '700' }]}>{n.title}</Text> : null}
            <Text style={font.sub}>{n.text}</Text>
          </View>
        ))}
      </Glass>

      <Btn
        kind="danger"
        label={confirm ? 'Emin misin? Proje ve görevleri silinecek' : 'Projeyi sil'}
        onPress={() => {
          if (!confirm) return setConfirm(true);
          store.update((s) => {
            s.projects = s.projects.filter((x) => x.id !== p.id);
            s.tasks = s.tasks.filter((t) => t.projectId !== p.id);
          });
          store.log('user', 'project_deleted', `Proje silindi: ${p.name}`);
          back();
        }}
      />
    </View>
  );
}

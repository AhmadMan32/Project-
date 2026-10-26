import { useState } from 'react';
import { Text, View } from 'react-native';
import { addNote } from '../core/ops';
import { shortDay, trTime } from '../core/time';
import { store, useJarvis } from '../runtime/JarvisProvider';
import { Btn, Chip, Empty, Field, Glass } from '../ui/kit';
import { C, font } from '../ui/theme';

export default function Notes() {
  const j = useJarvis();
  const [text, setText] = useState('');
  const [q, setQ] = useState('');
  const [confirmId, setConfirmId] = useState<string | null>(null);
  const ql = q.toLocaleLowerCase('tr');
  const notes = j.state.notes
    .filter((n) => !ql || `${n.title ?? ''} ${n.text}`.toLocaleLowerCase('tr').includes(ql))
    .slice()
    .reverse();
  return (
    <View>
      <Glass title="YENİ NOT">
        <Field value={text} onChangeText={setText} placeholder="Fikir, liste, taslak… (Jarvis'e de yazdırabilirsin)" multiline />
        <Btn
          label="Kaydet"
          disabled={!text.trim()}
          onPress={() => {
            addNote(store, { text }, 'user');
            setText('');
          }}
        />
      </Glass>
      <Field value={q} onChangeText={setQ} placeholder="Notlarda ara…" />
      {notes.length ? (
        notes.map((n) => {
          const p = n.projectId ? j.state.projects.find((x) => x.id === n.projectId) : undefined;
          const d = new Date(n.createdAt);
          return (
            <Glass key={n.id} title={(n.title ?? 'NOT').toLocaleUpperCase('tr')} right={<Text style={font.tiny}>{`${shortDay(d)} ${trTime(d)}`}</Text>}>
              <Text style={font.body}>{n.text}</Text>
              <View style={{ flexDirection: 'row', marginTop: 10, flexWrap: 'wrap' }}>
                {p ? <Chip label={p.name} color={C.purple} active /> : null}
                <Chip
                  label={confirmId === n.id ? 'Emin misin? Sil' : 'Sil'}
                  color={C.red}
                  active={confirmId === n.id}
                  onPress={() => {
                    if (confirmId !== n.id) return setConfirmId(n.id);
                    store.update((s) => {
                      s.notes = s.notes.filter((x) => x.id !== n.id);
                    });
                    store.log('user', 'note_deleted', `Not silindi: ${n.title ?? n.text.slice(0, 30)}`);
                  }}
                />
              </View>
            </Glass>
          );
        })
      ) : (
        <Glass>
          <Empty text={q ? 'Eşleşen not yok.' : 'Henüz not yok.'} />
        </Glass>
      )}
    </View>
  );
}

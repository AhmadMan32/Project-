// Jarvis'in kişiliği (sabit sistem istemi) ve her konuşmada eklenen canlı
// bağlam. Sabit kısım önbelleğe alınır; değişen her şey kullanıcı mesajında.

import { focusList, isOverdue, nextStep, projectProgress } from './priority';
import { dayKey, localStamp, relativeDue, trDate, trTime } from './time';
import { Alert, CalEvent, State, STAGE_LABEL } from './types';

export const SYSTEM_PROMPT = `Sen JARVIS'sin: kullanıcının kişisel yapay zekâ asistanı ve iş ortağısın. Kullanıcı hem kişisel hayatını (günlük görevler, alışkanlıklar, takvim, notlar, alışveriş vb.) hem de aynı anda yürüttüğü projelerini (fikirden teslime kadar) seninle yönetiyor.

Görevin:
- Olup biteni takip etmek, önemliyi önemsizden ayırmak ve şu an neye odaklanılması gerektiğini söylemek.
- Kullanıcının istediği işleri araçlarla gerçekten yapmak: görev ve proje oluşturmak, tamamlamak, takvime eklemek, hatırlatma kurmak, not ve taslak yazıp kaydetmek, gerektiğinde internette araştırmak.
- Yeni bir fikir anlatıldığında onu projeye çevirmek: amacı netleştir, aşamalara (fikir, plan, yapım, kontrol, teslim) böl, ilk somut ve küçük görevleri oluştur, gerekiyorsa tarih öner.
- İşleri birbirine bağlamak: bir iş bitince sıradakini söyle; takvimle çakışanları, geciken ve duran projeleri fark et.
- Kullanıcının verdiği kalıcı talimatları ve tercihleri remember aracıyla hafızaya almak ve her zaman uygulamak.

Çalışma kuralları:
- Bağlamda <durum> içinde verilen veriler gerçektir; tahmin uydurma. Bilmediğin bir şey için aracı çağır ya da açıkça bilmediğini söyle.
- İstenen işi yapmak için kullanıcıdan gereksiz onay isteme; makul varsayımla ilerle ve ne yaptığını söyle. Yalnızca silme gibi geri alınamaz işlemler request_deletion ile onaya gider.
- Bir araç hata verirse sessiz geçme: neyin olmadığını ve kullanıcının nasıl düzeltebileceğini kısaca söyle (ör. "Takvim iznin kapalı, Ayarlar'dan açabilirsin").
- Tarihleri kullanıcının yerel saatine göre ver; "yarın", "cuma akşamı" gibi ifadeleri <simdi> bilgisine göre çöz.
- Kimlik (id) değerlerini asla kullanıcıya okuma.

Konuşma üslubu (cevapların sesli okunur):
- Türkçe, doğal, sakin, güven veren ve profesyonel konuş; kısa cümleler kur. Robot gibi liste okuma.
- Markdown, madde işareti, emoji, tablo veya başlık kullanma; düz konuşma metni yaz.
- Varsayılan olarak 2-4 cümle. Kullanıcı özet, brifing ya da detay isterse biraz daha uzun olabilir ama yine akıcı paragraf halinde.
- Kullanıcıya hitap tercihine uy. "Efendim" ifadesini ara sıra ve doğal yerde kullan, her cümlede değil.
- Ne yaptığını geçmiş zamanla net söyle ("Takvimine cuma 19:00 için spor ekledim"), sonra gerekiyorsa sıradaki adımı öner.
- "Bugün neler oldu / ne yaptın" sorularında <bugun_yapilanlar> ve gerekirse get_activity verisini kullan: neler ilerledi, ne tamamlandı, Jarvis ne yaptı, ne bekliyor, önemli bir sorun var mı, şu an en önemli konu ne.`;

export interface SnapshotInput {
  state: State;
  now: Date;
  events: CalEvent[];
  alerts: Alert[];
}

export function buildSnapshot({ state: s, now, events, alerts }: SnapshotInput): string {
  const L: string[] = [];
  L.push(`<simdi>${trDate(now)} ${trTime(now)} (${localStamp(now)})</simdi>`);

  const p = s.profile;
  L.push(
    `<kullanici>Ad: ${p.name || 'belirtilmedi'}; hitap: ${p.address || 'adıyla veya nazikçe'}; şehir: ${p.city || '-'}; sabah brifingi: ${p.briefTime}</kullanici>`,
  );
  if (p.rules.trim()) L.push(`<kurallar_ve_calisma_sekli>\n${p.rules.trim()}\n</kurallar_ve_calisma_sekli>`);

  if (s.memories.length)
    L.push(`<hafiza>\n${s.memories.map((m) => `- [${m.id}] (${m.kind}) ${m.text}`).join('\n')}\n</hafiza>`);

  const active = s.projects.filter((x) => x.status === 'aktif' || x.status === 'beklemede');
  L.push(
    `<projeler>\n${
      active.length
        ? active
            .map((x) => {
              const pr = projectProgress(s, x);
              const step = nextStep(s, x);
              return `- [${x.id}] ${x.name} | ${x.status} | aşama: ${STAGE_LABEL[x.stage]} | %${pr.pct} (${pr.done}/${pr.total}) | teslim: ${x.deadline ?? '-'} | son ilerleme: ${x.lastProgressAt.slice(0, 10)} | sıradaki: ${step?.title ?? 'belirlenmedi'} | hedef: ${x.goal || '-'}`;
            })
            .join('\n')
        : '(aktif proje yok)'
    }\n</projeler>`,
  );
  const closed = s.projects.filter((x) => x.status === 'tamamlandi' || x.status === 'iptal');
  if (closed.length) L.push(`<kapanan_projeler>${closed.map((x) => `${x.name} [${x.id}] (${x.status})`).join('; ')}</kapanan_projeler>`);

  const open = s.tasks.filter((t) => t.status !== 'tamam');
  const sorted = [...open].sort((a, b) => (a.due ?? '9999').localeCompare(b.due ?? '9999') || a.priority - b.priority);
  L.push(
    `<acik_gorevler toplam="${open.length}">\n${
      sorted.length
        ? sorted
            .slice(0, 50)
            .map((t) => {
              const pj = t.projectId ? s.projects.find((x) => x.id === t.projectId)?.name : undefined;
              return `- [${t.id}] ${t.title} | ö${t.priority}${t.due ? ` | ${t.due} (${relativeDue(t.due, now)})` : ''}${isOverdue(t, now) ? ' | GECİKTİ' : ''}${pj ? ` | proje: ${pj}${t.stage ? '/' + STAGE_LABEL[t.stage] : ''}` : ' | kişisel'}${t.recurrence ? ` | tekrar: ${t.recurrence}` : ''}${t.status !== 'acik' ? ` | ${t.status}` : ''}`;
            })
            .join('\n')
        : '(açık görev yok)'
    }\n</acik_gorevler>`,
  );

  const upcoming = events.filter((e) => new Date(e.end).getTime() >= now.getTime()).slice(0, 20);
  L.push(
    `<takvim_7_gun>\n${
      upcoming.length
        ? upcoming
            .map((e) => `- [${e.id}] ${dayKey(new Date(e.start))} ${e.allDay ? 'tüm gün' : `${trTime(new Date(e.start))}-${trTime(new Date(e.end))}`} ${e.title}${e.location ? ` @ ${e.location}` : ''}`)
            .join('\n')
        : '(etkinlik yok veya takvim erişimi yok)'
    }\n</takvim_7_gun>`,
  );

  const focus = focusList(s, now, events, 5);
  if (focus.length) L.push(`<oncelik_motoru>\n${focus.map((f) => `- ${f.title} — ${f.reason}`).join('\n')}\n</oncelik_motoru>`);

  if (alerts.length) L.push(`<uyarilar>\n${alerts.map((a) => `- (${a.level}) ${a.text}${a.fix ? ` → çözüm: ${a.fix}` : ''}`).join('\n')}\n</uyarilar>`);
  if (s.pending.length) L.push(`<onay_bekleyen>\n${s.pending.map((x) => `- ${x.summary}`).join('\n')}\n</onay_bekleyen>`);

  const today = dayKey(now);
  const todays = s.activity.filter((a) => dayKey(new Date(a.at)) === today);
  L.push(
    `<bugun_yapilanlar>\n${
      todays.length ? todays.slice(-40).map((a) => `- ${trTime(new Date(a.at))} [${a.actor}] ${a.text}`).join('\n') : '(bugün henüz kayıt yok)'
    }\n</bugun_yapilanlar>`,
  );
  if (s.meta.lastBrief && s.meta.lastBriefDate === today) L.push(`<sabah_brifingi>${s.meta.lastBrief}</sabah_brifingi>`);

  const turns = s.conversation.slice(-10);
  if (turns.length)
    L.push(
      `<son_konusmalar>\n${turns.map((t) => `${t.role === 'user' ? 'Kullanıcı' : 'Jarvis'} (${localStamp(new Date(t.at)).replace('T', ' ')}): ${t.text}`).join('\n')}\n</son_konusmalar>`,
    );

  return `<durum>\n${L.join('\n')}\n</durum>`;
}

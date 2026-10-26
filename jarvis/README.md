# JARVIS — kişisel AI asistanı (Android / iOS)

Kişisel düzenini ve aynı anda yürüttüğün projeleri (fikirden teslime) takip eden, senin yerine iş yapan ve seninle Türkçe sesli konuşan mobil asistan.

## Ne yapar?

| Alan | Jarvis'in yaptığı |
|---|---|
| **Konuşma** | Orb'a dokun (ya da "Hey Jarvis" de), Türkçe konuş. Jarvis niyeti anlar, gerçek verilere bakar, gerekiyorsa işlemi yapar ve sesli cevap verir. |
| **Projeler** | Bir fikri anlattığında projeye çevirir: amaç, aşamalar (Fikir → Planlama → Yapım → Kontrol → Teslim) ve ilk somut görevler. Aşamadaki görevler bitince projeyi kendisi bir sonraki aşamaya geçirir. |
| **Kişisel düzen** | Görevler, tekrarlayan alışkanlıklar (spor, İngilizce…), bugün / geciken / yaklaşan listeleri, notlar. |
| **Takvim** | Telefonun takvimini okur (Google / iCloud / Outlook hesapların buraya senkron olur), etkinlik ekler. |
| **Takip** | Uygulama açıkken 5 dakikada bir, kapalıyken işletim sisteminin izin verdiği aralıklarla: gecikenleri, yaklaşan teslimleri, duran projeleri, yaklaşan etkinlikleri yakalar ve bildirim gönderir. |
| **Sabah brifingi** | Ayarladığın saatte günün özetini hazırlar ve bildirim olarak gönderir; ana ekranda "Dinle" ile okutabilirsin. |
| **Hafıza** | "Bundan sonra…", "unutma ki…" dediklerini kalıcı olarak hatırlar ve her kararında uygular. |
| **Araştırma** | Gerektiğinde internette arama yapar (proje araştırması, bilgi). |
| **Güvenlik** | Silme gibi geri alınamaz işlemleri doğrudan yapmaz; ana ekranda onay kartı açar. |
| **Hata yönetimi** | Bir bağlantı koparsa (takvim izni, internet, API anahtarı…) panelde ve konuşmada açıkça söyler, nasıl düzeltileceğini gösterir. |

## Mimari

```
Ses (tr-TR konuşma tanıma, mikrofon seviyesi → orb)
   ↓
Jarvis ajanı (Claude, araç kullanımı)  ←  canlı durum: görevler, projeler, takvim, uyarılar, hafıza, bugün yapılanlar, son konuşmalar
   ↓ araçlar
Görev / proje / not / hafıza (telefonda SQLite)   Takvim (expo-calendar)   Bildirim / hatırlatma   Hava (Open-Meteo)   Web araması
   ↓
Cevap → ses (ElevenLabs varsa premium, yoksa cihazın Türkçe sesi) → orb konuşma modu (gerçek ses süresine bağlı)
```

| Klasör | İçerik |
|---|---|
| `src/core/` | Platformdan bağımsız çekirdek: veri modeli, iş kuralları (`ops.ts`), öncelik motoru, izleyici (`watcher.ts`), brifing, Claude ajanı (`agent.ts`, `tools.ts`, `prompt.ts`). Testler `__tests__/`. |
| `src/services/` | Telefon bağlantıları: takvim, bildirim, konuşma tanıma, ses, kalıcı depo, arka plan görevi. (`*.web.ts` yalnızca tarayıcı önizlemesi içindir.) |
| `src/runtime/` | Uygulamanın çalışma zamanı: konuşma akışı, izleme döngüsü, orb durumu. |
| `src/app/` | Ekranlar (Expo Router): Komuta, Planım, Projeler, Notlar, Ayarlar. |
| `src/ui/` | Orb, cam paneller, tema, menü. |

Sunucu yoktur: tüm veri telefonda kalır, API anahtarları telefonun şifreli deposunda (Keychain / Keystore) tutulur.

## Kurulum (Android, bilgisayardan bir kez)

1. [Node.js 20+](https://nodejs.org) kur.
2. Bu klasörde: `npm install`
3. Ücretsiz bir [Expo hesabı](https://expo.dev/signup) aç, sonra: `npx eas-cli@latest login`
4. APK üret: `npx eas-cli@latest build -p android --profile preview`
   (Derleme Expo'nun bulut sunucularında yapılır, ~15 dk. Sonunda verilen bağlantıdan APK'yı telefona indirip kur.)

iPhone için: `npx eas-cli@latest build -p ios --profile preview` — bunun için ücretli Apple Developer hesabı gerekir.

> Expo Go uygulaması yeterli değildir; konuşma tanıma ve takvim modülleri için bu derleme gerekir.

## İlk açılış

1. Jarvis bildirim ve takvim izni ister → izin ver.
2. **Ayarlar → Bağlantılar → Claude API anahtarı**: [console.anthropic.com](https://console.anthropic.com) → API Keys → anahtar oluştur → yapıştır → **Test et ve kaydet**. (Test gerçek bir istek gönderir; "Bağlantı tamam" görmelisin.)
3. Orb'a dokun, mikrofon iznini ver ve konuş: "Bugün neler oldu?"
4. İsteğe bağlı:
   - **Ayarlar → Sen ve çalışma şeklin:** hitap şekli, şehir, brifing saati ve kuralların (ör. "TÜBİTAK teslimi her şeyden önce gelir").
   - **Ayarlar → Ses → Premium ses:** [elevenlabs.io](https://elevenlabs.io) anahtarı. Girersen Jarvis çok daha doğal bir sesle konuşur; girmezsen telefonun Türkçe sesi kullanılır. Telefonun ayarlarından "gelişmiş/yüksek kalite" Türkçe ses paketini indirmek de kaliteyi artırır.
   - **"Hey Jarvis" ile uyandırma:** uygulama açıkken sürekli dinler.

## Geliştirme ve test

```bash
npm test            # çekirdek testleri (iş akışları, izleyici, araçlar, ajan döngüsü)
npm run test:live   # GERÇEK Claude API ile uçtan uca test (ANTHROPIC_API_KEY gerekir)
npm run typecheck
npx expo lint
npx expo start --web   # tarayıcıda arayüz önizlemesi (takvim/bildirim yalnızca telefonda)
```

## Bilinen sınırlar

- Arka plan izleme sıklığını işletim sistemi belirler (Android'de en az ~15 dk, iOS'ta sistemin uygun gördüğü zaman). Uygulama açıkken izleme 5 dakikada birdir.
- "Hey Jarvis" yalnızca uygulama ön plandayken çalışır (mobil işletim sistemleri arka planda sürekli mikrofon dinlemeye izin vermez).
- Cihaz sesiyle konuşurken Android, kelime zamanlamasını bildirmez; orb'un ritmi sesin gerçek başlangıç/bitiş süresine bağlı sentetik bir dalgadır. ElevenLabs sesinde ritim gerçek ses örneklerinden gelir.
- Jarvis'in cevap süresi internet hızına ve sorunun karmaşıklığına bağlıdır (çoğunlukla birkaç saniye).

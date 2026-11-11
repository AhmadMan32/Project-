# FitAI Koç 🏋️

Tarayıcıda çalışan, kurulum gerektirmeyen fitness takip uygulaması. `index.html` dosyasını açman yeterli.

## 📱 Android uygulaması (APK)
Hazır APK: [`release/FitAI-Koc.apk`](release/FitAI-Koc.apk)

1. APK'yı telefona indir.
2. Aç; Android "bilinmeyen kaynaklardan yükleme" izni isterse tarayıcına / dosya yöneticine izin ver.
3. Yükle ve aç. İlk açılışta uygulama seni tanımak için 11 soru sorar.

## İlk açılış: soru-cevap ile kişiselleştirme
Uygulama adım adım şunları sorar: ad, cinsiyet ve yaş, boy ve kilo, hedef (kas / yağ yakımı / güç / form),
deneyim seviyesi, haftada kaç gün ve kaç dakika, ekipman (salon / evde dumbbell / ekipmansız),
**programın nasıl oluşturulacağı**, öncelikli kaslar (en fazla 3), sakatlıklar (omuz / bel / diz / bilek) ve kreatin kullanımı.

### İki plan modu
| | 🤖 Benim için oluştur | ✍️ Kendim seçeceğim |
|---|---|---|
| Hareketleri kim seçer | Uygulama | Kullanıcı |
| Plan kontrolü | Plan, kendi kontrolünden geçene kadar otomatik dengelenir | Her değişiklikte eksik / fazla / riskli noktalar gösterilir |
| Düzenleme | "Düzenle" ile kullanıcının planına dönüşür | Gün ekle/sil, hareket ekle/çıkar/sırala, set ve tekrar değiştir |

Mod istenildiği zaman Program sekmesinin üstünden değiştirilebilir.

### Plan kontrolü (iki modda da aynı kurallar)
- Kas başına haftalık set: hedefe, seviyeye, önceliklere ve **haftalık süreye** göre kişisel aralık; ön/arka vücut haritasında renkli
- Denge: itme/çekme, ön/arka omuz, quad/hamstring, üst vücut/bacak, core
- Sakatlığı zorlayan ve ekipmanla yapılamayan hareketler
- Seans süresi (set × ~2.5 dk) söylenen süreyi aşıyor mu, tek seansta bir kasa çok set var mı
- 4+ günlük planlarda aynı kasın art arda iki gün yüklenmesi, büyük kasların haftada tek günde kalması
- Her uyarının yanında tek dokunuşla düzeltme: "+ Barbell Row → 2. Gün", "− 1 set azalt", "Plandan çıkar"
- Hareket seçicide "Eksiklerim" filtresi: hangi hareketin hangi eksiği kapattığını, hangisinin zaten fazla olan kası yüklediğini gösterir

Analiz sekmesi ayrıca **plana uyumu** gösterir: bu hafta planlanan setlerin ne kadarı yapıldı, planda olup hiç yapılmayan kaslar.

Cevaplara göre uygulama şekillenir:
- **Program:** Gün sayısına göre bölünme seçilir (2-3 gün tüm vücut, 4 gün üst/alt, 5 gün PPL + üst/alt, 6 gün PPL×2). Hareketler ekipmanına göre seçilir, sakatlığını zorlayanlar çıkarılır, set/tekrar hedefe ve seviyeye göre ayarlanır, hareket sayısı süreye göre belirlenir. "Sıradaki" gün işaretlenir; "Kaydet" ile hareket set sayısı hazır şekilde açılır.
- **Kas hedefleri:** Haftalık set aralıkları seviyene göre ölçeklenir, öncelikli kaslara ek hacim verilir; analiz ve kas haritası bu kişisel hedeflere göre renklenir.
- **Beslenme:** Protein hedefe göre (kas 2.0, yağ yakımı 2.2, güç 1.8, form 1.6 g/kg), kalori Mifflin-St Jeor ile, kreatin seçimine göre (başlıyorsan ilk hafta yükleme). Kreatin kullanmıyorsan takibi gizlenir.
- **Hareket listesi:** Varsayılan olarak sadece sana uygun hareketler gösterilir; riskli olanlar ⚠️ ile işaretlenir.

Ayarlar > "Soruları yeniden yanıtla" ile her şeyi tekrar şekillendirebilirsin.

## Özellikler
- **Antrenman kaydı:** 180+ hazır hareket (göğüs, sırt, omuz, kol, bacak, karın, tüm vücut; 60+ makine hareketi "Machine …" adıyla), arama ("machine fly", "makine göğüs" gibi) ve kategori / "Makine" filtresi, set başına kilo × tekrar. Son seferin ağırlıkları otomatik dolar, yeni rekorda bildirim çıkar. Kendi hareketini kas seçerek ekleyebilirsin.
- **AI analiz ve kas haritası:** Ön/arka vücut modelinde her kas son 7 günün set sayısına göre renklenir (gri: çalışmadı, turuncu: yetersiz, yeşil: ideal, kırmızı: fazla). Analiz motoru şunları söyler:
  - fazla çalışan veya hiç çalışmayan kaslar ve onlar için önerilen hareketler
  - itme/çekme, ön/arka omuz, quad/hamstring ve üst/alt vücut dengesizlikleri
  - dinlenmesi gereken kaslar (son 48 saat) ve haftalık frekans önerileri
  - hareket bazında gelişme, plato ya da düşüş (tahmini 1RM ile)
- **Claude ile detaylı değerlendirme (isteğe bağlı):** Ayarlar'a Anthropic API anahtarını girersen veriler Claude'a gönderilir; kişisel haftalık program ve beslenme önerisi gelir.
- **Gelişim:** Hareket başına tahmini 1RM / en ağır set / toplam hacim grafiği, kişisel rekor (PR) tablosu, vücut ağırlığı grafiği.
- **Beslenme:** Kilo ve hedefe göre günlük protein (bulk 2.0, koruma 1.8, cut 2.2 g/kg), kreatin (5 g veya yükleme fazında 0.3 g/kg) ve su hedefi. Hızlı yiyecek butonları, kreatin serisi, son 7 gün geçmişi.
- **Veri:** Her şey tarayıcıda (localStorage) saklanır. JSON olarak dışa/içe aktarılabilir. Denemek için "Örnek veri yükle" butonu var.

## Geliştirme
Web dosyaları `www/` içinde; tarayıcıda denemek için `www/index.html` dosyasını açman yeterli.

APK'yı yeniden derlemek için Node 22+, JDK 21 ve Android SDK (platform 36) gerekir:
```bash
cd fitness-app
npm install
npx cap sync android
cd android && ./gradlew assembleDebug
# çıktı: android/app/build/outputs/apk/debug/app-debug.apk
```
GitHub'a her push'ta `.github/workflows/android-apk.yml` APK'yı otomatik derler; Actions sekmesindeki çalıştırmanın "Artifacts" bölümünden indirilebilir.

## Görseller
Hareket animasyonları, kas haritası ve vücut çizimleri uygulamanın kendi çizimleridir (SVG, `www/js/exanim.js`, `www/js/body.js`); dış kaynaklı fotoğraf ya da video kullanılmaz.

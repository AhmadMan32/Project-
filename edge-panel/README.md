# Kenar Panel

Ekranın kenarında her zaman ince bir çubuk durur. Çubuğu içeri çekince panel parmağınızla birlikte kenardan çıkar; bıraktığınızda yaylanarak açılır. Panelde uygulama kısayolları ve üç tuşlu bir **LG TV kumandası** bulunur: **Güç**, **HDMI 1** ve **HDMI 2**. Çubuğun yerini sürükleyerek siz belirlersiniz.

<img src="docs/tutamak.png" width="190" alt="Kenardaki çubuk"> <img src="docs/cekme-1.png" width="190" alt="Çekerken: panel parmağı takip ediyor"> <img src="docs/cekme-2.png" width="190" alt="Çekerken: içerik sırayla geliyor"> <img src="docs/panel.png" width="190" alt="Panel açık">

<img src="docs/panel-sag.png" width="190" alt="Sağ kenar"> <img src="docs/ayarlar.png" width="190" alt="Ayarlar"> <img src="docs/ayarlar-koyu.png" width="190" alt="Ayarlar, koyu tema">

## Kurulum (APK)

1. [`apk/KenarPanel-1.1.apk`](apk/KenarPanel-1.1.apk) dosyasını telefona indirin (78 KB). Dosyayı açın; Android "bilinmeyen kaynaklardan yükleme" izni isterse verin. 1.0 sürümü kuruluysa üzerine kurulur.
2. Uygulamayı açıp **Başlat**'a dokunun. **Diğer uygulamaların üzerinde göster** iznini açıp geri dönün.
3. **Xiaomi / Redmi / POCO:** İzinler bölümündeki Xiaomi satırına dokunun. **Arka planda çalışırken açılır pencereleri göster** ve **Otomatik başlatma** seçeneklerini açın. Bunlar kapalıysa panelden uygulama açılamaz.

Android 6.0 ve üzeri sürümlerde çalışır. Başka bir uygulama gerekmez.

## Kullanım

- **Paneli açmak:**
  - Kenardaki çubuğu içeri çekin; panel parmağınızı takip eder.
  - Yarıdan fazla çekip bırakırsanız açılır. Hızlıca fiskelemek de yeter.
  - Az çeker veya geri çekerseniz kapanır.
  - Çubuğa dokunmak da paneli açar.
- **Paneli kapatmak:** Boş alana dokunun veya geri tuşuna basın; panel kenara kayarak kapanır.
- **Çubuğu taşımak:** Çubuğu yukarı veya aşağı sürükleyin; yeri kaydedilir. Ayarlardan sol veya sağ kenar, uzunluk, kalınlık ve görünürlük seçilebilir. **Konumu kilitle** ile yanlışlıkla kayması önlenir.
- **Uygulamalar:** Simgeye dokununca uygulama açılır. **+** veya ayarlardaki **Uygulamaları seç** ile en fazla 12 uygulama seçilir; işaretleme sırası paneldeki sıradır.
- **LG TV kumandası:** Telefonun üst kenarını TV'ye doğru tutun.
  - **Güç**, LG kumandasındaki güç tuşunun kodunu gönderir: TV açıksa kapanır, kapalıysa açılır.
  - **HDMI 1** ve **HDMI 2**, LG'nin doğrudan giriş kodlarını gönderir.
  - Üç tuş ayarlar ekranından da denenebilir.

## Animasyonlar

- **Çekerken:** Panel parmağın tam altındadır, gecikme yoktur. Tam açık konumu geçince lastik gibi zorlaşır. Bırakınca yay hareketiyle yerine oturur (yaklaşık %3'lük küçük bir sekme); kapanırken hızlanarak kenara kayar.
- **İçerik sırayla gelir:** Önce kumandanın satırları, sonra uygulama simgeleri; ekrana giriş sırasına göre kayarak ve belirerek gelir. Çubuk, panel geldikçe solar.
- **Tuşlar:** Basınca hafifçe küçülür, bırakınca esneyerek geri döner. Dalga efekti parmağın dokunduğu noktadan yayılır. Kod, parmak dokunduğu anda gönderilir; animasyonu beklemez.
- **Çubuk:** Tutulunca kalınlaşır ve parlaklaşır; bırakınca yaylanarak eski haline döner.
- **Arka plan:** Panel geldikçe kararır. Android 12 ve üzerinde arka plan ayrıca bulanıklaşır (telefon pencere bulanıklığını destekliyorsa).
- **Hız:** Ayarlardan **Hızlı** (varsayılan), **Orta**, **Yavaş** veya **Kapalı** seçilir. Telefonun geliştirici seçeneklerindeki animasyon ölçeği de dikkate alınır.
- **90 FPS:**
  - Animasyonlar her ekran yenilemesinde bir adım ilerler; 90 Hz ekranda saniyede 90 kare oynar.
  - Çekerken ve panel açıkken ekrandan 90 Hz istenir. Ekranda 90 Hz modu yoksa ondan yüksek en düşük mod (ör. 120 Hz) istenir; panel kapanınca normale döner.
  - Ekranın kaç Hz desteklediği ayarlar ekranında yazar.
  - 60 Hz ekranlarda animasyonlar 60 FPS oynar.

## TV kodları

Sinyaller uygulamanın içinde NEC protokolüyle üretilir (LG adresi 0x04, 38 kHz) ve Android'in standart `ConsumerIrManager` arayüzüyle gönderilir.

| Tuş | Kod |
|---|---|
| Güç (aç/kapat) | `0x20DF10EF` |
| HDMI 1 | `0x20DF738C` |
| HDMI 2 | `0x20DF33CC` |

## Nasıl test edildi?

`./gradlew testReleaseUnitTest` 16 test çalıştırır:

- **`IrTest` (3 test):**
  - Üretilen sinyaller geri çözülüp yukarıdaki LG kodlarıyla birebir karşılaştırıldı.
  - NEC çerçeve yapısı doğrulandı: 9 ms + 4,5 ms başlık, 32 bit, durma biti.
  - Taşıyıcı frekansı telefonun aralığına uyduruluyor.
- **`MotionTest` (5 test):**
  - Yay eğrisi hemen hareket ediyor, %1–5 sekip duruyor.
  - İçerik sırayla geliyor.
  - Çekme sonunda açma/kapama kararı: yarı yol ve fiske hızı.
  - Lastik etkisi çalışıyor.
  - 90/120 Hz seçimi doğru.
- **`PanelTest` (8 test, Robolectric, gerçek servis, 90 Hz kare aralığıyla):**
  - Dokununca panel gerçekten kare kare açılıyor: 30 ms sonra yolun ortasında, sonra tam açık. Geri tuşuyla kayarak kapanıyor ve pencere kalkıyor.
  - Çekerken panel parmağın tam altında: %30 çekişte %30 açık. Çubuk soluyor ve içerik sırayla geliyor.
  - Yavaş bırakınca kapanıyor, yarıyı geçince açılıyor. Hızlı fiske çeyrek yolda bile açıyor.
  - Tam açıktan sonra çekmek lastik gibi zorlaşıyor. "Kapalı" hız ayarında panel animasyonsuz açılıp kapanıyor.
  - Çubuk tutulunca kalınlaşıyor ve bırakınca geri dönüyor. Sürüklenince yeri değişiyor, kilitliyken kıpırdamıyor.
  - Güç, HDMI 1 ve HDMI 2 tuşları dokunur dokunmaz doğru LG kodunu gönderiyor. Tuş basınca küçülüyor ve geri yaylanıyor.
  - Kızılötesi verici yoksa panel bunu yazıyor ve tuşlar kapalı oluyor.
  - Ayarlar ekranı, hız seçimi, tuş denemeleri ve uygulama seçici çalışıyor.

Android Lint 0 hata verdi. Panel, çekme anındaki kareler ve ayarlar ekranı çizdirilip kontrol edildi.

**Test edilemeyenler:**
- Bu ortamda gerçek telefon ve TV olmadığı için animasyonların akıcılığı gerçek ekranda izlenemedi.
- Arka plan bulanıklığı bu ortamda çizdirilemiyor.
- HDMI kodları gerçek bir LG TV'de denenemedi. Güç kodu, LG kumandasının standart kodudur.

## Bilinen sınırlamalar

- **HDMI tuşları:** HDMI 1/2 kodları LG'nin "doğrudan giriş" kodlarıdır ve normal kumandada yoktur. Çoğu LG TV bu kodları tanır; tanımayan eski modellerde HDMI tuşları çalışmayabilir.
- **Kızılötesi verici:** Kumanda yalnızca kızılötesi vericisi olan telefonlarda çalışır. Birçok Xiaomi, Redmi ve POCO modelinde bu verici vardır.
- **90 Hz:** Ekranın 90 Hz veya üzerini desteklemesi gerekir. Bazı telefonlar (ör. Xiaomi'nin "akıllı yenileme hızı" ayarı) uygulama isteğine rağmen ekran hızını kendi belirler.
- **Bulanıklık:** Android 12 öncesinde, pil tasarrufu modunda veya pencere bulanıklığını desteklemeyen telefonlarda arka plan yalnızca kararır.
- **İmza:** APK debug anahtarıyla imzalıdır. Farklı bir ortamda derlenen sürüm üzerine kurulmazsa önce eskisini kaldırın.

## Kaynaktan derleme

```bash
cd edge-panel
export ANDROID_HOME=/android/sdk/yolu
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
./gradlew testReleaseUnitTest    # testler (Robolectric)
```

| Dosya | Görev |
|---|---|
| `core/Ir.java`, `core/Remotes.java` | NEC sinyal üretimi, LG güç/HDMI kodları (saf Java) |
| `core/Motion.java` | Yay eğrisi, sıralı geliş, lastik etkisi, açma kararı, 90 Hz seçimi (saf Java) |
| `Tween.java` | Ekran yenilemesine bağlı animasyon adımları |
| `OverlayService.java`, `HandleView.java` | Çubuk, parmağı takip eden çekme, fiske, bulanıklık, 90 Hz isteği |
| `Panel.java`, `PanelRoot.java`, `Remote.java` | Hazır bekleyen panel, ilerleme tabanlı animasyon, tuşlar |
| `MainActivity.java`, `AppPickerActivity.java` | Ayarlar, hız seçimi, tuş denemesi, uygulama seçimi |

# Kulaklık Kapat (Disable Headphone)

Telefon kulaklık takılı sansa bile sesi telefonun **kendi hoparlöründen** çıkaran Android uygulaması.

Kulaklık girişi bozulunca ya da içine nem veya toz kaçınca telefon kulaklık takılı sanabilir ve hoparlörü susturur. Bu uygulama kulaklık çıkışını yazılımla devre dışı bırakır ve tüm sesleri hoparlöre gönderir. Root gerekmez.

<img src="docs/ekran.png" width="300" alt="Uygulama ekranı">

## Kurulum (APK)

1. [`apk/KulaklikKapat-1.3.apk`](apk/KulaklikKapat-1.3.apk) dosyasını telefona indirin.
   - **1.0 sürümü yüklüyse önce onu kaldırın.** Yeni sürüm farklı bir anahtarla imzalandığı için 1.0'ın üzerine kurulamaz. 1.1 ve sonrasının üzerine doğrudan kurulur.
2. Dosyayı açın. Android "bilinmeyen kaynaklardan yükleme" izni isterse verin.
3. **Yükle**'ye dokunun.

Android 6.0 ve üzeri sürümlerde çalışır.

## Kullanım

- Büyük **BAŞLAT** düğmesine dokunun. Kulaklık algılandığında ses otomatik olarak hoparlöre geçer.
- **Test sesi çal** ile sesin nereden çıktığını kontrol edin.
- Bildirim panelindeki Hızlı Ayarlar'a **Kulaklık Kapat** kutucuğunu ekleyerek uygulamayı tek dokunuşla açıp kapatabilirsiniz.
- Uygulama çalışırken bir bildirim görünür. **Durdur** ile her şey normale döner.

### Medya sesi arama sesi gibi çıkıyorsa: kablosuz hata ayıklama (Android 11+)

İletişim modunda Android medya sesini telefon görüşmesi gibi işler (görüşme ekolayzırı, ses işleme, çoğu zaman mono). Sesin normal kalitede çıkması için **Kablosuz hata ayıklama ile medya** yöntemini kullanın. Uygulamanın içinde Shizuku benzeri bir ADB istemcisi var. Başka uygulama, bilgisayar ya da root gerekmez:

1. Geliştirici seçeneklerini açın: *Ayarlar → Telefon hakkında → OS sürümü*'ne 7 kez dokunun.
   - Xiaomi/Redmi'de *Ek ayarlar → Geliştirici seçenekleri* içindeki **USB hata ayıklama (Güvenlik ayarları)** seçeneğini de açın. Bu açık değilse uygulama, telefon yeniden başladığında kablosuz hata ayıklamayı kendisi açma iznini alamaz.
2. Wi-Fi'ye bağlanın.
3. Kulaklık Kapat'ta **Kablosuz hata ayıklama ile eşleştir**'e dokunun. Geliştirici seçenekleri açılır.
4. **Kablosuz hata ayıklama**'yı açın, içine girin ve **Eşleştirme kodu ile cihaz eşleştir**'e dokunun. 6 haneli bir kod çıkar.
5. Kod penceresi açıkken bildirim panelini aşağı çekin. *Kulaklık Kapat: eşleştirme kodu* bildiriminde **Kodu gir**'e dokunup kodu yazın.
6. "Eşleştirme başarılı" bildirimi gelir. Uygulamada yöntem olarak **Kablosuz hata ayıklama ile medya**'yı (ya da *Otomatik*) seçin. Durum satırında *Hazır ✓*, "Kullanılan yöntem" satırında *Kablosuz hata ayıklama ile medya* görünmelidir.

Nasıl çalışır: uygulama telefonun kendi `adbd` servisine `127.0.0.1` üzerinden TLS ile bağlanır ([libadb-android](https://github.com/MuntashirAkon/libadb-android)). Bu bağlantıyla `app_process` üzerinden küçük bir yardımcı (`ForceCli`) başlatır. Yardımcı ADB kabuk kullanıcısı (uid 2000) olarak çalışır ve `AudioSystem.setForceUse(FOR_MEDIA, FORCE_SPEAKER)` çağrısını yapar. Kabuk kullanıcısında `MODIFY_AUDIO_ROUTING` izni olduğu için audioserver çağrıyı kabul eder.

Notlar:

- Android, telefon yeniden başlayınca kablosuz hata ayıklamayı kapatır. Uygulama ilk bağlantıda kendine `WRITE_SECURE_SETTINGS` iznini verir (`pm grant`). Böylece sonraki açılışlarda Wi-Fi bağlıysa kablosuz hata ayıklamayı kendisi açıp yeniden bağlanır. Eşleştirme bir kez yapılır.
- Bağlantı yokken (Wi-Fi kapalı vb.) uygulama, ses kesilmesin diye geçici olarak iletişim moduna geçer. Bağlantı gelince kendiliğinden geri döner.
- Bazı üretici yazılımları bu çağrıyı ADB yetkisiyle de reddedebilir. O durumda durum satırında hata kodu yazar ve iletişim modu kullanılmaya devam eder.

### Ses tuşları

- **Kablosuz hata ayıklama yönteminde** telefon normal modda kalır. Ses tuşları Android'in kendi davranışıyla çalışır: arama yokken medya sesini, arama sırasında arama sesini değiştirir.
- **İletişim modunda** Android telefonu görüşmede sandığı için tuşlar arama sesini değiştirir. **Ses tuşları medya sesini ayarlasın** seçeneği küçük bir erişilebilirlik servisi açar (`VolumeKeyService`). Servis yalnızca ses tuşu basışlarını görür. Uygulama iletişim modundayken ve gerçek bir arama yokken tuşu medya sesine yönlendirir. Gerçek arama şöyle anlaşılır: telefon araması (çalıyor ya da görüşmede) ya da başka bir uygulamanın görüşme mikrofonunu kullanması (WhatsApp, Telegram vb.). Arama sırasında tuşa dokunmaz, tuş arama sesini değiştirir.
- Android iletişim modunda medya sesini arama sesiyle sınırlar. Bu yüzden uygulama gerekirse arama sesini medya sesine kadar yükseltir ve mod kapanınca eski değerine döndürür.
- Eşleştirme yapıldıysa uygulama erişilebilirlik servisini kendisi açar. Yapılmadıysa Erişilebilirlik ayarlarından açılır. *Kısıtlanmış ayar* uyarısı çıkarsa: *Uygulama bilgisi → ⋮ → Kısıtlanmış ayarlara izin ver*.

### Ayarlar

| Ayar | Açıklama |
|---|---|
| Yönlendirme yöntemi | *Otomatik* cihaza uygun yöntemi seçer. Ses çıkmazsa diğer yöntemi deneyin. |
| Ses tuşları medya sesini ayarlasın | İletişim modunda, arama yokken ses tuşları medya sesini değiştirir. Arama sırasında arama sesini değiştirir. |
| Kulaklık algılanmasa da hoparlöre zorla | Kulaklık listede görünmese bile hoparlörü zorlar. |
| Bluetooth kulaklıkları da devre dışı bırak | Bluetooth kulaklıkları da kulaklık sayar. |
| Telefon açılınca otomatik başlat | Telefon yeniden başlayınca uygulama kendiliğinden açılır. |
| Pil kısıtlamasını kaldır | Bazı telefonlar (Xiaomi, Huawei, Samsung…) arka plandaki uygulamaları kapatır. Bunu önlemek için kullanın. |

## Nasıl çalışır?

| Android sürümü | Yöntem | Ayrıntı |
|---|---|---|
| 6.0 – 8.1 | **Medya yönlendirme** | Gizli `AudioSystem.setForceUse(FOR_MEDIA, FORCE_SPEAKER)` çağrısıyla medya sesi doğrudan hoparlöre gönderilir. Bu sürümlerde yalnızca `MODIFY_AUDIO_SETTINGS` izni yeterlidir. Ses kalitesi en iyi bu yöntemdedir. |
| 11 ve üzeri + kablosuz hata ayıklama | **Kablosuz hata ayıklama ile medya** | Aynı `setForceUse(FOR_MEDIA, FORCE_SPEAKER)` çağrısı, uygulamanın kendi kablosuz hata ayıklama bağlantısıyla ADB kabuğu (uid 2000) olarak başlattığı küçük bir yardımcıdan (`ForceCli`) yapılır. Kabuk kullanıcısında `MODIFY_AUDIO_ROUTING` izni olduğu için audioserver çağrıyı kabul eder. Medya normal stereo yolunda kalır, ses kalitesi bozulmaz. |
| 8.0 ve üzeri (12, 13, 14, 15… dahil) | **İletişim modu** | Telefon `MODE_IN_COMMUNICATION` moduna alınır ve iletişim cihazı olarak hoparlör seçilir (Android 12+ `setCommunicationDevice`, öncesinde `setSpeakerphoneOn`). Android ses motoru, "görüşme" sırasında tüm medya seslerini görüşme yönlendirmesine bağlar, bu yüzden her uygulamanın sesi hoparlörden çıkar. Android 12+ sessiz kalan uygulamanın modunu sıfırladığı için arka planda sessiz bir ses kanalı açık tutulur. |

Android 9'dan itibaren `setForceUse` için sistem uygulamalarına özel `MODIFY_AUDIO_ROUTING` izni gerekir. Bu yüzden yeni telefonlarda kablosuz hata ayıklama bağlantısı yoksa iletişim modu kullanılır. Bu davranış AOSP kaynak koduna (`AudioPolicyInterfaceImpl.cpp`, `enginedefault/Engine.cpp`, `AudioService.java`) bakılarak doğrulandı.

Arka plandaki servis kulaklık takma/çıkarma ve ses modu değişikliklerini dinler. Ayrıca yönlendirmeyi birkaç saniyede bir yeniden kontrol eder.

### Bilinen sınırlamalar

- **İletişim modunda ses düğmeleri arama sesini değiştirir** (*Ses tuşları medya sesini ayarlasın* açık değilse). Android, görüşme sırasında medya sesini arama sesiyle sınırlar. Uygulama bu sınırı gerekirse kendisi yükseltir.
- İletişim modunda medya sesi görüşme yolundan geçtiği için bazı telefonlarda mono veya biraz daha düşük kalitede olabilir.
- Gerçek bir telefon görüşmesi sırasında uygulama ses modunu değiştirmez. Görüşme sesi için arama ekranındaki hoparlör düğmesini kullanın.
- APK bu derleme ortamının hata ayıklama (debug) anahtarıyla imzalıdır. Başka bir ortamda derlenmiş yeni bir sürüm, eskisinin üzerine kurulmayabilir. Bu durumda önce eski sürümü kaldırın.

## Kaynaktan derleme

Gereksinimler: JDK 17+ ve Android SDK (platform 35, build-tools 35).

```bash
cd KulaklikKapat
export ANDROID_HOME=/android/sdk/yolu
./gradlew assembleRelease
# Çıktı: app/build/outputs/apk/release/app-release.apk
```

Dış kütüphaneler: `libadb-android` (JitPack) ve `conscrypt-android`. APK yaklaşık 7 MB'tır (çoğu ADB eşleştirmesi için gereken şifreleme kodu).

### Dosyalar

| Dosya | Görev |
|---|---|
| `AudioRouter.java` | Hoparlöre zorlama mantığı (iki yöntem, kulaklık algılama) |
| `SpeakerService.java` | Ön plan servisi: algılama, yeniden uygulama, bildirim |
| `MainActivity.java` | Ana ekran, test sesi, ses seviyeleri, ayarlar |
| `SpeakerTileService.java` | Hızlı Ayarlar kutucuğu |
| `BootReceiver.java` | Açılışta / güncellemeden sonra otomatik başlatma |

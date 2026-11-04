# Kulaklık Kapat (Disable Headphone)

Telefon kulaklık takılı sansa bile sesi telefonun **kendi hoparlöründen** çıkaran Android uygulaması.

Kulaklık girişi bozulunca ya da içine nem veya toz kaçınca telefon kulaklık takılı sanabilir ve hoparlörü susturur. Bu uygulama kulaklık çıkışını yazılımla devre dışı bırakır ve tüm sesleri hoparlöre gönderir. Root gerekmez.

<img src="docs/ekran.png" width="300" alt="Uygulama ekranı">

## Kurulum (APK)

1. [`apk/KulaklikKapat-1.0.apk`](apk/KulaklikKapat-1.0.apk) dosyasını telefona indirin.
2. Dosyayı açın. Android "bilinmeyen kaynaklardan yükleme" izni isterse verin.
3. **Yükle**'ye dokunun.

Android 6.0 ve üzeri sürümlerde çalışır.

## Kullanım

- Büyük **BAŞLAT** düğmesine dokunun. Kulaklık algılandığında ses otomatik olarak hoparlöre geçer.
- **Test sesi çal** ile sesin nereden çıktığını kontrol edin.
- Bildirim panelindeki Hızlı Ayarlar'a **Kulaklık Kapat** kutucuğunu ekleyerek uygulamayı tek dokunuşla açıp kapatabilirsiniz.
- Uygulama çalışırken bir bildirim görünür. **Durdur** ile her şey normale döner.

### Ayarlar

| Ayar | Açıklama |
|---|---|
| Yönlendirme yöntemi | *Otomatik* cihaza uygun yöntemi seçer. Ses çıkmazsa diğer yöntemi deneyin. |
| Kulaklık algılanmasa da hoparlöre zorla | Kulaklık listede görünmese bile hoparlörü zorlar. |
| Bluetooth kulaklıkları da devre dışı bırak | Bluetooth kulaklıkları da kulaklık sayar. |
| Telefon açılınca otomatik başlat | Telefon yeniden başlayınca uygulama kendiliğinden açılır. |
| Pil kısıtlamasını kaldır | Bazı telefonlar (Xiaomi, Huawei, Samsung…) arka plandaki uygulamaları kapatır. Bunu önlemek için kullanın. |

## Nasıl çalışır?

| Android sürümü | Yöntem | Ayrıntı |
|---|---|---|
| 6.0 – 8.1 | **Medya yönlendirme** | Gizli `AudioSystem.setForceUse(FOR_MEDIA, FORCE_SPEAKER)` çağrısıyla medya sesi doğrudan hoparlöre gönderilir. Bu sürümlerde yalnızca `MODIFY_AUDIO_SETTINGS` izni yeterlidir. Ses kalitesi en iyi bu yöntemdedir. |
| 8.0 ve üzeri (12, 13, 14, 15… dahil) | **İletişim modu** | Telefon `MODE_IN_COMMUNICATION` moduna alınır ve iletişim cihazı olarak hoparlör seçilir (Android 12+ `setCommunicationDevice`, öncesinde `setSpeakerphoneOn`). Android ses motoru, "görüşme" sırasında tüm medya seslerini görüşme yönlendirmesine bağlar, bu yüzden her uygulamanın sesi hoparlörden çıkar. Android 12+ sessiz kalan uygulamanın modunu sıfırladığı için arka planda sessiz bir ses kanalı açık tutulur. |

Android 9'dan itibaren `setForceUse` için sistem uygulamalarına özel `MODIFY_AUDIO_ROUTING` izni gerekir. Bu yüzden yeni telefonlarda iletişim modu kullanılır. Bu davranış AOSP kaynak koduna (`AudioPolicyInterfaceImpl.cpp`, `enginedefault/Engine.cpp`, `AudioService.java`) bakılarak doğrulandı.

Arka plandaki servis kulaklık takma/çıkarma ve ses modu değişikliklerini dinler. Ayrıca yönlendirmeyi birkaç saniyede bir yeniden kontrol eder.

### Bilinen sınırlamalar

- **İletişim modunda ses düğmeleri arama sesini değiştirir.** Android, görüşme sırasında medya sesini arama sesiyle sınırlar. Ses kısık gelirse ses düğmeleriyle veya uygulamadaki *Arama ses seviyesi* kaydırıcısıyla sesi açın.
- İletişim modunda medya sesi görüşme yolundan geçtiği için bazı telefonlarda mono veya biraz daha düşük kalitede olabilir.
- Gerçek bir telefon görüşmesi sırasında uygulama ses modunu değiştirmez. Görüşme sesi için arama ekranındaki hoparlör düğmesini kullanın.
- APK bu derleme ortamının hata ayıklama (debug) anahtarıyla imzalıdır. Başka bir ortamda derlenmiş yeni bir sürüm, eskisinin üzerine kurulmayabilir. Bu durumda önce eski sürümü kaldırın.

## Kaynaktan derleme

Gereksinimler: JDK 17+ ve Android SDK (platform 35, build-tools 35).

```bash
cd disable-headphone
export ANDROID_HOME=/android/sdk/yolu
./gradlew assembleRelease
# Çıktı: app/build/outputs/apk/release/app-release.apk
```

Proje hiçbir dış kütüphane kullanmaz. Yalnızca Android framework API'leri kullanılır ve APK yaklaşık 50 KB'tır.

### Dosyalar

| Dosya | Görev |
|---|---|
| `AudioRouter.java` | Hoparlöre zorlama mantığı (iki yöntem, kulaklık algılama) |
| `SpeakerService.java` | Ön plan servisi: algılama, yeniden uygulama, bildirim |
| `MainActivity.java` | Ana ekran, test sesi, ses seviyeleri, ayarlar |
| `SpeakerTileService.java` | Hızlı Ayarlar kutucuğu |
| `BootReceiver.java` | Açılışta / güncellemeden sonra otomatik başlatma |

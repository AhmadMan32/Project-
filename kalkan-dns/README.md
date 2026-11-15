# Kalkan DNS 🛡️

1.1.1.1 uygulamasına benzer bir Android uygulaması: **tek dokunuşla** telefondaki tüm
DNS sorgularını **DNS-over-HTTPS (DoH)** ile şifreler. Böylece internet sağlayıcınız ya da
bulunduğunuz ağ hangi siteleri ziyaret ettiğinizi DNS üzerinden göremez veya değiştiremez.

## Özellikler
- Büyük aç/kapat düğmesi (BAĞLI / BAĞLI DEĞİL)
- DNS sağlayıcısı seçimi:
  - Standart **1.1.1.1**
  - Kötü amaçlı yazılım engelleme **1.1.1.2**
  - Aile koruması **1.1.1.3** (zararlı + yetişkin içerik)
  - Reklam engelleme (AdGuard), Quad9, Google
- Canlı istatistik: sorgu sayısı, ortalama gecikme, son sorgular listesi
- Hızlı ayarlar kutucuğu (bildirim panelinden aç/kapat)
- Bildirimden "Bağlantıyı kes" düğmesi
- Android'in **Her zaman açık VPN** özelliği desteği
- Hiçbir harici kütüphane yok, APK ~40 KB

## Nasıl çalışır?
Uygulama yerel bir VPN (`VpnService`) açar, ama **yalnızca DNS trafiğini** bu tünele alır:
sisteme DNS sunucusu olarak sahte bir adres (`10.111.222.2`) verilir ve sadece bu adres
tünelden yönlendirilir. Gelen her UDP/53 sorgusu HTTPS üzerinden (RFC 8484) seçilen DoH
sunucusuna gönderilir, yanıt tekrar pakete dönüştürülüp uygulamaya verilir. Diğer tüm
internet trafiği tünele hiç girmez, bu yüzden hız kaybı olmaz.

> Not: 1.1.1.1 uygulamasındaki WARP gibi tüm trafiği şifreleyen bir VPN değildir;
> bu bir sunucu altyapısı gerektirir. Kalkan DNS, uygulamanın "1.1.1.1 (DNS)" modunun karşılığıdır.

## Kurulum
1. `release/KalkanDNS.apk` dosyasını telefona indirin.
2. "Bilinmeyen kaynaklardan yükleme" iznini verin ve kurun.
3. Uygulamayı açıp düğmeye dokunun, VPN iznini onaylayın.

Android 8.0 (API 26) ve üzeri gerekir.
Not: Android'deki **Özel DNS** ayarı açıksa onu "Kapalı" ya da "Otomatik" yapın.

## Derleme
```bash
echo "sdk.dir=/android/sdk/yolu" > local.properties
./gradlew assembleRelease          # APK: app/build/outputs/apk/release/
./gradlew testReleaseUnitTest      # birim testleri
KALKAN_NET_TEST=1 ./gradlew testReleaseUnitTest   # canlı DoH testi dahil
```
Release APK kolay kurulum için debug anahtarıyla imzalanır; Play Store'a yüklemek için
`app/build.gradle.kts` içinde kendi imza anahtarınızı tanımlayın.

## Kod yapısı
| Dosya | Görev |
|---|---|
| `MainActivity.kt` | Ana ekran, sağlayıcı seçimi, istatistikler |
| `BigSwitch.kt` | Animasyonlu büyük düğme |
| `DnsVpnService.kt` | VPN tüneli, paket okuma döngüsü, bildirim |
| `DohClient.kt` | DNS-over-HTTPS istemcisi |
| `Packets.kt` | IPv4/UDP paket ayrıştırma ve yanıt oluşturma |
| `ToggleTileService.kt` | Hızlı ayarlar kutucuğu |
| `Providers.kt` | DoH sağlayıcı listesi |

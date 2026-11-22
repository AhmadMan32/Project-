# Kalkan DNS 🛡️

1.1.1.1 uygulamasına benzer bir Android uygulaması. İki modu var:

- **1.1.1.1 modu:** telefondaki tüm DNS sorgularını **DNS-over-HTTPS (DoH)** ile şifreler.
  İnternet sağlayıcınız hangi siteleri ziyaret ettiğinizi DNS üzerinden göremez.
- **WARP modu:** **tüm internet trafiğini** WireGuard tüneliyle şifreler (tam VPN).

## Özellikler
- Büyük aç/kapat düğmesi (BAĞLI / BAĞLI DEĞİL), üstte **1.1.1.1 / WARP** mod seçimi
- DNS sağlayıcısı seçimi:
  - Standart **1.1.1.1**
  - Kötü amaçlı yazılım engelleme **1.1.1.2**
  - Aile koruması **1.1.1.3** (zararlı + yetişkin içerik)
  - Reklam engelleme (AdGuard), Quad9, Google
- Canlı istatistik: sorgu sayısı, ortalama gecikme, son sorgular listesi
  (WARP modunda indirilen / yüklenen veri)
- WARP yapılandırmasını dosyadan, metin yapıştırarak ya da "Paylaş / Birlikte aç" ile içe aktarma
- Hızlı ayarlar kutucuğu (bildirim panelinden aç/kapat)
- Bildirimden "Bağlantıyı kes" düğmesi
- Android'in **Her zaman açık VPN** özelliği desteği

## Nasıl çalışır?
Uygulama yerel bir VPN (`VpnService`) açar, ama **yalnızca DNS trafiğini** bu tünele alır:
sisteme DNS sunucusu olarak sahte bir adres (`10.111.222.2`) verilir ve sadece bu adres
tünelden yönlendirilir. Gelen her UDP/53 sorgusu HTTPS üzerinden (RFC 8484) seçilen DoH
sunucusuna gönderilir, yanıt tekrar pakete dönüştürülüp uygulamaya verilir. Diğer tüm
internet trafiği tünele hiç girmez, bu yüzden hız kaybı olmaz.

### WARP modu
WARP modu resmi WireGuard kütüphanesini (`com.wireguard.android:tunnel`, wireguard-go) kullanır.
Bu modda telefonun tüm trafiği şifrelenip bir WireGuard sunucusuna gider ve internete oradan çıkar.
Bunun için bir **WireGuard yapılandırması (.conf)** gerekir. Şuralardan alabilirsiniz:

1. **Kendi sunucunuz (önerilen):** Herhangi bir Ubuntu/Debian VPS üzerinde
   ```bash
   sudo bash server/warp-sunucu-kur.sh
   ```
   çalıştırın. Betik WireGuard'ı kurar ve `/etc/wireguard/kalkan-warp.conf` dosyasını üretir.
   Bu dosyayı telefona aktarıp uygulamada **WARP → Dosyadan içe aktar** deyin.
   Sunucunun güvenlik duvarında UDP 51820 portunu açmayı unutmayın.
2. **WireGuard destekleyen bir VPN sağlayıcısı:** Sağlayıcının verdiği .conf dosyasını içe aktarın.

Yapılandırmadaki `DNS =` satırı tünel içinde kullanılır (betik 1.1.1.1 yazar).

## Kurulum
1. `release/KalkanDNS.apk` dosyasını telefona indirin.
2. "Bilinmeyen kaynaklardan yükleme" iznini verin ve kurun.
3. Uygulamayı açıp düğmeye dokunun, VPN iznini onaylayın.

Android 8.0 (API 26) ve üzeri gerekir. APK boyutu ~14 MB (WireGuard motoru tüm işlemci türleri için dahil).
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
| `WarpManager.kt` | WARP (WireGuard) tüneli, yapılandırma saklama, trafik istatistiği |
| `Connection.kt` | İki modu yönetir, aynı anda yalnızca biri açık olur |
| `server/warp-sunucu-kur.sh` | Kendi WARP sunucunuzu kuran betik |

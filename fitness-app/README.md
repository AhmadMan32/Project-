# FitAI Koç 🏋️

Tarayıcıda çalışan, kurulum gerektirmeyen fitness takip uygulaması. `index.html` dosyasını açman yeterli.

## Özellikler
- **Antrenman kaydı:** 80+ hazır hareket (göğüs, sırt, omuz, kol, bacak, karın, tüm vücut), arama ve kategori filtresi, set başına kilo × tekrar. Son seferin ağırlıkları otomatik dolar, yeni rekorda bildirim çıkar. Kendi hareketini kas seçerek ekleyebilirsin.
- **AI analiz ve kas haritası:** Ön/arka vücut modelinde her kas son 7 günün set sayısına göre renklenir (gri: çalışmadı, turuncu: yetersiz, yeşil: ideal, kırmızı: fazla). Analiz motoru şunları söyler:
  - fazla çalışan veya hiç çalışmayan kaslar ve onlar için önerilen hareketler
  - itme/çekme, ön/arka omuz, quad/hamstring ve üst/alt vücut dengesizlikleri
  - dinlenmesi gereken kaslar (son 48 saat) ve haftalık frekans önerileri
  - hareket bazında gelişme, plato ya da düşüş (tahmini 1RM ile)
- **Claude ile detaylı değerlendirme (isteğe bağlı):** Ayarlar'a Anthropic API anahtarını girersen veriler Claude'a gönderilir; kişisel haftalık program ve beslenme önerisi gelir.
- **Gelişim:** Hareket başına tahmini 1RM / en ağır set / toplam hacim grafiği, kişisel rekor (PR) tablosu, vücut ağırlığı grafiği.
- **Beslenme:** Kilo ve hedefe göre günlük protein (bulk 2.0, koruma 1.8, cut 2.2 g/kg), kreatin (5 g veya yükleme fazında 0.3 g/kg) ve su hedefi. Hızlı yiyecek butonları, kreatin serisi, son 7 gün geçmişi.
- **Veri:** Her şey tarayıcıda (localStorage) saklanır. JSON olarak dışa/içe aktarılabilir. Denemek için "Örnek veri yükle" butonu var.

## Çalıştırma
```bash
cd fitness-app
python3 -m http.server 8000   # ya da index.html'i doğrudan aç
```

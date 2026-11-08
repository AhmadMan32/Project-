# 🧬 Mutant Lab — Oyun Tasarım Belgesi (Taslak v0.1)

> Kanca: **"İki hayvanı birleştir, dünyada olmayan bir yaratık yarat."**
> Hedef kitle: Yurt dışı (oyun dili İngilizce), 9–16 yaş, mobil + PC.

## 1. Temel Mantık
- Topladığın mutantlar **laboratuvarındaki kafeslerde pasif para üretir**.
- **Nadirlik arttıkça kazanç katlanarak artar.**
- **Oyunda yokken de kazanç birikir** (varsayılan 8 saat sınır; gamepass ile 24 saat).
- Girişte: *"While you were away, your lab earned 48,200 💰!"*

## 2. Oyun Döngüsü
1. **Yakala** — Haritada hayvanları ağla yakala (uzak bölge = nadir hayvan).
2. **Birleştir** — İki hayvanı Birleştirme Makinesi'ne at → rastgele nadirlikte mutant.
3. **Yerleştir** — Mutantı kafese koy → saniyede para.
4. **Genişlet** — Kafes satın al / zayıf mutantı sat.
5. **Güçlen** — Daha iyi ağ (menzil), makine (nadirlik şansı), hız.
6. **Keşfet** — Yeni bölge kilidi aç → yeni hayvanlar → yeni kombinasyonlar.

## 3. Nadirlik Tablosu (ilk denge taslağı)
| Nadirlik | Örnek | 💰/sn | Şans |
|---|---|---|---|
| ⚪ Common | Chicken-Frog | 1 | %60 |
| 🟢 Rare | Cat-Shark | 5 | %25 |
| 🔵 Epic | Bear-Bee | 25 | %10 |
| 🟣 Legendary | Tiger-Eagle | 150 | %4 |
| 🟡 Mythic | Dragon-Octopus | 1.000 | %0,9 |
| 🔴 Secret | ??? | 10.000 | %0,1 |

**Mutasyonlar** (her mutantta küçük şans): ✨ Shiny x2 · 🥇 Golden x5 · 🔥 Blazing x10

## 4. Sistemler
- **Fusion Machine** — 2 hayvan → mutant. Seviyesi nadirlik şansını artırır.
- **Triple Fuse** — Aynı mutanttan 3 tane → bir üst nadirlik.
- **Mutant Index** — Bulunmamışlar "???" görünür; bölge tamamlanınca ödül.
- **Rebirth** — Para sıfırlanır, mutantlar kalır, kalıcı gelir çarpanı.
- **Kafes Kırma (PvP)** — Rakibin kafes kilidini kır → mutant kaçar, ilk yakalayan alır.
  Koruma: kafes kilidi yükseltmesi, laboratuvar kalkanı (süreli).

## 5. Bölgeler (ilk sürüm)
1. 🌾 Farm — tavuk, inek, kurbağa, kedi
2. 🌲 Forest — ayı, tilki, baykuş, arı
3. 🌊 Ocean — köpekbalığı, ahtapot, yengeç
4. 🏜️ Desert — akrep, deve, yılan
5. 🌋 Volcano — semender, ejderha (nadir)

## 6. Para Kazanma (Robux)
| Ürün | Tür | Fikir fiyat |
|---|---|---|
| 2x Money | Gamepass | 199 R$ |
| Offline 24h | Gamepass | 149 R$ |
| Golden DNA (efsanevi şansı) | Developer Product | 49 R$ |
| Extra Cage Row | Gamepass | 99 R$ |
| Lab Shield (8 saat) | Developer Product | 25 R$ |
| VIP (özel ağ + isim rengi) | Gamepass | 299 R$ |

İlke: Ücretsiz oyuncu da ilerleyebilmeli; Robux **hızlandırır**, zorunlu değildir.

## 7. Canlı Etkinlikler
- **Mutation Storm** — 15 dk boyunca mutasyon şansı x3 (saatlik).
- **Admin Lab Night** — haftalık canlı etkinlik, özel gizli mutant.
- Mevsimlik: Halloween (Ghost-mutantlar), Christmas (Reindeer-mutantlar).

## 8. Teknik Notlar (Claude + ForgeGUI + Roblox Studio MCP)
- Modeller/ikonlar: ForgeGUI (ForgeMCP ile doğrudan yayın).
- Veri: DataStoreService (mutantlar, para, son çıkış zamanı → offline kazanç hesabı).
- Offline kazanç = `min(şimdi - sonÇıkış, sınır) × toplam💰/sn`.
- Güvenlik: tüm para/şans hesapları sunucuda (exploit'e karşı).

## 9. Sonraki Adımlar
- [ ] Roblox'ta benzer oyun var mı kontrol et (isim ve mekanik).
- [ ] İlk 10 hayvan + ilk 20 mutant listesini çıkar.
- [ ] Bilgisayarda ForgeGUI + Claude + Studio bağlantısını kur.
- [ ] Prototip: 1 bölge, yakalama, birleştirme, kafes, pasif gelir, kayıt.

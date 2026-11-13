Sen deneyimli bir Roblox oyun geliştiricisisin. Roblox Studio'ya MCP üzerinden bağlısın (script okuma/yazma, Luau çalıştırma, oyun ağacını düzenleme, playtest başlatma/durdurma, ekran görüntüsü alma). Ayrıca ForgeGUI'a MCP üzerinden bağlısın (GUI, ikon, görsel, 3D model, ses ve müzik üretip Roblox'a yayınlama). Görevin: aşağıda TAMAMI tarif edilen "Flip a Car" adlı Roblox oyununu, hiçbir bölümünü atlamadan, kısaltmadan ve kendi kafana göre değiştirmeden, eksiksiz ve çalışır şekilde inşa etmek.

==================================================
0. ÇALIŞMA KURALLARI (ÖNCE BUNLARI OKU)
==================================================

1. İşe başlamadan önce iki bağlantıyı da doğrula: Roblox Studio'da hangi place açık (adını ve PlaceId'sini söyle) ve ForgeGUI hesabına erişebiliyor musun. Biri çalışmıyorsa dur ve bana söyle.
2. Oyunu aşağıdaki "15. İNŞA SIRASI" bölümündeki fazlara göre, sırayla inşa et. Her fazın sonunda:
   - Playtest başlat, fazın kabul kriterlerini tek tek test et, Output penceresinde hata olmadığını kontrol et, gerekiyorsa ekran görüntüsü al.
   - Bana kısa bir rapor ver: ne yapıldı, neler test edildi, sonuç ne, bilinen sorun var mı.
   - Hata varsa düzeltmeden bir sonraki faza geçme.
3. Mevcut bir şeyi silmeden veya üzerine yazmadan önce ne olduğuna bak ve bana sor. Place boş değilse önce içinde ne olduğunu raporla.
4. Bu belgede yazan her sayı, isim, oran ve kural bağlayıcıdır. Bir şey teknik olarak imkânsızsa veya çelişkiliyse, kendi başına değiştirme; sorunu ve önerini bana yaz.
5. Oyun içindeki TÜM metinler İngilizce olacak (hedef kitle ABD/Avrupa). Kod yorumları İngilizce olabilir.
6. Bütün kod Luau ile, `--!strict` mümkün olduğunca kullanılarak, modüler yazılacak. Tüm denge sayıları tek bir yerde (Config modülleri) duracak; script içine sayı gömme.
7. Gerçek araba markası, model adı, logo veya ticari marka KULLANMA. Tüm arabalar özgün tasarım ve özgün isim.
8. Önce her şeyi basit yer tutucu (placeholder) parçalarla çalışır hale getir; görsel varlıkları (ForgeGUI) mekanik çalıştıktan sonra, ilgili fazda ekle.

==================================================
1. OYUNUN ÖZETİ
==================================================

Ad: Flip a Car
Kanca (oyun açıklaması ve küçük resim mesajı): "Buy rusty wrecks, repair them, and turn your gallery into a supercar empire."

Tek cümle: Oyuncu hurdalıktan paslı arabalar alır, tamir eder (tamirde arabanın gerçek nadirliği ortaya çıkar), galerisine koyar ve galerideki arabalar oyuncu oyunda yokken bile saniye başı para (kira) kazandırır. Oyuncu bu parayla daha pahalı bölgelere geçer, daha nadir arabalar bulur, arabalarını sürer ve yarışır.

Temel duygular:
- "Bu paslı arabadan ne çıkacak?" sürprizi (nadirlik tamirde açığa çıkar).
- Oyundan çıkıp geri gelince "sen yokken bu kadar kazandın" ödülü (ceza değil, ödül; oyunda ÇALMA YOK).
- Nadir arabanın parlayarak sergilenmesiyle gelen gurur.
- Arabayı sürmenin ve yarışmanın eğlencesi.

Hedef: 9 yaş ve üstü, özellikle 13–25. Görsel stil: parlak, temiz, stilize (low-poly ama kaliteli), hem çocuklara hem gençlere/yetişkinlere hoş görünen bir estetik.

==================================================
2. HARİTA VE ARSA (PLOT) SİSTEMİ
==================================================

- Sunucu kapasitesi: 8 oyuncu. Haritada 8 adet oyuncu arsası (Plot) var.
- Oyuncu girince boş bir arsa otomatik atanır; çıkınca arsa temizlenir ve boşa çıkar.
- Her arsada:
  - Bir GARAJ (tamir alanı): aynı anda 1 araba tamir edilir.
  - Bir GALERİ: arabaların sergilendiği, numaralı park yerleri (slotlar). Başlangıçta 4 slot görünür, satın alınan slotlar sırayla açılır/görünür olur.
  - Arsanın girişinde oyuncunun adını ve "Income: X/s" yazan bir tabela (BillboardGui veya SurfaceGui).
- Arsaların ortasında ortak alan (spawn) var. Spawn'dan 5 bölgeye yollar çıkar.
- Bölgeler (zone): her bölge haritada ayrı bir alandır; kilitli bölgenin girişinde bariyer ve fiyat tabelası vardır. Kilit oyuncuya özeldir (bölgeyi açan oyuncu girebilir, açmayan giremez; giremeyen oyuncuyu bariyer nazikçe geri iter ve "Unlock for X" mesajı çıkar).
- Her bölgede bir "Wreck Lot" (hurdalık satış noktası) vardır: ProximityPrompt ile "Buy Wreck – X Cash".
- Bölge 3'te (Desert Speedway) bir yarış pisti (kapalı devre, checkpoint'li) ve yarış başlangıç alanı bulunur.
- Spawn alanında: "Grand Auction" sahnesi (açık artırma podyumu) ve Index (koleksiyon) panosu.

==================================================
3. TEMEL DÖNGÜ
==================================================

1. Oyuncu açtığı bir bölgenin Wreck Lot'undan "gizemli hurda" (Wreck) satın alır. Hurdanın nadirliği satın alırken BİLİNMEZ; hurda paslı, gri, tozlu görünür.
2. Hurda oyuncunun garajına gelir. Garaj doluysa (zaten tamirde araba varsa) satın alma reddedilir: "Your garage is busy!".
3. Oyuncu garajda "Repair" butonuna basar, tamir ücretini öder ve TAMİR MİNİ OYUNUNU oynar (bkz. 6).
4. Tamir bitince nadirlik ve olası mutasyon SUNUCUDA belirlenir, dramatik bir "reveal" animasyonuyla gösterilir (renkli ışık patlaması, nadirlik adı, araba adı, kazanç).
5. Oyuncu karar verir:
   - "Display" → arabayı boş bir galeri slotuna koyar; araba saniye başı kira kazandırmaya başlar.
   - "Sell" → arabayı anında satar ve satış değerini alır.
   Galeride boş slot yoksa "Display" pasif görünür ve "Gallery full! Buy more slots or sell a car." uyarısı çıkar.
6. Galerideki bir arabaya tıklanınca (ClickDetector/ProximityPrompt) küçük panel açılır: "Drive", "Sell", "Move to Storage" (depo yok; bu buton olmayacak, sadece Drive ve Sell). Satış onay ister.
7. Para birikir → daha fazla hurda, daha fazla slot, yeni bölge, rebirth.

==================================================
4. NADİRLİK, ŞANS VE MUTASYON
==================================================

Altı nadirlik seviyesi:

| Nadirlik | Renk (UI/ışık) | Taban şans | Taban kazanç çarpanı |
|---|---|---|---|
| Common | Gri/beyaz (#C8C8C8) | %55 | 1 |
| Uncommon | Yeşil (#4CD964) | %25 | 5 |
| Rare | Mavi (#3FA9F5) | %12 | 25 |
| Epic | Mor (#A066FF) | %6 | 125 |
| Legendary | Altın (#FFC83D) | %1.8 | 600 |
| Mythic | Kırmızı-gökkuşağı (#FF3B5C + rainbow efekt) | %0.2 | 3000 |

Şans (Luck) sistemi:
- Oyuncunun bir "luck" değeri vardır (varsayılan 0).
- Luck kaynakları: yarıştan kazanılan "Rare Part" (tek kullanımlık, bir sonraki tamirde +0.5 luck), "Golden Auction Ticket" ürünü (tek kullanımlık, bir sonraki tamirde +1.0 luck), "Lucky Garage" gamepass'i (kalıcı +0.25 luck).
- Tek kullanımlık luck'lar bir sonraki tamirde otomatik tüketilir ve tamir ekranında "Luck active: +X" yazar.
- Luck uygulanma formülü: Common hariç her nadirliğin şansı `şans × (1 + luck)` ile çarpılır, sonra Common şansı kalan yüzdeyle (100 − diğerlerinin toplamı) yeniden hesaplanır. Common şansı %10'un altına düşemez (sınır).
- Rastgelelik SUNUCUDA `Random.new()` ile yapılır, istemci asla nadirlik belirleyemez.

Mutasyon (tamir sonrası, nadirlikten bağımsız ayrı zar):
| Mutasyon | Şans | Kazanç çarpanı | Görünüm |
|---|---|---|---|
| None | geri kalan | x1 | normal boya |
| Neon | %4 | x2 | parlayan neon boya, hafif ışık |
| Flame | %1 | x5 | alev deseni ve araba etrafında küçük alev parçacıkları |
Luck mutasyon şansını ETKİLEMEZ.

Nadirliğin görünürlüğü (çok önemli, oyunun "güzel" görünmesini sağlar):
- Galeride her slotun zemininde nadirlik renginde ışık halkası.
- Rare ve üstü arabaların üstünde ince parıltı (ParticleEmitter, düşük yoğunluk).
- Legendary ve Mythic arabalar spot ışığı (SpotLight) altında ve yavaşça dönen platformda durur.
- Mythic arabalarda gökkuşağı renk geçişi efekti.
- Her arabanın üstünde küçük BillboardGui: araba adı, nadirlik (renkli), mutasyon varsa etiketi, "+X/s".

==================================================
5. BÖLGELER, FİYATLAR VE EKONOMİ
==================================================

5 bölge:

| # | Bölge (İngilizce ad) | Açma ücreti | Hurda fiyatı | Bölge kazanç çarpanı |
|---|---|---|---|---|
| 1 | Town Junkyard | 0 (başlangıç) | 50 | 1 |
| 2 | City Auction | 5,000 | 1,000 | 8 |
| 3 | Desert Speedway | 150,000 | 20,000 | 64 |
| 4 | Mountain Pass | 4,000,000 | 400,000 | 512 |
| 5 | Luxury Harbor | 100,000,000 | 8,000,000 | 4,096 |

Formüller (Config'de tutulacak, tek yerden değiştirilebilir olacak):
- Araba saniyelik kazancı = nadirlik taban kazanç çarpanı × bölge kazanç çarpanı × mutasyon çarpanı × oyuncu çarpanı.
- Oyuncu çarpanı = (1 + 0.5 × rebirth sayısı) × (2x Rent Money gamepass varsa 2, yoksa 1).
- Tamir ücreti = hurda fiyatının %20'si (yukarı yuvarla).
- Satış değeri = arabanın oyuncu çarpanı HARİÇ saniyelik kazancı × 300 (yani 5 dakikalık kira). Oyuncu çarpanının satışa etkisi olmaması bilinçli bir karardır (rebirth/gamepass satış istismarını önler).
- Başlangıç parası: 100.
- Galeri slotları: başlangıç 4. Ek slot fiyatı = 100 × 2.2^(satın alınan ek slot sayısı), yukarı yuvarla. Normal yolla en fazla 30 slot. "Extra Garage Row" gamepass'i +5 slot verir (maksimum 35) ve bu 5 slot ücretsizdir.
- Para birimi adı: "Cash". Sayı gösterimi kısaltmalı: 1,250 → 1.25K, 3,400,000 → 3.4M, sonra B, T, Qa, Qi (iki ondalık).

30 araba (her bölgede her nadirlikten tam 1 araba, hepsi özgün isim):

Bölge 1 – Town Junkyard
- Common: Rusty Hatch
- Uncommon: Farm Pickup
- Rare: Street Coupe
- Epic: Night Racer
- Legendary: Thunder GT
- Mythic: Golden Classic '59

Bölge 2 – City Auction
- Common: City Sedan
- Uncommon: Delivery Van
- Rare: Turbo Hatch
- Epic: Carbon Wasp
- Legendary: Neon Specter
- Mythic: Chrome Monarch

Bölge 3 – Desert Speedway
- Common: Dune Buggy
- Uncommon: Rally Wagon
- Rare: Sand Striker
- Epic: Mirage RS
- Legendary: Solar Fang
- Mythic: Pharaoh X

Bölge 4 – Mountain Pass
- Common: Snow Crawler
- Uncommon: Alpine Tourer
- Rare: Ridge Drifter
- Epic: Avalanche S
- Legendary: Glacier Hyper
- Mythic: Summit Eclipse

Bölge 5 – Luxury Harbor
- Common: Marina Cruiser
- Uncommon: Harbor Cabrio
- Rare: Harbor Bolt
- Epic: Tidal Vortex
- Legendary: Ocean Crown
- Mythic: Leviathan Zero

Bir hurdadan çıkan araba = o bölgenin, belirlenen nadirlikteki arabası.

Her arabanın sürüş hızı (driving max speed, stud/s) nadirliğe ve bölgeye göre: taban hız Common 60, Uncommon 70, Rare 80, Epic 95, Legendary 110, Mythic 125; her bölge +5 (Bölge 1: +0 … Bölge 5: +20). Mutasyon hızı etkilemez.

==================================================
6. TAMİR MİNİ OYUNU
==================================================

Tamir 3 adımdan oluşur, toplam yaklaşık 10–15 saniye. Mobilde ve PC'de rahat oynanmalı (büyük butonlar, dokunmatik uyumlu).

1. ENGINE (zamanlama): Yatay bir çubukta sağa-sola giden bir gösterge var; ortadaki yeşil alanda "Tap!" butonuna basmak gerekiyor. 3 deneme hakkı. Yeşil alanda basınca adım biter.
2. PAINT (doldurma): Oyuncu "Hold to Paint" butonunu basılı tutar; bir ilerleme çubuğu dolar, araba görseli gri/paslıdan temiz renge geçer (görsel efekt). Dolunca adım biter.
3. TIRES (tıklama): Arabanın 4 tekerleği üzerinde 4 buton belirir, her birine bir kez tıklanır. Dördü tamamlanınca adım biter.

Kurallar:
- Mini oyunda başarısız olmak tamiri iptal ETMEZ; Engine adımında 3 hakkın hepsi kaçarsa adım otomatik geçilir. Mini oyun eğlence içindir, cezalandırma yoktur. Başarı/başarısızlık nadirliği ETKİLEMEZ.
- "Instant Repair" ürünü satın alınmışsa/kullanılırsa mini oyun atlanır.
- Tamir ücreti mini oyun başlarken sunucuda düşülür; para yetmiyorsa mini oyun başlamaz.
- Mini oyun bitince istemci sunucuya "repair finished" bildirir; sunucu bu oyuncunun gerçekten garajında tamir bekleyen bir hurdası olduğunu ve tamirin en az 5 saniye sürdüğünü doğrular, ardından nadirlik ve mutasyonu belirler.
- Reveal animasyonu: araba kapalı bir perde/duman altında, 1.5 saniye nadirlik renklerinde ışık yanıp söner, sonra araba görünür; nadirliğe göre ses efekti (Legendary/Mythic için özel fanfar). Legendary ve Mythic çıkınca sunucudaki herkese bildirim: "[Player] just restored a LEGENDARY Thunder GT!".

==================================================
7. OYUNDA YOKKEN KAZANÇ (OFFLINE EARNINGS)
==================================================

- Oyuncu çıkarken (ve her otomatik kayıtta) `lastSeen` (os.time()) kaydedilir.
- Oyuncu geri gelince: geçen süre = os.time() − lastSeen. Kazanılan = min(geçen süre, sınır) × o anki toplam saniyelik galeri kazancı. Sınır = 8 saat (28,800 sn); "Offline 24h" gamepass varsa 24 saat (86,400 sn).
- Geçen süre 60 saniyeden azsa popup gösterme.
- Popup (İngilizce): "Welcome back! While you were away, your cars earned 52.4K Cash!" + "Collect" butonu. Para sunucuda hesaplanır ve verilir; istemci miktar belirleyemez.
- Sınıra ulaşıldıysa popup'ta küçük not: "Offline earnings are capped at 8h. Get Offline 24h to earn longer!" (gamepass yoksa).
- os.time() geriye gidiyorsa (negatif süre) 0 kabul et.

Koleksiyoncu teklifi (Collector Offer):
- Oyuncu en az 1 saat uzak kaldıysa, geri dönüşte %30 ihtimalle offline popup'ın ardından "A collector visited your gallery!" teklifi çıkar.
- Teklif: oyuncunun açtığı en yüksek bölgeden bir araba; nadirlik şanslarında luck +1.0 uygulanmış gibi zar atılır (mutasyon normal şansla). Araba ve nadirliği teklifte AÇIKÇA gösterilir.
- Fiyat: o bölgenin hurda fiyatı + tamir ücreti toplamının %50'si.
- "Buy" veya "Decline". Teklif 5 dakika geçerli; garaj atlanır, araba doğrudan "Display / Sell" seçimine gelir (galeri doluysa sadece Sell veya bir slot açılana kadar bekleme; bekleme yok, sadece Sell aktif ve uyarı gösterilir).

==================================================
8. SÜRÜŞ VE YARIŞ
==================================================

Sürüş:
- Galerideki araba panelinden "Drive" seçilince araba oyuncunun arsasındaki çıkış noktasında sürülebilir kopyası olarak oluşur (galerideki araba yerinde kalır ve kazanç devam eder).
- Bir oyuncunun aynı anda yalnızca 1 sürülebilir arabası olur; yenisi çağrılınca eskisi silinir.
- Araç VehicleSeat tabanlı basit, sağlam bir şasi kullanır (mobilde ve PC'de çalışmalı; WASD/oklar ve mobil thumbstick). Hız nadirlik/bölgeye göre (bkz. 5).
- Oyuncu araçtan inince 10 saniye sonra araç silinir. Oyuncu çıkınca hemen silinir.
- Sürülebilir araçlar oyuncuya zarar vermez; araçlar oyuncuları itmesin diye çarpışma grupları (CollisionGroup) ayarlanır: araçlar oyuncu karakterleriyle çarpışmaz, araçlar birbirleriyle ve haritayla çarpışır.

Yarış (Desert Speedway, Bölge 3'ü açmış oyuncular katılabilir):
- Pistin başında "Race Lobby" alanı. Bir oyuncu arabayla alana girince yarış kuyruğuna katılır. En az 2 oyuncu olunca 20 saniyelik geri sayım başlar; geri sayım sırasında en fazla 8 oyuncuya kadar katılım olur.
- Yarış: 2 tur, sıralı checkpoint'ler. Checkpoint geçişleri SUNUCUDA doğrulanır (sırayla geçilmesi gerekir, bir sonraki checkpoint'e makul olmayan sürede ulaşılırsa yok sayılır).
- Ödüller: 1. → hurda fiyatı (oyuncunun açtığı en yüksek bölge) × 3 Cash + 2 Rare Part; 2. → × 2 Cash + 1 Rare Part; 3. → × 1 Cash + 1 Rare Part; diğer bitirenler → × 0.5 Cash.
- Bir oyuncu aynı yarış ödülünü saatte en fazla 10 kez alabilir (çiftçilik önleme).
- Yarış 5 dakikada bitmezse bitiremeyenler ödül almaz.
- Yarış UI: geri sayım, tur sayısı, sıra, checkpoint oku.

==================================================
9. REBIRTH
==================================================

- Rebirth şartı: en az Bölge 3 açılmış olmalı ve Cash ≥ rebirth fiyatı.
- Rebirth fiyatı = 10,000,000 × 5^(rebirth sayısı).
- Rebirth yapınca: Cash 100'e döner, bölge kilitleri sıfırlanır (sadece Bölge 1 açık kalır). ARABALAR, galeri slotları, gamepass'ler, Index ve Rare Part'lar KALIR.
- Kalıcı kazanç: her rebirth +%50 (oyuncu çarpanı formülüne bakın).
- Rebirth butonu onay ister ve neyin sıfırlanıp neyin kaldığını açıkça listeler.
- Not: Bölge kilitleri sıfırlandığı için yüksek bölge arabaları galeride kalıp kazanmaya devam eder; oyuncu bölgeleri tekrar açarak ilerler. Bu bilinçli bir tasarımdır.

==================================================
10. INDEX (KOLEKSİYON)
==================================================

- 30 araba × 3 mutasyon durumu (None, Neon, Flame) = 90 kayıt.
- Hiç bulunmamış araba silüet ve "???" olarak görünür. Bulunan araba renkli görünür; mutasyon versiyonları ayrı rozetlerle işaretlenir.
- Bir bölgenin 6 arabasının hepsini (mutasyonsuz) bulunca tek seferlik ödül: o bölgenin hurda fiyatı × 10 Cash.
- Bir bölgenin tüm Neon versiyonlarını bulunca: +%5 kalıcı kazanç (oyuncu çarpanına ayrı çarpan olarak). Flame versiyonları tamamlanınca: +%10 kalıcı. (Bu bonusları oyuncu çarpanına dahil et ve Config'de tut.)
- Index, ekranın solundaki "Index" butonuyla ve spawn'daki panoyla açılır.

==================================================
11. GRAND AUCTION (CUMARTESİ ETKİNLİĞİ)
==================================================

- Her Cumartesi (UTC), her saat başı (:00) bir açık artırma başlar ve 5 dakika sürer. Sunucu saatini `os.date("!*t")` ile UTC olarak kontrol et.
- Her hafta için Config'de bir "Auction Exclusive" araba tanımlıdır (ayrı bir araba listesi; en az 4 haftalık özel araba hazırla: "Midnight Comet", "Crystal Racer", "Aurora GT", "Phoenix One"; hepsi Mythic nadirliğinde, Bölge 5 çarpanıyla, sabit "Neon" mutasyonuyla). Hafta numarasına göre sırayla döner.
- Açık artırma o sunucudaki oyuncular arasındadır. Başlangıç teklifi: Bölge 3 hurda fiyatı × 5. Minimum artış: mevcut teklifin %10'u.
- Teklif veren oyuncunun teklif tutarı sunucuda emanete alınır (Cash'ten düşülür); daha yüksek teklif gelince önceki teklif sahibine iade edilir. Oyuncu çıkarsa teklifi iade edilir ve teklif bir önceki en yüksek teklife (hâlâ sunucuda olan) geri döner.
- Son 30 saniyede gelen her teklif süreyi 15 saniye uzatır (en fazla 2 dakika uzatma).
- Kazanan arabayı alır (garaj atlanır, doğrudan Display/Sell). Sunucuya duyuru: "[Player] won the Grand Auction: Midnight Comet!".
- Auction Exclusive arabalar Index'te ayrı bir "Auction" sekmesinde gösterilir; normal Index tamamlama hesabına dahil değildir.
- Test için Studio'da (RunService:IsStudio()) bir yönetici komutu/buton ile açık artırmayı hemen başlatabilmeliyim.
- Açık artırma UI: arabanın 3D önizlemesi (ViewportFrame), kalan süre, mevcut en yüksek teklif ve sahibi, "Bid +10%" butonu.

==================================================
12. PARA KAZANMA (ROBUX)
==================================================

Gamepass'ler (ID'ler Config'de placeholder olarak 0 bırakılacak; ben gerçek ID'leri gireceğim):
| Ad | Etki | Önerilen fiyat |
|---|---|---|
| 2x Rent Money | Tüm galeri kazancı x2 (offline dahil) | 199 R$ |
| Extra Garage Row | +5 galeri slotu (ücretsiz, max 35) | 99 R$ |
| Offline 24h | Offline kazanç sınırı 24 saat | 149 R$ |
| Lucky Garage | Kalıcı +0.25 luck | 249 R$ |
| Nitro Boost | Sürüşte Shift/mobil butonla 3 sn %40 hız, 15 sn bekleme | 149 R$ |
| VIP | İsim üstünde VIP etiketi, sohbet etiketi, galeri girişinde VIP tabela, +%10 kazanç | 299 R$ |

Developer Product'lar:
| Ad | Etki | Önerilen fiyat |
|---|---|---|
| Instant Repair | Bir sonraki tamirde mini oyun atlanır (stoklanabilir) | 25 R$ |
| Golden Auction Ticket | Bir sonraki tamirde +1.0 luck (tek kullanım, stoklanabilir) | 49 R$ |
| Cash Pack S / M / L | Oyuncunun açtığı en yüksek bölgenin hurda fiyatı × 5 / × 30 / × 200 Cash | 25 / 99 / 399 R$ |

Kurallar:
- Mythic arabalar ve Auction Exclusive arabalar ASLA doğrudan Robux ile satılmaz.
- Golden Auction Ticket şansı etkileyen ücretli bir üründür: Roblox'un ücretli rastgele ürün kuralları gereği satın almadan ÖNCE nadirlik oranları (luck uygulanmış haliyle) açıkça gösterilecek. Tamir ekranında her zaman "Odds" butonu bulunacak ve o anki gerçek şansları gösterecek.
- `MarketplaceService.ProcessReceipt` idempotent olacak: işlenen PurchaseId'ler oyuncu verisinde saklanır, aynı satın alma iki kez verilmez; veri kaydedilemezse `NotProcessedYet` döndürülür.
- Gamepass sahipliği girişte `UserOwnsGamePassAsync` ile kontrol edilir ve oyun içinde satın alınınca (`PromptGamePassPurchaseFinished`) anında uygulanır.
- Mağaza (Shop) UI'ı: gamepass'ler ve ürünler kartlar halinde, her kartta ikon, ad, kısa açıklama, fiyat.
- Fiyatlar Config'de; ileride Roblox bölgesel fiyatlandırmasına uygun olması için fiyat metnini her zaman MarketplaceService'ten okunan gerçek fiyattan göster (sabit yazma).

==================================================
13. VERİ KAYDI (DATASTORE)
==================================================

Oyuncu profil şeması (Luau tablosu, versiyonlu):
- version: number (şema versiyonu, 1'den başla)
- cash: number
- unlockedZones: {number} (en az {1})
- gallerySlots: number (satın alınan toplam slot, 4'ten başlar)
- cars: dizi; her eleman { id: string (benzersiz GUID), carKey: string, zone: number, rarity: string, mutation: string, slot: number? (galeride ise slot no) }
- garage: { wreckZone: number?, repairStartedAt: number? } (tamir bekleyen hurda)
- rebirths: number
- rareParts: number
- pendingLuck: number (tek kullanımlık luck toplamı)
- instantRepairs: number
- index: { [carKey..":"..mutation] = true }
- indexRewardsClaimed: { [string] = true }
- lastSeen: number
- processedPurchases: { [purchaseId] = true } (en son 200 kaydı tut, eskileri sil)
- raceRewardLog: { timestamps } (son 1 saat)
- stats: { totalEarned, carsRepaired, racesWon }

Kurallar:
- `DataStoreService` ile, oturum kilidi (session lock) içeren güvenli bir profil sistemi yaz: profil yüklenirken `UpdateAsync` ile kilit alınır (kilit = JobId + zaman), başka sunucuda açıksa kısa beklemeyle tekrar denenir, 30 dakikadan eski kilit ölü sayılır.
- Otomatik kayıt her 60 saniyede bir, çıkışta ve `game:BindToClose` ile kapanışta.
- Tüm DataStore çağrıları pcall + yeniden deneme (en fazla 3, artan bekleme) ile.
- Veri yüklenemezse oyuncu "Data failed to load, please rejoin" mesajıyla atılır (boş veriyle oynatılıp ilerlemesinin silinmesi KESİNLİKLE engellenir).
- Yeni oyuncuya varsayılan profil verilir. Eski versiyonlu profiller migrate fonksiyonuyla güncellenir.
- Studio'da test için ayrı DataStore adı kullan (örn. "PlayerData_Studio"), canlıda "PlayerData_v1".
- Liderlik tablosu: OrderedDataStore ile "Top Income" (toplam saniyelik kazanç) ve "Most Rebirths"; spawn'da SurfaceGui pano, 5 dakikada bir güncellenir.

==================================================
14. GÜVENLİK VE HİLE ÖNLEME
==================================================

- Sunucu otoriter: para, nadirlik, mutasyon, satın alma, satış, slot, bölge kilidi, offline kazanç, yarış ödülü, açık artırma — hepsi SUNUCUDA hesaplanır ve doğrulanır.
- İstemciden gelen her RemoteEvent/RemoteFunction çağrısında: parametre tipleri doğrulanır, oyuncunun ilgili şeye sahip olduğu kontrol edilir, mesafe kontrolü yapılır (ör. Wreck Lot satın alma için oyuncu en fazla 15 stud uzakta olmalı), hız sınırı (rate limit) uygulanır (oyuncu başına saniyede en fazla 5 çağrı; aşan çağrılar yok sayılır ve loglanır).
- Remote'lar ReplicatedStorage içinde tek bir "Remotes" klasöründe, açık isimlerle.
- İstemciye sadece görüntü için gerekli veri gönderilir (ör. kendi profilinin özeti, değişince güncelleme).
- Para eksiye düşemez; tüm harcama işlemleri "yeterli para var mı" kontrolüyle atomik yapılır.

==================================================
15. İNŞA SIRASI (FAZLAR VE KABUL KRİTERLERİ)
==================================================

FAZ 1 — İskelet ve veri
- Klasör yapısı (bkz. 17), Config modülleri (tüm sayılar), Remotes, profil/DataStore sistemi, sayı formatlama modülü.
- Kabul: Playtest'te oyuncu girince profil yükleniyor, Cash HUD'da görünüyor, çıkıp girince Cash korunuyor (Studio API Services açık), Output'ta hata yok.

FAZ 2 — Harita ve arsalar (placeholder)
- 8 arsa, spawn, 5 bölge alanı, bölge bariyerleri ve tabelaları, Wreck Lot'lar, yarış pisti iskeleti (Bölge 3), açık artırma podyumu, Index panosu, liderlik panosu yerleri. Basit parçalarla ama düzenli ve okunaklı.
- Kabul: Oyuncu girince arsa atanıyor, arsa tabelasında adı yazıyor; kilitli bölgeye girilemiyor ve fiyat mesajı çıkıyor.

FAZ 3 — Temel döngü
- Hurda satın alma, garaj, tamir mini oyunu (3 adım), nadirlik/mutasyon zarı, reveal, Display/Sell, galeri slotları, slot satın alma, saniyelik kazanç, araba BillboardGui'leri, nadirlik ışık efektleri, sunucu duyuruları.
- Kabul: 100 Cash ile başlayan oyuncu Bölge 1'den hurda alıp tamir edebiliyor, araba galeride kazanç üretiyor, satılabiliyor, slot satın alınabiliyor; nadirlik dağılımı Studio'da 10,000 sanal zar testiyle (komut satırında Luau) tablodaki oranlara ±%1 içinde uyuyor; raporla.

FAZ 4 — Bölgeler ve ilerleme
- Bölge açma, bölge çarpanları, rebirth, Index ve ödülleri, liderlik tabloları.
- Kabul: Bölge açılınca bariyer o oyuncu için kalkıyor; rebirth doğru şeyleri sıfırlıyor ve doğru şeyleri koruyor; Index ödülü bir kez veriliyor.

FAZ 5 — Offline kazanç ve koleksiyoncu
- lastSeen, offline hesaplama, popup, koleksiyoncu teklifi.
- Kabul: Studio'da test için lastSeen'i elle geriye çekebileceğim bir debug komutu olsun; 2 saat geri çekince doğru miktar hesaplanıyor; 10 saat geri çekince 8 saat sınırı uygulanıyor; koleksiyoncu teklifi zorlanabilir (debug) ve doğru çalışıyor.

FAZ 6 — Sürüş ve yarış
- Sürülebilir araç sistemi, CollisionGroup'lar, Nitro (gamepass kontrolü sonraki fazda bağlanacak, şimdilik debug ile açılabilir), yarış lobisi, checkpoint'ler, ödüller, yarış UI.
- Kabul: İki oyunculu Studio testinde (Test > Players: 2) yarış başlıyor, checkpoint sırası zorunlu, ödüller doğru dağılıyor, saatlik limit çalışıyor.

FAZ 7 — Grand Auction
- Zamanlayıcı, teklif/emanet/iade, süre uzatma, kazanan, UI, Studio debug başlatma.
- Kabul: İki oyunculu testte teklifler emanete alınıyor, geçilen teklif iade ediliyor, kazanan arabayı alıyor, çıkan oyuncunun teklifi iade ediliyor.

FAZ 8 — Mağaza ve Robux
- Gamepass/ürün Config'i, ProcessReceipt (idempotent), sahiplik kontrolleri, tüm etkilerin bağlanması, Shop UI, Odds ekranı.
- Kabul: Studio'da test satın almalarıyla (Studio test purchase) her ürün doğru etkiyi veriyor ve aynı receipt iki kez işlenmiyor.

FAZ 9 — UI cilası
- Tüm UI'lar mobil uyumlu (UIScale/UIAspectRatioConstraint, büyük dokunma alanları), tutarlı stil, animasyonlu açılış/kapanış (TweenService), bildirim (toast) sistemi, sesler için yer tutucular.
- HUD: sol üstte Cash ve "+X/s"; sağda Shop, Index, Rebirth, Settings butonları; sol altta hedef/ipucu kutusu.
- İlk giriş rehberi (tutorial): ok işareti ve kısa yönergeler: "Buy your first wreck" → "Repair it" → "Display it in your gallery" → "Watch the cash roll in!". Rehber bir kez tamamlanınca profilde işaretlenir.
- Settings: müzik aç/kapa, ses efekti aç/kapa, düşük grafik modu (parçacıkları azaltır).
- Kabul: Studio'nun cihaz emülatöründe telefon ve tablet görünümünde tüm UI'lar okunaklı ve tıklanabilir; ekran görüntüleriyle raporla.

FAZ 10 — ForgeGUI ile görsel ve ses
- Aşağıdaki "16. GÖRSEL VE SES VARLIKLARI" listesini üret ve placeholder'ların yerine koy.
- Kabul: Tüm arabalar, ikonlar ve UI öğeleri yerinde; performans: tipik sunucuda (8 oyuncu, dolu galeriler) Studio MicroProfiler'da ciddi düşüş yok; her mesh için üçgen sayısını raporla.

FAZ 11 — Son kontrol
- Tüm sistemleri baştan sona birlikte test et (yeni oyuncu gibi oyna), Output'u temizle, gereksiz debug çıktılarını kapat (debug komutları sadece Studio'da çalışsın), kısa bir "nasıl yayınlanır" kontrol listesi ve bilinen sorunlar listesi hazırla.

==================================================
16. GÖRSEL VE SES VARLIKLARI (FORGEGUI)
==================================================

Genel stil: parlak, temiz, stilize low-poly; doygun ama göz yormayan renkler; Roblox'ta mobilde iyi görünür. Gerçek marka/logo yok.

3D modeller:
- 30 araba + 4 Auction Exclusive araba. Her araba özgün silüete sahip olsun (bölgenin temasına uygun: kasaba, şehir, çöl, dağ, liman). Nadirlik arttıkça tasarım daha iddialı ve detaylı olsun.
- Her araba için bir "wreck" (hurda) görünümü: aynı model gri, paslı malzeme/doku, eksik parçalı izlenimi. Mümkünse ayrı model yerine aynı modelde materyal/renk değişimiyle yap (performans için).
- Mesh başına hedef üçgen sayısı en fazla 5,000 (Roblox sınırı 20,000; asla aşma). Ölçek Roblox karakterine uygun olsun. Gerekirse modelleri temizle/sadeleştir.
- Harita dekorları: hurdalık yığınları, açık artırma salonu, çöl kayaları ve pist bariyerleri, karlı dağ ağaçları, liman iskelesi ve yatlar, galeri platformları, garaj binası.

2D (ikon ve UI):
- 34 araba ikonu (Index ve UI için, şeffaf arka plan).
- Nadirlik rozetleri (6), mutasyon rozetleri (Neon, Flame).
- Tüm gamepass ve developer product ikonları.
- HUD butonları (Shop, Index, Rebirth, Settings), Cash ikonu, Rare Part ikonu, luck ikonu.
- Oyun ikonu (512×512) ve 3 adet oyun küçük resmi (1920×1080): (1) paslı arabadan parlayan süper arabaya dönüşüm, (2) ışıl ışıl galeri, (3) yarış. Başlık metni: "FLIP A CAR". Küçük resimler oyunun gerçekte gösterdiğini vaat etmeli (yanıltıcı olmamalı).

Ses:
- Lobi müziği (sakin, enerjik), yarış müziği, açık artırma gerilim müziği.
- Efektler: satın alma, tamir adımları (anahtar, sprey, lastik), reveal (nadirliğe göre 3 seviye: normal, nadir, efsane fanfar), para toplama, bölge açma, rebirth, yarış geri sayımı, kazanma, motor sesi.

Ses yüklemelerinde aylık kota olabileceğini unutma; gerekirse önce kritik sesleri üret.

==================================================
17. PROJE YAPISI
==================================================

ReplicatedStorage
- Shared
  - Config
    - CarConfig (30 araba + auction arabaları; carKey, ad, bölge, nadirlik, model referansı, ikon)
    - ZoneConfig (5 bölge; ad, açma ücreti, hurda fiyatı, çarpan)
    - RarityConfig (oranlar, çarpanlar, renkler, hız)
    - MutationConfig
    - EconomyConfig (başlangıç parası, tamir oranı, satış çarpanı, slot formülü, offline sınırları, rebirth formülü, index bonusları)
    - ProductConfig (gamepass/ürün ID'leri ve etkileri)
    - RaceConfig, AuctionConfig
  - Modules
    - NumberFormat
    - IncomeCalc (sunucu ve istemci aynı formülü göstersin diye paylaşılan, saf fonksiyonlar)
- Remotes (tüm RemoteEvent/RemoteFunction'lar)
- Assets (araba modelleri, efektler)

ServerScriptService
- Server
  - Main.server.lua (servisleri başlatır)
  - Services
    - DataService (profil, kilit, kayıt, migrate)
    - PlotService
    - ZoneService
    - WreckService (satın alma, tamir, zar)
    - GalleryService (slot, display, sell, kazanç tick'i)
    - OfflineService (offline kazanç, koleksiyoncu)
    - DriveService
    - RaceService
    - AuctionService
    - RebirthService
    - IndexService
    - ShopService (gamepass, ProcessReceipt)
    - LeaderboardService
    - RateLimiter
    - DebugService (sadece Studio)

StarterPlayer > StarterPlayerScripts
- Client
  - Main.client.lua
  - Controllers (HUDController, RepairMinigameController, RevealController, GalleryController, ShopController, IndexController, RaceController, AuctionController, TutorialController, NotificationController, SettingsController, SoundController)

StarterGui
- Tüm ScreenGui'ler (her biri ayrı, ResetOnSpawn = false)

Kazanç tick'i: sunucuda her 1 saniyede bir, her oyuncunun toplam saniyelik kazancı Cash'e eklenir (her oyuncu için ayrı döngü değil, tek döngü). İstemciye Cash değişimi saniyede en fazla 1 kez gönderilir.

==================================================
18. ROBLOX POLİTİKA UYUMU
==================================================

- Video/medya akışı izletme karşılığı ödül YOK (Roblox çocuk kataloğu kuralları).
- Serbest çizim, serbest metin tabelası vb. kullanıcı içeriği YOK.
- Ücretli rastgele ürünlerde oranlar satın almadan önce gösterilir.
- Yanıltıcı başlık/küçük resim yok.
- Sohbet ve sosyal özellikler Roblox'un kendi sistemleri dışında eklenmez.
- Oyun "Maturity & Compliance" anketinde en düşük olgunluk seviyesine uygun olacak şekilde tasarlanır (şiddet, kan, korku yok).

==================================================
19. TESLİM
==================================================

Tüm fazlar bittiğinde bana şunları ver:
1. Yapılan her şeyin özeti ve proje yapısı.
2. Config'te benim doldurmam gereken yerler (gamepass/ürün ID'leri vb.) listesi.
3. Debug komutlarının listesi ve nasıl kullanılacağı (sadece Studio'da çalıştıklarını doğrula).
4. Bilinen sorunlar ve önerilen sonraki güncellemeler (ilk 4 haftalık Cumartesi güncelleme planı önerisi dahil).
5. Yayın öncesi kontrol listesi: oyun adı, açıklama (İngilizce), ikon, küçük resimler, yaş/uyum anketi, API Services ayarı, 16+ yayın, ileride herkese açma.

Şimdi 0. bölümdeki kurallara uyarak bağlantıları doğrula ve FAZ 1'den başla.

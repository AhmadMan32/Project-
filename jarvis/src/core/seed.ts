// İlk açılışta kullanıcının depoda zaten var olan gerçek projesi içe aktarılır
// (orman-yangin-projesi.zip içindeki README / AGENTS.md yapılacaklar listesi).
// Bunun dışında hiçbir örnek veri yoktur.

import { createProject } from './ops';
import type { Store } from './store';

export function seedIfNeeded(store: Store): boolean {
  if (store.state.meta.seeded) return false;
  createProject(
    store,
    {
      name: 'Orman Yangını – Akıllı Su Hattı (TÜBİTAK)',
      goal:
        'Bölgelere ayrılmış ormanda yangının komşu bölgeye sıçramasını sınırlayan otomatik su püskürtme hatları ve akıllı kontrol stratejisinin hangi koşulda ne kadar iyi çalıştığını deneysel veriyle göstermek.',
      stage: 'yapim',
      tasks: [
        { title: 'Akıllı stratejiyi rüzgârlı koşul için iyileştir (öngörülü tetikleme), H2/H3’ü yeniden test et', stage: 'yapim', priority: 1 },
        { title: 'OSM yakıt ızgarasını sunucuya bağla (rasterize_osm → server.py → Sim)', stage: 'yapim', priority: 2 },
        { title: 'DEM (Copernicus / AWS Terrain) ekle ve eğim etkisini test et', stage: 'yapim', priority: 2 },
        { title: 'Kıvılcım sıçraması ve sensör gecikmesi/arıza modelini ekle', stage: 'yapim', priority: 2 },
        { title: 'Yeni web arayüzü: MapLibre 3D görünüm + simülasyon animasyonu', stage: 'yapim', priority: 3 },
        { title: 'Deneyleri en az 20 tekrarla çalıştır, rapor tablolarını üret', stage: 'kontrol', priority: 1 },
        { title: 'NASA FIRMS ile geçmiş yangın doğrulaması (Marmaris/Manavgat, IoU)', stage: 'kontrol', priority: 2 },
        { title: 'Maket prototip ölçümlerini motor parametrelerine bağla', stage: 'kontrol', priority: 2 },
        { title: 'TÜBİTAK raporunu yaz (özet, yöntem, bulgular, sınırlılıklar)', stage: 'teslim', priority: 2 },
      ],
    },
    'system',
  );
  store.update((s) => {
    s.meta.seeded = true;
    // Kullanıcının paylaştığı JARVIS tasarımında hitap "Ahmet"; Ayarlar'dan değiştirilebilir.
    if (!s.profile.name) s.profile.name = 'Ahmet';
  });
  store.log('system', 'import', 'Depodaki TÜBİTAK projesi ve yapılacaklar listesi içe aktarıldı');
  return true;
}

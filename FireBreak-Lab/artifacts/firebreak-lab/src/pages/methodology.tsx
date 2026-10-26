import { NavBar } from '@/components/bits';
import { useI18n } from '@/lib/i18n';

type Sec = { h: string; p: string[] };

const TR: Sec[] = [
  { h: 'Ne simüle ediyoruz?', p: ['FireBreak Lab, bir çalışma alanını eşit boyutlu hücrelere bölen olasılıksal bir hücresel otomat yangın yayılım modeli kullanır. Amaç, bölgeler arası sprinkler hatlarının farklı açma stratejilerinin yanan alan ve su tüketimi üzerindeki göreli etkisini karşılaştırmaktır. Model, gerçek bir yangının davranışını tahmin etmek için kalibre edilmemiştir.'] },
  { h: 'Veri kaynakları', p: ['Yükseklik: AWS Terrain Tiles (Terrarium kodlaması), zoom 12 karoları; her hücrenin değeri hücre merkezinden örneklenir. Eğim ve bakı, hücre ızgarası üzerinde sonlu farklarla hesaplanır.', 'Yakıt: ESA WorldCover 2021 (v200) 10 m arazi örtüsü; her hücre, merkezine en yakın piksel (nearest neighbour) ile sınıflandırılır; 10 ağaç, 20 çalılık, 30 çayır, 40 tarım, 90 sulak alan, 95 mangrov, 100 yosun yanabilir kabul edilir. Diğer sınıflar (yapı, çıplak, su, kar) yanmaz.', 'Uydu görüntüsü (Esri World Imagery) yalnızca görsel bağlam içindir; modele girdi olarak kullanılmaz.'] },
  { h: 'Yayılım varsayımları', p: ['Zaman adımı 10 saniyedir. Bir hücre tutuştuktan sonra 40 saniye yanar, ardından yanmış olur. Her adımda yanan bir hücre komşularını bir olasılıkla tutuşturur. Bu olasılık rüzgar hizalaması, yukarı eğim, yakıt sınıfı ve yakıt nemiyle ölçeklenir. Rüzgar yönü meteorolojik kurala göre rüzgarın geldiği yöndür (270° = batıdan esen).', 'Rastgelelik tohum (seed) ile tekrarlanabilir. Karşılaştırmalarda her strateji aynı tohum dizisiyle birden çok kez çalıştırılır.'] },
  { h: 'Sprinkler hatları', p: ['Alan, kenar başına 2–6 bölgeye bölünür. Komşu bölgeler arasındaki her sınır bir sprinkler hattıdır (örn. A1-A2). Açık hat, sınırın her iki yanında 2 hücre genişliğinde bir şeridi ıslatır; ıslak hücrelerin tutuşma olasılığı düşer. Hat kapandıktan sonra şerit 60 saniyede kurur. Su tüketimi, açık olan her benzersiz hat için debi (L/dakika) × açık kalma süresi olarak hesaplanır; aynı hat iki kez sayılmaz.', 'Stratejiler: yok (kontrol), tümü açık, sabit gecikmeli (tutuşmadan belirli saniye sonra tümü), komşu bölge (yanan bölgeye bitişik hatlar), akıllı (rüzgar yönündeki tahmini tehdit altındaki hatlar).'] },
  { h: 'Sonuçların yorumlanması', p: ['“Söndü” yalnızca simülasyon yanan hücre kalmadan bittiğinde gösterilir. Adım sınırına ulaşan çalışmalar “kesildi” olarak işaretlenir ve sönmüş sayılmaz.', 'Geçiş zamanı boş ise o çalışmada bölge geçişi gözlenmemiştir; bu sıfır anlamına gelmez. Karşılaştırma tablosu yalnızca gözlenen çalışmaların aritmetik ortalamasını verir; güven aralığı hesaplanmaz.'] },
  { h: 'Sınırlamalar', p: ['Lekelenme (spotting), taç yangını, gün içi nem değişimi, söndürme ekipleri ve hat basınç kayıpları modellenmez. WorldCover sınıfları yakıt yükünü doğrudan ölçmez. Sonuçlar eğitim ve hipotez üretimi içindir; operasyonel karar için kullanılmamalıdır.'] },
];
const EN: Sec[] = [
  { h: 'What we simulate', p: ['FireBreak Lab uses a probabilistic cellular-automaton spread model over a regular grid covering the study area. The goal is to compare the relative effect of different sprinkler-line activation strategies between zones on burned area and water use. The model is not calibrated to forecast real fire behaviour.'] },
  { h: 'Data sources', p: ['Elevation: AWS Terrain Tiles (Terrarium encoding) at zoom 12, sampled at each cell centre. Slope and aspect are derived by finite differences on the cell grid.', 'Fuel: ESA WorldCover 2021 (v200) 10 m land cover, each cell classified by the pixel nearest its centre (nearest neighbour); 10 tree, 20 shrub, 30 grass, 40 crop, 90 wetland, 95 mangrove, 100 moss are treated as burnable. Other classes (built-up, bare, water, snow) do not burn.', 'Satellite imagery (Esri World Imagery) is visual context only and is never a model input.'] },
  { h: 'Spread assumptions', p: ['The time step is 10 seconds. An ignited cell burns for 40 seconds (residence time), then becomes burned. At each step a burning cell ignites neighbours with a probability scaled by wind alignment, upslope, fuel class and fuel moisture. Wind direction follows meteorological convention: the direction the wind comes from (270° = westerly).', 'Randomness is reproducible via the seed. In comparisons each strategy is run several times on the same seed sequence.'] },
  { h: 'Sprinkler lines', p: ['The area is split into 2–6 zones per side. Every boundary between adjacent zones is a sprinkler line (e.g. A1-A2). An active line wets a strip 2 cells wide on both sides of the boundary; wet cells have reduced ignition probability. After a line turns off, its strip dries within 60 seconds. Water use is flow rate (L/min) × time on, counted once per unique active line.', 'Strategies: none (control), all on, fixed delay (all lines after N seconds from ignition), adjacent (lines bordering burning zones), smart (lines predicted under downwind threat).'] },
  { h: 'Reading results', p: ['“Extinguished” is shown only when the simulation ends with no burning cells. Runs that hit the step limit are marked “truncated” and are never counted as extinguished.', 'A null crossing time means no zone crossing was observed in that run; it does not mean zero. Comparison tables report arithmetic means of observed runs only; no confidence intervals are estimated.'] },
  { h: 'Limitations', p: ['Spotting, crown fire, diurnal moisture change, suppression crews and line pressure losses are not modelled. WorldCover classes do not measure fuel load directly. Results are for education and hypothesis generation, not operational decisions.'] },
];

export default function MethodologyPage() {
  const { lang } = useI18n();
  const secs = lang === 'tr' ? TR : EN;
  return (
    <div className="min-h-[100dvh] bg-background">
      <NavBar />
      <main className="mx-auto grid max-w-6xl gap-10 px-4 py-12 md:grid-cols-[220px_1fr] md:px-8">
        <nav className="hidden md:block" aria-label="Contents">
          <ol className="sticky top-8 space-y-2 text-sm">
            {secs.map((s, i) => (
              <li key={i}><a href={`#s${i}`} className="flex gap-3 text-muted-foreground hover:text-foreground"><span className="num text-xs text-primary">{String(i + 1).padStart(2, '0')}</span>{s.h}</a></li>
            ))}
          </ol>
        </nav>
        <article>
          <p className="eyebrow">{lang === 'tr' ? 'Model varsayımları ve kaynaklar' : 'Model assumptions & sources'}</p>
          <h1 className="mt-2 max-w-2xl text-4xl font-semibold leading-tight tracking-tight md:text-5xl">
            {lang === 'tr' ? 'Ne bildiğimizi ve bilmediğimizi açıkça yazıyoruz.' : 'We write down what we know, and what we do not.'}
          </h1>
          <div className="mt-12 space-y-12">
            {secs.map((s, i) => (
              <section key={i} id={`s${i}`} className="grid gap-4 border-t border-border pt-6 md:grid-cols-[60px_1fr]">
                <span className="num text-sm text-primary">{String(i + 1).padStart(2, '0')}</span>
                <div>
                  <h2 className="text-xl font-medium">{s.h}</h2>
                  {s.p.map((p, j) => <p key={j} className="mt-3 max-w-2xl leading-relaxed text-foreground/80">{p}</p>)}
                </div>
              </section>
            ))}
          </div>
          <section className="mt-14 rounded-2xl border border-border bg-card p-6 text-sm">
            <h2 className="eyebrow mb-3">{lang === 'tr' ? 'Kaynaklar' : 'Sources'}</h2>
            <ul className="space-y-1.5 text-muted-foreground">
              <li>Zanaga, D. et al. (2022). ESA WorldCover 10 m 2021 v200. doi:10.5281/zenodo.7254221</li>
              <li>AWS Open Data: Terrain Tiles (Terrarium). registry.opendata.aws/terrain-tiles</li>
              <li>Alexandridis, A. et al. (2008). A cellular automata model for forest fire spread prediction. Applied Mathematics and Computation, 204(1).</li>
              <li>Rothermel, R. C. (1972). A mathematical model for predicting fire spread in wildland fuels. USDA Forest Service INT-115.</li>
              <li>Esri World Imagery basemap (visual reference only).</li>
            </ul>
          </section>
        </article>
      </main>
    </div>
  );
}

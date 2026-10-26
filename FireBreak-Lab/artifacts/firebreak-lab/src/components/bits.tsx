import { useEffect, useState, type ReactNode } from 'react';
import { Link, useLocation } from 'wouter';
import { Flame, Search, Crosshair, Loader2, Languages } from 'lucide-react';
import { useHealthCheck, useSearchPlaces, getSearchPlacesQueryKey, getHealthCheckQueryKey } from '@workspace/api-client-react';
import type { ScenarioInput, Frame } from '@workspace/api-client-react';
import { useI18n, type Key } from '@/lib/i18n';
import { compassLabel, STRATEGIES, type FireStatus } from '@/lib/sim';
import { Slider } from '@/components/ui/slider';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { cn } from '@/lib/utils';

export function Brand() {
  return (
    <Link href="/" className="flex shrink-0 items-center gap-2.5 whitespace-nowrap" data-testid="link-home">
      <span className="relative grid h-8 w-8 place-items-center rounded-md bg-gradient-to-br from-[#ff7a2e] to-[#c9301a]">
        <Flame className="h-4 w-4 text-[#1a0d06]" strokeWidth={2.5} />
        <span className="absolute -bottom-0.5 -right-0.5 h-2.5 w-2.5 rounded-full border-2 border-[#11181c] bg-[#3a9bff]" />
      </span>
      <span className="leading-tight">
        <span className="block text-sm font-semibold tracking-tight">FireBreak Lab</span>
        <span className="eyebrow hidden sm:block !text-[9px]">TÜBİTAK 2204</span>
      </span>
    </Link>
  );
}

export function NavBar({ floating = false }: { floating?: boolean }) {
  const { t, lang, setLang } = useI18n();
   const [loc, navigate] = useLocation();
  const health = useHealthCheck({ query: { queryKey: getHealthCheckQueryKey(), refetchInterval: 30000, retry: 1 } });
  const ok = health.data?.status === 'ok';
  const links: [string, Key][] = [['/', 'lab'], ['/experiments', 'experiments'], ['/scenarios', 'scenarios'], ['/methodology', 'methodology']];
  return (
    <header className={cn('z-30 flex items-center gap-3 px-3 py-2', floating ? 'glass rounded-xl' : 'border-b border-border bg-card/60 backdrop-blur')}>
      <Brand />
      {floating ? <select aria-label={t('lab')} value={loc} onChange={(e) => navigate(e.target.value)} className="min-w-0 flex-1 rounded-md bg-[#192329] px-2 py-2 text-xs text-primary">
        {links.map(([href,k]) => <option key={href} value={href}>{t(k)}</option>)}
      </select> : <nav className="ml-2 flex items-center gap-0.5 overflow-x-auto" aria-label="Main">
        {links.map(([href, k]) => (
          <Link
            key={href}
            href={href}
            data-testid={`link-nav-${k}`}
            className={cn(
              'whitespace-nowrap rounded-md px-2.5 py-1.5 text-xs font-medium transition-colors',
              loc === href ? 'bg-primary/15 text-primary' : 'text-muted-foreground hover:text-foreground hover:bg-white/5',
            )}
          >
            {t(k)}
          </Link>
        ))}
      </nav>}
      <div className="ml-auto flex items-center gap-2">
        <span
          className={cn("hidden items-center gap-1.5 text-[11px] text-muted-foreground", !floating && "md:flex")}
          data-testid="status-api"
          title={health.isError ? t('apiDown') : ok ? t('apiOk') : t('apiChecking')}
        >
          <span className={cn('h-2 w-2 rounded-full', health.isLoading ? 'bg-yellow-400/80' : ok ? 'bg-emerald-400' : 'bg-red-500')} />
          {health.isLoading ? t('apiChecking') : ok ? t('apiOk') : t('apiDown')}
        </span>
        <button
          onClick={() => setLang(lang === 'tr' ? 'en' : 'tr')}
          className="flex items-center gap-1 rounded-md border border-border px-2 py-1 text-[11px] font-semibold hover:bg-white/5"
          data-testid="button-lang"
          aria-label="Language / Dil"
        >
          <Languages className="h-3.5 w-3.5" />
          {lang === 'tr' ? 'TR' : 'EN'}
        </button>
      </div>
    </header>
  );
}

export function StatusBanner({ status, frame }: { status: FireStatus; frame?: Frame | null }) {
  const { t, lang } = useI18n();
  const burning = frame?.zones.filter(z => z.state === 'burning').map(z => z.id).join(', ');
  const active = frame?.lines.filter(l => l.on).map(l => l.id).join(', ');
  const map = {
    idle: { k: 'idle' as Key, cls: 'bg-[#1d272d]/90 text-[#c9d4da] border-white/10', dot: 'bg-[#8aa0ad]' },
    fire: { k: 'fire' as Key, cls: 'bg-[#4a1408]/90 text-[#ffd2b8] border-[#ff5a1f]/50', dot: 'bg-[#ff5a1f] pulse-fire' },
    extinguished: { k: 'extinguished' as Key, cls: 'bg-[#0b2a44]/90 text-[#cfe6ff] border-[#3a9bff]/50', dot: 'bg-[#3a9bff]' },
    truncated: { k: 'truncated' as Key, cls: 'bg-[#3a2c0a]/90 text-[#ffe6a8] border-[#e8b23a]/50', dot: 'bg-[#e8b23a]' },
  }[status];
  return (
    <div
      role="status"
      aria-live="polite"
      data-testid="status-fire"
      className={cn('flex items-center gap-2.5 rounded-full border px-4 py-1.5 backdrop-blur-md shadow-lg', map.cls)}
    >
      <span className={cn('h-2.5 w-2.5 rounded-full', map.dot)} />
       <span className="text-xs font-semibold">{status === 'fire' && frame ? `${lang === 'tr' ? 'Yanan bölge' : 'Burning zone'}: ${burning} | ${lang === 'tr' ? 'Püskürten hat' : 'Active line'}: ${active || '—'}` : t(map.k)}</span>
    </div>
  );
}

export function WindRose({ dir, speed, size = 88 }: { dir: number; speed: number; size?: number }) {
  const { t } = useI18n();
  const r = size / 2;
  // wind "from" dir; arrow points where it blows to
  const to = (dir + 180) % 360;
  return (
    <figure className="flex items-center gap-3" data-testid="display-wind">
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} role="img" aria-label={`${t('wind')} ${dir}° ${speed} m/s`}>
        <circle cx={r} cy={r} r={r - 2} fill="hsl(200 18% 10% / .7)" stroke="hsl(36 30% 92% / .15)" />
        <circle cx={r} cy={r} r={(r - 2) * 0.6} fill="none" stroke="hsl(36 30% 92% / .08)" />
        {Array.from({ length: 16 }).map((_, i) => {
          const a = (i * 22.5 * Math.PI) / 180;
          const l = i % 4 === 0 ? 7 : 3;
          return (
            <line key={i} x1={r + Math.sin(a) * (r - 3)} y1={r - Math.cos(a) * (r - 3)} x2={r + Math.sin(a) * (r - 3 - l)} y2={r - Math.cos(a) * (r - 3 - l)} stroke="hsl(36 30% 92% / .35)" />
          );
        })}
        {['N', 'E', 'S', 'W'].map((c, i) => {
          const a = (i * 90 * Math.PI) / 180;
          return (
            <text key={c} x={r + Math.sin(a) * (r - 15)} y={r - Math.cos(a) * (r - 15) + 3.5} textAnchor="middle" fontSize="9" fill={c === 'N' ? '#ff8a4a' : 'hsl(36 30% 92% / .6)'} fontFamily="JetBrains Mono">
              {c}
            </text>
          );
        })}
        <g transform={`rotate(${to} ${r} ${r})`} style={{ transition: 'transform .4s ease' }}>
          <line x1={r} y1={r + r * 0.5} x2={r} y2={r - r * 0.45} stroke="#59b0ff" strokeWidth="2.5" strokeLinecap="round" />
          <path d={`M ${r} ${r - r * 0.62} L ${r - 6} ${r - r * 0.36} L ${r + 6} ${r - r * 0.36} Z`} fill="#59b0ff" />
        </g>
      </svg>
      <figcaption className="text-xs">
        <div className="eyebrow">{t('wind')}</div>
        <div className="num text-base font-semibold">{speed} <span className="text-xs text-muted-foreground">m/s</span></div>
        <div className="num text-muted-foreground">{dir}° {compassLabel(dir)}</div>
      </figcaption>
    </figure>
  );
}

export function SearchBox({ onPick }: { onPick: (lng: number, lat: number) => void }) {
  const { t } = useI18n();
  const [q, setQ] = useState('');
  const [dq, setDq] = useState('');
  const [open, setOpen] = useState(false);
  useEffect(() => {
    const id = setTimeout(() => setDq(q.trim()), 350);
    return () => clearTimeout(id);
  }, [q]);
  const coord = /^\s*(-?\d+(?:\.\d+)?)\s*[,\s]\s*(-?\d+(?:\.\d+)?)\s*$/.exec(q);
  const lat = coord ? Number(coord[1]) : NaN;
  const lon = coord ? Number(coord[2]) : NaN;
  const validCoord = coord && Math.abs(lat) <= 90 && Math.abs(lon) <= 180;
  const enabled = !coord && dq.length >= 2;
  const res = useSearchPlaces({ q: dq }, { query: { enabled, queryKey: getSearchPlacesQueryKey({ q: dq }), retry: 0 } });
  return (
    <div className="relative">
      <div className="glass flex items-center gap-2 rounded-xl px-3">
        <Search className="h-4 w-4 text-muted-foreground" aria-hidden />
        <input
          value={q}
          onChange={(e) => { setQ(e.target.value); setOpen(true); }}
          onFocus={() => setOpen(true)}
          onBlur={() => setTimeout(() => setOpen(false), 150)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && validCoord) { onPick(lon, lat); setOpen(false); }
            if (e.key === 'Enter' && res.data?.[0]) { onPick(res.data[0].lon, res.data[0].lat); setOpen(false); }
          }}
          placeholder={t('search')}
          aria-label={t('search')}
          className="h-10 w-full bg-transparent text-sm outline-none placeholder:text-muted-foreground/70"
          data-testid="input-search"
        />
        {res.isFetching && <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />}
      </div>
      {open && q.trim().length >= 2 && (
        <ul className="glass absolute left-0 right-0 top-12 z-40 max-h-72 overflow-auto rounded-xl p-1 text-sm" role="listbox">
          {validCoord && (
            <li>
              <button className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left hover:bg-white/5" onMouseDown={() => { onPick(lon, lat); setOpen(false); }} data-testid="button-goto-coords">
                <Crosshair className="h-4 w-4 text-accent" /> {t('goCoords')} <span className="num ml-auto text-xs text-muted-foreground">{lat.toFixed(4)}, {lon.toFixed(4)}</span>
              </button>
            </li>
          )}
          {enabled && res.isError && <li className="px-3 py-2 text-destructive text-xs">{(res.error as Error)?.message ?? 'Search failed'}</li>}
          {enabled && res.data && res.data.length === 0 && <li className="px-3 py-2 text-muted-foreground text-xs">{t('noResults')}</li>}
          {enabled && res.data?.map((pl, i) => (
            <li key={i}>
              <button className="w-full rounded-lg px-3 py-2 text-left hover:bg-white/5" onMouseDown={() => { onPick(pl.lon, pl.lat); setOpen(false); setQ(pl.name.split(',')[0]); }} data-testid={`button-place-${i}`}>
                <div className="truncate">{pl.name}</div>
                <div className="num text-[11px] text-muted-foreground">{pl.lat.toFixed(4)}, {pl.lon.toFixed(4)}</div>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function Field({ label, value, unit, children }: { label: string; value: string | number; unit?: string; children: ReactNode }) {
  return (
    <div className="space-y-2">
      <div className="flex items-baseline justify-between text-xs">
        <span className="text-muted-foreground">{label}</span>
        <span className="num font-medium">{value}{unit && <span className="ml-0.5 text-muted-foreground">{unit}</span>}</span>
      </div>
      {children}
    </div>
  );
}

export function ScenarioFields({ s, set, disabled }: { s: ScenarioInput; set: (p: Partial<ScenarioInput>) => void; disabled?: boolean }) {
  const { t } = useI18n();
  const sl = (k: keyof ScenarioInput, min: number, max: number, step: number, unit: string, label: Key) => (
    <Field label={t(label)} value={s[k] as number} unit={unit}>
      <Slider
        value={[s[k] as number]}
        min={min}
        max={max}
        step={step}
        disabled={disabled}
        onValueChange={([v]) => set({ [k]: v } as Partial<ScenarioInput>)}
        aria-label={t(label)}
        data-testid={`slider-${k}`}
      />
    </Field>
  );
  return (
    <div className="space-y-4">
      <div className="space-y-1.5">
        <label className="text-xs text-muted-foreground" htmlFor="sc-name">{t('name')}</label>
        <Input id="sc-name" value={s.name} onChange={(e) => set({ name: e.target.value })} disabled={disabled} className="h-8 bg-black/20" data-testid="input-scenario-name" />
      </div>
      <div className="grid grid-cols-2 gap-3">
        <div className="space-y-1.5">
          <label className="text-xs text-muted-foreground" htmlFor="sc-lat">Lat °</label>
          <Input id="sc-lat" type="number" step="0.0001" value={s.lat} disabled={disabled} onChange={(e) => set({ lat: Number(e.target.value) })} className="num h-8 bg-black/20" data-testid="input-lat" />
        </div>
        <div className="space-y-1.5">
          <label className="text-xs text-muted-foreground" htmlFor="sc-lon">Lon °</label>
          <Input id="sc-lon" type="number" step="0.0001" value={s.lon} disabled={disabled} onChange={(e) => set({ lon: Number(e.target.value) })} className="num h-8 bg-black/20" data-testid="input-lon" />
        </div>
      </div>
      {sl('widthM', 500, 4000, 50, ' m', 'width')}
      {sl('cellSize', 30, 100, 5, ' m', 'cellSize')}
      {sl('zones', 2, 6, 1, '', 'zones')}
      {sl('windDir', 0, 359, 1, '°', 'windDir')}
      {sl('windSpeed', 0, 30, 0.5, ' m/s', 'windSpeed')}
      {sl('moisture', 0, 100, 1, ' %', 'moisture')}
      <div className="space-y-1.5">
        <span className="text-xs text-muted-foreground">{t('strategy')}</span>
        <Select value={s.strategy} onValueChange={(v) => set({ strategy: v as ScenarioInput['strategy'] })} disabled={disabled}>
          <SelectTrigger className="h-8 bg-black/20" data-testid="select-strategy" aria-label={t('strategy')}><SelectValue /></SelectTrigger>
          <SelectContent>
            {STRATEGIES.map((x) => <SelectItem key={x} value={x}>{t(`s_${x}` as Key)}</SelectItem>)}
          </SelectContent>
        </Select>
      </div>
      {sl('flowLpm', 20, 400, 5, ' L/min', 'flow')}
      {sl('fixedDelay', 0, 600, 10, ' s', 'fixedDelay')}
      <div className="space-y-1.5">
        <label className="text-xs text-muted-foreground" htmlFor="sc-seed">{t('seed')}</label>
        <Input id="sc-seed" type="number" min={0} step={1} value={s.seed} disabled={disabled} onChange={(e) => set({ seed: Math.max(0, Math.round(Number(e.target.value) || 0)) })} className="num h-8 bg-black/20" data-testid="input-seed" />
      </div>
    </div>
  );
}

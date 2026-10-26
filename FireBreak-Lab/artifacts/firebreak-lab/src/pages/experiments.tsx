import { useMemo, useState } from 'react';
import { Link } from 'wouter';
import { Download, Play, FlaskConical, AlertTriangle, GitCompare, X } from 'lucide-react';
import { useListRuns, getListRunsQueryKey } from '@workspace/api-client-react';
import type { Run } from '@workspace/api-client-react';
import { NavBar } from '@/components/bits';
import { CompareTable } from '@/pages/lab';
import { useI18n, type Key } from '@/lib/i18n';
import { fmtHa, fmtL, fmtTime, errMsg, STRATEGIES } from '@/lib/sim';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';

export default function ExperimentsPage() {
  const { t, lang } = useI18n();
  const runs = useListRuns({ query: { queryKey: getListRunsQueryKey() } });
  const [sel, setSel] = useState<Set<string>>(new Set());
  const [filter, setFilter] = useState<string>('all-s');
  const list = useMemo(() => {
    const d = [...(runs.data ?? [])].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
    return filter === 'all-s' ? d : d.filter((r) => r.strategy === filter);
  }, [runs.data, filter]);
  const selected: Run[] = (runs.data ?? []).filter((r) => sel.has(r.id));
  const toggle = (id: string) => setSel((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n; });
  const df = new Intl.DateTimeFormat(lang === 'tr' ? 'tr-TR' : 'en-GB', { dateStyle: 'short', timeStyle: 'short' });

  return (
    <div className="min-h-[100dvh] bg-background">
      <NavBar />
      <main className="mx-auto max-w-7xl px-4 py-8 md:px-8">
        <div className="mb-6 flex flex-wrap items-end gap-4">
          <div>
            <p className="eyebrow">{t('replay')} · CSV · {t('compare')}</p>
            <h1 className="mt-1 text-3xl font-semibold tracking-tight">{t('experiments')}</h1>
          </div>
          <div className="ml-auto flex flex-wrap gap-1.5" role="group" aria-label={t('strategy')}>
            {['all-s', ...STRATEGIES].map((s) => (
              <button key={s} onClick={() => setFilter(s)} className={cn('rounded-full border px-3 py-1 text-xs', filter === s ? 'border-primary bg-primary/15 text-primary' : 'border-border text-muted-foreground hover:text-foreground')} data-testid={`button-filter-${s}`}>
                {s === 'all-s' ? '∗' : t(`s_${s}` as Key)}
              </button>
            ))}
          </div>
        </div>

        <div className="grid gap-6 lg:grid-cols-[1fr_380px]">
          <section className="overflow-hidden rounded-2xl border border-border bg-card">
            {runs.isLoading ? (
              <div className="space-y-2 p-4">{Array.from({ length: 6 }).map((_, i) => <Skeleton key={i} className="h-10 w-full" />)}</div>
            ) : runs.isError ? (
              <div className="flex flex-col items-center gap-3 p-12 text-center" role="alert">
                <AlertTriangle className="h-6 w-6 text-destructive" />
                <p className="text-sm">{errMsg(runs.error)}</p>
                <Button variant="secondary" size="sm" onClick={() => runs.refetch()} data-testid="button-retry">{t('retry')}</Button>
              </div>
            ) : list.length === 0 ? (
              <div className="flex flex-col items-center gap-4 p-16 text-center">
                <span className="grid h-14 w-14 place-items-center rounded-full bg-primary/10 text-primary"><FlaskConical className="h-6 w-6" /></span>
                <div>
                  <p className="font-medium">{lang === 'tr' ? 'Henüz kayıtlı deney yok' : 'No saved runs yet'}</p>
                  <p className="mt-1 max-w-sm text-sm text-muted-foreground">{lang === 'tr' ? 'Laboratuvarda arazi yükleyip tutuşma noktası seçerek ilk simülasyonu çalıştırın.' : 'Load terrain in the laboratory, place an ignition and run your first simulation.'}</p>
                </div>
                <Link href="/" className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground" data-testid="link-go-lab">{t('lab')}</Link>
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm" data-testid="table-runs">
                  <thead className="border-b border-border text-left text-xs text-muted-foreground">
                    <tr>
                      <th className="w-10 p-3"><span className="sr-only">select</span></th>
                      <th className="p-3 font-normal">{t('name')}</th>
                      <th className="p-3 font-normal">{t('strategyCol')}</th>
                      <th className="p-3 text-right font-normal">{t('burned')}</th>
                      <th className="p-3 text-right font-normal">{t('water')}</th>
                      <th className="p-3 text-right font-normal">{t('crossing')}</th>
                      <th className="p-3 font-normal">{t('status')}</th>
                      <th className="p-3" />
                    </tr>
                  </thead>
                  <tbody>
                    {list.map((r) => (
                      <tr key={r.id} className={cn('border-b border-border/50 hover:bg-white/[0.02]', sel.has(r.id) && 'bg-primary/5')} data-testid={`row-run-${r.id}`}>
                        <td className="p-3"><Checkbox checked={sel.has(r.id)} onCheckedChange={() => toggle(r.id)} aria-label={`select ${r.name}`} data-testid={`checkbox-run-${r.id}`} /></td>
                        <td className="p-3">
                          <div className="font-medium">{r.name}</div>
                          <div className="num text-[11px] text-muted-foreground">{df.format(new Date(r.createdAt))} · seed {r.seed} · {r.scenario.lat.toFixed(3)}, {r.scenario.lon.toFixed(3)}</div>
                        </td>
                        <td className="p-3 text-xs">{t(`s_${r.strategy}` as Key)}</td>
                        <td className="num p-3 text-right text-[#ff8a4a]">{fmtHa(r.burnedHa)}</td>
                        <td className="num p-3 text-right text-[#6cb8ff]">{fmtL(r.waterL)}</td>
                        <td className="num p-3 text-right text-xs">{r.crossingTime == null ? <span className="text-muted-foreground">{t('noCrossing')}</span> : fmtTime(r.crossingTime)}</td>
                        <td className="p-3 text-xs"><span className={cn('rounded px-1.5 py-0.5', r.status === 'completed' ? 'bg-emerald-500/10 text-emerald-300' : 'bg-[#e8b23a]/15 text-[#ffd48a]')}>{r.status === 'completed' ? t('completed') : t('truncatedS')}</span></td>
                        <td className="p-3">
                          <div className="flex justify-end gap-1">
                            <Link href={`/?run=${r.id}`} className="grid h-8 w-8 place-items-center rounded-md border border-border hover:bg-white/5" aria-label={t('replay')} title={t('replay')} data-testid={`link-replay-${r.id}`}><Play className="h-3.5 w-3.5" /></Link>
                            <a href={`/api/runs/${r.id}/export.csv`} download className="grid h-8 w-8 place-items-center rounded-md border border-border hover:bg-white/5" aria-label={t('export')} title={t('export')} data-testid={`link-export-${r.id}`}><Download className="h-3.5 w-3.5" /></a>
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <aside className="space-y-4 lg:sticky lg:top-6 lg:self-start">
            <div className="rounded-2xl border border-border bg-card p-4">
              <div className="mb-3 flex items-center gap-2">
                <GitCompare className="h-4 w-4 text-accent" />
                <h2 className="text-sm font-medium">{t('compare')}</h2>
                {sel.size > 0 && <button onClick={() => setSel(new Set())} className="ml-auto flex items-center gap-1 text-[11px] text-muted-foreground hover:text-foreground" data-testid="button-clear-selection"><X className="h-3 w-3" />{t('clear')}</button>}
              </div>
              {selected.length < 2 ? (
                <p className="text-xs text-muted-foreground">{lang === 'tr' ? 'Karşılaştırmak için tablodan en az iki çalıştırma seçin.' : 'Select at least two runs from the table to compare.'}</p>
              ) : (
                <CompareTable runs={selected} />
              )}
            </div>
          </aside>
        </div>
      </main>
    </div>
  );
}

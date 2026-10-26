import { useState, type ReactNode } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { Link, useLocation } from 'wouter';
import { useQueryClient } from '@tanstack/react-query';
import { Plus, Trash2, MapPin, Wind, Droplets, Loader2, AlertTriangle, BookMarked, Copy } from 'lucide-react';
import { useListScenarios, useSaveScenario, useDeleteScenario, getListScenariosQueryKey } from '@workspace/api-client-react';
import type { Scenario, ScenarioInput } from '@workspace/api-client-react';
import { NavBar, ScenarioFields, WindRose } from '@/components/bits';
import { useI18n, type Key } from '@/lib/i18n';
import { DEFAULT_SCENARIO, compassLabel, errMsg } from '@/lib/sim';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription } from '@/components/ui/sheet';
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from '@/components/ui/alert-dialog';
import { useToast } from '@/hooks/use-toast';

export default function ScenariosPage() {
  const { t, lang } = useI18n();
  const { toast } = useToast();
  const qc = useQueryClient();
  const [, nav] = useLocation();
  const list = useListScenarios({ query: { queryKey: getListScenariosQueryKey() } });
  const save = useSaveScenario();
  const del = useDeleteScenario();
  const [draft, setDraft] = useState<ScenarioInput | null>(null);
  const [toDelete, setToDelete] = useState<Scenario | null>(null);

  const strip = (s: Scenario): ScenarioInput => {
    const { id: _i, createdAt: _c, ...rest } = s;
    void _i; void _c;
    return { ...DEFAULT_SCENARIO, ...rest };
  };

  const submit = () => {
    if (!draft || !draft.name.trim()) return;
    save.mutate({ data: draft }, {
      onSuccess: (s) => { qc.invalidateQueries({ queryKey: getListScenariosQueryKey() }); setDraft(null); toast({ title: t('saveScenario'), description: s.name }); },
      onError: (e) => toast({ title: lang === 'tr' ? 'Kaydedilemedi' : 'Save failed', description: errMsg(e), variant: 'destructive' }),
    });
  };
  const confirmDelete = () => {
    if (!toDelete) return;
    del.mutate({ id: toDelete.id }, {
      onSuccess: () => { qc.invalidateQueries({ queryKey: getListScenariosQueryKey() }); setToDelete(null); },
      onError: (e) => toast({ title: lang === 'tr' ? 'Silinemedi' : 'Delete failed', description: errMsg(e), variant: 'destructive' }),
    });
  };
  const df = new Intl.DateTimeFormat(lang === 'tr' ? 'tr-TR' : 'en-GB', { dateStyle: 'medium' });

  return (
    <div className="min-h-[100dvh] bg-background">
      <NavBar />
      <main className="mx-auto max-w-6xl px-4 py-8 md:px-8">
        <div className="mb-8 flex flex-wrap items-end gap-4">
          <div>
            <p className="eyebrow">{lang === 'tr' ? 'Kayıtlı deney koşulları' : 'Saved experiment conditions'}</p>
            <h1 className="mt-1 text-3xl font-semibold tracking-tight">{t('scenarios')}</h1>
          </div>
          <Button className="fb-cta group ml-auto" onClick={() => setDraft({ ...DEFAULT_SCENARIO })} data-testid="button-new-scenario"><Plus className="mr-1.5 h-4 w-4 transition-transform duration-300 group-hover:rotate-90" />{lang === 'tr' ? 'Yeni senaryo' : 'New scenario'}</Button>
        </div>

        {list.isLoading ? (
          <div className="grid gap-4 md:grid-cols-2">{Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-44 rounded-2xl" />)}</div>
        ) : list.isError ? (
          <div className="flex flex-col items-center gap-3 rounded-2xl border border-border bg-card p-12 text-center" role="alert">
            <AlertTriangle className="h-6 w-6 text-destructive" />
            <p className="text-sm">{errMsg(list.error)}</p>
            <Button variant="secondary" size="sm" onClick={() => list.refetch()} data-testid="button-retry">{t('retry')}</Button>
          </div>
        ) : (list.data ?? []).length === 0 ? (
          <motion.div initial={{ opacity: 0, scale: 0.98 }} animate={{ opacity: 1, scale: 1 }} className="flex flex-col items-center gap-4 rounded-2xl border border-dashed border-border p-16 text-center">
            <motion.span animate={{ y: [0, -5, 0] }} transition={{ duration: 2.4, repeat: Infinity, ease: 'easeInOut' }} className="grid h-14 w-14 place-items-center rounded-full bg-accent/10 text-accent"><BookMarked className="h-6 w-6" /></motion.span>
            <div>
              <p className="font-medium">{lang === 'tr' ? 'Henüz senaryo yok' : 'No scenarios yet'}</p>
              <p className="mt-1 max-w-sm text-sm text-muted-foreground">{lang === 'tr' ? 'Tekrarlanabilir deneyler için konum, rüzgar ve strateji ayarlarını kaydedin.' : 'Save location, wind and strategy settings for reproducible experiments.'}</p>
            </div>
          </motion.div>
        ) : (
          <ul className="grid gap-4 md:grid-cols-2">
            <AnimatePresence initial={true}>
            {list.data!.map((s, k) => (
              <motion.li key={s.id} layout
                initial={{ opacity: 0, y: 16 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, scale: 0.95, transition: { duration: 0.2 } }}
                transition={{ delay: Math.min(k, 10) * 0.05, duration: 0.45, ease: [0.16, 1, 0.3, 1] }}
                whileHover={{ y: -3 }}
                className="group rounded-2xl border border-border bg-card p-5 transition-[border-color,box-shadow] duration-300 hover:border-primary/40 hover:shadow-[0_18px_40px_-20px_hsl(18_92%_55%/0.45)]" data-testid={`card-scenario-${s.id}`}>
                <div className="flex items-start gap-4">
                  <WindRose dir={s.windDir} speed={s.windSpeed} size={64} />
                  <div className="min-w-0 flex-1">
                    <h2 className="truncate text-lg font-medium">{s.name}</h2>
                    <p className="num text-[11px] text-muted-foreground">{df.format(new Date(s.createdAt))}</p>
                  </div>
                </div>
                <dl className="num mt-4 grid grid-cols-3 gap-2 text-[11px]">
                  <Item icon={<MapPin className="h-3 w-3" />} v={`${s.lat.toFixed(3)}, ${s.lon.toFixed(3)}`} />
                  <Item icon={<Wind className="h-3 w-3" />} v={`${s.windSpeed} m/s ${compassLabel(s.windDir)}`} />
                  <Item icon={<Droplets className="h-3 w-3" />} v={`${s.moisture} %`} />
                  <Item v={`${s.widthM} m · ${s.cellSize} m`} />
                  <Item v={`${s.zones}×${s.zones} · ${t(`s_${s.strategy}` as Key)}`} />
                  <Item v={`${s.flowLpm ?? '—'} L/min · ${s.fixedDelay ?? '—'} s`} />
                </dl>
                <div className="mt-4 flex gap-2">
                  <Button size="sm" onClick={() => nav(`/?scenario=${s.id}`)} data-testid={`button-load-${s.id}`}>{t('load')}</Button>
                  <Button size="sm" variant="secondary" onClick={() => setDraft({ ...strip(s), name: `${s.name} (2)` })} data-testid={`button-duplicate-${s.id}`}><Copy className="mr-1 h-3.5 w-3.5" />{lang === 'tr' ? 'Düzenle / kopya' : 'Edit as new'}</Button>
                  <Button size="sm" variant="ghost" className="ml-auto text-destructive hover:text-destructive" onClick={() => setToDelete(s)} aria-label={t('delete')} data-testid={`button-delete-${s.id}`}><Trash2 className="h-4 w-4" /></Button>
                </div>
              </motion.li>
            ))}
            </AnimatePresence>
          </ul>
        )}
        <p className="mt-8 text-xs text-muted-foreground">
          {lang === 'tr' ? 'İpucu: Laboratuvarda haritadaki alanı sürükleyip ' : 'Tip: drag the area on the map in the '}
          <Link href="/" className="underline">{t('lab')}</Link>
          {lang === 'tr' ? ' doğrudan kaydedebilirsiniz.' : ' and save it directly.'}
        </p>
      </main>

      <Sheet open={!!draft} onOpenChange={(o) => !o && setDraft(null)}>
        <SheetContent className="w-full overflow-y-auto sm:max-w-md">
          <SheetHeader>
            <SheetTitle>{lang === 'tr' ? 'Senaryo' : 'Scenario'}</SheetTitle>
            <SheetDescription>{lang === 'tr' ? 'Kaydetme her zaman yeni bir senaryo oluşturur.' : 'Saving always creates a new scenario.'}</SheetDescription>
          </SheetHeader>
          {draft && (
            <div className="mt-4 space-y-4 px-1">
              <ScenarioFields s={draft} set={(p) => setDraft((d) => (d ? { ...d, ...p } : d))} />
              <div className="flex gap-2 pt-2">
                <Button onClick={submit} disabled={save.isPending || !draft.name.trim()} className="flex-1" data-testid="button-submit-scenario">
                  {save.isPending && <Loader2 className="mr-1.5 h-4 w-4 animate-spin" />}{t('save')}
                </Button>
                <Button variant="secondary" onClick={() => setDraft(null)} data-testid="button-cancel-scenario">{t('cancel')}</Button>
              </div>
            </div>
          )}
        </SheetContent>
      </Sheet>

      <AlertDialog open={!!toDelete} onOpenChange={(o) => !o && setToDelete(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('delete')}: {toDelete?.name}</AlertDialogTitle>
            <AlertDialogDescription>{lang === 'tr' ? 'Bu işlem geri alınamaz.' : 'This cannot be undone.'}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel data-testid="button-cancel-delete">{t('cancel')}</AlertDialogCancel>
            <AlertDialogAction onClick={(e) => { e.preventDefault(); confirmDelete(); }} className="bg-destructive text-destructive-foreground" data-testid="button-confirm-delete">
              {del.isPending && <Loader2 className="mr-1.5 h-4 w-4 animate-spin" />}{t('delete')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

function Item({ icon, v }: { icon?: ReactNode; v: string }) {
  return <div className="flex items-center gap-1 truncate rounded-md bg-white/[0.03] px-2 py-1.5 text-muted-foreground">{icon}<span className="truncate text-foreground/90">{v}</span></div>;
}

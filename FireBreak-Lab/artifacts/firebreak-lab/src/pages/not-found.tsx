import { Link } from 'wouter';
import { motion } from 'framer-motion';
import { Flame } from 'lucide-react';
import { useI18n } from '@/lib/i18n';

export default function NotFound() {
  const { lang } = useI18n();
  return (
    <div className="flex min-h-[100dvh] w-full items-center justify-center bg-background px-4">
      <motion.div
        initial={{ opacity: 0, y: 16, scale: 0.98 }} animate={{ opacity: 1, y: 0, scale: 1 }} transition={{ duration: 0.5, ease: [0.16, 1, 0.3, 1] }}
        className="glass w-full max-w-md rounded-2xl p-8 text-center"
      >
        <motion.span animate={{ y: [0, -5, 0] }} transition={{ duration: 2.4, repeat: Infinity, ease: 'easeInOut' }}
          className="mx-auto grid h-14 w-14 place-items-center rounded-full bg-primary/10 text-primary shadow-[0_0_30px_-8px_hsl(18_92%_55%/0.7)]">
          <Flame className="fb-brand-flame h-6 w-6" />
        </motion.span>
        <h1 className="num mt-5 text-4xl font-semibold">404</h1>
        <p className="mt-2 text-sm text-muted-foreground">{lang === 'tr' ? 'Aradığınız sayfa bulunamadı.' : 'The page you are looking for was not found.'}</p>
        <Link href="/" className="fb-cta mt-6 inline-flex rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground">
          {lang === 'tr' ? 'Laboratuvara dön' : 'Back to the laboratory'}
        </Link>
      </motion.div>
    </div>
  );
}

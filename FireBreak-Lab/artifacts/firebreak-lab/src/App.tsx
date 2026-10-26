import { type ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ErrorBoundary } from '@/components/error-boundary';
import { Toaster } from '@/components/ui/toaster';
import { TooltipProvider } from '@/components/ui/tooltip';
import NotFound from '@/pages/not-found';
import LabPage from '@/pages/lab';
import ExperimentsPage from '@/pages/experiments';
import ScenariosPage from '@/pages/scenarios';
import MethodologyPage from '@/pages/methodology';
import { I18nProvider } from '@/lib/i18n';
import { Route, Switch, useLocation, Router as WouterRouter } from 'wouter';
import { MotionConfig, motion } from 'framer-motion';
import { memoryLocation } from 'wouter/memory-location';
import { IS_DEMO } from '@/demo/flag';

// The demo runs inside a sandboxed frame whose URL cannot change, so it routes in memory.
const demoLocation = IS_DEMO ? memoryLocation({ path: '/' }) : null;

const queryClient = new QueryClient({ defaultOptions: { queries: { refetchOnWindowFocus: false } } });

function Router() {
  const [location] = useLocation();
  return (
    <RoutedErrorBoundary>
      {/* Each page fades and lifts in; no exit animation so the heavy map never mounts twice. */}
      <motion.div
        key={location}
        initial={{ opacity: 0, y: 10 }}
        animate={{ opacity: 1, y: 0, transitionEnd: { transform: 'none' } }}
        transition={{ duration: 0.45, ease: [0.16, 1, 0.3, 1] }}
      >
      <Switch location={location}>
        <Route path="/" component={LabPage} />
        <Route path="/experiments" component={ExperimentsPage} />
        <Route path="/scenarios" component={ScenariosPage} />
        <Route path="/methodology" component={MethodologyPage} />
        <Route component={NotFound} />
      </Switch>
      </motion.div>
    </RoutedErrorBoundary>
  );
}

function RoutedErrorBoundary({ children }: { children: ReactNode }) {
  const [location] = useLocation();
  return <ErrorBoundary resetKey={location}>{children}</ErrorBoundary>;
}

function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <I18nProvider>
        <MotionConfig reducedMotion="user">
        <TooltipProvider>
          <WouterRouter {...(demoLocation ? { hook: demoLocation.hook, searchHook: demoLocation.searchHook } : { base: import.meta.env.BASE_URL.replace(/\/$/, '') })}>
            <Router />
          </WouterRouter>
          <Toaster />
        </TooltipProvider>
        </MotionConfig>
      </I18nProvider>
    </QueryClientProvider>
  );
}

export default App;

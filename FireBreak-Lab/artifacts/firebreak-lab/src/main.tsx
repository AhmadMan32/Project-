import { createRoot } from 'react-dom/client';

import App from './App';
import { ErrorBoundary } from '@/components/error-boundary';
import { IS_DEMO } from '@/demo/flag';
import { installMockApi } from '@/demo/mock-api';

import 'maplibre-gl/dist/maplibre-gl.css';
import './index.css';

if (IS_DEMO) installMockApi();

createRoot(document.getElementById('root')!, {
  // Keeps caught errors off reportError(), which would raise the dev overlay.
  onCaughtError: (error, errorInfo) => {
    console.error(error, errorInfo.componentStack);
  },
}).render(
  <ErrorBoundary>
    <App />
  </ErrorBoundary>,
);

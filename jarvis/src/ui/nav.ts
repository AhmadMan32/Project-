import { useRouter } from 'expo-router';
import { useCallback } from 'react';

const PATHS = { home: '/', plan: '/plan', projects: '/projects', notes: '/notes', settings: '/settings' } as const;
export type TabName = keyof typeof PATHS;

export function useGo() {
  const router = useRouter();
  return useCallback((tab: TabName) => router.navigate(PATHS[tab]), [router]);
}

// Tüm Jarvis verisi telefonda SQLite içinde tutulur.
import Storage from 'expo-sqlite/kv-store';
import * as SecureStore from 'expo-secure-store';
import type { Persistence } from '../core/store';

const KEY = 'jarvis_state_v1';

export const persistence: Persistence = {
  load: () => Storage.getItem(KEY),
  save: (json) => Storage.setItem(KEY, json),
};

// API anahtarları şifreli cihaz deposunda (Keychain / Keystore)
export const secrets = {
  get: (k: 'anthropic' | 'elevenlabs') => SecureStore.getItemAsync(`key_${k}`),
  set: async (k: 'anthropic' | 'elevenlabs', v: string | null) => {
    if (v) await SecureStore.setItemAsync(`key_${k}`, v);
    else await SecureStore.deleteItemAsync(`key_${k}`);
  },
};

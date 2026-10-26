// Web önizlemesi için tarayıcı deposu.
import type { Persistence } from '../core/store';

const KEY = 'jarvis_state_v1';
const ls = () => (typeof localStorage !== 'undefined' ? localStorage : null);

export const persistence: Persistence = {
  load: async () => ls()?.getItem(KEY) ?? null,
  save: async (json) => ls()?.setItem(KEY, json),
};

export const secrets = {
  get: async (k: 'anthropic' | 'elevenlabs') => ls()?.getItem(`key_${k}`) ?? null,
  set: async (k: 'anthropic' | 'elevenlabs', v: string | null) => {
    if (v) ls()?.setItem(`key_${k}`, v);
    else ls()?.removeItem(`key_${k}`);
  },
};

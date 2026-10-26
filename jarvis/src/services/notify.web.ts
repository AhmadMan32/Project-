import type { NotifierPort } from '../core/device';

export const notifierPort: NotifierPort & { request(): Promise<'kullanilamaz'> } = {
  status: async () => 'kullanilamaz',
  request: async () => 'kullanilamaz',
  notify: async () => null,
};

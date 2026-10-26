import * as Notifications from 'expo-notifications';
import { AndroidImportance } from 'expo-notifications';
import { Platform } from 'react-native';
import type { NotifierPort } from '../core/device';
import type { IntegrationStatus } from '../core/types';

Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowBanner: true,
    shouldShowList: true,
    shouldPlaySound: true,
    shouldSetBadge: false,
  }),
});

let channelReady = false;
async function ensureChannel() {
  if (channelReady || Platform.OS !== 'android') return;
  await Notifications.setNotificationChannelAsync('jarvis', {
    name: 'Jarvis',
    importance: AndroidImportance.HIGH,
    lightColor: '#22D3EE',
  });
  channelReady = true;
}

export const notifierPort: NotifierPort & { request(): Promise<IntegrationStatus> } = {
  async status() {
    const p = await Notifications.getPermissionsAsync();
    if (p.granted) return 'ok';
    return p.canAskAgain ? 'eksik' : 'izin_yok';
  },
  async request() {
    await ensureChannel();
    const p = await Notifications.requestPermissionsAsync();
    return p.granted ? 'ok' : 'izin_yok';
  },
  async notify(title, body, at) {
    await ensureChannel();
    return Notifications.scheduleNotificationAsync({
      content: { title, body },
      trigger: at
        ? { type: Notifications.SchedulableTriggerInputTypes.DATE, date: at, channelId: 'jarvis' }
        : Platform.OS === 'android'
          ? { channelId: 'jarvis' }
          : null,
    });
  },
};

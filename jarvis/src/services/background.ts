// Uygulama kapalıyken de Jarvis izlemeye devam eder (Android WorkManager /
// iOS BGTaskScheduler; sıklığı işletim sistemi belirler, en az ~15-30 dk).
import * as BackgroundTask from 'expo-background-task';
import * as TaskManager from 'expo-task-manager';
import { Store } from '../core/store';
import { runWatch } from '../core/watcher';
import { device } from './device';
import { persistence, secrets } from './persist';

export const WATCH_TASK = 'jarvis-watch';

TaskManager.defineTask(WATCH_TASK, async () => {
  try {
    const store = new Store(persistence);
    await store.load();
    const key = await secrets.get('anthropic');
    await runWatch(store, device, { claude: key ? 'ok' : 'eksik', background: true });
    await store.flush();
    return BackgroundTask.BackgroundTaskResult.Success;
  } catch {
    return BackgroundTask.BackgroundTaskResult.Failed;
  }
});

export async function registerBackground(): Promise<boolean> {
  const status = await BackgroundTask.getStatusAsync();
  if (status !== BackgroundTask.BackgroundTaskStatus.Available) return false;
  if (!(await TaskManager.isTaskRegisteredAsync(WATCH_TASK)))
    await BackgroundTask.registerTaskAsync(WATCH_TASK, { minimumInterval: 30 });
  return true;
}

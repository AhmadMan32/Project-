package com.ahmadman.kenarpanel;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so Android lets the panel see which app is playing music ("Notification access"
 * is the switch that allows reading media sessions). Notifications themselves are ignored.
 */
public class MusicAccess extends NotificationListenerService {
}

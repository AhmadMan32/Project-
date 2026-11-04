package com.ahmadman.notifsaver;

import android.app.Notification;
import android.app.Person;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;

import com.ahmadman.notifsaver.core.Capture;
import com.ahmadman.notifsaver.core.Rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads a posted notification. Chat notifications (MessagingStyle: WhatsApp, Telegram,
 * Messenger, SMS ...) contain the last few messages each time; every message is saved once.
 */
final class Parser {

    private Parser() {
    }

    static String cs(Bundle b, String key) {
        CharSequence c = b.getCharSequence(key);
        if (c == null) {
            return null;
        }
        String s = c.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * @param selfName shown as sender for messages written by the phone's owner
     */
    @android.annotation.SuppressLint("InlinedApi") // the chat extras simply are not there on Android 6
    static List<Capture> parse(StatusBarNotification sbn, String appLabel, boolean saveOngoing, String selfName) {
        Notification n = sbn.getNotification();
        if (n == null) {
            return Collections.emptyList();
        }
        // "5 new messages from 3 chats" summaries repeat their children
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            return Collections.emptyList();
        }
        Bundle e = n.extras == null ? new Bundle() : n.extras;
        boolean ongoing = (n.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE)) != 0;
        boolean progress = e.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0
                || e.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false);
        if ((ongoing || progress) && !saveOngoing) {
            return Collections.emptyList(); // music players, downloads, "running" services
        }
        String pkg = sbn.getPackageName();
        String title = cs(e, Notification.EXTRA_TITLE);
        String text = cs(e, Notification.EXTRA_TEXT);
        String big = cs(e, Notification.EXTRA_BIG_TEXT);
        String sub = cs(e, Notification.EXTRA_SUB_TEXT);
        CharSequence[] lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (big == null && lines != null && lines.length > 0) {
            StringBuilder sb = new StringBuilder();
            for (CharSequence l : lines) {
                if (l != null && l.length() > 0) {
                    sb.append(sb.length() > 0 ? "\n" : "").append(l);
                }
            }
            big = sb.length() > 0 ? sb.toString() : null;
        }

        Parcelable[] messages = e.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (messages != null && messages.length > 0) {
            String convTitle = cs(e, Notification.EXTRA_CONVERSATION_TITLE);
            String conv = Rules.cleanTitle(convTitle != null ? convTitle : title != null ? title : appLabel);
            List<Capture> out = new ArrayList<>();
            for (Parcelable p : messages) {
                if (!(p instanceof Bundle)) {
                    continue;
                }
                Bundle m = (Bundle) p;
                String msg = cs(m, "text");
                if (msg == null) {
                    continue;
                }
                long time = m.getLong("time", 0);
                String sender = cs(m, "sender");
                if (sender == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    Object person = m.getParcelable("sender_person");
                    if (person instanceof Person && ((Person) person).getName() != null) {
                        sender = ((Person) person).getName().toString();
                    }
                }
                if (sender == null) {
                    sender = selfName; // messages without a sender are the user's own replies
                }
                Capture c = new Capture();
                c.pkg = pkg;
                c.app = appLabel;
                c.title = conv;
                c.conversation = conv;
                c.sender = sender;
                c.text = msg;
                c.time = time > 0 ? time : sbn.getPostTime();
                c.message = true;
                c.deleted = Rules.isDeletedMarker(msg);
                c.key = sbn.getKey();
                // same message seen again in the next update -> same hash
                c.hash = Rules.hash(pkg, conv, sender, msg, time > 0 ? time : null);
                out.add(c);
            }
            if (!out.isEmpty()) {
                return out;
            }
        }

        if (title == null && text == null && big == null) {
            return Collections.emptyList();
        }
        Capture c = new Capture();
        c.pkg = pkg;
        c.app = appLabel;
        c.title = title;
        c.text = text;
        c.big = big != null && !big.equals(text) ? big : null;
        c.sub = sub;
        c.conversation = Rules.cleanTitle(title != null ? title : appLabel);
        c.time = sbn.getPostTime();
        c.deleted = Rules.isDeletedMarker(text);
        c.key = sbn.getKey();
        c.hash = Rules.hash(pkg, title, text, c.big);
        return Collections.singletonList(c);
    }
}

package com.ahmadman.notifsaver;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.ahmadman.notifsaver.core.Capture;
import com.ahmadman.notifsaver.core.Formats;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Full text of one saved notification with copy / share / open chat / delete. */
final class Dialogs {

    private Dialogs() {
    }

    static String plain(Activity a, Capture c) {
        String self = a.getString(R.string.self_sender);
        String head = Rows.headline(c, self);
        return head != null ? head + "\n" + c.body() : c.body();
    }

    static void entry(Activity a, Capture c, boolean offerChat) {
        View v = LayoutInflater.from(a).inflate(R.layout.dialog_entry, null);
        Icons.into(v.findViewById(R.id.icon), c.pkg, c.app);
        ((TextView) v.findViewById(R.id.app)).setText(c.app != null ? c.app : c.pkg);
        String date = DateFormat.getDateInstance(DateFormat.LONG, Locale.getDefault()).format(new Date(c.time));
        ((TextView) v.findViewById(R.id.when)).setText(date + "  " + Formats.time(c.time, TimeZone.getDefault()));
        Ui.text(v.findViewById(R.id.title), Rows.headline(c, a.getString(R.string.self_sender)));
        ((TextView) v.findViewById(R.id.text)).setText(c.body());
        Ui.text(v.findViewById(R.id.badge), c.recovered ? a.getString(R.string.badge_recovered)
                : c.deleted ? a.getString(R.string.badge_marker) : null);
        AlertDialog d = new AlertDialog.Builder(a).setView(v).create();
        v.findViewById(R.id.copy).setOnClickListener(x -> {
            ClipboardManager cm = a.getSystemService(ClipboardManager.class);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(c.app, c.body()));
                if (Build.VERSION.SDK_INT < 33) {
                    Toast.makeText(a, R.string.copied, Toast.LENGTH_SHORT).show();
                }
            }
            d.dismiss();
        });
        v.findViewById(R.id.share).setOnClickListener(x -> {
            share(a, plain(a, c));
            d.dismiss();
        });
        View open = v.findViewById(R.id.open);
        open.setVisibility(offerChat ? View.VISIBLE : View.GONE);
        open.setOnClickListener(x -> {
            a.startActivity(BrowseActivity.thread(a, c.pkg, c.conversation));
            d.dismiss();
        });
        v.findViewById(R.id.delete).setOnClickListener(x -> {
            Store.get(a).write(() -> Store.get(a).db.deleteOne(c.id));
            Toast.makeText(a, R.string.deleted_one, Toast.LENGTH_SHORT).show();
            d.dismiss();
        });
        d.show();
    }

    static void share(Activity a, String text) {
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
        Ui.open(a, Intent.createChooser(send, a.getString(R.string.share)));
    }

    static void confirm(Activity a, String message, int action, Runnable yes) {
        new AlertDialog.Builder(a)
                .setMessage(message)
                .setPositiveButton(action, (d, w) -> yes.run())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}

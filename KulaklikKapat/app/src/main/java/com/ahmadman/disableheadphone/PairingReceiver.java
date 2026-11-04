package com.ahmadman.disableheadphone;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Receives the wireless debugging pairing code typed into the pairing notification. */
public class PairingReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle results = RemoteInput.getResultsFromIntent(intent);
        CharSequence code = results != null ? results.getCharSequence(AdbForce.KEY_CODE) : null;
        if (code != null && code.toString().trim().length() > 0) {
            PendingResult pending = goAsync();
            AdbForce.submitCode(context, code.toString(), pending::finish);
        }
    }
}

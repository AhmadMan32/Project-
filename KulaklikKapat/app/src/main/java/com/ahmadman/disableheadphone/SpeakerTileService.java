package com.ahmadman.disableheadphone;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile: one tap to disable / re-enable the headphone output. */
@TargetApi(Build.VERSION_CODES.N)
public class SpeakerTileService extends TileService implements SpeakerService.Listener {

    static void requestUpdate(Context c) {
        try {
            requestListeningState(c, new ComponentName(c, SpeakerTileService.class));
        } catch (RuntimeException ignored) {
            // Tile not added or system refused; nothing to update.
        }
    }

    @Override
    public void onStartListening() {
        SpeakerService.addListener(this);
        updateTile();
    }

    @Override
    public void onStopListening() {
        SpeakerService.removeListener(this);
    }

    @Override
    public void onSpeakerStateChanged() {
        updateTile();
    }

    @Override
    public void onClick() {
        if (SpeakerService.isRunning()) {
            SpeakerService.stop(this);
        } else {
            try {
                SpeakerService.start(this);
            } catch (RuntimeException e) {
                // Starting a foreground service from the tile can be refused: open the app.
                openApp();
            }
        }
        updateTile();
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private void openApp() {
        Intent intent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(intent);
        }
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        boolean on = SpeakerService.isRunning();
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(R.string.tile_label));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int sub;
            if (!on) {
                sub = R.string.state_off_short;
            } else if (!SpeakerService.isForcing()) {
                sub = R.string.state_waiting_short;
            } else {
                sub = R.string.state_forcing_short;
            }
            tile.setSubtitle(getString(sub));
        }
        tile.updateTile();
    }
}

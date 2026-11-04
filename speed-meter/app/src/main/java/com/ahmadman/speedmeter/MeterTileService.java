package com.ahmadman.speedmeter;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile to show / hide the meter. */
@TargetApi(Build.VERSION_CODES.N)
public class MeterTileService extends TileService implements MonitorService.Listener {

    static void requestUpdate(Context c) {
        try {
            requestListeningState(c, new ComponentName(c, MeterTileService.class));
        } catch (RuntimeException ignored) {
            // tile not added
        }
    }

    @Override
    public void onStartListening() {
        MonitorService.addListener(this);
        update();
    }

    @Override
    public void onStopListening() {
        MonitorService.removeListener(this);
    }

    private boolean lastState;

    @Override
    public void onMonitorChanged() {
        if (MonitorService.isRunning() != lastState) {
            update();
        }
    }

    @Override
    public void onClick() {
        if (MonitorService.isRunning()) {
            MonitorService.stop(this);
        } else if (!Settings.canDrawOverlays(this)) {
            openApp(); // the permission is granted from the app screen
        } else {
            try {
                MonitorService.start(this);
            } catch (RuntimeException e) {
                openApp();
            }
        }
        update();
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private void openApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(i);
        }
    }

    private void update() {
        Tile t = getQsTile();
        if (t == null) {
            return;
        }
        lastState = MonitorService.isRunning();
        t.setState(lastState ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel(getString(R.string.tile_label));
        t.updateTile();
    }
}

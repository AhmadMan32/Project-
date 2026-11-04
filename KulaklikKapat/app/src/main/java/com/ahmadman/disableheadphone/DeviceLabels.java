package com.ahmadman.disableheadphone;

import android.content.Context;
import android.media.AudioDeviceInfo;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Human readable names for audio devices. */
final class DeviceLabels {

    private DeviceLabels() {
    }

    static String forType(Context c, int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return c.getString(R.string.out_speaker);
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
                return c.getString(R.string.out_earpiece);
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return c.getString(R.string.dev_wired_headset);
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                return c.getString(R.string.dev_wired_headphones);
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
            case AudioDeviceInfo.TYPE_AUX_LINE:
                return c.getString(R.string.dev_line);
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return c.getString(R.string.dev_usb);
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return c.getString(R.string.dev_bt);
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                return c.getString(R.string.dev_ble);
            default:
                return c.getString(R.string.out_other);
        }
    }

    static String join(Context c, List<AudioDeviceInfo> devices) {
        Set<String> names = new LinkedHashSet<>();
        for (AudioDeviceInfo d : devices) {
            names.add(forType(c, d.getType()));
        }
        if (names.isEmpty()) {
            return c.getString(R.string.status_none);
        }
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(n);
        }
        return sb.toString();
    }

    static String method(Context c, int method) {
        switch (method) {
            case AudioRouter.METHOD_FORCE_MEDIA:
                return c.getString(R.string.method_media);
            case AudioRouter.METHOD_COMMUNICATION:
                return c.getString(R.string.method_comm);
            default:
                return c.getString(R.string.method_none);
        }
    }
}

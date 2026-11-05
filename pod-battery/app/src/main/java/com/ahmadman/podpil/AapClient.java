package com.ahmadman.podpil;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Build;

import com.ahmadman.podpil.core.Aap;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Talks to the AirPods over their accessory channel (L2CAP PSM 0x1001) to get exact battery
 * percentages and their keys. Many Android versions refuse this channel (a bug in the
 * Bluetooth stack, fixed in newer builds); then the app keeps using the broadcast.
 */
final class AapClient implements Runnable {

    interface Callback {
        void onAapReady(AapClient c);

        void onAapFrame(AapClient c, byte[] frame);

        void onAapClosed(AapClient c, boolean wasReady);
    }

    private final BluetoothDevice device;
    private final Callback callback;
    private volatile BluetoothSocket socket;
    private volatile boolean closed;
    private volatile boolean ready;

    AapClient(BluetoothDevice device, Callback callback) {
        this.device = device;
        this.callback = callback;
    }

    BluetoothDevice device() {
        return device;
    }

    void start() {
        Thread t = new Thread(this, "aap");
        t.setDaemon(true);
        t.start();
    }

    void close() {
        closed = true;
        BluetoothSocket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }

    @Override
    @SuppressLint("MissingPermission")
    public void run() {
        try {
            BluetoothSocket s = open(device, Aap.PSM);
            if (s == null || closed) {
                return;
            }
            socket = s;
            // connect() blocks; give up after 6 seconds
            Thread watchdog = new Thread(() -> {
                try {
                    Thread.sleep(6000);
                    if (!s.isConnected()) {
                        close();
                    }
                } catch (InterruptedException ignored) {
                    // connected in time
                }
            }, "aap-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            s.connect();
            watchdog.interrupt();
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            for (byte[] packet : new byte[][]{Aap.HANDSHAKE, Aap.NOTIFY_1, Aap.NOTIFY_2, Aap.INIT_EXT, Aap.KEY_REQUEST}) {
                out.write(packet);
                out.flush();
                Thread.sleep(40);
            }
            byte[] buf = new byte[1024];
            while (!closed) {
                int n = in.read(buf); // one L2CAP frame per read
                if (n < 0) {
                    break;
                }
                byte[] frame = Arrays.copyOf(buf, n);
                if (!ready && (Aap.kind(frame) != Aap.Kind.OTHER)) {
                    ready = true;
                    callback.onAapReady(this);
                }
                callback.onAapFrame(this, frame);
            }
        } catch (IOException | InterruptedException | RuntimeException | ReflectiveOperationException
                 | LinkageError e) {
            // LinkageError: the hidden-API helper does not fit this Android build
            android.util.Log.i("PodPil", "accessory channel closed: " + e);
        } finally {
            close();
            callback.onAapClosed(this, ready);
        }
    }

    /** Public socket API where it exists (newer Android), else the hidden L2CAP socket. */
    @SuppressLint({"DiscouragedPrivateApi", "SoonBlockedPrivateApi", "PrivateApi"})
    static BluetoothSocket open(BluetoothDevice d, int psm) throws ReflectiveOperationException {
        try {
            Class<?> settingsClass = Class.forName("android.bluetooth.BluetoothSocketSettings");
            Class<?> builderClass = Class.forName("android.bluetooth.BluetoothSocketSettings$Builder");
            Object b = builderClass.getConstructor().newInstance();
            builderClass.getMethod("setSocketType", int.class).invoke(b, 3); // TYPE_L2CAP
            builderClass.getMethod("setL2capPsm", int.class).invoke(b, psm);
            builderClass.getMethod("setAuthenticationRequired", boolean.class).invoke(b, false);
            builderClass.getMethod("setEncryptionRequired", boolean.class).invoke(b, false);
            Object settings = builderClass.getMethod("build").invoke(b);
            Method create = BluetoothDevice.class.getMethod("createUsingSocketSettings", settingsClass);
            return (BluetoothSocket) create.invoke(d, settings);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            // older Android: fall through
        } catch (InvocationTargetException e) {
            if (!(e.getCause() instanceof IllegalArgumentException)) {
                throw e;
            }
        }
        Method m;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/");
            m = HiddenApiBypass.getDeclaredMethod(BluetoothDevice.class, "createInsecureL2capSocket", int.class);
        } else {
            m = BluetoothDevice.class.getDeclaredMethod("createInsecureL2capSocket", int.class);
        }
        m.setAccessible(true);
        return (BluetoothSocket) m.invoke(d, psm);
    }
}

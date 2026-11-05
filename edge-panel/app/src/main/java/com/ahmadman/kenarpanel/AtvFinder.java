package com.ahmadman.kenarpanel;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import com.ahmadman.kenarpanel.atv.Messages;

import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.Queue;

/** Finds Android TVs on the Wi-Fi (they announce "_androidtvremote2._tcp" over mDNS). */
final class AtvFinder {

    interface Found {
        void found(String name, String host, int port);
    }

    private final NsdManager nsd;
    private final Found found;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Queue<NsdServiceInfo> toResolve = new ArrayDeque<>();
    private boolean resolving;
    private boolean running;

    private final NsdManager.DiscoveryListener discovery = new NsdManager.DiscoveryListener() {
        @Override
        public void onStartDiscoveryFailed(String serviceType, int errorCode) {
            running = false;
        }

        @Override
        public void onStopDiscoveryFailed(String serviceType, int errorCode) {
        }

        @Override
        public void onDiscoveryStarted(String serviceType) {
        }

        @Override
        public void onDiscoveryStopped(String serviceType) {
        }

        @Override
        public void onServiceFound(NsdServiceInfo info) {
            main.post(() -> {
                toResolve.add(info);
                next();
            });
        }

        @Override
        public void onServiceLost(NsdServiceInfo info) {
        }
    };

    AtvFinder(Context c, Found found) {
        NsdManager m;
        try {
            m = (NsdManager) c.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        } catch (RuntimeException e) {
            m = null;
        }
        nsd = m;
        this.found = found;
    }

    void start() {
        if (nsd == null || running) {
            return;
        }
        try {
            nsd.discoverServices(Messages.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
            running = true;
        } catch (RuntimeException e) {
            running = false;
        }
    }

    void stop() {
        if (nsd != null && running) {
            try {
                nsd.stopServiceDiscovery(discovery);
            } catch (RuntimeException ignored) {
                // already stopped
            }
        }
        running = false;
        toResolve.clear();
    }

    /** Resolves one service at a time (older Android allows only one resolve at once). */
    @SuppressWarnings("deprecation")
    private void next() {
        if (resolving || toResolve.isEmpty() || nsd == null) {
            return;
        }
        resolving = true;
        NsdServiceInfo info = toResolve.poll();
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo s, int errorCode) {
                    main.post(() -> {
                        resolving = false;
                        next();
                    });
                }

                @Override
                public void onServiceResolved(NsdServiceInfo s) {
                    InetAddress a = s.getHost();
                    main.post(() -> {
                        resolving = false;
                        if (a != null && running) {
                            found.found(s.getServiceName(), a.getHostAddress(), s.getPort());
                        }
                        next();
                    });
                }
            });
        } catch (RuntimeException e) {
            resolving = false;
        }
    }
}

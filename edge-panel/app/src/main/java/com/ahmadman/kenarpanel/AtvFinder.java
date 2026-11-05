package com.ahmadman.kenarpanel;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.ahmadman.kenarpanel.atv.Messages;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * Finds Android TVs on the Wi-Fi. Up-to-date TVs announce "_androidtvremote2._tcp" over mDNS;
 * TVs whose remote service was never updated (some Mi Box 4) only the older type, and are
 * listed too so pairing can tell the user to update that service.
 */
final class AtvFinder {

    static final String OLD_SERVICE_TYPE = "_androidtvremote._tcp";

    interface Found {
        void found(String name, String host, int port);
    }

    private final NsdManager nsd;
    private final Found found;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Queue<NsdServiceInfo> toResolve = new ArrayDeque<>();
    private final List<NsdManager.DiscoveryListener> active = new ArrayList<>();
    private boolean resolving;
    private boolean running;

    private final class Discovery implements NsdManager.DiscoveryListener {
        @Override
        public void onStartDiscoveryFailed(String serviceType, int errorCode) {
            main.post(() -> active.remove(this));
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
    }

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
        for (String type : new String[]{Messages.SERVICE_TYPE, OLD_SERVICE_TYPE}) {
            Discovery d = new Discovery();
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, d);
                active.add(d);
            } catch (RuntimeException ignored) {
                // this type cannot be searched for
            }
        }
        running = !active.isEmpty();
    }

    void stop() {
        if (nsd != null) {
            for (NsdManager.DiscoveryListener d : active) {
                try {
                    nsd.stopServiceDiscovery(d);
                } catch (RuntimeException ignored) {
                    // already stopped
                }
            }
        }
        active.clear();
        running = false;
        toResolve.clear();
    }

    /** An IPv4 address when the TV has one (a bare IPv6 link-local address needs its interface). */
    @SuppressWarnings("deprecation")
    static InetAddress address(NsdServiceInfo s) {
        InetAddress first = s.getHost();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            List<InetAddress> all = s.getHostAddresses();
            for (InetAddress a : all) {
                if (a instanceof Inet4Address) {
                    return a;
                }
            }
            if (first == null && !all.isEmpty()) {
                first = all.get(0);
            }
        }
        return first;
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
                    InetAddress a = address(s);
                    int port = s.getServiceType() != null && s.getServiceType().contains(OLD_SERVICE_TYPE)
                            ? Messages.REMOTE_PORT : s.getPort();
                    main.post(() -> {
                        resolving = false;
                        if (a != null && running) {
                            found.found(s.getServiceName(), a.getHostAddress(), port);
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

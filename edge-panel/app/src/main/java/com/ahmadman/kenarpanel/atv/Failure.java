package com.ahmadman.kenarpanel.atv;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

/** Why talking to the TV failed, in words the screen can explain. */
public final class Failure {

    public enum Kind {
        /** No answer at that address: off, another Wi-Fi, or a new address. */
        UNREACHABLE,
        /** The address answers but nothing listens on the remote ports. */
        CLOSED_PORT,
        /** Connected, but the secure (TLS) handshake failed. */
        TLS,
        /** The TV's "Android TV Remote Service" speaks an older protocol: it needs an update. */
        OLD_SERVICE,
        /** The TV answered with an error to the pairing request. */
        TV_SAID_NO,
        OTHER
    }

    private Failure() {
    }

    public static Kind of(Throwable e) {
        if (e instanceof Pairing.OldServiceException) {
            return Kind.OLD_SERVICE;
        }
        if (e instanceof Pairing.RefusedException) {
            return Kind.TV_SAID_NO;
        }
        if (e instanceof Tls.HandshakeException) {
            return Kind.TLS;
        }
        if (e instanceof ConnectException) {
            String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
            return m.contains("refused") ? Kind.CLOSED_PORT : Kind.UNREACHABLE;
        }
        if (e instanceof SocketTimeoutException || e instanceof NoRouteToHostException
                || e instanceof UnknownHostException) {
            return Kind.UNREACHABLE;
        }
        return Kind.OTHER;
    }

    /** One short technical line (the deepest cause), for "send me a screenshot". */
    public static String detail(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String m = t.getMessage();
        String s = t.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ": " + m);
        return s.length() > 160 ? s.substring(0, 157) + "…" : s;
    }
}

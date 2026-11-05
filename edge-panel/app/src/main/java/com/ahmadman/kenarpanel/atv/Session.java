package com.ahmadman.kenarpanel.atv;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

/**
 * The remote channel to a paired Android TV (port 6466): answers the TV's set-up messages and
 * pings, and sends key presses. Reading and writing run on their own threads.
 */
public final class Session {

    public interface Listener {
        /** Keys can be sent now. */
        void onReady(Session s);

        /**
         * @param needsPairing the TV most likely refused this phone (forgotten, or its certificate
         *                     changed): show "pair again", but keep retrying
         */
        void onClosed(Session s, boolean needsPairing);
    }

    private final Tls.Identity identity;
    private final String host;
    private final int port;
    private final String pin;
    private final String model;
    private final String vendor;
    private final Listener listener;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "atv-write");
        t.setDaemon(true);
        return t;
    });
    private volatile SSLSocket socket;
    private volatile OutputStream out;
    private volatile boolean ready;
    private volatile boolean closed;
    private volatile boolean screenOn;
    private long tvFeatures = Messages.FEATURES;
    private int messages;
    private long connectedAt;

    public Session(Tls.Identity identity, String host, int port, String pin, String model, String vendor,
                   Listener listener) {
        this.identity = identity;
        this.host = host;
        this.port = port;
        this.pin = pin;
        this.model = model;
        this.vendor = vendor;
        this.listener = listener;
    }

    public String host() {
        return host;
    }

    public boolean ready() {
        return ready && !closed;
    }

    public boolean closed() {
        return closed;
    }

    /** The TV reported its screen as on (remote_start). */
    public boolean screenOn() {
        return screenOn;
    }

    public void start() {
        Thread t = new Thread(this::run, "atv-read");
        t.setDaemon(true);
        t.start();
    }

    private void run() {
        boolean needsPairing = false;
        try {
            SSLSocket s = Tls.connect(host, port, 5000, identity, new Tls.TvTrust(pin));
            connectedAt = System.currentTimeMillis();
            socket = s;
            if (closed) {
                return;
            }
            s.setSoTimeout(30_000); // the TV pings every few seconds
            out = s.getOutputStream();
            InputStream in = s.getInputStream();
            while (!closed) {
                byte[] msg = Proto.readDelimited(in);
                if (msg == null) {
                    needsPairing = messages == 0 && System.currentTimeMillis() - connectedAt < 3000;
                    break;
                }
                messages++;
                handle(Messages.remote(msg));
            }
        } catch (SocketTimeoutException e) {
            // silent TV: treat as gone
        } catch (SSLException e) {
            // a TLS alert before the channel came up: the TV refused this phone's certificate
            // (with TLS 1.3 that only shows on the first read) or its own certificate changed
            needsPairing = !ready;
        } catch (Tls.HandshakeException e) {
            needsPairing = true;
        } catch (IOException e) {
            // dropped right after the TLS handshake, before the TV said anything: with TLS 1.3 a
            // refused certificate can show up like this too (only a hint: the pairing is kept)
            needsPairing = socket != null && messages == 0
                    && System.currentTimeMillis() - connectedAt < 3000;
        } catch (RuntimeException e) {
            // unexpected data
        } finally {
            boolean wasClosed = closed;
            close();
            if (!wasClosed || needsPairing) {
                listener.onClosed(this, needsPairing);
            }
        }
    }

    private void handle(Messages.Incoming m) throws IOException {
        switch (m.kind) {
            case CONFIGURE:
                tvFeatures = m.value;
                write(Messages.configure(tvFeatures, model, vendor));
                break;
            case SET_ACTIVE:
                write(Messages.setActive(Messages.FEATURES & tvFeatures));
                becomeReady();
                break;
            case PING:
                write(Messages.pong(m.value));
                break;
            case START:
                screenOn = m.value != 0;
                becomeReady();
                break;
            default:
                break;
        }
    }

    private void becomeReady() {
        if (!ready) {
            ready = true;
            listener.onReady(this);
        }
    }

    private void write(byte[] msg) throws IOException {
        OutputStream o = out;
        if (o == null) {
            throw new IOException("not connected");
        }
        synchronized (this) {
            Proto.writeDelimited(o, msg);
        }
    }

    /** Sends a key press (never blocks the caller). @return false when not connected yet */
    public boolean key(int keyCode) {
        if (!ready()) {
            return false;
        }
        writer.execute(() -> {
            try {
                write(Messages.key(keyCode));
            } catch (IOException e) {
                close();
            }
        });
        return true;
    }

    public void close() {
        closed = true;
        ready = false;
        writer.shutdown();
        SSLSocket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closed
            }
        }
    }
}

package com.ahmadman.speedmeter.core;

/**
 * Turns the phone's total received / sent byte counters into speeds, plus totals and peaks.
 * Copes with unsupported counters (-1), counter resets and long gaps (screen off).
 */
public final class SpeedSampler {

    /** Gaps longer than this are not averaged into a speed (e.g. after the screen was off). */
    static final long MAX_GAP_MS = 10_000;

    private long lastRx = -1;
    private long lastTx = -1;
    private long lastTime;
    private double rxRate;
    private double txRate;
    private long totalRx;
    private long totalTx;
    private double peakRx;
    private double peakTx;
    private boolean supported = true;

    /**
     * @param rx  total received bytes (TrafficStats.getTotalRxBytes), -1 if unsupported
     * @param tx  total sent bytes
     * @param now elapsed realtime in milliseconds
     */
    public synchronized void sample(long rx, long tx, long now) {
        if (rx < 0 || tx < 0) {
            supported = false;
            rxRate = 0;
            txRate = 0;
            return;
        }
        supported = true;
        if (lastRx < 0 || now <= lastTime || now - lastTime > MAX_GAP_MS) {
            rxRate = 0;
            txRate = 0;
        } else {
            long dRx = Math.max(0, rx - lastRx); // counters reset when the network changes
            long dTx = Math.max(0, tx - lastTx);
            double seconds = (now - lastTime) / 1000.0;
            rxRate = dRx / seconds;
            txRate = dTx / seconds;
            totalRx += dRx;
            totalTx += dTx;
            peakRx = Math.max(peakRx, rxRate);
            peakTx = Math.max(peakTx, txRate);
        }
        lastRx = rx;
        lastTx = tx;
        lastTime = now;
    }

    /** Next sample starts a new measurement (keeps totals). */
    public synchronized void restart() {
        lastRx = -1;
        lastTx = -1;
        rxRate = 0;
        txRate = 0;
    }

    public synchronized void clearTotals() {
        totalRx = 0;
        totalTx = 0;
        peakRx = 0;
        peakTx = 0;
    }

    public synchronized double download() {
        return rxRate;
    }

    public synchronized double upload() {
        return txRate;
    }

    public synchronized long totalDownload() {
        return totalRx;
    }

    public synchronized long totalUpload() {
        return totalTx;
    }

    public synchronized double peakDownload() {
        return peakRx;
    }

    public synchronized double peakUpload() {
        return peakTx;
    }

    public synchronized boolean isSupported() {
        return supported;
    }
}

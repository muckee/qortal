package org.qortal.network.helper;

/**
 * Tracks download speed metrics for a peer, specifically for tracking round trip times.
 *
 * <p>This class tracks round trip times using an exponential moving average (EMA)
 * to smooth out temporary spikes while adapting to changing network conditions.
 * RTT values automatically expire after a period of inactivity to prevent stale
 * measurements from affecting peer selection.
 *
 * <p>It is separate from PeerSpeedTracker which tracks transfer time estimates.
 *
 * <p>Not thread-safe — external synchronization is required if used concurrently.
 *
 * @since v5.0.4
 * @author Auto
 * @updated v5.0.8 - Added exponential moving average and idle reset
 */
public class PeerDownloadSpeedTracker {

    private Long averageRoundTripTime = null; // Exponential moving average of RTT (null = no data yet)
    private Long lastUpdateTime = null; // Timestamp of last RTT measurement
    
    // EMA smoothing factor: 0.3 means new samples get 30% weight, old average gets 70%
    // This balances responsiveness (adapts to changes) with stability (filters noise)
    private static final double ALPHA = 0.3;
    
    // Reset RTT after 20 seconds of inactivity - allows faster recovery for degraded peers
    // Balanced between quick recovery and avoiding thrashing on genuinely slow peers
    private static final long IDLE_RESET_MS = 20_000; // 20 seconds

    /**
     * Constructs a {@code PeerDownloadSpeedTracker}.
     */
    public PeerDownloadSpeedTracker() {
    }

    /**
     * Records a new transfer time for download tracking.
     * Uses exponential moving average to smooth RTT measurements over time.
     *
     * @param bytes The number of bytes transferred in the latest measurement.
     * @param ms The time in milliseconds taken to transfer the {@code bytes}.
     */
    public void addNewTimeMetric(int bytes, int ms) {
        if (averageRoundTripTime == null) {
            // First measurement - use as initial value
            averageRoundTripTime = (long) ms;
        } else {
            // Exponential moving average: new_avg = alpha * new_value + (1 - alpha) * old_avg
            // This smooths out spikes while still adapting to changing conditions
            averageRoundTripTime = (long)(ALPHA * ms + (1 - ALPHA) * averageRoundTripTime);
        }
        lastUpdateTime = System.currentTimeMillis();
    }

    /**
     * Returns the smoothed round trip time (exponential moving average).
     * Returns null if no data has been received yet or if the RTT measurement
     * is stale (no activity for more than 20 seconds).
     *
     * @return the average round trip time in milliseconds, or null if no recent data
     */
    public Long getLatestRoundTripTime() {
        // Reset after 20 seconds of inactivity - network conditions may have changed
        // This prevents stale RTT values from affecting peer selection
        if (lastUpdateTime != null && 
            System.currentTimeMillis() - lastUpdateTime > IDLE_RESET_MS) {
            averageRoundTripTime = null;
            lastUpdateTime = null;
            return null;
        }
        return averageRoundTripTime;
    }
}


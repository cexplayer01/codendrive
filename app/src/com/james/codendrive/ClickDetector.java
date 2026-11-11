package com.james.codendrive;

/**
 * ClickSend detector (v3.4): pure-Java onset-pattern matcher for tongue
 * clicks. No Android imports, so the exact production class is host-tested
 * against the author's calibration sample (see the build report).
 *
 * Fed PCM16 mono samples from a parallel AudioRecord while dictation is
 * armed. Works on 5ms frames:
 *   - A rolling RMS floor tracks the room (fast down, very slow up).
 *   - A frame rising above max(floor * RISE_RATIO, ABS_MIN_RMS) opens a
 *     burst; the burst closes when the level falls back near the floor.
 *   - A burst that closes within MAX_BURST_MS AND peaked well clear of
 *     the room (PEAK_RATIO / ABS_PEAK_MIN) is a CLICK (broadband
 *     transient). A longer burst is speech/tone: it RESETS the match,
 *     which is what makes the trigger require a speech pause. A short
 *     but weak burst is ignored entirely (room noise).
 *   - Three clicks with inter-onset gaps in [GAP_MIN_MS, GAP_MAX_MS]
 *     fire onTripleClick exactly once, then lock out until reset().
 *     A trailing extra click after the triple therefore cannot re-fire.
 *
 * Calibrated from the author's recorded sample: triple gaps 85ms / 110ms,
 * clicks 22-31x background RMS, one accidental trailer ~210ms after the
 * third click (the service's cancel guard, not this class, absorbs it).
 */
public class ClickDetector {

    public interface Listener {
        /** Three-click pattern matched. Fires once per reset cycle. */
        void onTripleClick();
        /** Every validated click, including the ones that build a triple. */
        void onAnyClick();
    }

    static final int FRAME_MS = 5;
    static final double RISE_RATIO = 7.0;
    static final double RELEASE_RATIO = 2.0;
    static final double ABS_MIN_RMS = 1500.0;  // int16 units (~-27 dBFS)
    static final double PEAK_RATIO = 12.0;
    static final double ABS_PEAK_MIN = 1800.0;
    static final int MAX_BURST_MS = 90;
    static final int GAP_MIN_MS = 45;
    static final int GAP_MAX_MS = 260;
    static final int NEED = 3;

    private final int frameSamples;
    private final Listener listener;

    // frame accumulation
    private double sumSq = 0;
    private int filled = 0;
    private long totalFrames = 0;

    // floor + burst state
    private double floorRms = -1;
    private boolean inBurst = false;
    private long burstStartFrame = 0;
    private double burstPeak = 0;
    private double maxFrameRms = 0;

    // matcher state
    private int clickCount = 0;
    private long lastClickMs = -1;
    private boolean lockedOut = false;

    public ClickDetector(int sampleRate, Listener listener) {
        this.frameSamples = Math.max(1, sampleRate * FRAME_MS / 1000);
        this.listener = listener;
    }

    /** Feed PCM16 mono samples. Callbacks fire on the calling thread. */
    public synchronized void feed(short[] buf, int len) {
        for (int i = 0; i < len; i++) {
            double s = buf[i];
            sumSq += s * s;
            if (++filled >= frameSamples) {
                double rms = Math.sqrt(sumSq / frameSamples);
                sumSq = 0;
                filled = 0;
                onFrame(rms);
            }
        }
    }

    /** Clear matcher + lockout (service calls this when re-arming). */
    public synchronized void reset() {
        clickCount = 0;
        lastClickMs = -1;
        lockedOut = false;
        inBurst = false;
    }

    /** Loudest frame seen since construction; used by the deaf-stream check. */
    public double getMaxFrameRms() {
        return maxFrameRms;
    }

    private void onFrame(double rms) {
        totalFrames++;
        if (rms > maxFrameRms) maxFrameRms = rms;
        if (floorRms < 0) floorRms = Math.max(rms, 1.0);

        // Floor tracking: a slow ambient estimate in BOTH directions.
        // (Tuned on the calibration sample: a fast-falling floor collapsed
        // between clicks and let room noise compose false triples — the
        // floor must still be "the room" when the third click lands.)
        // Frozen during a burst so a click can't lift its own floor.
        if (rms < floorRms) {
            floorRms = floorRms * 0.98 + rms * 0.02;
        } else if (!inBurst) {
            floorRms = floorRms * 0.999 + rms * 0.001;
        }
        if (floorRms < 1.0) floorRms = 1.0;

        double threshold = Math.max(floorRms * RISE_RATIO, ABS_MIN_RMS);
        double release = Math.max(floorRms * RELEASE_RATIO, ABS_MIN_RMS * 0.5);

        if (!inBurst) {
            if (rms > threshold) {
                inBurst = true;
                burstStartFrame = totalFrames;
                burstPeak = rms;
            }
        } else {
            if (rms > burstPeak) burstPeak = rms;
            if (rms < release) {
                inBurst = false;
                long durMs = (totalFrames - burstStartFrame) * FRAME_MS;
                double peakNeed = Math.max(floorRms * PEAK_RATIO, ABS_PEAK_MIN);
                if (durMs <= MAX_BURST_MS && burstPeak >= peakNeed) {
                    onClick(burstStartFrame * FRAME_MS);
                } else if (durMs > MAX_BURST_MS) {
                    // Speech / tone / sustained noise breaks the pattern:
                    // the trigger only lives in a pause.
                    clickCount = 0;
                    lastClickMs = -1;
                }
                // A short but weak burst is just noise: neither a click
                // nor a reset — ignore it entirely.
            }
        }
    }

    private void onClick(long onsetMs) {
        if (listener != null) listener.onAnyClick();
        if (lockedOut) return;
        if (lastClickMs >= 0) {
            long gap = onsetMs - lastClickMs;
            if (gap < GAP_MIN_MS) {
                // Double peak of the same physical click: don't count it,
                // don't move the reference time.
                return;
            }
            if (gap > GAP_MAX_MS) {
                clickCount = 1;
            } else {
                clickCount++;
            }
        } else {
            clickCount = 1;
        }
        lastClickMs = onsetMs;
        if (clickCount >= NEED) {
            clickCount = 0;
            lastClickMs = -1;
            lockedOut = true;
            if (listener != null) listener.onTripleClick();
        }
    }
}

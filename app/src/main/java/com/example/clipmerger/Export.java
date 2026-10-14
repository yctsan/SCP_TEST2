package com.example.clipmerger;

/** Types shared by the two export paths. */
final class Export {

    private Export() {
    }

    /** Microseconds to media ticks, rounded to nearest. */
    static long usToTicks(long us, int timescale) {
        return (us * timescale + 500000L) / 1000000L;
    }

    interface Progress {
        /** Reports output progress; throws {@link Cancelled} when the user cancelled. */
        void update(long doneUs, long totalUs) throws Cancelled;
    }

    static final class Cancelled extends Exception {
        Cancelled() {
            super("Cancelled");
        }
    }

    /** User-facing failure (message is shown as-is). */
    static final class Failure extends Exception {
        Failure(String message) {
            super(message);
        }
    }

    static final class Options {
        boolean reencode;
        String mime = "video/hevc";
        /** Target length of the shorter side in pixels, or 0 to keep the source size. */
        int shortSide;
        int bitrate = 40000000;
    }
}

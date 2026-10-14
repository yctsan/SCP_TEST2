package com.example.clipmerger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Copies compressed audio samples (no re-encoding) from each clip's source
 * into the output, keeping the audio track continuous across clip boundaries.
 */
final class AudioCopier {

    /** Decides whether audio can be passed through; returns a warning for the user, or null. */
    static String plan(List<Mp4Source> sources, boolean[] includeOut) {
        Mp4Source.Track first = null;
        boolean missing = false;
        for (Mp4Source s : sources) {
            if (s.audio == null) {
                missing = true;
                continue;
            }
            if (first == null) {
                first = s.audio;
            } else if (!Mp4Source.audioCompatible(first, s.audio)) {
                includeOut[0] = false;
                return "The clips use different audio formats, so the output has no audio.";
            }
        }
        includeOut[0] = first != null;
        if (first != null && missing) return "Some clips have no audio track; those parts are silent.";
        return null;
    }

    static Mp4Source.Track firstAudio(List<Mp4Source> sources) {
        for (Mp4Source s : sources) if (s.audio != null) return s.audio;
        return null;
    }

    private final Mp4Writer writer;
    private final int track;
    private final int outTimescale;
    private final ByteBuffer buf;
    private long nextTicks;

    private Mp4Source source;
    private Mp4Source.Track src;
    private int index;
    private long srcStartUs, srcEndUs, dstStartUs;

    AudioCopier(Mp4Writer writer, int track, int maxSampleSize) {
        this.writer = writer;
        this.track = track;
        this.outTimescale = writer.timescale(track);
        this.buf = ByteBuffer.allocate(Math.max(maxSampleSize, 8192));
    }

    /** Starts a segment: source audio from srcStartUs..srcEndUs is placed at dstStartUs. */
    void begin(Mp4Source source, long srcStartUs, long srcEndUs, long dstStartUs) {
        this.source = source;
        this.src = source.audio;
        this.srcStartUs = srcStartUs;
        this.srcEndUs = srcEndUs;
        this.dstStartUs = dstStartUs;
        this.index = src != null ? src.firstIndexAtOrAfter(srcStartUs) : 0;
    }

    /** Source pts (µs) of the next audio sample of this segment, or Long.MAX_VALUE when done. */
    long nextUs() {
        if (src == null || index >= src.count) return Long.MAX_VALUE;
        long us = src.ptsUs(index);
        return us < srcEndUs ? us : Long.MAX_VALUE;
    }

    /** Writes audio samples whose source pts is below {@code srcUs}. */
    void pumpUntil(long srcUs) throws IOException {
        while (true) {
            long us = nextUs();
            if (us == Long.MAX_VALUE || us >= srcUs) return;
            writeOne();
        }
    }

    void finishSegment() throws IOException {
        pumpUntil(Long.MAX_VALUE);
        src = null;
        source = null;
    }

    private void writeOne() throws IOException {
        long us = src.ptsUs(index);
        long ticks = Export.usToTicks(dstStartUs + (us - srcStartUs), outTimescale);
        long dur = toOut(src.durTicks(index));
        if (ticks + dur / 2 >= nextTicks) {
            if (ticks < nextTicks) ticks = nextTicks;
            source.readSample(src, index, buf);
            writer.writeSample(track, buf, ticks, true);
            nextTicks = ticks + dur;
            writer.setLastSampleDuration(track, dur);
        }
        index++;
    }

    private long toOut(long srcTicks) {
        return srcTicks * outTimescale / src.timescale;
    }
}

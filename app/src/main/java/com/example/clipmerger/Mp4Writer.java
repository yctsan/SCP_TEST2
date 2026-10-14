package com.example.clipmerger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Minimal MP4 writer with 64-bit chunk offsets (no 4 GB limit). Samples are
 * appended to one large 'mdat'; the 'moov' box is written at the end.
 * Samples are given in decode order with presentation timestamps; decode
 * timestamps and composition offsets are derived from those.
 */
final class Mp4Writer {

    private static final int MOVIE_TIMESCALE = 1000;
    private static final byte[] IDENTITY_MATRIX = {
            0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0x40, 0, 0, 0};

    private static final class TrackOut {
        final boolean video;
        final int timescale;
        byte[] stsd;
        byte[] matrix = IDENTITY_MATRIX;
        int width, height;
        long lastDuration;
        int count;
        long[] pts = new long[1024];
        int[] size = new int[1024];
        boolean[] sync = new boolean[1024];
        int chunkCount;
        long[] chunkOffset = new long[256];
        int[] chunkSamples = new int[256];

        TrackOut(boolean video, int timescale) {
            this.video = video;
            this.timescale = timescale;
        }

        void add(long p, int s, boolean k) {
            if (count == pts.length) {
                pts = Arrays.copyOf(pts, count * 2);
                size = Arrays.copyOf(size, count * 2);
                sync = Arrays.copyOf(sync, count * 2);
            }
            pts[count] = p;
            size[count] = s;
            sync[count] = k;
            count++;
        }

        void newChunk(long offset) {
            if (chunkCount == chunkOffset.length) {
                chunkOffset = Arrays.copyOf(chunkOffset, chunkCount * 2);
                chunkSamples = Arrays.copyOf(chunkSamples, chunkCount * 2);
            }
            chunkOffset[chunkCount] = offset;
            chunkSamples[chunkCount] = 1;
            chunkCount++;
        }
    }

    private final FileChannel out;
    private final ArrayList<TrackOut> tracks = new ArrayList<>();
    private final long mdatStart;
    private long pos;
    private int lastTrack = -1;
    private long lastEnd = -1;

    Mp4Writer(FileChannel out) throws IOException {
        this.out = out;
        out.truncate(0);
        Buf ftyp = new Buf();
        ftyp.fourcc("isom").u32(0x200).fourcc("isom").fourcc("iso2").fourcc("mp41");
        write(ByteBuffer.wrap(box("ftyp", ftyp.bytes())));
        mdatStart = pos;
        Buf mdat = new Buf();
        mdat.u32(1).fourcc("mdat").u64(0);
        write(ByteBuffer.wrap(mdat.bytes()));
    }

    int addTrack(boolean video, int timescale) {
        tracks.add(new TrackOut(video, timescale));
        return tracks.size() - 1;
    }

    void setSampleEntry(int track, byte[] stsdBox) {
        tracks.get(track).stsd = stsdBox;
    }

    boolean hasSampleEntry(int track) {
        return tracks.get(track).stsd != null;
    }

    void setVideoInfo(int track, byte[] matrix, int width, int height) {
        TrackOut t = tracks.get(track);
        if (matrix != null && matrix.length == 36) t.matrix = matrix;
        t.width = width;
        t.height = height;
    }

    /** Duration (media ticks) of the final sample in the track. */
    void setLastSampleDuration(int track, long ticks) {
        tracks.get(track).lastDuration = Math.max(1, ticks);
    }

    int timescale(int track) {
        return tracks.get(track).timescale;
    }

    /** Appends one sample. {@code data} is consumed from position to limit. */
    void writeSample(int track, ByteBuffer data, long ptsTicks, boolean sync) throws IOException {
        TrackOut t = tracks.get(track);
        long offset = pos;
        int size = data.remaining();
        write(data);
        t.add(ptsTicks, size, sync);
        if (track == lastTrack && offset == lastEnd && t.chunkCount > 0) {
            t.chunkSamples[t.chunkCount - 1]++;
        } else {
            t.newChunk(offset);
        }
        lastTrack = track;
        lastEnd = pos;
    }

    long bytesWritten() {
        return pos;
    }

    void finish() throws IOException {
        long mdatSize = pos - mdatStart;
        Buf moov = new Buf();
        long movieDuration = 0;
        int trackId = 1;
        ArrayList<byte[]> traks = new ArrayList<>();
        for (TrackOut t : tracks) {
            if (t.count == 0) continue;
            if (t.stsd == null) throw new IOException("Internal error: missing sample description");
            long[] presentation = new long[1];
            traks.add(buildTrak(t, trackId++, presentation));
            movieDuration = Math.max(movieDuration, presentation[0]);
        }
        if (traks.isEmpty()) throw new IOException("Nothing was written");

        Buf mvhd = new Buf();
        mvhd.u32(0x01000000).u64(0).u64(0).u32(MOVIE_TIMESCALE).u64(movieDuration)
                .u32(0x00010000).u16(0x0100).zeros(10).raw(IDENTITY_MATRIX).zeros(24).u32(trackId);
        moov.raw(box("mvhd", mvhd.bytes()));
        for (byte[] trak : traks) moov.raw(trak);
        write(ByteBuffer.wrap(box("moov", moov.bytes())));

        ByteBuffer size = ByteBuffer.allocate(8);
        size.putLong(0, mdatSize);
        long p = mdatStart + 8;
        while (size.hasRemaining()) p += out.write(size, p);
        out.force(false);
    }

    private byte[] buildTrak(TrackOut t, int trackId, long[] presentationOut) {
        int n = t.count;
        long[] sorted = Arrays.copyOf(t.pts, n);
        Arrays.sort(sorted);
        for (int i = 1; i < n; i++) if (sorted[i] <= sorted[i - 1]) sorted[i] = sorted[i - 1] + 1;
        long delay = 0;
        for (int i = 0; i < n; i++) delay = Math.max(delay, sorted[i] - t.pts[i]);
        long start = sorted[0];
        long lastDur = t.lastDuration > 0 ? t.lastDuration : (n > 1 ? sorted[n - 1] - sorted[n - 2] : 1);
        long mediaDuration = sorted[n - 1] - start + lastDur;
        long movieStart = Math.max(0, start) * MOVIE_TIMESCALE / t.timescale;
        long movieMediaDur = mediaDuration * MOVIE_TIMESCALE / t.timescale;
        presentationOut[0] = movieStart + movieMediaDur;

        // stts
        Buf stts = new Buf();
        int sttsEntries = 0;
        Buf sttsBody = new Buf();
        long runDelta = -1;
        int runCount = 0;
        for (int i = 0; i < n; i++) {
            long d = i + 1 < n ? sorted[i + 1] - sorted[i] : lastDur;
            if (d == runDelta) {
                runCount++;
            } else {
                if (runCount > 0) {
                    sttsBody.u32(runCount).u32((int) runDelta);
                    sttsEntries++;
                }
                runDelta = d;
                runCount = 1;
            }
        }
        sttsBody.u32(runCount).u32((int) runDelta);
        sttsEntries++;
        stts.u32(0).u32(sttsEntries).raw(sttsBody.bytes());

        // ctts
        byte[] cttsBox = null;
        boolean needCtts = false;
        for (int i = 0; i < n; i++) if (t.pts[i] - sorted[i] + delay != 0) needCtts = true;
        if (needCtts) {
            Buf body = new Buf();
            int entries = 0;
            long runOff = Long.MIN_VALUE;
            runCount = 0;
            for (int i = 0; i < n; i++) {
                long off = t.pts[i] - sorted[i] + delay;
                if (off == runOff) {
                    runCount++;
                } else {
                    if (runCount > 0) {
                        body.u32(runCount).u32((int) runOff);
                        entries++;
                    }
                    runOff = off;
                    runCount = 1;
                }
            }
            body.u32(runCount).u32((int) runOff);
            entries++;
            Buf c = new Buf();
            c.u32(0).u32(entries).raw(body.bytes());
            cttsBox = box("ctts", c.bytes());
        }

        // stss
        byte[] stssBox = null;
        int syncCount = 0;
        for (int i = 0; i < n; i++) if (t.sync[i]) syncCount++;
        if (syncCount != n) {
            Buf s = new Buf();
            s.u32(0).u32(syncCount);
            for (int i = 0; i < n; i++) if (t.sync[i]) s.u32(i + 1);
            stssBox = box("stss", s.bytes());
        }

        // stsz
        Buf stsz = new Buf();
        boolean sameSize = true;
        for (int i = 1; i < n; i++) if (t.size[i] != t.size[0]) sameSize = false;
        if (sameSize) {
            stsz.u32(0).u32(t.size[0]).u32(n);
        } else {
            stsz.u32(0).u32(0).u32(n);
            for (int i = 0; i < n; i++) stsz.u32(t.size[i]);
        }

        // stsc
        Buf stscBody = new Buf();
        int stscEntries = 0;
        int prev = -1;
        for (int c = 0; c < t.chunkCount; c++) {
            if (t.chunkSamples[c] != prev) {
                stscBody.u32(c + 1).u32(t.chunkSamples[c]).u32(1);
                stscEntries++;
                prev = t.chunkSamples[c];
            }
        }
        Buf stsc = new Buf();
        stsc.u32(0).u32(stscEntries).raw(stscBody.bytes());

        // co64
        Buf co64 = new Buf();
        co64.u32(0).u32(t.chunkCount);
        for (int c = 0; c < t.chunkCount; c++) co64.u64(t.chunkOffset[c]);

        Buf stbl = new Buf();
        stbl.raw(t.stsd).raw(box("stts", stts.bytes()));
        if (cttsBox != null) stbl.raw(cttsBox);
        if (stssBox != null) stbl.raw(stssBox);
        stbl.raw(box("stsc", stsc.bytes())).raw(box("stsz", stsz.bytes())).raw(box("co64", co64.bytes()));

        Buf url = new Buf();
        url.u32(1);
        Buf dref = new Buf();
        dref.u32(0).u32(1).raw(box("url ", url.bytes()));
        byte[] dinf = box("dinf", box("dref", dref.bytes()));

        Buf mediaHeader = new Buf();
        if (t.video) {
            mediaHeader.u32(1).zeros(8);
        } else {
            mediaHeader.u32(0).zeros(4);
        }
        Buf minf = new Buf();
        minf.raw(box(t.video ? "vmhd" : "smhd", mediaHeader.bytes())).raw(dinf).raw(box("stbl", stbl.bytes()));

        Buf hdlr = new Buf();
        hdlr.u32(0).u32(0).fourcc(t.video ? "vide" : "soun").zeros(12)
                .raw((t.video ? "VideoHandler" : "SoundHandler").getBytes(StandardCharsets.US_ASCII)).u8(0);

        Buf mdhd = new Buf();
        mdhd.u32(0x01000000).u64(0).u64(0).u32(t.timescale).u64(mediaDuration).u16(0x55C4).u16(0);

        Buf mdia = new Buf();
        mdia.raw(box("mdhd", mdhd.bytes())).raw(box("hdlr", hdlr.bytes())).raw(box("minf", minf.bytes()));

        Buf tkhd = new Buf();
        tkhd.u32(0x01000003).u64(0).u64(0).u32(trackId).u32(0).u64(presentationOut[0]).zeros(8)
                .u16(0).u16(t.video ? 0 : 1).u16(t.video ? 0 : 0x0100).u16(0).raw(t.matrix)
                .u32(t.video ? t.width << 16 : 0).u32(t.video ? t.height << 16 : 0);

        Buf trak = new Buf();
        trak.raw(box("tkhd", tkhd.bytes()));
        if (movieStart > 0 || delay != 0) {
            Buf elst = new Buf();
            int entries = movieStart > 0 ? 2 : 1;
            elst.u32(0x01000000).u32(entries);
            if (movieStart > 0) elst.u64(movieStart).u64(-1L).u16(1).u16(0);
            elst.u64(movieMediaDur).u64(delay).u16(1).u16(0);
            trak.raw(box("edts", box("elst", elst.bytes())));
        }
        trak.raw(box("mdia", mdia.bytes()));
        return box("trak", trak.bytes());
    }

    private void write(ByteBuffer b) throws IOException {
        while (b.hasRemaining()) pos += out.write(b, pos);
    }

    static byte[] box(String type, byte[] payload) {
        Buf b = new Buf();
        b.u32(8 + payload.length).fourcc(type).raw(payload);
        return b.bytes();
    }

    /** Big-endian byte builder. */
    static final class Buf {
        private final ByteArrayOutputStream bos = new ByteArrayOutputStream();

        Buf u8(int v) {
            bos.write(v);
            return this;
        }

        Buf u16(int v) {
            bos.write(v >>> 8);
            bos.write(v);
            return this;
        }

        Buf u32(int v) {
            bos.write(v >>> 24);
            bos.write(v >>> 16);
            bos.write(v >>> 8);
            bos.write(v);
            return this;
        }

        Buf u64(long v) {
            u32((int) (v >>> 32));
            return u32((int) v);
        }

        Buf fourcc(String s) {
            return raw(s.getBytes(StandardCharsets.ISO_8859_1));
        }

        Buf zeros(int n) {
            for (int i = 0; i < n; i++) bos.write(0);
            return this;
        }

        Buf raw(byte[] b) {
            bos.write(b, 0, b.length);
            return this;
        }

        byte[] bytes() {
            return bos.toByteArray();
        }
    }
}

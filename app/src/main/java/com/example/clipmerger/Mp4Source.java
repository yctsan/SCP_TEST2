package com.example.clipmerger;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

/**
 * Minimal ISO-BMFF (MP4/MOV) demuxer. Reads the sample tables of the first
 * video and first audio track so samples can be copied byte-for-byte.
 */
final class Mp4Source implements Closeable {

    static final int TYPE_MOOV = fourcc("moov");
    private static final int TYPE_MVHD = fourcc("mvhd");
    private static final int TYPE_TRAK = fourcc("trak");
    private static final int TYPE_TKHD = fourcc("tkhd");
    private static final int TYPE_EDTS = fourcc("edts");
    private static final int TYPE_ELST = fourcc("elst");
    private static final int TYPE_MDIA = fourcc("mdia");
    private static final int TYPE_MDHD = fourcc("mdhd");
    private static final int TYPE_HDLR = fourcc("hdlr");
    private static final int TYPE_MINF = fourcc("minf");
    private static final int TYPE_STBL = fourcc("stbl");
    private static final int TYPE_STSD = fourcc("stsd");
    private static final int TYPE_STTS = fourcc("stts");
    private static final int TYPE_CTTS = fourcc("ctts");
    private static final int TYPE_STSS = fourcc("stss");
    private static final int TYPE_STSZ = fourcc("stsz");
    private static final int TYPE_STZ2 = fourcc("stz2");
    private static final int TYPE_STSC = fourcc("stsc");
    private static final int TYPE_STCO = fourcc("stco");
    private static final int TYPE_CO64 = fourcc("co64");
    private static final int HANDLER_VIDE = fourcc("vide");
    private static final int HANDLER_SOUN = fourcc("soun");

    /** One track's sample table, expanded to per-sample arrays in decode order. */
    static final class Track {
        boolean isVideo;
        int timescale;
        /** The complete 'stsd' box, header included. */
        byte[] stsd;
        String fourcc = "";
        int width, height;           // coded size from the sample entry
        int channels, sampleRate;
        /** Payload of the hvcC / avcC box, or null. */
        byte[] codecConfig;
        /** 36-byte tkhd transformation matrix. */
        byte[] matrix;
        int count;
        long[] offset;
        int[] size;
        long[] dts;
        /** Presentation time in media timescale, after applying the edit list. */
        long[] pts;
        boolean[] sync;
        int maxSampleSize;

        long ptsUs(int i) {
            return pts[i] * 1000000L / timescale;
        }

        /** Duration of sample i in media ticks. */
        long durTicks(int i) {
            if (i + 1 < count) return dts[i + 1] - dts[i];
            if (count > 1) return dts[count - 1] - dts[count - 2];
            return timescale / 30;
        }

        long frameDurUs() {
            if (count < 2) return 33333;
            long d = (dts[count - 1] - dts[0]) / (count - 1);
            return Math.max(1, d * 1000000L / timescale);
        }

        long durationUs() {
            long max = 0;
            for (int i = 0; i < count; i++) max = Math.max(max, pts[i]);
            return max * 1000000L / timescale + frameDurUs();
        }

        /** Index (decode order) of the last sync sample whose pts is at or before {@code us}. */
        int syncIndexAtOrBefore(long us) {
            int best = -1;
            for (int i = 0; i < count; i++) {
                if (!sync[i]) continue;
                if (best < 0) best = i;
                if (ptsUs(i) <= us) best = i; else break;
            }
            return best < 0 ? 0 : best;
        }

        /** First sample index (decode order) with pts at or after {@code us}. Audio only. */
        int firstIndexAtOrAfter(long us) {
            for (int i = 0; i < count; i++) if (ptsUs(i) >= us) return i;
            return count;
        }

        String mime() {
            if ("hvc1".equals(fourcc) || "hev1".equals(fourcc)) return "video/hevc";
            if ("avc1".equals(fourcc) || "avc3".equals(fourcc)) return "video/avc";
            return null;
        }

        /** NAL unit length field size from hvcC / avcC. */
        int nalLengthSize() {
            if (codecConfig == null) return 4;
            if ("video/hevc".equals(mime()) && codecConfig.length > 21) return (codecConfig[21] & 3) + 1;
            if ("video/avc".equals(mime()) && codecConfig.length > 4) return (codecConfig[4] & 3) + 1;
            return 4;
        }

        /** Rotation in degrees derived from the tkhd matrix. */
        int rotation() {
            if (matrix == null) return 0;
            ByteBuffer m = ByteBuffer.wrap(matrix);
            int a = m.getInt(0), b = m.getInt(4);
            if (a == 0 && b > 0) return 90;
            if (a == 0 && b < 0) return 270;
            if (a < 0) return 180;
            return 0;
        }
    }

    private final Closeable owner;
    private final FileChannel ch;
    final Track video;
    final Track audio;

    /** Parses the file behind {@code ch}; {@code owner} is closed together with the channel. */
    Mp4Source(FileChannel ch, Closeable owner) throws IOException {
        this.owner = owner;
        this.ch = ch;
        long fileSize = ch.size();
        if (fileSize <= 0) throw new IOException("File is not seekable (is it on device storage?)");
        byte[] moov = null;
        long pos = 0;
        while (pos + 8 <= fileSize) {
            ByteBuffer h = readAt(pos, (int) Math.min(16, fileSize - pos));
            long size = h.getInt(0) & 0xffffffffL;
            int type = h.getInt(4);
            int hdr = 8;
            if (size == 1) {
                if (h.limit() < 16) break;
                size = h.getLong(8);
                hdr = 16;
            } else if (size == 0) {
                size = fileSize - pos;
            }
            if (size < hdr) throw new IOException("Corrupt MP4 (bad box size)");
            if (type == TYPE_MOOV) {
                if (size - hdr > 256L * 1024 * 1024) throw new IOException("MP4 header too large");
                ByteBuffer b = readAt(pos + hdr, (int) (size - hdr));
                if (b.limit() != size - hdr) throw new IOException("Truncated MP4 header");
                moov = b.array();
                break;
            }
            pos += size;
        }
        if (moov == null) throw new IOException("No 'moov' box: not an MP4 file, or the recording is incomplete");

        int movieTimescale = 1000;
        Track v = null, vAny = null, a = null;
        for (int[] box : children(moov, 0, moov.length)) {
            if (box[0] == TYPE_MVHD) {
                int p = box[1];
                movieTimescale = moov[p] == 1 ? u32(moov, p + 20) : u32(moov, p + 12);
            }
        }
        for (int[] box : children(moov, 0, moov.length)) {
            if (box[0] != TYPE_TRAK) continue;
            Track t = parseTrak(moov, box[1], box[2], movieTimescale);
            if (t == null || t.count == 0) continue;
            if (t.isVideo) {
                if (vAny == null) vAny = t;
                if (v == null && t.mime() != null) v = t;
            } else if (a == null) {
                a = t;
            }
        }
        video = v != null ? v : vAny;
        audio = a;
    }

    static Mp4Source open(Context ctx, Uri uri) throws IOException {
        ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "r");
        if (pfd == null) throw new IOException("Cannot open " + uri);
        try {
            return new Mp4Source(new FileInputStream(pfd.getFileDescriptor()).getChannel(), pfd);
        } catch (IOException | RuntimeException e) {
            try {
                pfd.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    /** Reads sample {@code i} of {@code t} into {@code dst} (cleared first, flipped after). */
    void readSample(Track t, int i, ByteBuffer dst) throws IOException {
        dst.clear();
        dst.limit(t.size[i]);
        long p = t.offset[i];
        while (dst.hasRemaining()) {
            int n = ch.read(dst, p);
            if (n < 0) throw new IOException("Unexpected end of file");
            p += n;
        }
        dst.flip();
    }

    @Override
    public void close() {
        try {
            ch.close();
        } catch (IOException ignored) {
        }
        try {
            if (owner != null) owner.close();
        } catch (IOException ignored) {
        }
    }

    private ByteBuffer readAt(long pos, int len) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(len);
        while (b.hasRemaining()) {
            int n = ch.read(b, pos + b.position());
            if (n < 0) break;
        }
        b.flip();
        return b;
    }

    private static Track parseTrak(byte[] b, int start, int end, int movieTs) throws IOException {
        Track t = new Track();
        int handler = 0;
        long emptyEdit = 0, mediaTime = 0;
        int[] stbl = null;
        for (int[] box : children(b, start, end)) {
            int p = box[1];
            if (box[0] == TYPE_TKHD) {
                int m = p + (b[p] == 1 ? 52 : 40);
                if (m + 44 <= box[2]) {
                    t.matrix = Arrays.copyOfRange(b, m, m + 36);
                }
            } else if (box[0] == TYPE_EDTS) {
                for (int[] e : children(b, p, box[2])) {
                    if (e[0] != TYPE_ELST) continue;
                    int q = e[1];
                    int version = b[q];
                    int n = u32(b, q + 4);
                    int off = q + 8;
                    for (int i = 0; i < n; i++) {
                        long segDur, mt;
                        if (version == 1) {
                            segDur = s64(b, off);
                            mt = s64(b, off + 8);
                            off += 20;
                        } else {
                            segDur = u32(b, off) & 0xffffffffL;
                            mt = u32(b, off + 4);
                            off += 12;
                        }
                        if (mt == -1) {
                            emptyEdit += segDur;
                        } else {
                            mediaTime = mt;
                            break;
                        }
                    }
                }
            } else if (box[0] == TYPE_MDIA) {
                for (int[] m : children(b, p, box[2])) {
                    int q = m[1];
                    if (m[0] == TYPE_MDHD) {
                        t.timescale = b[q] == 1 ? u32(b, q + 20) : u32(b, q + 12);
                    } else if (m[0] == TYPE_HDLR) {
                        handler = u32(b, q + 8);
                    } else if (m[0] == TYPE_MINF) {
                        for (int[] s : children(b, q, m[2])) {
                            if (s[0] == TYPE_STBL) stbl = s;
                        }
                    }
                }
            }
        }
        if (handler != HANDLER_VIDE && handler != HANDLER_SOUN) return null;
        if (stbl == null || t.timescale <= 0) return null;
        t.isVideo = handler == HANDLER_VIDE;
        parseStbl(t, b, stbl[1], stbl[2]);
        long shift = -mediaTime + emptyEdit * t.timescale / Math.max(1, movieTs);
        for (int i = 0; i < t.count; i++) t.pts[i] += shift;
        return t;
    }

    private static void parseStbl(Track t, byte[] b, int start, int end) throws IOException {
        int[] stts = null, ctts = null, stss = null, stsz = null, stsc = null, stco = null;
        boolean co64 = false;
        for (int[] box : children(b, start, end)) {
            int type = box[0];
            if (type == TYPE_STSD) {
                t.stsd = Arrays.copyOfRange(b, box[3], box[2]);
                parseSampleEntry(t);
            } else if (type == TYPE_STTS) stts = box;
            else if (type == TYPE_CTTS) ctts = box;
            else if (type == TYPE_STSS) stss = box;
            else if (type == TYPE_STSZ) stsz = box;
            else if (type == TYPE_STZ2) throw new IOException("Unsupported MP4 (stz2)");
            else if (type == TYPE_STSC) stsc = box;
            else if (type == TYPE_STCO) stco = box;
            else if (type == TYPE_CO64) {
                stco = box;
                co64 = true;
            }
        }
        if (t.stsd == null || stts == null || stsz == null || stsc == null || stco == null) {
            throw new IOException("Incomplete MP4 sample table");
        }
        int fixedSize = u32(b, stsz[1] + 4);
        int n = u32(b, stsz[1] + 8);
        if (n < 0 || n > 50000000) throw new IOException("Bad sample count");
        t.count = n;
        t.size = new int[n];
        for (int i = 0; i < n; i++) {
            t.size[i] = fixedSize != 0 ? fixedSize : u32(b, stsz[1] + 12 + 4 * i);
            if (t.size[i] < 0) throw new IOException("Bad sample size");
            t.maxSampleSize = Math.max(t.maxSampleSize, t.size[i]);
        }

        t.dts = new long[n];
        int entries = u32(b, stts[1] + 4);
        int s = 0;
        long time = 0, lastDelta = 0;
        for (int e = 0; e < entries && s < n; e++) {
            int cnt = u32(b, stts[1] + 8 + 8 * e);
            lastDelta = u32(b, stts[1] + 12 + 8 * e) & 0xffffffffL;
            for (int k = 0; k < cnt && s < n; k++) {
                t.dts[s++] = time;
                time += lastDelta;
            }
        }
        while (s < n) {
            t.dts[s++] = time;
            time += lastDelta;
        }

        t.pts = t.dts.clone();
        if (ctts != null) {
            entries = u32(b, ctts[1] + 4);
            s = 0;
            for (int e = 0; e < entries && s < n; e++) {
                int cnt = u32(b, ctts[1] + 8 + 8 * e);
                int off = u32(b, ctts[1] + 12 + 8 * e);
                for (int k = 0; k < cnt && s < n; k++) t.pts[s++] += off;
            }
        }

        t.sync = new boolean[n];
        if (stss == null) {
            Arrays.fill(t.sync, true);
        } else {
            entries = u32(b, stss[1] + 4);
            for (int e = 0; e < entries; e++) {
                int idx = u32(b, stss[1] + 8 + 4 * e) - 1;
                if (idx >= 0 && idx < n) t.sync[idx] = true;
            }
        }

        int chunkCount = u32(b, stco[1] + 4);
        long[] chunkOff = new long[chunkCount];
        for (int c = 0; c < chunkCount; c++) {
            chunkOff[c] = co64 ? s64(b, stco[1] + 8 + 8 * c) : (u32(b, stco[1] + 8 + 4 * c) & 0xffffffffL);
        }
        t.offset = new long[n];
        entries = u32(b, stsc[1] + 4);
        s = 0;
        for (int e = 0; e < entries && s < n; e++) {
            int first = u32(b, stsc[1] + 8 + 12 * e);
            int perChunk = u32(b, stsc[1] + 12 + 12 * e);
            int last = e + 1 < entries ? u32(b, stsc[1] + 8 + 12 * (e + 1)) - 1 : chunkCount;
            for (int c = first; c <= last && c <= chunkCount && s < n; c++) {
                long off = chunkOff[c - 1];
                for (int k = 0; k < perChunk && s < n; k++) {
                    t.offset[s] = off;
                    off += t.size[s];
                    s++;
                }
            }
        }
        if (s < n) throw new IOException("Corrupt MP4 chunk table");
    }

    private static void parseSampleEntry(Track t) {
        byte[] d = t.stsd;
        int e = 16; // box header (8) + version/flags (4) + entry_count (4)
        if (d.length < e + 8) return;
        int entrySize = u32(d, e);
        int entryEnd = Math.min(d.length, e + entrySize);
        t.fourcc = new String(d, e + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (d.length >= e + 36) {
            t.width = u16(d, e + 32);
            t.height = u16(d, e + 34);
            t.channels = u16(d, e + 24);
            t.sampleRate = u32(d, e + 32) >>> 16;
        }
        int childStart = e + 86;
        if (t.isVideo && childStart < entryEnd) {
            for (int[] c : children(d, childStart, entryEnd)) {
                if (c[0] == fourcc("hvcC") || c[0] == fourcc("avcC")) {
                    t.codecConfig = Arrays.copyOfRange(d, c[1], c[2]);
                }
            }
        }
    }

    /** Returns null when the two video tracks can be joined without re-encoding, else a reason. */
    static String videoMismatch(Track a, Track b) {
        if (!a.fourcc.equals(b.fourcc)) return "uses a different codec (" + b.fourcc + " vs " + a.fourcc + ")";
        if (a.width != b.width || a.height != b.height) {
            return "has a different resolution (" + b.width + "×" + b.height + " vs " + a.width + "×" + a.height + ")";
        }
        if (!Arrays.equals(a.codecConfig, b.codecConfig)) return "was recorded with different codec settings";
        return null;
    }

    static boolean audioCompatible(Track a, Track b) {
        return a.fourcc.equals(b.fourcc) && a.channels == b.channels && a.sampleRate == b.sampleRate;
    }

    /** Child boxes in [start,end): each item is {type, payloadStart, end, boxStart}. */
    static java.util.List<int[]> children(byte[] b, int start, int end) {
        java.util.ArrayList<int[]> out = new java.util.ArrayList<>();
        int p = start;
        while (p + 8 <= end) {
            long size = u32(b, p) & 0xffffffffL;
            int type = u32(b, p + 4);
            int hdr = 8;
            if (size == 1) {
                if (p + 16 > end) break;
                size = s64(b, p + 8);
                hdr = 16;
            } else if (size == 0) {
                size = end - p;
            }
            if (size < hdr || p + size > end) break;
            out.add(new int[]{type, p + hdr, (int) (p + size), p});
            p += (int) size;
        }
        return out;
    }

    static int fourcc(String s) {
        return (s.charAt(0) << 24) | (s.charAt(1) << 16) | (s.charAt(2) << 8) | s.charAt(3);
    }

    static int u16(byte[] b, int p) {
        return ((b[p] & 0xff) << 8) | (b[p + 1] & 0xff);
    }

    static int u32(byte[] b, int p) {
        return ((b[p] & 0xff) << 24) | ((b[p + 1] & 0xff) << 16) | ((b[p + 2] & 0xff) << 8) | (b[p + 3] & 0xff);
    }

    static long s64(byte[] b, int p) {
        return ((long) u32(b, p) << 32) | (u32(b, p + 4) & 0xffffffffL);
    }
}

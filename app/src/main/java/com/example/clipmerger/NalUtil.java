package com.example.clipmerger;

import android.media.MediaFormat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** H.264 / H.265 bitstream helpers: Annex-B <-> length-prefixed, avcC / hvcC, sample entries. */
final class NalUtil {

    private static final byte[] START_CODE = {0, 0, 0, 1};

    private NalUtil() {
    }

    static int nalType(byte[] nal, boolean hevc) {
        return hevc ? (nal[0] >> 1) & 0x3f : nal[0] & 0x1f;
    }

    static boolean isParameterSet(byte[] nal, boolean hevc) {
        int t = nalType(nal, hevc);
        return hevc ? (t == 32 || t == 33 || t == 34) : (t == 7 || t == 8);
    }

    /** Splits an Annex-B byte stream into NAL units (without start codes). */
    static List<byte[]> splitAnnexB(byte[] d, int off, int len) {
        ArrayList<byte[]> out = new ArrayList<>();
        int end = off + len;
        int i = findStart(d, off, end);
        while (i >= 0) {
            int nalStart = i + 3;
            int next = findStart(d, nalStart, end);
            int nalEnd = next >= 0 ? next : end;
            while (nalEnd > nalStart && d[nalEnd - 1] == 0) nalEnd--;
            if (nalEnd > nalStart) {
                byte[] nal = new byte[nalEnd - nalStart];
                System.arraycopy(d, nalStart, nal, 0, nal.length);
                out.add(nal);
            }
            i = next;
        }
        return out;
    }

    private static int findStart(byte[] d, int from, int end) {
        for (int i = from; i + 2 < end; i++) {
            if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) return i;
        }
        return -1;
    }

    /** Converts encoder output (Annex-B) to MP4 sample data with 4-byte lengths, dropping parameter sets. */
    static ByteBuffer annexBToSample(byte[] d, int off, int len, boolean hevc) {
        List<byte[]> nals = splitAnnexB(d, off, len);
        int total = 0;
        for (byte[] n : nals) if (!isParameterSet(n, hevc)) total += 4 + n.length;
        ByteBuffer b = ByteBuffer.allocate(total);
        for (byte[] n : nals) {
            if (isParameterSet(n, hevc)) continue;
            b.putInt(n.length);
            b.put(n);
        }
        b.flip();
        return b;
    }

    /** Converts an MP4 sample (length-prefixed NALs) to Annex-B into {@code dst}. */
    static void sampleToAnnexB(ByteBuffer src, int lengthSize, ByteBuffer dst) throws IOException {
        while (src.remaining() >= lengthSize) {
            int len = 0;
            for (int i = 0; i < lengthSize; i++) len = (len << 8) | (src.get() & 0xff);
            if (len < 0 || len > src.remaining()) throw new IOException("Corrupt video sample");
            if (dst.remaining() < 4 + len) throw new IOException("Decoder input buffer too small");
            dst.put(START_CODE);
            int lim = src.limit();
            src.limit(src.position() + len);
            dst.put(src);
            src.limit(lim);
        }
    }

    /** Builds a decoder MediaFormat from an hvcC / avcC record. */
    static MediaFormat decoderFormat(Mp4Source.Track t) throws IOException {
        String mime = t.mime();
        if (mime == null || t.codecConfig == null) {
            throw new IOException("Unsupported video codec '" + t.fourcc + "' (only H.264 and H.265 can be re-encoded)");
        }
        MediaFormat f = MediaFormat.createVideoFormat(mime, t.width, t.height);
        byte[] c = t.codecConfig;
        ByteArrayOutputStream csd0 = new ByteArrayOutputStream();
        ByteArrayOutputStream csd1 = new ByteArrayOutputStream();
        try {
            if ("video/hevc".equals(mime)) {
                int arrays = c[22] & 0xff;
                int p = 23;
                for (int a = 0; a < arrays; a++) {
                    p++; // array_completeness / NAL type
                    int num = Mp4Source.u16(c, p);
                    p += 2;
                    for (int k = 0; k < num; k++) {
                        int len = Mp4Source.u16(c, p);
                        p += 2;
                        csd0.write(START_CODE);
                        csd0.write(c, p, len);
                        p += len;
                    }
                }
                f.setByteBuffer("csd-0", ByteBuffer.wrap(csd0.toByteArray()));
            } else {
                int numSps = c[5] & 0x1f;
                int p = 6;
                for (int k = 0; k < numSps; k++) {
                    int len = Mp4Source.u16(c, p);
                    p += 2;
                    csd0.write(START_CODE);
                    csd0.write(c, p, len);
                    p += len;
                }
                int numPps = c[p++] & 0xff;
                for (int k = 0; k < numPps; k++) {
                    int len = Mp4Source.u16(c, p);
                    p += 2;
                    csd1.write(START_CODE);
                    csd1.write(c, p, len);
                    p += len;
                }
                f.setByteBuffer("csd-0", ByteBuffer.wrap(csd0.toByteArray()));
                f.setByteBuffer("csd-1", ByteBuffer.wrap(csd1.toByteArray()));
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IOException("Corrupt codec configuration in " + t.fourcc + " track");
        }
        int maxIn = t.maxSampleSize + t.maxSampleSize / 4 + 64 * 1024;
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxIn);
        return f;
    }

    /** Builds a complete 'stsd' box for encoder output described by {@code f}. */
    static byte[] stsdForEncoder(MediaFormat f, boolean hevc, int width, int height) throws IOException {
        ArrayList<byte[]> vps = new ArrayList<>(), sps = new ArrayList<>(), pps = new ArrayList<>();
        for (String key : new String[]{"csd-0", "csd-1", "csd-2"}) {
            ByteBuffer bb = f.containsKey(key) ? f.getByteBuffer(key) : null;
            if (bb == null) continue;
            byte[] d = new byte[bb.remaining()];
            bb.duplicate().get(d);
            for (byte[] nal : splitAnnexB(d, 0, d.length)) {
                int t = nalType(nal, hevc);
                if (hevc) {
                    if (t == 32) vps.add(nal);
                    else if (t == 33) sps.add(nal);
                    else if (t == 34) pps.add(nal);
                } else {
                    if (t == 7) sps.add(nal);
                    else if (t == 8) pps.add(nal);
                }
            }
        }
        if (sps.isEmpty() || pps.isEmpty() || (hevc && vps.isEmpty())) {
            throw new IOException("Encoder did not provide codec configuration");
        }
        byte[] config = hevc ? Mp4Writer.box("hvcC", hvcC(vps, sps, pps)) : Mp4Writer.box("avcC", avcC(sps, pps));
        Mp4Writer.Buf entry = new Mp4Writer.Buf();
        entry.zeros(6).u16(1).u16(0).u16(0).zeros(12).u16(width).u16(height)
                .u32(0x00480000).u32(0x00480000).u32(0).u16(1).zeros(32).u16(0x0018).u16(0xffff).raw(config);
        Mp4Writer.Buf stsd = new Mp4Writer.Buf();
        stsd.u32(0).u32(1).raw(Mp4Writer.box(hevc ? "hvc1" : "avc1", entry.bytes()));
        return Mp4Writer.box("stsd", stsd.bytes());
    }

    private static byte[] avcC(List<byte[]> sps, List<byte[]> pps) {
        byte[] s0 = sps.get(0);
        Mp4Writer.Buf b = new Mp4Writer.Buf();
        b.u8(1).u8(s0[1] & 0xff).u8(s0[2] & 0xff).u8(s0[3] & 0xff).u8(0xff).u8(0xe0 | sps.size());
        for (byte[] s : sps) b.u16(s.length).raw(s);
        b.u8(pps.size());
        for (byte[] p : pps) b.u16(p.length).raw(p);
        return b.bytes();
    }

    private static byte[] hvcC(List<byte[]> vps, List<byte[]> sps, List<byte[]> pps) throws IOException {
        byte[] rbsp = unescape(sps.get(0), 2);
        if (rbsp.length < 13) throw new IOException("Short HEVC SPS");
        BitReader r = new BitReader(rbsp);
        r.skip(4); // sps_video_parameter_set_id
        int maxSubLayersMinus1 = r.u(3);
        int temporalIdNesting = r.u(1);
        r.skip(96); // general profile_tier_level (12 bytes, copied verbatim below)
        boolean[] profilePresent = new boolean[8], levelPresent = new boolean[8];
        for (int i = 0; i < maxSubLayersMinus1; i++) {
            profilePresent[i] = r.u(1) == 1;
            levelPresent[i] = r.u(1) == 1;
        }
        if (maxSubLayersMinus1 > 0) for (int i = maxSubLayersMinus1; i < 8; i++) r.skip(2);
        for (int i = 0; i < maxSubLayersMinus1; i++) {
            if (profilePresent[i]) r.skip(88);
            if (levelPresent[i]) r.skip(8);
        }
        r.ue(); // sps_seq_parameter_set_id
        int chroma = r.ue();
        if (chroma == 3) r.skip(1);
        r.ue();
        r.ue();
        if (r.u(1) == 1) {
            r.ue();
            r.ue();
            r.ue();
            r.ue();
        }
        int bitDepthLuma = r.ue();
        int bitDepthChroma = r.ue();

        Mp4Writer.Buf b = new Mp4Writer.Buf();
        b.u8(1);
        for (int i = 1; i <= 12; i++) b.u8(rbsp[i] & 0xff); // profile/tier/idc, compat, constraints, level
        b.u16(0xf000).u8(0xfc).u8(0xfc | (chroma & 3)).u8(0xf8 | (bitDepthLuma & 7)).u8(0xf8 | (bitDepthChroma & 7))
                .u16(0).u8(((maxSubLayersMinus1 + 1) << 3) | (temporalIdNesting << 2) | 3).u8(3);
        writeArray(b, 32, vps);
        writeArray(b, 33, sps);
        writeArray(b, 34, pps);
        return b.bytes();
    }

    private static void writeArray(Mp4Writer.Buf b, int type, List<byte[]> nals) {
        b.u8(0x80 | type).u16(nals.size());
        for (byte[] n : nals) b.u16(n.length).raw(n);
    }

    /** Removes emulation-prevention bytes, starting after {@code skip} header bytes. */
    static byte[] unescape(byte[] nal, int skip) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(nal.length);
        int zeros = 0;
        for (int i = skip; i < nal.length; i++) {
            int v = nal[i] & 0xff;
            if (zeros >= 2 && v == 3) {
                zeros = 0;
                continue;
            }
            o.write(v);
            zeros = v == 0 ? zeros + 1 : 0;
        }
        return o.toByteArray();
    }

    static final class BitReader {
        private final byte[] d;
        private int bit;

        BitReader(byte[] d) {
            this.d = d;
        }

        int u(int n) throws IOException {
            int v = 0;
            for (int i = 0; i < n; i++) {
                int byteIdx = bit >> 3;
                if (byteIdx >= d.length) throw new IOException("Truncated SPS");
                v = (v << 1) | ((d[byteIdx] >> (7 - (bit & 7))) & 1);
                bit++;
            }
            return v;
        }

        void skip(int n) {
            bit += n;
        }

        int ue() throws IOException {
            int zeros = 0;
            while (u(1) == 0) {
                if (++zeros > 31) throw new IOException("Bad SPS");
            }
            return zeros == 0 ? 0 : (1 << zeros) - 1 + u(zeros);
        }
    }
}

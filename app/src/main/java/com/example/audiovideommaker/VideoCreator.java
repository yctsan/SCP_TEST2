package com.example.audiovideommaker;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.view.Surface;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;

public final class VideoCreator {

    private VideoCreator() {}

    private static final String VIDEO_MIME   = "video/avc";
    private static final int    VIDEO_WIDTH  = 1280;
    private static final int    VIDEO_HEIGHT = 720;
    private static final int    VIDEO_FPS    = 30;
    private static final int    VIDEO_BIT    = 4000000;
    private static final String AUDIO_MIME   = "audio/mp4a-latm";
    private static final int    AUDIO_BIT    = 128000;
    private static final long   TIMEOUT_US   = 10000L;

    private static final double TARGET_PEAK_DBFS = -1.0;
    private static final double TARGET_RMS_DBFS  = -18.0;
    private static final double MAX_GAIN_DB      = 40.0;

    public enum NormalizeMode { NONE, PEAK, RMS }

    private static final class PcmResult {
        final byte[] bytes;
        final int sampleRate;
        final int channels;
        PcmResult(byte[] bytes, int sampleRate, int channels) {
            this.bytes = bytes;
            this.sampleRate = sampleRate;
            this.channels = channels;
        }
    }

    public static Uri create(Context context, Uri audioUri, Uri imageUri,
                             float leftVol, float rightVol, NormalizeMode normalizeMode)
            throws IOException {
        File outFile = new File(context.getCacheDir(),
                "avm_output_" + System.currentTimeMillis() + ".mp4");

        // Determine output dimensions from the image's pixel orientation.
        // Portrait images get a 720x1280 frame; everything else gets 1280x720.
        int outW, outH;
        if (imageUri != null) {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            InputStream ps = context.getContentResolver().openInputStream(imageUri);
            if (ps != null) { BitmapFactory.decodeStream(ps, null, probe); ps.close(); }
            if (probe.outHeight > probe.outWidth && probe.outWidth > 0) {
                outW = 720; outH = 1280;
            } else {
                outW = VIDEO_WIDTH; outH = VIDEO_HEIGHT;
            }
        } else {
            outW = VIDEO_WIDTH; outH = VIDEO_HEIGHT;
        }

        Bitmap srcBitmap;
        if (imageUri != null) {
            srcBitmap = loadAndScaleBitmap(context, imageUri, outW, outH);
        } else {
            srcBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
            new Canvas(srcBitmap).drawColor(Color.BLACK);
        }

        PcmResult pcm = decodeToPcm(context, audioUri);
        long audioDurationUs = (long) pcm.bytes.length * 1000000L /
                ((long) pcm.sampleRate * pcm.channels * 2L);

        switch (normalizeMode) {
            case PEAK: normalizePeak(pcm.bytes); break;
            case RMS:  normalizeRms(pcm.bytes);  break;
            default:   break;
        }

        applyBalance(pcm.bytes, pcm.channels, leftVol, rightVol);
        mux(outFile, srcBitmap, pcm.bytes, pcm.sampleRate, pcm.channels, audioDurationUs, outW, outH);
        srcBitmap.recycle();
        return Uri.fromFile(outFile);
    }

    private static void normalizePeak(byte[] pcm) {
        int peak = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int a = Math.abs(readSampleLE(pcm, i));
            if (a > peak) peak = a;
        }
        if (peak == 0) return;
        double targetLinear  = 32767.0 * Math.pow(10.0, TARGET_PEAK_DBFS / 20.0);
        double rawGainDb     = 20.0 * Math.log10(targetLinear / peak);
        double clampedGainDb = Math.min(rawGainDb, MAX_GAIN_DB);
        float  gain          = (float) Math.pow(10.0, clampedGainDb / 20.0);
        applyGainAllSamples(pcm, gain);
    }

    private static void normalizeRms(byte[] pcm) {
        double sumSq = 0.0;
        int count = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            double s = readSampleLE(pcm, i);
            sumSq += s * s;
            count++;
        }
        if (count == 0 || sumSq == 0.0) return;
        double rms           = Math.sqrt(sumSq / count);
        double rmsDb         = 20.0 * Math.log10(rms / 32767.0);
        double neededGainDb  = TARGET_RMS_DBFS - rmsDb;
        double clampedGainDb = Math.min(neededGainDb, MAX_GAIN_DB);
        float  gain          = (float) Math.pow(10.0, clampedGainDb / 20.0);
        applyGainAllSamples(pcm, gain);
    }

    private static void applyGainAllSamples(byte[] pcm, float gain) {
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int scaled = Math.max(-32768, Math.min(32767, (int) (readSampleLE(pcm, i) * gain)));
            writeSampleLE(pcm, i, scaled);
        }
    }

    private static Bitmap loadAndScaleBitmap(Context context, Uri uri, int reqW, int reqH)
            throws IOException {
        // Pass 1: bounds only
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        InputStream s1 = context.getContentResolver().openInputStream(uri);
        if (s1 != null) { BitmapFactory.decodeStream(s1, null, opts); s1.close(); }

        // Compute the minimum decoded size actually needed.
        // When letterboxing, only the dimension that *fills* the frame must reach reqW/reqH;
        // the other dimension is smaller. Using reqW×reqH as the floor would keep much more
        // data than the encoder ever uses (e.g. a square photo only needs ~720 px per side
        // for a 1280×720 output, not 2500 px per side).
        int minW = reqW, minH = reqH;
        if (opts.outWidth > 0 && opts.outHeight > 0) {
            float rs = (float) opts.outWidth / opts.outHeight;   // source ratio
            float rd = (float) reqW / reqH;                      // dest ratio
            if (rs > rd) {
                // wider than output → width fills frame, height letterboxed
                minH = Math.max(1, (int) (reqW / rs));
            } else {
                // taller than output → height fills frame, width pillarboxed
                minW = Math.max(1, (int) (reqH * rs));
            }
        }

        // Largest power-of-2 inSampleSize where decoded image stays >= minW × minH
        int inSampleSize = 1;
        while ((opts.outWidth  / (inSampleSize * 2)) >= minW
            && (opts.outHeight / (inSampleSize * 2)) >= minH) {
            inSampleSize *= 2;
        }

        // Hard cap: never decode more than 4 MP (16 MB @ ARGB_8888). Our output is
        // at most 1280×720 = 921 600 px, so 4 MP is always more than enough to render
        // without visible quality loss, and keeps us well inside device heap limits.
        final int MAX_DECODE_PIXELS = 4 * 1024 * 1024;
        if (opts.outWidth > 0 && opts.outHeight > 0) {
            while ((long) (opts.outWidth / inSampleSize) * (opts.outHeight / inSampleSize)
                    > MAX_DECODE_PIXELS) {
                inSampleSize *= 2;
            }
        }

        // Pass 2: decode with retry on OOM.
        // inPreferredConfig = ARGB_8888 forces SDR 8-bit output — without this,
        // HDR images (Ultra HDR JPEG / HEIC 10-bit) decode to HARDWARE or RGBA_F16
        // config on Android 10+, and canvas.drawBitmap() on a hardware bitmap
        // throws IllegalStateException: "Software rendering doesn't support hardware bitmaps".
        BitmapFactory.Options loadOpts = new BitmapFactory.Options();
        loadOpts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap raw = null;
        while (raw == null && inSampleSize <= 1024) {
            loadOpts.inSampleSize = inSampleSize;
            try {
                InputStream s2 = context.getContentResolver().openInputStream(uri);
                if (s2 != null) {
                    try { raw = BitmapFactory.decodeStream(s2, null, loadOpts); }
                    finally { s2.close(); }
                }
                if (raw == null) break; // undecodable format — don't retry
            } catch (OutOfMemoryError oom) {
                inSampleSize *= 2;
            }
        }

        // Safety net: if inPreferredConfig hint was ignored (e.g. HARDWARE or RGBA_F16
        // bitmap returned for HDR images), copy to ARGB_8888 so Canvas ops don't throw.
        if (raw != null && raw.getConfig() != Bitmap.Config.ARGB_8888) {
            Bitmap soft = raw.copy(Bitmap.Config.ARGB_8888, false);
            if (soft != null) { raw.recycle(); raw = soft; }
        }
        if (raw == null) {
            raw = Bitmap.createBitmap(reqW, reqH, Bitmap.Config.ARGB_8888);
        }

        // Scale into exactly reqW×reqH, letterboxing as needed
        Bitmap scaled = Bitmap.createBitmap(reqW, reqH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(scaled);
        canvas.drawColor(Color.BLACK);
        float ratioSrc = (float) raw.getWidth() / raw.getHeight();
        float ratioDst = (float) reqW / reqH;
        int dw, dh;
        if (ratioSrc > ratioDst) { dw = reqW; dh = (int) (reqW / ratioSrc); }
        else                     { dw = (int) (reqH * ratioSrc); dh = reqH; }
        float left = (reqW - dw) / 2f;
        float top  = (reqH - dh) / 2f;
        canvas.drawBitmap(raw, null, new RectF(left, top, left + dw, top + dh), null);
        raw.recycle();
        return scaled;
    }

    private static PcmResult decodeToPcm(Context context, Uri uri) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(context, uri, null);

        int audioTrack = -1;
        MediaFormat format = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat fmt = extractor.getTrackFormat(i);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                audioTrack = i;
                format = fmt;
                break;
            }
        }
        if (format == null) throw new IOException("No audio track found in file");
        extractor.selectTrack(audioTrack);

        String mime         = format.getString(MediaFormat.KEY_MIME);
        int    sampleRate   = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int    channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

        MediaCodec decoder = MediaCodec.createDecoderByType(mime);
        decoder.configure(format, null, null, 0);
        decoder.start();

        ByteArrayOutputStream pcmOut = new ByteArrayOutputStream();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean eos = false;

        while (!eos) {
            int inIdx = decoder.dequeueInputBuffer(TIMEOUT_US);
            if (inIdx >= 0) {
                ByteBuffer buf  = decoder.getInputBuffer(inIdx);
                int size = extractor.readSampleData(buf, 0);
                if (size < 0) {
                    decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                } else {
                    decoder.queueInputBuffer(inIdx, 0, size, extractor.getSampleTime(), 0);
                    extractor.advance();
                }
            }
            int outIdx = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
            if (outIdx >= 0) {
                ByteBuffer buf  = decoder.getOutputBuffer(outIdx);
                byte[] chunk = new byte[info.size];
                buf.get(chunk);
                pcmOut.write(chunk);
                decoder.releaseOutputBuffer(outIdx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eos = true;
            }
        }

        decoder.stop();
        decoder.release();
        extractor.release();
        return new PcmResult(pcmOut.toByteArray(), sampleRate, channelCount);
    }

    private static void applyBalance(byte[] pcm, int channels, float leftVol, float rightVol) {
        if (leftVol == 1f && rightVol == 1f) return;
        if (channels < 2) {
            scaleChannel(pcm, 0, 1, (leftVol + rightVol) / 2f);
            return;
        }
        scaleChannel(pcm, 0, channels, leftVol);
        scaleChannel(pcm, 1, channels, rightVol);
    }

    private static void scaleChannel(byte[] pcm, int chIdx, int channels, float vol) {
        int frameSize = channels * 2;
        for (int i = chIdx * 2; i + 1 < pcm.length; i += frameSize) {
            int scaled = Math.max(-32768, Math.min(32767, (int) (readSampleLE(pcm, i) * vol)));
            writeSampleLE(pcm, i, scaled);
        }
    }

    private static int readSampleLE(byte[] buf, int off) {
        int lo = buf[off]     & 0xFF;
        int hi = buf[off + 1] & 0xFF;
        int u  = (hi << 8) | lo;
        return u >= 0x8000 ? u - 0x10000 : u;
    }

    private static void writeSampleLE(byte[] buf, int off, int value) {
        buf[off]     = (byte) (value        & 0xFF);
        buf[off + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private static void mux(File outFile, Bitmap bitmap, byte[] pcmData,
                            int sampleRate, int channelCount, long durationUs,
                            int videoWidth, int videoHeight)
            throws IOException {

        // Phase 1: encode all video frames into memory buffers.
        // We must collect the track format (available only after INFO_OUTPUT_FORMAT_CHANGED)
        // before we can call muxer.addTrack(), which must happen before muxer.start().
        MediaFormat vf = MediaFormat.createVideoFormat(VIDEO_MIME, videoWidth, videoHeight);
        vf.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        vf.setInteger(MediaFormat.KEY_BIT_RATE,         VIDEO_BIT);
        vf.setInteger(MediaFormat.KEY_FRAME_RATE,       VIDEO_FPS);
        vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        MediaCodec videoEnc = MediaCodec.createEncoderByType(VIDEO_MIME);
        videoEnc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface surface = videoEnc.createInputSurface();
        videoEnc.start();

        long totalFrames = Math.max(1L, (long) ((durationUs / 1000000.0) * VIDEO_FPS));
        long frameUs     = 1000000L / VIDEO_FPS;
        long frameIdx    = 0;
        boolean videoDone = false;

        MediaCodec.BufferInfo videoInfo = new MediaCodec.BufferInfo();
        MediaFormat videoTrackFormat = null;
        ArrayList<byte[]>   videoChunks = new ArrayList<byte[]>();
        ArrayList<Long>     videoPtsUs  = new ArrayList<Long>();
        ArrayList<Integer>  videoFlags  = new ArrayList<Integer>();
        int videoFrameOut = 0;  // count of actual output frames, used for PTS

        while (!videoDone) {
            if (frameIdx <= totalFrames) {
                Canvas c = surface.lockCanvas(null);
                if (c != null) {
                    c.drawBitmap(bitmap, 0f, 0f, null);
                    surface.unlockCanvasAndPost(c);
                }
                frameIdx++;
                if (frameIdx > totalFrames) videoEnc.signalEndOfInputStream();
            }
            int outIdx = videoEnc.dequeueOutputBuffer(videoInfo, TIMEOUT_US);
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                videoTrackFormat = videoEnc.getOutputFormat();
            } else if (outIdx >= 0) {
                ByteBuffer buf = videoEnc.getOutputBuffer(outIdx);
                if ((videoInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        && videoTrackFormat != null && videoInfo.size > 0) {
                    byte[] chunk = new byte[videoInfo.size];
                    buf.get(chunk);
                    videoChunks.add(chunk);
                    videoPtsUs.add((long) videoFrameOut * frameUs);  // PTS from output index
                    videoFlags.add(videoInfo.flags);                  // preserve KEY_FRAME flag
                    videoFrameOut++;
                }
                videoEnc.releaseOutputBuffer(outIdx, false);
                if ((videoInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) videoDone = true;
            }
        }
        videoEnc.stop(); videoEnc.release(); surface.release();

        // Phase 2: encode all audio frames into memory buffers.
        MediaFormat af = MediaFormat.createAudioFormat(AUDIO_MIME, sampleRate, channelCount);
        af.setInteger(MediaFormat.KEY_BIT_RATE,   AUDIO_BIT);
        af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);

        MediaCodec audioEnc = MediaCodec.createEncoderByType(AUDIO_MIME);
        audioEnc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        audioEnc.start();

        MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();
        MediaFormat audioTrackFormat = null;
        ArrayList<byte[]> audioChunks = new ArrayList<byte[]>();
        ArrayList<Long>   audioPtsUs  = new ArrayList<Long>();

        int     pcmOffset  = 0;
        long    audioPts   = 0L;
        boolean audioDone  = false;

        while (!audioDone) {
            int inIdx = audioEnc.dequeueInputBuffer(TIMEOUT_US);
            if (inIdx >= 0) {
                ByteBuffer buf = audioEnc.getInputBuffer(inIdx);
                buf.clear();
                int chunk = Math.min(buf.capacity(), pcmData.length - pcmOffset);
                if (chunk <= 0) {
                    audioEnc.queueInputBuffer(inIdx, 0, 0, audioPts,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                } else {
                    buf.put(pcmData, pcmOffset, chunk);
                    audioEnc.queueInputBuffer(inIdx, 0, chunk, audioPts, 0);
                    audioPts  += (long) (chunk / (channelCount * 2)) * 1000000L / sampleRate;
                    pcmOffset += chunk;
                }
            }
            int outIdx = audioEnc.dequeueOutputBuffer(audioInfo, TIMEOUT_US);
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                audioTrackFormat = audioEnc.getOutputFormat();
            } else if (outIdx >= 0) {
                ByteBuffer buf = audioEnc.getOutputBuffer(outIdx);
                if ((audioInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        && audioTrackFormat != null) {
                    byte[] chunk = new byte[audioInfo.size];
                    buf.get(chunk);
                    audioChunks.add(chunk);
                    audioPtsUs.add(audioInfo.presentationTimeUs);
                }
                audioEnc.releaseOutputBuffer(outIdx, false);
                if ((audioInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) audioDone = true;
            }
        }
        audioEnc.stop(); audioEnc.release();

        if (videoTrackFormat == null) throw new IOException("Video encoder produced no track format");
        if (audioTrackFormat == null) throw new IOException("Audio encoder produced no track format");

        // Phase 3: add both tracks, start muxer, write all buffered samples.
        MediaMuxer muxer = new MediaMuxer(outFile.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int videoTrackId = muxer.addTrack(videoTrackFormat);
        int audioTrackId = muxer.addTrack(audioTrackFormat);
        muxer.start();

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        for (int i = 0; i < videoChunks.size(); i++) {
            info.set(0, videoChunks.get(i).length, videoPtsUs.get(i), videoFlags.get(i));
            muxer.writeSampleData(videoTrackId, ByteBuffer.wrap(videoChunks.get(i)), info);
        }
        for (int i = 0; i < audioChunks.size(); i++) {
            info.set(0, audioChunks.get(i).length, audioPtsUs.get(i), 0);
            muxer.writeSampleData(audioTrackId, ByteBuffer.wrap(audioChunks.get(i)), info);
        }

        muxer.stop(); muxer.release();
    }
}

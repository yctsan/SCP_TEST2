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

        Bitmap srcBitmap;
        if (imageUri != null) {
            srcBitmap = loadAndScaleBitmap(context, imageUri);
        } else {
            srcBitmap = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888);
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
        mux(outFile, srcBitmap, pcm.bytes, pcm.sampleRate, pcm.channels, audioDurationUs);
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

    private static Bitmap loadAndScaleBitmap(Context context, Uri uri) throws IOException {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        InputStream s1 = context.getContentResolver().openInputStream(uri);
        if (s1 != null) {
            BitmapFactory.decodeStream(s1, null, opts);
            s1.close();
        }
        int scaleW = opts.outWidth  / VIDEO_WIDTH;
        int scaleH = opts.outHeight / VIDEO_HEIGHT;
        int scale  = Math.max(1, Math.min(scaleW, scaleH));

        BitmapFactory.Options loadOpts = new BitmapFactory.Options();
        loadOpts.inSampleSize = scale;
        Bitmap raw = null;
        InputStream s2 = context.getContentResolver().openInputStream(uri);
        if (s2 != null) {
            raw = BitmapFactory.decodeStream(s2, null, loadOpts);
            s2.close();
        }
        if (raw == null) {
            raw = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888);
        }

        Bitmap scaled = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(scaled);
        canvas.drawColor(Color.BLACK);
        float ratioSrc = (float) raw.getWidth()  / raw.getHeight();
        float ratioDst = (float) VIDEO_WIDTH / VIDEO_HEIGHT;
        int dw, dh;
        if (ratioSrc > ratioDst) {
            dw = VIDEO_WIDTH;
            dh = (int) (VIDEO_WIDTH / ratioSrc);
        } else {
            dw = (int) (VIDEO_HEIGHT * ratioSrc);
            dh = VIDEO_HEIGHT;
        }
        float left = (VIDEO_WIDTH  - dw) / 2f;
        float top  = (VIDEO_HEIGHT - dh) / 2f;
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
                            int sampleRate, int channelCount, long durationUs)
            throws IOException {
        MediaMuxer muxer = new MediaMuxer(outFile.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        MediaFormat videoFormat = MediaFormat.createVideoFormat(VIDEO_MIME, VIDEO_WIDTH, VIDEO_HEIGHT);
        videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        videoFormat.setInteger(MediaFormat.KEY_BIT_RATE,         VIDEO_BIT);
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE,       VIDEO_FPS);
        videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        MediaCodec videoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME);
        videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface surface = videoEncoder.createInputSurface();
        videoEncoder.start();

        MediaCodec.BufferInfo videoInfo = new MediaCodec.BufferInfo();
        int     videoTrackId = -1;
        long    totalFrames  = Math.max(1L, (long) ((durationUs / 1000000.0) * VIDEO_FPS));
        long    frameUs      = 1000000L / VIDEO_FPS;
        long    frameIdx     = 0;
        boolean videoDone    = false;

        while (!videoDone) {
            if (frameIdx <= totalFrames) {
                Canvas c = surface.lockHardwareCanvas();
                c.drawBitmap(bitmap, 0f, 0f, null);
                surface.unlockCanvasAndPost(c);
                frameIdx++;
                if (frameIdx > totalFrames) videoEncoder.signalEndOfInputStream();
            }
            int outIdx = videoEncoder.dequeueOutputBuffer(videoInfo, TIMEOUT_US);
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                videoTrackId = muxer.addTrack(videoEncoder.getOutputFormat());
            } else if (outIdx >= 0) {
                ByteBuffer buf = videoEncoder.getOutputBuffer(outIdx);
                if ((videoInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    videoInfo.presentationTimeUs = (frameIdx - 1) * frameUs;
                    if (videoTrackId >= 0) muxer.writeSampleData(videoTrackId, buf, videoInfo);
                }
                videoEncoder.releaseOutputBuffer(outIdx, false);
                if ((videoInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) videoDone = true;
            }
        }

        MediaFormat audioFormat = MediaFormat.createAudioFormat(AUDIO_MIME, sampleRate, channelCount);
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT);
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC);

        MediaCodec audioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME);
        audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        audioEncoder.start();

        MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();
        int     audioTrackId = muxer.addTrack(audioEncoder.getOutputFormat());
        muxer.start();

        int     pcmOffset  = 0;
        boolean audioDone  = false;
        long    audioPtsUs = 0L;

        while (!audioDone) {
            int inIdx = audioEncoder.dequeueInputBuffer(TIMEOUT_US);
            if (inIdx >= 0) {
                ByteBuffer buf   = audioEncoder.getInputBuffer(inIdx);
                buf.clear();
                int chunk = Math.min(buf.capacity(), pcmData.length - pcmOffset);
                if (chunk <= 0) {
                    audioEncoder.queueInputBuffer(inIdx, 0, 0, audioPtsUs,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                } else {
                    buf.put(pcmData, pcmOffset, chunk);
                    audioEncoder.queueInputBuffer(inIdx, 0, chunk, audioPtsUs, 0);
                    audioPtsUs += (long) (chunk / (channelCount * 2)) * 1000000L / sampleRate;
                    pcmOffset  += chunk;
                }
            }
            int outIdx = audioEncoder.dequeueOutputBuffer(audioInfo, TIMEOUT_US);
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                audioTrackId = muxer.addTrack(audioEncoder.getOutputFormat());
            } else if (outIdx >= 0) {
                ByteBuffer buf = audioEncoder.getOutputBuffer(outIdx);
                if ((audioInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0)
                    muxer.writeSampleData(audioTrackId, buf, audioInfo);
                audioEncoder.releaseOutputBuffer(outIdx, false);
                if ((audioInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) audioDone = true;
            }
        }

        audioEncoder.stop(); audioEncoder.release();
        videoEncoder.stop(); videoEncoder.release();
        surface.release();
        muxer.stop(); muxer.release();
    }
}

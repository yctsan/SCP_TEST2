package com.example.clipmerger;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * Trims and joins clips by decoding the video and re-encoding it with the
 * codec, size and bitrate the user picked. Cuts are frame-accurate. Audio is
 * copied without re-encoding.
 */
final class Transcoder {

    private static final int VIDEO_TIMESCALE = 90000;

    /** Returns a warning for the user, or null. */
    static String run(Context ctx, List<Clip> clips, Export.Options opt, FileChannel out,
                      Export.Progress progress) throws Exception {
        ArrayList<Mp4Source> sources = new ArrayList<>();
        HandlerThread frames = new HandlerThread("frame-callbacks");
        frames.start();
        try {
            for (Clip c : clips) sources.add(Mp4Source.open(ctx, c.uri));
            return new Transcoder(clips, sources, opt, out, progress, new Handler(frames.getLooper())).transcode();
        } finally {
            for (Mp4Source s : sources) s.close();
            frames.quitSafely();
        }
    }

    private final List<Clip> clips;
    private final List<Mp4Source> sources;
    private final Export.Options opt;
    private final FileChannel out;
    private final Export.Progress progress;
    private final Handler frameHandler;
    private final boolean hevcOut;
    private final MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();

    private Mp4Writer writer;
    private MediaCodec encoder;
    private GlFrameCopier gl;
    private AudioCopier audio;
    private int videoTrack;
    private int outW, outH;
    private long lastOutUs = -1;
    private long totalUs;
    private byte[] encBytes = new byte[1 << 20];

    private Transcoder(List<Clip> clips, List<Mp4Source> sources, Export.Options opt, FileChannel out,
                       Export.Progress progress, Handler frameHandler) {
        this.clips = clips;
        this.sources = sources;
        this.opt = opt;
        this.out = out;
        this.progress = progress;
        this.frameHandler = frameHandler;
        this.hevcOut = "video/hevc".equals(opt.mime);
    }

    private String transcode() throws Exception {
        for (int i = 0; i < clips.size(); i++) {
            Mp4Source.Track v = sources.get(i).video;
            if (v == null) throw new Export.Failure(clips.get(i).name + " has no video track.");
            if (v.mime() == null || v.codecConfig == null) {
                throw new Export.Failure(clips.get(i).name + " uses an unsupported video codec (" + v.fourcc + ").");
            }
        }
        Mp4Source.Track v0 = sources.get(0).video;
        outW = v0.width;
        outH = v0.height;
        int shortSide = Math.min(outW, outH);
        if (opt.shortSide > 0 && opt.shortSide < shortSide) {
            double s = opt.shortSide / (double) shortSide;
            outW = (int) Math.round(outW * s);
            outH = (int) Math.round(outH * s);
        }
        outW &= ~1;
        outH &= ~1;
        long frameDurUs = v0.frameDurUs();
        int fps = Math.max(1, Math.round(1000000f / frameDurUs));

        MediaCodecInfo info = Encoders.find(opt.mime, outW, outH);
        if (info == null) {
            throw new Export.Failure("This phone has no " + Encoders.label(opt.mime) + " encoder for "
                    + outW + "×" + outH + ". Pick a smaller resolution or another codec.");
        }
        MediaCodecInfo.VideoCapabilities caps = info.getCapabilitiesForType(opt.mime).getVideoCapabilities();
        int bitrate = caps.getBitrateRange().clamp(opt.bitrate);
        MediaFormat f = MediaFormat.createVideoFormat(opt.mime, outW, outH);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        boolean[] include = new boolean[1];
        String warning = AudioCopier.plan(sources, include);
        int maxAudio = 0;
        for (Mp4Source s : sources) if (s.audio != null) maxAudio = Math.max(maxAudio, s.audio.maxSampleSize);
        for (Clip c : clips) totalUs += c.keptUs();

        encoder = MediaCodec.createByCodecName(info.getName());
        try {
            encoder.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            gl = new GlFrameCopier(encoder.createInputSurface(), outW, outH);
            encoder.start();

            writer = new Mp4Writer(out);
            videoTrack = writer.addTrack(true, VIDEO_TIMESCALE);
            writer.setVideoInfo(videoTrack, v0.matrix, outW, outH);
            if (include[0]) {
                Mp4Source.Track a0 = AudioCopier.firstAudio(sources);
                int at = writer.addTrack(false, a0.timescale);
                writer.setSampleEntry(at, a0.stsd);
                audio = new AudioCopier(writer, at, maxAudio);
            }

            long segStartUs = 0;
            for (int i = 0; i < clips.size(); i++) {
                segStartUs += transcodeClip(clips.get(i), sources.get(i), segStartUs);
                progress.update(segStartUs, totalUs);
            }
            encoder.signalEndOfInputStream();
            drainEncoder(true);
            writer.setLastSampleDuration(videoTrack, Export.usToTicks(frameDurUs, VIDEO_TIMESCALE));
            writer.finish();
        } finally {
            try {
                encoder.stop();
            } catch (RuntimeException ignored) {
            }
            encoder.release();
            if (gl != null) gl.release();
        }
        return warning;
    }

    /** Decodes one clip's kept range into the encoder; returns the output duration it occupies. */
    private long transcodeClip(Clip c, Mp4Source src, long segStartUs) throws Exception {
        Mp4Source.Track v = src.video;
        MediaFormat df = NalUtil.decoderFormat(v);
        GlFrameCopier.Input input = gl.createInput(frameHandler);
        MediaCodec decoder = MediaCodec.createDecoderByType(v.mime());
        long maxRelUs = -1;
        try {
            decoder.configure(df, input.surface(), null, 0);
            decoder.start();
            int next = v.syncIndexAtOrBefore(c.trimStartUs);
            int lengthSize = v.nalLengthSize();
            ByteBuffer sample = ByteBuffer.allocate(Math.max(1024, v.maxSampleSize));
            if (audio != null) audio.begin(src, c.trimStartUs, c.trimEndUs, segStartUs);
            MediaCodec.BufferInfo di = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            while (!outputDone) {
                if (!inputDone) {
                    int idx = decoder.dequeueInputBuffer(10000);
                    if (idx >= 0) {
                        if (next >= v.count) {
                            decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            ByteBuffer ib = decoder.getInputBuffer(idx);
                            ib.clear();
                            src.readSample(v, next, sample);
                            NalUtil.sampleToAnnexB(sample, lengthSize, ib);
                            decoder.queueInputBuffer(idx, 0, ib.position(), v.ptsUs(next),
                                    v.sync[next] ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                            next++;
                        }
                    }
                }
                int o = decoder.dequeueOutputBuffer(di, 10000);
                if (o >= 0) {
                    boolean eos = (di.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    long pts = di.presentationTimeUs;
                    long outUs = segStartUs + (pts - c.trimStartUs);
                    boolean render = di.size != 0 && pts >= c.trimStartUs && pts <= c.trimEndUs && outUs > lastOutUs;
                    if (di.size != 0 && pts > c.trimEndUs) outputDone = true;
                    decoder.releaseOutputBuffer(o, render);
                    if (render) {
                        input.awaitFrame();
                        gl.draw(input, v.width, v.height);
                        gl.setPresentationTime(outUs * 1000L);
                        gl.swapBuffers();
                        lastOutUs = outUs;
                        maxRelUs = Math.max(maxRelUs, pts - c.trimStartUs);
                        if (audio != null) audio.pumpUntil(pts);
                        progress.update(outUs, totalUs);
                    }
                    if (eos) outputDone = true;
                }
                drainEncoder(false);
            }
            if (audio != null) audio.finishSegment();
        } finally {
            try {
                decoder.stop();
            } catch (RuntimeException ignored) {
            }
            decoder.release();
            input.release();
        }
        return maxRelUs >= 0 ? maxRelUs + v.frameDurUs() : 0;
    }

    private void drainEncoder(boolean endOfStream) throws IOException {
        int idle = 0;
        while (true) {
            int idx = encoder.dequeueOutputBuffer(encInfo, endOfStream ? 10000 : 0);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) return;
                if (++idle > 1000) throw new IOException("Encoder did not finish");
                continue;
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                writer.setSampleEntry(videoTrack, NalUtil.stsdForEncoder(encoder.getOutputFormat(), hevcOut, outW, outH));
                continue;
            }
            if (idx < 0) continue;
            idle = 0;
            ByteBuffer ob = encoder.getOutputBuffer(idx);
            if (encInfo.size > 0 && ob != null) {
                if (encInfo.size > encBytes.length) encBytes = new byte[encInfo.size * 2];
                ob.position(encInfo.offset);
                ob.get(encBytes, 0, encInfo.size);
                if ((encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    if (!writer.hasSampleEntry(videoTrack)) {
                        MediaFormat cfg = new MediaFormat();
                        byte[] csd = new byte[encInfo.size];
                        System.arraycopy(encBytes, 0, csd, 0, csd.length);
                        cfg.setByteBuffer("csd-0", ByteBuffer.wrap(csd));
                        writer.setSampleEntry(videoTrack, NalUtil.stsdForEncoder(cfg, hevcOut, outW, outH));
                    }
                } else {
                    if (!writer.hasSampleEntry(videoTrack)) throw new IOException("Encoder output has no codec configuration");
                    ByteBuffer s = NalUtil.annexBToSample(encBytes, 0, encInfo.size, hevcOut);
                    if (s.hasRemaining()) {
                        writer.writeSample(videoTrack, s, Export.usToTicks(encInfo.presentationTimeUs, VIDEO_TIMESCALE),
                                (encInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0);
                    }
                }
            }
            encoder.releaseOutputBuffer(idx, false);
            if ((encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
        }
    }
}

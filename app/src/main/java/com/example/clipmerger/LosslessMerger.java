package com.example.clipmerger;

import android.content.Context;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * Trims and joins clips by copying the compressed samples unchanged.
 * Each clip starts at the keyframe at or before its trim start, because a
 * video can only be cut without re-encoding at a keyframe.
 */
final class LosslessMerger {

    private LosslessMerger() {
    }

    /** Returns a warning for the user, or null. */
    static String run(Context ctx, List<Clip> clips, FileChannel out, Export.Progress progress) throws Exception {
        ArrayList<Mp4Source> sources = new ArrayList<>();
        try {
            for (Clip c : clips) sources.add(Mp4Source.open(ctx, c.uri));
            return merge(clips, sources, out, progress);
        } finally {
            for (Mp4Source s : sources) s.close();
        }
    }

    static String merge(List<Clip> clips, List<Mp4Source> sources, FileChannel out,
                                Export.Progress progress) throws Exception {
        Mp4Source.Track v0 = sources.get(0).video;
        if (v0 == null) throw new Export.Failure(clips.get(0).name + " has no video track.");
        int maxVideo = 0, maxAudio = 0;
        for (int i = 0; i < clips.size(); i++) {
            Mp4Source.Track v = sources.get(i).video;
            if (v == null) throw new Export.Failure(clips.get(i).name + " has no video track.");
            String why = Mp4Source.videoMismatch(v0, v);
            if (why != null) {
                throw new Export.Failure(clips.get(i).name + " " + why + " than " + clips.get(0).name
                        + ", so they cannot be joined without re-encoding. Choose \"Re-encode\" instead.");
            }
            maxVideo = Math.max(maxVideo, v.maxSampleSize);
            if (sources.get(i).audio != null) maxAudio = Math.max(maxAudio, sources.get(i).audio.maxSampleSize);
        }
        boolean[] include = new boolean[1];
        String warning = AudioCopier.plan(sources, include);

        Mp4Writer w = new Mp4Writer(out);
        int vt = w.addTrack(true, v0.timescale);
        w.setSampleEntry(vt, v0.stsd);
        w.setVideoInfo(vt, v0.matrix, v0.width, v0.height);
        AudioCopier audio = null;
        if (include[0]) {
            Mp4Source.Track a0 = AudioCopier.firstAudio(sources);
            int at = w.addTrack(false, a0.timescale);
            w.setSampleEntry(at, a0.stsd);
            audio = new AudioCopier(w, at, maxAudio);
        }

        long totalUs = 0;
        for (Clip c : clips) totalUs += c.keptUs();
        ByteBuffer buf = ByteBuffer.allocate(Math.max(maxVideo, 1024));
        long segStartUs = 0;
        long lastFrameDurUs = v0.frameDurUs();
        for (int i = 0; i < clips.size(); i++) {
            Clip c = clips.get(i);
            Mp4Source src = sources.get(i);
            Mp4Source.Track v = src.video;
            int k = v.syncIndexAtOrBefore(c.trimStartUs);
            long keyUs = v.ptsUs(k);
            if (audio != null) audio.begin(src, keyUs, c.trimEndUs, segStartUs);
            long maxRelUs = -1;
            for (int j = k; j < v.count; j++) {
                long ptsUs = v.ptsUs(j);
                if (ptsUs > c.trimEndUs) break;
                if (ptsUs < keyUs) continue; // leading pictures that reference the previous GOP
                if (audio != null) audio.pumpUntil(ptsUs);
                src.readSample(v, j, buf);
                long rel = ptsUs - keyUs;
                w.writeSample(vt, buf, Export.usToTicks(segStartUs + rel, v0.timescale), v.sync[j]);
                maxRelUs = Math.max(maxRelUs, rel);
                if ((j & 15) == 0) progress.update(segStartUs + rel, totalUs);
            }
            if (audio != null) audio.finishSegment();
            if (maxRelUs >= 0) {
                lastFrameDurUs = v.frameDurUs();
                segStartUs += maxRelUs + lastFrameDurUs;
            }
            progress.update(segStartUs, totalUs);
        }
        w.setLastSampleDuration(vt, Export.usToTicks(lastFrameDurUs, v0.timescale));
        w.finish();
        return warning;
    }
}

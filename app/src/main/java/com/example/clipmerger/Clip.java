package com.example.clipmerger;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.IOException;
import java.util.Locale;

/** A source video plus the part of it the user wants to keep. */
final class Clip {
    final Uri uri;
    final String name;
    final long durationUs;
    final int width, height, rotation;
    final String codec;
    final float frameRate;
    final boolean hasAudio;
    final long videoBitrate;

    long trimStartUs;
    long trimEndUs;

    Clip(Uri uri, String name, Mp4Source.Track v, boolean hasAudio) {
        this.uri = uri;
        this.name = name;
        this.durationUs = v.durationUs();
        this.width = v.width;
        this.height = v.height;
        this.rotation = v.rotation();
        this.codec = codecLabel(v);
        this.frameRate = 1000000f / v.frameDurUs();
        this.hasAudio = hasAudio;
        long bytes = 0;
        for (int i = 0; i < v.count; i++) bytes += v.size[i];
        this.videoBitrate = durationUs > 0 ? bytes * 8L * 1000000L / durationUs : 0;
        this.trimStartUs = 0;
        this.trimEndUs = durationUs;
    }

    static Clip probe(Context ctx, Uri uri) throws IOException {
        String name = displayName(ctx, uri);
        Mp4Source s = Mp4Source.open(ctx, uri);
        try {
            if (s.video == null) throw new IOException("no video track");
            return new Clip(uri, name, s.video, s.audio != null);
        } finally {
            s.close();
        }
    }

    long keptUs() {
        return trimEndUs - trimStartUs;
    }

    boolean isTrimmed() {
        return trimStartUs > 0 || trimEndUs < durationUs;
    }

    static String displayName(Context ctx, Uri uri) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (RuntimeException ignored) {
        } finally {
            if (c != null) c.close();
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : uri.toString();
    }

    private static String codecLabel(Mp4Source.Track v) {
        String mime = v.mime();
        if ("video/hevc".equals(mime)) return "H.265";
        if ("video/avc".equals(mime)) return "H.264";
        return v.fourcc;
    }

    static String formatUs(long us) {
        if (us < 0) us = 0;
        long ms = us / 1000;
        long h = ms / 3600000, m = (ms / 60000) % 60, s = (ms / 1000) % 60, f = ms % 1000;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d.%03d", h, m, s, f);
        return String.format(Locale.US, "%d:%02d.%03d", m, s, f);
    }
}

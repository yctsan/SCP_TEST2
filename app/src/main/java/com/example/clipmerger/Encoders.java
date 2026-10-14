package com.example.clipmerger;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/** Looks up the device's video encoders. */
final class Encoders {

    static final String[] OUTPUT_MIMES = {"video/hevc", "video/avc"};

    private Encoders() {
    }

    static String label(String mime) {
        if ("video/hevc".equals(mime)) return "H.265 / HEVC";
        if ("video/avc".equals(mime)) return "H.264 / AVC";
        return mime;
    }

    /** Output codecs this device can encode, in {@link #OUTPUT_MIMES} order. */
    static List<String> availableMimes() {
        ArrayList<String> out = new ArrayList<>();
        for (String mime : OUTPUT_MIMES) if (!candidates(mime).isEmpty()) out.add(mime);
        return out;
    }

    /** Best encoder for {@code mime} that supports width×height (hardware first), or null. */
    static MediaCodecInfo find(String mime, int width, int height) {
        MediaCodecInfo software = null;
        for (MediaCodecInfo info : candidates(mime)) {
            MediaCodecInfo.VideoCapabilities caps = info.getCapabilitiesForType(mime).getVideoCapabilities();
            if (caps == null || !caps.isSizeSupported(width, height)) continue;
            if (isHardware(info)) return info;
            if (software == null) software = info;
        }
        return software;
    }

    private static List<MediaCodecInfo> candidates(String mime) {
        ArrayList<MediaCodecInfo> out = new ArrayList<>();
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(mime)) {
                    out.add(info);
                    break;
                }
            }
        }
        return out;
    }

    private static boolean isHardware(MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated();
        String n = info.getName().toLowerCase(java.util.Locale.US);
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw."));
    }
}

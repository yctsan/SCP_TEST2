package com.example.audiovideommaker;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class FileUtils {

    private FileUtils() {}

    public static String displayName(Context context, Uri uri) {
        if ("content".equals(uri.getScheme())) {
            Cursor cursor = context.getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null
            );
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (idx >= 0) return cursor.getString(idx);
                    }
                } finally {
                    cursor.close();
                }
            }
        }
        String lastSeg = uri.getLastPathSegment();
        return lastSeg != null ? lastSeg : uri.toString();
    }

    public static void copyUri(Context context, Uri src, Uri dest) throws IOException {
        InputStream input = context.getContentResolver().openInputStream(src);
        if (input == null) return;
        try {
            OutputStream output = context.getContentResolver().openOutputStream(dest);
            if (output == null) { input.close(); return; }
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = input.read(buf)) != -1) {
                    output.write(buf, 0, n);
                }
            } finally {
                output.close();
            }
        } finally {
            input.close();
        }
    }

    public static void deleteCacheFile(Context context, Uri uri) {
        if ("file".equals(uri.getScheme())) {
            String path = uri.getPath();
            if (path != null) new File(path).delete();
        }
    }
}

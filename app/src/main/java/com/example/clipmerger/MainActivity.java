package com.example.clipmerger;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements ExportJob.Listener {

    private static final int REQ_PICK = 1;
    private static final int REQ_TRIM = 2;
    private static final int REQ_SAVE = 3;

    private final ArrayList<Clip> clips = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout list;
    private TextView summary, status;
    private Button addButton, exportButton, cancelButton;
    private ProgressBar progressBar;
    private View progressPanel;
    private Export.Options pendingOptions;
    private Uri pendingOutput;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));

        addButton = new Button(this);
        addButton.setText("Add clips");
        addButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickClips();
            }
        });
        root.addView(addButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        summary = new TextView(this);
        summary.setPadding(0, dp(8), 0, dp(8));
        root.addView(summary);

        progressPanel = buildProgressPanel();
        progressPanel.setVisibility(View.GONE);
        root.addView(progressPanel);

        exportButton = new Button(this);
        exportButton.setText("Export…");
        exportButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showExportDialog();
            }
        });
        root.addView(exportButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        refresh();
    }

    private View buildProgressPanel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(this);
        p.addView(status);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        p.addView(progressBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cancelButton = new Button(this);
        cancelButton.setText("Cancel export");
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ExportJob job = ExportJob.current();
                if (job != null) job.cancel.set(true);
                cancelButton.setEnabled(false);
                status.setText("Cancelling…");
            }
        });
        p.addView(cancelButton);
        return p;
    }

    // ---- clip list ----------------------------------------------------------------------------

    private void pickClips() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_PICK);
    }

    private void addUris(final List<Uri> uris) {
        setBusy(true, "Reading clips…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ArrayList<Clip> added = new ArrayList<>();
                final StringBuilder errors = new StringBuilder();
                for (Uri u : uris) {
                    try {
                        added.add(Clip.probe(MainActivity.this, u));
                    } catch (Exception e) {
                        errors.append(Clip.displayName(MainActivity.this, u)).append(": ").append(e.getMessage()).append('\n');
                    }
                }
                Collections.sort(added, new Comparator<Clip>() {
                    @Override
                    public int compare(Clip a, Clip b) {
                        return a.name.compareToIgnoreCase(b.name);
                    }
                });
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        clips.addAll(added);
                        setBusy(false, null);
                        refresh();
                        if (errors.length() > 0) message("Some files could not be added", errors.toString().trim());
                    }
                });
            }
        }, "probe").start();
    }

    private void refresh() {
        list.removeAllViews();
        long total = 0;
        for (int i = 0; i < clips.size(); i++) {
            list.addView(clipRow(i));
            total += clips.get(i).keptUs();
        }
        if (clips.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Tap \"Add clips\" to choose MP4 files. They are listed in name order; "
                    + "use ▲ ▼ to change the order, and Trim to keep only part of a clip.");
            empty.setPadding(0, dp(24), 0, 0);
            list.addView(empty);
        }
        summary.setText(clips.size() + (clips.size() == 1 ? " clip" : " clips") + " · output length " + Clip.formatUs(total));
        exportButton.setEnabled(!busy && !clips.isEmpty());
        addButton.setEnabled(!busy);
    }

    private View clipRow(final int index) {
        Clip c = clips.get(index);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(8), dp(12), dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setStroke(dp(1), 0xFFBDBDBD);
        bg.setCornerRadius(dp(6));
        row.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        row.setLayoutParams(lp);

        TextView title = new TextView(this);
        title.setText((index + 1) + ". " + c.name);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(title);

        TextView details = new TextView(this);
        details.setText(String.format(Locale.US, "%d×%d · %s · %.2f fps · %s%s · %.0f Mbps",
                c.width, c.height, c.codec, c.frameRate, Clip.formatUs(c.durationUs),
                c.hasAudio ? "" : " · no audio", c.videoBitrate / 1e6));
        row.addView(details);

        TextView trim = new TextView(this);
        trim.setText(c.isTrimmed()
                ? "Keep " + Clip.formatUs(c.trimStartUs) + " → " + Clip.formatUs(c.trimEndUs) + " (" + Clip.formatUs(c.keptUs()) + ")"
                : "Whole clip");
        row.addView(trim);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(smallButton("▲", index > 0 && !busy, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Collections.swap(clips, index, index - 1);
                refresh();
            }
        }));
        buttons.addView(smallButton("▼", index < clips.size() - 1 && !busy, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Collections.swap(clips, index, index + 1);
                refresh();
            }
        }));
        buttons.addView(smallButton("Trim", !busy, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openTrim(index);
            }
        }));
        buttons.addView(smallButton("Remove", !busy, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clips.remove(index);
                refresh();
            }
        }));
        row.addView(buttons);
        return row;
    }

    private Button smallButton(String text, boolean enabled, View.OnClickListener l) {
        Button b = new Button(this, null, android.R.attr.buttonStyleSmall);
        b.setText(text);
        b.setEnabled(enabled);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private void openTrim(int index) {
        Clip c = clips.get(index);
        Intent i = new Intent(this, TrimActivity.class);
        i.putExtra(TrimActivity.EXTRA_INDEX, index);
        i.putExtra(TrimActivity.EXTRA_URI, c.uri);
        i.putExtra(TrimActivity.EXTRA_NAME, c.name);
        i.putExtra(TrimActivity.EXTRA_DURATION, c.durationUs);
        i.putExtra(TrimActivity.EXTRA_START, c.trimStartUs);
        i.putExtra(TrimActivity.EXTRA_END, c.trimEndUs);
        startActivityForResult(i, REQ_TRIM);
    }

    // ---- export -------------------------------------------------------------------------------

    private void showExportDialog() {
        if (clips.isEmpty()) return;
        requestNotificationPermission();
        final Clip first = clips.get(0);
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(20), dp(8), dp(20), 0);

        final RadioGroup mode = new RadioGroup(this);
        RadioButton copy = new RadioButton(this);
        copy.setId(View.generateViewId());
        copy.setText("Keep original codec (no re-encoding)");
        RadioButton re = new RadioButton(this);
        re.setId(View.generateViewId());
        re.setText("Re-encode");
        mode.addView(copy);
        mode.addView(note("Fast and lossless. Every clip must have the same codec settings and resolution. "
                + "Each clip starts at the keyframe at or just before its trim start."));
        mode.addView(re);
        mode.addView(note("Slower. Frame-accurate cuts; clips may differ. Audio is copied unchanged."));
        v.addView(mode);

        final LinearLayout settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);
        settings.setPadding(dp(16), 0, 0, 0);

        final List<String> mimes = Encoders.availableMimes();
        ArrayList<String> codecLabels = new ArrayList<>();
        for (String m : mimes) codecLabels.add(Encoders.label(m));
        settings.addView(label("Codec"));
        final Spinner codec = spinner(codecLabels);
        settings.addView(codec);

        final int srcShort = Math.min(first.width, first.height);
        final ArrayList<Integer> sides = new ArrayList<>();
        ArrayList<String> sizeLabels = new ArrayList<>();
        sides.add(0);
        sizeLabels.add("Same as first clip (" + first.width + "×" + first.height + ")");
        for (int s : new int[]{2160, 1440, 1080, 720}) {
            if (s < srcShort) {
                sides.add(s);
                sizeLabels.add(s + "p");
            }
        }
        settings.addView(label("Resolution"));
        final Spinner size = spinner(sizeLabels);
        settings.addView(size);

        settings.addView(label("Video bitrate (Mbps)"));
        final EditText bitrate = new EditText(this);
        bitrate.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        long srcMbps = Math.max(1, Math.round(first.videoBitrate / 1e6));
        bitrate.setText(String.valueOf(srcMbps));
        settings.addView(bitrate);
        final TextView bitrateHint = note("Source is about " + srcMbps + " Mbps. Lower values make smaller files.");
        settings.addView(bitrateHint);
        size.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int side = sides.get(position);
                double scale = side == 0 ? 1.0 : (side / (double) srcShort) * (side / (double) srcShort);
                bitrate.setText(String.valueOf(Math.max(1, Math.round(first.videoBitrate / 1e6 * scale))));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        v.addView(settings);

        if (mimes.isEmpty()) {
            re.setEnabled(false);
            re.setText("Re-encode (no H.264/H.265 encoder found)");
        }
        mode.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                setEnabledDeep(settings, group.getCheckedRadioButtonId() != group.getChildAt(0).getId());
            }
        });
        mode.check(copy.getId());
        setEnabledDeep(settings, false);

        final int reId = re.getId();
        ScrollView sv = new ScrollView(this);
        sv.addView(v);
        new AlertDialog.Builder(this)
                .setTitle("Export")
                .setView(sv)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Choose where to save…", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Export.Options o = new Export.Options();
                        o.reencode = mode.getCheckedRadioButtonId() == reId;
                        if (o.reencode) {
                            o.mime = mimes.get(codec.getSelectedItemPosition());
                            o.shortSide = sides.get(size.getSelectedItemPosition());
                            double mbps;
                            try {
                                mbps = Double.parseDouble(bitrate.getText().toString().trim());
                            } catch (NumberFormatException e) {
                                mbps = 0;
                            }
                            if (mbps <= 0) {
                                toast("Enter a bitrate in Mbps");
                                return;
                            }
                            o.bitrate = (int) Math.min(Integer.MAX_VALUE, mbps * 1e6);
                        }
                        pendingOptions = o;
                        chooseOutput();
                    }
                })
                .show();
    }

    private void chooseOutput() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/mp4");
        i.putExtra(Intent.EXTRA_TITLE, "merged_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4");
        startActivityForResult(i, REQ_SAVE);
    }

    private void startExport(Uri output, Export.Options opt) {
        ExportJob job = ExportJob.create(clips, opt, output);
        job.addListener(this);
        ExportService.start(this);
        render(job);
    }

    @Override
    public void onExportChanged(ExportJob job) {
        render(job);
    }

    /** Shows the state of {@code job}: progress while it runs, the result once it has finished. */
    private void render(ExportJob job) {
        if (job.running) {
            if (!busy) {
                cancelButton.setEnabled(!job.cancel.get());
                setBusy(true, "Exporting…");
            }
            progressBar.setProgress((int) (job.fraction() * 1000));
            status.setText("Exporting… " + job.progressText() + "\n"
                    + Clip.formatUs(job.doneUs) + " of " + Clip.formatUs(job.totalUs)
                    + " · elapsed " + ExportJob.formatDuration(job.elapsedMs()));
            return;
        }
        job.removeListener(this);
        ExportJob.clearFinished();
        getSystemService(NotificationManager.class).cancel(ExportService.ID_DONE);
        setBusy(false, null);
        showResult(job);
    }

    private void showResult(ExportJob job) {
        final Uri output = job.output;
        Throwable error = job.error;
        if (error instanceof Export.Cancelled) {
            toast("Export cancelled");
            return;
        }
        if (error != null) {
            String msg = error instanceof Export.Failure ? error.getMessage() : error.toString();
            message("Export failed", msg);
            return;
        }
        String text = "Saved " + Clip.displayName(this, output) + " in " + ExportJob.formatDuration(job.elapsedMs()) + ".";
        if (job.warning != null) text += "\n\n" + job.warning;
        new AlertDialog.Builder(this)
                .setTitle("Done")
                .setMessage(text)
                .setPositiveButton("OK", null)
                .setNeutralButton("Play", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Intent view = new Intent(Intent.ACTION_VIEW);
                        view.setDataAndType(output, "video/mp4");
                        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        try {
                            startActivity(view);
                        } catch (Exception e) {
                            toast("No video player found");
                        }
                    }
                })
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingOutput != null) {
            // Started here rather than in onActivityResult so the app is in the foreground,
            // which Android requires for starting a foreground service.
            Uri out = pendingOutput;
            pendingOutput = null;
            startExport(out, pendingOptions);
            pendingOptions = null;
            return;
        }
        ExportJob job = ExportJob.current();
        if (job != null) {
            job.addListener(this);
            render(job);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ExportJob job = ExportJob.current();
        if (job != null) job.removeListener(this);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
        }
    }

    // ---- results ------------------------------------------------------------------------------

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_PICK) {
            ArrayList<Uri> uris = new ArrayList<>();
            ClipData cd = data.getClipData();
            if (cd != null) {
                for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            for (Uri u : uris) {
                try {
                    getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) {
                }
            }
            if (!uris.isEmpty()) addUris(uris);
        } else if (requestCode == REQ_TRIM) {
            int index = data.getIntExtra(TrimActivity.EXTRA_INDEX, -1);
            Uri uri = data.getParcelableExtra(TrimActivity.EXTRA_URI);
            if (index >= 0 && index < clips.size() && clips.get(index).uri.equals(uri)) {
                Clip c = clips.get(index);
                c.trimStartUs = data.getLongExtra(TrimActivity.EXTRA_START, 0);
                c.trimEndUs = data.getLongExtra(TrimActivity.EXTRA_END, c.durationUs);
                refresh();
            }
        } else if (requestCode == REQ_SAVE && data.getData() != null && pendingOptions != null) {
            pendingOutput = data.getData();
        }
    }

    @Override
    public void onBackPressed() {
        if (busy) {
            moveTaskToBack(true); // keep the clip list; the export continues in the background
            return;
        }
        super.onBackPressed();
    }

    // ---- helpers ------------------------------------------------------------------------------

    private void setBusy(boolean b, String text) {
        busy = b;
        progressPanel.setVisibility(b ? View.VISIBLE : View.GONE);
        cancelButton.setVisibility(text != null && text.startsWith("Exporting") ? View.VISIBLE : View.GONE);
        progressBar.setIndeterminate(text != null && !text.startsWith("Exporting"));
        if (text != null) status.setText(text);
        refresh();
    }

    private static void setEnabledDeep(View v, boolean enabled) {
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : 0.4f);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) setEnabledDeep(g.getChildAt(i), enabled);
        }
    }

    private Spinner spinner(List<String> items) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        return s;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(10), 0, 0);
        return t;
    }

    private TextView note(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setPadding(dp(32), 0, 0, dp(6));
        t.setGravity(Gravity.START);
        return t;
    }

    private void message(String title, String text) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("OK", null).show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

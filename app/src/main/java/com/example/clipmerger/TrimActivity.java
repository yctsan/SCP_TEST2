package com.example.clipmerger;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

/** Lets the user pick the start and end of the part of a clip to keep. */
public class TrimActivity extends Activity {

    static final String EXTRA_INDEX = "index";
    static final String EXTRA_URI = "uri";
    static final String EXTRA_NAME = "name";
    static final String EXTRA_DURATION = "duration";
    static final String EXTRA_START = "start";
    static final String EXTRA_END = "end";

    private static final long MIN_LENGTH_US = 100000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private VideoView video;
    private MediaPlayer player;
    private SeekBar seek;
    private TextView position, range, keyframeNote;
    private Button playButton;
    private Uri uri;
    private long durationUs, startUs, endUs;
    private long positionMs;
    private Mp4Source.Track videoTrack;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (player != null && video.isPlaying()) {
                positionMs = video.getCurrentPosition();
                if (positionMs * 1000 >= endUs) {
                    video.pause();
                    playButton.setText("Play");
                }
                showPosition();
            }
            ui.postDelayed(this, 50);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        uri = in.getParcelableExtra(EXTRA_URI);
        durationUs = in.getLongExtra(EXTRA_DURATION, 0);
        startUs = in.getLongExtra(EXTRA_START, 0);
        endUs = in.getLongExtra(EXTRA_END, durationUs);
        setTitle("Trim " + in.getStringExtra(EXTRA_NAME));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        box.addView(video, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.addView(controls);

        position = new TextView(this);
        position.setGravity(Gravity.CENTER_HORIZONTAL);
        controls.addView(position);

        seek = new SeekBar(this);
        seek.setMax((int) Math.max(1, durationUs / 1000));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) seekTo(progress, false);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                seekTo(s.getProgress(), true);
            }
        });
        controls.addView(seek);

        LinearLayout step = row(controls);
        step.addView(button("−1s", stepBy(-1000)));
        step.addView(button("−0.1s", stepBy(-100)));
        playButton = button("Play", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlay();
            }
        });
        step.addView(playButton);
        step.addView(button("+0.1s", stepBy(100)));
        step.addView(button("+1s", stepBy(1000)));

        LinearLayout marks = row(controls);
        marks.addView(button("Set start here", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                long p = positionMs * 1000;
                if (p > endUs - MIN_LENGTH_US) {
                    toast("Start must be before the end");
                    return;
                }
                startUs = p;
                showRange();
            }
        }));
        marks.addView(button("Set end here", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                long p = positionMs * 1000;
                if (p < startUs + MIN_LENGTH_US) {
                    toast("End must be after the start");
                    return;
                }
                endUs = p >= durationUs - 1000 ? durationUs : p;
                showRange();
            }
        }));

        range = new TextView(this);
        range.setGravity(Gravity.CENTER_HORIZONTAL);
        controls.addView(range);
        keyframeNote = new TextView(this);
        keyframeNote.setTextSize(12);
        keyframeNote.setGravity(Gravity.CENTER_HORIZONTAL);
        controls.addView(keyframeNote);

        LinearLayout actions = row(controls);
        actions.addView(button("Go to start", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                seekTo(startUs / 1000, true);
            }
        }));
        actions.addView(button("Reset", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startUs = 0;
                endUs = durationUs;
                showRange();
            }
        }));
        actions.addView(button("Done", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent r = new Intent();
                r.putExtra(EXTRA_INDEX, getIntent().getIntExtra(EXTRA_INDEX, -1));
                r.putExtra(EXTRA_URI, uri);
                r.putExtra(EXTRA_START, startUs);
                r.putExtra(EXTRA_END, endUs);
                setResult(RESULT_OK, r);
                finish();
            }
        }));

        setContentView(root);

        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                player = mp;
                seekTo(startUs / 1000, true);
            }
        });
        video.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                playButton.setText("Play");
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                toast("This phone cannot preview the clip (error " + what + "/" + extra + "). You can still trim by time.");
                return true;
            }
        });
        video.setVideoURI(uri);
        positionMs = startUs / 1000;
        showPosition();
        showRange();
        loadKeyframes();
        ui.post(ticker);
    }

    private void loadKeyframes() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Mp4Source s = Mp4Source.open(TrimActivity.this, uri);
                    final Mp4Source.Track t = s.video;
                    s.close();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            videoTrack = t;
                            showRange();
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        }, "keyframes").start();
    }

    private View.OnClickListener stepBy(final long deltaMs) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (video.isPlaying()) togglePlay();
                seekTo(positionMs + deltaMs, true);
            }
        };
    }

    private void togglePlay() {
        if (player == null) return;
        if (video.isPlaying()) {
            video.pause();
            positionMs = video.getCurrentPosition();
            playButton.setText("Play");
        } else {
            if (positionMs * 1000 >= endUs) seekTo(startUs / 1000, true);
            video.start();
            playButton.setText("Pause");
        }
        showPosition();
    }

    private void seekTo(long ms, boolean exact) {
        ms = Math.max(0, Math.min(ms, durationUs / 1000));
        positionMs = ms;
        if (player != null) {
            try {
                player.seekTo(ms, exact ? MediaPlayer.SEEK_CLOSEST : MediaPlayer.SEEK_PREVIOUS_SYNC);
            } catch (IllegalStateException ignored) {
            }
        }
        showPosition();
    }

    private void showPosition() {
        position.setText(Clip.formatUs(positionMs * 1000) + " / " + Clip.formatUs(durationUs));
        seek.setProgress((int) positionMs);
    }

    private void showRange() {
        range.setText("Keep " + Clip.formatUs(startUs) + " → " + Clip.formatUs(endUs)
                + "  (" + Clip.formatUs(endUs - startUs) + ")");
        Mp4Source.Track t = videoTrack;
        if (t != null && startUs > 0) {
            long key = t.ptsUs(t.syncIndexAtOrBefore(startUs));
            keyframeNote.setText(key < startUs - 1000
                    ? "Without re-encoding, this clip will start at the keyframe at " + Clip.formatUs(key) + "."
                    : "The start is on a keyframe, so the cut is exact even without re-encoding.");
        } else {
            keyframeNote.setText("");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (video.isPlaying()) togglePlay();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(ticker);
        video.stopPlayback();
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(r);
        return r;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this, null, android.R.attr.buttonStyleSmall);
        b.setText(text);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

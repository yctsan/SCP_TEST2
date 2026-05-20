package com.example.audiovideommaker;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_PICK_AUDIO = 1;
    private static final int REQ_PICK_IMAGE = 2;
    private static final int REQ_SAVE_VIDEO = 3;

    private Button      btnPickAudio;
    private TextView    tvAudioPath;
    private RadioGroup  rgNormalize;
    private SeekBar     seekBalance;
    private TextView    tvBalanceValue;
    private Button      btnPickImage;
    private Button      btnDefaultImage;
    private TextView    tvImagePath;
    private ImageView   ivPreview;
    private Button      btnCreateVideo;
    private TextView    tvStatus;
    private ProgressBar progressBar;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Uri audioUri       = null;
    private Uri imageUri       = null;
    private Uri pendingVideoUri = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnPickAudio    = (Button)      findViewById(R.id.btnPickAudio);
        tvAudioPath     = (TextView)    findViewById(R.id.tvAudioPath);
        rgNormalize     = (RadioGroup)  findViewById(R.id.rgNormalize);
        seekBalance     = (SeekBar)     findViewById(R.id.seekBalance);
        tvBalanceValue  = (TextView)    findViewById(R.id.tvBalanceValue);
        btnPickImage    = (Button)      findViewById(R.id.btnPickImage);
        btnDefaultImage = (Button)      findViewById(R.id.btnDefaultImage);
        tvImagePath     = (TextView)    findViewById(R.id.tvImagePath);
        ivPreview       = (ImageView)   findViewById(R.id.ivPreview);
        btnCreateVideo  = (Button)      findViewById(R.id.btnCreateVideo);
        tvStatus        = (TextView)    findViewById(R.id.tvStatus);
        progressBar     = (ProgressBar) findViewById(R.id.progressBar);

        setupBalanceSeek();

        btnPickAudio.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickAudio(); }
        });

        btnPickImage.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickImage(); }
        });

        btnDefaultImage.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                imageUri = null;
                tvImagePath.setText(getString(R.string.default_black_image));
                ivPreview.setImageDrawable(null);
                ivPreview.setBackgroundColor(Color.BLACK);
            }
        });

        btnCreateVideo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                onCreateVideo();
            }
        });
    }

    private void pickAudio() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        startActivityForResult(intent, REQ_PICK_AUDIO);
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQ_PICK_IMAGE);
    }

    private void openSavePicker() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/mp4");
        intent.putExtra(Intent.EXTRA_TITLE, "output_video.mp4");
        startActivityForResult(intent, REQ_SAVE_VIDEO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == REQ_PICK_AUDIO) {
            final Uri uri = data.getData();
            if (uri == null) return;
            try {
                getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {}
            audioUri = uri;
            tvAudioPath.setText(FileUtils.displayName(this, uri));

        } else if (requestCode == REQ_PICK_IMAGE) {
            final Uri uri = data.getData();
            if (uri == null) return;
            try {
                getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {}
            imageUri = uri;
            tvImagePath.setText(FileUtils.displayName(this, uri));
            ivPreview.setImageURI(uri);

        } else if (requestCode == REQ_SAVE_VIDEO) {
            final Uri destUri = data.getData();
            if (destUri == null) return;
            final Uri src = pendingVideoUri;
            if (src == null) return;
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        FileUtils.copyUri(MainActivity.this, src, destUri);
                        mainHandler.post(new Runnable() {
                            @Override public void run() {
                                setStatus(getString(R.string.status_done));
                                toast(getString(R.string.status_done));
                            }
                        });
                    } catch (final Exception e) {
                        mainHandler.post(new Runnable() {
                            @Override public void run() {
                                toast("Save failed: " + e.getMessage());
                            }
                        });
                    } finally {
                        FileUtils.deleteCacheFile(MainActivity.this, src);
                        pendingVideoUri = null;
                    }
                }
            }).start();
        }
    }

    private void setupBalanceSeek() {
        seekBalance.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int offset = progress - 100;
                String txt;
                if      (offset < -5) txt = "Left "  + (-offset);
                else if (offset >  5) txt = "Right " + offset;
                else                  txt = "Center (0)";
                tvBalanceValue.setText(txt);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    private VideoCreator.NormalizeMode selectedNormalizeMode() {
        int id = rgNormalize.getCheckedRadioButtonId();
        if      (id == R.id.rbNormalizePeak) return VideoCreator.NormalizeMode.PEAK;
        else if (id == R.id.rbNormalizeRms)  return VideoCreator.NormalizeMode.RMS;
        else                                 return VideoCreator.NormalizeMode.NONE;
    }

    private void onCreateVideo() {
        if (audioUri == null) {
            toast(getString(R.string.error_no_audio));
            return;
        }

        int    balanceOffset = seekBalance.getProgress() - 100;
        final float leftVol  = balanceOffset >= 0 ? 1f : (100 + balanceOffset) / 100f;
        final float rightVol = balanceOffset <= 0 ? 1f : (100 - balanceOffset) / 100f;
        final VideoCreator.NormalizeMode normalizeMode = selectedNormalizeMode();
        final Uri audioRef  = audioUri;
        final Uri imageRef  = imageUri;

        setStatus(getString(R.string.status_creating));
        progressBar.setVisibility(View.VISIBLE);
        btnCreateVideo.setEnabled(false);

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final Uri outUri = VideoCreator.create(
                            MainActivity.this, audioRef, imageRef,
                            leftVol, rightVol, normalizeMode);
                    pendingVideoUri = outUri;
                    mainHandler.post(new Runnable() {
                        @Override public void run() { openSavePicker(); }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            setStatus(getString(R.string.error_create_failed));
                            toast(getString(R.string.error_create_failed) + ": " + e.getMessage());
                        }
                    });
                } finally {
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            progressBar.setVisibility(View.GONE);
                            btnCreateVideo.setEnabled(true);
                        }
                    });
                }
            }
        }).start();
    }

    private void setStatus(String msg) { tvStatus.setText(msg); }
    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

}

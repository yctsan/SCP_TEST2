package com.example.audiovideommaker

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.example.audiovideommaker.VideoCreator.NormalizeMode

class MainActivity : Activity() {

    private lateinit var btnPickAudio: Button
    private lateinit var tvAudioPath: TextView
    private lateinit var rgNormalize: RadioGroup
    private lateinit var seekBalance: SeekBar
    private lateinit var tvBalanceValue: TextView
    private lateinit var btnPickImage: Button
    private lateinit var btnDefaultImage: Button
    private lateinit var tvImagePath: TextView
    private lateinit var ivPreview: ImageView
    private lateinit var btnCreateVideo: Button
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar

    private val mainHandler = Handler(Looper.getMainLooper())

    private var audioUri: Uri? = null
    private var imageUri: Uri? = null
    private var pendingVideoUri: Uri? = null

    private val REQ_PICK_AUDIO   = 1
    private val REQ_PICK_IMAGE   = 2
    private val REQ_SAVE_VIDEO   = 3
    private val REQ_PERMISSIONS  = 4

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnPickAudio    = findViewById(R.id.btnPickAudio)    as Button
        tvAudioPath     = findViewById(R.id.tvAudioPath)     as TextView
        rgNormalize     = findViewById(R.id.rgNormalize)     as RadioGroup
        seekBalance     = findViewById(R.id.seekBalance)     as SeekBar
        tvBalanceValue  = findViewById(R.id.tvBalanceValue)  as TextView
        btnPickImage    = findViewById(R.id.btnPickImage)    as Button
        btnDefaultImage = findViewById(R.id.btnDefaultImage) as Button
        tvImagePath     = findViewById(R.id.tvImagePath)     as TextView
        ivPreview       = findViewById(R.id.ivPreview)       as ImageView
        btnCreateVideo  = findViewById(R.id.btnCreateVideo)  as Button
        tvStatus        = findViewById(R.id.tvStatus)        as TextView
        progressBar     = findViewById(R.id.progressBar)     as ProgressBar

        setupBalanceSeek()

        btnPickAudio.setOnClickListener {
            if (hasMediaPermissions()) pickAudio()
            else requestMediaPermissions()
        }

        btnPickImage.setOnClickListener {
            if (hasMediaPermissions()) pickImage()
            else requestMediaPermissions()
        }

        btnDefaultImage.setOnClickListener {
            imageUri = null
            tvImagePath.text = getString(R.string.default_black_image)
            ivPreview.setImageDrawable(null)
            ivPreview.setBackgroundColor(0xFF000000.toInt())
        }

        btnCreateVideo.setOnClickListener { onCreateVideo() }
    }

    private fun pickAudio() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        startActivityForResult(intent, REQ_PICK_AUDIO)
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, REQ_PICK_IMAGE)
    }

    private fun openSavePicker() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/mp4"
            putExtra(Intent.EXTRA_TITLE, "output_video.mp4")
        }
        startActivityForResult(intent, REQ_SAVE_VIDEO)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return

        when (requestCode) {
            REQ_PICK_AUDIO -> {
                val uri = data.data ?: return
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                audioUri = uri
                tvAudioPath.text = FileUtils.displayName(this, uri)
            }
            REQ_PICK_IMAGE -> {
                val uri = data.data ?: return
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                imageUri = uri
                tvImagePath.text = FileUtils.displayName(this, uri)
                ivPreview.setImageURI(uri)
            }
            REQ_SAVE_VIDEO -> {
                val destUri = data.data ?: return
                val src = pendingVideoUri ?: return
                Thread {
                    try {
                        FileUtils.copyUri(this, src, destUri)
                        mainHandler.post {
                            setStatus(getString(R.string.status_done))
                            toast(getString(R.string.status_done))
                        }
                    } catch (e: Exception) {
                        mainHandler.post { toast("Save failed: ${e.message}") }
                    } finally {
                        FileUtils.deleteCacheFile(this, src)
                        pendingVideoUri = null
                    }
                }.start()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            if (!grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                toast(getString(R.string.permission_rationale))
            }
        }
    }

    private fun setupBalanceSeek() {
        seekBalance.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val offset = progress - 100
                tvBalanceValue.text = when {
                    offset < -5 -> "Left ${-offset}"
                    offset > 5  -> "Right $offset"
                    else        -> "Center (0)"
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun selectedNormalizeMode(): NormalizeMode = when (rgNormalize.checkedRadioButtonId) {
        R.id.rbNormalizePeak -> NormalizeMode.PEAK
        R.id.rbNormalizeRms  -> NormalizeMode.RMS
        else                 -> NormalizeMode.NONE
    }

    private fun onCreateVideo() {
        val audio = audioUri
        if (audio == null) {
            toast(getString(R.string.error_no_audio))
            return
        }

        val balanceOffset = seekBalance.progress - 100
        val leftVol  = if (balanceOffset >= 0) 1f else (100 + balanceOffset) / 100f
        val rightVol = if (balanceOffset <= 0) 1f else (100 - balanceOffset) / 100f
        val normalizeMode = selectedNormalizeMode()

        setStatus(getString(R.string.status_creating))
        progressBar.visibility = View.VISIBLE
        btnCreateVideo.isEnabled = false

        Thread {
            try {
                val outUri = VideoCreator.create(
                    context       = this,
                    audioUri      = audio,
                    imageUri      = imageUri,
                    leftVol       = leftVol,
                    rightVol      = rightVol,
                    normalizeMode = normalizeMode
                )
                pendingVideoUri = outUri
                mainHandler.post { openSavePicker() }
            } catch (e: Exception) {
                mainHandler.post {
                    setStatus(getString(R.string.error_create_failed))
                    toast("${getString(R.string.error_create_failed)}: ${e.message}")
                }
            } finally {
                mainHandler.post {
                    progressBar.visibility = View.GONE
                    btnCreateVideo.isEnabled = true
                }
            }
        }.start()
    }

    private fun setStatus(msg: String) { tvStatus.text = msg }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun hasMediaPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            checkSelfPermission("android.permission.READ_MEDIA_AUDIO") ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE") ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestMediaPermissions() {
        if (Build.VERSION.SDK_INT >= 34) {
            requestPermissions(arrayOf(
                "android.permission.READ_MEDIA_AUDIO",
                "android.permission.READ_MEDIA_IMAGES",
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
            ), REQ_PERMISSIONS)
        } else if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(
                "android.permission.READ_MEDIA_AUDIO",
                "android.permission.READ_MEDIA_IMAGES"
            ), REQ_PERMISSIONS)
        } else {
            requestPermissions(
                arrayOf("android.permission.READ_EXTERNAL_STORAGE"),
                REQ_PERMISSIONS
            )
        }
    }
}

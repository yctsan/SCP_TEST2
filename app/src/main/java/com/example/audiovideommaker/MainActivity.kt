package com.example.audiovideommaker

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.audiovideommaker.VideoCreator.NormalizeMode
import com.example.audiovideommaker.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var audioUri: Uri? = null
    private var imageUri: Uri? = null
    private var pendingVideoUri: Uri? = null

    // ── Permission request ──
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (!grants.values.all { it }) toast(getString(R.string.permission_rationale))
    }

    // ── Audio picker ──
    private val audioPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            contentResolver.takePersistableUriPermission(
                it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            audioUri = it
            binding.tvAudioPath.text = FileUtils.displayName(this, it)
        }
    }

    // ── Image picker ──
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            contentResolver.takePersistableUriPermission(
                it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            imageUri = it
            binding.tvImagePath.text = FileUtils.displayName(this, it)
            binding.ivPreview.setImageURI(it)
        }
    }

    // ── Save-As picker ──
    private val savePickerLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4")
    ) { destUri: Uri? ->
        destUri ?: return@registerForActivityResult
        val src = pendingVideoUri ?: return@registerForActivityResult
        lifecycleScope.launch {
            try {
                FileUtils.copyUri(this@MainActivity, src, destUri)
                setStatus(getString(R.string.status_done))
                toast(getString(R.string.status_done))
            } catch (e: Exception) {
                toast("Save failed: ${e.message}")
            } finally {
                FileUtils.deleteCacheFile(this@MainActivity, src)
                pendingVideoUri = null
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBalanceSeek()

        binding.btnPickAudio.setOnClickListener {
            if (hasMediaPermissions()) audioPickerLauncher.launch(arrayOf("audio/*"))
            else requestMediaPermissions()
        }

        binding.btnPickImage.setOnClickListener {
            if (hasMediaPermissions()) imagePickerLauncher.launch(arrayOf("image/*"))
            else requestMediaPermissions()
        }

        binding.btnDefaultImage.setOnClickListener {
            imageUri = null
            binding.tvImagePath.text = getString(R.string.default_black_image)
            binding.ivPreview.setImageDrawable(null)
            binding.ivPreview.setBackgroundColor(0xFF000000.toInt())
        }

        binding.btnCreateVideo.setOnClickListener { onCreateVideo() }
    }

    private fun setupBalanceSeek() {
        binding.seekBalance.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val offset = progress - 100
                binding.tvBalanceValue.text = when {
                    offset < -5 -> "Left ${-offset}"
                    offset > 5  -> "Right $offset"
                    else        -> "Center (0)"
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun selectedNormalizeMode(): NormalizeMode = when (binding.rgNormalize.checkedRadioButtonId) {
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

        val balanceOffset = binding.seekBalance.progress - 100
        val leftVol  = if (balanceOffset >= 0) 1f else (100 + balanceOffset) / 100f
        val rightVol = if (balanceOffset <= 0) 1f else (100 - balanceOffset) / 100f
        val normalizeMode = selectedNormalizeMode()

        setStatus(getString(R.string.status_creating))
        binding.progressBar.visibility = View.VISIBLE
        binding.btnCreateVideo.isEnabled = false

        lifecycleScope.launch {
            try {
                val outUri = VideoCreator.create(
                    context       = this@MainActivity,
                    audioUri      = audio,
                    imageUri      = imageUri,
                    leftVol       = leftVol,
                    rightVol      = rightVol,
                    normalizeMode = normalizeMode
                )
                pendingVideoUri = outUri
                savePickerLauncher.launch("output_video.mp4")
            } catch (e: Exception) {
                setStatus(getString(R.string.error_create_failed))
                toast("${getString(R.string.error_create_failed)}: ${e.message}")
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.btnCreateVideo.isEnabled = true
            }
        }
    }

    private fun setStatus(msg: String) { binding.tvStatus.text = msg }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun hasMediaPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestMediaPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permLauncher.launch(arrayOf(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permLauncher.launch(arrayOf(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_IMAGES
            ))
        } else {
            permLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
        }
    }
}

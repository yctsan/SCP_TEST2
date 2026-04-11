package com.example.audiovideommaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.*
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Creates an MP4 video from a still image + audio file.
 *
 * Processing order: decode PCM → normalize (optional) → balance → encode
 */
object VideoCreator {

    private const val VIDEO_MIME   = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val VIDEO_WIDTH  = 1280
    private const val VIDEO_HEIGHT = 720
    private const val VIDEO_FPS    = 30
    private const val VIDEO_BIT    = 4_000_000
    private const val AUDIO_MIME   = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val AUDIO_BIT    = 128_000
    private const val TIMEOUT_US   = 10_000L

    /** Target peak for peak normalisation: -1 dBFS */
    private const val TARGET_PEAK_DBFS = -1.0
    /** Target RMS for loudness normalisation: -18 dBFS (broadcast programme loudness) */
    private const val TARGET_RMS_DBFS  = -18.0
    /** Maximum allowed gain to prevent extreme amplification of near-silent material */
    private const val MAX_GAIN_DB      = 40.0

    enum class NormalizeMode {
        /** No normalisation */
        NONE,
        /** Scale so the loudest sample reaches TARGET_PEAK_DBFS */
        PEAK,
        /** Scale so the RMS level reaches TARGET_RMS_DBFS */
        RMS
    }

    /**
     * @param leftVol       gain for left  channel (0.0–1.0, 1.0 = unchanged)
     * @param rightVol      gain for right channel (0.0–1.0, 1.0 = unchanged)
     * @param normalizeMode which automatic normalisation algorithm to apply
     * @return Uri pointing to the temporary MP4 cache file
     */
    suspend fun create(
        context:       Context,
        audioUri:      Uri,
        imageUri:      Uri?,
        leftVol:       Float         = 1f,
        rightVol:      Float         = 1f,
        normalizeMode: NormalizeMode = NormalizeMode.NONE
    ): Uri = withContext(Dispatchers.IO) {

        val outFile = File(context.cacheDir, "avm_output_${System.currentTimeMillis()}.mp4")

        // 1. Prepare source bitmap
        val srcBitmap: Bitmap = if (imageUri != null) {
            loadAndScaleBitmap(context, imageUri)
        } else {
            Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawColor(Color.BLACK)
            }
        }

        // 2. Decode audio to raw 16-bit PCM
        val (pcmData, sampleRate, channelCount) = decodeToPcm(context, audioUri)
        val audioDurationUs = pcmData.size.toLong() * 1_000_000L /
                (sampleRate.toLong() * channelCount.toLong() * 2L)

        // 3. Normalise volume (two-pass: analyse then apply gain)
        when (normalizeMode) {
            NormalizeMode.PEAK -> normalizePeak(pcmData)
            NormalizeMode.RMS  -> normalizeRms(pcmData)
            NormalizeMode.NONE -> Unit
        }

        // 4. Apply stereo balance
        applyBalance(pcmData, channelCount, leftVol, rightVol)

        // 5. Encode video + audio into MP4
        mux(
            outFile      = outFile,
            bitmap       = srcBitmap,
            pcmData      = pcmData,
            sampleRate   = sampleRate,
            channelCount = channelCount,
            durationUs   = audioDurationUs
        )

        srcBitmap.recycle()
        Uri.fromFile(outFile)
    }

    // ─────────────────────────────────────────────────
    // Volume normalisation
    // ─────────────────────────────────────────────────

    /**
     * Peak normalisation (two-pass).
     *
     * Pass 1 – scan all samples to find maximum absolute value.
     * Pass 2 – apply linear gain so that peak maps to TARGET_PEAK_DBFS.
     * Gain is capped at MAX_GAIN_DB.
     */
    private fun normalizePeak(pcm: ByteArray) {
        // Pass 1: find peak
        var peak = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val a = abs(readSampleLE(pcm, i))
            if (a > peak) peak = a
            i += 2
        }
        if (peak == 0) return  // silence

        val targetLinear  = 32767.0 * 10.0.pow(TARGET_PEAK_DBFS / 20.0)
        val rawGainDb     = 20.0 * log10(targetLinear / peak)
        val clampedGainDb = rawGainDb.coerceAtMost(MAX_GAIN_DB)
        val gain          = 10.0.pow(clampedGainDb / 20.0).toFloat()

        // Pass 2: apply gain
        applyGainAllSamples(pcm, gain)
    }

    /**
     * RMS (loudness) normalisation (two-pass).
     *
     * Pass 1 – compute RMS level across all samples.
     * Pass 2 – apply gain so RMS reaches TARGET_RMS_DBFS.
     * Any resulting clips are hard-limited via coerceIn.
     * Gain is capped at MAX_GAIN_DB.
     */
    private fun normalizeRms(pcm: ByteArray) {
        // Pass 1: compute RMS
        var sumSq = 0.0
        var count = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val s = readSampleLE(pcm, i).toDouble()
            sumSq += s * s
            count++
            i += 2
        }
        if (count == 0 || sumSq == 0.0) return  // silence

        val rms           = sqrt(sumSq / count)
        val rmsDb         = 20.0 * log10(rms / 32767.0)
        val neededGainDb  = TARGET_RMS_DBFS - rmsDb
        val clampedGainDb = neededGainDb.coerceAtMost(MAX_GAIN_DB)
        val gain          = 10.0.pow(clampedGainDb / 20.0).toFloat()

        // Pass 2: apply gain (coerceIn clips any over-scale)
        applyGainAllSamples(pcm, gain)
    }

    /** Multiplies every 16-bit LE sample by [gain], clamping to [-32768, 32767]. */
    private fun applyGainAllSamples(pcm: ByteArray, gain: Float) {
        var i = 0
        while (i + 1 < pcm.size) {
            val scaled = (readSampleLE(pcm, i) * gain).toInt().coerceIn(-32768, 32767)
            writeSampleLE(pcm, i, scaled)
            i += 2
        }
    }

    // ─────────────────────────────────────────────────
    // Bitmap loading
    // ─────────────────────────────────────────────────

    private fun loadAndScaleBitmap(context: Context, uri: Uri): Bitmap {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, opts)
        }
        val scaleW = opts.outWidth  / VIDEO_WIDTH
        val scaleH = opts.outHeight / VIDEO_HEIGHT
        val scale  = maxOf(1, minOf(scaleW, scaleH))
        val loadOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = scale }
        val raw = context.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, loadOpts)
        } ?: Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888)

        val scaled = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scaled)
        canvas.drawColor(Color.BLACK)
        val ratioSrc = raw.width.toFloat()  / raw.height
        val ratioDst = VIDEO_WIDTH.toFloat() / VIDEO_HEIGHT
        val (dw, dh) = if (ratioSrc > ratioDst)
            VIDEO_WIDTH to (VIDEO_WIDTH / ratioSrc).toInt()
        else
            (VIDEO_HEIGHT * ratioSrc).toInt() to VIDEO_HEIGHT
        val left = (VIDEO_WIDTH  - dw) / 2f
        val top  = (VIDEO_HEIGHT - dh) / 2f
        canvas.drawBitmap(raw, null, android.graphics.RectF(left, top, left + dw, top + dh), null)
        raw.recycle()
        return scaled
    }

    // ─────────────────────────────────────────────────
    // PCM decode
    // ─────────────────────────────────────────────────

    data class PcmResult(val bytes: ByteArray, val sampleRate: Int, val channels: Int)

    private fun decodeToPcm(context: Context, uri: Uri): PcmResult {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        var audioTrack = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                audioTrack = i
                format = fmt
                break
            }
        }
        checkNotNull(format) { "No audio track found in file" }
        extractor.selectTrack(audioTrack)

        val mime         = format.getString(MediaFormat.KEY_MIME)!!
        val sampleRate   = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()

        val pcmOut = java.io.ByteArrayOutputStream()
        val info   = MediaCodec.BufferInfo()
        var eos    = false

        while (!eos) {
            val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
            if (inIdx >= 0) {
                val buf  = decoder.getInputBuffer(inIdx)!!
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) {
                    decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                } else {
                    decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
            val outIdx = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
            if (outIdx >= 0) {
                val buf   = decoder.getOutputBuffer(outIdx)!!
                val chunk = ByteArray(info.size)
                buf.get(chunk)
                pcmOut.write(chunk)
                decoder.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
            }
        }

        decoder.stop()
        decoder.release()
        extractor.release()
        return PcmResult(pcmOut.toByteArray(), sampleRate, channelCount)
    }

    // ─────────────────────────────────────────────────
    // Balance
    // ─────────────────────────────────────────────────

    private fun applyBalance(pcm: ByteArray, channels: Int, leftVol: Float, rightVol: Float) {
        if (leftVol == 1f && rightVol == 1f) return
        if (channels < 2) {
            scaleChannel(pcm, 0, 1, (leftVol + rightVol) / 2f)
            return
        }
        scaleChannel(pcm, 0, channels, leftVol)
        scaleChannel(pcm, 1, channels, rightVol)
    }

    private fun scaleChannel(pcm: ByteArray, chIdx: Int, channels: Int, vol: Float) {
        val frameSize = channels * 2
        var i = chIdx * 2
        while (i + 1 < pcm.size) {
            val scaled = (readSampleLE(pcm, i) * vol).toInt().coerceIn(-32768, 32767)
            writeSampleLE(pcm, i, scaled)
            i += frameSize
        }
    }

    // ─────────────────────────────────────────────────
    // 16-bit little-endian helpers
    // ─────────────────────────────────────────────────

    private fun readSampleLE(buf: ByteArray, off: Int): Int {
        val lo = buf[off].toInt()     and 0xFF
        val hi = buf[off + 1].toInt() and 0xFF
        val u  = (hi shl 8) or lo
        return if (u >= 0x8000) u - 0x10000 else u
    }

    private fun writeSampleLE(buf: ByteArray, off: Int, value: Int) {
        buf[off]     = (value        and 0xFF).toByte()
        buf[off + 1] = ((value shr 8) and 0xFF).toByte()
    }

    // ─────────────────────────────────────────────────
    // Mux: encode still-frame video + AAC audio → MP4
    // ─────────────────────────────────────────────────

    private fun mux(
        outFile:      File,
        bitmap:       Bitmap,
        pcmData:      ByteArray,
        sampleRate:   Int,
        channelCount: Int,
        durationUs:   Long
    ) {
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // Video encoder
        val videoFormat = MediaFormat.createVideoFormat(VIDEO_MIME, VIDEO_WIDTH, VIDEO_HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE,         VIDEO_BIT)
            setInteger(MediaFormat.KEY_FRAME_RATE,       VIDEO_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val videoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME)
        videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = videoEncoder.createInputSurface()
        videoEncoder.start()

        val videoInfo    = MediaCodec.BufferInfo()
        var videoTrackId = -1
        val totalFrames  = ((durationUs / 1_000_000.0) * VIDEO_FPS).toLong().coerceAtLeast(1)
        val frameUs      = 1_000_000L / VIDEO_FPS
        var frameIdx     = 0L
        var videoDone    = false

        while (!videoDone) {
            if (frameIdx <= totalFrames) {
                val c = surface.lockHardwareCanvas()
                c.drawBitmap(bitmap, 0f, 0f, null)
                surface.unlockCanvasAndPost(c)
                frameIdx++
                if (frameIdx > totalFrames) videoEncoder.signalEndOfInputStream()
            }
            val outIdx = videoEncoder.dequeueOutputBuffer(videoInfo, TIMEOUT_US)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    videoTrackId = muxer.addTrack(videoEncoder.outputFormat)
                outIdx >= 0 -> {
                    val buf = videoEncoder.getOutputBuffer(outIdx)!!
                    if (videoInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        videoInfo.presentationTimeUs = (frameIdx - 1) * frameUs
                        if (videoTrackId >= 0) muxer.writeSampleData(videoTrackId, buf, videoInfo)
                    }
                    videoEncoder.releaseOutputBuffer(outIdx, false)
                    if (videoInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                        videoDone = true
                }
            }
        }

        // Audio encoder (AAC-LC)
        val audioFormat = MediaFormat.createAudioFormat(AUDIO_MIME, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_BIT_RATE,   AUDIO_BIT)
            setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        val audioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME)
        audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioEncoder.start()

        val audioInfo    = MediaCodec.BufferInfo()
        var audioTrackId = muxer.addTrack(audioEncoder.outputFormat)
        muxer.start()

        var pcmOffset  = 0
        var audioDone  = false
        var audioPtsUs = 0L

        while (!audioDone) {
            val inIdx = audioEncoder.dequeueInputBuffer(TIMEOUT_US)
            if (inIdx >= 0) {
                val buf   = audioEncoder.getInputBuffer(inIdx)!!
                buf.clear()
                val chunk = minOf(buf.capacity(), pcmData.size - pcmOffset)
                if (chunk <= 0) {
                    audioEncoder.queueInputBuffer(inIdx, 0, 0, audioPtsUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                } else {
                    buf.put(pcmData, pcmOffset, chunk)
                    audioEncoder.queueInputBuffer(inIdx, 0, chunk, audioPtsUs, 0)
                    audioPtsUs += (chunk / (channelCount * 2)).toLong() * 1_000_000L / sampleRate
                    pcmOffset  += chunk
                }
            }
            val outIdx = audioEncoder.dequeueOutputBuffer(audioInfo, TIMEOUT_US)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    audioTrackId = muxer.addTrack(audioEncoder.outputFormat)
                outIdx >= 0 -> {
                    val buf = audioEncoder.getOutputBuffer(outIdx)!!
                    if (audioInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0)
                        muxer.writeSampleData(audioTrackId, buf, audioInfo)
                    audioEncoder.releaseOutputBuffer(outIdx, false)
                    if (audioInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                        audioDone = true
                }
            }
        }

        audioEncoder.stop(); audioEncoder.release()
        videoEncoder.stop(); videoEncoder.release()
        surface.release()
        muxer.stop(); muxer.release()
    }
}

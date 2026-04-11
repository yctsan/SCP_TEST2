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
import java.nio.ByteBuffer

/**
 * Creates an MP4 video by encoding a single still image as video frames
 * for the full duration of the supplied audio track.
 *
 * Audio balance is applied by scaling left/right PCM samples before muxing.
 */
object VideoCreator {

    private const val VIDEO_MIME   = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val VIDEO_WIDTH  = 1280
    private const val VIDEO_HEIGHT = 720
    private const val VIDEO_FPS    = 30
    private const val VIDEO_BIT    = 4_000_000  // 4 Mbps
    private const val AUDIO_MIME   = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val AUDIO_BIT    = 128_000    // 128 kbps
    private const val TIMEOUT_US   = 10_000L

    /**
     * @param leftVol   0.0 – 1.0 (1.0 = full left channel)
     * @param rightVol  0.0 – 1.0 (1.0 = full right channel)
     * @return Uri pointing to the temporary cache file
     */
    suspend fun create(
        context:  Context,
        audioUri: Uri,
        imageUri: Uri?,
        leftVol:  Float = 1f,
        rightVol: Float = 1f
    ): Uri = withContext(Dispatchers.IO) {

        val outFile = File(context.cacheDir, "avm_output_${System.currentTimeMillis()}.mp4")

        // ── 1. Prepare source bitmap ──
        val srcBitmap: Bitmap = if (imageUri != null) {
            loadAndScaleBitmap(context, imageUri)
        } else {
            Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawColor(Color.BLACK)
            }
        }

        // ── 2. Decode audio to raw PCM ──
        val (pcmData, sampleRate, channelCount) = decodeToPcm(context, audioUri)
        val audioDurationUs = pcmData.size.toLong() * 1_000_000L /
                (sampleRate.toLong() * channelCount.toLong() * 2L)  // 16-bit = 2 bytes/sample

        // ── 3. Apply balance ──
        applyBalance(pcmData, channelCount, leftVol, rightVol)

        // ── 4. Encode video + audio into MP4 ──
        mux(
            outFile       = outFile,
            bitmap        = srcBitmap,
            pcmData       = pcmData,
            sampleRate    = sampleRate,
            channelCount  = channelCount,
            durationUs    = audioDurationUs
        )

        srcBitmap.recycle()
        Uri.fromFile(outFile)
    }

    // ───────────────────────────────────────────────
    // Bitmap loading
    // ───────────────────────────────────────────────
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

        // Scale to exactly VIDEO_WIDTH x VIDEO_HEIGHT (letter-box or fill)
        val scaled = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scaled)
        canvas.drawColor(Color.BLACK)
        val ratioSrc = raw.width.toFloat() / raw.height
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

    // ───────────────────────────────────────────────
    // PCM decode
    // ───────────────────────────────────────────────
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

        val mime        = format.getString(MediaFormat.KEY_MIME)!!
        val sampleRate  = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount= format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

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
                val buf = decoder.getOutputBuffer(outIdx)!!
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

    // ───────────────────────────────────────────────
    // Balance: scale 16-bit PCM samples per channel
    // ───────────────────────────────────────────────
    private fun applyBalance(pcm: ByteArray, channels: Int, leftVol: Float, rightVol: Float) {
        if (leftVol == 1f && rightVol == 1f) return
        if (channels < 2) {
            // mono – apply average of left/right
            val vol = (leftVol + rightVol) / 2f
            scaleChannel(pcm, 0, 1, vol)
            return
        }
        scaleChannel(pcm, 0, channels, leftVol)   // left
        scaleChannel(pcm, 1, channels, rightVol)  // right
    }

    /** Scales every sample of a given channel index in interleaved 16-bit PCM. */
    private fun scaleChannel(pcm: ByteArray, chIdx: Int, channels: Int, vol: Float) {
        val bytesPerSample = 2
        val frameSize      = channels * bytesPerSample
        var i = chIdx * bytesPerSample
        while (i + 1 < pcm.size) {
            val s = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)  // little-endian
            val scaled = (s * vol).toInt().coerceIn(-32768, 32767)
            pcm[i]     = (scaled and 0xFF).toByte()
            pcm[i + 1] = ((scaled shr 8) and 0xFF).toByte()
            i += frameSize
        }
    }

    // ───────────────────────────────────────────────
    // Mux: encode still-frame video + AAC audio into MP4
    // ───────────────────────────────────────────────
    private fun mux(
        outFile:      File,
        bitmap:       Bitmap,
        pcmData:      ByteArray,
        sampleRate:   Int,
        channelCount: Int,
        durationUs:   Long
    ) {
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // — encode video —
        val videoFormat = MediaFormat.createVideoFormat(VIDEO_MIME, VIDEO_WIDTH, VIDEO_HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BIT)
            setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS)
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
        var encoderDone  = false

        while (!encoderDone) {
            // draw the bitmap on the surface for each frame
            if (frameIdx <= totalFrames) {
                val canvas = surface.lockHardwareCanvas()
                canvas.drawBitmap(bitmap, 0f, 0f, null)
                surface.unlockCanvasAndPost(canvas)
                frameIdx++
                if (frameIdx > totalFrames) {
                    videoEncoder.signalEndOfInputStream()
                }
            }

            val outIdx = videoEncoder.dequeueOutputBuffer(videoInfo, TIMEOUT_US)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    videoTrackId = muxer.addTrack(videoEncoder.outputFormat)
                    // audio track added below; muxer.start() called after both tracks known
                }
                outIdx >= 0 -> {
                    val buf = videoEncoder.getOutputBuffer(outIdx)!!
                    if (videoInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        videoInfo.presentationTimeUs = (frameIdx - 1) * frameUs
                        if (videoTrackId >= 0) muxer.writeSampleData(videoTrackId, buf, videoInfo)
                    }
                    videoEncoder.releaseOutputBuffer(outIdx, false)
                    if (videoInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                        encoderDone = true
                }
            }
        }

        // — encode audio (AAC) —
        val chanMask = if (channelCount == 1)
            AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val audioFormat = MediaFormat.createAudioFormat(AUDIO_MIME, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT)
            setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        val audioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME)
        audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioEncoder.start()

        val audioInfo    = MediaCodec.BufferInfo()
        var audioTrackId = muxer.addTrack(audioEncoder.outputFormat)

        // need both track ids before start
        muxer.start()

        var pcmOffset      = 0
        var audioEncDone   = false
        var audioPtsUs     = 0L

        while (!audioEncDone) {
            val inIdx = audioEncoder.dequeueInputBuffer(TIMEOUT_US)
            if (inIdx >= 0) {
                val buf = audioEncoder.getInputBuffer(inIdx)!!
                buf.clear()
                val chunk = minOf(buf.capacity(), pcmData.size - pcmOffset)
                if (chunk <= 0) {
                    audioEncoder.queueInputBuffer(
                        inIdx, 0, 0, audioPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                } else {
                    buf.put(pcmData, pcmOffset, chunk)
                    audioEncoder.queueInputBuffer(inIdx, 0, chunk, audioPtsUs, 0)
                    val samplesInChunk = chunk / (channelCount * 2)
                    audioPtsUs += samplesInChunk * 1_000_000L / sampleRate
                    pcmOffset  += chunk
                }
            }

            val outIdx = audioEncoder.dequeueOutputBuffer(audioInfo, TIMEOUT_US)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    audioTrackId = muxer.addTrack(audioEncoder.outputFormat)
                }
                outIdx >= 0 -> {
                    val buf = audioEncoder.getOutputBuffer(outIdx)!!
                    if (audioInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        muxer.writeSampleData(audioTrackId, buf, audioInfo)
                    }
                    audioEncoder.releaseOutputBuffer(outIdx, false)
                    if (audioInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                        audioEncDone = true
                }
            }
        }

        audioEncoder.stop()
        audioEncoder.release()
        videoEncoder.stop()
        videoEncoder.release()
        surface.release()
        muxer.stop()
        muxer.release()
    }
}

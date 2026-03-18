package com.motionedge.cam

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * VideoRecorder
 * -------------
 * Encodes processed Bitmap frames to an H.264/MP4 file using MediaCodec + MediaMuxer.
 * Saves to the device's Movies/MotionEdgeCam folder and registers with MediaStore
 * so it appears in the Gallery immediately.
 */
class VideoRecorder(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val fps: Int = 30
) {
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var presentationTimeUs = 0L
    private var outputFile: File? = null
    private var isRecording = false

    companion object {
        private const val TAG = "VideoRecorder"
        private const val MIME = "video/avc"
        private const val BIT_RATE = 4_000_000   // 4 Mbps — good quality at low size
    }

    fun start() {
        // Ensure dimensions are even (H.264 requirement)
        val encW = if (width  % 2 == 0) width  else width  - 1
        val encH = if (height % 2 == 0) height else height - 1

        // Output file
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "MotionEdgeCam"
        ).also { it.mkdirs() }
        val ts  = System.currentTimeMillis()
        outputFile = File(dir, "MEC_$ts.mp4")

        // Encoder setup
        val format = MediaFormat.createVideoFormat(MIME, encW, encH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        encoder = MediaCodec.createEncoderByType(MIME).also { enc ->
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
        }

        muxer = MediaMuxer(outputFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        presentationTimeUs = 0L
        muxerStarted = false
        trackIndex   = -1
        isRecording  = true
        Log.d(TAG, "Recording started → ${outputFile!!.absolutePath}")
    }

    /** Call once per processed frame while recording. */
    fun encodeFrame(bitmap: Bitmap) {
        if (!isRecording) return
        val enc = encoder ?: return

        val encW = if (width  % 2 == 0) width  else width  - 1
        val encH = if (height % 2 == 0) height else height - 1

        // Scale bitmap to encoder dimensions if needed
        val scaled = if (bitmap.width != encW || bitmap.height != encH)
            Bitmap.createScaledBitmap(bitmap, encW, encH, false) else bitmap

        // Convert ARGB → YUV420
        val yuv = bitmapToYuv420(scaled, encW, encH)

        // Feed to encoder
        val inputIdx = enc.dequeueInputBuffer(10_000)
        if (inputIdx >= 0) {
            val buf: ByteBuffer = enc.getInputBuffer(inputIdx)!!
            buf.clear()
            buf.put(yuv)
            val frameDurationUs = 1_000_000L / fps
            enc.queueInputBuffer(inputIdx, 0, yuv.size, presentationTimeUs, 0)
            presentationTimeUs += frameDurationUs
        }

        drainEncoder(false)
    }

    fun stop(): String? {
        if (!isRecording) return null
        isRecording = false
        val enc = encoder ?: return null

        // Signal EOS
        val inputIdx = enc.dequeueInputBuffer(10_000)
        if (inputIdx >= 0) {
            enc.queueInputBuffer(inputIdx, 0, 0, presentationTimeUs,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        drainEncoder(true)

        enc.stop()
        enc.release()
        encoder = null

        if (muxerStarted) {
            muxer?.stop()
        }
        muxer?.release()
        muxer = null

        val path = outputFile?.absolutePath
        outputFile?.let { addToMediaStore(it) }
        Log.d(TAG, "Recording saved → $path")
        return path
    }

    // ── Internal helpers ───────────────────────────────────────────────────

    private fun drainEncoder(endOfStream: Boolean) {
        val enc = encoder ?: return
        val mx  = muxer  ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIdx = enc.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = mx.addTrack(enc.outputFormat)
                    mx.start()
                    muxerStarted = true
                }
                outIdx >= 0 -> {
                    val buf = enc.getOutputBuffer(outIdx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        info.size = 0
                    }
                    if (info.size > 0 && muxerStarted) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        mx.writeSampleData(trackIndex, buf, info)
                    }
                    enc.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }

    private fun bitmapToYuv420(bmp: Bitmap, w: Int, h: Int): ByteArray {
        val argb = IntArray(w * h)
        bmp.getPixels(argb, 0, w, 0, 0, w, h)
        val yuv = ByteArray(w * h * 3 / 2)
        var yIdx = 0
        var uvIdx = w * h
        for (j in 0 until h) {
            for (i in 0 until w) {
                val pixel = argb[j * w + i]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr  8) and 0xFF
                val b =  pixel         and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yIdx++] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    yuv[uvIdx++] = u.coerceIn(0, 255).toByte()
                    yuv[uvIdx++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
        return yuv
    }

    private fun addToMediaStore(file: File) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MotionEdgeCam")
                put(MediaStore.Video.Media.IS_PENDING, 0)
            } else {
                put(MediaStore.Video.Media.DATA, file.absolutePath)
            }
        }
        context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
    }
}

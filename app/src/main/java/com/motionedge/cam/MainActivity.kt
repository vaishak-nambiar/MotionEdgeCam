package com.motionedge.cam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.motionedge.cam.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var processor: MotionEdgeProcessor
    private var recorder: VideoRecorder? = null
    private var isRecording = false

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var surfaceReady = false
    private var frameWidth  = 0
    private var frameHeight = 0

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding  = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        processor = MotionEdgeProcessor()

        setupSurface()
        setupSliders()
        setupRecordButton()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQ_PERMISSIONS)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        scope.cancel()
        if (isRecording) recorder?.stop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS && allPermissionsGranted()) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // ── Surface setup ──────────────────────────────────────────────────────

    private fun setupSurface() {
        binding.surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true }
            override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) { surfaceReady = true }
            override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false }
        })
    }

    // ── Sliders ────────────────────────────────────────────────────────────

    private fun setupSliders() {
        fun SeekBar.onProgress(label: android.widget.TextView, apply: (Int) -> Unit) {
            label.text = progress.toString()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, user: Boolean) {
                    label.text = v.toString(); apply(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }

        binding.seekMotion.onProgress(binding.tvMotionVal) { processor.motionThreshold  = maxOf(1, it) }
        binding.seekNoise .onProgress(binding.tvNoiseVal)  { processor.noiseSuppression = it }
        binding.seekEdge  .onProgress(binding.tvEdgeVal)   { processor.edgeThreshold    = maxOf(1, it) }

        // Seed processor with initial slider values
        processor.motionThreshold  = binding.seekMotion.progress.coerceAtLeast(1)
        processor.noiseSuppression = binding.seekNoise.progress
        processor.edgeThreshold    = binding.seekEdge.progress.coerceAtLeast(1)
    }

    // ── Record button ──────────────────────────────────────────────────────

    private fun setupRecordButton() {
        binding.btnRecord.setOnClickListener {
            if (!isRecording) startRecording() else stopRecording()
        }
    }

    private fun startRecording() {
        val w = frameWidth; val h = frameHeight
        if (w == 0 || h == 0) {
            Toast.makeText(this, "Camera not ready yet", Toast.LENGTH_SHORT).show()
            return
        }
        recorder = VideoRecorder(this, w, h)
        recorder!!.start()
        isRecording = true
        binding.btnRecord.text  = "⏹  Stop Recording"
        binding.btnRecord.backgroundTintList =
            ContextCompat.getColorStateList(this, android.R.color.holo_red_dark)
        binding.recIndicator.visibility = View.VISIBLE
    }

    private fun stopRecording() {
        val path = recorder?.stop()
        recorder     = null
        isRecording  = false
        binding.btnRecord.text = "⏺  Start Recording"
        binding.btnRecord.backgroundTintList =
            ContextCompat.getColorStateList(this, android.R.color.darker_gray)
        binding.recIndicator.visibility = View.GONE
        runOnUiThread {
            Toast.makeText(this, "Saved to Movies/MotionEdgeCam", Toast.LENGTH_LONG).show()
        }
        Log.d(TAG, "Saved: $path")
    }

    // ── Camera ─────────────────────────────────────────────────────────────

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { it.setAnalyzer(cameraExecutor, ::analyzeFrame) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    analysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ── Frame analysis ─────────────────────────────────────────────────────

    private fun analyzeFrame(imageProxy: ImageProxy) {
        val bitmap = imageProxy.toBitmap() ?: run { imageProxy.close(); return }
        frameWidth  = bitmap.width
        frameHeight = bitmap.height

        scope.launch {
            val result = processor.process(bitmap)
            bitmap.recycle()

            // Feed to recorder if active
            if (isRecording) {
                recorder?.encodeFrame(result)
            }

            // Draw to surface
            if (surfaceReady) {
                withContext(Dispatchers.Main) {
                    drawToSurface(result)
                }
            }
            result.recycle()
        }
        imageProxy.close()
    }

    private fun drawToSurface(bitmap: Bitmap) {
        val holder = binding.surfaceView.holder
        val canvas: Canvas? = try { holder.lockCanvas() } catch (e: Exception) { null }
        canvas ?: return
        try {
            val surfW = canvas.width.toFloat()
            val surfH = canvas.height.toFloat()
            val bmpW  = bitmap.width.toFloat()
            val bmpH  = bitmap.height.toFloat()

            // Scale to fit surface, maintaining aspect ratio
            val scale   = minOf(surfW / bmpW, surfH / bmpH)
            val drawW   = bmpW * scale
            val drawH   = bmpH * scale
            val left    = (surfW - drawW) / 2f
            val top     = (surfH - drawH) / 2f

            canvas.drawColor(android.graphics.Color.BLACK)
            canvas.drawBitmap(
                bitmap,
                Rect(0, 0, bitmap.width, bitmap.height),
                android.graphics.RectF(left, top, left + drawW, top + drawH),
                null
            )
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun ImageProxy.toBitmap(): Bitmap? {
        return try {
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer
            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()
            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 80, out)
            val data = out.toByteArray()
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
        } catch (e: Exception) {
            Log.e(TAG, "toBitmap failed", e)
            null
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }
}

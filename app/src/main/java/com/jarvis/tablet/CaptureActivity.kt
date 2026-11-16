package com.jarvis.tablet

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.view.Surface
import android.view.WindowManager
import java.io.ByteArrayOutputStream

/** Invisible photo: a transparent Activity (so the camera is allowed in the background) that takes one still and closes itself. */
class CaptureActivity : Activity() {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var preview: SurfaceTexture? = null
    private var rotation = 90
    @Volatile private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            finishWith(null); return
        }
        thread = HandlerThread("jarvis-cam").also { it.start() }
        handler = Handler(thread!!.looper)
        handler!!.postDelayed({ finishWith(null) }, 9000)
        openCamera(intent.getStringExtra("facing") ?: "back")
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun openCamera(facing: String) {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val want = if (facing == "front") CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
                ?: cm.cameraIdList.firstOrNull()
            if (id == null) { finishWith(null); return }
            val chars = cm.getCameraCharacteristics(id)
            val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.JPEG)
            val size = sizes?.filter { it.width <= 1600 }?.maxByOrNull { it.width * it.height }
                ?: sizes?.minByOrNull { it.width * it.height }
            if (size == null) { finishWith(null); return }
            rotation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val rd = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
            reader = rd
            rd.setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage()
                if (img != null) {
                    try {
                        val buf = img.planes[0].buffer
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        finishWith(encode(bytes))
                    } finally {
                        img.close()
                    }
                }
            }, handler)
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { camera = c; startSession(c, rd) }
                override fun onDisconnected(c: CameraDevice) { c.close(); finishWith(null) }
                override fun onError(c: CameraDevice, error: Int) { c.close(); finishWith(null) }
            }, handler)
        } catch (e: Exception) {
            finishWith(null)
        }
    }

    private fun startSession(c: CameraDevice, rd: ImageReader) {
        try {
            val tex = SurfaceTexture(0).also { it.setDefaultBufferSize(640, 480) }
            preview = tex
            val ps = Surface(tex)
            c.createCaptureSession(listOf(ps, rd.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        val prev = c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(ps)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        }
                        s.setRepeatingRequest(prev.build(), null, handler)
                        handler?.postDelayed({ capture(c, s, rd) }, 1000)   // let exposure and focus settle
                    } catch (e: Exception) {
                        finishWith(null)
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { finishWith(null) }
            }, handler)
        } catch (e: Exception) {
            finishWith(null)
        }
    }

    private fun capture(c: CameraDevice, s: CameraCaptureSession, rd: ImageReader) {
        try {
            val req = c.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(rd.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.JPEG_QUALITY, 85.toByte())
            }
            s.capture(req.build(), null, handler)
        } catch (e: Exception) {
            finishWith(null)
        }
    }

    /** upright, max 1024px, no EXIF: ready for a vision model */
    private fun encode(jpeg: ByteArray): String? = try {
        var bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        if (bmp == null) null else {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            val scale = minOf(1f, 1024f / maxOf(bmp.width, bmp.height))
            if (scale < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 70, bos)
            Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        }
    } catch (e: Exception) {
        null
    }

    @Synchronized
    private fun finishWith(b64: String?) {
        if (done) return
        done = true
        try { session?.close() } catch (_: Exception) {}
        try { camera?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { preview?.release() } catch (_: Exception) {}
        try { thread?.quitSafely() } catch (_: Exception) {}
        Bus.cameraResult?.invoke(b64)
        runOnUiThread {
            finish()
            overridePendingTransition(0, 0)
        }
    }
}

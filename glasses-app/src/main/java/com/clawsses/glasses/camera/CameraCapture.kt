package com.clawsses.glasses.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream

sealed class PhotoCaptureState {
    object Idle : PhotoCaptureState()
    object Capturing : PhotoCaptureState()
    data class Captured(val base64: String, val thumbnail: Bitmap) : PhotoCaptureState()
    data class Error(val message: String) : PhotoCaptureState()
}

class CameraCapture(private val context: Context) {

    companion object {
        private const val TAG = "CameraCapture"
        private const val MAX_WIDTH = 1280
        private const val MAX_HEIGHT = 960
        private const val JPEG_QUALITY = 75
        private const val THUMBNAIL_WIDTH = 80
        private const val THUMBNAIL_HEIGHT = 60
    }

    private val _state = MutableStateFlow<PhotoCaptureState>(PhotoCaptureState.Idle)
    val state: StateFlow<PhotoCaptureState> = _state.asStateFlow()

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    fun capture() {
        if (_state.value is PhotoCaptureState.Capturing) return

        _state.value = PhotoCaptureState.Capturing
        Log.d(TAG, "Starting photo capture")

        val thread = HandlerThread("camera-capture").also { it.start() }
        cameraThread = thread
        val handler = Handler(thread.looper)
        cameraHandler = handler

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val cameraId = findBackCamera(cameraManager)
        if (cameraId == null) {
            Log.e(TAG, "No back camera found")
            _state.value = PhotoCaptureState.Error("No camera found")
            cleanupThread()
            return
        }

        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)

        // Pick a size close to MAX_WIDTH x MAX_HEIGHT
        val captureSize = jpegSizes?.filter { it.width <= MAX_WIDTH && it.height <= MAX_HEIGHT }
            ?.maxByOrNull { it.width * it.height }
            ?: jpegSizes?.minByOrNull { it.width * it.height }

        val width = captureSize?.width ?: MAX_WIDTH
        val height = captureSize?.height ?: MAX_HEIGHT

        Log.d(TAG, "Capture size: ${width}x${height}")

        val imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()

                    processCapture(bytes)
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading image", e)
                    _state.value = PhotoCaptureState.Error("Failed to read image")
                } finally {
                    cleanupThread()
                }
            }
        }, handler)

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.d(TAG, "Camera opened")
                    try {
                        val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                        captureBuilder.addTarget(imageReader.surface)
                        captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

                        camera.createCaptureSession(
                            listOf(imageReader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    try {
                                        session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                                            override fun onCaptureCompleted(
                                                session: CameraCaptureSession,
                                                request: CaptureRequest,
                                                result: android.hardware.camera2.TotalCaptureResult
                                            ) {
                                                Log.d(TAG, "Capture completed")
                                                camera.close()
                                            }

                                            override fun onCaptureFailed(
                                                session: CameraCaptureSession,
                                                request: CaptureRequest,
                                                failure: android.hardware.camera2.CaptureFailure
                                            ) {
                                                Log.e(TAG, "Capture failed: reason=${failure.reason}")
                                                camera.close()
                                                _state.value = PhotoCaptureState.Error("Capture failed")
                                                cleanupThread()
                                            }
                                        }, handler)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error capturing", e)
                                        camera.close()
                                        _state.value = PhotoCaptureState.Error("Capture error")
                                        cleanupThread()
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    Log.e(TAG, "Session configure failed")
                                    camera.close()
                                    _state.value = PhotoCaptureState.Error("Camera configure failed")
                                    cleanupThread()
                                }
                            },
                            handler
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error setting up capture", e)
                        camera.close()
                        _state.value = PhotoCaptureState.Error("Setup error")
                        cleanupThread()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.d(TAG, "Camera disconnected")
                    camera.close()
                    if (_state.value is PhotoCaptureState.Capturing) {
                        _state.value = PhotoCaptureState.Error("Camera disconnected")
                    }
                    cleanupThread()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close()
                    _state.value = PhotoCaptureState.Error("Camera error: $error")
                    cleanupThread()
                }
            }, handler)
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission denied", e)
            _state.value = PhotoCaptureState.Error("Camera permission denied")
            cleanupThread()
        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera", e)
            _state.value = PhotoCaptureState.Error("Failed to open camera")
            cleanupThread()
        }
    }

    private fun processCapture(jpegBytes: ByteArray) {
        try {
            // Decode to check dimensions and potentially scale down
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)

            val needsResize = options.outWidth > MAX_WIDTH || options.outHeight > MAX_HEIGHT
            val finalBytes: ByteArray

            if (needsResize) {
                // Calculate sample size
                val sampleSize = maxOf(
                    options.outWidth / MAX_WIDTH,
                    options.outHeight / MAX_HEIGHT
                ).coerceAtLeast(1)

                val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, decodeOptions)
                    ?: throw Exception("Failed to decode image")

                // Scale to fit within bounds
                val scale = minOf(
                    MAX_WIDTH.toFloat() / bitmap.width,
                    MAX_HEIGHT.toFloat() / bitmap.height
                ).coerceAtMost(1f)
                val scaledBitmap = if (scale < 1f) {
                    Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                        .also { if (it !== bitmap) bitmap.recycle() }
                } else {
                    bitmap
                }

                val outputStream = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
                finalBytes = outputStream.toByteArray()
                scaledBitmap.recycle()
            } else {
                // Re-compress at target quality
                val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                    ?: throw Exception("Failed to decode image")
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
                finalBytes = outputStream.toByteArray()
                bitmap.recycle()
            }

            val base64 = Base64.encodeToString(finalBytes, Base64.NO_WRAP)

            // Create thumbnail
            val thumbBitmap = BitmapFactory.decodeByteArray(finalBytes, 0, finalBytes.size)
            val thumbnail = Bitmap.createScaledBitmap(thumbBitmap, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT, true)
            if (thumbnail !== thumbBitmap) thumbBitmap.recycle()

            Log.d(TAG, "Photo captured: ${finalBytes.size} bytes, base64 length=${base64.length}")
            _state.value = PhotoCaptureState.Captured(base64, thumbnail)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing capture", e)
            _state.value = PhotoCaptureState.Error("Processing error")
        }
    }

    fun clearPhoto() {
        val current = _state.value
        if (current is PhotoCaptureState.Captured) {
            current.thumbnail.recycle()
        }
        _state.value = PhotoCaptureState.Idle
    }

    fun cleanup() {
        clearPhoto()
        cleanupThread()
    }

    private fun cleanupThread() {
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    private fun findBackCamera(cameraManager: CameraManager): String? {
        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id
            }
        }
        // Fallback: use first available camera
        return cameraManager.cameraIdList.firstOrNull()
    }
}

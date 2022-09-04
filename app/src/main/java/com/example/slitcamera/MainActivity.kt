package com.example.slitcamera

import android.Manifest.permission.CAMERA
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.slitcamera.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions(arrayOf(CAMERA), REQUEST_CAMERA_PERMISSION)
    }

    override fun onResume() {
        super.onResume()
        window.decorView.apply {
            systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
        }
    }

    @SuppressLint("MissingPermission")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            REQUEST_CAMERA_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    openCamera(cameraManager, cameraManager.cameraIdList[0]) { cameraDevice, supportedSizes ->
                        setImageSize(supportedSizes) { dialog: AlertDialog, size: Size ->
                            try {
                                jniInitialize(size.width, size.height)
                                dialog.dismiss()
                                setupTextureView(size.width, size.height)
                                createNewCaptureSession(cameraDevice, listOf(createMyImageReader(size.width, size.height).surface))
                            } catch (e: Throwable) {
                                AlertDialog.Builder(this@MainActivity)
                                        .setPositiveButton("BACK", null)
                                        .setMessage(e.message)
                                        .create()
                                        .apply {
                                            window!!.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                                        }
                                        .show()
                            }
                        }
                    }
                    setContentView(binding.root)
                } else {
                    finishAndRemoveTask()
                }
            }
            else -> super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        jniFinalize()
    }

    private fun setImageSize(supportedSizes: List<Size>, onSizeDetermined: (AlertDialog, Size) -> Unit) {
        AlertDialog
                .Builder(this)
                .setTitle("Resolution")
                .setItems(supportedSizes.map { it.toString() }.toTypedArray(), null)
                .setCancelable(false)
                .create()
                .apply {
                    window!!.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                    listView.setOnItemClickListener { _, _, position, _ ->
                        onSizeDetermined(this, supportedSizes[position])
                    }
                }
                .show()
    }

    private fun setupTextureView(imageWidth: Int, imageHeight: Int) {
        fun setBufferSize(imageWidth: Int, imageHeight: Int) {
            binding.textureView.apply {
                val magnification = when (resources.configuration.orientation) {
                    Configuration.ORIENTATION_LANDSCAPE ->
                        (imageWidth / width.toDouble()).coerceAtLeast(imageHeight / height.toDouble())
                    else ->
                        (imageWidth / height.toDouble()).coerceAtLeast(imageHeight / width.toDouble())
                }
                surfaceTexture.setDefaultBufferSize(
                        kotlin.math.ceil(width * magnification).toInt(),
                        kotlin.math.ceil(height * magnification).toInt()
                )
            }
        }
        binding.textureView.apply {
            if (isAvailable) {
                setBufferSize(imageWidth, imageHeight)
            }
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) {
                    setBufferSize(imageWidth, imageHeight)
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture?, width: Int, height: Int) {
                    setBufferSize(imageWidth, imageHeight)
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture?) {}

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?): Boolean = false
            }
        }
    }

    private fun createMyImageReader(width: Int, height: Int) =
            ImageReader
                    .newInstance(width, height, ImageFormat.YUV_420_888, 2)
                    .apply {
                        setOnImageAvailableListener(
                                { reader ->
                                    reader?.acquireLatestImage().use {
                                        it ?: return@use
                                        jniSlitScan(it, binding.textureView.surfaceTexture, resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                                    }
                                },
                                Handler(HandlerThread("ImageProcessingThread").apply { start() }.looper)
                        )
                    }

    private external fun jniInitialize(width: Int, height: Int)
    private external fun jniSlitScan(image: Image, surfaceTexture: SurfaceTexture, isLandscape: Boolean)
    private external fun jniFinalize()

    companion object {
        private const val REQUEST_CAMERA_PERMISSION = 200

        init {
            System.loadLibrary("native-lib")
        }
    }
}

@RequiresPermission(CAMERA)
fun openCamera(cameraManager: CameraManager, cameraId: String, onOpened: (CameraDevice, List<Size>) -> Unit) {
    val supportedSizes = cameraManager
            .getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(ImageReader::class.java)
            .sortedWith(compareBy<Size> { it.width }.thenBy { it.height })
    cameraManager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    onOpened(camera, supportedSizes)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                }
            },
            null
    )
}

fun createNewCaptureSession(cameraDevice: CameraDevice, surfaces: List<Surface>) {
    cameraDevice.createCaptureSession(
            SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    surfaces.map { OutputConfiguration(it) },
                    Executors.newSingleThreadExecutor(),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            session.setRepeatingRequest(
                                    cameraDevice
                                            .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                            .apply {
                                                surfaces.forEach { addTarget(it) }
                                            }
                                            .build(),
                                    null,
                                    null
                            )
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {}
                    }
            )
    )
}
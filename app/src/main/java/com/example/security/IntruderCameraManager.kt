package com.example.security

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.data.local.SecurityEvidenceDatabase
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class IntruderCameraManager(private val context: Context) {
  private val appContext = context.applicationContext

  fun hasCameraPermission(): Boolean {
    return ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) ==
      PackageManager.PERMISSION_GRANTED
  }

  suspend fun captureEvidencePhoto(lifecycleOwner: LifecycleOwner? = null): Result<File> =
    runCatching {
      require(hasCameraPermission()) { "Camera permission is not granted." }

      // CameraX works best with a visible ComponentActivity; background Services go directly to headless Camera2
      if (lifecycleOwner is ComponentActivity) {
        val cameraXAttempt = runCatching { captureWithCameraX(lifecycleOwner) }
        if (cameraXAttempt.isSuccess) {
          return@runCatching cameraXAttempt.getOrThrow()
        }
        Log.w(
          TAG,
          "CameraX capture failed (${cameraXAttempt.exceptionOrNull()?.message}); falling back to headless Camera2",
        )
      }

      val firstTry = runCatching { captureWithCamera2() }
      if (firstTry.isSuccess) return@runCatching firstTry.getOrThrow()

      val msg = firstTry.exceptionOrNull()?.message.orEmpty()
      if (msg.contains("in use", ignoreCase = true) || msg.contains("error 1", ignoreCase = true) || msg.contains("error 2", ignoreCase = true)) {
        delay(450L)
        return@runCatching captureWithCamera2()
      }
      throw firstTry.exceptionOrNull()!!
    }

  private suspend fun captureWithCameraX(lifecycleOwner: LifecycleOwner): File =
    withTimeout(10_000L) {
      withContext(Dispatchers.Main.immediate) {
        val providerFuture = ProcessCameraProvider.getInstance(appContext)
        val cameraProvider =
          suspendCancellableCoroutine<ProcessCameraProvider> { cont ->
            providerFuture.addListener(
              {
                runCatching { providerFuture.get() }
                  .onSuccess { if (cont.isActive) cont.resume(it) }
                  .onFailure {
                    if (cont.isActive) cont.cancel(it)
                  }
              },
              ContextCompat.getMainExecutor(appContext),
            )
          }

        val selector =
          when {
            cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ->
              CameraSelector.DEFAULT_FRONT_CAMERA
            cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ->
              CameraSelector.DEFAULT_BACK_CAMERA
            else -> throw IllegalStateException("No camera hardware available on this device.")
          }

        val imageCapture =
          ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        val dir = SecurityEvidenceDatabase.evidenceDirectory(appContext)
        val outputFile = File(dir, "intruder_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

        try {
          cameraProvider.unbindAll()
          cameraProvider.bindToLifecycle(lifecycleOwner, selector, imageCapture)
          delay(350L)

          suspendCancellableCoroutine { cont ->
            imageCapture.takePicture(
              outputOptions,
              ContextCompat.getMainExecutor(appContext),
              object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                  if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(exception: ImageCaptureException) {
                  if (cont.isActive) cont.cancel(exception)
                }
              },
            )
          }

          withContext(Dispatchers.IO) {
            saveCopyToPublicGallery(outputFile)
          }
          outputFile
        } finally {
          runCatching { cameraProvider.unbindAll() }
          delay(120L)
        }
      }
    }

  @SuppressLint("MissingPermission")
  private suspend fun captureWithCamera2(): File {
    val twoSurfaceAttempt = runCatching { captureWithCamera2Internal(usePreviewStream = true) }
    if (twoSurfaceAttempt.isSuccess) return twoSurfaceAttempt.getOrThrow()

    val errMsg = twoSurfaceAttempt.exceptionOrNull()?.message.orEmpty()
    // Only retry single JPEG surface on session configuration failures (not permission/lockscreen denial)
    if (
      errMsg.contains("configuration", ignoreCase = true) ||
        errMsg.contains("capture failed", ignoreCase = true) ||
        errMsg.contains("timeout", ignoreCase = true) ||
        errMsg.contains("timed out", ignoreCase = true)
    ) {
      Log.w(TAG, "Camera2 2-surface session failed ($errMsg); retrying single JPEG surface")
      return captureWithCamera2Internal(usePreviewStream = false)
    }
    throw twoSurfaceAttempt.exceptionOrNull()!!
  }

  @SuppressLint("MissingPermission")
  private suspend fun captureWithCamera2Internal(usePreviewStream: Boolean): File = withTimeout(7_000L) {
    val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val cameraId =
      findPreferredCameraId(cameraManager)
        ?: throw IllegalStateException("No camera hardware available on this device.")

    val characteristics = cameraManager.getCameraCharacteristics(cameraId)
    val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val jpegSizes = configMap?.getOutputSizes(ImageFormat.JPEG).orEmpty()
    val targetSize =
      jpegSizes
        .filter { it.width in 640..1600 && it.height in 480..1200 }
        .maxByOrNull { it.width * it.height }
        ?: jpegSizes.minByOrNull { it.width * it.height }
        ?: Size(1280, 720)
    val yuvSizes = configMap?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
    val previewSize =
      yuvSizes.firstOrNull { it.width == 640 && it.height == 480 }
        ?: yuvSizes.filter { it.width in 320..1280 && it.height in 240..960 }.minByOrNull { it.width * it.height }
        ?: yuvSizes.minByOrNull { it.width * it.height }
        ?: Size(640, 480)

    val handlerThread = HandlerThread("TerraGuardCam2").apply { start() }
    val handler = Handler(handlerThread.looper)
    val imageReader = ImageReader.newInstance(targetSize.width, targetSize.height, ImageFormat.JPEG, 2)
    val previewReader =
      if (usePreviewStream) {
        ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 2).apply {
          setOnImageAvailableListener(
            { reader -> runCatching { reader.acquireLatestImage()?.close() } },
            handler,
          )
        }
      } else {
        null
      }

    val supportedAfModes =
      characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet() ?: emptySet()
    val supportsContinuousAf =
      CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in supportedAfModes

    var cameraDevice: CameraDevice? = null
    var captureSession: CameraCaptureSession? = null
    val closedSignal = kotlinx.coroutines.CompletableDeferred<Unit>()

    try {
      val dir = SecurityEvidenceDatabase.evidenceDirectory(appContext)
      val outputFile = File(dir, "intruder_${System.currentTimeMillis()}.jpg")

      val jpegBytes =
        suspendCancellableCoroutine<ByteArray> { cont ->
          var delivered = false
          imageReader.setOnImageAvailableListener(
            { reader ->
              val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
              try {
                if (!delivered && cont.isActive) {
                  delivered = true
                  val buffer = image.planes[0].buffer
                  val bytes = ByteArray(buffer.remaining())
                  buffer.get(bytes)
                  cont.resume(bytes)
                }
              } finally {
                image.close()
              }
            },
            handler,
          )

          cameraManager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
              override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                val surfaces =
                  if (previewReader != null) listOf(previewReader.surface, imageReader.surface)
                  else listOf(imageReader.surface)

                val sessionCallback =
                  object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                      captureSession = session
                      if (previewReader != null) {
                        runCatching {
                          val previewBuilder =
                            camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                              addTarget(previewReader.surface)
                              set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                              set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                            }
                          session.setRepeatingRequest(previewBuilder.build(), null, handler)
                        }
                      }

                      // Allow 350ms AE/AWB warm-up when preview stream active, then snap JPEG still
                      handler.postDelayed(
                        {
                          runCatching {
                            val stillBuilder =
                              camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                addTarget(imageReader.surface)
                                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                                if (supportsContinuousAf) {
                                  set(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                                  )
                                }
                                val sensorOrientation =
                                  characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                                set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                              }
                            session.capture(
                              stillBuilder.build(),
                              object : CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureFailed(
                                  session: CameraCaptureSession,
                                  request: CaptureRequest,
                                  failure: CaptureFailure,
                                ) {
                                  if (!delivered && cont.isActive) {
                                    delivered = true
                                    cont.resumeWithException(
                                      IllegalStateException("Camera2 capture failed (reason=${failure.reason})")
                                    )
                                  }
                                }
                              },
                              handler,
                            )
                          }.onFailure { err ->
                            if (!delivered && cont.isActive) {
                              delivered = true
                              cont.resumeWithException(err)
                            }
                          }
                        },
                        if (previewReader != null) 350L else 100L,
                      )
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                      if (!delivered && cont.isActive) {
                        delivered = true
                        cont.resumeWithException(
                          IllegalStateException("Camera2 session configuration failed")
                        )
                      }
                    }
                  }

                runCatching {
                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val configs = surfaces.map { OutputConfiguration(it) }
                    val executor = Executor { cmd -> handler.post(cmd) }
                    camera.createCaptureSession(
                      SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        configs,
                        executor,
                        sessionCallback,
                      )
                    )
                  } else {
                    @Suppress("DEPRECATION")
                    camera.createCaptureSession(surfaces, sessionCallback, handler)
                  }
                }.onFailure { err ->
                  if (!delivered && cont.isActive) {
                    delivered = true
                    cont.resumeWithException(err)
                  }
                }
              }

              override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                if (!delivered && cont.isActive) {
                  delivered = true
                  cont.resumeWithException(IllegalStateException("Camera disconnected"))
                }
              }

              override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                val label =
                  when (error) {
                    CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "CAMERA_IN_USE"
                    CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "MAX_CAMERAS_IN_USE"
                    CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "CAMERA_DISABLED"
                    CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "CAMERA_DEVICE"
                    CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "CAMERA_SERVICE"
                    else -> "UNKNOWN"
                  }
                if (!delivered && cont.isActive) {
                  delivered = true
                  cont.resumeWithException(
                    IllegalStateException("Camera2 error $error ($label)")
                  )
                }
              }

              override fun onClosed(camera: CameraDevice) {
                closedSignal.complete(Unit)
              }
            },
            handler,
          )
        }

      withContext(Dispatchers.IO) {
        outputFile.writeBytes(jpegBytes)
        saveCopyToPublicGallery(outputFile)
      }
      outputFile
    } finally {
      runCatching { captureSession?.stopRepeating() }
      runCatching { captureSession?.close() }
      val dev = cameraDevice
      if (dev != null) {
        runCatching { dev.close() }
        kotlinx.coroutines.withTimeoutOrNull(500L) { closedSignal.await() }
      }
      runCatching { imageReader.close() }
      runCatching { previewReader?.close() }
      runCatching { handlerThread.quitSafely() }
    }
  }

  private fun findPreferredCameraId(cameraManager: CameraManager): String? {
    val ids = cameraManager.cameraIdList
    val front =
      ids.firstOrNull { id ->
        val facing =
          cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
        facing == CameraCharacteristics.LENS_FACING_FRONT
      }
    if (front != null) return front
    return ids.firstOrNull()
  }

  private fun saveCopyToPublicGallery(file: File) {
    if (!file.exists()) return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    runCatching {
      val values =
        ContentValues().apply {
          put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
          put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
          put(
            MediaStore.Images.Media.RELATIVE_PATH,
            "${Environment.DIRECTORY_PICTURES}/TerraGuard",
          )
          put(MediaStore.Images.Media.IS_PENDING, 1)
        }
      val resolver = appContext.contentResolver
      val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
      resolver.openOutputStream(uri)?.use { out ->
        file.inputStream().use { input -> input.copyTo(out) }
      }
      values.clear()
      values.put(MediaStore.Images.Media.IS_PENDING, 0)
      resolver.update(uri, values, null, null)
    }
  }

  companion object {
    private const val TAG = "IntruderCameraMgr"
  }
}

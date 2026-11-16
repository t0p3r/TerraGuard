package com.example.security

import android.app.KeyguardManager
import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.LifecycleOwner
import com.example.data.local.PendingAlertEntity
import com.example.data.local.SecurityEvidenceDatabase
import com.example.data.local.SecurityPhotoEntity
import com.example.data.local.TerraGuardPreferences
import com.example.data.model.AlertType
import com.example.data.model.EmailStatus
import com.example.data.model.LocationRecord
import com.example.data.model.SecurityAlert
import com.example.data.repository.TerraGuardRepository
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class IntruderCaptureOutcome(
  val photoCaptured: Boolean,
  val capturedFile: File?,
  val emailStatus: String,
  val alertLogged: Boolean,
)

object IntruderPipeline {
  private const val TAG = "IntruderPipeline"
  val captureMutex = Mutex()
  @Volatile private var lastPhotoSuccessMillis: Long = 0L

  suspend fun execute(
    context: Context,
    lifecycleOwner: LifecycleOwner?,
    reason: String,
    attemptCount: Int,
    fromUnlock: Boolean,
  ): IntruderCaptureOutcome {
    val appContext = context.applicationContext
    val prefs = TerraGuardPreferences(appContext)
    val telemetry = DeviceTelemetryManager(appContext)
    val locationManager = LocationAndGeofenceManager(appContext)
    val cameraManager = IntruderCameraManager(appContext)
    val dao = SecurityEvidenceDatabase.getInstance(appContext).evidenceDao()

    data class LocalCaptureResult(
      val skip: Boolean = false,
      val skipEmailStatus: String = EmailStatus.QUEUED,
      val settings: com.example.data.local.TerraGuardSettingsState? = null,
      val deviceId: String = "",
      val now: Long = 0L,
      val batteryPercent: Int = 100,
      val isOnline: Boolean = false,
      val freshFix: GpsFix? = null,
      val lat: Double = 0.0,
      val lng: Double = 0.0,
      val acc: Double = 0.0,
      val capturedFile: File? = null,
      val hasPhoto: Boolean = false,
      val photoErr: String = "",
    )

    // Hold hardware mutex ONLY for fast GPS + Camera + Local Vault DB steps (never across 20s network/SMTP calls)
    val local =
      captureMutex.withLock {
        val settings = prefs.settingsFlow.first()
        if (fromUnlock && !settings.pendingIntruderCapture) {
          return@withLock LocalCaptureResult(skip = true, skipEmailStatus = EmailStatus.QUEUED)
        }

        val now = System.currentTimeMillis()
        if (fromUnlock && (now - lastPhotoSuccessMillis) < 5_000L) {
          prefs.setPendingIntruderCapture(false)
          prefs.resetOsFailedPinCount()
          return@withLock LocalCaptureResult(skip = true, skipEmailStatus = EmailStatus.DISPATCHED)
        }

        val deviceId = prefs.getOrCreateDeviceId()
        val battery = telemetry.readBatterySnapshot()
        val net = telemetry.readCurrentNetworkSnapshot()

        val keyguard = appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (fromUnlock && lifecycleOwner !is ComponentActivity && keyguard?.isKeyguardLocked == true) {
          // ACTION_PASSWORD_SUCCEEDED often fires before keyguard dismissal completes (~300-700ms).
          repeat(8) {
            delay(150L)
            if (keyguard.isKeyguardLocked != true) return@repeat
          }
        }

        val freshFix = runCatching { locationManager.captureCurrentLocation(timeoutMillis = 1_800L) }.getOrNull()
        if (freshFix != null) {
          prefs.saveLastKnownLocation(
            latitude = freshFix.latitude,
            longitude = freshFix.longitude,
            accuracyMeters = freshFix.accuracyMeters,
            batteryPercent = battery.percent,
            provider = freshFix.provider,
            recordedAtMillis = freshFix.timestampMillis,
            savedBeforeOffline = !net.isOnline,
          )
        }

        val lat = freshFix?.latitude ?: settings.lastKnownLocation.latitude
        val lng = freshFix?.longitude ?: settings.lastKnownLocation.longitude
        val acc = freshFix?.accuracyMeters ?: settings.lastKnownLocation.accuracyMeters

        val isLocked = keyguard?.isKeyguardLocked == true
        val photoResult =
          if (cameraManager.hasCameraPermission()) {
            cameraManager.captureEvidencePhoto(lifecycleOwner)
          } else {
            Result.failure(IllegalStateException("Camera permission not granted"))
          }

        val capturedFile = photoResult.getOrNull()
        val hasPhoto = capturedFile != null && capturedFile.exists()

        if (hasPhoto && capturedFile != null) {
          lastPhotoSuccessMillis = System.currentTimeMillis()
          prefs.setPendingIntruderCapture(false)
          prefs.resetOsFailedPinCount()
          dao.insertPhoto(
            SecurityPhotoEntity(
              filePath = capturedFile.absolutePath,
              reason = reason,
              attemptCount = attemptCount,
              latitude = lat,
              longitude = lng,
              accuracyMeters = acc,
              batteryPercent = battery.percent,
              capturedAtMillis = now,
            )
          )
        } else {
          if (cameraManager.hasCameraPermission() && (isLocked || lifecycleOwner !is ComponentActivity)) {
            prefs.setPendingIntruderCapture(true)
          } else {
            prefs.setPendingIntruderCapture(false)
            prefs.resetOsFailedPinCount()
          }
          Log.w(TAG, "Camera capture deferred/failed: ${photoResult.exceptionOrNull()?.message}")
        }

        LocalCaptureResult(
          skip = false,
          settings = settings,
          deviceId = deviceId,
          now = now,
          batteryPercent = battery.percent,
          isOnline = net.isOnline,
          freshFix = freshFix,
          lat = lat,
          lng = lng,
          acc = acc,
          capturedFile = capturedFile,
          hasPhoto = hasPhoto,
          photoErr = photoResult.exceptionOrNull()?.message ?: "locked",
        )
      }

    if (local.skip || local.settings == null) {
      return IntruderCaptureOutcome(
        photoCaptured = false,
        capturedFile = null,
        emailStatus = local.skipEmailStatus,
        alertLogged = false,
      )
    }

    val settings = local.settings
    val now = local.now
    val lat = local.lat
    val lng = local.lng
    val acc = local.acc
    val hasPhoto = local.hasPhoto
    val capturedFile = local.capturedFile
    val photoErr = local.photoErr
    val timeText = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))

    // Avoid spamming duplicate photo-less alerts/emails when background unlock capture is blocked by OEM policy.
    if (fromUnlock && !hasPhoto) {
      val deferredStatus = "$timeText: Unlock camera deferred ($photoErr) — tap notification or open TerraGuard"
      runCatching { prefs.setLastIntruderStatus(deferredStatus) }
      TerraGuardNotifications.postAlertNotification(
        appContext,
        7203,
        "Intruder Detected — Tap to Capture Photo",
        "Android blocked background camera on unlock ($photoErr). Tap to open TerraGuard and save photo.",
      )
      return IntruderCaptureOutcome(
        photoCaptured = false,
        capturedFile = null,
        emailStatus = EmailStatus.QUEUED,
        alertLogged = false,
      )
    }

    // 3. Log alert to Firestore (or offline Room queue) without holding camera mutex
    val user = runCatching { Firebase.auth.currentUser }.getOrNull()
    val ownerEmail = settings.ownerAlertEmail.ifBlank { user?.email.orEmpty() }
    val alertId = UUID.randomUUID().toString()
    val coordsText = String.format(Locale.US, "%.5f, %.5f", lat, lng)
    val alertMessage =
      if (hasPhoto) {
        "$reason. Intruder photo saved to Security Vault & Gallery with GPS ($coordsText)."
      } else {
        "$reason. GPS ($coordsText) recorded. Photo queued for unlock/app open ($photoErr)."
      }

    val alert =
      SecurityAlert(
        id = alertId,
        userId = user?.uid.orEmpty(),
        deviceId = local.deviceId,
        deviceName = settings.deviceName,
        type = AlertType.INTRUDER_PIN,
        title = "Lockscreen Intruder Alert ($attemptCount attempts)",
        message = alertMessage,
        latitude = lat,
        longitude = lng,
        accuracyMeters = acc,
        batteryPercent = local.batteryPercent.toLong(),
        emailStatus = if (ownerEmail.isBlank()) EmailStatus.NOT_CONFIGURED else EmailStatus.QUEUED,
        hasPhotoEvidence = hasPhoto,
        occurredAtMillis = now,
      )

    var loggedToCloud = false
    var repo: TerraGuardRepository? = null
    if (local.isOnline && user != null) {
      repo = TerraGuardRepository(appContext)
      val freshFix = local.freshFix
      if (freshFix != null) {
        runCatching {
          repo.recordLocation(
            local.deviceId,
            LocationRecord(
              userId = user.uid,
              deviceId = local.deviceId,
              latitude = freshFix.latitude,
              longitude = freshFix.longitude,
              accuracyMeters = freshFix.accuracyMeters,
              batteryPercent = local.batteryPercent.toLong(),
              isOnline = true,
              provider = freshFix.provider,
              sourceEvent = "INTRUDER",
              recordedAtMillis = freshFix.timestampMillis,
            ),
          )
        }
      }
      loggedToCloud = runCatching { repo.logAlert(alert).isSuccess }.getOrDefault(false)
    } else {
      runCatching {
        dao.insertPendingAlert(
          PendingAlertEntity(
            id = alertId,
            type = AlertType.INTRUDER_PIN,
            title = alert.title,
            message = alertMessage,
            latitude = lat,
            longitude = lng,
            accuracyMeters = acc,
            batteryPercent = local.batteryPercent,
            hasPhotoEvidence = hasPhoto,
            occurredAtMillis = now,
          )
        )
      }
    }

    // 4. Send automated email immediately (with photo attachment when captured)
    var finalEmailStatus = alert.emailStatus
    var emailDetail = if (ownerEmail.isBlank()) "No owner email" else "Offline"
    if (local.isOnline && ownerEmail.isNotBlank()) {
      val report =
        runCatching {
          EmailAlertDispatcher().dispatchAutomatedEmailDetailed(
            settings = settings,
            ownerEmail = ownerEmail,
            alert = alert,
            attachmentFile = capturedFile,
          )
        }.getOrElse { EmailAlertDispatcher.EmailDispatchReport(EmailStatus.QUEUED, it.message ?: "Error") }
      finalEmailStatus = report.status
      emailDetail = report.detail
      if (loggedToCloud && finalEmailStatus != EmailStatus.QUEUED) {
        runCatching { repo?.updateAlertEmailStatus(alertId, finalEmailStatus) }
      }
    }

    val emailNote =
      if (finalEmailStatus == EmailStatus.DISPATCHED) "DISPATCHED"
      else "$finalEmailStatus ($emailDetail)"
    val statusSummary =
      if (hasPhoto) {
        "$timeText: Captured photo -> Vault & Gallery • Email: $emailNote"
      } else {
        "$timeText: Photo queued ($photoErr) • Email: $emailNote"
      }
    runCatching { prefs.setLastIntruderStatus(statusSummary) }

    TerraGuardNotifications.postAlertNotification(
      appContext,
      7203,
      if (hasPhoto) "Intruder Photo & GPS Captured" else "Intruder Detected — Tap to Capture Photo",
      "$statusSummary • Battery ${local.batteryPercent}%.",
    )

    return IntruderCaptureOutcome(
      photoCaptured = hasPhoto,
      capturedFile = capturedFile,
      emailStatus = finalEmailStatus,
      alertLogged = loggedToCloud,
    )
  }
}

package com.example.receiver

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import com.example.data.local.TerraGuardPreferences
import com.example.security.IntruderPipeline
import com.example.security.TerraGuardNotifications
import com.example.service.TerraGuardIntruderService
import com.example.service.TerraGuardMonitoringService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object LockscreenIntruderTracker {
  private val eventMutex = Mutex()
  @Volatile var lastObservedDpmCount: Int = 0
    private set
  @Volatile private var lastEventElapsedMs: Long = 0L

  fun readOsFailedAttempts(context: Context): Int {
    val appContext = context.applicationContext
    val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    val admin = ComponentName(appContext, TerraGuardDeviceAdminReceiver::class.java)
    if (dpm?.isAdminActive(admin) != true) return 0
    return runCatching { dpm.currentFailedPasswordAttempts }.getOrDefault(0)
  }

  suspend fun onWrongPinObserved(context: Context, fromBroadcast: Boolean) {
    val appContext = context.applicationContext
    val triggerWork: Triple<Boolean, String, Int> =
      eventMutex.withLock {
        val dpmCount = readOsFailedAttempts(appContext)
        val now = android.os.SystemClock.elapsedRealtime()

        // Avoid double-counting when both ACTION_PASSWORD_FAILED and the service keyguard watcher see the same event.
        if (!fromBroadcast) {
          if (dpmCount <= 0 || dpmCount <= lastObservedDpmCount) {
            return@withLock Triple(false, "", 0)
          }
        } else {
          if (dpmCount > 0 && dpmCount <= lastObservedDpmCount && (now - lastEventElapsedMs) < 1_500L) {
            return@withLock Triple(false, "", 0)
          }
        }

        lastObservedDpmCount = maxOf(lastObservedDpmCount, dpmCount)
        lastEventElapsedMs = now

        val prefs = TerraGuardPreferences(appContext)
        val settings = prefs.settingsFlow.first()
        if (!settings.isProtected) {
          prefs.setLastIntruderStatus("${timeNow()}: Wrong lockscreen PIN ignored (Shield is Paused)")
          return@withLock Triple(false, "", 0)
        }

        val nextCount = maxOf(prefs.incrementOsFailedPinCount(), dpmCount)
        val sourceTag = if (fromBroadcast) "OS broadcast" else "OS lock monitor"
        prefs.setLastIntruderStatus(
          "${timeNow()}: Lockscreen Wrong PIN #$nextCount/${settings.failedPinThreshold} ($sourceTag)"
        )

        if (nextCount >= settings.failedPinThreshold) {
          prefs.setPendingIntruderCapture(true)
          LockscreenFailureBus.emit(nextCount)
          val reason = "Android Lockscreen Wrong PIN ($nextCount failed attempts)"
          Triple(true, reason, nextCount)
        } else {
          Triple(false, "", nextCount)
        }
      }

    if (!triggerWork.first) return
    val reason = triggerWork.second
    val count = triggerWork.third

    TerraGuardNotifications.postAlertNotification(
      appContext,
      7203,
      "Lockscreen Intruder #$count Detected",
      "Capturing camera evidence, GPS coordinates, and email alert...",
    )

    val dispatchedToMonitor =
      TerraGuardMonitoringService.triggerIntruderCapture(
        context = appContext,
        reason = reason,
        attemptCount = count,
        fromUnlock = false,
      )

    if (!dispatchedToMonitor) {
      val startedShortService =
        TerraGuardIntruderService.start(
          context = appContext,
          reason = reason,
          attemptCount = count,
          fromUnlock = false,
        )
      if (!startedShortService) {
        IntruderPipeline.execute(
          context = appContext,
          lifecycleOwner = null,
          reason = reason,
          attemptCount = count,
          fromUnlock = false,
        )
      }
    }
  }

  suspend fun onPasswordSucceeded(context: Context) {
    val appContext = context.applicationContext
    resetObservedCount()

    val prefs = TerraGuardPreferences(appContext)
    val settings = prefs.settingsFlow.first()
    val failedCount = settings.osFailedPinCount.coerceAtLeast(1)
    if (!settings.pendingIntruderCapture || !settings.isProtected) {
      prefs.resetOsFailedPinCount()
      return
    }

    prefs.setLastIntruderStatus(
      "${timeNow()}: Unlocked after $failedCount wrong PIN(s) — capturing..."
    )
    val reason = "Unlock after Failed Lockscreen PIN ($failedCount attempts)"
    val sentToMonitor =
      TerraGuardMonitoringService.triggerIntruderCapture(
        context = appContext,
        reason = reason,
        attemptCount = failedCount,
        fromUnlock = true,
      )
    if (!sentToMonitor) {
      val startedShort =
        TerraGuardIntruderService.start(
          context = appContext,
          reason = reason,
          attemptCount = failedCount,
          fromUnlock = true,
        )
      if (!startedShort) {
        IntruderPipeline.execute(
          context = appContext,
          lifecycleOwner = null,
          reason = reason,
          attemptCount = failedCount,
          fromUnlock = true,
        )
      }
    }
  }

  fun resetObservedCount() {
    lastObservedDpmCount = 0
    lastEventElapsedMs = 0L
  }

  private fun timeNow(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
}

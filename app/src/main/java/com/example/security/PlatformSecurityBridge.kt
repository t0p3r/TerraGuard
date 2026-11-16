package com.example.security

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.example.data.local.TerraGuardSettingsState
import com.example.data.model.SecurityAlert
import com.example.receiver.TerraGuardDeviceAdminReceiver
import com.example.service.TerraGuardAlarmService
import com.example.service.TerraGuardIntruderService
import com.example.service.TerraGuardMonitoringService
import java.io.File
import kotlinx.coroutines.flow.StateFlow

class PlatformSecurityBridge(context: Context) {
  private val appContext = context.applicationContext
  private val emailDispatcher = EmailAlertDispatcher()

  val isAlarmRinging: StateFlow<Boolean> = AlarmController.isAlarmRinging

  fun startMonitoringService() {
    TerraGuardMonitoringService.start(appContext)
  }

  fun stopMonitoringService() {
    TerraGuardMonitoringService.stop(appContext)
  }

  fun startAlarm(overrideSilent: Boolean) {
    TerraGuardAlarmService.start(appContext, overrideSilent)
  }

  fun stopAlarm() {
    TerraGuardAlarmService.stop(appContext)
  }

  fun startIntruderCaptureService(
    reason: String,
    attemptCount: Int,
    fromUnlock: Boolean = false,
  ): Boolean {
    return TerraGuardIntruderService.start(appContext, reason, attemptCount, fromUnlock)
  }

  fun isNotificationPolicyAccessGranted(): Boolean {
    return AlarmController.isNotificationPolicyAccessGranted(appContext)
  }

  fun openNotificationPolicySettings() {
    val intent =
      Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    runCatching { appContext.startActivity(intent) }
  }

  fun openAppDetailsSettings() {
    val intent =
      Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", appContext.packageName, null),
      ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    runCatching { appContext.startActivity(intent) }
  }

  fun requestDeviceAdminActivation(context: Context? = null) {
    val targetContext = context ?: appContext
    val componentName = ComponentName(appContext, TerraGuardDeviceAdminReceiver::class.java)
    val intent =
      Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
        putExtra(
          DevicePolicyManager.EXTRA_ADD_EXPLANATION,
          "Allow TerraGuard to detect repeated failed lockscreen PIN attempts.",
        )
        if (targetContext !is Activity) {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
      }
    val launched = runCatching { targetContext.startActivity(intent); true }.getOrDefault(false)
    if (!launched) {
      val fallbackIntent =
        Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
      runCatching { appContext.startActivity(fallbackIntent) }
    }
  }

  fun openMapsLocation(latitude: Double, longitude: Double, label: String): Boolean {
    val uri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude(${Uri.encode(label)})")
    val intent =
      Intent(Intent.ACTION_VIEW, uri).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    val launched =
      runCatching {
        appContext.startActivity(intent)
        true
      }.getOrDefault(false)
    if (launched) return true

    val webIntent =
      Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://maps.google.com/?q=$latitude,$longitude"),
      ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    return runCatching {
      appContext.startActivity(webIntent)
      true
    }.getOrDefault(false)
  }

  fun postNotification(id: Int, title: String, body: String) {
    TerraGuardNotifications.postAlertNotification(appContext, id, title, body)
  }

  suspend fun dispatchAutomatedEmail(
    settings: TerraGuardSettingsState,
    ownerEmail: String,
    alert: SecurityAlert,
    attachmentFile: File? = null,
  ): String {
    return emailDispatcher.dispatchAutomatedEmail(settings, ownerEmail, alert, attachmentFile)
  }

  suspend fun dispatchAutomatedEmailDetailed(
    settings: TerraGuardSettingsState,
    ownerEmail: String,
    alert: SecurityAlert,
    attachmentFile: File? = null,
  ): EmailAlertDispatcher.EmailDispatchReport {
    return emailDispatcher.dispatchAutomatedEmailDetailed(settings, ownerEmail, alert, attachmentFile)
  }

  suspend fun sendTestEmail(
    settings: TerraGuardSettingsState,
    toEmail: String,
  ): Result<Unit> {
    return emailDispatcher.sendTestSmtpEmail(settings, toEmail)
  }

  fun launchEmailComposer(ownerEmail: String, alert: SecurityAlert): Boolean {
    return emailDispatcher.launchEmailApp(appContext, ownerEmail, alert)
  }

  fun shareEvidenceFile(file: File, ownerEmail: String, summary: String): Boolean {
    return emailDispatcher.shareEvidencePhoto(appContext, file, ownerEmail, summary)
  }
}

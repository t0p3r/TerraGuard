package com.example.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.local.PendingAlertEntity
import com.example.data.local.SecurityEvidenceDatabase
import com.example.data.local.TerraGuardPreferences
import com.example.data.model.AlertType
import com.example.data.model.EmailStatus
import com.example.data.model.SecurityAlert
import com.example.data.repository.TerraGuardRepository
import com.example.security.DeviceTelemetryManager
import com.example.security.EmailAlertDispatcher
import com.example.security.LocationAndGeofenceManager
import com.example.security.TerraGuardNotifications
import com.example.service.TerraGuardAlarmService
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SmsCommandReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent?) {
    if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
    val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
    if (messages.isNullOrEmpty()) return

    val sender = messages.firstOrNull()?.displayOriginatingAddress.orEmpty()
    val fullBody = messages.joinToString(separator = "") { it.messageBody.orEmpty() }.trim()
    if (fullBody.isBlank()) return

    val appContext = context.applicationContext
    val pending = goAsync()

    CoroutineScope(Dispatchers.IO).launch {
      try {
        val prefs = TerraGuardPreferences(appContext)
        val settings = prefs.settingsFlow.first()
        if (!settings.smsRemoteEnabled || !settings.isProtected) return@launch

        if (settings.smsTrustedSender.isNotBlank() && !senderMatches(sender, settings.smsTrustedSender)) {
          Log.d(TAG, "Ignoring SMS command from untrusted sender: $sender")
          return@launch
        }

        val normalizedBody = fullBody.uppercase(Locale.US)
        val alarmKeyword = settings.smsAlarmKeyword.trim().uppercase(Locale.US)
        val locateKeyword = settings.smsLocateKeyword.trim().uppercase(Locale.US)

        val isAlarmCmd = alarmKeyword.isNotBlank() && normalizedBody.contains(alarmKeyword)
        val isLocateCmd = locateKeyword.isNotBlank() && normalizedBody.contains(locateKeyword)
        if (!isAlarmCmd && !isLocateCmd) return@launch

        if (isAlarmCmd) {
          TerraGuardAlarmService.start(appContext, settings.alarmOverrideSilent)
        }

        val telemetry = DeviceTelemetryManager(appContext)
        val battery = telemetry.readBatterySnapshot()
        val net = telemetry.readCurrentNetworkSnapshot()
        val locationManager = LocationAndGeofenceManager(appContext)
        val freshFix = runCatching { locationManager.captureCurrentLocation() }.getOrNull()

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
        val now = System.currentTimeMillis()
        val deviceId = prefs.getOrCreateDeviceId()
        val user = Firebase.auth.currentUser
        val ownerEmail = settings.ownerAlertEmail.ifBlank { user?.email.orEmpty() }

        val mapsLink =
          if (lat != 0.0 || lng != 0.0) "https://maps.google.com/?q=$lat,$lng"
          else "No GPS fix yet"

        if (sender.isNotBlank()) {
          sendSmsReplyIfPermitted(
            appContext,
            sender,
            buildString {
              append("TerraGuard (${settings.deviceName}): ")
              if (isAlarmCmd) append("LOUD ALARM ACTIVE (Silent Override ON). ")
              append("Battery ${battery.percent}%. GPS: $mapsLink")
            },
          )
        }

        val alertId = UUID.randomUUID().toString()
        val title =
          if (isAlarmCmd) "SMS Remote Alarm Triggered"
          else "SMS Remote Location Request"
        val message =
          buildString {
            append(if (isAlarmCmd) "Silent-override alarm started" else "Location requested")
            if (sender.isNotBlank()) append(" via SMS from $sender")
            append(". Coordinates: ${String.format(Locale.US, "%.5f, %.5f", lat, lng)}.")
          }

        TerraGuardNotifications.postAlertNotification(
          appContext,
          7250,
          title,
          "$message (${battery.percent}% battery)",
        )

        val alert =
          SecurityAlert(
            id = alertId,
            userId = user?.uid.orEmpty(),
            deviceId = deviceId,
            deviceName = settings.deviceName,
            type = AlertType.REMOTE_ALARM,
            title = title,
            message = message,
            latitude = lat,
            longitude = lng,
            accuracyMeters = acc,
            batteryPercent = battery.percent.toLong(),
            emailStatus = if (ownerEmail.isBlank()) EmailStatus.NOT_CONFIGURED else EmailStatus.QUEUED,
            hasPhotoEvidence = false,
            occurredAtMillis = now,
          )

        var loggedToCloud = false
        var repo: TerraGuardRepository? = null
        if (net.isOnline && user != null) {
          repo = TerraGuardRepository(appContext)
          if (isAlarmCmd) {
            repo.setRemoteAlarm(deviceId, active = true, overrideSilent = settings.alarmOverrideSilent)
          }
          loggedToCloud = repo.logAlert(alert).isSuccess
        } else {
          SecurityEvidenceDatabase.getInstance(appContext).evidenceDao().insertPendingAlert(
            PendingAlertEntity(
              id = alertId,
              type = AlertType.REMOTE_ALARM,
              title = title,
              message = message,
              latitude = lat,
              longitude = lng,
              accuracyMeters = acc,
              batteryPercent = battery.percent,
              hasPhotoEvidence = false,
              occurredAtMillis = now,
            )
          )
        }

        if (net.isOnline && ownerEmail.isNotBlank()) {
          val emailStatus =
            EmailAlertDispatcher().dispatchAutomatedEmail(
              settings = settings,
              ownerEmail = ownerEmail,
              alert = alert,
              attachmentFile = null,
            )
          if (loggedToCloud && emailStatus != EmailStatus.QUEUED) {
            repo?.updateAlertEmailStatus(alertId, emailStatus)
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed handling SMS remote command: ${e.message}", e)
      } finally {
        pending.finish()
      }
    }
  }

  private fun senderMatches(actual: String, trusted: String): Boolean {
    val cleanActual = actual.filter { it.isDigit() }
    val cleanTrusted = trusted.filter { it.isDigit() }
    if (cleanActual.isBlank() || cleanTrusted.isBlank()) {
      return actual.trim().equals(trusted.trim(), ignoreCase = true)
    }
    val tail = minOf(cleanActual.length, cleanTrusted.length, 10)
    return tail >= 7 && cleanActual.takeLast(tail) == cleanTrusted.takeLast(tail)
  }

  private fun sendSmsReplyIfPermitted(context: Context, destination: String, text: String) {
    val granted =
      ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
        PackageManager.PERMISSION_GRANTED
    if (!granted) return

    runCatching {
      val smsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          context.getSystemService(SmsManager::class.java)
        } else {
          @Suppress("DEPRECATION")
          SmsManager.getDefault()
        }
      smsManager?.sendTextMessage(destination, null, text.take(155), null, null)
    }.onFailure { err ->
      Log.w(TAG, "Could not send SMS reply: ${err.message}")
    }
  }

  companion object {
    private const val TAG = "SmsCommandReceiver"
  }
}

package com.example.security

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.example.data.local.TerraGuardSettingsState
import com.example.data.model.EmailStatus
import com.example.data.model.SecurityAlert
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class EmailAlertDispatcher(
  private val httpClient: OkHttpClient = OkHttpClient(),
  private val smtpSender: SmtpEmailSender = SmtpEmailSender(),
) {

  fun formatAlertSubject(alert: SecurityAlert): String {
    return "[TerraGuard ALERT] ${alert.type}: ${alert.title} (${alert.deviceName})"
  }

  fun formatAlertBody(alert: SecurityAlert): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
    val dateStr = formatter.format(Date(alert.occurredAtMillis))
    val mapsUrl = "https://maps.google.com/?q=${alert.latitude},${alert.longitude}"
    return buildString {
      appendLine("TERRAGUARD SECURITY ALERT")
      appendLine("==========================")
      appendLine("Device: ${alert.deviceName} (${alert.deviceId})")
      appendLine("Event Type: ${alert.type}")
      appendLine("Headline: ${alert.title}")
      appendLine("Details: ${alert.message}")
      appendLine()
      appendLine("TELEMETRY SNAPSHOT")
      appendLine("------------------")
      appendLine("Timestamp: $dateStr")
      appendLine("Latitude: ${String.format(Locale.US, "%.6f", alert.latitude)}")
      appendLine("Longitude: ${String.format(Locale.US, "%.6f", alert.longitude)}")
      appendLine("Accuracy: ${String.format(Locale.US, "%.1f", alert.accuracyMeters)} meters")
      appendLine("Battery Remaining: ${alert.batteryPercent}%")
      appendLine("Photo Evidence Captured: ${if (alert.hasPhotoEvidence) "YES (Attached / Saved in Security Gallery)" else "NO"}")
      appendLine()
      appendLine("Map Link: $mapsUrl")
    }
  }

  data class EmailDispatchReport(
    val status: String,
    val detail: String,
  )

  suspend fun dispatchAutomatedEmail(
    settings: TerraGuardSettingsState,
    ownerEmail: String,
    alert: SecurityAlert,
    attachmentFile: File? = null,
  ): String = dispatchAutomatedEmailDetailed(settings, ownerEmail, alert, attachmentFile).status

  suspend fun dispatchAutomatedEmailDetailed(
    settings: TerraGuardSettingsState,
    ownerEmail: String,
    alert: SecurityAlert,
    attachmentFile: File? = null,
  ): EmailDispatchReport = withContext(Dispatchers.IO) {
    if (ownerEmail.isBlank()) {
      return@withContext EmailDispatchReport(EmailStatus.NOT_CONFIGURED, "Owner email is blank")
    }

    var smtpError: String? = null
    // 1. Try direct SMTP delivery (with intruder photo attachment if present)
    if (settings.smtpEnabled && settings.smtpUsername.isNotBlank() && settings.smtpPassword.isNotBlank()) {
      val smtpResult =
        smtpSender.sendEmail(
          host = settings.smtpHost,
          port = settings.smtpPort,
          username = settings.smtpUsername,
          password = settings.smtpPassword,
          toEmail = ownerEmail,
          subject = formatAlertSubject(alert),
          bodyText = formatAlertBody(alert),
          attachmentFile = attachmentFile,
        )
      if (smtpResult.isSuccess) {
        Log.i("EmailAlert", "SMTP alert sent to $ownerEmail")
        return@withContext EmailDispatchReport(EmailStatus.DISPATCHED, "Sent via SMTP")
      } else {
        smtpError = smtpResult.exceptionOrNull()?.message ?: "SMTP error"
        Log.w("EmailAlert", "SMTP send failed: $smtpError")
      }
    } else if (!settings.smtpEnabled) {
      smtpError = "Direct SMTP is OFF"
    } else {
      smtpError = "SMTP sender/App Password missing (tap Save Email Config)"
    }

    // 2. Fallback to configured HTTPS Webhook URL
    val webhookStatus = dispatchViaWebhookIfConfigured(settings.emailWebhookUrl, ownerEmail, alert)
    if (webhookStatus == EmailStatus.DISPATCHED) {
      return@withContext EmailDispatchReport(EmailStatus.DISPATCHED, "Sent via webhook")
    }

    EmailDispatchReport(EmailStatus.QUEUED, smtpError ?: "Relay not configured")
  }

  suspend fun sendTestSmtpEmail(
    settings: TerraGuardSettingsState,
    toEmail: String,
  ): Result<Unit> {
    return smtpSender.sendEmail(
      host = settings.smtpHost,
      port = settings.smtpPort,
      username = settings.smtpUsername,
      password = settings.smtpPassword,
      toEmail = toEmail,
      subject = "[TerraGuard] Automatic Email Test Successful",
      bodyText = "Your TerraGuard automatic security email relay is configured and active for ${settings.deviceName}.",
      attachmentFile = null,
    )
  }

  suspend fun dispatchViaWebhookIfConfigured(
    webhookUrl: String,
    ownerEmail: String,
    alert: SecurityAlert,
  ): String = withContext(Dispatchers.IO) {
    if (ownerEmail.isBlank()) return@withContext EmailStatus.NOT_CONFIGURED
    val trimmedUrl = webhookUrl.trim()
    if (!trimmedUrl.startsWith("https://")) {
      return@withContext EmailStatus.QUEUED
    }

    val payload =
      JSONObject().apply {
        put("to", ownerEmail)
        put("subject", formatAlertSubject(alert))
        put("body", formatAlertBody(alert))
        put("type", alert.type)
        put("deviceId", alert.deviceId)
        put("deviceName", alert.deviceName)
        put("latitude", alert.latitude)
        put("longitude", alert.longitude)
        put("accuracyMeters", alert.accuracyMeters)
        put("batteryPercent", alert.batteryPercent)
        put("occurredAtMillis", alert.occurredAtMillis)
        put("mapsUrl", "https://maps.google.com/?q=${alert.latitude},${alert.longitude}")
      }

    val request =
      Request.Builder()
        .url(trimmedUrl)
        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()

    val success =
      runCatching {
        httpClient.newCall(request).execute().use { response -> response.isSuccessful }
      }.getOrDefault(false)

    if (success) EmailStatus.DISPATCHED else EmailStatus.QUEUED
  }

  fun launchEmailApp(
    context: Context,
    ownerEmail: String,
    alert: SecurityAlert,
  ): Boolean {
    val subject = formatAlertSubject(alert)
    val body = formatAlertBody(alert)
    val mailtoUri =
      Uri.parse(
        "mailto:${Uri.encode(ownerEmail)}?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}"
      )
    val intent =
      Intent(Intent.ACTION_SENDTO, mailtoUri).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    return runCatching {
      context.startActivity(intent)
      true
    }.getOrDefault(false)
  }

  fun shareEvidencePhoto(
    context: Context,
    file: File,
    ownerEmail: String,
    summary: String,
  ): Boolean {
    if (!file.exists()) return false
    val uri =
      FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
      )
    val intent =
      Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        if (ownerEmail.isNotBlank()) {
          putExtra(Intent.EXTRA_EMAIL, arrayOf(ownerEmail))
        }
        putExtra(Intent.EXTRA_SUBJECT, "[TerraGuard Evidence] Intruder Photo Capture")
        putExtra(Intent.EXTRA_TEXT, summary)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    val chooser =
      Intent.createChooser(intent, "Send Security Evidence").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    return runCatching {
      context.startActivity(chooser)
      true
    }.getOrDefault(false)
  }
}

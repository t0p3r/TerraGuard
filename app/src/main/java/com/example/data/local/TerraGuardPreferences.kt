package com.example.data.local

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.terraGuardDataStore: DataStore<Preferences> by
  preferencesDataStore(name = "terraguard_prefs")

data class LastKnownLocationSnapshot(
  val latitude: Double = 0.0,
  val longitude: Double = 0.0,
  val accuracyMeters: Double = 0.0,
  val batteryPercent: Int = 100,
  val provider: String = "None",
  val recordedAtMillis: Long = 0L,
  val savedBeforeOffline: Boolean = false,
) {
  val hasValidFix: Boolean
    get() = recordedAtMillis > 0L && (latitude != 0.0 || longitude != 0.0)
}

data class TerraGuardSettingsState(
  val deviceId: String = "",
  val deviceName: String = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
  val isProtected: Boolean = true,
  val alarmOverrideSilent: Boolean = true,
  val isPinConfigured: Boolean = false,
  val failedPinThreshold: Int = 3,
  val osFailedPinCount: Int = 0,
  val pendingIntruderCapture: Boolean = false,
  val lastIntruderStatus: String = "",
  val ownerAlertEmail: String = "",
  val emailWebhookUrl: String = "",
  val autoLaunchEmailComposer: Boolean = false,
  val smtpEnabled: Boolean = false,
  val smtpHost: String = "smtp.gmail.com",
  val smtpPort: Int = 465,
  val smtpUsername: String = "",
  val smtpPassword: String = "",
  val smsRemoteEnabled: Boolean = true,
  val smsAlarmKeyword: String = "TERRAGUARD ALARM",
  val smsLocateKeyword: String = "TERRAGUARD LOCATE",
  val smsTrustedSender: String = "",
  val baselineSimFingerprint: String = "",
  val baselineSimCarrier: String = "",
  val lastKnownLocation: LastKnownLocationSnapshot = LastKnownLocationSnapshot(),
  val darkThemeEnabled: Boolean = true,
)

class TerraGuardPreferences(private val context: Context) {
  private val appContext = context.applicationContext

  private object Keys {
    val DEVICE_ID = stringPreferencesKey("device_id")
    val DEVICE_NAME = stringPreferencesKey("device_name")
    val IS_PROTECTED = booleanPreferencesKey("is_protected")
    val ALARM_OVERRIDE_SILENT = booleanPreferencesKey("alarm_override_silent")
    val SECURITY_PIN_HASH = stringPreferencesKey("security_pin_hash")
    val FAILED_PIN_THRESHOLD = intPreferencesKey("failed_pin_threshold")
    val OS_FAILED_PIN_COUNT = intPreferencesKey("os_failed_pin_count")
    val PENDING_INTRUDER_CAPTURE = booleanPreferencesKey("pending_intruder_capture")
    val LAST_INTRUDER_STATUS = stringPreferencesKey("last_intruder_status")
    val OWNER_ALERT_EMAIL = stringPreferencesKey("owner_alert_email")
    val EMAIL_WEBHOOK_URL = stringPreferencesKey("email_webhook_url")
    val AUTO_LAUNCH_EMAIL = booleanPreferencesKey("auto_launch_email")
    val SMTP_ENABLED = booleanPreferencesKey("smtp_enabled")
    val SMTP_HOST = stringPreferencesKey("smtp_host")
    val SMTP_PORT = intPreferencesKey("smtp_port")
    val SMTP_USERNAME = stringPreferencesKey("smtp_username")
    val SMTP_PASSWORD = stringPreferencesKey("smtp_password")
    val SMS_REMOTE_ENABLED = booleanPreferencesKey("sms_remote_enabled")
    val SMS_ALARM_KEYWORD = stringPreferencesKey("sms_alarm_keyword")
    val SMS_LOCATE_KEYWORD = stringPreferencesKey("sms_locate_keyword")
    val SMS_TRUSTED_SENDER = stringPreferencesKey("sms_trusted_sender")
    val BASELINE_SIM_FP = stringPreferencesKey("baseline_sim_fp")
    val BASELINE_SIM_CARRIER = stringPreferencesKey("baseline_sim_carrier")
    val LAST_LAT = doublePreferencesKey("last_lat")
    val LAST_LNG = doublePreferencesKey("last_lng")
    val LAST_ACCURACY = doublePreferencesKey("last_accuracy")
    val LAST_BATTERY = intPreferencesKey("last_battery")
    val LAST_PROVIDER = stringPreferencesKey("last_provider")
    val LAST_TIMESTAMP = longPreferencesKey("last_timestamp")
    val LAST_SAVED_OFFLINE = booleanPreferencesKey("last_saved_offline")
    val DARK_THEME = booleanPreferencesKey("dark_theme")
  }

  val settingsFlow: Flow<TerraGuardSettingsState> =
    appContext.terraGuardDataStore.data.map { prefs ->
      val defaultName =
        "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".take(60)
      TerraGuardSettingsState(
        deviceId = prefs[Keys.DEVICE_ID] ?: "",
        deviceName = prefs[Keys.DEVICE_NAME] ?: defaultName,
        isProtected = prefs[Keys.IS_PROTECTED] ?: true,
        alarmOverrideSilent = prefs[Keys.ALARM_OVERRIDE_SILENT] ?: true,
        isPinConfigured = !prefs[Keys.SECURITY_PIN_HASH].isNullOrBlank(),
        failedPinThreshold = (prefs[Keys.FAILED_PIN_THRESHOLD] ?: 3).coerceIn(1, 6),
        osFailedPinCount = prefs[Keys.OS_FAILED_PIN_COUNT] ?: 0,
        pendingIntruderCapture = prefs[Keys.PENDING_INTRUDER_CAPTURE] ?: false,
        lastIntruderStatus = prefs[Keys.LAST_INTRUDER_STATUS] ?: "",
        ownerAlertEmail = prefs[Keys.OWNER_ALERT_EMAIL] ?: "",
        emailWebhookUrl = prefs[Keys.EMAIL_WEBHOOK_URL] ?: "",
        autoLaunchEmailComposer = prefs[Keys.AUTO_LAUNCH_EMAIL] ?: false,
        smtpEnabled = prefs[Keys.SMTP_ENABLED] ?: false,
        smtpHost = prefs[Keys.SMTP_HOST] ?: "smtp.gmail.com",
        smtpPort = prefs[Keys.SMTP_PORT] ?: 465,
        smtpUsername = prefs[Keys.SMTP_USERNAME] ?: "",
        smtpPassword = prefs[Keys.SMTP_PASSWORD] ?: "",
        smsRemoteEnabled = prefs[Keys.SMS_REMOTE_ENABLED] ?: true,
        smsAlarmKeyword = prefs[Keys.SMS_ALARM_KEYWORD] ?: "TERRAGUARD ALARM",
        smsLocateKeyword = prefs[Keys.SMS_LOCATE_KEYWORD] ?: "TERRAGUARD LOCATE",
        smsTrustedSender = prefs[Keys.SMS_TRUSTED_SENDER] ?: "",
        baselineSimFingerprint = prefs[Keys.BASELINE_SIM_FP] ?: "",
        baselineSimCarrier = prefs[Keys.BASELINE_SIM_CARRIER] ?: "",
        lastKnownLocation =
          LastKnownLocationSnapshot(
            latitude = prefs[Keys.LAST_LAT] ?: 0.0,
            longitude = prefs[Keys.LAST_LNG] ?: 0.0,
            accuracyMeters = prefs[Keys.LAST_ACCURACY] ?: 0.0,
            batteryPercent = prefs[Keys.LAST_BATTERY] ?: 100,
            provider = prefs[Keys.LAST_PROVIDER] ?: "None",
            recordedAtMillis = prefs[Keys.LAST_TIMESTAMP] ?: 0L,
            savedBeforeOffline = prefs[Keys.LAST_SAVED_OFFLINE] ?: false,
          ),
        darkThemeEnabled = prefs[Keys.DARK_THEME] ?: true,
      )
    }

  suspend fun getOrCreateDeviceId(): String {
    val current = appContext.terraGuardDataStore.data.first()[Keys.DEVICE_ID]
    if (!current.isNullOrBlank()) return current
    val generated = "tg_${UUID.randomUUID().toString().replace("-", "").take(16)}"
    appContext.terraGuardDataStore.edit { it[Keys.DEVICE_ID] = generated }
    return generated
  }

  suspend fun setDeviceName(name: String) {
    appContext.terraGuardDataStore.edit {
      it[Keys.DEVICE_NAME] = name.trim().take(80).ifBlank { "Android Device" }
    }
  }

  suspend fun setProtected(enabled: Boolean) {
    appContext.terraGuardDataStore.edit { it[Keys.IS_PROTECTED] = enabled }
  }

  suspend fun setAlarmOverrideSilent(enabled: Boolean) {
    appContext.terraGuardDataStore.edit { it[Keys.ALARM_OVERRIDE_SILENT] = enabled }
  }

  suspend fun setSecurityPin(pin: String) {
    val hash = hashPin(pin)
    appContext.terraGuardDataStore.edit { it[Keys.SECURITY_PIN_HASH] = hash }
  }

  suspend fun verifySecurityPin(pin: String): Boolean {
    val stored = appContext.terraGuardDataStore.data.first()[Keys.SECURITY_PIN_HASH] ?: return false
    return stored == hashPin(pin)
  }

  suspend fun setFailedPinThreshold(threshold: Int) {
    appContext.terraGuardDataStore.edit { it[Keys.FAILED_PIN_THRESHOLD] = threshold.coerceIn(1, 6) }
  }

  suspend fun incrementOsFailedPinCount(): Int {
    var updated = 0
    appContext.terraGuardDataStore.edit { prefs ->
      updated = (prefs[Keys.OS_FAILED_PIN_COUNT] ?: 0) + 1
      prefs[Keys.OS_FAILED_PIN_COUNT] = updated
    }
    return updated
  }

  suspend fun resetOsFailedPinCount() {
    appContext.terraGuardDataStore.edit { it[Keys.OS_FAILED_PIN_COUNT] = 0 }
  }

  suspend fun setPendingIntruderCapture(pending: Boolean) {
    appContext.terraGuardDataStore.edit { it[Keys.PENDING_INTRUDER_CAPTURE] = pending }
  }

  suspend fun setLastIntruderStatus(status: String) {
    appContext.terraGuardDataStore.edit { it[Keys.LAST_INTRUDER_STATUS] = status.take(240) }
  }

  suspend fun setOwnerAlertEmail(email: String) {
    appContext.terraGuardDataStore.edit { it[Keys.OWNER_ALERT_EMAIL] = email.trim().take(160) }
  }

  suspend fun setEmailWebhookUrl(url: String) {
    appContext.terraGuardDataStore.edit { it[Keys.EMAIL_WEBHOOK_URL] = url.trim().take(300) }
  }

  suspend fun setAutoLaunchEmailComposer(enabled: Boolean) {
    appContext.terraGuardDataStore.edit { it[Keys.AUTO_LAUNCH_EMAIL] = enabled }
  }

  suspend fun setSmtpConfig(
    enabled: Boolean,
    host: String,
    port: Int,
    username: String,
    password: String,
  ) {
    appContext.terraGuardDataStore.edit {
      it[Keys.SMTP_ENABLED] = enabled
      it[Keys.SMTP_HOST] = host.trim().ifBlank { "smtp.gmail.com" }
      it[Keys.SMTP_PORT] = port
      it[Keys.SMTP_USERNAME] = username.trim()
      it[Keys.SMTP_PASSWORD] = password.trim()
    }
  }

  suspend fun setSmsRemoteConfig(
    enabled: Boolean,
    alarmKeyword: String,
    locateKeyword: String,
    trustedSender: String,
  ) {
    appContext.terraGuardDataStore.edit {
      it[Keys.SMS_REMOTE_ENABLED] = enabled
      it[Keys.SMS_ALARM_KEYWORD] = alarmKeyword.trim().ifBlank { "TERRAGUARD ALARM" }
      it[Keys.SMS_LOCATE_KEYWORD] = locateKeyword.trim().ifBlank { "TERRAGUARD LOCATE" }
      it[Keys.SMS_TRUSTED_SENDER] = trustedSender.trim().take(30)
    }
  }

  suspend fun setBaselineSim(fingerprint: String, carrier: String) {
    appContext.terraGuardDataStore.edit {
      it[Keys.BASELINE_SIM_FP] = fingerprint.take(160)
      it[Keys.BASELINE_SIM_CARRIER] = carrier.take(80)
    }
  }

  suspend fun saveLastKnownLocation(
    latitude: Double,
    longitude: Double,
    accuracyMeters: Double,
    batteryPercent: Int,
    provider: String,
    recordedAtMillis: Long,
    savedBeforeOffline: Boolean = false,
  ) {
    appContext.terraGuardDataStore.edit {
      it[Keys.LAST_LAT] = latitude
      it[Keys.LAST_LNG] = longitude
      it[Keys.LAST_ACCURACY] = accuracyMeters
      it[Keys.LAST_BATTERY] = batteryPercent.coerceIn(0, 100)
      it[Keys.LAST_PROVIDER] = provider.take(40)
      it[Keys.LAST_TIMESTAMP] = recordedAtMillis
      it[Keys.LAST_SAVED_OFFLINE] = savedBeforeOffline
    }
  }

  suspend fun markLastLocationSavedOffline() {
    appContext.terraGuardDataStore.edit { it[Keys.LAST_SAVED_OFFLINE] = true }
  }

  suspend fun setDarkThemeEnabled(enabled: Boolean) {
    appContext.terraGuardDataStore.edit { it[Keys.DARK_THEME] = enabled }
  }

  private fun hashPin(pin: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest("TerraGuardSalt::$pin".toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
  }
}

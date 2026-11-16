package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class DeviceStatus(
  @DocumentId val id: String = "",
  val userId: String = "",
  val deviceName: String = "",
  val deviceModel: String = "",
  val isProtected: Boolean = true,
  val remoteAlarmActive: Boolean = false,
  val alarmOverrideSilent: Boolean = true,
  val latitude: Double = 0.0,
  val longitude: Double = 0.0,
  val accuracyMeters: Double = 0.0,
  val batteryPercent: Long = 100L,
  val isCharging: Boolean = false,
  val isOnline: Boolean = true,
  val networkType: String = "Unknown",
  val simFingerprint: String = "",
  val simCarrier: String = "",
  val simAlertTriggered: Boolean = false,
  val ownerAlertEmail: String = "",
  val lastLocationAtMillis: Long = 0L,
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class LocationRecord(
  @DocumentId val id: String = "",
  val userId: String = "",
  val deviceId: String = "",
  val latitude: Double = 0.0,
  val longitude: Double = 0.0,
  val accuracyMeters: Double = 0.0,
  val batteryPercent: Long = 100L,
  val isOnline: Boolean = true,
  val provider: String = "Fused",
  val sourceEvent: String = "MANUAL",
  val recordedAtMillis: Long = 0L,
  @ServerTimestamp val createdAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class GeofenceZone(
  @DocumentId val id: String = "",
  val userId: String = "",
  val name: String = "",
  val latitude: Double = 0.0,
  val longitude: Double = 0.0,
  val radiusMeters: Double = 250.0,
  val isActive: Boolean = true,
  val lastStatus: String = "UNKNOWN",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class SecurityAlert(
  @DocumentId val id: String = "",
  val userId: String = "",
  val deviceId: String = "",
  val deviceName: String = "",
  val type: String = "GEOFENCE_EXIT",
  val title: String = "",
  val message: String = "",
  val latitude: Double = 0.0,
  val longitude: Double = 0.0,
  val accuracyMeters: Double = 0.0,
  val batteryPercent: Long = 100L,
  val emailStatus: String = "QUEUED",
  val hasPhotoEvidence: Boolean = false,
  val occurredAtMillis: Long = 0L,
  @ServerTimestamp val createdAt: Timestamp? = null,
)

object AlertType {
  const val GEOFENCE_EXIT = "GEOFENCE_EXIT"
  const val INTRUDER_PIN = "INTRUDER_PIN"
  const val SIM_CHANGE = "SIM_CHANGE"
  const val REMOTE_ALARM = "REMOTE_ALARM"
  const val INTERNET_RECONNECTED = "INTERNET_RECONNECTED"
  const val OFFLINE_LOCATION_SAVED = "OFFLINE_LOCATION_SAVED"
  const val BATTERY_LOW = "BATTERY_LOW"
}

object EmailStatus {
  const val QUEUED = "QUEUED"
  const val DISPATCHED = "DISPATCHED"
  const val NOT_CONFIGURED = "NOT_CONFIGURED"
}

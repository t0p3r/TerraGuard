package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.DeviceStatus
import com.example.data.model.GeofenceZone
import com.example.data.model.LocationRecord
import com.example.data.model.SecurityAlert
import com.example.data.remote.OperationType
import com.example.data.remote.handleFirestoreError
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class TerraGuardRepository(
  private val db: FirebaseFirestore,
  private val auth: FirebaseAuth = Firebase.auth,
) {
  constructor(context: Context) : this(
    FirebaseFirestore.getInstance(
      context.applicationContext.getString(R.string.firestore_database_id)
    )
  )

  private fun requireUserId(): String {
    return auth.currentUser?.uid
      ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
  }

  private fun devicesCollection(uid: String) =
    db.collection("users").document(uid).collection("devices")

  private fun geofencesCollection(uid: String) =
    db.collection("users").document(uid).collection("geofences")

  private fun alertsCollection(uid: String) =
    db.collection("users").document(uid).collection("alerts")

  fun observeDevices(userId: String): Flow<List<DeviceStatus>> = flow {
    val path = "users/$userId/devices"
    emitAll(
      devicesCollection(userId)
        .snapshots()
        .map { snapshot ->
          snapshot.toObjects(DeviceStatus::class.java, ServerTimestampBehavior.ESTIMATE)
        }
        .catch { error ->
          handleFlowError(error, OperationType.LIST, path, userId)
          throw error
        }
    )
  }

  fun observeDevice(userId: String, deviceId: String): Flow<DeviceStatus?> = flow {
    val path = "users/$userId/devices/$deviceId"
    emitAll(
      devicesCollection(userId)
        .document(deviceId)
        .snapshots()
        .map { snapshot ->
          if (snapshot.exists()) {
            snapshot.toObject(DeviceStatus::class.java, ServerTimestampBehavior.ESTIMATE)
          } else {
            null
          }
        }
        .catch { error ->
          handleFlowError(error, OperationType.GET, path, userId)
          throw error
        }
    )
  }

  fun observeLocations(userId: String, deviceId: String): Flow<List<LocationRecord>> = flow {
    val path = "users/$userId/devices/$deviceId/locations"
    emitAll(
      devicesCollection(userId)
        .document(deviceId)
        .collection("locations")
        .orderBy("recordedAtMillis", Query.Direction.DESCENDING)
        .limit(50)
        .snapshots()
        .map { snapshot ->
          snapshot.toObjects(LocationRecord::class.java, ServerTimestampBehavior.ESTIMATE)
        }
        .catch { error ->
          handleFlowError(error, OperationType.LIST, path, userId)
          throw error
        }
    )
  }

  fun observeGeofences(userId: String): Flow<List<GeofenceZone>> = flow {
    val path = "users/$userId/geofences"
    emitAll(
      geofencesCollection(userId)
        .snapshots()
        .map { snapshot ->
          snapshot.toObjects(GeofenceZone::class.java, ServerTimestampBehavior.ESTIMATE)
        }
        .catch { error ->
          handleFlowError(error, OperationType.LIST, path, userId)
          throw error
        }
    )
  }

  fun observeAlerts(userId: String): Flow<List<SecurityAlert>> = flow {
    val path = "users/$userId/alerts"
    emitAll(
      alertsCollection(userId)
        .orderBy("occurredAtMillis", Query.Direction.DESCENDING)
        .limit(100)
        .snapshots()
        .map { snapshot ->
          snapshot.toObjects(SecurityAlert::class.java, ServerTimestampBehavior.ESTIMATE)
        }
        .catch { error ->
          handleFlowError(error, OperationType.LIST, path, userId)
          throw error
        }
    )
  }

  suspend fun upsertDeviceStatus(deviceId: String, status: DeviceStatus): Result<Unit> = runCatching {
    val uid = requireUserId()
    require(deviceId.isNotBlank()) { "Device ID must not be blank" }
    val docRef = devicesCollection(uid).document(deviceId)
    val existingSnapshot = try {
      docRef.get().await()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.GET, docRef.path)
      throw e
    }

    val commonFields = mapOf(
      "deviceName" to status.deviceName.take(80).ifBlank { "Android Device" },
      "deviceModel" to status.deviceModel.take(80),
      "isProtected" to status.isProtected,
      "remoteAlarmActive" to status.remoteAlarmActive,
      "alarmOverrideSilent" to status.alarmOverrideSilent,
      "latitude" to status.latitude.coerceIn(-90.0, 90.0),
      "longitude" to status.longitude.coerceIn(-180.0, 180.0),
      "accuracyMeters" to status.accuracyMeters.coerceIn(0.0, 100000.0),
      "batteryPercent" to status.batteryPercent.coerceIn(0L, 100L),
      "isCharging" to status.isCharging,
      "isOnline" to status.isOnline,
      "networkType" to status.networkType.take(40),
      "simFingerprint" to status.simFingerprint.take(160),
      "simCarrier" to status.simCarrier.take(80),
      "simAlertTriggered" to status.simAlertTriggered,
      "ownerAlertEmail" to status.ownerAlertEmail.take(160),
      "lastLocationAtMillis" to status.lastLocationAtMillis.coerceAtLeast(0L),
      "updatedAt" to FieldValue.serverTimestamp(),
    )

    try {
      if (!existingSnapshot.exists()) {
        val createPayload = commonFields + mapOf(
          "userId" to uid,
          "createdAt" to FieldValue.serverTimestamp(),
        )
        docRef.set(createPayload).await()
      } else {
        docRef.update(commonFields).await()
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(
        e,
        if (existingSnapshot.exists()) OperationType.UPDATE else OperationType.CREATE,
        docRef.path,
      )
      throw e
    }
  }

  suspend fun setRemoteAlarm(
    deviceId: String,
    active: Boolean,
    overrideSilent: Boolean,
  ): Result<Unit> = runCatching {
    val uid = requireUserId()
    val docRef = devicesCollection(uid).document(deviceId)
    try {
      docRef.update(
        mapOf(
          "remoteAlarmActive" to active,
          "alarmOverrideSilent" to overrideSilent,
          "updatedAt" to FieldValue.serverTimestamp(),
        )
      ).await()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, docRef.path)
      throw e
    }
  }

  suspend fun recordLocation(deviceId: String, record: LocationRecord): Result<String> = runCatching {
    val uid = requireUserId()
    val locationId = UUID.randomUUID().toString()
    val docRef = devicesCollection(uid).document(deviceId).collection("locations").document(locationId)
    val payload = mapOf(
      "userId" to uid,
      "deviceId" to deviceId,
      "latitude" to record.latitude.coerceIn(-90.0, 90.0),
      "longitude" to record.longitude.coerceIn(-180.0, 180.0),
      "accuracyMeters" to record.accuracyMeters.coerceIn(0.0, 100000.0),
      "batteryPercent" to record.batteryPercent.coerceIn(0L, 100L),
      "isOnline" to record.isOnline,
      "provider" to record.provider.take(40),
      "sourceEvent" to record.sourceEvent.take(40).ifBlank { "MANUAL" },
      "recordedAtMillis" to record.recordedAtMillis.coerceAtLeast(0L),
      "createdAt" to FieldValue.serverTimestamp(),
    )
    try {
      docRef.set(payload).await()
      locationId
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.CREATE, docRef.path)
      throw e
    }
  }

  suspend fun createGeofence(zone: GeofenceZone): Result<String> = runCatching {
    val uid = requireUserId()
    val geofenceId = zone.id.ifBlank { UUID.randomUUID().toString() }
    val docRef = geofencesCollection(uid).document(geofenceId)
    val payload = mapOf(
      "userId" to uid,
      "name" to zone.name.trim().take(80).ifBlank { "Safe Zone" },
      "latitude" to zone.latitude.coerceIn(-90.0, 90.0),
      "longitude" to zone.longitude.coerceIn(-180.0, 180.0),
      "radiusMeters" to zone.radiusMeters.coerceIn(50.0, 50000.0),
      "isActive" to zone.isActive,
      "lastStatus" to zone.lastStatus.ifBlank { "UNKNOWN" },
      "createdAt" to FieldValue.serverTimestamp(),
      "updatedAt" to FieldValue.serverTimestamp(),
    )
    try {
      docRef.set(payload).await()
      geofenceId
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.CREATE, docRef.path)
      throw e
    }
  }

  suspend fun updateGeofenceStatus(
    geofenceId: String,
    isActive: Boolean,
    lastStatus: String,
  ): Result<Unit> = runCatching {
    val uid = requireUserId()
    val docRef = geofencesCollection(uid).document(geofenceId)
    try {
      docRef.update(
        mapOf(
          "isActive" to isActive,
          "lastStatus" to lastStatus,
          "updatedAt" to FieldValue.serverTimestamp(),
        )
      ).await()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, docRef.path)
      throw e
    }
  }

  suspend fun deleteGeofence(geofenceId: String): Result<Unit> = runCatching {
    val uid = requireUserId()
    val docRef = geofencesCollection(uid).document(geofenceId)
    try {
      docRef.delete().await()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, docRef.path)
      throw e
    }
  }

  suspend fun logAlert(alert: SecurityAlert): Result<String> = runCatching {
    val uid = requireUserId()
    val alertId = alert.id.ifBlank { UUID.randomUUID().toString() }
    val docRef = alertsCollection(uid).document(alertId)
    val payload = mapOf(
      "userId" to uid,
      "deviceId" to alert.deviceId.take(128).ifBlank { "unknown_device" },
      "deviceName" to alert.deviceName.take(80),
      "type" to alert.type,
      "title" to alert.title.take(120).ifBlank { "Security Alert" },
      "message" to alert.message.take(500).ifBlank { "Security event recorded" },
      "latitude" to alert.latitude.coerceIn(-90.0, 90.0),
      "longitude" to alert.longitude.coerceIn(-180.0, 180.0),
      "accuracyMeters" to alert.accuracyMeters.coerceIn(0.0, 100000.0),
      "batteryPercent" to alert.batteryPercent.coerceIn(0L, 100L),
      "emailStatus" to alert.emailStatus,
      "hasPhotoEvidence" to alert.hasPhotoEvidence,
      "occurredAtMillis" to alert.occurredAtMillis.coerceAtLeast(0L),
      "createdAt" to FieldValue.serverTimestamp(),
    )
    try {
      docRef.set(payload).await()
      alertId
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.CREATE, docRef.path)
      throw e
    }
  }

  suspend fun updateAlertEmailStatus(alertId: String, emailStatus: String): Result<Unit> = runCatching {
    val uid = requireUserId()
    val docRef = alertsCollection(uid).document(alertId)
    try {
      docRef.update(mapOf("emailStatus" to emailStatus)).await()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, docRef.path)
      throw e
    }
  }

  suspend fun deleteAlert(alertId: String): Result<Unit> = runCatching {
    val uid = requireUserId()
    val docRef = alertsCollection(uid).document(alertId)
    try {
      docRef.delete().await()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, docRef.path)
      throw e
    }
  }

  private fun handleFlowError(
    error: Throwable,
    operationType: OperationType,
    path: String,
    expectedUid: String,
  ) {
    val firestoreError =
      generateSequence(error) { it.cause }
        .filterIsInstance<FirebaseFirestoreException>()
        .firstOrNull()
    if (firestoreError == null && error is CancellationException) throw error
    val exceptionToLog = firestoreError ?: (error as? Exception)
    if (exceptionToLog != null && auth.currentUser?.uid == expectedUid) {
      handleFirestoreError(exceptionToLog, operationType, path)
    }
  }
}

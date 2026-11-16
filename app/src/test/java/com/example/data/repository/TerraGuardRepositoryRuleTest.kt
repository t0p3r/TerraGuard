package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.AlertType
import com.example.data.model.DeviceStatus
import com.example.data.model.EmailStatus
import com.example.data.model.GeofenceZone
import com.example.data.model.LocationRecord
import com.example.data.model.SecurityAlert
import com.google.firebase.firestore.FirebaseFirestoreException
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerraGuardRepositoryRuleTest : FirestoreEmulatorTestBase() {

  @Test
  fun upsertDeviceAndRecordLocation_authenticatedOwner_succeeds() = runBlocking {
    val uid = signInTestUser(ALICE_EMAIL)
    val repo = TerraGuardRepository(firestore, auth)
    val deviceId = "dev_${UUID.randomUUID().toString().take(12)}"

    val status =
      DeviceStatus(
        userId = uid,
        deviceName = "Pixel 9 Pro",
        deviceModel = "Google Pixel",
        isProtected = true,
        remoteAlarmActive = false,
        alarmOverrideSilent = true,
        latitude = 37.422,
        longitude = -122.084,
        accuracyMeters = 8.5,
        batteryPercent = 91L,
        isCharging = false,
        isOnline = true,
        networkType = "Wi-Fi",
        simFingerprint = "sim_fp_1",
        simCarrier = "T-Mobile",
        simAlertTriggered = false,
        ownerAlertEmail = ALICE_EMAIL,
        lastLocationAtMillis = System.currentTimeMillis(),
      )

    val upsertResult = withTimeout(DEFAULT_TIMEOUT_MS) { repo.upsertDeviceStatus(deviceId, status) }
    assertTrue(upsertResult.isSuccess)

    val locResult =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repo.recordLocation(
          deviceId,
          LocationRecord(
            userId = uid,
            deviceId = deviceId,
            latitude = 37.422,
            longitude = -122.084,
            accuracyMeters = 8.5,
            batteryPercent = 91L,
            isOnline = true,
            provider = "Fused",
            sourceEvent = "MANUAL",
            recordedAtMillis = System.currentTimeMillis(),
          ),
        )
      }
    assertTrue(locResult.isSuccess)

    val alarmResult =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repo.setRemoteAlarm(deviceId, active = true, overrideSilent = true)
      }
    assertTrue(alarmResult.isSuccess)

    val devices =
      withTimeout(FLOW_TIMEOUT_MS) {
        repo.observeDevices(uid).first { list -> list.any { it.id == deviceId && it.remoteAlarmActive } }
      }
    assertTrue(devices.any { it.id == deviceId && it.remoteAlarmActive })
  }

  @Test
  fun geofenceAndAlertLifecycle_authenticatedOwner_succeeds() = runBlocking {
    val uid = signInTestUser(ALICE_EMAIL)
    val repo = TerraGuardRepository(firestore, auth)
    val deviceId = "dev_${UUID.randomUUID().toString().take(12)}"

    val geoId =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repo
          .createGeofence(
            GeofenceZone(
              userId = uid,
              name = "Home Base",
              latitude = 37.422,
              longitude = -122.084,
              radiusMeters = 250.0,
              isActive = true,
              lastStatus = "INSIDE",
            )
          )
          .getOrThrow()
      }
    assertTrue(geoId.isNotBlank())

    val updateGeo =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repo.updateGeofenceStatus(geoId, isActive = true, lastStatus = "OUTSIDE")
      }
    assertTrue(updateGeo.isSuccess)

    val alertId =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repo
          .logAlert(
            SecurityAlert(
              userId = uid,
              deviceId = deviceId,
              deviceName = "Pixel 9 Pro",
              type = AlertType.GEOFENCE_EXIT,
              title = "Left Home Base",
              message = "Device exited geofence perimeter.",
              latitude = 37.428,
              longitude = -122.090,
              accuracyMeters = 12.0,
              batteryPercent = 82L,
              emailStatus = EmailStatus.QUEUED,
              hasPhotoEvidence = false,
              occurredAtMillis = System.currentTimeMillis(),
            )
          )
          .getOrThrow()
      }
    assertTrue(alertId.isNotBlank())

    val emailUpdate =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repo.updateAlertEmailStatus(alertId, EmailStatus.DISPATCHED)
      }
    assertTrue(emailUpdate.isSuccess)
  }

  @Test
  fun observeDevices_unauthenticatedCaller_failsWithPermissionDenied() = runBlocking {
    auth.signOut()
    val repo = TerraGuardRepository(firestore, auth)

    var thrown: Throwable? = null
    try {
      withTimeout(FLOW_TIMEOUT_MS) { repo.observeDevices("some_uid").first() }
    } catch (e: Exception) {
      thrown = e
    }
    assertNotNull(thrown)
    val firestoreException = thrown!!.unwrapFirestoreException()
    assertNotNull("Expected nested FirebaseFirestoreException, got $thrown", firestoreException)
    assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreException!!.code)
  }

  @Test
  fun crossUserAccess_bobCannotReadOrTriggerAliceDevice() = runBlocking {
    val aliceUid = signInTestUser(ALICE_EMAIL)
    val aliceRepo = TerraGuardRepository(firestore, auth)
    val deviceId = "dev_${UUID.randomUUID().toString().take(12)}"

    withTimeout(DEFAULT_TIMEOUT_MS) {
      aliceRepo
        .upsertDeviceStatus(
          deviceId,
          DeviceStatus(
            userId = aliceUid,
            deviceName = "Alice Phone",
            deviceModel = "Pixel",
            latitude = 37.422,
            longitude = -122.084,
            accuracyMeters = 10.0,
            batteryPercent = 75L,
            ownerAlertEmail = ALICE_EMAIL,
            lastLocationAtMillis = System.currentTimeMillis(),
          ),
        )
        .getOrThrow()
    }

    signInTestUser(BOB_EMAIL)
    val bobRepo = TerraGuardRepository(firestore, auth)

    var thrown: Throwable? = null
    try {
      withTimeout(FLOW_TIMEOUT_MS) { bobRepo.observeDevice(aliceUid, deviceId).first() }
    } catch (e: Exception) {
      thrown = e
    }
    assertNotNull(thrown)
    val firestoreException = thrown!!.unwrapFirestoreException()
    assertNotNull(firestoreException)
    assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreException!!.code)

    val alarmAttempt =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        bobRepo.setRemoteAlarm(deviceId, active = true, overrideSilent = true)
      }
    assertTrue(alarmAttempt.isFailure)
  }

  private companion object {
    const val ALICE_EMAIL = "alice_tg@test.com"
    const val BOB_EMAIL = "bob_tg@test.com"
    const val DEFAULT_TIMEOUT_MS = 5000L
    const val FLOW_TIMEOUT_MS = 3000L
  }
}

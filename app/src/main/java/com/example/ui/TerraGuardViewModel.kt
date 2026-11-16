package com.example.ui

import android.content.Context
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.LastKnownLocationSnapshot
import com.example.data.local.PendingAlertEntity
import com.example.data.local.SecurityEvidenceDao
import com.example.data.local.SecurityPhotoEntity
import com.example.data.local.TerraGuardPreferences
import com.example.data.local.TerraGuardSettingsState
import com.example.data.model.AlertType
import com.example.data.model.DeviceStatus
import com.example.data.model.EmailStatus
import com.example.data.model.GeofenceZone
import com.example.data.model.LocationRecord
import com.example.data.model.SecurityAlert
import com.example.data.repository.TerraGuardRepository
import com.example.receiver.GeofenceEventBus
import com.example.receiver.LockscreenFailureBus
import com.example.security.BatterySnapshot
import com.example.security.DeviceTelemetryManager
import com.example.security.GpsFix
import com.example.security.IntruderCameraManager
import com.example.security.IntruderPipeline
import com.example.security.LocationAndGeofenceManager
import com.example.security.NetworkSnapshot
import com.example.security.PlatformSecurityBridge
import com.example.security.SimSnapshot
import com.google.android.gms.location.Geofence
import com.google.firebase.firestore.FirebaseFirestoreException
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

sealed interface UiState<out T> {
  data object Loading : UiState<Nothing>
  data class Success<T>(val data: T) : UiState<T>
  data class Error(val message: String) : UiState<Nothing>
}

class TerraGuardViewModel(
  private val repository: TerraGuardRepository,
  private val preferences: TerraGuardPreferences,
  private val evidenceDao: SecurityEvidenceDao,
  private val telemetryManager: DeviceTelemetryManager,
  private val locationManager: LocationAndGeofenceManager,
  private val intruderCameraManager: IntruderCameraManager,
  private val platformBridge: PlatformSecurityBridge,
  private val currentUserId: String,
  private val currentUserEmail: String,
) : ViewModel() {

  private val _currentDeviceId = MutableStateFlow("")
  val currentDeviceId: StateFlow<String> = _currentDeviceId.asStateFlow()

  private val _currentGpsFix = MutableStateFlow<GpsFix?>(null)
  val currentGpsFix: StateFlow<GpsFix?> = _currentGpsFix.asStateFlow()

  private val _isDeviceAdminActive = MutableStateFlow(telemetryManager.isDeviceAdminActive())
  val isDeviceAdminActive: StateFlow<Boolean> = _isDeviceAdminActive.asStateFlow()

  private val _hasDndPolicyAccess =
    MutableStateFlow(platformBridge.isNotificationPolicyAccessGranted())
  val hasDndPolicyAccess: StateFlow<Boolean> = _hasDndPolicyAccess.asStateFlow()

  private val _isVaultUnlocked = MutableStateFlow(false)
  val isVaultUnlocked: StateFlow<Boolean> = _isVaultUnlocked.asStateFlow()

  private val _wrongPinAttempts = MutableStateFlow(0)
  val wrongPinAttempts: StateFlow<Int> = _wrongPinAttempts.asStateFlow()

  private val _isCapturingGps = MutableStateFlow(false)
  val isCapturingGps: StateFlow<Boolean> = _isCapturingGps.asStateFlow()

  private val _statusBanner = MutableSharedFlow<String>(extraBufferCapacity = 16)
  val statusBanner: SharedFlow<String> = _statusBanner.asSharedFlow()

  val settingsState: StateFlow<TerraGuardSettingsState> =
    preferences.settingsFlow.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
      initialValue = TerraGuardSettingsState(),
    )

  val batteryState: StateFlow<BatterySnapshot> = telemetryManager.battery
  val networkState: StateFlow<NetworkSnapshot> = telemetryManager.network
  val simState: StateFlow<SimSnapshot> = telemetryManager.sim
  val isAlarmRinging: StateFlow<Boolean> = platformBridge.isAlarmRinging

  val photosState: StateFlow<List<SecurityPhotoEntity>> =
    evidenceDao.observePhotos().stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
      initialValue = emptyList(),
    )

  val pendingOfflineCount: StateFlow<Int> =
    evidenceDao.observePendingAlertCount().stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
      initialValue = 0,
    )

  val devicesState: StateFlow<UiState<List<DeviceStatus>>> =
    repository
      .observeDevices(currentUserId)
      .map<List<DeviceStatus>, UiState<List<DeviceStatus>>> { UiState.Success(it) }
      .catch { error ->
        val rootError = unwrapFirestore(error)
        if (rootError == null && error is CancellationException) throw error
        val message = (rootError ?: error).message ?: "Failed to load devices"
        Log.w(TAG, "observeDevices error: $message", rootError ?: error)
        emit(UiState.Error(message))
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = UiState.Loading,
      )

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  val locationsState: StateFlow<UiState<List<LocationRecord>>> =
    _currentDeviceId
      .flatMapLatest { deviceId ->
        if (deviceId.isBlank()) {
          flowOf(UiState.Loading)
        } else {
          repository
            .observeLocations(currentUserId, deviceId)
            .map<List<LocationRecord>, UiState<List<LocationRecord>>> { UiState.Success(it) }
            .catch { error ->
              val rootError = unwrapFirestore(error)
              if (rootError == null && error is CancellationException) throw error
              val message = (rootError ?: error).message ?: "Failed to load GPS history"
              Log.w(TAG, "observeLocations error: $message", rootError ?: error)
              emit(UiState.Error(message))
            }
        }
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = UiState.Loading,
      )

  val geofencesState: StateFlow<UiState<List<GeofenceZone>>> =
    repository
      .observeGeofences(currentUserId)
      .map<List<GeofenceZone>, UiState<List<GeofenceZone>>> { UiState.Success(it) }
      .catch { error ->
        val rootError = unwrapFirestore(error)
        if (rootError == null && error is CancellationException) throw error
        val message = (rootError ?: error).message ?: "Failed to load geofences"
        Log.w(TAG, "observeGeofences error: $message", rootError ?: error)
        emit(UiState.Error(message))
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = UiState.Loading,
      )

  val alertsState: StateFlow<UiState<List<SecurityAlert>>> =
    repository
      .observeAlerts(currentUserId)
      .map<List<SecurityAlert>, UiState<List<SecurityAlert>>> { UiState.Success(it) }
      .catch { error ->
        val rootError = unwrapFirestore(error)
        if (rootError == null && error is CancellationException) throw error
        val message = (rootError ?: error).message ?: "Failed to load alerts"
        Log.w(TAG, "observeAlerts error: $message", rootError ?: error)
        emit(UiState.Error(message))
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = UiState.Loading,
      )

  private var lowBatteryAlertSent = false
  private var simAlertSentForCurrentMismatch = false
  private var lastKnownRemoteAlarm = false

  init {
    initializeMonitoring()
  }

  private fun initializeMonitoring() {
    viewModelScope.launch {
      val deviceId = preferences.getOrCreateDeviceId()
      _currentDeviceId.value = deviceId

      val initialSettings = preferences.settingsFlow.first()
      if (initialSettings.ownerAlertEmail.isBlank() && currentUserEmail.isNotBlank()) {
        preferences.setOwnerAlertEmail(currentUserEmail)
      }
      if (initialSettings.lastKnownLocation.hasValidFix) {
        _currentGpsFix.value =
          GpsFix(
            latitude = initialSettings.lastKnownLocation.latitude,
            longitude = initialSettings.lastKnownLocation.longitude,
            accuracyMeters = initialSettings.lastKnownLocation.accuracyMeters,
            provider = initialSettings.lastKnownLocation.provider,
            timestampMillis = initialSettings.lastKnownLocation.recordedAtMillis,
          )
      }

      telemetryManager.startMonitoring(
        onInternetReconnected = { net -> handleInternetReconnected(net) },
        onInternetLost = { handleInternetLost() },
      )

      if (initialSettings.isProtected && (locationManager.hasLocationPermission() || intruderCameraManager.hasCameraPermission())) {
        platformBridge.startMonitoringService()
        if (locationManager.hasLocationPermission()) {
          startGpsTrackingLoop()
        }
      }
      if (initialSettings.isProtected && initialSettings.pendingIntruderCapture) {
        platformBridge.startIntruderCaptureService(
          reason = "Unlock after Failed Lockscreen PIN (${initialSettings.osFailedPinCount} attempts)",
          attemptCount = initialSettings.osFailedPinCount.coerceAtLeast(1),
          fromUnlock = true,
        )
      }

      // Initial device status registration in Firestore
      syncCurrentDeviceStatus()
    }

    // Observe remote alarm state on THIS device from Firestore
    viewModelScope.launch {
      combine(devicesState, _currentDeviceId) { state, deviceId ->
        if (state is UiState.Success && deviceId.isNotBlank()) {
          state.data.firstOrNull { it.id == deviceId }
        } else {
          null
        }
      }.collectLatest { thisDevice ->
        if (thisDevice != null) {
          if (thisDevice.remoteAlarmActive && !lastKnownRemoteAlarm) {
            lastKnownRemoteAlarm = true
            platformBridge.startAlarm(thisDevice.alarmOverrideSilent)
          } else if (!thisDevice.remoteAlarmActive && lastKnownRemoteAlarm) {
            lastKnownRemoteAlarm = false
            platformBridge.stopAlarm()
          }
        }
      }
    }

    // Observe geofences & keep system GeofencingClient synced
    viewModelScope.launch {
      geofencesState.collectLatest { state ->
        if (state is UiState.Success) {
          locationManager.syncSystemGeofences(state.data)
        }
      }
    }

    // Observe system GeofenceBroadcastReceiver events
    viewModelScope.launch {
      GeofenceEventBus.events.collect { transition ->
        val zones = (geofencesState.value as? UiState.Success)?.data.orEmpty()
        transition.geofenceIds.forEach { zoneId ->
          val zone = zones.firstOrNull { it.id == zoneId }
          val zoneName = zone?.name ?: zoneId
          if (transition.transitionType == Geofence.GEOFENCE_TRANSITION_EXIT) {
            repository.updateGeofenceStatus(zoneId, isActive = true, lastStatus = "OUTSIDE")
            recordAndDispatchAlert(
              type = AlertType.GEOFENCE_EXIT,
              title = "Geofence Exit: $zoneName",
              message = "Device exited protected safe zone \"$zoneName\".",
              hasPhotoEvidence = false,
            )
          } else if (transition.transitionType == Geofence.GEOFENCE_TRANSITION_ENTER) {
            repository.updateGeofenceStatus(zoneId, isActive = true, lastStatus = "INSIDE")
          }
        }
      }
    }

    // Observe SIM state changes against baseline
    viewModelScope.launch {
      combine(simState, settingsState) { sim, settings -> sim to settings }
        .collectLatest { (sim, settings) ->
          if (
            settings.isProtected &&
              settings.baselineSimFingerprint.isNotBlank() &&
              sim.fingerprint.isNotBlank() &&
              sim.fingerprint != settings.baselineSimFingerprint
          ) {
            if (!simAlertSentForCurrentMismatch) {
              simAlertSentForCurrentMismatch = true
              syncCurrentDeviceStatus(simAlertTriggered = true)
              recordAndDispatchAlert(
                type = AlertType.SIM_CHANGE,
                title = "SIM Card Change Detected",
                message =
                  "SIM profile changed from ${settings.baselineSimCarrier.ifBlank { "Baseline" }} to ${sim.carrierName} (${sim.simStateLabel}).",
                hasPhotoEvidence = false,
              )
            }
          } else if (sim.fingerprint == settings.baselineSimFingerprint) {
            simAlertSentForCurrentMismatch = false
          }
        }
    }

    // Observe low battery threshold
    viewModelScope.launch {
      batteryState.collectLatest { battery ->
        if (battery.isLow && !lowBatteryAlertSent && settingsState.value.isProtected) {
          lowBatteryAlertSent = true
          recordAndDispatchAlert(
            type = AlertType.BATTERY_LOW,
            title = "Critical Battery (${battery.percent}%)",
            message = "Device battery dropped to ${battery.percent}%. Saving latest GPS fix.",
            hasPhotoEvidence = false,
          )
        } else if (!battery.isLow) {
          lowBatteryAlertSent = false
        }
      }
    }

    // Observe OS Device Admin password failure threshold (capture service handles evidence & alert)
    viewModelScope.launch {
      LockscreenFailureBus.thresholdReached.collect { attempts ->
        _statusBanner.tryEmit(
          "OS lockscreen failure threshold ($attempts attempts) — capturing photo & GPS..."
        )
      }
    }
  }

  fun refreshSystemStates(lifecycleOwner: LifecycleOwner? = null) {
    telemetryManager.refreshAll()
    _isDeviceAdminActive.value = telemetryManager.isDeviceAdminActive()
    _hasDndPolicyAccess.value = platformBridge.isNotificationPolicyAccessGranted()
    viewModelScope.launch {
      val settings = preferences.settingsFlow.first()
      if (settings.isProtected && (locationManager.hasLocationPermission() || intruderCameraManager.hasCameraPermission())) {
        platformBridge.startMonitoringService()
      }
      if (settings.isProtected && settings.pendingIntruderCapture) {
        val count = settings.osFailedPinCount.coerceAtLeast(1)
        if (lifecycleOwner != null && intruderCameraManager.hasCameraPermission()) {
          captureIntruderEvidence(
            lifecycleOwner = lifecycleOwner,
            reason = "Unlock after Failed Lockscreen PIN ($count attempts)",
            attemptCount = count,
            clearPendingCapture = true,
          )
        } else {
          platformBridge.startIntruderCaptureService(
            reason = "Unlock after Failed Lockscreen PIN ($count attempts)",
            attemptCount = count,
            fromUnlock = true,
          )
        }
      }
    }
  }

  fun startGpsTrackingLoop() {
    if (!locationManager.hasLocationPermission()) return
    locationManager.startContinuousTracking(intervalMillis = 25_000L) { fix ->
      viewModelScope.launch {
        handleNewGpsFix(fix, sourceEvent = "PERIODIC")
      }
    }
  }

  fun captureGpsNow(sourceEvent: String = "MANUAL") {
    viewModelScope.launch {
      if (!locationManager.hasLocationPermission()) {
        _statusBanner.tryEmit("Grant Location permission to capture live GPS.")
        return@launch
      }
      _isCapturingGps.value = true
      try {
        val fix = locationManager.captureCurrentLocation()
        if (fix != null) {
          handleNewGpsFix(fix, sourceEvent = sourceEvent)
          _statusBanner.tryEmit(
            "GPS locked (±${fix.accuracyMeters.toInt()}m) • ${batteryState.value.percent}% battery"
          )
        } else {
          _statusBanner.tryEmit("GPS provider returned no fix yet. Ensure emulator/device location is set.")
        }
      } finally {
        _isCapturingGps.value = false
      }
    }
  }

  private suspend fun handleNewGpsFix(fix: GpsFix, sourceEvent: String) {
    _currentGpsFix.value = fix
    val battery = telemetryManager.readBatterySnapshot()
    val net = telemetryManager.readCurrentNetworkSnapshot()
    val deviceId = _currentDeviceId.value.ifBlank { preferences.getOrCreateDeviceId() }

    // Always persist Last Known Location locally before anything can go offline
    preferences.saveLastKnownLocation(
      latitude = fix.latitude,
      longitude = fix.longitude,
      accuracyMeters = fix.accuracyMeters,
      batteryPercent = battery.percent,
      provider = fix.provider,
      recordedAtMillis = fix.timestampMillis,
      savedBeforeOffline = !net.isOnline,
    )

    syncCurrentDeviceStatus(gpsOverride = fix)

    if (net.isOnline) {
      repository.recordLocation(
        deviceId,
        LocationRecord(
          userId = currentUserId,
          deviceId = deviceId,
          latitude = fix.latitude,
          longitude = fix.longitude,
          accuracyMeters = fix.accuracyMeters,
          batteryPercent = battery.percent.toLong(),
          isOnline = true,
          provider = fix.provider,
          sourceEvent = sourceEvent,
          recordedAtMillis = fix.timestampMillis,
        ),
      )
    }

    evaluateSoftwareGeofences(fix)
  }

  private suspend fun evaluateSoftwareGeofences(fix: GpsFix) {
    val zones = (geofencesState.value as? UiState.Success)?.data.orEmpty()
    for (zone in zones) {
      if (!zone.isActive || zone.id.isBlank()) continue
      val distance =
        locationManager.computeDistanceMeters(
          fix.latitude,
          fix.longitude,
          zone.latitude,
          zone.longitude,
        )
      val newStatus = if (distance <= zone.radiusMeters.toFloat()) "INSIDE" else "OUTSIDE"
      if (newStatus != zone.lastStatus) {
        repository.updateGeofenceStatus(zone.id, isActive = zone.isActive, lastStatus = newStatus)
        if (zone.lastStatus == "INSIDE" && newStatus == "OUTSIDE") {
          recordAndDispatchAlert(
            type = AlertType.GEOFENCE_EXIT,
            title = "Left ${zone.name}",
            message =
              "Device moved ${distance.toInt()}m from \"${zone.name}\" (limit ${zone.radiusMeters.toInt()}m).",
            hasPhotoEvidence = false,
            gpsOverride = fix,
          )
        }
      }
    }
  }

  private fun handleInternetLost() {
    viewModelScope.launch {
      val fix = _currentGpsFix.value ?: locationManager.captureCurrentLocation()
      val battery = telemetryManager.readBatterySnapshot()
      if (fix != null) {
        preferences.saveLastKnownLocation(
          latitude = fix.latitude,
          longitude = fix.longitude,
          accuracyMeters = fix.accuracyMeters,
          batteryPercent = battery.percent,
          provider = fix.provider,
          recordedAtMillis = fix.timestampMillis,
          savedBeforeOffline = true,
        )
      } else {
        preferences.markLastLocationSavedOffline()
      }

      val lastLoc = preferences.settingsFlow.first().lastKnownLocation
      evidenceDao.insertPendingAlert(
        PendingAlertEntity(
          id = UUID.randomUUID().toString(),
          type = AlertType.OFFLINE_LOCATION_SAVED,
          title = "Device Went Offline — Last GPS Saved",
          message = "Saved last available position locally before internet disconnected.",
          latitude = lastLoc.latitude,
          longitude = lastLoc.longitude,
          accuracyMeters = lastLoc.accuracyMeters,
          batteryPercent = battery.percent,
          hasPhotoEvidence = false,
          occurredAtMillis = System.currentTimeMillis(),
        )
      )
      _statusBanner.tryEmit("Offline detected — Last Known Location preserved locally.")
    }
  }

  private fun handleInternetReconnected(net: NetworkSnapshot) {
    viewModelScope.launch {
      delay(800)
      val deviceId = _currentDeviceId.value.ifBlank { preferences.getOrCreateDeviceId() }
      val fix = locationManager.captureCurrentLocation() ?: _currentGpsFix.value
      if (fix != null) {
        handleNewGpsFix(fix, sourceEvent = "RECONNECT")
      } else {
        syncCurrentDeviceStatus()
      }

      // Flush offline queued alerts to Firestore
      val settings = settingsState.value
      val pending = evidenceDao.getPendingAlerts()
      for (item in pending) {
        val alert =
          SecurityAlert(
            id = item.id,
            userId = currentUserId,
            deviceId = deviceId,
            deviceName = settings.deviceName,
            type = item.type,
            title = item.title,
            message = item.message,
            latitude = item.latitude,
            longitude = item.longitude,
            accuracyMeters = item.accuracyMeters,
            batteryPercent = item.batteryPercent.toLong(),
            emailStatus = EmailStatus.QUEUED,
            hasPhotoEvidence = item.hasPhotoEvidence,
            occurredAtMillis = item.occurredAtMillis,
          )
        val result = repository.logAlert(alert)
        if (result.isSuccess) {
          val ownerEmail = settings.ownerAlertEmail.ifBlank { currentUserEmail }
          val emailStatus =
            platformBridge.dispatchAutomatedEmail(
              settings = settings,
              ownerEmail = ownerEmail,
              alert = alert,
              attachmentFile = null,
            )
          if (emailStatus != EmailStatus.QUEUED) {
            repository.updateAlertEmailStatus(item.id, emailStatus)
          }
          evidenceDao.deletePendingAlert(item.id)
        }
      }

      recordAndDispatchAlert(
        type = AlertType.INTERNET_RECONNECTED,
        title = "Phone Reconnected (${net.networkType})",
        message = "Device regained internet via ${net.networkType}. Flushed ${pending.size} queued alert(s).",
        hasPhotoEvidence = false,
        gpsOverride = fix,
      )
    }
  }

  fun toggleProtection(enabled: Boolean) {
    viewModelScope.launch {
      preferences.setProtected(enabled)
      if (enabled) {
        if (locationManager.hasLocationPermission() || intruderCameraManager.hasCameraPermission()) {
          platformBridge.startMonitoringService()
        }
        if (locationManager.hasLocationPermission()) {
          startGpsTrackingLoop()
        }
        val sim = telemetryManager.readSimSnapshot()
        if (settingsState.value.baselineSimFingerprint.isBlank() && sim.fingerprint.isNotBlank()) {
          preferences.setBaselineSim(sim.fingerprint, sim.carrierName)
        }
      } else {
        locationManager.stopContinuousTracking()
        platformBridge.stopMonitoringService()
      }
      syncCurrentDeviceStatus(isProtectedOverride = enabled)
    }
  }

  fun triggerRemoteAlarm(targetDeviceId: String, active: Boolean) {
    viewModelScope.launch {
      val overrideSilent = settingsState.value.alarmOverrideSilent
      val result = repository.setRemoteAlarm(targetDeviceId, active, overrideSilent)
      if (result.isSuccess) {
        if (targetDeviceId == _currentDeviceId.value) {
          if (active) {
            platformBridge.startAlarm(overrideSilent)
          } else {
            platformBridge.stopAlarm()
          }
        }
        if (active) {
          recordAndDispatchAlert(
            type = AlertType.REMOTE_ALARM,
            title = "Remote Alarm Triggered",
            message =
              if (overrideSilent) "Loud emergency siren activated (Silent Mode Override ON)."
              else "Loud emergency siren activated.",
            hasPhotoEvidence = false,
          )
        }
        _statusBanner.tryEmit(if (active) "Remote alarm triggered!" else "Remote alarm silenced.")
      } else {
        _statusBanner.tryEmit("Failed to update alarm: ${result.exceptionOrNull()?.message}")
      }
    }
  }

  fun setAlarmOverrideSilent(enabled: Boolean) {
    viewModelScope.launch {
      preferences.setAlarmOverrideSilent(enabled)
      syncCurrentDeviceStatus(overrideSilentOverride = enabled)
    }
  }

  fun createGeofence(name: String, latitude: Double, longitude: Double, radiusMeters: Double) {
    viewModelScope.launch {
      val fix = _currentGpsFix.value
      val initialStatus =
        if (fix != null) {
          val dist = locationManager.computeDistanceMeters(fix.latitude, fix.longitude, latitude, longitude)
          if (dist <= radiusMeters.toFloat()) "INSIDE" else "OUTSIDE"
        } else {
          "UNKNOWN"
        }

      val result =
        repository.createGeofence(
          GeofenceZone(
            userId = currentUserId,
            name = name.trim().ifBlank { "Safe Zone" },
            latitude = latitude,
            longitude = longitude,
            radiusMeters = radiusMeters.coerceIn(50.0, 50000.0),
            isActive = true,
            lastStatus = initialStatus,
          )
        )
      if (result.isSuccess) {
        _statusBanner.tryEmit("Geofence \"$name\" activated (${radiusMeters.toInt()}m radius).")
      } else {
        _statusBanner.tryEmit("Could not save geofence: ${result.exceptionOrNull()?.message}")
      }
    }
  }

  fun toggleGeofence(zone: GeofenceZone) {
    viewModelScope.launch {
      repository.updateGeofenceStatus(zone.id, !zone.isActive, zone.lastStatus)
    }
  }

  fun deleteGeofence(geofenceId: String) {
    viewModelScope.launch {
      repository.deleteGeofence(geofenceId)
    }
  }

  fun registerBaselineSim() {
    viewModelScope.launch {
      val sim = telemetryManager.readSimSnapshot()
      preferences.setBaselineSim(sim.fingerprint, sim.carrierName)
      simAlertSentForCurrentMismatch = false
      syncCurrentDeviceStatus(simAlertTriggered = false)
      _statusBanner.tryEmit("Baseline SIM registered: ${sim.carrierName} (${sim.simStateLabel})")
    }
  }

  fun configureSecurityPin(pin: String) {
    if (pin.length != 4) return
    viewModelScope.launch {
      preferences.setSecurityPin(pin)
      _isVaultUnlocked.value = true
      _wrongPinAttempts.value = 0
      _statusBanner.tryEmit("4-digit Security PIN saved.")
    }
  }

  fun lockVault() {
    _isVaultUnlocked.value = false
    _wrongPinAttempts.value = 0
  }

  fun submitPinAttempt(
    pin: String,
    lifecycleOwner: LifecycleOwner,
    onSuccess: () -> Unit,
  ) {
    viewModelScope.launch {
      val valid = preferences.verifySecurityPin(pin)
      if (valid) {
        _wrongPinAttempts.value = 0
        _isVaultUnlocked.value = true
        onSuccess()
      } else {
        val attempts = _wrongPinAttempts.value + 1
        _wrongPinAttempts.value = attempts
        val threshold = settingsState.value.failedPinThreshold
        if (attempts >= threshold) {
          captureIntruderEvidence(
            lifecycleOwner = lifecycleOwner,
            reason = "Incorrect Security PIN ($attempts attempts)",
            attemptCount = attempts,
          )
        } else {
          _statusBanner.tryEmit("Incorrect PIN ($attempts/$threshold attempts)")
        }
      }
    }
  }

  fun captureIntruderEvidence(
    lifecycleOwner: LifecycleOwner?,
    reason: String,
    attemptCount: Int,
    clearPendingCapture: Boolean = false,
  ) {
    viewModelScope.launch {
      val localOutcome =
        IntruderPipeline.captureMutex.withLock {
          if (clearPendingCapture) {
            val latestSettings = preferences.settingsFlow.first()
            if (!latestSettings.pendingIntruderCapture) {
              return@withLock null
            }
          }

          val fix = _currentGpsFix.value ?: locationManager.captureCurrentLocation(timeoutMillis = 2_000L)
          val lastSaved = settingsState.value.lastKnownLocation
          val lat = fix?.latitude ?: lastSaved.latitude
          val lng = fix?.longitude ?: lastSaved.longitude
          val acc = fix?.accuracyMeters ?: lastSaved.accuracyMeters
          val battery = telemetryManager.readBatterySnapshot().percent

          val photoResult =
            if (intruderCameraManager.hasCameraPermission()) {
              intruderCameraManager.captureEvidencePhoto(lifecycleOwner)
            } else {
              Result.failure(IllegalStateException("Camera permission not granted"))
            }

          val capturedFile = photoResult.getOrNull()
          val hasPhoto = capturedFile != null && capturedFile.exists()
          if (clearPendingCapture && (hasPhoto || !intruderCameraManager.hasCameraPermission())) {
            preferences.setPendingIntruderCapture(false)
            preferences.resetOsFailedPinCount()
          }
          capturedFile?.let { file ->
            evidenceDao.insertPhoto(
              SecurityPhotoEntity(
                filePath = file.absolutePath,
                reason = reason,
                attemptCount = attemptCount,
                latitude = lat,
                longitude = lng,
                accuracyMeters = acc,
                batteryPercent = battery,
                capturedAtMillis = System.currentTimeMillis(),
              )
            )
          }

          val errMsg = photoResult.exceptionOrNull()?.message
          preferences.setLastIntruderStatus(
            if (hasPhoto) "$reason -> Vault & Gallery"
            else "$reason failed (${errMsg ?: "camera unavailable"})"
          )

          Triple(fix, capturedFile, errMsg)
        } ?: return@launch

      val (fix, capturedFile, errMsg) = localOutcome
      val hasPhoto = capturedFile != null && capturedFile.exists()

      val emailReport =
        recordAndDispatchAlert(
          type = AlertType.INTRUDER_PIN,
          title = "Intruder Security Capture ($attemptCount tries)",
          message =
            if (hasPhoto) "$reason. Intruder photo saved to protected Security Gallery."
            else "$reason. Photo capture unavailable ($errMsg).",
          hasPhotoEvidence = hasPhoto,
          gpsOverride = fix,
          attachmentFile = capturedFile,
        )

      val emailSuffix =
        if (emailReport.status == EmailStatus.DISPATCHED) "Email sent!"
        else "Email: ${emailReport.detail}"

      preferences.setLastIntruderStatus(
        if (hasPhoto) "$reason -> Vault & Gallery • $emailSuffix"
        else "$reason failed (${errMsg ?: "camera unavailable"}) • $emailSuffix"
      )

      _statusBanner.tryEmit(
        if (hasPhoto) "Photo saved to Vault & Gallery • $emailSuffix"
        else "Camera unavailable (${errMsg ?: "grant permission"}) • $emailSuffix"
      )
    }
  }

  fun deleteEvidencePhoto(photo: SecurityPhotoEntity) {
    viewModelScope.launch {
      runCatching { File(photo.filePath).delete() }
      evidenceDao.deletePhotoById(photo.id)
    }
  }

  fun shareEvidencePhoto(photo: SecurityPhotoEntity) {
    val ownerEmail = settingsState.value.ownerAlertEmail.ifBlank { currentUserEmail }
    val summary =
      "TerraGuard Intruder Evidence\nReason: ${photo.reason}\nCoordinates: ${photo.latitude}, ${photo.longitude} (±${photo.accuracyMeters.toInt()}m)\nBattery: ${photo.batteryPercent}%\nMap: https://maps.google.com/?q=${photo.latitude},${photo.longitude}"
    val launched = platformBridge.shareEvidenceFile(File(photo.filePath), ownerEmail, summary)
    if (!launched) {
      _statusBanner.tryEmit("Unable to open share sheet for this evidence file.")
    }
  }

  fun sendAlertViaEmailApp(alert: SecurityAlert) {
    val recipient = settingsState.value.ownerAlertEmail.ifBlank { currentUserEmail }
    val launched = platformBridge.launchEmailComposer(recipient, alert)
    if (launched) {
      viewModelScope.launch {
        if (alert.id.isNotBlank()) {
          repository.updateAlertEmailStatus(alert.id, EmailStatus.DISPATCHED)
        }
      }
    } else {
      _statusBanner.tryEmit("No email app installed on this emulator/device.")
    }
  }

  fun deleteAlert(alertId: String) {
    viewModelScope.launch {
      repository.deleteAlert(alertId)
    }
  }

  fun openInMaps(latitude: Double, longitude: Double, label: String) {
    val ok = platformBridge.openMapsLocation(latitude, longitude, label)
    if (!ok) _statusBanner.tryEmit("Could not open Maps application.")
  }

  fun requestDeviceAdmin(context: Context? = null) = platformBridge.requestDeviceAdminActivation(context)
  fun openDndPolicySettings() = platformBridge.openNotificationPolicySettings()
  fun openAppDetailsSettings() = platformBridge.openAppDetailsSettings()

  fun updateOwnerEmail(email: String) {
    viewModelScope.launch {
      preferences.setOwnerAlertEmail(email)
      syncCurrentDeviceStatus()
    }
  }

  fun updateEmailWebhookUrl(url: String) {
    viewModelScope.launch { preferences.setEmailWebhookUrl(url) }
  }

  fun setAutoLaunchEmailComposer(enabled: Boolean) {
    viewModelScope.launch { preferences.setAutoLaunchEmailComposer(enabled) }
  }

  fun updateSmtpConfig(
    enabled: Boolean,
    host: String,
    port: Int,
    username: String,
    password: String,
  ) {
    viewModelScope.launch {
      preferences.setSmtpConfig(enabled, host, port, username, password)
      _statusBanner.tryEmit("Automatic SMTP email settings saved.")
    }
  }

  fun saveAndSendTestEmail(
    ownerEmail: String,
    deviceName: String,
    enabled: Boolean,
    host: String,
    port: Int,
    username: String,
    password: String,
  ) {
    viewModelScope.launch {
      preferences.setOwnerAlertEmail(ownerEmail)
      preferences.setDeviceName(deviceName)
      preferences.setSmtpConfig(enabled, host, port, username, password)
      syncCurrentDeviceStatus()
      sendTestEmail()
    }
  }

  fun sendTestEmail() {
    viewModelScope.launch {
      val settings = preferences.settingsFlow.first()
      val recipient = settings.ownerAlertEmail.ifBlank { currentUserEmail }
      if (recipient.isBlank()) {
        _statusBanner.tryEmit("Set an Owner Alert Email first.")
        return@launch
      }
      if (!settings.smtpEnabled || settings.smtpUsername.isBlank() || settings.smtpPassword.isBlank()) {
        _statusBanner.tryEmit("Enable Direct SMTP and enter sender email + 16-char App Password first.")
        return@launch
      }
      _statusBanner.tryEmit("Sending test email to $recipient...")
      val result = platformBridge.sendTestEmail(settings, recipient)
      if (result.isSuccess) {
        _statusBanner.tryEmit("Test email delivered to $recipient!")
      } else {
        _statusBanner.tryEmit("SMTP failed: ${result.exceptionOrNull()?.message ?: "Check host/app password"}")
      }
    }
  }

  fun updateSmsRemoteConfig(
    enabled: Boolean,
    alarmKeyword: String,
    locateKeyword: String,
    trustedSender: String,
  ) {
    viewModelScope.launch {
      preferences.setSmsRemoteConfig(enabled, alarmKeyword, locateKeyword, trustedSender)
      _statusBanner.tryEmit("SMS remote trigger settings saved.")
    }
  }

  fun updateFailedPinThreshold(threshold: Int) {
    viewModelScope.launch { preferences.setFailedPinThreshold(threshold) }
  }

  fun updateDeviceName(name: String) {
    viewModelScope.launch {
      preferences.setDeviceName(name)
      syncCurrentDeviceStatus()
    }
  }

  fun setDarkThemeEnabled(enabled: Boolean) {
    viewModelScope.launch { preferences.setDarkThemeEnabled(enabled) }
  }

  private suspend fun syncCurrentDeviceStatus(
    gpsOverride: GpsFix? = null,
    isProtectedOverride: Boolean? = null,
    overrideSilentOverride: Boolean? = null,
    simAlertTriggered: Boolean? = null,
  ) {
    val deviceId = _currentDeviceId.value.ifBlank { preferences.getOrCreateDeviceId() }
    val settings = preferences.settingsFlow.first()
    val battery = telemetryManager.readBatterySnapshot()
    val net = telemetryManager.readCurrentNetworkSnapshot()
    val sim = telemetryManager.readSimSnapshot()
    val fix = gpsOverride ?: _currentGpsFix.value
    val existing =
      (devicesState.value as? UiState.Success)?.data?.firstOrNull { it.id == deviceId }

    val status =
      DeviceStatus(
        id = deviceId,
        userId = currentUserId,
        deviceName = settings.deviceName,
        deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".take(80),
        isProtected = isProtectedOverride ?: settings.isProtected,
        remoteAlarmActive = existing?.remoteAlarmActive ?: isAlarmRinging.value,
        alarmOverrideSilent = overrideSilentOverride ?: settings.alarmOverrideSilent,
        latitude = fix?.latitude ?: settings.lastKnownLocation.latitude,
        longitude = fix?.longitude ?: settings.lastKnownLocation.longitude,
        accuracyMeters = fix?.accuracyMeters ?: settings.lastKnownLocation.accuracyMeters,
        batteryPercent = battery.percent.toLong(),
        isCharging = battery.isCharging,
        isOnline = net.isOnline,
        networkType = net.networkType,
        simFingerprint = sim.fingerprint,
        simCarrier = sim.carrierName,
        simAlertTriggered = simAlertTriggered ?: (existing?.simAlertTriggered ?: false),
        ownerAlertEmail = settings.ownerAlertEmail.ifBlank { currentUserEmail },
        lastLocationAtMillis =
          fix?.timestampMillis ?: settings.lastKnownLocation.recordedAtMillis,
      )
    if (net.isOnline) {
      repository.upsertDeviceStatus(deviceId, status)
    }
  }

  private suspend fun recordAndDispatchAlert(
    type: String,
    title: String,
    message: String,
    hasPhotoEvidence: Boolean,
    gpsOverride: GpsFix? = null,
    attachmentFile: File? = null,
  ): com.example.security.EmailAlertDispatcher.EmailDispatchReport {
    val deviceId = _currentDeviceId.value.ifBlank { preferences.getOrCreateDeviceId() }
    val settings = preferences.settingsFlow.first()
    val battery = telemetryManager.readBatterySnapshot()
    val net = telemetryManager.readCurrentNetworkSnapshot()
    val fix = gpsOverride ?: _currentGpsFix.value
    val lat = fix?.latitude ?: settings.lastKnownLocation.latitude
    val lng = fix?.longitude ?: settings.lastKnownLocation.longitude
    val acc = fix?.accuracyMeters ?: settings.lastKnownLocation.accuracyMeters
    val now = System.currentTimeMillis()
    val ownerEmail = settings.ownerAlertEmail.ifBlank { currentUserEmail }

    platformBridge.postNotification(
      id = (now % 10000).toInt() + 7300,
      title = title,
      body = "$message (${battery.percent}% battery)",
    )

    val alertId = UUID.randomUUID().toString()
    val initialEmailStatus =
      if (ownerEmail.isBlank()) EmailStatus.NOT_CONFIGURED else EmailStatus.QUEUED

    val alert =
      SecurityAlert(
        id = alertId,
        userId = currentUserId,
        deviceId = deviceId,
        deviceName = settings.deviceName,
        type = type,
        title = title,
        message = message,
        latitude = lat,
        longitude = lng,
        accuracyMeters = acc,
        batteryPercent = battery.percent.toLong(),
        emailStatus = initialEmailStatus,
        hasPhotoEvidence = hasPhotoEvidence,
        occurredAtMillis = now,
      )

    if (!net.isOnline) {
      evidenceDao.insertPendingAlert(
        PendingAlertEntity(
          id = alertId,
          type = type,
          title = title,
          message = message,
          latitude = lat,
          longitude = lng,
          accuracyMeters = acc,
          batteryPercent = battery.percent,
          hasPhotoEvidence = hasPhotoEvidence,
          occurredAtMillis = now,
        )
      )
      return com.example.security.EmailAlertDispatcher.EmailDispatchReport(
        EmailStatus.QUEUED,
        "Device offline",
      )
    }

    val logged = runCatching { repository.logAlert(alert) }.getOrElse { Result.failure(it) }
    if (ownerEmail.isBlank()) {
      return com.example.security.EmailAlertDispatcher.EmailDispatchReport(
        EmailStatus.NOT_CONFIGURED,
        "Owner email is blank",
      )
    }

    // Dispatch automated email whenever online — do not block email delivery if Firestore fails.
    val report =
      platformBridge.dispatchAutomatedEmailDetailed(settings, ownerEmail, alert, attachmentFile)
    if (report.status == EmailStatus.DISPATCHED) {
      if (logged.isSuccess) {
        runCatching { repository.updateAlertEmailStatus(alertId, EmailStatus.DISPATCHED) }
      }
    } else if (settings.autoLaunchEmailComposer) {
      val launched = platformBridge.launchEmailComposer(ownerEmail, alert)
      if (launched && logged.isSuccess) {
        runCatching { repository.updateAlertEmailStatus(alertId, EmailStatus.DISPATCHED) }
      }
    }
    return report
  }

  private fun unwrapFirestore(error: Throwable): FirebaseFirestoreException? {
    return generateSequence(error) { it.cause }
      .filterIsInstance<FirebaseFirestoreException>()
      .firstOrNull()
  }

  override fun onCleared() {
    telemetryManager.stopMonitoring()
    locationManager.stopContinuousTracking()
    super.onCleared()
  }

  private companion object {
    const val TAG = "TerraGuardVM"
    const val STOP_TIMEOUT_MILLIS = 5000L
  }
}

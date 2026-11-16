package com.example.security

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.data.model.GeofenceZone
import com.example.receiver.GeofenceBroadcastReceiver
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class GpsFix(
  val latitude: Double,
  val longitude: Double,
  val accuracyMeters: Double,
  val provider: String,
  val timestampMillis: Long,
)

class LocationAndGeofenceManager(context: Context) {
  private val appContext = context.applicationContext
  private val fusedClient = LocationServices.getFusedLocationProviderClient(appContext)
  private val geofencingClient = LocationServices.getGeofencingClient(appContext)
  private val locationManager =
    appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

  private var activeCallback: LocationCallback? = null

  fun hasLocationPermission(): Boolean {
    val fine =
      ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val coarse =
      ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    return fine || coarse
  }

  fun hasBackgroundLocationPermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
      ) == PackageManager.PERMISSION_GRANTED
    } else {
      hasLocationPermission()
    }
  }

  @SuppressLint("MissingPermission")
  suspend fun captureCurrentLocation(timeoutMillis: Long = 3_500L): GpsFix? {
    if (!hasLocationPermission()) return null

    // 1. Try fresh high-accuracy Fused Location with strict timeout so it never stalls Camera/Receivers
    val fusedFix =
      withTimeoutOrNull(timeoutMillis) {
        runCatching {
          val cts = CancellationTokenSource()
          suspendCancellableCoroutine<Location?> { cont ->
            cont.invokeOnCancellation { cts.cancel() }
            fusedClient
              .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
              .addOnSuccessListener { loc -> if (cont.isActive) cont.resume(loc) }
              .addOnFailureListener { if (cont.isActive) cont.resume(null) }
          }
        }.getOrNull()
      }

    if (fusedFix != null) {
      return fusedFix.toGpsFix("Fused")
    }

    // 2. Try Fused lastLocation quickly
    val lastFused =
      withTimeoutOrNull(1_000L) {
        runCatching { fusedClient.lastLocation.await() }.getOrNull()
      }
    if (lastFused != null) {
      return lastFused.toGpsFix("Fused-Last")
    }

    // 3. Fallback to Android framework LocationManager providers
    val providers =
      listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
    val bestPlatformLocation =
      providers
        .mapNotNull { provider -> runCatching { locationManager?.getLastKnownLocation(provider) }.getOrNull() }
        .maxByOrNull { it.time }

    return bestPlatformLocation?.toGpsFix(bestPlatformLocation.provider ?: "Platform")
  }

  @SuppressLint("MissingPermission")
  fun startContinuousTracking(
    intervalMillis: Long = 20_000L,
    onFix: (GpsFix) -> Unit,
  ) {
    if (!hasLocationPermission()) return
    stopContinuousTracking()

    val request =
      LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
        .setMinUpdateIntervalMillis(10_000L)
        .setWaitForAccurateLocation(false)
        .build()

    val callback =
      object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
          val location = result.lastLocation ?: return
          onFix(location.toGpsFix("Fused"))
        }
      }
    activeCallback = callback
    runCatching {
      fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }
  }

  fun stopContinuousTracking() {
    activeCallback?.let { cb ->
      runCatching { fusedClient.removeLocationUpdates(cb) }
    }
    activeCallback = null
  }

  @SuppressLint("MissingPermission")
  suspend fun syncSystemGeofences(zones: List<GeofenceZone>): Result<Int> = runCatching {
    if (!hasLocationPermission()) return@runCatching 0
    val pendingIntent = geofencePendingIntent()
    runCatching { geofencingClient.removeGeofences(pendingIntent).await() }

    val activeZones = zones.filter { it.isActive && it.id.isNotBlank() }.take(95)
    if (activeZones.isEmpty()) return@runCatching 0

    val geofences =
      activeZones.map { zone ->
        Geofence.Builder()
          .setRequestId(zone.id)
          .setCircularRegion(
            zone.latitude,
            zone.longitude,
            zone.radiusMeters.toFloat().coerceIn(50f, 50000f),
          )
          .setExpirationDuration(Geofence.NEVER_EXPIRE)
          .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
          .setNotificationResponsiveness(5000)
          .build()
      }

    val request =
      GeofencingRequest.Builder()
        .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
        .addGeofences(geofences)
        .build()

    geofencingClient.addGeofences(request, pendingIntent).await()
    activeZones.size
  }

  fun computeDistanceMeters(
    lat1: Double,
    lng1: Double,
    lat2: Double,
    lng2: Double,
  ): Float {
    val results = FloatArray(1)
    Location.distanceBetween(lat1, lng1, lat2, lng2, results)
    return results[0]
  }

  private fun geofencePendingIntent(): PendingIntent {
    val intent = Intent(appContext, GeofenceBroadcastReceiver::class.java)
    val flags =
      PendingIntent.FLAG_UPDATE_CURRENT or
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    return PendingIntent.getBroadcast(appContext, 4401, intent, flags)
  }

  private fun Location.toGpsFix(fallbackProvider: String): GpsFix {
    return GpsFix(
      latitude = latitude,
      longitude = longitude,
      accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else 25.0,
      provider = provider ?: fallbackProvider,
      timestampMillis = if (time > 0L) time else System.currentTimeMillis(),
    )
  }
}

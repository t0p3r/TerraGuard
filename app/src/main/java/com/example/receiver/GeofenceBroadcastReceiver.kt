package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.security.TerraGuardNotifications
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class SystemGeofenceTransition(
  val geofenceIds: List<String>,
  val transitionType: Int,
  val latitude: Double?,
  val longitude: Double?,
  val timestampMillis: Long = System.currentTimeMillis(),
)

object GeofenceEventBus {
  private val _events = MutableSharedFlow<SystemGeofenceTransition>(extraBufferCapacity = 16)
  val events: SharedFlow<SystemGeofenceTransition> = _events.asSharedFlow()

  fun emit(event: SystemGeofenceTransition) {
    _events.tryEmit(event)
  }
}

class GeofenceBroadcastReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val event = GeofencingEvent.fromIntent(intent) ?: return
    if (event.hasError()) {
      Log.w(TAG, "Geofencing error code: ${event.errorCode}")
      return
    }

    val transition = event.geofenceTransition
    if (transition != Geofence.GEOFENCE_TRANSITION_EXIT &&
      transition != Geofence.GEOFENCE_TRANSITION_ENTER
    ) {
      return
    }

    val ids = event.triggeringGeofences?.map { it.requestId }.orEmpty()
    val loc = event.triggeringLocation
    GeofenceEventBus.emit(
      SystemGeofenceTransition(
        geofenceIds = ids,
        transitionType = transition,
        latitude = loc?.latitude,
        longitude = loc?.longitude,
      )
    )

    if (transition == Geofence.GEOFENCE_TRANSITION_EXIT) {
      TerraGuardNotifications.postAlertNotification(
        context = context,
        notificationId = 7100,
        title = "TerraGuard Geofence Breach",
        body = "Device exited protected safe zone (${ids.joinToString()}).",
      )
    }
  }

  private companion object {
    const val TAG = "GeofenceReceiver"
  }
}

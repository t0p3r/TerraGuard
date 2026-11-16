package com.example.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.security.AlarmController
import com.example.security.TerraGuardNotifications

class TerraGuardAlarmService : Service() {

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      AlarmController.stopSiren(this)
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return START_NOT_STICKY
    }

    val overrideSilent = intent?.getBooleanExtra(EXTRA_OVERRIDE_SILENT, true) ?: true
    TerraGuardNotifications.ensureChannels(this)

    val openIntent =
      PendingIntent.getActivity(
        this,
        7002,
        Intent(this, MainActivity::class.java).apply {
          this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val notification =
      NotificationCompat.Builder(this, TerraGuardNotifications.CHANNEL_ALARM)
        .setSmallIcon(R.drawable.ic_terraguard_shield)
        .setContentTitle("TerraGuard Remote Alarm Active")
        .setContentText("Emergency anti-theft siren is sounding. Tap to open TerraGuard.")
        .setPriority(NotificationCompat.PRIORITY_MAX)
        .setOngoing(true)
        .setContentIntent(openIntent)
        .build()

    runCatching {
      startForeground(TerraGuardNotifications.ALARM_NOTIFICATION_ID, notification)
    }.onFailure { error ->
      Log.w("TerraGuardAlarmService", "startForeground failed: ${error.message}")
      TerraGuardNotifications.postAlertNotification(
        this,
        TerraGuardNotifications.ALARM_NOTIFICATION_ID,
        "TerraGuard Remote Alarm Active",
        "Emergency anti-theft siren is sounding. Tap to open TerraGuard.",
      )
    }

    AlarmController.startSiren(this, overrideSilent)
    return START_STICKY
  }

  override fun onDestroy() {
    AlarmController.stopSiren(this)
    super.onDestroy()
  }

  companion object {
    const val ACTION_START = "com.example.action.START_ALARM"
    const val ACTION_STOP = "com.example.action.STOP_ALARM"
    const val EXTRA_OVERRIDE_SILENT = "extra_override_silent"

    fun start(context: Context, overrideSilent: Boolean) {
      val intent =
        Intent(context, TerraGuardAlarmService::class.java).apply {
          action = ACTION_START
          putExtra(EXTRA_OVERRIDE_SILENT, overrideSilent)
        }
      val started =
        runCatching {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
          } else {
            context.startService(intent)
          }
          true
        }.getOrElse { error ->
          Log.w("TerraGuardAlarmService", "Foreground alarm launch blocked, falling back to direct siren: ${error.message}")
          false
        }

      if (!started) {
        AlarmController.startSiren(context, overrideSilent)
        TerraGuardNotifications.postAlertNotification(
          context,
          TerraGuardNotifications.ALARM_NOTIFICATION_ID,
          "TerraGuard Remote Alarm Active",
          "Emergency siren triggered (Silent Mode Override ${if (overrideSilent) "ON" else "OFF"}). Open TerraGuard to disarm.",
        )
      }
    }

    fun stop(context: Context) {
      val intent =
        Intent(context, TerraGuardAlarmService::class.java).apply {
          action = ACTION_STOP
        }
      runCatching { context.startService(intent) }
      AlarmController.stopSiren(context)
    }
  }
}

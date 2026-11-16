package com.example.security

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

object TerraGuardNotifications {
  const val CHANNEL_MONITORING = "terraguard_monitoring"
  const val CHANNEL_SECURITY_ALERTS = "terraguard_alerts"
  const val CHANNEL_ALARM = "terraguard_alarm"

  const val MONITORING_NOTIFICATION_ID = 7001
  const val ALARM_NOTIFICATION_ID = 7002

  fun ensureChannels(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    val monitoring =
      NotificationChannel(
        CHANNEL_MONITORING,
        "TerraGuard Shield Monitoring",
        NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = "Keeps GPS tracking, connectivity, and battery telemetry active."
      }

    val alerts =
      NotificationChannel(
        CHANNEL_SECURITY_ALERTS,
        "Security & Geofence Alerts",
        NotificationManager.IMPORTANCE_HIGH,
      ).apply {
        description = "Alerts for geofence exits, SIM changes, intruder attempts, and reconnects."
      }

    val alarm =
      NotificationChannel(
        CHANNEL_ALARM,
        "Remote Siren Alarm",
        NotificationManager.IMPORTANCE_HIGH,
      ).apply {
        description = "Active anti-theft alarm notification."
      }

    manager.createNotificationChannels(listOf(monitoring, alerts, alarm))
  }

  fun postAlertNotification(
    context: Context,
    notificationId: Int,
    title: String,
    body: String,
  ) {
    ensureChannels(context)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val granted =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
          PackageManager.PERMISSION_GRANTED
      if (!granted) return
    }

    val openIntent =
      PendingIntent.getActivity(
        context,
        notificationId,
        Intent(context, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val notification: Notification =
      NotificationCompat.Builder(context, CHANNEL_SECURITY_ALERTS)
        .setSmallIcon(R.drawable.ic_terraguard_shield)
        .setContentTitle(title)
        .setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setContentIntent(openIntent)
        .build()

    runCatching { NotificationManagerCompat.from(context).notify(notificationId, notification) }
  }
}

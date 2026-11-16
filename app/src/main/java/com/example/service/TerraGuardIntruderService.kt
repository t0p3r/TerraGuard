package com.example.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.MainActivity
import com.example.R
import com.example.security.IntruderPipeline
import com.example.security.TerraGuardNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TerraGuardIntruderService : Service(), LifecycleOwner {

  private lateinit var lifecycleRegistry: LifecycleRegistry
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val requestQueue = kotlinx.coroutines.channels.Channel<Triple<String, Int, Boolean>>(
    capacity = kotlinx.coroutines.channels.Channel.UNLIMITED
  )
  private var workerJob: Job? = null

  override val lifecycle: Lifecycle
    get() = lifecycleRegistry

  override fun onCreate() {
    super.onCreate()
    lifecycleRegistry = LifecycleRegistry(this)
    lifecycleRegistry.currentState = Lifecycle.State.CREATED
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val reason =
      intent?.getStringExtra(EXTRA_REASON) ?: "Repeated Failed Lockscreen PIN Attempts"
    val attemptCount = intent?.getIntExtra(EXTRA_ATTEMPT_COUNT, 1) ?: 1
    val fromUnlock = intent?.getBooleanExtra(EXTRA_FROM_UNLOCK, false) ?: false

    promoteToForeground()
    lifecycleRegistry.currentState = Lifecycle.State.STARTED
    lifecycleRegistry.currentState = Lifecycle.State.RESUMED

    requestQueue.trySend(Triple(reason, attemptCount, fromUnlock))

    if (workerJob?.isActive != true) {
      workerJob =
        serviceScope.launch {
          try {
            while (true) {
              val next = requestQueue.tryReceive().getOrNull() ?: break
              runCatching {
                IntruderPipeline.execute(
                  context = applicationContext,
                  lifecycleOwner = this@TerraGuardIntruderService,
                  reason = next.first,
                  attemptCount = next.second,
                  fromUnlock = next.third,
                )
              }.onFailure { err ->
                Log.e(TAG, "Queued intruder capture failed", err)
              }
            }
          } finally {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
          }
        }
    }

    return START_NOT_STICKY
  }

  override fun onTimeout(startId: Int, fgsType: Int) {
    workerJob?.cancel()
    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    stopSelf()
  }

  private fun promoteToForeground() {
    TerraGuardNotifications.ensureChannels(this)
    val openIntent =
      PendingIntent.getActivity(
        this,
        NOTIFICATION_ID,
        Intent(this, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val notification =
      NotificationCompat.Builder(this, TerraGuardNotifications.CHANNEL_SECURITY_ALERTS)
        .setSmallIcon(R.drawable.ic_terraguard_shield)
        .setContentTitle("TerraGuard Intruder Capture")
        .setContentText("Recording security photo, GPS coordinates, and email alert...")
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setOngoing(true)
        .setContentIntent(openIntent)
        .build()

    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startForeground(
          NOTIFICATION_ID,
          notification,
          ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
        )
      } else {
        startForeground(NOTIFICATION_ID, notification)
      }
    }.onFailure { error ->
      Log.w(TAG, "shortService foreground promotion fallback: ${error.message}")
    }
  }

  override fun onDestroy() {
    if (::lifecycleRegistry.isInitialized) {
      lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
    serviceScope.cancel()
    super.onDestroy()
  }

  companion object {
    private const val TAG = "TerraGuardIntruderSvc"
    private const val NOTIFICATION_ID = 7003
    const val ACTION_CAPTURE_INTRUDER = "com.example.action.CAPTURE_INTRUDER"
    const val EXTRA_REASON = "extra_reason"
    const val EXTRA_ATTEMPT_COUNT = "extra_attempt_count"
    const val EXTRA_FROM_UNLOCK = "extra_from_unlock"

    fun start(
      context: Context,
      reason: String,
      attemptCount: Int,
      fromUnlock: Boolean = false,
    ): Boolean {
      val intent =
        Intent(context, TerraGuardIntruderService::class.java).apply {
          action = ACTION_CAPTURE_INTRUDER
          putExtra(EXTRA_REASON, reason)
          putExtra(EXTRA_ATTEMPT_COUNT, attemptCount)
          putExtra(EXTRA_FROM_UNLOCK, fromUnlock)
        }
      return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          context.startForegroundService(intent)
        } else {
          context.startService(intent)
        }
        true
      }.getOrElse { error ->
        Log.w(TAG, "Could not launch intruder foreground service: ${error.message}")
        false
      }
    }
  }
}

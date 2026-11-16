package com.example.service

import android.Manifest
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.MainActivity
import com.example.R
import com.example.data.local.TerraGuardPreferences
import com.example.receiver.LockscreenIntruderTracker
import com.example.security.IntruderPipeline
import com.example.security.TerraGuardNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class MonitorServiceStatus(
  val isRunning: Boolean = false,
  val isCollectorActive: Boolean = false,
  val hasCameraFgs: Boolean = false,
)

data class InProcessIntruderRequest(
  val reason: String,
  val attemptCount: Int,
  val fromUnlock: Boolean,
)

class TerraGuardMonitoringService : Service(), LifecycleOwner {

  private lateinit var lifecycleRegistry: LifecycleRegistry
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var unlockReceiverRegistered = false
  private var keyguardWatchJob: Job? = null

  override val lifecycle: Lifecycle
    get() = lifecycleRegistry

  private val userPresentReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        val appContext = applicationContext
        when (action) {
          Intent.ACTION_SCREEN_ON -> startKeyguardWatcher()
          Intent.ACTION_SCREEN_OFF -> {
            keyguardWatchJob?.cancel()
            keyguardWatchJob = null
            serviceScope.launch(Dispatchers.IO) {
              runCatching {
                val dpmCount = LockscreenIntruderTracker.readOsFailedAttempts(appContext)
                if (dpmCount > LockscreenIntruderTracker.lastObservedDpmCount) {
                  delay(300L)
                  LockscreenIntruderTracker.onWrongPinObserved(appContext, fromBroadcast = false)
                }
              }
            }
          }
          Intent.ACTION_USER_PRESENT -> {
            keyguardWatchJob?.cancel()
            keyguardWatchJob = null
            LockscreenIntruderTracker.resetObservedCount()
            serviceScope.launch {
              runCatching {
                val settings = TerraGuardPreferences(appContext).settingsFlow.first()
                if (settings.pendingIntruderCapture && settings.isProtected) {
                  lifecycleRegistry.currentState = Lifecycle.State.RESUMED
                  IntruderPipeline.execute(
                    context = appContext,
                    lifecycleOwner = this@TerraGuardMonitoringService,
                    reason = "Unlock after Failed Lockscreen PIN (${settings.osFailedPinCount.coerceAtLeast(1)} attempts)",
                    attemptCount = settings.osFailedPinCount.coerceAtLeast(1),
                    fromUnlock = true,
                  )
                }
              }.onFailure { err ->
                Log.e(TAG, "Unlock capture failed in userPresentReceiver", err)
              }
            }
          }
        }
      }
    }

  private fun startKeyguardWatcher() {
    if (keyguardWatchJob?.isActive == true) return
    val appContext = applicationContext
    val keyguard = appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return
    val power = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    if (!keyguard.isKeyguardLocked) return

    keyguardWatchJob =
      serviceScope.launch(Dispatchers.IO) {
        while (isActive && keyguard.isKeyguardLocked && (power?.isInteractive != false)) {
          runCatching {
            val dpmCount = LockscreenIntruderTracker.readOsFailedAttempts(appContext)
            if (dpmCount > LockscreenIntruderTracker.lastObservedDpmCount) {
              // Give system ACTION_PASSWORD_FAILED broadcast a brief head start before fallback triggers.
              delay(350L)
              LockscreenIntruderTracker.onWrongPinObserved(appContext, fromBroadcast = false)
            }
          }
          delay(900L)
        }
      }
  }

  override fun onCreate() {
    super.onCreate()
    lifecycleRegistry = LifecycleRegistry(this)
    lifecycleRegistry.currentState = Lifecycle.State.CREATED
    updateStatus(isRunning = true)

    val filter =
      IntentFilter().apply {
        addAction(Intent.ACTION_USER_PRESENT)
        addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_SCREEN_OFF)
      }
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        registerReceiver(userPresentReceiver, filter, Context.RECEIVER_EXPORTED)
      } else {
        registerReceiver(userPresentReceiver, filter)
      }
      unlockReceiverRegistered = true
    }

    // Listen in-process for wrong-PIN / unlock triggers (isolated per-request so one error never kills collector)
    serviceScope.launch {
      updateStatus(isCollectorActive = true)
      try {
        inProcessCaptureFlow.collect { req ->
          lifecycleRegistry.currentState = Lifecycle.State.RESUMED
          serviceScope.launch {
            runCatching {
              IntruderPipeline.execute(
                context = applicationContext,
                lifecycleOwner = this@TerraGuardMonitoringService,
                reason = req.reason,
                attemptCount = req.attemptCount,
                fromUnlock = req.fromUnlock,
              )
            }.onFailure { err ->
              Log.e(TAG, "IntruderPipeline execution error in MonitoringService", err)
            }
          }
        }
      } finally {
        updateStatus(isCollectorActive = false)
      }
    }
    startKeyguardWatcher()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      updateStatus(isRunning = false, hasCameraFgs = false)
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return START_NOT_STICKY
    }

    promoteForegroundSafely()
    lifecycleRegistry.currentState = Lifecycle.State.STARTED
    lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    startKeyguardWatcher()

    if (intent?.action == ACTION_CAPTURE_INTRUDER) {
      val reason =
        intent.getStringExtra(EXTRA_REASON) ?: "Android Lockscreen Failed PIN Attempts"
      val attemptCount = intent.getIntExtra(EXTRA_ATTEMPT_COUNT, 1)
      val fromUnlock = intent.getBooleanExtra(EXTRA_FROM_UNLOCK, false)
      serviceScope.launch {
        IntruderPipeline.execute(
          context = applicationContext,
          lifecycleOwner = this@TerraGuardMonitoringService,
          reason = reason,
          attemptCount = attemptCount,
          fromUnlock = fromUnlock,
        )
      }
    }

    return START_STICKY
  }

  private fun promoteForegroundSafely() {
    TerraGuardNotifications.ensureChannels(this)
    val openIntent =
      PendingIntent.getActivity(
        this,
        7001,
        Intent(this, MainActivity::class.java).apply {
          this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val notification =
      NotificationCompat.Builder(this, TerraGuardNotifications.CHANNEL_MONITORING)
        .setSmallIcon(R.drawable.ic_terraguard_shield)
        .setContentTitle("TerraGuard Shield Active")
        .setContentText("Monitoring GPS, camera intruder watch, SMS alarm & connectivity.")
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .setContentIntent(openIntent)
        .build()

    val hasLocation =
      ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
          PackageManager.PERMISSION_GRANTED
    val hasCamera =
      ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      var fullType = 0
      if (hasLocation) fullType = fullType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
      val wantCameraFgs = hasCamera && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
      if (wantCameraFgs) {
        fullType = fullType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
      }
      val promotedWithCamera =
        runCatching {
          if (fullType != 0) {
            startForeground(TerraGuardNotifications.MONITORING_NOTIFICATION_ID, notification, fullType)
          } else {
            startForeground(TerraGuardNotifications.MONITORING_NOTIFICATION_ID, notification)
          }
          wantCameraFgs
        }.getOrElse { err ->
          Log.w(TAG, "location|camera FGS promotion fallback to location-only: ${err.message}")
          false
        }
      updateStatus(hasCameraFgs = promotedWithCamera)

      if (!promotedWithCamera && hasLocation) {
        runCatching {
          startForeground(
            TerraGuardNotifications.MONITORING_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
          )
        }
      } else if (!promotedWithCamera) {
        runCatching {
          startForeground(TerraGuardNotifications.MONITORING_NOTIFICATION_ID, notification)
        }
      }
    } else {
      updateStatus(hasCameraFgs = hasCamera)
      startForeground(TerraGuardNotifications.MONITORING_NOTIFICATION_ID, notification)
    }
  }

  override fun onDestroy() {
    keyguardWatchJob?.cancel()
    keyguardWatchJob = null
    updateStatus(isRunning = false, isCollectorActive = false, hasCameraFgs = false)
    if (unlockReceiverRegistered) {
      runCatching { unregisterReceiver(userPresentReceiver) }
      unlockReceiverRegistered = false
    }
    if (::lifecycleRegistry.isInitialized) {
      lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
    serviceScope.cancel()
    super.onDestroy()
  }

  companion object {
    private const val TAG = "TerraGuardMonSvc"
    const val ACTION_START = "com.example.action.START_MONITORING"
    const val ACTION_STOP = "com.example.action.STOP_MONITORING"
    const val ACTION_CAPTURE_INTRUDER = "com.example.action.MONITOR_CAPTURE_INTRUDER"
    const val EXTRA_REASON = "extra_reason"
    const val EXTRA_ATTEMPT_COUNT = "extra_attempt_count"
    const val EXTRA_FROM_UNLOCK = "extra_from_unlock"

    private val inProcessCaptureFlow =
      MutableSharedFlow<InProcessIntruderRequest>(extraBufferCapacity = 32)

    private val _statusFlow = MutableStateFlow(MonitorServiceStatus())
    val statusFlow: StateFlow<MonitorServiceStatus> = _statusFlow.asStateFlow()

    @Volatile var isRunning: Boolean = false
      private set
    @Volatile var isCollectorActive: Boolean = false
      private set
    @Volatile var hasCameraFgs: Boolean = false
      private set

    private fun updateStatus(
      isRunning: Boolean = this.isRunning,
      isCollectorActive: Boolean = this.isCollectorActive,
      hasCameraFgs: Boolean = this.hasCameraFgs,
    ) {
      this.isRunning = isRunning
      this.isCollectorActive = isCollectorActive
      this.hasCameraFgs = hasCameraFgs
      _statusFlow.value = MonitorServiceStatus(isRunning, isCollectorActive, hasCameraFgs)
    }

    fun start(context: Context) {
      val intent =
        Intent(context, TerraGuardMonitoringService::class.java).apply {
          action = ACTION_START
        }
      runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          context.startForegroundService(intent)
        } else {
          context.startService(intent)
        }
      }
    }

    fun triggerIntruderCapture(
      @Suppress("UNUSED_PARAMETER") context: Context,
      reason: String,
      attemptCount: Int,
      fromUnlock: Boolean,
    ): Boolean {
      if (!isRunning || !isCollectorActive) return false
      return inProcessCaptureFlow.tryEmit(
        InProcessIntruderRequest(
          reason = reason,
          attemptCount = attemptCount,
          fromUnlock = fromUnlock,
        )
      )
    }

    fun stop(context: Context) {
      val intent =
        Intent(context, TerraGuardMonitoringService::class.java).apply {
          action = ACTION_STOP
        }
      runCatching { context.stopService(intent) }
    }
  }
}

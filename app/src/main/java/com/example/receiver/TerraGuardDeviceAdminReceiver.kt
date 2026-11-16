package com.example.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import com.example.data.local.TerraGuardPreferences
import com.example.security.TerraGuardNotifications
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

object LockscreenFailureBus {
  private val _thresholdReached = MutableSharedFlow<Int>(extraBufferCapacity = 8)
  val thresholdReached: SharedFlow<Int> = _thresholdReached.asSharedFlow()

  fun emit(attemptCount: Int) {
    _thresholdReached.tryEmit(attemptCount)
  }
}

class TerraGuardDeviceAdminReceiver : DeviceAdminReceiver() {
  override fun onEnabled(context: Context, intent: Intent) {
    CoroutineScope(Dispatchers.IO).launch {
      TerraGuardPreferences(context.applicationContext)
        .setLastIntruderStatus("Lock Admin activated at ${timeNow()}")
    }
    TerraGuardNotifications.postAlertNotification(
      context,
      7201,
      "TerraGuard Lock Monitor Active",
      "OS failed unlock attempt detection is enabled.",
    )
  }

  override fun onDisabled(context: Context, intent: Intent) {
    CoroutineScope(Dispatchers.IO).launch {
      TerraGuardPreferences(context.applicationContext)
        .setLastIntruderStatus("Lock Admin deactivated at ${timeNow()}")
    }
    TerraGuardNotifications.postAlertNotification(
      context,
      7202,
      "TerraGuard Lock Monitor Disabled",
      "OS failed unlock monitoring was deactivated.",
    )
  }

  override fun onReceive(context: Context, intent: Intent) {
    when (intent.action) {
      ACTION_PASSWORD_FAILED -> handlePasswordFailed(context)
      ACTION_PASSWORD_SUCCEEDED -> handlePasswordSucceeded(context)
      else -> super.onReceive(context, intent)
    }
  }

  @Suppress("OVERRIDE_DEPRECATION")
  override fun onPasswordSucceeded(context: Context, intent: Intent) = Unit

  override fun onPasswordSucceeded(context: Context, intent: Intent, user: UserHandle) = Unit

  @Suppress("OVERRIDE_DEPRECATION")
  override fun onPasswordFailed(context: Context, intent: Intent) = Unit

  override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) = Unit

  private fun handlePasswordSucceeded(context: Context) {
    val appContext = context.applicationContext
    val pending = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
      try {
        LockscreenIntruderTracker.onPasswordSucceeded(appContext)
      } finally {
        pending.finish()
      }
    }
  }

  private fun handlePasswordFailed(context: Context) {
    val appContext = context.applicationContext
    val pending = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
      try {
        LockscreenIntruderTracker.onWrongPinObserved(appContext, fromBroadcast = true)
      } finally {
        pending.finish()
      }
    }
  }

  private fun timeNow(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
}

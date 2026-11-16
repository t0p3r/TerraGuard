package com.example.security

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.example.receiver.TerraGuardDeviceAdminReceiver
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BatterySnapshot(
  val percent: Int = 100,
  val isCharging: Boolean = false,
  val isLow: Boolean = false,
)

data class NetworkSnapshot(
  val isOnline: Boolean = true,
  val networkType: String = "Unknown",
  val lastTransitionMillis: Long = System.currentTimeMillis(),
)

data class SimSnapshot(
  val simStateLabel: String = "Unknown",
  val carrierName: String = "Unknown",
  val countryIso: String = "",
  val activeSlotCount: Int = 0,
  val fingerprint: String = "",
  val permissionGranted: Boolean = false,
  val isSimPresent: Boolean = false,
)

class DeviceTelemetryManager(context: Context) {
  private val appContext = context.applicationContext
  private val connectivityManager =
    appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  private val batteryManager =
    appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
  private val telephonyManager =
    appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
  private val devicePolicyManager =
    appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager

  private val _battery = MutableStateFlow(readBatterySnapshot())
  val battery: StateFlow<BatterySnapshot> = _battery.asStateFlow()

  private val _network = MutableStateFlow(readCurrentNetworkSnapshot())
  val network: StateFlow<NetworkSnapshot> = _network.asStateFlow()

  private val _sim = MutableStateFlow(readSimSnapshot())
  val sim: StateFlow<SimSnapshot> = _sim.asStateFlow()

  private var onInternetReconnectedCallback: ((NetworkSnapshot) -> Unit)? = null
  private var onInternetLostCallback: ((NetworkSnapshot) -> Unit)? = null
  private var registered = false

  private val batteryReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        _battery.value = readBatterySnapshot(intent)
      }
    }

  private val simReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        _sim.value = readSimSnapshot()
      }
    }

  private val networkCallback =
    object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        updateNetworkState(isNowOnline = true)
      }

      override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
        val hasNet =
          capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
              Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
        updateNetworkState(isNowOnline = hasNet, capabilities = capabilities)
      }

      override fun onLost(network: Network) {
        val current = readCurrentNetworkSnapshot()
        updateNetworkState(isNowOnline = current.isOnline, overrideType = current.networkType)
      }
    }

  fun startMonitoring(
    onInternetReconnected: ((NetworkSnapshot) -> Unit)? = null,
    onInternetLost: ((NetworkSnapshot) -> Unit)? = null,
  ) {
    onInternetReconnectedCallback = onInternetReconnected
    onInternetLostCallback = onInternetLost
    if (registered) return
    registered = true

    _battery.value = readBatterySnapshot()
    _network.value = readCurrentNetworkSnapshot()
    _sim.value = readSimSnapshot()

    val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      appContext.registerReceiver(batteryReceiver, batteryFilter, Context.RECEIVER_EXPORTED)
    } else {
      appContext.registerReceiver(batteryReceiver, batteryFilter)
    }

    val simFilter = IntentFilter("android.intent.action.SIM_STATE_CHANGED")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      appContext.registerReceiver(simReceiver, simFilter, Context.RECEIVER_EXPORTED)
    } else {
      appContext.registerReceiver(simReceiver, simFilter)
    }

    runCatching { connectivityManager.registerDefaultNetworkCallback(networkCallback) }
  }

  fun stopMonitoring() {
    if (!registered) return
    registered = false
    runCatching { appContext.unregisterReceiver(batteryReceiver) }
    runCatching { appContext.unregisterReceiver(simReceiver) }
    runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
  }

  fun refreshAll() {
    _battery.value = readBatterySnapshot()
    _network.value = readCurrentNetworkSnapshot()
    _sim.value = readSimSnapshot()
  }

  fun isDeviceAdminActive(): Boolean {
    val adminComponent = ComponentName(appContext, TerraGuardDeviceAdminReceiver::class.java)
    return devicePolicyManager?.isAdminActive(adminComponent) == true
  }

  fun readBatterySnapshot(stickyIntent: Intent? = null): BatterySnapshot {
    val intent =
      stickyIntent
        ?: appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val percent =
      if (level >= 0 && scale > 0) {
        ((level * 100f) / scale).toInt().coerceIn(0, 100)
      } else {
        batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
      }
    val isCharging =
      status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL
    return BatterySnapshot(percent = percent, isCharging = isCharging, isLow = percent <= 15 && !isCharging)
  }

  fun readCurrentNetworkSnapshot(): NetworkSnapshot {
    val activeNetwork = connectivityManager.activeNetwork
    val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
    if (capabilities == null) {
      return NetworkSnapshot(isOnline = false, networkType = "Offline")
    }
    val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    val type =
      when {
        !hasInternet -> "Offline"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Connected"
      }
    return NetworkSnapshot(isOnline = hasInternet, networkType = type)
  }

  @SuppressLint("MissingPermission", "HardwareIds")
  fun readSimSnapshot(): SimSnapshot {
    val hasPermission =
      ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_PHONE_STATE) ==
        PackageManager.PERMISSION_GRANTED

    val tm = telephonyManager
    val simState = tm?.simState ?: TelephonyManager.SIM_STATE_UNKNOWN
    val stateLabel =
      when (simState) {
        TelephonyManager.SIM_STATE_READY -> "Ready"
        TelephonyManager.SIM_STATE_ABSENT -> "No SIM"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN Locked"
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK Locked"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "Network Locked"
        else -> "Unknown"
      }

    val carrierFromTm = tm?.simOperatorName?.takeIf { it.isNotBlank() } ?: ""
    val countryFromTm = tm?.simCountryIso?.uppercase()?.takeIf { it.isNotBlank() } ?: ""

    var slotInfo = ""
    var activeCount = 0
    var subCarrier = ""
    if (hasPermission) {
      runCatching {
        val subManager =
          appContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
        val list = subManager?.activeSubscriptionInfoList.orEmpty()
        activeCount = list.size
        subCarrier = list.firstOrNull()?.carrierName?.toString().orEmpty()
        slotInfo =
          list.joinToString("|") { info ->
            "${info.simSlotIndex}:${info.subscriptionId}:${info.carrierName}:${info.countryIso}"
          }
      }
    }

    val effectiveCarrier =
      subCarrier.ifBlank { carrierFromTm }.ifBlank { if (simState == TelephonyManager.SIM_STATE_ABSENT) "No SIM" else "Unavailable" }
    val rawDescriptor = "$stateLabel|$effectiveCarrier|$countryFromTm|$activeCount|$slotInfo"
    val digest =
      MessageDigest.getInstance("SHA-256")
        .digest(rawDescriptor.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(24)

    return SimSnapshot(
      simStateLabel = stateLabel,
      carrierName = effectiveCarrier,
      countryIso = countryFromTm,
      activeSlotCount = activeCount,
      fingerprint = "sim_$digest",
      permissionGranted = hasPermission,
      isSimPresent = simState == TelephonyManager.SIM_STATE_READY || activeCount > 0,
    )
  }

  private fun updateNetworkState(
    isNowOnline: Boolean,
    capabilities: NetworkCapabilities? = null,
    overrideType: String? = null,
  ) {
    val wasOnline = _network.value.isOnline
    val type =
      overrideType
        ?: when {
          !isNowOnline -> "Offline"
          capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
          capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cellular"
          capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
          else -> "Connected"
        }
    val updated =
      NetworkSnapshot(
        isOnline = isNowOnline,
        networkType = type,
        lastTransitionMillis = System.currentTimeMillis(),
      )
    _network.value = updated

    if (wasOnline && !isNowOnline) {
      onInternetLostCallback?.invoke(updated)
    } else if (!wasOnline && isNowOnline) {
      onInternetReconnectedCallback?.invoke(updated)
    }
  }
}

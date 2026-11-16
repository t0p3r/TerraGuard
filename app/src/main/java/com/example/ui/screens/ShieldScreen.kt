package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.TerraGuardSettingsState
import com.example.data.model.DeviceStatus
import com.example.security.BatterySnapshot
import com.example.security.GpsFix
import com.example.security.NetworkSnapshot
import com.example.security.SimSnapshot
import com.example.ui.UiState
import com.example.ui.components.ActiveAlarmBanner
import com.example.ui.components.StatusBadge
import com.example.ui.components.TelemetryTile
import com.example.ui.components.formatCoords
import com.example.ui.components.formatTimestamp

@Composable
fun ShieldScreen(
  currentDeviceId: String,
  settings: TerraGuardSettingsState,
  battery: BatterySnapshot,
  network: NetworkSnapshot,
  sim: SimSnapshot,
  currentFix: GpsFix?,
  isCapturingGps: Boolean,
  isAlarmRinging: Boolean,
  isDeviceAdminActive: Boolean,
  hasDndAccess: Boolean,
  pendingOfflineCount: Int,
  devicesState: UiState<List<DeviceStatus>>,
  onToggleProtection: (Boolean) -> Unit,
  onCaptureGpsNow: () -> Unit,
  onTriggerAlarm: (deviceId: String, active: Boolean) -> Unit,
  onDisarmLocalAlarmClick: () -> Unit,
  onSetAlarmOverrideSilent: (Boolean) -> Unit,
  onRegisterBaselineSim: () -> Unit,
  onRequestDeviceAdmin: () -> Unit,
  onOpenDndSettings: () -> Unit,
  onOpenAppSettings: () -> Unit,
  onOpenInMaps: (Double, Double, String) -> Unit,
  onPermissionsUpdated: () -> Unit,
) {
  val context = LocalContext.current
  var permRefreshTick by remember { mutableStateOf(0) }

  val hasLocationPerm =
    remember(permRefreshTick) {
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    }
  val hasCameraPerm =
    remember(permRefreshTick) {
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    }
  val hasPhonePerm =
    remember(permRefreshTick) {
      ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
        PackageManager.PERMISSION_GRANTED
    }
  val hasSmsReceivePerm =
    remember(permRefreshTick) {
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
        PackageManager.PERMISSION_GRANTED
    }
  val hasSmsSendPerm =
    remember(permRefreshTick) {
      ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
        PackageManager.PERMISSION_GRANTED
    }
  val hasNotifPerm =
    remember(permRefreshTick) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
          PackageManager.PERMISSION_GRANTED
      } else true
    }

  val runtimePermLauncher =
    rememberLauncherForActivityResult(
      ActivityResultContracts.RequestMultiplePermissions()
    ) {
      permRefreshTick++
      onPermissionsUpdated()
    }

  val missingCorePerms =
    buildList {
      if (!hasLocationPerm) {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
      }
      if (!hasCameraPerm) add(Manifest.permission.CAMERA)
      if (!hasPhonePerm) add(Manifest.permission.READ_PHONE_STATE)
      if (!hasSmsReceivePerm) add(Manifest.permission.RECEIVE_SMS)
      if (!hasSmsSendPerm) add(Manifest.permission.SEND_SMS)
      if (!hasNotifPerm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
      }
    }

  val lastSaved = settings.lastKnownLocation
  val displayLat = currentFix?.latitude ?: lastSaved.latitude
  val displayLng = currentFix?.longitude ?: lastSaved.longitude
  val displayAcc = currentFix?.accuracyMeters ?: lastSaved.accuracyMeters
  val displayTime = currentFix?.timestampMillis ?: lastSaved.recordedAtMillis

  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      if (isAlarmRinging) {
        item {
          ActiveAlarmBanner(onDisarmClicked = onDisarmLocalAlarmClick)
        }
      }

      // Hero Shield Card
      item {
        Card(
          shape = RoundedCornerShape(24.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Box(modifier = Modifier.fillMaxWidth()) {
            Image(
              painter = painterResource(id = R.drawable.img_terraguard_hero),
              contentDescription = "TerraGuard radar background",
              modifier = Modifier.fillMaxWidth().height(168.dp),
              contentScale = ContentScale.Crop,
            )
            Box(
              modifier =
                Modifier.fillMaxWidth()
                  .height(168.dp)
                  .background(
                    Brush.verticalGradient(
                      listOf(Color(0x66070C16), Color(0xEA0B1320))
                    )
                  )
            )
            Column(
              modifier = Modifier.fillMaxWidth().padding(18.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                  Icon(
                    imageVector = Icons.Filled.Shield,
                    contentDescription = "Protection shield",
                    tint =
                      if (settings.isProtected) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(30.dp),
                  )
                  Column {
                    Text(
                      text = settings.deviceName,
                      style = MaterialTheme.typography.titleLarge,
                      color = Color.White,
                    )
                    Text(
                      text =
                        if (settings.isProtected) "Anti-Theft Shield Armed"
                        else "Shield Paused",
                      style = MaterialTheme.typography.bodySmall,
                      color = Color(0xFFCBD5E1),
                    )
                  }
                }
                Switch(
                  checked = settings.isProtected,
                  onCheckedChange = onToggleProtection,
                  modifier = Modifier.testTag("shield_arm_switch"),
                )
              }

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                Button(
                  onClick = onCaptureGpsNow,
                  enabled = !isCapturingGps,
                  modifier =
                    Modifier.weight(1f)
                      .minimumInteractiveComponentSize()
                      .testTag("capture_gps_button"),
                ) {
                  if (isCapturingGps) {
                    CircularProgressIndicator(
                      modifier = Modifier.size(18.dp),
                      color = MaterialTheme.colorScheme.onPrimary,
                      strokeWidth = 2.dp,
                    )
                  } else {
                    Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                  }
                  Spacer(modifier = Modifier.width(8.dp))
                  Text(if (isCapturingGps) "Locking GPS..." else "Locate Now")
                }

                Button(
                  onClick = {
                    if (isAlarmRinging) {
                      onDisarmLocalAlarmClick()
                    } else if (currentDeviceId.isNotBlank()) {
                      onTriggerAlarm(currentDeviceId, true)
                    }
                  },
                  colors =
                    ButtonDefaults.buttonColors(
                      containerColor =
                        if (isAlarmRinging) MaterialTheme.colorScheme.surface
                        else MaterialTheme.colorScheme.error,
                      contentColor =
                        if (isAlarmRinging) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onError,
                    ),
                  modifier =
                    Modifier.weight(1f)
                      .minimumInteractiveComponentSize()
                      .testTag("trigger_local_alarm_button"),
                ) {
                  Icon(
                    imageVector =
                      if (isAlarmRinging) Icons.Filled.VolumeOff
                      else Icons.Filled.NotificationsActive,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  Text(if (isAlarmRinging) "Silence" else "Sound Alarm")
                }
              }
            }
          }
        }
      }

      // Missing Permissions Strip
      if (missingCorePerms.isNotEmpty()) {
        item {
          Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.padding(14.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "Permissions Required (${missingCorePerms.size})",
                  style = MaterialTheme.typography.titleSmall,
                  color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                  text = "Enable GPS, Camera, SMS Remote Alarm, Phone State & Notifications for full protection.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
              }
              Spacer(modifier = Modifier.width(10.dp))
              Button(
                onClick = { runtimePermLauncher.launch(missingCorePerms.toTypedArray()) },
                modifier = Modifier.minimumInteractiveComponentSize().testTag("grant_permissions_button"),
              ) {
                Text("Grant")
              }
            }
          }
        }
      }

      val isAutoEmailReady =
        (settings.smtpEnabled && settings.smtpUsername.isNotBlank() && settings.smtpPassword.isNotBlank()) ||
          settings.emailWebhookUrl.trim().startsWith("https://")
      if (!isDeviceAdminActive || !isAutoEmailReady) {
        item {
          Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              modifier = Modifier.padding(14.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Text(
                text = "Action Needed for Automatic Intruder Capture & Email",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
              )
              if (!isDeviceAdminActive) {
                Text(
                  text = "• Tap \"Enable Lock Admin\" below so Android notifies TerraGuard on wrong lockscreen PIN attempts.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onErrorContainer,
                )
              }
              if (!isAutoEmailReady) {
                Text(
                  text = "• Open the Alerts tab and turn ON \"Direct Background Email (SMTP)\" with your Gmail + 16-char Google App Password so photos email automatically.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onErrorContainer,
                )
              }
            }
          }
        }
      }

      // Live Telemetry 2x2 Grid
      item {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            TelemetryTile(
              icon = Icons.Filled.GpsFixed,
              title = "Live GPS Fix",
              primaryValue = formatCoords(displayLat, displayLng),
              secondaryValue =
                if (displayTime > 0L) "±${displayAcc.toInt()}m • ${formatTimestamp(displayTime)}"
                else "Tap Locate Now to record coordinates",
              accentColor = MaterialTheme.colorScheme.secondary,
              modifier = Modifier.weight(1f),
              onClick =
                if (displayLat != 0.0 || displayLng != 0.0) {
                  { onOpenInMaps(displayLat, displayLng, settings.deviceName) }
                } else null,
            )
            TelemetryTile(
              icon = if (battery.isCharging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryStd,
              title = "Battery Monitor",
              primaryValue = "${battery.percent}%${if (battery.isCharging) " • Charging" else ""}",
              secondaryValue =
                if (battery.isLow) "Low battery alert active"
                else "Included with every GPS update",
              accentColor =
                if (battery.isLow) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
              modifier = Modifier.weight(1f),
            )
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            TelemetryTile(
              icon = if (network.isOnline) Icons.Filled.Wifi else Icons.Filled.CloudOff,
              title = "Internet Monitor",
              primaryValue = if (network.isOnline) "Online (${network.networkType})" else "Offline Mode",
              secondaryValue =
                if (pendingOfflineCount > 0) "$pendingOfflineCount alert(s) queued for reconnect"
                else "Reports reconnects automatically",
              accentColor =
                if (network.isOnline) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.tertiary,
              modifier = Modifier.weight(1f),
            )
            TelemetryTile(
              icon = Icons.Filled.SimCard,
              title = "SIM Watch",
              primaryValue = "${sim.carrierName} (${sim.simStateLabel})",
              secondaryValue =
                if (settings.baselineSimFingerprint.isBlank()) "Baseline not registered yet"
                else if (sim.fingerprint == settings.baselineSimFingerprint) "Matches baseline SIM"
                else "SIM change detected!",
              accentColor =
                if (settings.baselineSimFingerprint.isNotBlank() && sim.fingerprint != settings.baselineSimFingerprint)
                  MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.secondary,
              modifier = Modifier.weight(1f),
            )
          }
        }
      }

      // Last Known Location Offline Snapshot Card
      item {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
          shape = RoundedCornerShape(18.dp),
          modifier =
            Modifier.fillMaxWidth()
              .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp)),
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text("Last Known Location Vault", style = MaterialTheme.typography.titleMedium)
              StatusBadge(
                label =
                  if (lastSaved.savedBeforeOffline) "PRE-OFFLINE SAVED"
                  else if (lastSaved.hasValidFix) "CACHED"
                  else "PENDING",
                containerColor =
                  if (lastSaved.savedBeforeOffline) MaterialTheme.colorScheme.tertiaryContainer
                  else MaterialTheme.colorScheme.secondaryContainer,
                contentColor =
                  if (lastSaved.savedBeforeOffline) MaterialTheme.colorScheme.onTertiaryContainer
                  else MaterialTheme.colorScheme.onSecondaryContainer,
              )
            }
            Text(
              text =
                if (lastSaved.hasValidFix) {
                  "Lat ${String.format("%.6f", lastSaved.latitude)}, Lng ${String.format("%.6f", lastSaved.longitude)} (±${lastSaved.accuracyMeters.toInt()}m) • Battery ${lastSaved.batteryPercent}% • ${formatTimestamp(lastSaved.recordedAtMillis)}"
                } else {
                  "No cached GPS fix yet. Once locked, TerraGuard preserves it locally before the device goes offline."
                },
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (lastSaved.hasValidFix) {
              OutlinedButton(
                onClick = { onOpenInMaps(lastSaved.latitude, lastSaved.longitude, "Last Known Location") },
                modifier = Modifier.minimumInteractiveComponentSize().testTag("open_last_location_maps_button"),
              ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Open in Maps")
              }
            }
          }
        }
      }

      // Hardware Security Controls (SIM Baseline, Silent Override, OS Device Admin)
      item {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
          shape = RoundedCornerShape(18.dp),
          modifier =
            Modifier.fillMaxWidth()
              .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp)),
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text("Device Hardening Controls", style = MaterialTheme.typography.titleMedium)

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Icon(
                    imageVector = Icons.Filled.VolumeUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                  )
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("Silent Mode Alarm Override", style = MaterialTheme.typography.titleSmall)
                }
                Text(
                  text = "Routes siren through Android ALARM stream to ring even in silent/vibrate mode.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Switch(
                checked = settings.alarmOverrideSilent,
                onCheckedChange = onSetAlarmOverrideSilent,
                modifier = Modifier.testTag("silent_override_switch"),
              )
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              FilledTonalButton(
                onClick = onRegisterBaselineSim,
                modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("register_sim_button"),
              ) {
                Icon(Icons.Filled.SimCard, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Lock SIM Profile")
              }

              FilledTonalButton(
                onClick = onRequestDeviceAdmin,
                modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("device_admin_button"),
              ) {
                Icon(Icons.Filled.AdminPanelSettings, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (isDeviceAdminActive) "Admin Active" else "Enable Lock Admin")
              }
            }

            if (!hasDndAccess) {
              OutlinedButton(
                onClick = onOpenDndSettings,
                modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("dnd_access_button"),
              ) {
                Text("Grant Do Not Disturb Access (Recommended for Silent Override)")
              }
            }

            Surface(
              color = MaterialTheme.colorScheme.surfaceContainerHigh,
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text("SMS Remote Siren Trigger", style = MaterialTheme.typography.titleSmall)
                  Text(
                    text =
                      if (settings.smsRemoteEnabled) {
                        "Text \"${settings.smsAlarmKeyword}\" to ring even in silent mode, or \"${settings.smsLocateKeyword}\" for GPS."
                      } else {
                        "Disabled in Alerts & Settings tab."
                      },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Spacer(modifier = Modifier.width(8.dp))
                StatusBadge(
                  label =
                    when {
                      !settings.smsRemoteEnabled -> "OFF"
                      hasSmsReceivePerm -> "LISTENING"
                      else -> "NEEDS SMS"
                    },
                  containerColor =
                    if (settings.smsRemoteEnabled && hasSmsReceivePerm)
                      MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.tertiaryContainer,
                  contentColor =
                    if (settings.smsRemoteEnabled && hasSmsReceivePerm)
                      MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onTertiaryContainer,
                )
              }
            }

            val monStatus by com.example.service.TerraGuardMonitoringService.statusFlow.collectAsStateWithLifecycle()
            val monitorReady = monStatus.isRunning
            val camFgsReady = monStatus.hasCameraFgs
            Surface(
              color = MaterialTheme.colorScheme.surfaceContainerHigh,
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Lockscreen Intruder Watch", style = MaterialTheme.typography.titleSmall)
                    Text(
                      text =
                        "Wrong PINs: ${settings.osFailedPinCount}/${settings.failedPinThreshold} • Monitor: ${
                          when {
                            monitorReady && camFgsReady -> "Active (Cam FGS)"
                            monitorReady -> "Active (No Cam FGS)"
                            else -> "Standby"
                          }
                        } • Camera: ${if (hasCameraPerm) "OK" else "Missing"}",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (settings.lastIntruderStatus.isNotBlank()) {
                      Text(
                        text = "Last: ${settings.lastIntruderStatus}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                      )
                    }
                  }
                  Spacer(modifier = Modifier.width(8.dp))
                  StatusBadge(
                    label =
                      when {
                        !isDeviceAdminActive -> "NO ADMIN"
                        settings.pendingIntruderCapture -> "QUEUED"
                        hasCameraPerm -> "ARMED"
                        else -> "NO CAM"
                      },
                    containerColor =
                      if (isDeviceAdminActive && hasCameraPerm)
                        MaterialTheme.colorScheme.primaryContainer
                      else MaterialTheme.colorScheme.errorContainer,
                    contentColor =
                      if (isDeviceAdminActive && hasCameraPerm)
                        MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.error,
                  )
                }
                Text(
                  text = "Tip: If Last stays blank after a 4+ digit wrong phone PIN, set Battery → Unrestricted & enable Auto-start in App Info.",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                  onClick = onOpenAppSettings,
                  modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("shield_app_battery_button"),
                ) {
                  Text("Open App Battery & Auto-Start Settings")
                }
              }
            }
          }
        }
      }

      // Remote Devices Fleet Section (Multi-device Remote Locate & Alarm)
      item {
        Text(
          text = "Your Protected Devices (Remote Control)",
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.padding(top = 4.dp),
        )
      }

      when (devicesState) {
        is UiState.Loading -> {
          item {
            Box(modifier = Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
              CircularProgressIndicator()
            }
          }
        }
        is UiState.Error -> {
          item {
            Text(
              text = "Could not sync fleet: ${devicesState.message}",
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodyMedium,
            )
          }
        }
        is UiState.Success -> {
          if (devicesState.data.isEmpty()) {
            item {
              Text(
                text = "Registering this device with your Google account...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          } else {
            items(devicesState.data, key = { it.id }) { device ->
              val isThisDevice = device.id == currentDeviceId
              Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                shape = RoundedCornerShape(18.dp),
                modifier =
                  Modifier.fillMaxWidth()
                    .border(
                      width = if (device.remoteAlarmActive) 1.5.dp else 1.dp,
                      color =
                        if (device.remoteAlarmActive) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.outlineVariant,
                      shape = RoundedCornerShape(18.dp),
                    ),
              ) {
                Column(
                  modifier = Modifier.padding(14.dp),
                  verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                      Icon(
                        imageVector = Icons.Filled.PhoneAndroid,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                      )
                      Spacer(modifier = Modifier.width(8.dp))
                      Column {
                        Text(
                          text = device.deviceName + if (isThisDevice) " (This Phone)" else "",
                          style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                          text =
                            "${device.batteryPercent}% Battery • ${if (device.isOnline) device.networkType else "Offline"}",
                          style = MaterialTheme.typography.bodySmall,
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                      }
                    }
                    StatusBadge(
                      label = if (device.remoteAlarmActive) "ALARM ON" else if (device.isProtected) "ARMED" else "PAUSED",
                      containerColor =
                        if (device.remoteAlarmActive) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.primaryContainer,
                      contentColor =
                        if (device.remoteAlarmActive) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    )
                  }

                  Text(
                    text =
                      "GPS: ${formatCoords(device.latitude, device.longitude)} (±${device.accuracyMeters.toInt()}m) • ${formatTimestamp(device.lastLocationAtMillis)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )

                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    OutlinedButton(
                      onClick = { onOpenInMaps(device.latitude, device.longitude, device.deviceName) },
                      enabled = device.latitude != 0.0 || device.longitude != 0.0,
                      modifier = Modifier.weight(1f).minimumInteractiveComponentSize(),
                    ) {
                      Text("Locate")
                    }
                    Button(
                      onClick = { onTriggerAlarm(device.id, !device.remoteAlarmActive) },
                      colors =
                        ButtonDefaults.buttonColors(
                          containerColor =
                            if (device.remoteAlarmActive) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.error,
                        ),
                      modifier =
                        Modifier.weight(1f)
                          .minimumInteractiveComponentSize()
                          .testTag("remote_alarm_device_${device.id}"),
                    ) {
                      Text(if (device.remoteAlarmActive) "Stop Remote Alarm" else "Trigger Remote Alarm")
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

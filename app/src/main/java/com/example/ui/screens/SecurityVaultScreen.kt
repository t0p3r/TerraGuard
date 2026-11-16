package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.SecurityPhotoEntity
import com.example.data.local.TerraGuardSettingsState
import com.example.ui.components.SecurityPinKeypad
import com.example.ui.components.StatusBadge
import com.example.ui.components.formatCoords
import com.example.ui.components.formatTimestamp
import java.io.File

@Composable
fun SecurityVaultScreen(
  settings: TerraGuardSettingsState,
  isVaultUnlocked: Boolean,
  wrongPinAttempts: Int,
  isDeviceAdminActive: Boolean,
  photos: List<SecurityPhotoEntity>,
  onConfigurePin: (String) -> Unit,
  onSubmitPin: (String, LifecycleOwner) -> Unit,
  onLockVault: () -> Unit,
  onCaptureTestPhoto: (LifecycleOwner) -> Unit,
  onTestBackgroundCamera2: () -> Unit,
  onSharePhoto: (SecurityPhotoEntity) -> Unit,
  onDeletePhoto: (SecurityPhotoEntity) -> Unit,
  onRequestDeviceAdmin: () -> Unit,
  onOpenAppSettings: () -> Unit,
  onCameraPermissionGranted: () -> Unit,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  var selectedPhoto by remember { mutableStateOf<SecurityPhotoEntity?>(null) }

  var hasCameraPerm by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    )
  }
  val cameraPermLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      hasCameraPerm = granted
      if (granted) onCameraPermissionGranted()
    }

  if (!settings.isPinConfigured || !isVaultUnlocked) {
    Box(
      modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
      contentAlignment = Alignment.Center,
    ) {
      Column(
        modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
      ) {
        if (!settings.isPinConfigured) {
          Image(
            painter = painterResource(id = R.drawable.img_vault_shield),
            contentDescription = "Protected security gallery vault",
            modifier =
              Modifier.fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(20.dp)),
            contentScale = ContentScale.Crop,
          )
        }

        SecurityPinKeypad(
          title =
            if (!settings.isPinConfigured) "Create 4-Digit Security PIN"
            else "Security Gallery Locked",
          subtitle =
            if (!settings.isPinConfigured) {
              "Protects captured intruder photos & alarm disarming. ${settings.failedPinThreshold} wrong entries trigger CameraX capture."
            } else {
              "Enter your 4-digit Security PIN to inspect captured evidence."
            },
          wrongAttempts = wrongPinAttempts,
          maxAttempts = settings.failedPinThreshold,
          isSetupMode = !settings.isPinConfigured,
          onPinSubmitted = { pin ->
            if (!settings.isPinConfigured) {
              onConfigurePin(pin)
            } else {
              onSubmitPin(pin, lifecycleOwner)
            }
          },
        )
      }
    }
    return
  }

  // Unlocked Protected Security Gallery
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      item {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
          shape = RoundedCornerShape(20.dp),
          modifier =
            Modifier.fillMaxWidth()
              .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)),
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text("Protected Security Gallery", style = MaterialTheme.typography.titleLarge)
                Text(
                  text = "${photos.size} private capture(s) stored in app-isolated vault",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              OutlinedButton(
                onClick = onLockVault,
                modifier = Modifier.minimumInteractiveComponentSize().testTag("lock_vault_button"),
              ) {
                Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Lock")
              }
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              if (!hasCameraPerm) {
                Button(
                  onClick = { cameraPermLauncher.launch(Manifest.permission.CAMERA) },
                  modifier = Modifier.weight(1f).minimumInteractiveComponentSize(),
                ) {
                  Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("Grant Camera Permission")
                }
              } else {
                FilledTonalButton(
                  onClick = { onCaptureTestPhoto(lifecycleOwner) },
                  modifier =
                    Modifier.weight(1f)
                      .minimumInteractiveComponentSize()
                      .testTag("test_intruder_capture_button"),
                ) {
                  Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                  Spacer(modifier = Modifier.width(8.dp))
                  Text("Capture Test Evidence")
                }
              }
            }
            Surface(
              color =
                if (!isDeviceAdminActive || !settings.isProtected)
                  MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                Text(
                  text =
                    when {
                      !settings.isProtected -> "Shield is PAUSED — enable Anti-Theft Shield on Shield tab to monitor wrong PINs."
                      !isDeviceAdminActive -> "Phone Lockscreen Watch is OFF — tap \"Enable Lock Admin\" so Android notifies TerraGuard when a wrong lockscreen PIN (4+ digits) is entered."
                      else -> "Phone Lockscreen Watch ACTIVE • Threshold: ${settings.failedPinThreshold} wrong PIN(s)"
                    },
                  style = MaterialTheme.typography.bodySmall,
                  color =
                    if (!isDeviceAdminActive || !settings.isProtected)
                      MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (settings.lastIntruderStatus.isNotBlank()) {
                  Text(
                    text = "Last event: ${settings.lastIntruderStatus}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                  )
                }
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  if (!isDeviceAdminActive) {
                    OutlinedButton(
                      onClick = onRequestDeviceAdmin,
                      modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("vault_enable_admin_button"),
                    ) {
                      Text("Enable Lock Admin")
                    }
                  } else if (hasCameraPerm) {
                    OutlinedButton(
                      onClick = onTestBackgroundCamera2,
                      modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("vault_test_cam2_button"),
                    ) {
                      Text("Test Camera2")
                    }
                  }
                  OutlinedButton(
                    onClick = onOpenAppSettings,
                    modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("vault_app_battery_button"),
                  ) {
                    Text("Battery Settings")
                  }
                }
                Text(
                  text = "For phone lockscreen capture: use a 4+ digit PIN/Pattern, set Battery → Unrestricted, and allow Auto-start in App Info.",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }

      if (photos.isEmpty()) {
        item {
          Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              modifier = Modifier.padding(22.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Image(
                painter = painterResource(id = R.drawable.img_vault_shield),
                contentDescription = "Empty protected security gallery",
                modifier =
                  Modifier.fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Crop,
              )
              Text("No Intruder Captures Yet", style = MaterialTheme.typography.titleMedium)
              Text(
                text =
                  "When someone enters ${settings.failedPinThreshold} wrong PIN attempts, TerraGuard captures a front-camera photo with GPS & battery telemetry.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      } else {
        items(photos, key = { it.id }) { photo ->
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            shape = RoundedCornerShape(18.dp),
            modifier =
              Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                .clickable { selectedPhoto = photo },
          ) {
            Row(
              modifier = Modifier.padding(12.dp),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              AsyncImage(
                model = File(photo.filePath),
                contentDescription = "Captured security evidence: ${photo.reason}",
                contentScale = ContentScale.Crop,
                modifier =
                  Modifier.size(86.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
              )
              Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                StatusBadge(
                  label = photo.reason,
                  containerColor = MaterialTheme.colorScheme.errorContainer,
                  contentColor = MaterialTheme.colorScheme.error,
                )
                Text(
                  text = formatTimestamp(photo.capturedAtMillis),
                  style = MaterialTheme.typography.labelLarge,
                )
                Text(
                  text = "${formatCoords(photo.latitude, photo.longitude)} • ${photo.batteryPercent}% Battery",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Column {
                IconButton(
                  onClick = { onSharePhoto(photo) },
                  modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                  Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = "Share evidence photo",
                    tint = MaterialTheme.colorScheme.secondary,
                  )
                }
                IconButton(
                  onClick = { onDeletePhoto(photo) },
                  modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                  Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete evidence photo",
                    tint = MaterialTheme.colorScheme.error,
                  )
                }
              }
            }
          }
        }
      }
    }
  }

  selectedPhoto?.let { photo ->
    AlertDialog(
      onDismissRequest = { selectedPhoto = null },
      title = { Text("Evidence Inspection") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          AsyncImage(
            model = File(photo.filePath),
            contentDescription = photo.reason,
            contentScale = ContentScale.Fit,
            modifier =
              Modifier.fillMaxWidth()
                .height(260.dp)
                .clip(RoundedCornerShape(14.dp)),
          )
          Text(text = photo.reason, style = MaterialTheme.typography.titleSmall)
          Text(
            text =
              "Captured: ${formatTimestamp(photo.capturedAtMillis)}\nGPS: ${formatCoords(photo.latitude, photo.longitude)} (±${photo.accuracyMeters.toInt()}m)\nBattery: ${photo.batteryPercent}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      },
      confirmButton = {
        Button(onClick = { onSharePhoto(photo) }) { Text("Email / Share") }
      },
      dismissButton = {
        TextButton(onClick = { selectedPhoto = null }) { Text("Close") }
      },
    )
  }
}

package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.data.local.TerraGuardSettingsState
import com.example.data.model.EmailStatus
import com.example.data.model.SecurityAlert
import com.example.ui.UiState
import com.example.ui.components.StatusBadge
import com.example.ui.components.formatCoords
import com.example.ui.components.formatTimestamp
import kotlin.math.roundToInt

@Composable
fun AlertsAndSettingsScreen(
  userEmail: String,
  settings: TerraGuardSettingsState,
  alertsState: UiState<List<SecurityAlert>>,
  onSendAlertEmail: (SecurityAlert) -> Unit,
  onOpenInMaps: (Double, Double, String) -> Unit,
  onDeleteAlert: (String) -> Unit,
  onUpdateOwnerEmail: (String) -> Unit,
  onUpdateWebhookUrl: (String) -> Unit,
  onToggleAutoEmailComposer: (Boolean) -> Unit,
  onUpdateSmtpConfig: (enabled: Boolean, host: String, port: Int, username: String, password: String) -> Unit,
  onSendTestEmail: (ownerEmail: String, deviceName: String, enabled: Boolean, host: String, port: Int, username: String, password: String) -> Unit,
  onUpdateSmsConfig: (enabled: Boolean, alarmKeyword: String, locateKeyword: String, trustedSender: String) -> Unit,
  onUpdatePinThreshold: (Int) -> Unit,
  onUpdateDeviceName: (String) -> Unit,
  onToggleDarkTheme: (Boolean) -> Unit,
  onSignOut: () -> Unit,
) {
  val context = LocalContext.current
  var smsPermTick by remember { mutableStateOf(0) }
  val hasSmsPerms =
    remember(smsPermTick) {
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
        PackageManager.PERMISSION_GRANTED
    }
  val smsPermLauncher =
    rememberLauncherForActivityResult(
      ActivityResultContracts.RequestMultiplePermissions()
    ) {
      smsPermTick++
    }

  var emailDraft by remember(settings.ownerAlertEmail) { mutableStateOf(settings.ownerAlertEmail) }
  var webhookDraft by remember(settings.emailWebhookUrl) { mutableStateOf(settings.emailWebhookUrl) }
  var deviceNameDraft by remember(settings.deviceName) { mutableStateOf(settings.deviceName) }

  var smtpEnabledDraft by remember(settings.smtpEnabled) { mutableStateOf(settings.smtpEnabled) }
  var smtpHostDraft by remember(settings.smtpHost) { mutableStateOf(settings.smtpHost) }
  var smtpPortDraft by remember(settings.smtpPort) { mutableStateOf(settings.smtpPort.toString()) }
  var smtpUserDraft by
    remember(settings.smtpUsername, settings.ownerAlertEmail) {
      mutableStateOf(settings.smtpUsername.ifBlank { settings.ownerAlertEmail })
    }
  var smtpPassDraft by remember(settings.smtpPassword) { mutableStateOf(settings.smtpPassword) }

  var smsEnabledDraft by remember(settings.smsRemoteEnabled) { mutableStateOf(settings.smsRemoteEnabled) }
  var smsAlarmDraft by remember(settings.smsAlarmKeyword) { mutableStateOf(settings.smsAlarmKeyword) }
  var smsLocateDraft by remember(settings.smsLocateKeyword) { mutableStateOf(settings.smsLocateKeyword) }
  var smsTrustedDraft by remember(settings.smsTrustedSender) { mutableStateOf(settings.smsTrustedSender) }

  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      // Automatic Email & Security Policy Settings Card
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
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                  imageVector = Icons.Filled.Email,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Automatic Email & Alert Config", style = MaterialTheme.typography.titleMedium)
              }
              OutlinedButton(
                onClick = onSignOut,
                modifier = Modifier.minimumInteractiveComponentSize().testTag("sign_out_button"),
              ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Sign Out")
              }
            }

            Text(
              text = "Signed in as $userEmail",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
              value = emailDraft,
              onValueChange = { emailDraft = it },
              label = { Text("Owner Alert Recipient Email") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth().testTag("owner_email_input"),
            )

            OutlinedTextField(
              value = deviceNameDraft,
              onValueChange = { deviceNameDraft = it },
              label = { Text("Device Label") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth().testTag("device_name_input"),
            )

            HorizontalDivider()

            // Direct SMTP Auto-Email Relay
            val smtpReady =
              smtpEnabledDraft && smtpUserDraft.isNotBlank() && smtpPassDraft.isNotBlank()
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  Text("Direct Background Email (SMTP)", style = MaterialTheme.typography.titleSmall)
                  StatusBadge(
                    label = if (smtpReady) "READY" else if (smtpEnabledDraft) "NEEDS PASSWORD" else "OFF",
                    containerColor =
                      if (smtpReady) MaterialTheme.colorScheme.primaryContainer
                      else MaterialTheme.colorScheme.errorContainer,
                    contentColor =
                      if (smtpReady) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.error,
                  )
                }
                Text(
                  text = "Required for automatic silent email delivery with intruder photo attachments (use a Google 16-char App Password, not your regular Gmail password).",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Switch(
                checked = smtpEnabledDraft,
                onCheckedChange = {
                  smtpEnabledDraft = it
                  val parsedPort = smtpPortDraft.toIntOrNull() ?: 465
                  onUpdateSmtpConfig(it, smtpHostDraft, parsedPort, smtpUserDraft, smtpPassDraft)
                },
                modifier = Modifier.testTag("smtp_enabled_switch"),
              )
            }

            if (smtpEnabledDraft) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                OutlinedTextField(
                  value = smtpHostDraft,
                  onValueChange = { smtpHostDraft = it },
                  label = { Text("SMTP Host") },
                  placeholder = { Text("smtp.gmail.com") },
                  singleLine = true,
                  modifier = Modifier.weight(2f).testTag("smtp_host_input"),
                )
                OutlinedTextField(
                  value = smtpPortDraft,
                  onValueChange = { smtpPortDraft = it.filter { ch -> ch.isDigit() } },
                  label = { Text("Port") },
                  placeholder = { Text("465") },
                  singleLine = true,
                  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                  modifier = Modifier.weight(1f).testTag("smtp_port_input"),
                )
              }

              OutlinedTextField(
                value = smtpUserDraft,
                onValueChange = { smtpUserDraft = it },
                label = { Text("Sender Email (Gmail / Outlook)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("smtp_username_input"),
              )

              OutlinedTextField(
                value = smtpPassDraft,
                onValueChange = { smtpPassDraft = it },
                label = { Text("16-char Gmail App Password") },
                placeholder = { Text("abcd efgh ijkl mnop") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().testTag("smtp_password_input"),
              )
            }

            OutlinedTextField(
              value = webhookDraft,
              onValueChange = { webhookDraft = it },
              label = { Text("Optional HTTPS Email Webhook URL") },
              placeholder = { Text("https://your-relay.example.com/alert") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth().testTag("webhook_url_input"),
            )

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Button(
                onClick = {
                  val parsedPort = smtpPortDraft.toIntOrNull() ?: 465
                  onUpdateOwnerEmail(emailDraft)
                  onUpdateWebhookUrl(webhookDraft)
                  onUpdateDeviceName(deviceNameDraft)
                  onUpdateSmtpConfig(smtpEnabledDraft, smtpHostDraft, parsedPort, smtpUserDraft, smtpPassDraft)
                },
                modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("save_settings_button"),
              ) {
                Text("Save Email Config")
              }

              if (smtpEnabledDraft) {
                FilledTonalButton(
                  onClick = {
                    val parsedPort = smtpPortDraft.toIntOrNull() ?: 465
                    onSendTestEmail(
                      emailDraft,
                      deviceNameDraft,
                      smtpEnabledDraft,
                      smtpHostDraft,
                      parsedPort,
                      smtpUserDraft,
                      smtpPassDraft,
                    )
                  },
                  modifier = Modifier.minimumInteractiveComponentSize().testTag("test_smtp_button"),
                ) {
                  Icon(Icons.Filled.MarkEmailRead, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("Test Email")
                }
              }
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text("Fallback: Open Email App on Alert", style = MaterialTheme.typography.titleSmall)
                Text(
                  text = "Opens pre-filled email if SMTP and webhook relays are unavailable.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Switch(
                checked = settings.autoLaunchEmailComposer,
                onCheckedChange = onToggleAutoEmailComposer,
              )
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text("Tactical Dark Theme", style = MaterialTheme.typography.titleSmall)
                Text(
                  text = "Switch between Obsidian Dark and Arctic Light mode.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Switch(
                checked = settings.darkThemeEnabled,
                onCheckedChange = onToggleDarkTheme,
              )
            }

            Column {
              Text(
                text = "Intruder Capture Threshold: ${settings.failedPinThreshold} wrong PIN attempts",
                style = MaterialTheme.typography.titleSmall,
              )
              Slider(
                value = settings.failedPinThreshold.toFloat(),
                onValueChange = { onUpdatePinThreshold(it.roundToInt()) },
                valueRange = 1f..5f,
                steps = 3,
              )
            }
          }
        }
      }

      // SMS Remote Alarm & Locate Card
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                  imageVector = Icons.Filled.Sms,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.secondary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("SMS Remote Alarm & Locate", style = MaterialTheme.typography.titleMedium)
              }
              Switch(
                checked = smsEnabledDraft,
                onCheckedChange = {
                  smsEnabledDraft = it
                  onUpdateSmsConfig(it, smsAlarmDraft, smsLocateDraft, smsTrustedDraft)
                },
                modifier = Modifier.testTag("sms_remote_switch"),
              )
            }

            Text(
              text = "If your phone is lost, text the Alarm keyword from any phone to sound the loud siren even in silent mode, and receive GPS coordinates back.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
              value = smsAlarmDraft,
              onValueChange = { smsAlarmDraft = it },
              label = { Text("SMS Keyword to Ring Loud Alarm") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth().testTag("sms_alarm_keyword_input"),
            )

            OutlinedTextField(
              value = smsLocateDraft,
              onValueChange = { smsLocateDraft = it },
              label = { Text("SMS Keyword to Reply GPS Location") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth().testTag("sms_locate_keyword_input"),
            )

            OutlinedTextField(
              value = smsTrustedDraft,
              onValueChange = { smsTrustedDraft = it },
              label = { Text("Optional Trusted Sender Number") },
              placeholder = { Text("Leave blank to accept SMS command from any phone") },
              singleLine = true,
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
              modifier = Modifier.fillMaxWidth().testTag("sms_trusted_sender_input"),
            )

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Button(
                onClick = {
                  onUpdateSmsConfig(smsEnabledDraft, smsAlarmDraft, smsLocateDraft, smsTrustedDraft)
                },
                modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("save_sms_settings_button"),
              ) {
                Text("Save SMS Rules")
              }

              if (!hasSmsPerms) {
                FilledTonalButton(
                  onClick = {
                    smsPermLauncher.launch(
                      arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS)
                    )
                  },
                  modifier = Modifier.minimumInteractiveComponentSize().testTag("grant_sms_button"),
                ) {
                  Text("Grant SMS")
                }
              }
            }
          }
        }
      }

      // Security Alerts & Email Outbox
      item {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = Icons.Filled.Notifications,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text("Security Alerts & Email Outbox", style = MaterialTheme.typography.titleMedium)
        }
      }

      when (alertsState) {
        is UiState.Loading -> {
          item { CircularProgressIndicator() }
        }
        is UiState.Error -> {
          item { Text(alertsState.message, color = MaterialTheme.colorScheme.error) }
        }
        is UiState.Success -> {
          if (alertsState.data.isEmpty()) {
            item {
              Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Column(
                  modifier = Modifier.padding(20.dp),
                  horizontalAlignment = Alignment.CenterHorizontally,
                  verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  Icon(
                    imageVector = Icons.Filled.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp),
                  )
                  Text("No Security Incidents Logged", style = MaterialTheme.typography.titleSmall)
                  Text(
                    text = "Geofence breaches, wrong PIN captures, SIM changes, remote alarms, and internet reconnects appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          } else {
            items(alertsState.data, key = { it.id }) { alert ->
              Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                shape = RoundedCornerShape(16.dp),
                modifier =
                  Modifier.fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
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
                    StatusBadge(
                      label = alert.type,
                      containerColor = MaterialTheme.colorScheme.errorContainer,
                      contentColor = MaterialTheme.colorScheme.error,
                    )
                    StatusBadge(
                      label = "EMAIL: ${alert.emailStatus}",
                      containerColor =
                        if (alert.emailStatus == EmailStatus.DISPATCHED) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.secondaryContainer,
                      contentColor =
                        if (alert.emailStatus == EmailStatus.DISPATCHED) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.secondary,
                    )
                  }

                  Text(alert.title, style = MaterialTheme.typography.titleSmall)
                  Text(
                    text = alert.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  Text(
                    text =
                      "${formatCoords(alert.latitude, alert.longitude)} (±${alert.accuracyMeters.toInt()}m) • ${alert.batteryPercent}% Battery • ${formatTimestamp(alert.occurredAtMillis)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )

                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    FilledTonalButton(
                      onClick = { onSendAlertEmail(alert) },
                      modifier = Modifier.weight(1f).minimumInteractiveComponentSize(),
                    ) {
                      Icon(Icons.Filled.Email, contentDescription = null, modifier = Modifier.size(16.dp))
                      Spacer(modifier = Modifier.width(6.dp))
                      Text("Send Email")
                    }
                    OutlinedButton(
                      onClick = { onOpenInMaps(alert.latitude, alert.longitude, alert.title) },
                      enabled = alert.latitude != 0.0 || alert.longitude != 0.0,
                      modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                      Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open in Maps", modifier = Modifier.size(16.dp))
                    }
                    IconButton(
                      onClick = { onDeleteAlert(alert.id) },
                      modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                      Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Delete alert",
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
    }
  }
}

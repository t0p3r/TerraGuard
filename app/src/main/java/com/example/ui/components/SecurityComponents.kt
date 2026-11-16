package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatTimestamp(millis: Long): String {
  if (millis <= 0L) return "Not recorded"
  val formatter = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault())
  return formatter.format(Date(millis))
}

fun formatCoords(lat: Double, lng: Double): String {
  if (lat == 0.0 && lng == 0.0) return "No GPS lock"
  return String.format(Locale.US, "%.5f, %.5f", lat, lng)
}

@Composable
fun StatusBadge(
  label: String,
  containerColor: Color,
  contentColor: Color,
  modifier: Modifier = Modifier,
) {
  Surface(
    color = containerColor,
    contentColor = contentColor,
    shape = RoundedCornerShape(50),
    modifier = modifier,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
    )
  }
}

@Composable
fun TelemetryTile(
  icon: ImageVector,
  title: String,
  primaryValue: String,
  secondaryValue: String,
  accentColor: Color,
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    shape = RoundedCornerShape(18.dp),
    modifier =
      modifier
        .border(
          width = 1.dp,
          color = MaterialTheme.colorScheme.outlineVariant,
          shape = RoundedCornerShape(18.dp),
        )
        .let { base ->
          if (onClick != null) base.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick)
          else base
        },
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Box(
          modifier =
            Modifier.size(32.dp)
              .clip(RoundedCornerShape(10.dp))
              .background(accentColor.copy(alpha = 0.15f)),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = icon,
            contentDescription = title,
            tint = accentColor,
            modifier = Modifier.size(18.dp),
          )
        }
        Text(
          text = title,
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Text(
        text = primaryValue,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = secondaryValue,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
fun SecurityPinKeypad(
  title: String,
  subtitle: String,
  wrongAttempts: Int,
  maxAttempts: Int,
  isSetupMode: Boolean,
  onPinSubmitted: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var enteredPin by remember(isSetupMode) { mutableStateOf("") }

  Column(
    modifier = modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Box(
      modifier =
        Modifier.size(56.dp)
          .clip(CircleShape)
          .background(
            if (wrongAttempts > 0) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.primaryContainer
          ),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = if (wrongAttempts > 0) Icons.Filled.WarningAmber else Icons.Filled.Lock,
        contentDescription = title,
        tint =
          if (wrongAttempts > 0) MaterialTheme.colorScheme.error
          else MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(28.dp),
      )
    }

    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(text = title, style = MaterialTheme.typography.headlineSmall)
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (!isSetupMode && wrongAttempts > 0) {
        Text(
          text = "Incorrect PIN ($wrongAttempts/$maxAttempts before intruder photo)",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.error,
        )
      }
    }

    // 4-dot PIN indicator
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      for (i in 0 until 4) {
        val filled = i < enteredPin.length
        val dotColor by
          animateColorAsState(
            targetValue =
              if (filled) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.surfaceContainerHigh,
            label = "pinDot",
          )
        Box(
          modifier =
            Modifier.size(18.dp)
              .clip(CircleShape)
              .background(dotColor)
              .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
        )
      }
    }

    val rows =
      listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("C", "0", "DEL"),
      )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      rows.forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
          row.forEach { key ->
            Surface(
              shape = CircleShape,
              color = MaterialTheme.colorScheme.surfaceContainer,
              modifier =
                Modifier.size(64.dp)
                  .minimumInteractiveComponentSize()
                  .clip(CircleShape)
                  .testTag("pin_key_$key")
                  .clickable {
                    when (key) {
                      "C" -> enteredPin = ""
                      "DEL" -> if (enteredPin.isNotEmpty()) enteredPin = enteredPin.dropLast(1)
                      else -> {
                        if (enteredPin.length < 4) {
                          val next = enteredPin + key
                          enteredPin = next
                          if (next.length == 4) {
                            val submitted = next
                            enteredPin = ""
                            onPinSubmitted(submitted)
                          }
                        }
                      }
                    }
                  },
            ) {
              Box(contentAlignment = Alignment.Center) {
                if (key == "DEL") {
                  Icon(
                    imageVector = Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = "Backspace",
                    tint = MaterialTheme.colorScheme.onSurface,
                  )
                } else {
                  Text(
                    text = key,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
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

@Composable
fun ActiveAlarmBanner(
  onDisarmClicked: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    color = MaterialTheme.colorScheme.error,
    contentColor = MaterialTheme.colorScheme.onError,
    shape = RoundedCornerShape(18.dp),
    modifier = modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier.padding(14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.weight(1f),
      ) {
        Icon(
          imageVector = Icons.Filled.WarningAmber,
          contentDescription = "Alarm active",
          modifier = Modifier.size(28.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
          Text(
            text = "EMERGENCY ALARM ACTIVE",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
          )
          Text(
            text = "Loud siren sounding via Alarm audio stream.",
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
      Button(
        onClick = onDisarmClicked,
        colors =
          ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.onError,
            contentColor = MaterialTheme.colorScheme.error,
          ),
        modifier = Modifier.minimumInteractiveComponentSize().testTag("disarm_alarm_banner_button"),
      ) {
        Text("Disarm", fontWeight = FontWeight.Bold)
      }
    }
  }
}

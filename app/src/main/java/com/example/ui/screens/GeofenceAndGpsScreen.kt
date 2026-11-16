package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.data.local.LastKnownLocationSnapshot
import com.example.data.model.GeofenceZone
import com.example.data.model.LocationRecord
import com.example.security.GpsFix
import com.example.ui.UiState
import com.example.ui.components.StatusBadge
import com.example.ui.components.formatCoords
import com.example.ui.components.formatTimestamp
import kotlin.math.cos
import kotlin.math.max

@Composable
fun GeofenceAndGpsScreen(
  currentFix: GpsFix?,
  lastSaved: LastKnownLocationSnapshot,
  geofencesState: UiState<List<GeofenceZone>>,
  locationsState: UiState<List<LocationRecord>>,
  onCaptureGpsNow: () -> Unit,
  onCreateGeofence: (String, Double, Double, Double) -> Unit,
  onToggleGeofence: (GeofenceZone) -> Unit,
  onDeleteGeofence: (String) -> Unit,
  onOpenInMaps: (Double, Double, String) -> Unit,
) {
  val context = LocalContext.current
  var showAddDialog by remember { mutableStateOf(false) }

  val hasBgPerm =
    remember {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ContextCompat.checkSelfPermission(
          context,
          Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
      } else true
    }
  var bgPermGranted by remember { mutableStateOf(hasBgPerm) }
  val bgPermLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      bgPermGranted = granted
    }

  val activeLat = currentFix?.latitude ?: lastSaved.latitude
  val activeLng = currentFix?.longitude ?: lastSaved.longitude
  val zones = (geofencesState as? UiState.Success)?.data.orEmpty()
  val locations = (locationsState as? UiState.Success)?.data.orEmpty()

  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      // Tactical Radar Canvas Card
      item {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
          shape = RoundedCornerShape(22.dp),
          modifier =
            Modifier.fillMaxWidth()
              .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(22.dp)),
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
                  imageVector = Icons.Filled.Radar,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.secondary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                  Text("Perimeter & GPS Radar", style = MaterialTheme.typography.titleMedium)
                  Text(
                    text = formatCoords(activeLat, activeLng),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }

              FilledTonalButton(
                onClick = onCaptureGpsNow,
                modifier = Modifier.minimumInteractiveComponentSize().testTag("radar_refresh_gps_button"),
              ) {
                Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Fix")
              }
            }

            GeofenceRadarCanvas(
              centerLat = activeLat,
              centerLng = activeLng,
              zones = zones,
              breadcrumbs = locations,
              modifier =
                Modifier.fillMaxWidth()
                  .height(190.dp)
                  .clip(RoundedCornerShape(16.dp)),
            )

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Button(
                onClick = { showAddDialog = true },
                modifier =
                  Modifier.weight(1f)
                    .minimumInteractiveComponentSize()
                    .testTag("add_geofence_button"),
              ) {
                Icon(Icons.Filled.AddLocationAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Safe Zone")
              }

              if (!bgPermGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                OutlinedButton(
                  onClick = { bgPermLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
                  modifier =
                    Modifier.weight(1f)
                      .minimumInteractiveComponentSize()
                      .testTag("background_location_button"),
                ) {
                  Text("Allow Background GPS")
                }
              }
            }
          }
        }
      }

      // Geofence Zones List
      item {
        Text("Monitored Safe Zones (${zones.size})", style = MaterialTheme.typography.titleMedium)
      }

      when (geofencesState) {
        is UiState.Loading -> {
          item { CircularProgressIndicator() }
        }
        is UiState.Error -> {
          item {
            Text(
              text = geofencesState.message,
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodyMedium,
            )
          }
        }
        is UiState.Success -> {
          if (geofencesState.data.isEmpty()) {
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
                    imageVector = Icons.Filled.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                  )
                  Text("No Safe Zones Configured", style = MaterialTheme.typography.titleSmall)
                  Text(
                    text = "Add a perimeter around your home or office to receive exit alerts and automatic emails.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          } else {
            items(geofencesState.data, key = { it.id }) { zone ->
              Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                shape = RoundedCornerShape(16.dp),
                modifier =
                  Modifier.fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
              ) {
                Row(
                  modifier = Modifier.padding(14.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                  ) {
                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                      Text(zone.name, style = MaterialTheme.typography.titleSmall)
                      StatusBadge(
                        label = zone.lastStatus,
                        containerColor =
                          when (zone.lastStatus) {
                            "INSIDE" -> MaterialTheme.colorScheme.primaryContainer
                            "OUTSIDE" -> MaterialTheme.colorScheme.errorContainer
                            else -> MaterialTheme.colorScheme.surfaceContainerHigh
                          },
                        contentColor =
                          when (zone.lastStatus) {
                            "INSIDE" -> MaterialTheme.colorScheme.primary
                            "OUTSIDE" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                          },
                      )
                    }
                    Text(
                      text = "${formatCoords(zone.latitude, zone.longitude)} • Radius ${zone.radiusMeters.toInt()}m",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                  Switch(
                    checked = zone.isActive,
                    onCheckedChange = { onToggleGeofence(zone) },
                    modifier = Modifier.testTag("toggle_geofence_${zone.id}"),
                  )
                  IconButton(
                    onClick = { onDeleteGeofence(zone.id) },
                    modifier = Modifier.minimumInteractiveComponentSize().testTag("delete_geofence_${zone.id}"),
                  ) {
                    Icon(
                      imageVector = Icons.Filled.Delete,
                      contentDescription = "Delete safe zone ${zone.name}",
                      tint = MaterialTheme.colorScheme.error,
                    )
                  }
                }
              }
            }
          }
        }
      }

      // GPS Tracking History
      item {
        Text(
          text = "GPS Tracking Log (${locations.size})",
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.padding(top = 6.dp),
        )
      }

      when (locationsState) {
        is UiState.Loading -> {
          item { CircularProgressIndicator() }
        }
        is UiState.Error -> {
          item {
            Text(locationsState.message, color = MaterialTheme.colorScheme.error)
          }
        }
        is UiState.Success -> {
          if (locationsState.data.isEmpty()) {
            item {
              Text(
                text = "Tap Fix or Locate Now to record GPS coordinates, accuracy, battery, and timestamp.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          } else {
            items(locationsState.data, key = { it.id }) { record ->
              Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Row(
                  modifier = Modifier.padding(12.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                  ) {
                    Text(
                      text = formatCoords(record.latitude, record.longitude),
                      style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                      text =
                        "±${record.accuracyMeters.toInt()}m • ${record.batteryPercent}% Battery • ${record.sourceEvent} (${record.provider})",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                      text = formatTimestamp(record.recordedAtMillis),
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                  IconButton(
                    onClick = { onOpenInMaps(record.latitude, record.longitude, "GPS Breadcrumb") },
                    modifier = Modifier.minimumInteractiveComponentSize(),
                  ) {
                    Icon(
                      imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                      contentDescription = "Open GPS breadcrumb in Maps",
                      tint = MaterialTheme.colorScheme.secondary,
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

  if (showAddDialog) {
    AddGeofenceDialog(
      defaultLat = activeLat,
      defaultLng = activeLng,
      onDismiss = { showAddDialog = false },
      onConfirm = { name, lat, lng, radius ->
        showAddDialog = false
        onCreateGeofence(name, lat, lng, radius)
      },
    )
  }
}

@Composable
private fun GeofenceRadarCanvas(
  centerLat: Double,
  centerLng: Double,
  zones: List<GeofenceZone>,
  breadcrumbs: List<LocationRecord>,
  modifier: Modifier = Modifier,
) {
  val primaryColor = MaterialTheme.colorScheme.primary
  val secondaryColor = MaterialTheme.colorScheme.secondary
  val errorColor = MaterialTheme.colorScheme.error
  val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
  val bgColor = MaterialTheme.colorScheme.surface

  Surface(color = bgColor, modifier = modifier) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val cx = size.width / 2f
      val cy = size.height / 2f
      val maxRadius = minOf(size.width, size.height) * 0.44f

      // Concentric tactical range rings
      listOf(0.33f, 0.66f, 1.0f).forEach { factor ->
        drawCircle(
          color = gridColor,
          radius = maxRadius * factor,
          center = Offset(cx, cy),
          style = Stroke(width = 1.5f),
        )
      }
      drawLine(gridColor, Offset(0f, cy), Offset(size.width, cy), strokeWidth = 1f)
      drawLine(gridColor, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 1f)

      val hasCenter = centerLat != 0.0 || centerLng != 0.0
      if (hasCenter) {
        val metersPerDegreeLat = 111_320.0
        val metersPerDegreeLng = max(1000.0, 111_320.0 * cos(Math.toRadians(centerLat)))
        val scaleMeters =
          max(
            500.0,
            zones.filter { it.isActive }.maxOfOrNull { it.radiusMeters * 1.4 } ?: 1000.0,
          )
        val pxPerMeter = maxRadius / scaleMeters.toFloat()

        zones.filter { it.isActive }.forEach { zone ->
          val dxMeters = (zone.longitude - centerLng) * metersPerDegreeLng
          val dyMeters = (centerLat - zone.latitude) * metersPerDegreeLat
          val zx = cx + (dxMeters.toFloat() * pxPerMeter)
          val zy = cy + (dyMeters.toFloat() * pxPerMeter)
          val zr = (zone.radiusMeters.toFloat() * pxPerMeter).coerceIn(16f, maxRadius * 1.2f)
          val color = if (zone.lastStatus == "OUTSIDE") errorColor else primaryColor
          drawCircle(
            color = color.copy(alpha = 0.16f),
            radius = zr,
            center = Offset(zx, zy),
          )
          drawCircle(
            color = color,
            radius = zr,
            center = Offset(zx, zy),
            style = Stroke(width = 2.5f),
          )
        }

        breadcrumbs.take(12).forEach { pt ->
          val dxMeters = (pt.longitude - centerLng) * metersPerDegreeLng
          val dyMeters = (centerLat - pt.latitude) * metersPerDegreeLat
          val px = (cx + dxMeters.toFloat() * pxPerMeter).coerceIn(10f, size.width - 10f)
          val py = (cy + dyMeters.toFloat() * pxPerMeter).coerceIn(10f, size.height - 10f)
          drawCircle(
            color = secondaryColor.copy(alpha = 0.6f),
            radius = 4.5f,
            center = Offset(px, py),
          )
        }
      }

      // Device live beacon at center
      drawCircle(color = secondaryColor.copy(alpha = 0.28f), radius = 16f, center = Offset(cx, cy))
      drawCircle(color = Color.White, radius = 6.5f, center = Offset(cx, cy))
      drawCircle(color = secondaryColor, radius = 4.5f, center = Offset(cx, cy))
    }
  }
}

@Composable
private fun AddGeofenceDialog(
  defaultLat: Double,
  defaultLng: Double,
  onDismiss: () -> Unit,
  onConfirm: (String, Double, Double, Double) -> Unit,
) {
  val initialLat = if (defaultLat != 0.0) defaultLat else 37.421998
  val initialLng = if (defaultLng != 0.0) defaultLng else -122.084
  var name by remember { mutableStateOf("Home Safe Zone") }
  var latText by remember { mutableStateOf(String.format("%.6f", initialLat)) }
  var lngText by remember { mutableStateOf(String.format("%.6f", initialLng)) }
  var radiusMeters by remember { mutableFloatStateOf(250f) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Create Geofence Safe Zone") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("Zone Name") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("geofence_name_input"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = latText,
            onValueChange = { latText = it },
            label = { Text("Latitude") },
            singleLine = true,
            modifier = Modifier.weight(1f),
          )
          OutlinedTextField(
            value = lngText,
            onValueChange = { lngText = it },
            label = { Text("Longitude") },
            singleLine = true,
            modifier = Modifier.weight(1f),
          )
        }
        Column {
          Text(
            text = "Perimeter Radius: ${radiusMeters.toInt()} meters",
            style = MaterialTheme.typography.labelLarge,
          )
          Slider(
            value = radiusMeters,
            onValueChange = { radiusMeters = it },
            valueRange = 100f..3000f,
            steps = 28,
          )
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          val lat = latText.toDoubleOrNull() ?: initialLat
          val lng = lngText.toDoubleOrNull() ?: initialLng
          onConfirm(name, lat, lng, radiusMeters.toDouble())
        },
        modifier = Modifier.testTag("confirm_add_geofence_button"),
      ) {
        Text("Save Zone")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  )
}

package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.local.SecurityEvidenceDatabase
import com.example.data.local.TerraGuardPreferences
import com.example.data.repository.TerraGuardRepository
import com.example.security.DeviceTelemetryManager
import com.example.security.IntruderCameraManager
import com.example.security.LocationAndGeofenceManager
import com.example.security.PlatformSecurityBridge
import com.example.ui.TerraGuardViewModel
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.signOut
import com.example.ui.components.SecurityPinKeypad
import com.example.ui.components.StatusBadge
import com.example.ui.screens.AlertsAndSettingsScreen
import com.example.ui.screens.GeofenceAndGpsScreen
import com.example.ui.screens.SecurityVaultScreen
import com.example.ui.screens.ShieldScreen
import com.example.ui.theme.TerraGuardTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      TerraGuardAppRoot()
    }
  }
}

private fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
  val listener = FirebaseAuth.AuthStateListener { auth -> trySend(auth.currentUser) }
  addAuthStateListener(listener)
  awaitClose { removeAuthStateListener(listener) }
}

private enum class TerraGuardDestination(
  val route: String,
  val label: String,
  val selectedIcon: ImageVector,
  val unselectedIcon: ImageVector,
) {
  SHIELD("shield", "Shield", Icons.Filled.Shield, Icons.Outlined.Shield),
  GEOFENCE("geofence", "Geofence", Icons.Filled.Radar, Icons.Outlined.Radar),
  VAULT("vault", "Vault", Icons.Filled.Lock, Icons.Outlined.Lock),
  ALERTS("alerts", "Alerts", Icons.Filled.Notifications, Icons.Outlined.Notifications),
}

@Composable
fun TerraGuardAppRoot(auth: FirebaseAuth = Firebase.auth) {
  val currentUser by
    auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
  val user = currentUser

  if (user == null) {
    TerraGuardTheme(darkTheme = true) {
      AuthScreen(onAuthSuccess = {})
    }
  } else {
    key(user.uid) {
      TerraGuardAuthenticatedShell(
        currentUserId = user.uid,
        currentUserEmail = user.email.orEmpty(),
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerraGuardAuthenticatedShell(
  currentUserId: String,
  currentUserEmail: String,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val scope = rememberCoroutineScope()
  val credentialManager = remember(context) { CredentialManager.create(context) }

  val viewModel: TerraGuardViewModel =
    viewModel(
      key = currentUserId,
      factory =
        viewModelFactory {
          initializer {
            val app =
              checkNotNull(this[APPLICATION_KEY]) {
                "APPLICATION_KEY missing from CreationExtras"
              }
            val databaseId = app.getString(R.string.firestore_database_id)
            val db = FirebaseFirestore.getInstance(databaseId)
            val repository = TerraGuardRepository(db)
            val preferences = TerraGuardPreferences(app)
            val evidenceDao = SecurityEvidenceDatabase.getInstance(app).evidenceDao()
            val telemetryManager = DeviceTelemetryManager(app)
            val locationManager = LocationAndGeofenceManager(app)
            val intruderCameraManager = IntruderCameraManager(app)
            val platformBridge = PlatformSecurityBridge(app)
            TerraGuardViewModel(
              repository = repository,
              preferences = preferences,
              evidenceDao = evidenceDao,
              telemetryManager = telemetryManager,
              locationManager = locationManager,
              intruderCameraManager = intruderCameraManager,
              platformBridge = platformBridge,
              currentUserId = currentUserId,
              currentUserEmail = currentUserEmail,
            )
          }
        },
    )

  val settings by viewModel.settingsState.collectAsStateWithLifecycle()
  val currentDeviceId by viewModel.currentDeviceId.collectAsStateWithLifecycle()
  val currentFix by viewModel.currentGpsFix.collectAsStateWithLifecycle()
  val battery by viewModel.batteryState.collectAsStateWithLifecycle()
  val network by viewModel.networkState.collectAsStateWithLifecycle()
  val sim by viewModel.simState.collectAsStateWithLifecycle()
  val isAlarmRinging by viewModel.isAlarmRinging.collectAsStateWithLifecycle()
  val isDeviceAdminActive by viewModel.isDeviceAdminActive.collectAsStateWithLifecycle()
  val hasDndAccess by viewModel.hasDndPolicyAccess.collectAsStateWithLifecycle()
  val isVaultUnlocked by viewModel.isVaultUnlocked.collectAsStateWithLifecycle()
  val wrongPinAttempts by viewModel.wrongPinAttempts.collectAsStateWithLifecycle()
  val isCapturingGps by viewModel.isCapturingGps.collectAsStateWithLifecycle()
  val pendingOfflineCount by viewModel.pendingOfflineCount.collectAsStateWithLifecycle()
  val devicesState by viewModel.devicesState.collectAsStateWithLifecycle()
  val locationsState by viewModel.locationsState.collectAsStateWithLifecycle()
  val geofencesState by viewModel.geofencesState.collectAsStateWithLifecycle()
  val alertsState by viewModel.alertsState.collectAsStateWithLifecycle()
  val photos by viewModel.photosState.collectAsStateWithLifecycle()

  val snackbarHostState = remember { SnackbarHostState() }
  var showDisarmPinDialog by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    viewModel.statusBanner.collect { msg ->
      snackbarHostState.showSnackbar(msg)
    }
  }

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        viewModel.refreshSystemStates(lifecycleOwner)
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  val navController = rememberNavController()
  val backStackEntry by navController.currentBackStackEntryAsState()
  val currentRoute = backStackEntry?.destination?.route ?: TerraGuardDestination.SHIELD.route

  TerraGuardTheme(darkTheme = settings.darkThemeEnabled) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
      val useNavRail = maxWidth >= 600.dp

      Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
          TopAppBar(
            colors =
              TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
              ),
            title = {
              Column {
                Text("TerraGuard", style = MaterialTheme.typography.titleLarge)
                Text(
                  text = "Anti-Theft & GPS Security",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            },
            actions = {
              StatusBadge(
                label = if (network.isOnline) "${network.networkType} • ${battery.percent}%" else "OFFLINE • ${battery.percent}%",
                containerColor =
                  if (network.isOnline) MaterialTheme.colorScheme.primaryContainer
                  else MaterialTheme.colorScheme.tertiaryContainer,
                contentColor =
                  if (network.isOnline) MaterialTheme.colorScheme.primary
                  else MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(end = 12.dp),
              )
            },
          )
        },
        bottomBar = {
          if (!useNavRail) {
            NavigationBar {
              TerraGuardDestination.entries.forEach { dest ->
                val selected = currentRoute == dest.route
                NavigationBarItem(
                  selected = selected,
                  onClick = {
                    if (currentRoute != dest.route) {
                      navController.navigate(dest.route) {
                        popUpTo(TerraGuardDestination.SHIELD.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                      }
                    }
                  },
                  icon = {
                    Icon(
                      imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                      contentDescription = dest.label,
                    )
                  },
                  label = { Text(dest.label) },
                  modifier = Modifier.testTag("nav_${dest.route}"),
                )
              }
            }
          }
        },
      ) { innerPadding ->
        Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
          if (useNavRail) {
            NavigationRail {
              TerraGuardDestination.entries.forEach { dest ->
                val selected = currentRoute == dest.route
                NavigationRailItem(
                  selected = selected,
                  onClick = {
                    if (currentRoute != dest.route) {
                      navController.navigate(dest.route) {
                        popUpTo(TerraGuardDestination.SHIELD.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                      }
                    }
                  },
                  icon = {
                    Icon(
                      imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                      contentDescription = dest.label,
                    )
                  },
                  label = { Text(dest.label) },
                  modifier = Modifier.testTag("rail_${dest.route}"),
                )
              }
            }
          }

          Box(modifier = Modifier.weight(1f).fillMaxSize()) {
            NavHost(
              navController = navController,
              startDestination = TerraGuardDestination.SHIELD.route,
            ) {
              composable(TerraGuardDestination.SHIELD.route) {
                ShieldScreen(
                  currentDeviceId = currentDeviceId,
                  settings = settings,
                  battery = battery,
                  network = network,
                  sim = sim,
                  currentFix = currentFix,
                  isCapturingGps = isCapturingGps,
                  isAlarmRinging = isAlarmRinging,
                  isDeviceAdminActive = isDeviceAdminActive,
                  hasDndAccess = hasDndAccess,
                  pendingOfflineCount = pendingOfflineCount,
                  devicesState = devicesState,
                  onToggleProtection = viewModel::toggleProtection,
                  onCaptureGpsNow = { viewModel.captureGpsNow("MANUAL") },
                  onTriggerAlarm = viewModel::triggerRemoteAlarm,
                  onDisarmLocalAlarmClick = {
                    if (settings.isPinConfigured) {
                      showDisarmPinDialog = true
                    } else if (currentDeviceId.isNotBlank()) {
                      viewModel.triggerRemoteAlarm(currentDeviceId, false)
                    }
                  },
                  onSetAlarmOverrideSilent = viewModel::setAlarmOverrideSilent,
                  onRegisterBaselineSim = viewModel::registerBaselineSim,
                  onRequestDeviceAdmin = { viewModel.requestDeviceAdmin(context) },
                  onOpenDndSettings = viewModel::openDndPolicySettings,
                  onOpenAppSettings = viewModel::openAppDetailsSettings,
                  onOpenInMaps = viewModel::openInMaps,
                  onPermissionsUpdated = {
                    viewModel.refreshSystemStates(lifecycleOwner)
                    viewModel.startGpsTrackingLoop()
                    viewModel.captureGpsNow("MANUAL")
                  },
                )
              }

              composable(TerraGuardDestination.GEOFENCE.route) {
                GeofenceAndGpsScreen(
                  currentFix = currentFix,
                  lastSaved = settings.lastKnownLocation,
                  geofencesState = geofencesState,
                  locationsState = locationsState,
                  onCaptureGpsNow = { viewModel.captureGpsNow("MANUAL") },
                  onCreateGeofence = viewModel::createGeofence,
                  onToggleGeofence = viewModel::toggleGeofence,
                  onDeleteGeofence = viewModel::deleteGeofence,
                  onOpenInMaps = viewModel::openInMaps,
                )
              }

              composable(TerraGuardDestination.VAULT.route) {
                SecurityVaultScreen(
                  settings = settings,
                  isVaultUnlocked = isVaultUnlocked,
                  wrongPinAttempts = wrongPinAttempts,
                  isDeviceAdminActive = isDeviceAdminActive,
                  photos = photos,
                  onConfigurePin = viewModel::configureSecurityPin,
                  onSubmitPin = { pin, owner ->
                    viewModel.submitPinAttempt(pin, owner, onSuccess = {})
                  },
                  onLockVault = viewModel::lockVault,
                  onCaptureTestPhoto = { owner ->
                    viewModel.captureIntruderEvidence(
                      lifecycleOwner = owner,
                      reason = "Manual Security Test Capture",
                      attemptCount = 0,
                    )
                  },
                  onTestBackgroundCamera2 = {
                    viewModel.captureIntruderEvidence(
                      lifecycleOwner = null,
                      reason = "Headless Camera2 Lockscreen Test",
                      attemptCount = 0,
                    )
                  },
                  onSharePhoto = viewModel::shareEvidencePhoto,
                  onDeletePhoto = viewModel::deleteEvidencePhoto,
                  onRequestDeviceAdmin = { viewModel.requestDeviceAdmin(context) },
                  onOpenAppSettings = viewModel::openAppDetailsSettings,
                  onCameraPermissionGranted = {
                    viewModel.refreshSystemStates(lifecycleOwner)
                  },
                )
              }

              composable(TerraGuardDestination.ALERTS.route) {
                AlertsAndSettingsScreen(
                  userEmail = currentUserEmail,
                  settings = settings,
                  alertsState = alertsState,
                  onSendAlertEmail = viewModel::sendAlertViaEmailApp,
                  onOpenInMaps = viewModel::openInMaps,
                  onDeleteAlert = viewModel::deleteAlert,
                  onUpdateOwnerEmail = viewModel::updateOwnerEmail,
                  onUpdateWebhookUrl = viewModel::updateEmailWebhookUrl,
                  onToggleAutoEmailComposer = viewModel::setAutoLaunchEmailComposer,
                  onUpdateSmtpConfig = viewModel::updateSmtpConfig,
                  onSendTestEmail = viewModel::saveAndSendTestEmail,
                  onUpdateSmsConfig = viewModel::updateSmsRemoteConfig,
                  onUpdatePinThreshold = viewModel::updateFailedPinThreshold,
                  onUpdateDeviceName = viewModel::updateDeviceName,
                  onToggleDarkTheme = viewModel::setDarkThemeEnabled,
                  onSignOut = {
                    signOut(credentialManager = credentialManager, scope = scope)
                  },
                )
              }
            }
          }
        }
      }
    }

    if (showDisarmPinDialog) {
      AlertDialog(
        onDismissRequest = { showDisarmPinDialog = false },
        title = { Text("Disarm Emergency Alarm") },
        text = {
          SecurityPinKeypad(
            title = "Enter Security PIN",
            subtitle = "Confirm your 4-digit PIN to silence the siren.",
            wrongAttempts = wrongPinAttempts,
            maxAttempts = settings.failedPinThreshold,
            isSetupMode = false,
            onPinSubmitted = { pin ->
              viewModel.submitPinAttempt(
                pin = pin,
                lifecycleOwner = lifecycleOwner,
                onSuccess = {
                  showDisarmPinDialog = false
                  if (currentDeviceId.isNotBlank()) {
                    viewModel.triggerRemoteAlarm(currentDeviceId, false)
                  }
                },
              )
            },
          )
        },
        confirmButton = {},
        dismissButton = {
          TextButton(onClick = { showDisarmPinDialog = false }) { Text("Cancel") }
        },
      )
    }
  }
}

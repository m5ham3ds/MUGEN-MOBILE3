package com.example.ui.screens.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.engine.DownloadState
import com.example.engine.EngineDataDownloader
import com.example.engine.NativeEngineLoader
import com.example.storage.MugenMobileStorage
import com.example.ui.components.BounceButton
import com.example.ui.theme.LocalAppStrings
import java.io.File

@Composable
fun HomeScreen(
    hasPermission: Boolean,
    onLaunchBuiltInGame: () -> Unit,
    onNavigateToDiagnostics: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    
    var showLogs by remember { mutableStateOf(false) }
    var logContent by remember { mutableStateOf("") }
    
    var showMissingDataDialog by remember { mutableStateOf(false) }
    var showMissingEngineDialog by remember { mutableStateOf(false) }

    val downloadState by EngineDataDownloader.downloadState.collectAsState()
    val progressMessage by EngineDataDownloader.progressMessage.collectAsState()
    val progressPercentage by EngineDataDownloader.progressPercentage.collectAsState()

    fun triggerLaunch() {
        com.example.utils.CrashReporter.recordBreadcrumb("PLAY_BUTTON_CLICKED", "Built-in MUGEN / IKEMEN-GO launch requested")
        val sharedPrefs = context.getSharedPreferences("IkemenGo", android.content.Context.MODE_PRIVATE)
        val basePath = MugenMobileStorage.getBaseDir(context).absolutePath
        sharedPrefs.edit().putString("folder", basePath).apply()

        // Ensure characters are synchronized to data/select.def before launching engine
        try {
            val chars = com.example.engine.MugenParser.getCharacters(context)
            android.util.Log.i("HomeScreen", "Pre-launch character synchronization completed: ${chars.size} characters found")
        } catch (e: Exception) {
            android.util.Log.w("HomeScreen", "Non-fatal pre-launch sync notice: ${e.message}")
        }

        onLaunchBuiltInGame()
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            if (!hasPermission) {
                Text(
                    text = strings.storagePermissionRequired,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            Text(
                text = strings.readyToFight,
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(32.dp))

            // Show active download progress if downloading or extracting
            if (downloadState == DownloadState.DOWNLOADING || downloadState == DownloadState.EXTRACTING) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .padding(bottom = 24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = progressMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (downloadState == DownloadState.DOWNLOADING && progressPercentage > 0f) {
                            LinearProgressIndicator(
                                progress = { progressPercentage },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            BounceButton(
                onClick = {
                    com.example.utils.LiveLogger.i(
                        com.example.utils.LiveLogger.Category.ENGINE,
                        "START_BUTTON_PRESSED",
                        "START button pressed on HomeScreen"
                    )
                    com.example.utils.CrashReporter.recordBreadcrumb("PLAY_BUTTON_CLICKED", "START button pressed on HomeScreen")
                    val isDataInstalled = MugenMobileStorage.isEngineDataInstalled(context)
                    val hasEngine = NativeEngineLoader.hasEngineFiles(context)

                    if (!isDataInstalled) {
                        com.example.utils.LiveLogger.w(com.example.utils.LiveLogger.Category.ENGINE, "START_HALTED", "Base engine data not installed")
                        showMissingDataDialog = true
                    } else if (!hasEngine) {
                        com.example.utils.LiveLogger.w(com.example.utils.LiveLogger.Category.ENGINE, "START_HALTED", "Native engine libmain.so missing")
                        showMissingEngineDialog = true
                    } else {
                        triggerLaunch()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(64.dp)
            ) {
                Text(text = strings.start, style = MaterialTheme.typography.titleLarge)
            }
            
            Spacer(modifier = Modifier.height(20.dp))
            
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onNavigateToDiagnostics != null) {
                    Button(
                        onClick = onNavigateToDiagnostics,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text("التشخيص والسجلات الحية")
                    }
                }

                OutlinedButton(onClick = {
                    val logFile = File(MugenMobileStorage.getBaseDir(context), "mugen_crash.log")
                    logContent = if (logFile.exists()) {
                        val lines = logFile.readLines()
                        lines.takeLast(150).joinToString("\n")
                    } else {
                        strings.noCrashLogs
                    }
                    showLogs = true
                }) {
                    Text(strings.viewCrashLogs)
                }
            }
        }
    }

    // Dialog 1: Base Data Missing
    if (showMissingDataDialog) {
        AlertDialog(
            onDismissRequest = { showMissingDataDialog = false },
            title = { Text(strings.baseEngineData) },
            text = { Text(strings.downloadEngineDescription) },
            confirmButton = {
                Button(onClick = {
                    showMissingDataDialog = false
                    EngineDataDownloader.startDownload(context)
                }) {
                    Text(strings.downloadBaseData)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showMissingDataDialog = false
                    // Allow advanced user to launch anyway
                    triggerLaunch()
                }) {
                    Text(strings.start)
                }
            }
        )
    }

    // Dialog 2: Engine Binary Missing Warning
    if (showMissingEngineDialog) {
        AlertDialog(
            onDismissRequest = { showMissingEngineDialog = false },
            title = { Text("Native Engine Missing") },
            text = { 
                Text("IKEMEN-GO engine binary (libmain.so) was not detected in the installed application package. Launch is halted. Please reinstall the APK.")
            },
            confirmButton = {
                Button(onClick = { showMissingEngineDialog = false }) {
                    Text(strings.close)
                }
            }
        )
    }

    // Dialog 3: Crash Logs
    if (showLogs) {
        Dialog(
            onDismissRequest = { showLogs = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(modifier = Modifier.fillMaxSize().padding(16.dp), shape = MaterialTheme.shapes.medium) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(strings.crashLogs, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = logContent,
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { showLogs = false }, modifier = Modifier.align(Alignment.End)) {
                        Text(strings.close)
                    }
                }
            }
        }
    }
}

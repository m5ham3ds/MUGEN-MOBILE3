package com.example.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.DownloadState
import com.example.engine.EngineDataDownloader
import com.example.engine.gemini.GeminiApiClient
import com.example.engine.gemini.GeminiResult
import com.example.data.settings.SettingsDataStore
import com.example.storage.MugenMobileStorage
import com.example.storage.StorageDiagnosticInfo
import com.example.storage.StorageManager
import com.example.ui.theme.LocalAppStrings
import com.example.utils.GeminiKeyStoreManager
import com.example.utils.PermissionUtils
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.net.URLDecoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToControllerEditor: () -> Unit,
    onNavigateToDiagnostics: (() -> Unit)? = null,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val strings = LocalAppStrings.current

    // Observe DataStore
    val language by remember { SettingsDataStore.getLanguage(context) }.collectAsState(initial = "en")
    val themeMode by remember { SettingsDataStore.getThemeMode(context) }.collectAsState(initial = "dark")
    val primaryColor by remember { SettingsDataStore.getPrimaryColor(context) }.collectAsState(initial = "red")
    
    // Authoritative game directory state
    var customGamePath by remember { mutableStateOf(MugenMobileStorage.getBaseDirPath(context)) }
    var diagnostics by remember { mutableStateOf(MugenMobileStorage.getDiagnostics(context)) }
    var showDiagnosticDetails by remember { mutableStateOf(false) }
    
    val downloadState by EngineDataDownloader.downloadState.collectAsState()
    val progressPercentage by EngineDataDownloader.progressPercentage.collectAsState()
    val progressMessage by EngineDataDownloader.progressMessage.collectAsState()

    // Gemini API states
    var geminiApiKeyInput by remember { mutableStateOf("") }
    var geminiApiKeyVisible by remember { mutableStateOf(false) }
    var hasGeminiKey by remember { mutableStateOf(GeminiKeyStoreManager.hasApiKey(context)) }
    var maskedGeminiKey by remember { mutableStateOf(GeminiKeyStoreManager.getMaskedStoredKey(context)) }
    var geminiStatusMessage by remember { mutableStateOf<String?>(null) }
    var geminiStatusSuccess by remember { mutableStateOf<Boolean?>(null) }
    var isTestingGemini by remember { mutableStateOf(false) }

    val documentTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            StorageManager.takePersistableUriPermission(context, it)
            
            // Resolve filesystem path from SAF Tree Uri
            val resolved = StorageManager.getFullPathFromTreeUri(context, it)
            val finalPath = if (!resolved.isNullOrEmpty()) {
                resolved
            } else {
                val decoded = try { URLDecoder.decode(it.toString(), "UTF-8") } catch (_: Exception) { it.toString() }
                if (decoded.contains("Documents") && decoded.contains("MUGEN_MOBILE")) {
                    MugenMobileStorage.DEFAULT_PATH
                } else {
                    decoded
                }
            }
            
            MugenMobileStorage.setCustomBaseDir(context, finalPath, it.toString())
            customGamePath = finalPath
            diagnostics = MugenMobileStorage.getDiagnostics(context)
            Toast.makeText(context, "تم اعتماد مجلد اللعبة: $finalPath", Toast.LENGTH_SHORT).show()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            EngineDataDownloader.startDownload(context)
        }
    }

    LaunchedEffect(Unit) {
        customGamePath = MugenMobileStorage.getBaseDirPath(context)
        diagnostics = MugenMobileStorage.getDiagnostics(context)
        hasGeminiKey = GeminiKeyStoreManager.hasApiKey(context)
        maskedGeminiKey = GeminiKeyStoreManager.getMaskedStoredKey(context)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // --- LANGUAGE & APPEARANCE SECTION ---
        item {
            Text(strings.languageAndAppearance, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
        }

        item {
            var languageExpanded by remember { mutableStateOf(false) }
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                onClick = { languageExpanded = true }
            ) {
                Box {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(strings.language, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            Text(if (language == "ar") strings.arabic else strings.english, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                    DropdownMenu(expanded = languageExpanded, onDismissRequest = { languageExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(strings.english) },
                            onClick = { scope.launch { SettingsDataStore.setLanguage(context, "en") }; languageExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text(strings.arabic) },
                            onClick = { scope.launch { SettingsDataStore.setLanguage(context, "ar") }; languageExpanded = false }
                        )
                    }
                }
            }
        }

        item {
            var themeExpanded by remember { mutableStateOf(false) }
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                onClick = { themeExpanded = true }
            ) {
                Box {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.DarkMode, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(strings.themeMode, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            val currentTheme = when(themeMode) {
                                "light" -> strings.light
                                "dark" -> strings.dark
                                else -> strings.systemDefault
                            }
                            Text(currentTheme, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                    DropdownMenu(expanded = themeExpanded, onDismissRequest = { themeExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(strings.light) },
                            onClick = { scope.launch { SettingsDataStore.setThemeMode(context, "light") }; themeExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text(strings.dark) },
                            onClick = { scope.launch { SettingsDataStore.setThemeMode(context, "dark") }; themeExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text(strings.systemDefault) },
                            onClick = { scope.launch { SettingsDataStore.setThemeMode(context, "system") }; themeExpanded = false }
                        )
                    }
                }
            }
        }

        item {
            var colorExpanded by remember { mutableStateOf(false) }
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                onClick = { colorExpanded = true }
            ) {
                Box {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(strings.primaryColor, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            val currentColor = when(primaryColor) {
                                "purple" -> strings.purple
                                "blue" -> strings.blue
                                "green" -> strings.green
                                "yellow" -> strings.yellow
                                else -> strings.red
                            }
                            Text(currentColor, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                    DropdownMenu(expanded = colorExpanded, onDismissRequest = { colorExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(strings.red) },
                            onClick = { scope.launch { SettingsDataStore.setPrimaryColor(context, "red") }; colorExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text(strings.purple) },
                            onClick = { scope.launch { SettingsDataStore.setPrimaryColor(context, "purple") }; colorExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text(strings.blue) },
                            onClick = { scope.launch { SettingsDataStore.setPrimaryColor(context, "blue") }; colorExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text(strings.green) },
                            onClick = { scope.launch { SettingsDataStore.setPrimaryColor(context, "green") }; colorExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text(strings.yellow) },
                            onClick = { scope.launch { SettingsDataStore.setPrimaryColor(context, "yellow") }; colorExpanded = false }
                        )
                    }
                }
            }
        }

        // --- GAME DIRECTORY SECTION ---
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(strings.gameDirectory, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                TextButton(
                    onClick = {
                        MugenMobileStorage.setCustomBaseDir(context, MugenMobileStorage.DEFAULT_PATH, null)
                        customGamePath = MugenMobileStorage.DEFAULT_PATH
                        diagnostics = MugenMobileStorage.getDiagnostics(context)
                        Toast.makeText(context, "تمت استعادة المجلد الافتراضي: ${MugenMobileStorage.DEFAULT_PATH}", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("المجلد الافتراضي", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        item {
            Card(
                onClick = { documentTreeLauncher.launch(null) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(strings.gameDirectory, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSecondaryContainer)
                                Surface(
                                    color = if (diagnostics.isAccessSuccessful) Color(0xFF1B5E20) else Color(0xFFB71C1C),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = if (diagnostics.isAccessSuccessful) "جاهز ومتاح" else "بحاجة لصلاحية",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = Color.White)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = customGamePath,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${diagnostics.characterCount} شخصيات | ${diagnostics.stageCount} مراحل مكتشفة",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.primary
                        )
                        TextButton(
                            onClick = { showDiagnosticDetails = !showDiagnosticDetails },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                text = if (showDiagnosticDetails) "إخفاء التفاصيل" else "فحص التشخيص",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }

                    if (showDiagnosticDetails) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                .padding(8.dp)
                        ) {
                            Column {
                                Text("المسار الفعلي: ${diagnostics.actualPathUsed}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp))
                                if (!diagnostics.configuredUri.isNullOrBlank()) {
                                    Text("SAF URI: ${diagnostics.configuredUri}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 9.sp))
                                }
                                if (diagnostics.failureReason != null) {
                                    Text("السبب: ${diagnostics.failureReason}", style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, color = MaterialTheme.colorScheme.error))
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- BASE DATA DOWNLOAD SECTION ---
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(strings.baseEngineData, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            Text(strings.downloadEngineDescription, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    if (downloadState == DownloadState.DOWNLOADING || downloadState == DownloadState.EXTRACTING || downloadState == DownloadState.PAUSED) {
                        if (downloadState == DownloadState.EXTRACTING) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(
                                progress = { progressPercentage },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(progressMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        
                        if (downloadState == DownloadState.DOWNLOADING || downloadState == DownloadState.PAUSED) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                if (downloadState == DownloadState.PAUSED) {
                                    IconButton(onClick = { EngineDataDownloader.startDownload(context) }) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = "Resume")
                                    }
                                } else {
                                    IconButton(onClick = { EngineDataDownloader.pauseDownload() }) {
                                        Icon(Icons.Default.Pause, contentDescription = "Pause")
                                    }
                                }
                                IconButton(onClick = { EngineDataDownloader.cancelDownload() }) {
                                    Icon(Icons.Default.Close, contentDescription = "Cancel")
                                }
                            }
                        }
                    } else {
                        Button(
                            onClick = { 
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    EngineDataDownloader.startDownload(context)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(strings.downloadBaseData)
                        }
                        if (downloadState == DownloadState.SUCCESS || downloadState == DownloadState.ERROR) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(progressMessage, style = MaterialTheme.typography.bodySmall, color = if (downloadState == DownloadState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
        
        // --- CONTROLS SECTION ---
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Text(strings.controls, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
        }
        item {
            Card(
                onClick = onNavigateToControllerEditor,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.VideogameAsset, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(strings.editControllerLayout, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(strings.customizeControllerDescription, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }

        // --- GOOGLE GEMINI AI SECTION ---
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(strings.geminiAiTitle, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                }
                Surface(
                    color = when {
                        geminiStatusSuccess == true -> Color(0xFF1B5E20)
                        geminiStatusSuccess == false -> Color(0xFFB71C1C)
                        hasGeminiKey -> Color(0xFF2E7D32)
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = when {
                            geminiStatusSuccess == true -> strings.connectionSuccess
                            geminiStatusSuccess == false -> strings.connectionFailed
                            hasGeminiKey -> strings.keySaved
                            else -> strings.noKeyAdded
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (hasGeminiKey || geminiStatusSuccess != null) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    if (hasGeminiKey && !maskedGeminiKey.isNullOrEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "المفتاح المحفوظ المشفر:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                )
                                Text(
                                    text = maskedGeminiKey ?: "",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            FilledTonalButton(
                                onClick = {
                                    GeminiKeyStoreManager.deleteApiKey(context)
                                    hasGeminiKey = false
                                    maskedGeminiKey = null
                                    geminiApiKeyInput = ""
                                    geminiStatusMessage = strings.keyDeleted
                                    geminiStatusSuccess = null
                                    Toast.makeText(context, strings.keyDeleted, Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                )
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(strings.deleteKey, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    OutlinedTextField(
                        value = geminiApiKeyInput,
                        onValueChange = { geminiApiKeyInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(strings.geminiApiKey) },
                        placeholder = { Text(strings.enterGeminiApiKey) },
                        singleLine = true,
                        visualTransformation = if (geminiApiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { geminiApiKeyVisible = !geminiApiKeyVisible }) {
                                Icon(
                                    imageVector = if (geminiApiKeyVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = if (geminiApiKeyVisible) "Hide key" else "Show key"
                                )
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (geminiApiKeyInput.isNotBlank()) {
                                    val saved = GeminiKeyStoreManager.saveApiKey(context, geminiApiKeyInput)
                                    if (saved) {
                                        hasGeminiKey = true
                                        maskedGeminiKey = GeminiKeyStoreManager.getMaskedStoredKey(context)
                                        geminiApiKeyInput = ""
                                        geminiApiKeyVisible = false
                                        geminiStatusMessage = strings.keySaved
                                        geminiStatusSuccess = true
                                        Toast.makeText(context, strings.keySaved, Toast.LENGTH_SHORT).show()
                                    } else {
                                        geminiStatusMessage = "فشل تشفير المفتاح وتخزينه"
                                        geminiStatusSuccess = false
                                        Toast.makeText(context, "فشل تشفير المفتاح وتخزينه", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(context, strings.enterGeminiApiKey, Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = geminiApiKeyInput.isNotBlank()
                        ) {
                            Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.saveKey)
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isTestingGemini = true
                                    geminiStatusMessage = strings.testingConnection
                                    geminiStatusSuccess = null
                                    val keyToTest = if (geminiApiKeyInput.isNotBlank()) geminiApiKeyInput.trim() else GeminiKeyStoreManager.getApiKey(context)
                                    if (keyToTest.isNullOrBlank()) {
                                        isTestingGemini = false
                                        geminiStatusMessage = strings.noKeyAdded
                                        geminiStatusSuccess = false
                                        return@launch
                                    }
                                    val res = GeminiApiClient.testConnection(keyToTest)
                                    isTestingGemini = false
                                    when (res) {
                                        is GeminiResult.Success -> {
                                            geminiStatusMessage = "${strings.connectionSuccess}: ${res.data}"
                                            geminiStatusSuccess = true
                                        }
                                        is GeminiResult.Error -> {
                                            geminiStatusMessage = "${strings.connectionFailed}: ${res.message}"
                                            geminiStatusSuccess = false
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isTestingGemini && (geminiApiKeyInput.isNotBlank() || hasGeminiKey)
                        ) {
                            if (isTestingGemini) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.testConnection)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/app/apikey"))
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(strings.createApiKey)
                    }

                    if (geminiStatusMessage != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            color = when (geminiStatusSuccess) {
                                true -> Color(0xFF1B5E20).copy(alpha = 0.2f)
                                false -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = geminiStatusMessage ?: "",
                                modifier = Modifier.padding(10.dp),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = when (geminiStatusSuccess) {
                                        true -> Color(0xFF2E7D32)
                                        false -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Privacy & Ownership Disclaimer
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                            .padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp).padding(top = 2.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = strings.geminiDisclaimer,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }

        // --- DIAGNOSTICS & LIVE LOGS SECTION ---
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Text("التشخيص والمراقبة الحية", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
        }
        item {
            Card(
                onClick = { onNavigateToDiagnostics?.invoke() },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text("منظومة السجل الحي و Logcat", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text("عرض سجل mugen_live.log الحي، وتدفق Logcat المباشر، وتصدير التقارير", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }
    }
}

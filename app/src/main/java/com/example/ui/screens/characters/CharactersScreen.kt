package com.example.ui.screens.characters

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.engine.CharacterData
import com.example.engine.CharacterValidationStatus
import com.example.engine.CnsEditor
import com.example.engine.MugenParser
import com.example.engine.SelectDefAnalysis
import com.example.engine.SelectDefManager
import com.example.engine.workspace.BatchRestorePlan
import com.example.engine.workspace.CharacterMetadata
import com.example.engine.workspace.CharacterWorkspaceManager
import com.example.engine.workspace.CharacterWorkspaceStatus
import com.example.storage.MugenMobileStorage
import com.example.storage.StorageAccessStatus
import com.example.storage.StorageDiagnosticInfo
import com.example.storage.StorageManager
import com.example.ui.utils.bounceClick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder

@Composable
fun CharactersScreen(hasPermission: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var characters by remember { mutableStateOf<List<CharacterData>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedCharacter by remember { mutableStateOf<CharacterData?>(null) }
    var accessStatus by remember { mutableStateOf<StorageAccessStatus?>(null) }
    var diagnostics by remember { mutableStateOf<StorageDiagnosticInfo?>(null) }
    var selectDefAnalysis by remember { mutableStateOf<SelectDefAnalysis?>(null) }
    var showBatchRestoreDialog by remember { mutableStateOf(false) }
    var showRosterBalancingDialog by remember { mutableStateOf(false) }

    suspend fun reloadData() {
        isLoading = true
        withContext(Dispatchers.IO) {
            accessStatus = MugenMobileStorage.checkStorageAccess(context)
            diagnostics = MugenMobileStorage.getDiagnostics(context)
            characters = MugenParser.getCharacters(context)
            val selectDefFile = SelectDefManager.getSelectDefFile(context)
            selectDefAnalysis = SelectDefManager.analyzeSelectDef(selectDefFile)
        }
        isLoading = false
    }

    val documentTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            StorageManager.takePersistableUriPermission(context, it)
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
            Toast.makeText(context, "تم تحديث مجلد اللعبة: $finalPath", Toast.LENGTH_SHORT).show()
            scope.launch { reloadData() }
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            reloadData()
        } else {
            isLoading = false
        }
    }

    if (!hasPermission) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(12.dp))
                Text("Storage permission is required.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            }
        }
        return
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val charsDirPath = MugenMobileStorage.getCharsDir(context).absolutePath
    val validCharactersCount = characters.count { it.isValid }
    val registeredInEngineCount = selectDefAnalysis?.registeredKeys?.size ?: 0

    if (characters.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                if (accessStatus?.isAccessible == false) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("تعذر الوصول إلى مجلد اللعبة المحدد", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "المسار المستهدف: $charsDirPath",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "السبب: ${accessStatus?.errorReason ?: "خطأ في الصلاحيات أو المجلد غير موجود"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                } else {
                    Text("No characters found.", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Place character folders inside:\n$charsDirPath",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { scope.launch { reloadData() } },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Re-scan", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("إعادة الفحص")
                    }

                    OutlinedButton(
                        onClick = { documentTreeLauncher.launch(null) },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = "Change Folder", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("اختيار مجلد آخر")
                    }
                }
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            // Status bar showing paths and select.def sync stats
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    // Top Row: Path display & Compact Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "المسار: $charsDirPath",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Compact Action Buttons Row
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            FilledTonalIconButton(
                                onClick = { showRosterBalancingDialog = true },
                                modifier = Modifier.size(36.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = "موازنة وتناغم القائمة", modifier = Modifier.size(18.dp))
                            }
                            FilledTonalIconButton(
                                onClick = { showBatchRestoreDialog = true },
                                modifier = Modifier.size(36.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = Color(0xFF2E7D32).copy(alpha = 0.15f),
                                    contentColor = Color(0xFF2E7D32)
                                )
                            ) {
                                Icon(Icons.Default.Security, contentDescription = "خزينة الأصول المحمية", modifier = Modifier.size(18.dp))
                            }
                            FilledTonalIconButton(
                                onClick = {
                                    scope.launch {
                                        reloadData()
                                        Toast.makeText(context, "تمت مزامنة $registeredInEngineCount شخصيات مع select.def", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.size(36.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(Icons.Default.Sync, contentDescription = "Sync", modifier = Modifier.size(18.dp))
                            }
                            FilledTonalIconButton(
                                onClick = { scope.launch { reloadData() } },
                                modifier = Modifier.size(36.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Bottom Row: Stats Chips using FlowRow to adapt flexibly to mobile screen widths
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "مكتشفة: ${characters.size} ($validCharactersCount صالحة)",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Surface(
                            color = if (registeredInEngineCount >= validCharactersCount) Color(0xFF2E7D32).copy(alpha = 0.12f) else MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "مسجلة بالمحرك (select.def): $registeredInEngineCount",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (registeredInEngineCount >= validCharactersCount) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(characters) { charData ->
                    CharacterCard(charData) {
                        selectedCharacter = charData
                    }
                }
            }
        }
    }

    if (showRosterBalancingDialog) {
        RosterBalancingDialog(
            characters = characters,
            onDismiss = { showRosterBalancingDialog = false },
            onApplied = {
                scope.launch { reloadData() }
            }
        )
    }

    if (showBatchRestoreDialog) {
        BatchRestoreDialog(
            onDismiss = { showBatchRestoreDialog = false },
            onRestored = {
                showBatchRestoreDialog = false
                scope.launch { reloadData() }
            }
        )
    }

    selectedCharacter?.let { char ->
        CompleteCharacterEditorDialog(
            character = char,
            onDismiss = { selectedCharacter = null },
            onCharacterUpdated = {
                scope.launch { reloadData() }
            }
        )
    }
}

@Composable
fun CharacterCard(character: CharacterData, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.75f)
            .bounceClick()
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                if (character.imageUri != null) {
                    AsyncImage(
                        model = character.imageUri,
                        contentDescription = character.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }

                // Vault Status Badge in Top Left
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                ) {
                    when (character.workspaceStatus) {
                        CharacterWorkspaceStatus.MODIFIED -> {
                            Surface(
                                color = Color(0xFFE65100).copy(alpha = 0.9f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "معدلة",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                        CharacterWorkspaceStatus.RESTORED -> {
                            Surface(
                                color = Color(0xFF0277BD).copy(alpha = 0.9f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "مستعادة",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                        else -> {
                            Surface(
                                color = Color(0xFF1B5E20).copy(alpha = 0.75f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "أصلية",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                // Status Badge in Top Right
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                ) {
                    when (character.validationStatus) {
                        CharacterValidationStatus.VALID -> {
                            Surface(
                                color = Color(0xFF2E7D32).copy(alpha = 0.85f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "Ready",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                        CharacterValidationStatus.VALID_MISSING_PORTRAIT -> {
                            Surface(
                                color = Color(0xFFF57F17).copy(alpha = 0.9f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "No Icon",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                        else -> {
                            Surface(
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.9f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "Error",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = character.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "ID: ${character.selectEntry}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun CharacterDynamicEditDialog(
    character: CharacterData,
    onDismiss: () -> Unit,
    onCharacterUpdated: () -> Unit = {}
) {
    CompleteCharacterEditorDialog(
        character = character,
        onDismiss = onDismiss,
        onCharacterUpdated = onCharacterUpdated
    )
}

@Composable
fun BatchRestoreDialog(
    onDismiss: () -> Unit,
    onRestored: () -> Unit
) {
    val context = LocalContext.current
    val plan = remember { CharacterWorkspaceManager.planBatchRestore(context) }
    var isRestoring by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "خزينة الأصول المحمية (Immutable Vault)",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "إجمالي الشخصيات في الخزينة: ${plan.totalCharacters}",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(
                                text = "معدلة: ${plan.modifiedCharacters.size}",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = if (plan.modifiedCharacters.isNotEmpty()) Color(0xFFE65100) else Color(0xFF2E7D32)
                            )
                            Text(
                                text = "نقية: ${plan.pristineCharacters.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF2E7D32)
                            )
                            if (plan.corruptedCharacters.isNotEmpty()) {
                                Text(
                                    text = "تالفة: ${plan.corruptedCharacters.size}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (plan.modifiedCharacters.isEmpty()) {
                    Text(
                        text = "جميع الشخصيات المسجلة مطابقة تماماً لبصمات الخزينة الأصلية (Pristine). لا توجد شخصيات معدلة بحاجة للاستعادة.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "الشخصيات المعدلة حالياً (${plan.modifiedCharacters.size}):",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = plan.modifiedCharacters.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = Color(0xFFE65100),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss, enabled = !isRestoring) {
                        Text("إغلاق")
                    }
                    if (plan.modifiedCharacters.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                isRestoring = true
                                val report = CharacterWorkspaceManager.executeBatchRestore(null, context)
                                isRestoring = false
                                if (report.success) {
                                    Toast.makeText(context, "تمت استعادة ${report.restoredCharacters.size} مقاتلين بنجاح!", Toast.LENGTH_SHORT).show()
                                    onRestored()
                                } else {
                                    Toast.makeText(context, "فشلت استعادة بعض الشخصيات: ${report.failedCharacters.size}", Toast.LENGTH_LONG).show()
                                    onRestored()
                                }
                            },
                            enabled = !isRestoring,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            if (isRestoring) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text("استعادة الكل للأصل (${plan.modifiedCharacters.size})")
                        }
                    }
                }
            }
        }
    }
}

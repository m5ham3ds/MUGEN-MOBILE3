package com.example.ui.screens.stages

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.engine.MugenParser
import com.example.engine.SelectDefManager
import com.example.engine.StageData
import com.example.engine.StageValidationStatus
import com.example.storage.MugenMobileStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class StageFilter(val label: String) {
    ALL("الكل"),
    REGISTERED("المسجلة"),
    UNREGISTERED("غير مسجلة"),
    READY("جاهزة للقتال")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StagesScreen(hasPermission: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var stages by remember { mutableStateOf<List<StageData>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedFilter by remember { mutableStateOf(StageFilter.ALL) }
    var searchQuery by remember { mutableStateOf("") }

    fun refreshStages() {
        scope.launch {
            isLoading = true
            withContext(Dispatchers.IO) {
                stages = MugenParser.getStages(context)
            }
            isLoading = false
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            refreshStages()
        } else {
            isLoading = false
        }
    }

    if (!hasPermission) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("صلاحية التخزين مطلوبة لاستكشاف المراحل.")
        }
        return
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("جاري استكشاف وفحص المراحل...", style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }

    val filteredStages = remember(stages, selectedFilter, searchQuery) {
        stages.filter { stage ->
            val matchesFilter = when (selectedFilter) {
                StageFilter.ALL -> true
                StageFilter.REGISTERED -> stage.isRegisteredInSelectDef
                StageFilter.UNREGISTERED -> !stage.isRegisteredInSelectDef
                StageFilter.READY -> stage.status == StageValidationStatus.READY
            }
            val matchesQuery = searchQuery.isBlank() ||
                    stage.displayName.contains(searchQuery, ignoreCase = true) ||
                    stage.relativePath.contains(searchQuery, ignoreCase = true) ||
                    (stage.author?.contains(searchQuery, ignoreCase = true) == true)
            matchesFilter && matchesQuery
        }
    }

    val totalCount = stages.size
    val readyCount = stages.count { it.status == StageValidationStatus.READY }
    val registeredCount = stages.count { it.isRegisteredInSelectDef }

    Column(modifier = Modifier.fillMaxSize()) {
        // --- TOP CONTROLS & SEARCH ---
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "المراحل المكتشفة ($totalCount)",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "$readyCount جاهزة للقتال • $registeredCount مسجلة في select.def",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val readyStages = stages.filter { it.status == StageValidationStatus.READY }
                                val added = SelectDefManager.syncAllReadyStages(context, readyStages)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        if (added > 0) "تمت إضافة $added مرحلة جديدة إلى select.def" else "جميع المراحل الجاهزة مسجلة مسبقاً",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    refreshStages()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.PlaylistAddCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("تسجيل الكل")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("بحث عن مرحلة بالاسم أو المسار...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Horizontally scrollable filter chips to prevent cramped overflowing on mobile
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StageFilter.values().forEach { filter ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = {
                                val count = when (filter) {
                                    StageFilter.ALL -> totalCount
                                    StageFilter.REGISTERED -> registeredCount
                                    StageFilter.UNREGISTERED -> totalCount - registeredCount
                                    StageFilter.READY -> readyCount
                                }
                                Text("${filter.label} ($count)")
                            }
                        )
                    }
                }
            }
        }

        // --- STAGES GRID ---
        if (filteredStages.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Map,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (stages.isEmpty()) "لم يتم العثور على مراحل." else "لا توجد مراحل مطابقة لمعايير البحث.",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "ضع ملفات المراحل (.def و .sff) في:\n${MugenMobileStorage.getStagesDir(context).absolutePath}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 240.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(filteredStages, key = { it.relativePath }) { stage ->
                    StageCard(
                        stage = stage,
                        onToggleRegistration = {
                            scope.launch(Dispatchers.IO) {
                                if (stage.isRegisteredInSelectDef) {
                                    SelectDefManager.removeExtraStage(context, stage.relativePath)
                                } else {
                                    SelectDefManager.addExtraStage(context, stage.relativePath)
                                }
                                withContext(Dispatchers.Main) {
                                    refreshStages()
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun StageCard(
    stage: StageData,
    onToggleRegistration: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Stage Thumbnail Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .background(Color(0xFF1E1E24))
            ) {
                if (stage.imageUri != null) {
                    AsyncImage(
                        model = stage.imageUri,
                        contentDescription = stage.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Map,
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                            tint = Color.White.copy(alpha = 0.45f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stage.displayName,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Status chip on top corner
                val statusColor = when (stage.status) {
                    StageValidationStatus.READY -> Color(0xFF2E7D32)
                    StageValidationStatus.MISSING_SFF -> Color(0xFFEF6C00)
                    StageValidationStatus.CORRUPTED_DEF -> Color(0xFFC62828)
                }

                Surface(
                    color = statusColor,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier
                        .padding(8.dp)
                        .align(Alignment.TopEnd)
                ) {
                    Text(
                        text = stage.validationMessage,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Info & Actions Section
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stage.displayName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (!stage.author.isNullOrBlank()) {
                    Text(
                        text = "المؤلف: ${stage.author}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stage.relativePath,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 9.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Toggle select.def registration button
                if (stage.isRegisteredInSelectDef) {
                    Button(
                        onClick = onToggleRegistration,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("مسجلة في اللعبة (إلغاء)")
                    }
                } else {
                    OutlinedButton(
                        onClick = onToggleRegistration,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = stage.status == StageValidationStatus.READY
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("إضافة إلى select.def")
                    }
                }
            }
        }
    }
}

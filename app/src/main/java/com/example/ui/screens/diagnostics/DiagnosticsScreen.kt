package com.example.ui.screens.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.storage.MugenMobileStorage
import com.example.utils.CrashReporter
import com.example.utils.LiveLogger
import com.example.utils.LogcatEntry
import com.example.utils.LogcatStreamer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DiagnosticsTab(val label: String) {
    LIVE_LOG("السجل الحي (mugen_live.log)"),
    LOGCAT("عارض Logcat المباشر"),
    SYSTEM("بيئة النظام والملفات")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(DiagnosticsTab.LIVE_LOG) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("منظومة التشخيص والمراقبة") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "رجوع")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Tab Selector
            TabRow(selectedTabIndex = selectedTab.ordinal) {
                DiagnosticsTab.values().forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }

            when (selectedTab) {
                DiagnosticsTab.LIVE_LOG -> LiveLogView(context)
                DiagnosticsTab.LOGCAT -> LogcatView(context)
                DiagnosticsTab.SYSTEM -> SystemDiagnosticView(context)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveLogView(context: Context) {
    val scope = rememberCoroutineScope()
    var logLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    var selectedLevel by remember { mutableStateOf("ALL") }
    var selectedCategory by remember { mutableStateOf("ALL") }
    var searchQuery by remember { mutableStateOf("") }

    val listState = rememberLazyListState()

    fun loadLogs() {
        scope.launch {
            isLoading = true
            val lines = withContext(Dispatchers.IO) {
                LiveLogger.readRecentLines(context, 1000)
            }
            logLines = lines
            isLoading = false
            if (lines.isNotEmpty()) {
                listState.scrollToItem(lines.size - 1)
            }
        }
    }

    LaunchedEffect(Unit) {
        loadLogs()
    }

    val filteredLines = remember(logLines, selectedLevel, selectedCategory, searchQuery) {
        logLines.filter { line ->
            val matchesLevel = when (selectedLevel) {
                "ALL" -> true
                "INFO" -> line.contains(" [I] ")
                "DEBUG" -> line.contains(" [D] ")
                "WARN" -> line.contains(" [W] ")
                "ERROR" -> line.contains(" [E] ") || line.contains(" [F] ")
                else -> true
            }
            val matchesCategory = when (selectedCategory) {
                "ALL" -> true
                else -> line.contains(" [$selectedCategory] ")
            }
            val matchesSearch = searchQuery.isBlank() || line.contains(searchQuery, ignoreCase = true)
            matchesLevel && matchesCategory && matchesSearch
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        // Toolbar actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "سجلات دورة الحياة (${filteredLines.size} / ${logLines.size})",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = { loadLogs() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "تحديث")
                }

                IconButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("LiveLog", filteredLines.joinToString("\n")))
                    Toast.makeText(context, "تم نسخ السجل إلى الحافظة", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "نسخ")
                }

                IconButton(onClick = {
                    val uri = LiveLogger.getShareableUri(context)
                    if (uri != null) {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "مشاركة سجل التشخيص الحي"))
                    } else {
                        Toast.makeText(context, "ملف السجل فارغ أو غير متاح", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(Icons.Default.Share, contentDescription = "تصدير ومشاركة")
                }

                IconButton(onClick = {
                    LiveLogger.clearLogs(context)
                    loadLogs()
                    Toast.makeText(context, "تم مسح السجل", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.Delete, contentDescription = "مسح", tint = MaterialTheme.colorScheme.error)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Search & Filter
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("بحث في سجل التطبيق الحي...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = null)
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Filter chips row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("ALL", "INFO", "DEBUG", "WARN", "ERROR").forEach { lvl ->
                FilterChip(
                    selected = selectedLevel == lvl,
                    onClick = { selectedLevel = lvl },
                    label = { Text(lvl, fontSize = 11.sp) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Log display area
        Card(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0F0F))
        ) {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (filteredLines.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("لا توجد أحداث مطابقة في السجل الحي", color = Color.Gray, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(8.dp)
                ) {
                    items(filteredLines) { line ->
                        val lineTextColor = when {
                            line.contains(" [F] ") || line.contains(" [E] ") -> Color(0xFFFF5252)
                            line.contains(" [W] ") -> Color(0xFFFFB74D)
                            line.contains(" [I] ") -> Color(0xFF81C784)
                            else -> Color(0xFFE0E0E0)
                        }
                        Text(
                            text = line,
                            color = lineTextColor,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogcatView(context: Context) {
    val scope = rememberCoroutineScope()
    val entries by LogcatStreamer.entries.collectAsState()
    val isStreaming by LogcatStreamer.isStreaming.collectAsState()

    var selectedLevel by remember { mutableStateOf("ALL") }
    var selectedTag by remember { mutableStateOf("ALL") }
    var searchQuery by remember { mutableStateOf("") }
    var autoScroll by remember { mutableStateOf(true) }

    val listState = rememberLazyListState()

    DisposableEffect(Unit) {
        LogcatStreamer.startStreaming()
        onDispose {
            // Keep running or pause
        }
    }

    LaunchedEffect(entries.size, autoScroll) {
        if (autoScroll && entries.isNotEmpty()) {
            listState.scrollToItem(entries.size - 1)
        }
    }

    val filteredEntries = remember(entries, selectedLevel, selectedTag, searchQuery) {
        entries.filter { entry ->
            val matchesLevel = when (selectedLevel) {
                "ALL" -> true
                "V" -> entry.level == "V"
                "D" -> entry.level == "D"
                "I" -> entry.level == "I"
                "W" -> entry.level == "W"
                "E" -> entry.level == "E" || entry.level == "F"
                else -> true
            }
            val matchesTag = when (selectedTag) {
                "ALL" -> true
                "IKEMEN" -> entry.raw.contains("ikemen", ignoreCase = true) || entry.tag.contains("ikemen", ignoreCase = true)
                "SDL" -> entry.raw.contains("SDL", ignoreCase = true) || entry.tag.contains("SDL", ignoreCase = true)
                "MUGEN" -> entry.raw.contains("MUGEN", ignoreCase = true) || entry.tag.contains("MUGEN", ignoreCase = true)
                "CRASH" -> entry.raw.contains("crash", ignoreCase = true) || entry.level == "E" || entry.level == "F"
                else -> true
            }
            val matchesSearch = searchQuery.isBlank() || entry.raw.contains(searchQuery, ignoreCase = true)
            matchesLevel && matchesTag && matchesSearch
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        // Controls row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = if (isStreaming) Color(0xFF2E7D32) else Color(0xFFEF6C00),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = if (isStreaming) "مباشر LIVE" else "متوقف مؤقتاً",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("Logcat (${filteredEntries.size} سطر)")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = {
                    if (isStreaming) LogcatStreamer.pauseStreaming() else LogcatStreamer.resumeStreaming()
                }) {
                    Icon(
                        imageVector = if (isStreaming) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Pause/Resume"
                    )
                }

                IconButton(onClick = { autoScroll = !autoScroll }) {
                    Icon(
                        imageVector = if (autoScroll) Icons.Default.VerticalAlignBottom else Icons.Default.VerticalAlignCenter,
                        contentDescription = "Auto Scroll",
                        tint = if (autoScroll) MaterialTheme.colorScheme.primary else Color.Gray
                    )
                }

                IconButton(onClick = {
                    val uri = LogcatStreamer.exportLogcat(context)
                    if (uri != null) {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "مشاركة سجل Logcat"))
                    } else {
                        Toast.makeText(context, "لا توجد أسطر لتصديرها", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(Icons.Default.Share, contentDescription = "تصدير")
                }

                IconButton(onClick = { LogcatStreamer.clearBuffer() }) {
                    Icon(Icons.Default.ClearAll, contentDescription = "تفريغ المخزن")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("بحث في تدفق Logcat الحقيقي...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = null)
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Tag filter chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("ALL", "IKEMEN", "SDL", "MUGEN", "CRASH").forEach { tag ->
                FilterChip(
                    selected = selectedTag == tag,
                    onClick = { selectedTag = tag },
                    label = { Text(tag, fontSize = 11.sp) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Logcat Terminal Output
        Card(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0A0A))
        ) {
            if (filteredEntries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("بانتظار وصول رسائل Logcat...", color = Color.Gray, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(8.dp)
                ) {
                    items(filteredEntries, key = { it.id }) { entry ->
                        val lineColor = when (entry.level) {
                            "E", "F" -> Color(0xFFFF5252)
                            "W" -> Color(0xFFFFB74D)
                            "I" -> Color(0xFF64B5F6)
                            "V" -> Color(0xFF9E9E9E)
                            else -> Color(0xFFE0E0E0)
                        }
                        Text(
                            text = entry.raw,
                            color = lineColor,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SystemDiagnosticView(context: Context) {
    val diag = remember { MugenMobileStorage.getDiagnostics(context) }
    val latestCrash = remember { CrashReporter.getUnreviewedCrashReport(context) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("بيئة التشغيل والنظام", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("معلومات الجلسة والعملية الحالية:", fontWeight = FontWeight.Bold)
                    Text("معرف الجلسة (Session ID): ${LiveLogger.getSessionId()}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text("رقم العملية (PID): ${LiveLogger.getPid()}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text("مسار التخزين القياسي (filesDir): ${context.filesDir.absolutePath}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text("مسار ملف السجل الحي: ${LiveLogger.getLogFile(context).absolutePath}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("حالة مسار لعبة MUGEN MOBILE:", fontWeight = FontWeight.Bold)
                    Text("المسار المستهدف: ${MugenMobileStorage.getBaseDirPath(context)}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text("المسار الفعلي المستخدم: ${diag.actualPathUsed}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text("عدد الشخصيات المكتشفة: ${diag.characterCount}")
                    Text("حالة الوصول: ${if (diag.isAccessSuccessful) "ناجح ومتاح ✓" else "بحاجة لصلاحية ✗"}")
                    if (diag.failureReason != null) {
                        Text("السبب: ${diag.failureReason}", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        if (latestCrash != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("آخر تقرير عطل تم تسجيله:", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text("النوع: ${latestCrash.errorType}", fontWeight = FontWeight.Medium)
                        Text("الرسالة: ${latestCrash.errorMessage}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("المرحلة: ${latestCrash.launchStage}")
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = { CrashReporter.launchCrashActivity(context, latestCrash) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("فتح التقرير في شاشة فحص الأعطال (CrashActivity)")
                        }
                    }
                }
            }
        }
    }
}

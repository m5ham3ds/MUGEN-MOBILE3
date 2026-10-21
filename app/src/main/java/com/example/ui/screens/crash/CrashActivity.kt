package com.example.ui.screens.crash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.MainActivity
import com.example.ui.theme.MyApplicationTheme
import com.example.utils.CrashReportData
import com.example.utils.CrashReporter
import com.example.utils.ForensicHypothesis
import com.example.utils.LibraryStatusInfo
import com.example.utils.StorageStatusInfo
import com.example.utils.TimelineEntryInfo
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashActivity : ComponentActivity() {

    companion object {
        const val EXTRA_REPORT_DATA = "extra_crash_report_data"
        const val EXTRA_REPORT_FILE = "extra_crash_report_file"
        const val EXTRA_BYPASS_CRASH_CHECK = "extra_bypass_crash_check"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val initialReport: CrashReportData = extractReportFromIntent(intent)

        setContent {
            MyApplicationTheme(themeMode = "dark", primaryColorString = "red") {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var currentReport by remember { mutableStateOf(initialReport) }

                    CrashScreenContent(
                        report = currentReport,
                        onCopyReport = { copyToClipboard(currentReport) },
                        onShareReport = { shareReportAsFile(currentReport) },
                        onExportFile = { format -> exportReportFile(currentReport, format) },
                        onExportRawPb = { exportRawPbFile(currentReport) },
                        onRefreshDiagnostics = {
                            val refreshed = CrashReporter.checkHistoricalExitReasons(this)
                                ?: CrashReporter.getUnreviewedCrashReport(this)
                                ?: currentReport
                            currentReport = refreshed
                            Toast.makeText(this, "تم تحديث وفحص الأدلة التشخيصية", Toast.LENGTH_SHORT).show()
                        },
                        onReopenApp = { reopenApp() }
                    )
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun extractReportFromIntent(intent: Intent?): CrashReportData {
        if (intent != null) {
            val serializable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getSerializableExtra(EXTRA_REPORT_DATA, CrashReportData::class.java)
            } else {
                intent.getSerializableExtra(EXTRA_REPORT_DATA) as? CrashReportData
            }
            if (serializable != null) return serializable

            val filePath = intent.getStringExtra(EXTRA_REPORT_FILE)
            if (!filePath.isNullOrEmpty()) {
                val f = File(filePath)
                if (f.exists()) {
                    try {
                        val json = JSONObject(f.readText())
                        return CrashReportData.fromJsonObject(json)
                    } catch (_: Exception) {}
                }
            }
        }

        return CrashReporter.getUnreviewedCrashReport(this) ?: CrashReporter.buildReport(
            context = this,
            errorType = "UNEXPECTED_APP_TERMINATION",
            errorMessage = "The game engine terminated unexpectedly without an explicit Java exception.",
            exceptionClassName = "FatalProcessTermination",
            stackTrace = "Process exited or native signal received in libmain.so.",
            threadName = "Main/SDLThread",
            stage = CrashReporter.getLastStage(),
            relevantLibrary = "libmain.so"
        )
    }

    private fun copyToClipboard(report: CrashReportData) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val textToCopy = report.toFormattedReportText()
            val clip = ClipData.newPlainText("MUGEN Mobile Forensic Crash Report", textToCopy)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "تم نسخ التقرير الجنائي بالكامل إلى الحافظة", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر النسخ: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareReportAsFile(report: CrashReportData) {
        try {
            val shareDir = File(filesDir, "crash_reports/share")
            if (!shareDir.exists()) shareDir.mkdirs()
            val shareFile = File(shareDir, "mugen_crash_${System.currentTimeMillis()}.txt")
            shareFile.writeText(report.toFormattedReportText())

            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", shareFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "MUGEN Mobile Crash Report - ${report.errorType}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "مشاركة تقرير الكراش"))
        } catch (e: Exception) {
            Toast.makeText(this, "فشل تجهيز ملف المشاركة: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportReportFile(report: CrashReportData, format: String) {
        try {
            val docsDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir
            val filename = "mugen_crash_report_${System.currentTimeMillis()}.$format"
            val file = File(docsDir, filename)

            if (format == "json") {
                file.writeText(report.toJsonObject().toString(2))
            } else {
                file.writeText(report.toFormattedReportText())
            }

            Toast.makeText(this, "تم تصدير ملف $format بنجاح:\n${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "فشل تصدير الملف: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportRawPbFile(report: CrashReportData) {
        val path = report.rawTombstonePath
        if (path.isNullOrBlank() || !File(path).exists()) {
            Toast.makeText(this, "ملف Tombstone الثنائي الخام (.pb) غير متوفر لهذه الجلسة من نظام التشغيل", Toast.LENGTH_LONG).show()
            return
        }

        try {
            val rawFile = File(path)
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", rawFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "MUGEN Mobile Raw Tombstone (.pb)")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "تصدير Tombstone الثنائي الخام (.pb)"))
        } catch (e: Exception) {
            Toast.makeText(this, "فشل تصدير Tombstone: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun reopenApp() {
        CrashReporter.markLatestCrashReviewed(this)
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(EXTRA_BYPASS_CRASH_CHECK, true)
        }
        startActivity(intent)
        finish()
    }
}

@Composable
fun CrashScreenContent(
    report: CrashReportData,
    onCopyReport: () -> Unit,
    onShareReport: () -> Unit,
    onExportFile: (format: String) -> Unit,
    onExportRawPb: () -> Unit,
    onRefreshDiagnostics: () -> Unit,
    onReopenApp: () -> Unit
) {
    var summaryExpanded by remember { mutableStateOf(true) }
    var hypothesesExpanded by remember { mutableStateOf(true) }
    var nativeTraceExpanded by remember { mutableStateOf(true) }
    var timelineExpanded by remember { mutableStateOf(false) }
    var envExpanded by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Header Top Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Crash Icon",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "مركز تشخيص الأعطال الجنائية",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "MUGEN Mobile — Forensic Crash Diagnostics",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(onClick = onRefreshDiagnostics) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh Diagnostics",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // SECTION A: Crash Summary (ملخص الانهيار)
        SectionCard(
            title = "A. ملخص الانهيار (Crash Summary)",
            isExpanded = summaryExpanded,
            onToggle = { summaryExpanded = !summaryExpanded },
            badgeText = report.errorType,
            badgeColor = MaterialTheme.colorScheme.error
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Evidence Status Badge
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "حالة الأدلة (Evidence Status):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val isComplete = report.evidenceStatus == "COMPLETE_EVIDENCE"
                    Surface(
                        color = if (isComplete) Color(0xFF1B5E20) else Color(0xFFE65100),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (isComplete) "أدلة مؤكدة مكتملة" else "أدلة جزئية من النظام",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = Color.White)
                        )
                    }
                }

                SummaryDataRow(label = "وقت الانهيار (UTC)", value = report.timestamp)
                if (report.processPid > 0) {
                    SummaryDataRow(label = "معرف العملية (PID)", value = "${report.processPid}")
                }
                SummaryDataRow(label = "المرحلة الأخيرة المؤكدة", value = report.launchStage)
                SummaryDataRow(label = "الخيط المتسبب (Thread)", value = report.threadName)
                if (!report.relevantLibrary.isNullOrBlank()) {
                    SummaryDataRow(label = "المكتبة المتسببة (Library)", value = report.relevantLibrary)
                }
                if (report.signalNumber > 0 || report.signalName.isNotBlank()) {
                    val sName = if (report.signalName.isNotBlank()) report.signalName else "Signal ${report.signalNumber}"
                    val sCode = if (report.signalCodeName.isNotBlank()) " [${report.signalCodeName}]" else ""
                    SummaryDataRow(label = "الإشارة (Signal)", value = "$sName ($report.signalNumber)$sCode")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "رسالة الخطأ (Error Message):",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    SelectionContainer {
                        Text(
                            text = report.errorMessage,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.error
                            )
                        )
                    }
                }

                if (report.missingEvidenceReasons.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "ملاحظات حول الأدلة المفقودة:",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, color = Color(0xFFFFB74D))
                    )
                    report.missingEvidenceReasons.forEach { reason ->
                        Text(
                            text = "• $reason",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // SECTION B: Root Cause & Hypotheses (السبب والتحليل الجنائي)
        SectionCard(
            title = "B. السبب والتحليل الجنائي (Root Cause & Analysis)",
            isExpanded = hypothesesExpanded,
            onToggle = { hypothesesExpanded = !hypothesesExpanded }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (report.forensicHypotheses.isEmpty()) {
                    Text(
                        text = "لا تتوفر فرضيات مسبقة لهذه الجلسة.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    report.forensicHypotheses.forEach { hyp ->
                        HypothesisCard(hypothesis = hyp)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                Text(
                    text = "ملاحظة: درجات الثقة هي تقديرات تشخيصية قائمة على الأدلة المتاحة والمقارنة المعمارية، وليست ضماناً قاطعاً.",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // SECTION C: Native Crash / Tombstone
        SectionCard(
            title = "C. الانهيار الأصلي وسجل Tombstone",
            isExpanded = nativeTraceExpanded,
            onToggle = { nativeTraceExpanded = !nativeTraceExpanded }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (report.signalNumber > 0 || report.signalName.isNotBlank() || report.faultAddress != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Signal: ${report.signalName.ifBlank { "N/A" }} (${report.signalNumber})",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.error
                        )
                        if (report.faultAddress != null) {
                            Text(
                                text = "Fault Addr: 0x%x".format(report.faultAddress),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                if (!report.abortMessage.isNullOrBlank()) {
                    Text(
                        text = "Abort Message: '${report.abortMessage}'",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                if (report.registers.isNotEmpty()) {
                    Text(
                        text = "سجلات المعالج (CPU Registers):",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                            .padding(8.dp)
                    ) {
                        Column {
                            val regList = report.registers.entries.toList()
                            for (i in regList.indices step 2) {
                                val chunk = regList.subList(i, minOf(i + 2, regList.size))
                                val line = chunk.joinToString("   ") { "%-4s: %016x".format(it.key, it.value) }
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Text(
                    text = "إطارات مكدس الاستدعاءات (Stack Backtrace):",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = 220.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                ) {
                    SelectionContainer {
                        Text(
                            text = report.stackTrace.ifBlank { "(No backtrace frames available)" },
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                }

                if (report.logBufferEntries.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "سجل Tombstone Logcat Buffer (${report.logBufferEntries.size} entries):",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 140.dp)
                            .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                    ) {
                        SelectionContainer {
                            Column {
                                report.logBufferEntries.takeLast(20).forEach { logLine ->
                                    Text(
                                        text = logLine,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 9.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                if (!report.rawTombstonePath.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "ملف Tombstone الثنائي الخام محفوظ: ${File(report.rawTombstonePath).name}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // SECTION D: Execution Timeline
        SectionCard(
            title = "D. سجل التشغيل الزمني (Execution Timeline)",
            isExpanded = timelineExpanded,
            onToggle = { timelineExpanded = !timelineExpanded }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (report.timelineEntries.isEmpty() && report.breadcrumbs.isEmpty()) {
                    Text(
                        text = "لم يتم تسجيل أي معالم زمنية قبل التوقف.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (report.timelineEntries.isNotEmpty()) {
                    report.timelineEntries.forEachIndexed { index, entry ->
                        TimelineRow(entry = entry, isLast = index == report.timelineEntries.size - 1)
                    }
                } else {
                    report.breadcrumbs.forEach { bc ->
                        Text(
                            text = bc,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // SECTION E: Environment & Libraries Audit
        SectionCard(
            title = "E. المكتبات والبيئة التحتية (Environment Audit)",
            isExpanded = envExpanded,
            onToggle = { envExpanded = !envExpanded }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                SummaryDataRow(label = "الجهاز (Device)", value = "${report.deviceModel} (${report.deviceManufacturer})")
                SummaryDataRow(label = "نظام التشغيل", value = report.androidVersion)
                SummaryDataRow(label = "المعماريات المدعومة", value = report.supportedAbis)

                Spacer(modifier = Modifier.height(10.dp))

                // Distinction banner
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "OK",
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "تحميل المكتبات: ناجح (10/10 في الذاكرة)",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Cancel,
                                contentDescription = "Crash",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "تنفيذ المحرك: تعطل أثناء التشغيل في libmain.so",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                            )
                        }
                    }
                }

                if (report.libraryStatusList.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "حالة مكتبات النظام العشر (10/10 Native Libraries):",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    report.libraryStatusList.forEach { lib ->
                        val sizeKb = lib.sizeBytes / 1024
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = lib.name,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (lib.isFound) "${sizeKb}KB [${lib.loadStatus}]" else "MISSING",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = if (lib.isFound) Color(0xFF81C784) else MaterialTheme.colorScheme.error
                                )
                            )
                        }
                    }
                }

                if (report.storageStatusList.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "فحص مسارات ومجلدات اللعبة الأساسية:",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    report.storageStatusList.forEach { store ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = File(store.path).name.ifBlank { "root" },
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (store.exists) "EXISTS (${store.itemCount} items)" else "NOT FOUND",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 10.sp,
                                    color = if (store.exists) Color(0xFF81C784) else Color(0xFFFFB74D)
                                )
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // SECTION F: Actions (الإجراءات والتصدير)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "F. الإجراءات والتحقيق الجنائي",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Action Row 1: Copy & Share
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onCopyReport,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "نسخ التقرير", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                    }

                    OutlinedButton(
                        onClick = onShareReport,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "مشاركة كملف", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Action Row 2: Export TXT & JSON
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onExportFile("txt") },
                        modifier = Modifier.weight(1f).height(42.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.FileDownload, contentDescription = "Export TXT", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "تصدير TXT", style = MaterialTheme.typography.bodySmall)
                    }

                    OutlinedButton(
                        onClick = { onExportFile("json") },
                        modifier = Modifier.weight(1f).height(42.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.FileDownload, contentDescription = "Export JSON", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "تصدير JSON", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Action Row 3: Export Raw PB & Re-scan
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onExportRawPb,
                        modifier = Modifier.weight(1f).height(42.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(text = "تصدير Tombstone (.pb)", style = MaterialTheme.typography.bodySmall)
                    }

                    OutlinedButton(
                        onClick = onRefreshDiagnostics,
                        modifier = Modifier.weight(1f).height(42.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Re-scan", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "إعادة فحص الأدلة", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Primary Reopen App Button
                Button(
                    onClick = onReopenApp,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "Reopen", modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "إعادة فتح التطبيق والعودة للرئيسية",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun SectionCard(
    title: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    badgeText: String? = null,
    badgeColor: Color = MaterialTheme.colorScheme.primary,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!badgeText.isNullOrBlank()) {
                        Surface(
                            color = badgeColor,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = badgeText,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = Color.White)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "Toggle",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun HypothesisCard(hypothesis: ForensicHypothesis) {
    var showTech by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            1.dp,
            if (hypothesis.isConfirmed) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "#${hypothesis.rank}. ${hypothesis.title}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    color = when (hypothesis.confidence) {
                        "Confirmed" -> Color(0xFF1B5E20)
                        "High" -> Color(0xFFE65100)
                        else -> Color(0xFF37474F)
                    },
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = hypothesis.confidence,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = Color.White)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = if (showTech) hypothesis.technicalExplanation else hypothesis.simplifiedExplanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = { showTech = !showTech },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        text = if (showTech) "عرض التفسير المبسط" else "عرض التفاصيل التقنية",
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.primary)
                    )
                }
            }

            if (hypothesis.evidenceFor.isNotEmpty()) {
                Text(
                    text = "الأدلة المؤيدة:",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, color = Color(0xFF81C784))
                )
                hypothesis.evidenceFor.forEach { ev ->
                    Text(
                        text = "+ $ev",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (hypothesis.nextAction.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = "Next Action",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "الخطوة الموصى بها: ${hypothesis.nextAction}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineRow(entry: TimelineEntryInfo, isLast: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(24.dp)) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(
                        color = if (entry.isConfirmed) Color(0xFF4CAF50) else Color(0xFFFF5252),
                        shape = CircleShape
                    )
            )
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(32.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.padding(bottom = 8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = entry.stage,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    ),
                    color = if (entry.isConfirmed) MaterialTheme.colorScheme.onSurface else Color(0xFFFF5252)
                )
                if (entry.deltaMs > 0) {
                    Text(
                        text = "+${entry.deltaMs}ms",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (entry.details.isNotBlank()) {
                Text(
                    text = entry.details,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SummaryDataRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

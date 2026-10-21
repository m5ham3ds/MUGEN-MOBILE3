package com.example.ui.screens.characters

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.engine.*
import com.example.engine.gemini.GeminiRosterBalancer
import com.example.engine.workspace.ChangeRecord
import com.example.engine.workspace.CharacterWorkspaceManager
import com.example.utils.GeminiKeyStoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RosterBalancingDialog(
    characters: List<CharacterData>,
    onDismiss: () -> Unit,
    onApplied: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var summary by remember {
        mutableStateOf(RosterBalancingEngine.analyzeCharacterDataRoster(characters))
    }
    var isAiPowered by remember { mutableStateOf(false) }
    var isLoadingAi by remember { mutableStateOf(false) }
    var aiErrorMessage by remember { mutableStateOf<String?>(null) }
    var isApplyingChanges by remember { mutableStateOf(false) }

    // Maintain selected IDs; default to all characters with adjustments
    val selectedCharacterIds = remember {
        mutableStateMapOf<String, Boolean>().apply {
            summary.proposals.filter { it.hasAdjustments }.forEach { put(it.characterId, true) }
        }
    }

    val hasGeminiKey = remember { GeminiKeyStoreManager.hasApiKey(context) }

    Dialog(
        onDismissRequest = { if (!isApplyingChanges && !isLoadingAi) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f)
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "موازنة وتناغم القائمة",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = if (isAiPowered) "تحليل ذكي مدعوم بـ Google Gemini (gemini-3.8-flash)" else "تحليل رياضي محافظ على الهوية",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isAiPowered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Surface(
                        color = if (isAiPowered) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (isAiPowered) "Gemini AI" else "محرك محلي",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (isAiPowered) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Stats Summary Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("المفحوصة", style = MaterialTheme.typography.labelSmall)
                            Text("${summary.totalCharactersScanned}", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("تحتاج لتعديل", style = MaterialTheme.typography.labelSmall)
                            Text(
                                "${summary.charactersNeedingAdjustmentCount}",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (summary.charactersNeedingAdjustmentCount > 0) Color(0xFFE65100) else Color(0xFF2E7D32)
                                )
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("متوازنة", style = MaterialTheme.typography.labelSmall)
                            Text("${summary.balancedCharactersCount}", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32)))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Gemini Action Button / Hint
                if (hasGeminiKey) {
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                isLoadingAi = true
                                aiErrorMessage = null
                                val key = GeminiKeyStoreManager.getApiKey(context)
                                val inputs = characters.mapNotNull { char ->
                                    val cns = char.cnsFile
                                    if (cns != null && cns.isFile) {
                                        try {
                                            val editor = CnsEditor(cns)
                                            CharacterStatInput(
                                                characterId = char.folderName,
                                                displayName = char.displayName,
                                                life = editor.getProperty("[data]", "life")?.trim()?.toIntOrNull(),
                                                attack = editor.getProperty("[data]", "attack")?.trim()?.toIntOrNull(),
                                                defence = editor.getProperty("[data]", "defence")?.trim()?.toIntOrNull()
                                            )
                                        } catch (_: Exception) {
                                            null
                                        }
                                    } else null
                                }

                                val result = GeminiRosterBalancer.analyze(inputs, key)
                                isLoadingAi = false
                                summary = result.summary
                                isAiPowered = result.isAiPowered
                                aiErrorMessage = result.aiErrorMessage

                                if (result.isAiPowered) {
                                    // Update selections for new adjustments
                                    selectedCharacterIds.clear()
                                    summary.proposals.filter { it.hasAdjustments }.forEach {
                                        selectedCharacterIds[it.characterId] = true
                                    }
                                    Toast.makeText(context, "تم تطبيق مقترحات Gemini AI بنجاح", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoadingAi && !isApplyingChanges
                    ) {
                        if (isLoadingAi) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("جارٍ التحليل بواسطة Gemini AI...", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isAiPowered) "إعادة التحليل بواسطة Gemini AI" else "تحليل ذكي مخصص بواسطة Gemini AI")
                        }
                    }
                } else {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "يمكنك إضافة مفتاح Gemini في الإعدادات للحصول على مقترحات موازنة ذكية ومفسرة.",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }

                if (aiErrorMessage != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "ملاحظة: $aiErrorMessage — تم اعتماد المقترحات الرياضية المحلية كبديل آمن.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Scrollable Proposals List
                val charactersNeedingAdjustment = summary.proposals.filter { it.hasAdjustments }
                val balancedCharacters = summary.proposals.filter { !it.hasAdjustments }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (charactersNeedingAdjustment.isNotEmpty()) {
                        item {
                            Text(
                                text = "شخصيات مقترح تعديلها (${charactersNeedingAdjustment.size}):",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        items(charactersNeedingAdjustment) { proposal ->
                            val isSelected = selectedCharacterIds[proposal.characterId] ?: false
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(
                                                checked = isSelected,
                                                onCheckedChange = { checked ->
                                                    selectedCharacterIds[proposal.characterId] = checked
                                                }
                                            )
                                            Column {
                                                Text(
                                                    text = proposal.displayName,
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                                )
                                                Text(
                                                    text = proposal.characterId,
                                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                )
                                            }
                                        }

                                        Surface(
                                            color = when (proposal.overallSeverity) {
                                                StatSeverity.EXTREME_OUTLIER -> Color(0xFFB71C1C)
                                                StatSeverity.OUT_OF_TARGET_RANGE -> Color(0xFFE65100)
                                                else -> Color(0xFF2E7D32)
                                            },
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = proposal.overallSeverity.arabicLabel,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall.copy(color = Color.White, fontSize = 10.sp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    // Stats diffs: FlowRow to prevent cramped letter-by-letter wrapping on mobile
                                    @OptIn(ExperimentalLayoutApi::class)
                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        proposal.originalLife?.let { orig ->
                                            val prop = proposal.proposedLife ?: orig
                                            StatBadge(name = "Life", orig = orig, prop = prop, unit = "")
                                        }
                                        proposal.originalAttack?.let { orig ->
                                            val prop = proposal.proposedAttack ?: orig
                                            StatBadge(name = "Attack", orig = orig, prop = prop, unit = "%")
                                        }
                                        proposal.originalDefence?.let { orig ->
                                            val prop = proposal.proposedDefence ?: orig
                                            StatBadge(name = "Defence", orig = orig, prop = prop, unit = "%")
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    // Explanation text
                                    Text(
                                        text = proposal.overallExplanation,
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    if (balancedCharacters.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "شخصيات متوازنة بالفعل (${balancedCharacters.size}):",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = Color(0xFF2E7D32)
                            )
                        }

                        items(balancedCharacters) { balanced ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = balanced.displayName,
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
                                    )
                                    Text(
                                        text = "متوازن (Life: ${balanced.originalLife ?: 1000}, Att: ${balanced.originalAttack ?: 100}%, Def: ${balanced.originalDefence ?: 100}%)",
                                        style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF2E7D32), fontSize = 10.sp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Bottom Action Buttons
                val selectedCount = selectedCharacterIds.values.count { it }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        enabled = !isApplyingChanges && !isLoadingAi
                    ) {
                        Text("إغلاق")
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                isApplyingChanges = true
                                var appliedCount = 0

                                withContext(Dispatchers.IO) {
                                    val charMap = characters.associateBy { it.folderName }
                                    for (proposal in charactersNeedingAdjustment) {
                                        if (selectedCharacterIds[proposal.characterId] == true) {
                                            val charData = charMap[proposal.characterId]
                                            val cns = charData?.cnsFile
                                            if (cns != null && cns.isFile) {
                                                try {
                                                    val editor = CnsEditor(cns)
                                                    proposal.proposedLife?.let { editor.setProperty("[data]", "life", it.toString()) }
                                                    proposal.proposedAttack?.let { editor.setProperty("[data]", "attack", it.toString()) }
                                                    proposal.proposedDefence?.let { editor.setProperty("[data]", "defence", it.toString()) }

                                                    val saveRes = CharacterWorkspaceManager.saveCnsChanges(
                                                        cnsEditor = editor,
                                                        characterId = proposal.characterId,
                                                        context = context,
                                                        changes = listOf(
                                                            ChangeRecord(
                                                                relativePath = cns.name,
                                                                section = "[data]",
                                                                key = "balance",
                                                                oldValue = "original",
                                                                newValue = "balanced",
                                                                source = if (isAiPowered) "GEMINI_ROSTER_BALANCE" else "AUTO_ROSTER_BALANCE"
                                                            )
                                                        )
                                                    )
                                                    if (saveRes.success) {
                                                        appliedCount++
                                                    }
                                                } catch (_: Exception) {}
                                            }
                                        }
                                    }
                                }

                                isApplyingChanges = false
                                Toast.makeText(
                                    context,
                                    "تم بنجاح تطبيق الموازنة على $appliedCount شخصيات في مساحة العمل",
                                    Toast.LENGTH_LONG
                                ).show()
                                onApplied()
                                onDismiss()
                            }
                        },
                        modifier = Modifier.weight(2f),
                        enabled = !isApplyingChanges && !isLoadingAi && selectedCount > 0
                    ) {
                        if (isApplyingChanges) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("جارٍ التطبيق...", maxLines = 1)
                        } else {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تطبيق الموازنة ($selectedCount)", maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatBadge(name: String, orig: Int, prop: Int, unit: String) {
    val delta = prop - orig
    val isChanged = delta != 0
    Surface(
        color = if (isChanged) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$name: $orig$unit",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                maxLines = 1
            )
            if (isChanged) {
                Text(
                    text = " → $prop$unit",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary),
                    maxLines = 1
                )
                Text(
                    text = if (delta > 0) " (+${delta}$unit)" else " (${delta}$unit)",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (delta > 0) Color(0xFF2E7D32) else Color(0xFFC62828)
                    ),
                    maxLines = 1
                )
            }
        }
    }
}

package com.example.ui.screens.characters

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.example.engine.workspace.CharacterWorkspaceManager
import com.example.engine.workspace.CharacterWorkspaceStatus
import com.example.storage.MugenMobileStorage
import java.io.File

/**
 * Complete, organized, mobile-friendly Character Editor for MUGEN / IKEMEN-GO.
 * Replaces the limited CharacterDynamicEditDialog.
 *
 * Highlights:
 * 1. Collapsible sections for Identity (.def), Combat Data (.cns), Size (.cns), Movement (.cns), and Dependent Files.
 * 2. Instant live search filter across all fields and sections.
 * 3. Multi-tier validation with clear Arabic error notifications.
 * 4. Unsaved changes tracking with cancellation protection.
 * 5. Strict security: Reset Character is disabled unless vault integrity is cryptographically proven.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompleteCharacterEditorDialog(
    character: CharacterData,
    onDismiss: () -> Unit,
    onCharacterUpdated: () -> Unit = {}
) {
    val context = LocalContext.current
    val charsWorkspace = MugenMobileStorage.getCharsDir(context)
    val charFolder = File(charsWorkspace, character.folderName)

    // Resolve .def and .cns files safely
    val defFile = remember(character.folderName) {
        character.defFile ?: run {
            if (character.defFileName.isNotEmpty()) {
                File(charFolder, character.defFileName)
            } else {
                charFolder.listFiles()?.find { it.extension.equals("def", ignoreCase = true) }
            }
        }
    }

    val cnsFile = remember(character.folderName) {
        character.cnsFile ?: run {
            charFolder.listFiles()?.find { it.extension.equals("cns", ignoreCase = true) }
        }
    }

    val defEditor = remember(defFile) { defFile?.let { if (it.exists()) DefEditor(it, context) else null } }
    val cnsEditor = remember(cnsFile) { cnsFile?.let { if (it.exists()) CnsEditor(it, context) else null } }

    var auditStatus by remember(character.folderName) {
        mutableStateOf(CharacterWorkspaceManager.getCharacterAuditStatus(character.folderName, context))
    }

    // Live search query
    var searchQuery by remember { mutableStateOf("") }

    // Collapsible section states
    var isIdentityExpanded by remember { mutableStateOf(true) }
    var isDataExpanded by remember { mutableStateOf(true) }
    var isSizeExpanded by remember { mutableStateOf(false) }
    var isMovementExpanded by remember { mutableStateOf(false) }
    var isFilesExpanded by remember { mutableStateOf(false) }

    // Dialog state for confirmation
    var showConfirmRestore by remember { mutableStateOf(false) }
    var showConfirmCancel by remember { mutableStateOf(false) }

    // -------------------------------------------------------------
    // Editable Field Values (Local State)
    // -------------------------------------------------------------
    // 1. Identity (.def)
    var displayNameVal by remember(defEditor) { mutableStateOf(defEditor?.getProperty("info", "displayname") ?: defEditor?.getProperty("info", "name") ?: character.displayName) }
    var authorVal by remember(defEditor) { mutableStateOf(defEditor?.getProperty("info", "author") ?: "") }
    var versionDateVal by remember(defEditor) { mutableStateOf(defEditor?.getProperty("info", "versiondate") ?: "") }
    var mugenVersionVal by remember(defEditor) { mutableStateOf(defEditor?.getProperty("info", "mugenversion") ?: "") }
    var localcoordVal by remember(defEditor) { mutableStateOf(defEditor?.getProperty("info", "localcoord") ?: "") }

    // 2. Data (.cns [Data])
    var lifeVal by remember(cnsEditor) { mutableStateOf(cnsEditor?.getProperty("[data]", "life") ?: "1000") }
    var attackVal by remember(cnsEditor) { mutableStateOf(cnsEditor?.getProperty("[data]", "attack") ?: "100") }
    var defenceVal by remember(cnsEditor) { mutableStateOf(cnsEditor?.getProperty("[data]", "defence") ?: "100") }
    var fallDefenceUpVal by remember(cnsEditor) { mutableStateOf(cnsEditor?.getProperty("[data]", "fall.defence_up") ?: "50") }
    var airJuggleVal by remember(cnsEditor) { mutableStateOf(cnsEditor?.getProperty("[data]", "airjuggle") ?: "15") }
    var sparkNoVal by remember(cnsEditor) { mutableStateOf(cnsEditor?.getProperty("[data]", "sparkno") ?: "2") }
    var guardSparkNoVal by remember(cnsEditor) { mutableStateOf(cnsEditor?.getProperty("[data]", "guard.sparkno") ?: "40") }

    // 3. Size (.cns [Size])
    var xscaleVal by remember(cnsEditor) { mutableStateOf(cnsEditor?.getProperty("[size]", "xscale") ?: "1") }
    var yscaleVal by remember(cnsEditor) { mutableStateOf(cnsEditor?.getProperty("[size]", "yscale") ?: "1") }
    var groundBackVal by remember(cnsEditor) { mutableStateOf(cnsEditor?.getProperty("[size]", "ground.back") ?: "15") }
    var groundFrontVal by remember(cnsEditor) { mutableStateOf(cnsEditor?.getProperty("[size]", "ground.front") ?: "16") }
    var airBackVal by remember(cnsEditor) { mutableStateOf(cnsEditor?.getProperty("[size]", "air.back") ?: "12") }
    var airFrontVal by remember(cnsEditor) { mutableStateOf(cnsEditor?.getProperty("[size]", "air.front") ?: "12") }
    var headPosVal by remember(cnsEditor) { mutableStateOf(cnsEditor?.getProperty("[size]", "head.pos") ?: "-5, -90") }
    var midPosVal by remember(cnsEditor) { mutableStateOf(cnsEditor?.getProperty("[size]", "mid.pos") ?: "-5, -60") }
    var shadowOffsetVal by remember(cnsEditor) { mutableStateOf(cnsEditor?.getProperty("[size]", "shadowoffset") ?: "0") }

    // 4. Velocity & Movement (.cns [Velocity] & [Movement])
    var walkFwdVal by remember(cnsEditor) { mutableStateOf(cnsEditor?.getProperty("[velocity]", "walk.fwd") ?: "2.4") }
    var walkBackVal by remember(cnsEditor) { mutableStateOf(cnsEditor?.getProperty("[velocity]", "walk.back") ?: "-2.2") }
    var runFwdVal by remember(cnsEditor) { mutableStateOf(cnsEditor?.getProperty("[velocity]", "run.fwd") ?: "6.5, 0") }
    var runBackVal by remember(cnsEditor) { mutableStateOf(cnsEditor?.getProperty("[velocity]", "run.back") ?: "-4.5, -3.8") }
    var jumpNeuVal by remember(cnsEditor) { mutableStateOf(cnsEditor?.getProperty("[velocity]", "jump.neu") ?: "0, -8.4") }
    var jumpBackVal by remember(cnsEditor) { mutableStateOf(cnsEditor?.getProperty("[velocity]", "jump.back") ?: "-2.55") }
    var jumpFwdVal by remember(cnsEditor) { mutableStateOf(cnsEditor?.getProperty("[velocity]", "jump.fwd") ?: "2.5") }
    var airjumpNumVal by remember(cnsEditor) { mutableStateOf(cnsEditor?.getProperty("[movement]", "airjump.num") ?: "0") }
    var airjumpHeightVal by remember(cnsEditor) { mutableStateOf(cnsEditor?.getProperty("[movement]", "airjump.height") ?: "35") }
    var standFrictionVal by remember(cnsEditor) { mutableStateOf(cnsEditor?.getProperty("[movement]", "stand.friction") ?: "0.85") }
    var crouchFrictionVal by remember(cnsEditor) { mutableStateOf(cnsEditor?.getProperty("[movement]", "crouch.friction") ?: "0.82") }

    // Initial snapshot for change detection
    val initialValues = remember(character.folderName) {
        mapOf(
            "displayName" to displayNameVal,
            "author" to authorVal,
            "versionDate" to versionDateVal,
            "mugenVersion" to mugenVersionVal,
            "localcoord" to localcoordVal,
            "life" to lifeVal,
            "attack" to attackVal,
            "defence" to defenceVal,
            "fallDefenceUp" to fallDefenceUpVal,
            "airJuggle" to airJuggleVal,
            "sparkNo" to sparkNoVal,
            "guardSparkNo" to guardSparkNoVal,
            "xscale" to xscaleVal,
            "yscale" to yscaleVal,
            "groundBack" to groundBackVal,
            "groundFront" to groundFrontVal,
            "airBack" to airBackVal,
            "airFront" to airFrontVal,
            "headPos" to headPosVal,
            "midPos" to midPosVal,
            "shadowOffset" to shadowOffsetVal,
            "walkFwd" to walkFwdVal,
            "walkBack" to walkBackVal,
            "runFwd" to runFwdVal,
            "runBack" to runBackVal,
            "jumpNeu" to jumpNeuVal,
            "jumpBack" to jumpBackVal,
            "jumpFwd" to jumpFwdVal,
            "airjumpNum" to airjumpNumVal,
            "airjumpHeight" to airjumpHeightVal,
            "standFriction" to standFrictionVal,
            "crouchFriction" to crouchFrictionVal
        )
    }

    // Track unsaved modifications count
    val unsavedCount by remember {
        derivedStateOf {
            var count = 0
            if (displayNameVal != initialValues["displayName"]) count++
            if (authorVal != initialValues["author"]) count++
            if (versionDateVal != initialValues["versionDate"]) count++
            if (mugenVersionVal != initialValues["mugenVersion"]) count++
            if (localcoordVal != initialValues["localcoord"]) count++
            if (lifeVal != initialValues["life"]) count++
            if (attackVal != initialValues["attack"]) count++
            if (defenceVal != initialValues["defence"]) count++
            if (fallDefenceUpVal != initialValues["fallDefenceUp"]) count++
            if (airJuggleVal != initialValues["airJuggle"]) count++
            if (sparkNoVal != initialValues["sparkNo"]) count++
            if (guardSparkNoVal != initialValues["guardSparkNo"]) count++
            if (xscaleVal != initialValues["xscale"]) count++
            if (yscaleVal != initialValues["yscale"]) count++
            if (groundBackVal != initialValues["groundBack"]) count++
            if (groundFrontVal != initialValues["groundFront"]) count++
            if (airBackVal != initialValues["airBack"]) count++
            if (airFrontVal != initialValues["airFront"]) count++
            if (headPosVal != initialValues["headPos"]) count++
            if (midPosVal != initialValues["midPos"]) count++
            if (shadowOffsetVal != initialValues["shadowOffset"]) count++
            if (walkFwdVal != initialValues["walkFwd"]) count++
            if (walkBackVal != initialValues["walkBack"]) count++
            if (runFwdVal != initialValues["runFwd"]) count++
            if (runBackVal != initialValues["runBack"]) count++
            if (jumpNeuVal != initialValues["jumpNeu"]) count++
            if (jumpBackVal != initialValues["jumpBack"]) count++
            if (jumpFwdVal != initialValues["jumpFwd"]) count++
            if (airjumpNumVal != initialValues["airjumpNum"]) count++
            if (airjumpHeightVal != initialValues["airjumpHeight"]) count++
            if (standFrictionVal != initialValues["standFriction"]) count++
            if (crouchFrictionVal != initialValues["crouchFriction"]) count++
            count
        }
    }

    // Dependent files inspection list
    val dependentFiles = remember(defEditor) {
        defEditor?.getDependentFiles() ?: emptyList()
    }

    // Helper to test if a string matches live search
    fun matchesSearch(vararg terms: String): Boolean {
        if (searchQuery.isBlank()) return true
        val q = searchQuery.trim().lowercase()
        return terms.any { it.lowercase().contains(q) }
    }

    Dialog(
        onDismissRequest = {
            if (unsavedCount > 0) {
                showConfirmCancel = true
            } else {
                onDismiss()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.95f),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // -------------------------------------------------------------
                // 1. Header: Character Title & Audit Status Badge
                // -------------------------------------------------------------
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = displayNameVal.ifBlank { character.displayName },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "المجلد: ${character.folderName} | .def: ${character.defFileName.ifEmpty { "غير محدد" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Audit Status Badge
                    val isCorrupted = !auditStatus.isVaultHealthy || auditStatus.workspaceStatus == CharacterWorkspaceStatus.CORRUPTED
                    val badgeColor = when {
                        isCorrupted -> Color(0xFFC62828)
                        auditStatus.isVerifiedOriginal -> Color(0xFF2E7D32)
                        auditStatus.isAdoptedLegacy -> Color(0xFFEF6C00)
                        else -> Color(0xFF0277BD)
                    }

                    Surface(
                        color = badgeColor.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, badgeColor),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (!isCorrupted) Icons.Default.Security else Icons.Default.Warning,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp),
                                    tint = badgeColor
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = auditStatus.statusBadgeTitle,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = badgeColor
                                )
                            }
                            Text(
                                text = auditStatus.statusBadgeSubtitle,
                                fontSize = 9.sp,
                                color = badgeColor.copy(alpha = 0.85f)
                            )
                        }
                    }
                }

                // Unsaved changes alert badge
                AnimatedVisibility(visible = unsavedCount > 0) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        color = Color(0xFFFFF3E0),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFFFFB74D))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFFE65100))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "تعديلات معلقة غير محفوظة ($unsavedCount حقول معدلة)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE65100)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // -------------------------------------------------------------
                // 2. Live Search Bar
                // -------------------------------------------------------------
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("بحث فوري في الحقول والخصائص...", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "بحث", modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "مسح", modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // -------------------------------------------------------------
                // 3. Scrollable Sections Area
                // -------------------------------------------------------------
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    // SECTION 1: IDENTITY (.def)
                    val showSec1 = matchesSearch("هوية", "identity", "def", "displayname", "name", "author", "versiondate", "mugenversion", "localcoord", displayNameVal, authorVal)
                    if (showSec1) {
                        EditorSectionCard(
                            title = "1. هوية المقاتل (.def)",
                            isExpanded = isIdentityExpanded || searchQuery.isNotBlank(),
                            onToggle = { isIdentityExpanded = !isIdentityExpanded },
                            icon = Icons.Default.Person
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                OutlinedTextField(
                                    value = displayNameVal,
                                    onValueChange = { displayNameVal = it },
                                    label = { Text("الاسم الظاهر (Display Name)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = authorVal,
                                    onValueChange = { authorVal = it },
                                    label = { Text("المؤلف / المطور (Author)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    OutlinedTextField(
                                        value = versionDateVal,
                                        onValueChange = { versionDateVal = it },
                                        label = { Text("تاريخ الإصدار (Version Date)") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    OutlinedTextField(
                                        value = mugenVersionVal,
                                        onValueChange = { mugenVersionVal = it },
                                        label = { Text("إصدار MUGEN") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))

                                val localcoordValidation = CharacterEditorValidator.validateLocalcoord(localcoordVal)
                                OutlinedTextField(
                                    value = localcoordVal,
                                    onValueChange = { localcoordVal = it },
                                    label = { Text("أبعاد الدقة (Localcoord مثل: 320, 240)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    isError = !localcoordValidation.isValid,
                                    supportingText = {
                                        if (!localcoordValidation.isValid) {
                                            Text(localcoordValidation.errorMessage ?: "", color = MaterialTheme.colorScheme.error)
                                        }
                                    },
                                    singleLine = true
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // SECTION 2: COMBAT DATA (.cns [Data])
                    val showSec2 = matchesSearch("حيوية", "حياة", "هجوم", "دفاع", "life", "attack", "defence", "data", "cns", lifeVal, attackVal, defenceVal)
                    if (showSec2) {
                        EditorSectionCard(
                            title = "2. الحيوية والهجوم والدفاع (.cns - [Data])",
                            isExpanded = isDataExpanded || searchQuery.isNotBlank(),
                            onToggle = { isDataExpanded = !isDataExpanded },
                            icon = Icons.Default.Favorite
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                val lifeValidation = CharacterEditorValidator.validateLife(lifeVal)
                                OutlinedTextField(
                                    value = lifeVal,
                                    onValueChange = { lifeVal = it },
                                    label = { Text("نقاط الحياة (Life)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    isError = !lifeValidation.isValid,
                                    supportingText = {
                                        if (!lifeValidation.isValid) Text(lifeValidation.errorMessage ?: "", color = MaterialTheme.colorScheme.error)
                                    },
                                    singleLine = true
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                Row(modifier = Modifier.fillMaxWidth()) {
                                    val attackValidation = CharacterEditorValidator.validateAttack(attackVal)
                                    OutlinedTextField(
                                        value = attackVal,
                                        onValueChange = { attackVal = it },
                                        label = { Text("قوة الهجوم (Attack %)") },
                                        modifier = Modifier.weight(1f),
                                        isError = !attackValidation.isValid,
                                        supportingText = {
                                            if (!attackValidation.isValid) Text(attackValidation.errorMessage ?: "", color = MaterialTheme.colorScheme.error)
                                        },
                                        singleLine = true
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    val defenceValidation = CharacterEditorValidator.validateDefence(defenceVal)
                                    OutlinedTextField(
                                        value = defenceVal,
                                        onValueChange = { defenceVal = it },
                                        label = { Text("قوة الدفاع (Defence %)") },
                                        modifier = Modifier.weight(1f),
                                        isError = !defenceValidation.isValid,
                                        supportingText = {
                                            if (!defenceValidation.isValid) Text(defenceValidation.errorMessage ?: "", color = MaterialTheme.colorScheme.error)
                                        },
                                        singleLine = true
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))

                                Row(modifier = Modifier.fillMaxWidth()) {
                                    OutlinedTextField(
                                        value = fallDefenceUpVal,
                                        onValueChange = { fallDefenceUpVal = it },
                                        label = { Text("دفاع السقوط (Fall Defence Up)") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    OutlinedTextField(
                                        value = airJuggleVal,
                                        onValueChange = { airJuggleVal = it },
                                        label = { Text("توازن الهواء (Air Juggle)") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))

                                Row(modifier = Modifier.fillMaxWidth()) {
                                    OutlinedTextField(
                                        value = sparkNoVal,
                                        onValueChange = { sparkNoVal = it },
                                        label = { Text("رقم الشرارة (Spark No)") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    OutlinedTextField(
                                        value = guardSparkNoVal,
                                        onValueChange = { guardSparkNoVal = it },
                                        label = { Text("شرارة الصد (Guard Spark No)") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // SECTION 3: SIZE & DIMENSIONS (.cns [Size])
                    val showSec3 = matchesSearch("أبعاد", "حجم", "مقاس", "size", "xscale", "yscale", "ground", "air", "head.pos", "mid.pos", "shadow")
                    if (showSec3) {
                        EditorSectionCard(
                            title = "3. الأبعاد والحجم (.cns - [Size])",
                            isExpanded = isSizeExpanded || searchQuery.isNotBlank(),
                            onToggle = { isSizeExpanded = !isSizeExpanded },
                            icon = Icons.Default.AspectRatio
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    val xScaleValidation = CharacterEditorValidator.validateScale(xscaleVal, "xscale")
                                    OutlinedTextField(
                                        value = xscaleVal,
                                        onValueChange = { xscaleVal = it },
                                        label = { Text("مقياس العرض (xscale)") },
                                        modifier = Modifier.weight(1f),
                                        isError = !xScaleValidation.isValid,
                                        supportingText = {
                                            if (!xScaleValidation.isValid) Text(xScaleValidation.errorMessage ?: "", color = MaterialTheme.colorScheme.error)
                                        },
                                        singleLine = true
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    val yScaleValidation = CharacterEditorValidator.validateScale(yscaleVal, "yscale")
                                    OutlinedTextField(
                                        value = yscaleVal,
                                        onValueChange = { yscaleVal = it },
                                        label = { Text("مقياس الارتفاع (yscale)") },
                                        modifier = Modifier.weight(1f),
                                        isError = !yScaleValidation.isValid,
                                        supportingText = {
                                            if (!yScaleValidation.isValid) Text(yScaleValidation.errorMessage ?: "", color = MaterialTheme.colorScheme.error)
                                        },
                                        singleLine = true
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))

                                Row(modifier = Modifier.fillMaxWidth()) {
                                    OutlinedTextField(
                                        value = groundBackVal,
                                        onValueChange = { groundBackVal = it },
                                        label = { Text("عرض الأرض للخلف (ground.back)") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    OutlinedTextField(
                                        value = groundFrontVal,
                                        onValueChange = { groundFrontVal = it },
                                        label = { Text("عرض الأرض للأمام (ground.front)") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))

                                Row(modifier = Modifier.fillMaxWidth()) {
                                    OutlinedTextField(
                                        value = airBackVal,
                                        onValueChange = { airBackVal = it },
                                        label = { Text("عرض الهواء للخلف (air.back)") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    OutlinedTextField(
                                        value = airFrontVal,
                                        onValueChange = { airFrontVal = it },
                                        label = { Text("عرض الهواء للأمام (air.front)") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))

                                Row(modifier = Modifier.fillMaxWidth()) {
                                    val headValidation = CharacterEditorValidator.validateCoordinates(headPosVal, "head.pos")
                                    OutlinedTextField(
                                        value = headPosVal,
                                        onValueChange = { headPosVal = it },
                                        label = { Text("موضع الرأس (head.pos)") },
                                        modifier = Modifier.weight(1f),
                                        isError = !headValidation.isValid,
                                        supportingText = {
                                            if (!headValidation.isValid) Text(headValidation.errorMessage ?: "", color = MaterialTheme.colorScheme.error)
                                        },
                                        singleLine = true
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    val midValidation = CharacterEditorValidator.validateCoordinates(midPosVal, "mid.pos")
                                    OutlinedTextField(
                                        value = midPosVal,
                                        onValueChange = { midPosVal = it },
                                        label = { Text("موضع المنتصف (mid.pos)") },
                                        modifier = Modifier.weight(1f),
                                        isError = !midValidation.isValid,
                                        supportingText = {
                                            if (!midValidation.isValid) Text(midValidation.errorMessage ?: "", color = MaterialTheme.colorScheme.error)
                                        },
                                        singleLine = true
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))

                                OutlinedTextField(
                                    value = shadowOffsetVal,
                                    onValueChange = { shadowOffsetVal = it },
                                    label = { Text("إزاحة الظل (shadowoffset)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // SECTION 4: VELOCITY & MOVEMENT (.cns [Velocity] & [Movement])
                    val showSec4 = matchesSearch("سرعة", "حركة", "قفز", "مشى", "ركض", "velocity", "movement", "walk", "run", "jump", "friction")
                    if (showSec4) {
                        EditorSectionCard(
                            title = "4. السرعة والحركة (.cns - [Velocity] & [Movement])",
                            isExpanded = isMovementExpanded || searchQuery.isNotBlank(),
                            onToggle = { isMovementExpanded = !isMovementExpanded },
                            icon = Icons.Default.DirectionsRun
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    OutlinedTextField(
                                        value = walkFwdVal,
                                        onValueChange = { walkFwdVal = it },
                                        label = { Text("مشى للأمام (walk.fwd)") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    OutlinedTextField(
                                        value = walkBackVal,
                                        onValueChange = { walkBackVal = it },
                                        label = { Text("مشى للخلف (walk.back)") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))

                                Row(modifier = Modifier.fillMaxWidth()) {
                                    OutlinedTextField(
                                        value = runFwdVal,
                                        onValueChange = { runFwdVal = it },
                                        label = { Text("ركض للأمام (run.fwd)") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    OutlinedTextField(
                                        value = runBackVal,
                                        onValueChange = { runBackVal = it },
                                        label = { Text("ركض للخلف (run.back)") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))

                                Row(modifier = Modifier.fillMaxWidth()) {
                                    OutlinedTextField(
                                        value = jumpNeuVal,
                                        onValueChange = { jumpNeuVal = it },
                                        label = { Text("قفز عمودي (jump.neu)") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    OutlinedTextField(
                                        value = jumpFwdVal,
                                        onValueChange = { jumpFwdVal = it },
                                        label = { Text("قفز للأمام (jump.fwd)") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))

                                Row(modifier = Modifier.fillMaxWidth()) {
                                    val airjumpNumValidation = CharacterEditorValidator.validateNonNegativeInt(airjumpNumVal, "airjump.num")
                                    OutlinedTextField(
                                        value = airjumpNumVal,
                                        onValueChange = { airjumpNumVal = it },
                                        label = { Text("عدد القفزات بالهواء") },
                                        modifier = Modifier.weight(1f),
                                        isError = !airjumpNumValidation.isValid,
                                        supportingText = {
                                            if (!airjumpNumValidation.isValid) Text(airjumpNumValidation.errorMessage ?: "", color = MaterialTheme.colorScheme.error)
                                        },
                                        singleLine = true
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    OutlinedTextField(
                                        value = airjumpHeightVal,
                                        onValueChange = { airjumpHeightVal = it },
                                        label = { Text("ارتفاع القفز بالهواء") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))

                                Row(modifier = Modifier.fillMaxWidth()) {
                                    OutlinedTextField(
                                        value = standFrictionVal,
                                        onValueChange = { standFrictionVal = it },
                                        label = { Text("احتكاك الوقوف (stand.friction)") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    OutlinedTextField(
                                        value = crouchFrictionVal,
                                        onValueChange = { crouchFrictionVal = it },
                                        label = { Text("احتكاك الجلوس (crouch.friction)") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // SECTION 5: DEPENDENT FILES (Read-Only Inspection)
                    val showSec5 = matchesSearch("ملفات", "تابعة", "files", "sff", "snd", "air", "cmd", "اعتماديات")
                    if (showSec5) {
                        EditorSectionCard(
                            title = "5. الملفات التابعة (.def - [Files]) [للفحص فقط]",
                            isExpanded = isFilesExpanded || searchQuery.isNotBlank(),
                            onToggle = { isFilesExpanded = !isFilesExpanded },
                            icon = Icons.Default.FolderOpen
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "الملفات والاعتماديات المحددة بملف .def (غير قابلة للتحرير المباشر في هذه المرحلة لضمان سلامة المحرك):",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                if (dependentFiles.isEmpty()) {
                                    Text("لا توجد ملفات تابعة مسجلة في ملف .def", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                                } else {
                                    dependentFiles.forEach { dep ->
                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 3.dp),
                                            color = if (dep.exists) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else Color(0xFFFFEBEE),
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(0.5.dp, if (dep.exists) MaterialTheme.colorScheme.outlineVariant else Color(0xFFEF5350))
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(
                                                            text = dep.key.uppercase(),
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 11.sp,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = dep.declaredPath,
                                                            fontSize = 11.sp,
                                                            fontFamily = FontFamily.Monospace
                                                        )
                                                    }
                                                }
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = if (dep.exists) dep.formattedSize else "مفقود!",
                                                        fontSize = 10.sp,
                                                        color = if (dep.exists) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFC62828),
                                                        fontWeight = if (dep.exists) FontWeight.Normal else FontWeight.Bold
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Icon(
                                                        imageVector = if (dep.exists) Icons.Default.CheckCircle else Icons.Default.Warning,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(13.dp),
                                                        tint = if (dep.exists) Color(0xFF2E7D32) else Color(0xFFC62828)
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

                Spacer(modifier = Modifier.height(12.dp))

                // -------------------------------------------------------------
                // 4. Footer Action Bar (Reset, Cancel, Save)
                // -------------------------------------------------------------
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: Reset Character Button
                    if (auditStatus.canResetToPristine) {
                        OutlinedButton(
                            onClick = { showConfirmRestore = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("استعادة الأصل", fontSize = 12.sp)
                        }
                    } else {
                        // Restoration Disabled with explanation
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "الاستعادة محظورة (سلامة الأصل غير مثبتة)",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }

                    // Right: Cancel & Save Buttons
                    Row {
                        TextButton(
                            onClick = {
                                if (unsavedCount > 0) {
                                    showConfirmCancel = true
                                } else {
                                    onDismiss()
                                }
                            }
                        ) {
                            Text("إلغاء")
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            enabled = auditStatus.isVaultHealthy && auditStatus.workspaceStatus != CharacterWorkspaceStatus.CORRUPTED,
                            onClick = {
                                // 1. Run all validations
                                val vLife = CharacterEditorValidator.validateLife(lifeVal)
                                val vAttack = CharacterEditorValidator.validateAttack(attackVal)
                                val vDefence = CharacterEditorValidator.validateDefence(defenceVal)
                                val vXScale = CharacterEditorValidator.validateScale(xscaleVal, "xscale")
                                val vYScale = CharacterEditorValidator.validateScale(yscaleVal, "yscale")
                                val vLocalcoord = CharacterEditorValidator.validateLocalcoord(localcoordVal)
                                val vHead = CharacterEditorValidator.validateCoordinates(headPosVal, "head.pos")
                                val vMid = CharacterEditorValidator.validateCoordinates(midPosVal, "mid.pos")
                                val vAirjumpNum = CharacterEditorValidator.validateNonNegativeInt(airjumpNumVal, "airjump.num")

                                if (!vLife.isValid || !vAttack.isValid || !vDefence.isValid ||
                                    !vXScale.isValid || !vYScale.isValid || !vLocalcoord.isValid ||
                                    !vHead.isValid || !vMid.isValid || !vAirjumpNum.isValid
                                ) {
                                    Toast.makeText(context, "يرجى تصحيح القيم غير الصالحة باللون الأحمر قبل الحفظ", Toast.LENGTH_LONG).show()
                                    return@Button
                                }

                                // 2. Apply values to DefEditor
                                defEditor?.let { def ->
                                    def.setProperty("info", "displayname", displayNameVal)
                                    def.setProperty("info", "author", authorVal)
                                    if (versionDateVal.isNotBlank()) def.setProperty("info", "versiondate", versionDateVal)
                                    if (mugenVersionVal.isNotBlank()) def.setProperty("info", "mugenversion", mugenVersionVal)
                                    if (localcoordVal.isNotBlank()) def.setProperty("info", "localcoord", localcoordVal)
                                }

                                // 3. Apply values to CnsEditor
                                cnsEditor?.let { cns ->
                                    cns.setProperty("[data]", "life", lifeVal)
                                    cns.setProperty("[data]", "attack", attackVal)
                                    cns.setProperty("[data]", "defence", defenceVal)
                                    cns.setProperty("[data]", "fall.defence_up", fallDefenceUpVal)
                                    cns.setProperty("[data]", "airjuggle", airJuggleVal)
                                    cns.setProperty("[data]", "sparkno", sparkNoVal)
                                    cns.setProperty("[data]", "guard.sparkno", guardSparkNoVal)

                                    cns.setProperty("[size]", "xscale", xscaleVal)
                                    cns.setProperty("[size]", "yscale", yscaleVal)
                                    cns.setProperty("[size]", "ground.back", groundBackVal)
                                    cns.setProperty("[size]", "ground.front", groundFrontVal)
                                    cns.setProperty("[size]", "air.back", airBackVal)
                                    cns.setProperty("[size]", "air.front", airFrontVal)
                                    cns.setProperty("[size]", "head.pos", headPosVal)
                                    cns.setProperty("[size]", "mid.pos", midPosVal)
                                    cns.setProperty("[size]", "shadowoffset", shadowOffsetVal)

                                    cns.setProperty("[velocity]", "walk.fwd", walkFwdVal)
                                    cns.setProperty("[velocity]", "walk.back", walkBackVal)
                                    cns.setProperty("[velocity]", "run.fwd", runFwdVal)
                                    cns.setProperty("[velocity]", "run.back", runBackVal)
                                    cns.setProperty("[velocity]", "jump.neu", jumpNeuVal)
                                    cns.setProperty("[velocity]", "jump.back", jumpBackVal)
                                    cns.setProperty("[velocity]", "jump.fwd", jumpFwdVal)

                                    cns.setProperty("[movement]", "airjump.num", airjumpNumVal)
                                    cns.setProperty("[movement]", "airjump.height", airjumpHeightVal)
                                    cns.setProperty("[movement]", "stand.friction", standFrictionVal)
                                    cns.setProperty("[movement]", "crouch.friction", crouchFrictionVal)
                                }

                                // 4. Execute atomic and safe save via CharacterWorkspaceManager
                                val saveResult = CharacterWorkspaceManager.saveCompleteCharacterEdits(
                                    characterId = character.folderName,
                                    defEditor = defEditor,
                                    cnsEditor = cnsEditor,
                                    context = context
                                )

                                if (saveResult.success) {
                                    Toast.makeText(context, "تم حفظ التعديلات بأمان في مساحة العمل!", Toast.LENGTH_SHORT).show()
                                    auditStatus = CharacterWorkspaceManager.getCharacterAuditStatus(character.folderName, context)
                                    onCharacterUpdated()
                                    onDismiss()
                                } else {
                                    Toast.makeText(context, "فشل الحفظ: ${saveResult.errorMessage}", Toast.LENGTH_LONG).show()
                                    auditStatus = CharacterWorkspaceManager.getCharacterAuditStatus(character.folderName, context)
                                }
                            }
                        ) {
                            Text("حفظ التعديلات")
                        }
                    }
                }
            }
        }
    }

    // Confirmation: Reset to Original Vault
    if (showConfirmRestore) {
        AlertDialog(
            onDismissRequest = { showConfirmRestore = false },
            title = { Text("استعادة النسخة الأصلية الموثوقة") },
            text = {
                Text("هل أنت متأكد من استعادة المقاتل '${character.displayName}' إلى حالته الأصلية النقية؟ ستتم استعادة الملفات من الخزينة المشفرة وإلغاء كافة التعديلات.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmRestore = false
                        val result = CharacterWorkspaceManager.restoreSingleCharacter(character.folderName, context)
                        if (result.success) {
                            Toast.makeText(context, "تمت استعادة المقاتل بنجاح إلى النسخة الأصلية!", Toast.LENGTH_SHORT).show()
                            auditStatus = CharacterWorkspaceManager.getCharacterAuditStatus(character.folderName, context)
                            onCharacterUpdated()
                            onDismiss()
                        } else {
                            Toast.makeText(context, "فشلت الاستعادة: ${result.errorMessage}", Toast.LENGTH_LONG).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("استعادة الآن")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmRestore = false }) {
                    Text("إلغاء")
                }
            }
        )
    }

    // Confirmation: Cancel with unsaved edits
    if (showConfirmCancel) {
        AlertDialog(
            onDismissRequest = { showConfirmCancel = false },
            title = { Text("تجاهل التعديلات؟") },
            text = {
                Text("توجد $unsavedCount تعديلات معلقة لم يتم حفظها بعد. هل تريد بالتأكيد إغلاق المحرر وتجاهل هذه التغييرات؟")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmCancel = false
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("تجاهل وإغلاق")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmCancel = false }) {
                    Text("العودة للمحرر")
                }
            }
        )
    }
}

/**
 * Expandable Card Container for each Editor Section.
 */
@Composable
fun EditorSectionCard(
    title: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "طي" else "توسيع",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                content()
            }
        }
    }
}

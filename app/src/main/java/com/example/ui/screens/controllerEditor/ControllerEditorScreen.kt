package com.example.ui.screens.controllerEditor

import android.content.pm.ActivityInfo
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.example.data.settings.ControllerDataStore
import com.example.utils.HideSystemBars
import com.example.utils.LiveLogger
import com.example.utils.LockScreenOrientation
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

data class ControllerButtonState(
    val id: String,
    val initialX: Float,
    val initialY: Float,
    val label: String,
    val scale: Float = 1.0f,
    val opacity: Float = 0.7f,
    val isEnabled: Boolean = true,
    val isMacro: Boolean = false
)

enum class ControllerPreset(val displayName: String) {
    SIX_BUTTON("6-Button (Standard)"),
    CLASSIC("Classic (4-Button)"),
    ARCADE("Arcade View"),
    CUSTOM("Custom Layout")
}

data class MacroDefinition(
    val id: String,
    val label: String,
    val name: String,
    val description: String
)

val MACRO_CATALOG = listOf(
    MacroDefinition("macro_charge", "CHG", "شحن الطاقة (Power Charge)", "ضغط مستمر متزامن على B + Y لشحن العداد"),
    MacroDefinition("macro_ab", "A+B", "كومبو ركلات خفيف+متوسط (A + B)", "ضربة مشتركة لركلتي A و B"),
    MacroDefinition("macro_bc", "B+C", "كومبو ركلات متوسط+ثقيل (B + C)", "ضربة مشتركة لركلتي B و C"),
    MacroDefinition("macro_xy", "X+Y", "كومبو لكمات خفيف+متوسط (X + Y)", "ضربة مشتركة للكمتي X و Y"),
    MacroDefinition("macro_abc", "ABC", "الضربة الثلاثية (A + B + C)", "ضغط ثلاثي متزامن لأزرار A و B و C"),
    MacroDefinition("macro_round_reset", "RESET", "إعادة الجولة (Round Reset)", "إعادة الجولة فورياً (مفتاح F4)"),
    MacroDefinition("macro_max_power", "MAX", "طاقة قصوى (Max Power)", "تعبئة عداد الطاقة إلى الحد الأقصى (F3)"),
    MacroDefinition("macro_control_swap", "SWAP", "تبديل التحكم (Player ↔ CPU)", "نقل التحكم اليدوي بين المقاتل النشط وخصم الـ CPU أثناء المباراة"),
    MacroDefinition("macro_team_switch", "TAG", "تبديل عضو الفريق (Switch Team Member)", "تبديل المقاتل النشط بعضو احتياطي من نفس الفريق (مخصص لطور Tag)")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControllerEditorScreen(onNavigateBack: () -> Unit) {
    LockScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
    HideSystemBars()

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedPreset by remember { mutableStateOf(ControllerPreset.SIX_BUTTON) }
    var buttons by remember { mutableStateOf<List<ControllerButtonState>>(emptyList()) }
    var showPresetMenu by remember { mutableStateOf(false) }
    var showMacroDialog by remember { mutableStateOf(false) }

    var selectedButtonId by remember { mutableStateOf<String?>(null) }

    // Load saved layout
    LaunchedEffect(Unit) {
        val savedLayout = ControllerDataStore.getLayout(context).firstOrNull()
        if (!savedLayout.isNullOrEmpty()) {
            try {
                val jsonArray = JSONArray(savedLayout)
                val loadedButtons = mutableListOf<ControllerButtonState>()
                val loadedIds = mutableSetOf<String>()

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val id = obj.getString("id")
                    val isMacro = obj.optBoolean("macro", id.startsWith("macro_"))
                    val isEnabled = if (obj.has("enabled")) obj.getBoolean("enabled") else !isMacro
                    val scale = if (obj.has("scale")) obj.getDouble("scale").toFloat() else 1.0f
                    val opacity = if (obj.has("opacity")) obj.getDouble("opacity").toFloat() else 0.7f

                    loadedButtons.add(
                        ControllerButtonState(
                            id = id,
                            initialX = obj.getDouble("x").toFloat(),
                            initialY = obj.getDouble("y").toFloat(),
                            label = getLabelForId(id),
                            scale = scale,
                            opacity = opacity,
                            isEnabled = isEnabled,
                            isMacro = isMacro
                        )
                    )
                    loadedIds.add(id)
                }

                if (loadedButtons.isNotEmpty()) {
                    // Also ensure all macro definitions exist in list (as disabled if not present)
                    for (macro in MACRO_CATALOG) {
                        if (!loadedIds.contains(macro.id)) {
                            loadedButtons.add(
                                ControllerButtonState(
                                    id = macro.id,
                                    initialX = 300f,
                                    initialY = 150f,
                                    label = macro.label,
                                    scale = 0.9f,
                                    opacity = 0.8f,
                                    isEnabled = false,
                                    isMacro = true
                                )
                            )
                        }
                    }
                    buttons = loadedButtons
                    selectedPreset = ControllerPreset.CUSTOM
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF141414))
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()

        LaunchedEffect(selectedPreset, widthPx, heightPx) {
            if (widthPx > 0 && heightPx > 0 && selectedPreset != ControllerPreset.CUSTOM) {
                val presetButtons = getPresetLayout(selectedPreset, widthPx, heightPx).toMutableList()
                // Merge macro definitions into preset
                for (macro in MACRO_CATALOG) {
                    if (presetButtons.none { it.id == macro.id }) {
                        val posX = when (macro.id) {
                            "macro_charge" -> widthPx * 0.75f
                            "macro_ab" -> widthPx * 0.65f
                            "macro_bc" -> widthPx * 0.55f
                            "macro_xy" -> widthPx * 0.45f
                            "macro_abc" -> widthPx * 0.35f
                            "macro_round_reset" -> widthPx * 0.40f
                            "macro_max_power" -> widthPx * 0.50f
                            "macro_control_swap" -> widthPx * 0.30f
                            "macro_team_switch" -> widthPx * 0.20f
                            else -> widthPx * 0.50f
                        }
                        presetButtons.add(
                            ControllerButtonState(
                                id = macro.id,
                                initialX = posX,
                                initialY = heightPx * 0.35f,
                                label = macro.label,
                                scale = 0.85f,
                                opacity = 0.8f,
                                isEnabled = false,
                                isMacro = true
                            )
                        )
                    }
                }
                buttons = presetButtons
            }
        }

        // --- 1. EMPTY CANVAS HIT TARGET (Z-order 0: captures taps on empty areas to clear selection) ---
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            selectedButtonId = null
                        }
                    )
                }
        )

        // --- 2. CONTROLLER BUTTONS (Z-order 1) ---
        val activeButtons = buttons.filter { it.isEnabled }
        activeButtons.forEach { buttonState ->
            DraggableButton(
                buttonState = buttonState,
                isSelected = buttonState.id == selectedButtonId,
                onClick = { selectedButtonId = buttonState.id },
                onPositionChanged = { newX, newY ->
                    val updated = buttons.map {
                        if (it.id == buttonState.id) it.copy(initialX = newX, initialY = newY) else it
                    }
                    buttons = updated
                    selectedPreset = ControllerPreset.CUSTOM
                    selectedButtonId = buttonState.id
                }
            )
        }

        val selectedButton = buttons.find { it.id == selectedButtonId }

        // --- 3. TOP TOOLBAR (Z-order 2) ---
        Surface(
            color = Color.Black.copy(alpha = 0.85f),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .pointerInput(Unit) {
                    detectTapGestures { /* absorb taps on toolbar so they do not deselect */ }
                }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back & Reset
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onNavigateBack,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("رجوع")
                    }

                    OutlinedButton(
                        onClick = {
                            selectedPreset = ControllerPreset.SIX_BUTTON
                            if (widthPx > 0 && heightPx > 0) {
                                buttons = getPresetLayout(ControllerPreset.SIX_BUTTON, widthPx, heightPx)
                            }
                            selectedButtonId = null
                            Toast.makeText(context, "تمت استعادة الوضع الافتراضي", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("الافتراضي")
                    }

                    Button(
                        onClick = { showMacroDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100))
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        val activeMacroCount = buttons.count { it.isMacro && it.isEnabled }
                        Text("أزرار الماكرو ($activeMacroCount)")
                    }
                }

                // Controls for Selected Button (Size, Opacity, Hide, Deselect)
                if (selectedButton != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        IconButton(
                            onClick = { selectedButtonId = null },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "إلغاء التحديد",
                                tint = Color.LightGray,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Text(
                            text = "${selectedButton.label}:",
                            color = Color.Yellow,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )

                        // Size Slider
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("الحجم:", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(end = 4.dp))
                            Slider(
                                value = selectedButton.scale,
                                onValueChange = { newScale ->
                                    buttons = buttons.map {
                                        if (it.id == selectedButton.id) it.copy(scale = newScale) else it
                                    }
                                    selectedPreset = ControllerPreset.CUSTOM
                                },
                                valueRange = 0.5f..2.5f,
                                modifier = Modifier.width(90.dp)
                            )
                        }

                        // Opacity Slider
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("الشفافية:", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(end = 4.dp))
                            Slider(
                                value = selectedButton.opacity,
                                onValueChange = { newOpacity ->
                                    buttons = buttons.map {
                                        if (it.id == selectedButton.id) it.copy(opacity = newOpacity) else it
                                    }
                                    selectedPreset = ControllerPreset.CUSTOM
                                },
                                valueRange = 0.2f..1.0f,
                                modifier = Modifier.width(90.dp)
                            )
                        }

                        if (selectedButton.isMacro) {
                            IconButton(
                                onClick = {
                                    buttons = buttons.map {
                                        if (it.id == selectedButton.id) it.copy(isEnabled = false) else it
                                    }
                                    selectedButtonId = null
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.VisibilityOff, contentDescription = "إخفاء", tint = Color.Red, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                } else {
                    Text(
                        "اضغط على أي زر لتعديل حجمه وشفافيته وموضعه",
                        color = Color.LightGray,
                        fontSize = 12.sp
                    )
                }

                // Preset Dropdown & Save Button
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        OutlinedButton(onClick = { showPresetMenu = true }) {
                            Text(selectedPreset.displayName.substringBefore(" "))
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(expanded = showPresetMenu, onDismissRequest = { showPresetMenu = false }) {
                            ControllerPreset.values().forEach { preset ->
                                DropdownMenuItem(
                                    text = { Text(preset.displayName) },
                                    onClick = {
                                        selectedPreset = preset
                                        showPresetMenu = false
                                    }
                                )
                            }
                        }
                    }

                    Button(
                        onClick = {
                            coroutineScope.launch {
                                val jsonArray = JSONArray()
                                buttons.forEach { b ->
                                    val obj = JSONObject()
                                    obj.put("id", b.id)
                                    obj.put("x", b.initialX)
                                    obj.put("y", b.initialY)
                                    obj.put("scale", b.scale.toDouble())
                                    obj.put("opacity", b.opacity.toDouble())
                                    obj.put("enabled", b.isEnabled)
                                    obj.put("macro", b.isMacro)
                                    jsonArray.put(obj)
                                }
                                ControllerDataStore.saveLayout(context, jsonArray.toString())
                                LiveLogger.i(
                                    LiveLogger.Category.CONTROLLER,
                                    "LAYOUT_SAVED",
                                    "Saved ${buttons.size} buttons (Active=${buttons.count { it.isEnabled }})"
                                )
                                Toast.makeText(context, "تم حفظ تخطيط أزرار التحكم بنجاح!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("حفظ")
                    }
                }
            }
        }
    }

    // --- MACRO BUTTON SELECTION DIALOG (Vertically Scrollable & Fixed Controls) ---
    if (showMacroDialog) {
        val configuration = LocalConfiguration.current
        val maxDialogHeight = (configuration.screenHeightDp.dp * 0.55f).coerceIn(160.dp, 380.dp)
        val macroScrollState = rememberScrollState()

        AlertDialog(
            onDismissRequest = { showMacroDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .wrapContentHeight(),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bolt, contentDescription = null, tint = Color(0xFFFF9800))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("أزرار الماكرو والأكشن المركبة")
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "فعّل الأزرار المركبة التي ترغب بإظهارها على شاشة اللعب (مخفية افتراضياً لحماية الشاشة):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxDialogHeight)
                            .verticalScroll(macroScrollState),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MACRO_CATALOG.forEach { macro ->
                            val currentBtn = buttons.find { it.id == macro.id }
                            val isEnabled = currentBtn?.isEnabled == true

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val newEnabled = !isEnabled
                                        buttons = buttons.map {
                                            if (it.id == macro.id) it.copy(isEnabled = newEnabled) else it
                                        }
                                        selectedPreset = ControllerPreset.CUSTOM
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isEnabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Surface(
                                                color = if (isEnabled) Color(0xFFFF9800) else Color.Gray,
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    macro.label,
                                                    color = Color.White,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(macro.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            macro.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 11.sp
                                        )
                                    }

                                    Switch(
                                        checked = isEnabled,
                                        onCheckedChange = { checked ->
                                            buttons = buttons.map {
                                                if (it.id == macro.id) it.copy(isEnabled = checked) else it
                                            }
                                            selectedPreset = ControllerPreset.CUSTOM
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showMacroDialog = false }) {
                    Text("تم")
                }
            }
        )
    }
}

fun getLabelForId(id: String): String {
    return when(id) {
        "dpad_up" -> "↑"
        "dpad_down" -> "↓"
        "dpad_left" -> "←"
        "dpad_right" -> "→"
        "btn_start" -> "START"
        "btn_menu" -> "MENU"
        "macro_charge" -> "CHG"
        "macro_ab" -> "A+B"
        "macro_bc" -> "B+C"
        "macro_xy" -> "X+Y"
        "macro_abc" -> "ABC"
        "macro_round_reset" -> "RESET"
        "macro_max_power" -> "MAX"
        "macro_control_swap" -> "SWAP"
        "macro_team_switch" -> "TAG"
        else -> id.removePrefix("btn_").removePrefix("macro_").uppercase()
    }
}

fun getPresetLayout(preset: ControllerPreset, widthPx: Float, heightPx: Float): List<ControllerButtonState> {
    val unit = (widthPx * 0.08f).coerceIn(50f, 120f)

    val dpadCx = widthPx * 0.15f + unit
    val dpadCy = heightPx * 0.65f

    val dpad = listOf(
        ControllerButtonState("dpad_up", dpadCx, dpadCy - unit, "↑"),
        ControllerButtonState("dpad_down", dpadCx, dpadCy + unit, "↓"),
        ControllerButtonState("dpad_left", dpadCx - unit, dpadCy, "←"),
        ControllerButtonState("dpad_right", dpadCx + unit, dpadCy, "→")
    )

    val system = listOf(
        ControllerButtonState("btn_start", widthPx * 0.5f - unit * 0.8f, heightPx * 0.85f, "START", scale = 0.8f),
        ControllerButtonState("btn_menu", widthPx * 0.5f + unit * 0.8f, heightPx * 0.85f, "MENU", scale = 0.8f)
    )

    val actionCx = widthPx * 0.85f - unit
    val actionCy = heightPx * 0.65f

    val actionButtons = when (preset) {
        ControllerPreset.CLASSIC -> listOf(
            ControllerButtonState("btn_a", actionCx + unit, actionCy, "A"),
            ControllerButtonState("btn_b", actionCx, actionCy + unit, "B"),
            ControllerButtonState("btn_x", actionCx, actionCy - unit, "X"),
            ControllerButtonState("btn_y", actionCx - unit, actionCy, "Y")
        )
        ControllerPreset.ARCADE -> listOf(
            ControllerButtonState("btn_x", actionCx - unit, actionCy - unit * 0.6f, "X"),
            ControllerButtonState("btn_y", actionCx, actionCy - unit * 0.6f, "Y"),
            ControllerButtonState("btn_z", actionCx + unit, actionCy - unit * 0.6f, "Z"),
            ControllerButtonState("btn_a", actionCx - unit, actionCy + unit * 0.6f, "A"),
            ControllerButtonState("btn_b", actionCx, actionCy + unit * 0.6f, "B"),
            ControllerButtonState("btn_c", actionCx + unit, actionCy + unit * 0.6f, "C")
        )
        else -> listOf(
            ControllerButtonState("btn_x", actionCx - unit, actionCy - unit * 0.5f, "X"),
            ControllerButtonState("btn_y", actionCx, actionCy - unit, "Y"),
            ControllerButtonState("btn_z", actionCx + unit, actionCy - unit * 0.5f, "Z"),
            ControllerButtonState("btn_a", actionCx - unit, actionCy + unit * 0.5f, "A"),
            ControllerButtonState("btn_b", actionCx, actionCy, "B"),
            ControllerButtonState("btn_c", actionCx + unit, actionCy + unit * 0.5f, "C")
        )
    }

    return dpad + system + actionButtons
}

@Composable
fun DraggableButton(
    buttonState: ControllerButtonState,
    isSelected: Boolean,
    onClick: () -> Unit,
    onPositionChanged: (Float, Float) -> Unit
) {
    var offsetX by remember(buttonState.initialX) { mutableStateOf(buttonState.initialX) }
    var offsetY by remember(buttonState.initialY) { mutableStateOf(buttonState.initialY) }

    val baseSize = if (buttonState.label.length > 2) 80.dp else if (buttonState.label.length > 1) 70.dp else 64.dp
    val scaledSize = baseSize * buttonState.scale

    val borderModifier = if (isSelected) {
        Modifier.border(2.5.dp, Color.Yellow, CircleShape)
    } else if (buttonState.isMacro) {
        Modifier.border(2.dp, Color(0xFFFFB300), CircleShape)
    } else {
        Modifier.border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
    }

    val bgAlpha = (buttonState.opacity * (if (isSelected) 0.6f else 0.35f)).coerceIn(0.1f, 1.0f)
    val bgColor = if (buttonState.isMacro) Color(0xFFE65100).copy(alpha = bgAlpha) else Color.White.copy(alpha = bgAlpha)

    Box(
        modifier = Modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .size(scaledSize)
            .then(borderModifier)
            .background(bgColor, CircleShape)
            .pointerInput(buttonState.id) {
                detectDragGestures(
                    onDragStart = { onClick() },
                    onDragEnd = { onPositionChanged(offsetX, offsetY) }
                ) { change, dragAmount ->
                    change.consume()
                    offsetX += dragAmount.x
                    offsetY += dragAmount.y
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = buttonState.label,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            fontSize = if (buttonState.label.length > 3) 11.sp else if (buttonState.label.length > 1) 13.sp else 16.sp
        )
    }
}

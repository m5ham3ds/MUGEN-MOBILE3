package com.example.ui.screens.runtime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.ui.screens.controllerEditor.ControllerButtonState
import com.example.ui.screens.controllerEditor.ControllerPreset
import com.example.ui.screens.controllerEditor.getLabelForId
import com.example.ui.screens.controllerEditor.getPresetLayout
import org.json.JSONArray
import kotlin.math.hypot

/**
 * Native Android View implementing the multi-touch on-screen arcade controller for MUGEN Mobile.
 *
 * Designed to completely replace ComposeView inside SDLActivity's view tree:
 * 1. Zero Compose runtime overhead and zero ViewTreeLifecycleOwner dependencies.
 * 2. High-performance multi-touch handling for fighting game combos and diagonals.
 * 3. Direct dispatch to SDLActivity.onNativeKeyDown / onNativeKeyUp.
 */
class NativeVirtualControllerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class ButtonModel(
        val id: String,
        var centerX: Float,
        var centerY: Float,
        var radius: Float,
        val label: String,
        var opacity: Float = 0.7f,
        var isEnabled: Boolean = true,
        var isMacro: Boolean = false
    )

    var onButtonPressed: ((String) -> Unit)? = null
    var onButtonReleased: ((String) -> Unit)? = null

    private val buttonModels = mutableListOf<ButtonModel>()
    private val pointerButtonMap = HashMap<Int, String>()
    private val pressedButtonIds = HashSet<String>()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(60, 255, 255, 255)
    }

    private val pressedBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(160, 230, 45, 45) // Vivid fighting game red
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * context.resources.displayMetrics.density
        color = Color.argb(130, 255, 255, 255)
    }

    private val macroBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * context.resources.displayMetrics.density
        color = Color.argb(220, 255, 193, 7) // Amber/Gold for Macro / Charge
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    init {
        isClickable = true
        isFocusable = false
        // Setup initial default layout based on screen metrics
        val dm = context.resources.displayMetrics
        loadDefaultPreset(dm.widthPixels.toFloat(), dm.heightPixels.toFloat())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && buttonModels.isEmpty()) {
            loadDefaultPreset(w.toFloat(), h.toFloat())
        }
    }

    private fun loadDefaultPreset(width: Float, height: Float) {
        val preset = getPresetLayout(ControllerPreset.SIX_BUTTON, width, height)
        applyControllerButtons(preset)
    }

    /**
     * Updates button layout from saved JSON or defaults.
     */
    fun updateLayoutFromJson(jsonStr: String?, width: Float = this.width.toFloat(), height: Float = this.height.toFloat()) {
        val w = if (width > 0) width else context.resources.displayMetrics.widthPixels.toFloat()
        val h = if (height > 0) height else context.resources.displayMetrics.heightPixels.toFloat()

        if (!jsonStr.isNullOrEmpty()) {
            try {
                val jsonArray = JSONArray(jsonStr)
                val loaded = mutableListOf<ControllerButtonState>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val id = obj.getString("id")
                    val isMacro = id.startsWith("macro_")
                    val isEnabled = if (obj.has("enabled")) obj.getBoolean("enabled") else !isMacro
                    val scale = if (obj.has("scale")) obj.getDouble("scale").toFloat() else 1.0f
                    val opacity = if (obj.has("opacity")) obj.getDouble("opacity").toFloat() else 0.7f

                    loaded.add(
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
                }
                if (loaded.isNotEmpty()) {
                    applyControllerButtons(loaded)
                    return
                }
            } catch (e: Exception) {
                // Fall back to default
            }
        }
        loadDefaultPreset(w, h)
    }

    fun applyControllerButtons(buttons: List<ControllerButtonState>) {
        val density = context.resources.displayMetrics.density
        buttonModels.clear()
        for (btn in buttons) {
            // Only add enabled buttons to active touch and render models
            if (!btn.isEnabled) continue

            val baseDp = if (btn.label.length > 2) 80f else if (btn.label.length > 1) 70f else 64f
            val sizePx = baseDp * density * btn.scale
            val radius = sizePx / 2f
            val cx = btn.initialX + radius
            val cy = btn.initialY + radius
            buttonModels.add(
                ButtonModel(
                    id = btn.id,
                    centerX = cx,
                    centerY = cy,
                    radius = radius,
                    label = btn.label,
                    opacity = btn.opacity,
                    isEnabled = btn.isEnabled,
                    isMacro = btn.isMacro
                )
            )
        }
        invalidate()
    }

    fun findButtonAt(x: Float, y: Float): ButtonModel? {
        var closestBtn: ButtonModel? = null
        var minNormalizedDist = Float.MAX_VALUE

        // Select the button whose center is closest to the touch point, preventing accidental cross-talk
        for (b in buttonModels) {
            val dist = hypot((x - b.centerX).toDouble(), (y - b.centerY).toDouble()).toFloat()
            val maxAllowed = b.radius * 1.15f
            if (dist <= maxAllowed) {
                val normalizedDist = dist / b.radius
                if (normalizedDist < minNormalizedDist) {
                    minNormalizedDist = normalizedDist
                    closestBtn = b
                }
            }
        }
        return closestBtn
    }

    fun getPressedButtonIds(): Set<String> = HashSet(pressedButtonIds)

    fun getButtonModels(): List<ButtonModel> = ArrayList(buttonModels)

    fun releaseAllPressedButtons() {
        for (buttonId in ArrayList(pressedButtonIds)) {
            onButtonReleased?.invoke(buttonId)
        }
        pointerButtonMap.clear()
        pressedButtonIds.clear()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        releaseAllPressedButtons()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val actionMasked = event.actionMasked
        val actionIndex = event.actionIndex

        when (actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerId = event.getPointerId(actionIndex)
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)
                val btn = findButtonAt(x, y)
                if (btn != null) {
                    pointerButtonMap[pointerId] = btn.id
                    if (pressedButtonIds.add(btn.id)) {
                        onButtonPressed?.invoke(btn.id)
                    }
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                var changed = false
                for (i in 0 until event.pointerCount) {
                    val pointerId = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)
                    val prevButtonId = pointerButtonMap[pointerId]
                    val currentBtn = findButtonAt(x, y)
                    val newButtonId = currentBtn?.id

                    if (prevButtonId != newButtonId) {
                        if (prevButtonId != null) {
                            pointerButtonMap.remove(pointerId)
                            if (!pointerButtonMap.values.contains(prevButtonId)) {
                                pressedButtonIds.remove(prevButtonId)
                                onButtonReleased?.invoke(prevButtonId)
                            }
                        }
                        if (newButtonId != null) {
                            pointerButtonMap[pointerId] = newButtonId
                            if (pressedButtonIds.add(newButtonId)) {
                                onButtonPressed?.invoke(newButtonId)
                            }
                        }
                        changed = true
                    }
                }
                if (changed) invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val pointerId = event.getPointerId(actionIndex)
                val buttonId = pointerButtonMap.remove(pointerId)
                if (buttonId != null) {
                    if (!pointerButtonMap.values.contains(buttonId)) {
                        pressedButtonIds.remove(buttonId)
                        onButtonReleased?.invoke(buttonId)
                    }
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                releaseAllPressedButtons()
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (btn in buttonModels) {
            val isPressed = pressedButtonIds.contains(btn.id)
            val alphaMult = btn.opacity.coerceIn(0.1f, 1.0f)

            // Draw circular background
            val baseFillAlpha = if (isPressed) (180 * alphaMult).toInt() else (65 * alphaMult).toInt()
            val fillPaint = if (isPressed) {
                if (btn.isMacro) {
                    Paint(pressedBgPaint).apply { color = Color.argb((200 * alphaMult).toInt(), 255, 140, 0) }
                } else {
                    Paint(pressedBgPaint).apply { alpha = (180 * alphaMult).toInt() }
                }
            } else {
                Paint(bgPaint).apply { alpha = baseFillAlpha }
            }
            canvas.drawCircle(btn.centerX, btn.centerY, btn.radius, fillPaint)

            // Draw border ring
            val bPaint = if (btn.isMacro) {
                Paint(macroBorderPaint).apply { alpha = (230 * alphaMult).toInt() }
            } else {
                Paint(borderPaint).apply { alpha = (150 * alphaMult).toInt() }
            }
            canvas.drawCircle(btn.centerX, btn.centerY, btn.radius, bPaint)

            // Dynamic text size based on button radius
            val textPx = (btn.radius * 0.65f).coerceIn(14f, 40f)
            textPaint.textSize = textPx
            textPaint.alpha = (255 * alphaMult).toInt()

            val fontMetrics = textPaint.fontMetrics
            val baseline = btn.centerY - (fontMetrics.descent + fontMetrics.ascent) / 2f
            canvas.drawText(btn.label, btn.centerX, baseline, textPaint)
        }
    }
}

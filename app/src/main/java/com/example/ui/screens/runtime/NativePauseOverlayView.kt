package com.example.ui.screens.runtime

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Native Android View pause menu overlay for MUGEN Mobile.
 *
 * Replaces Compose GameOverlay inside SDLActivity, providing instant,
 * crash-proof pause/resume/exit controls directly in the Android view hierarchy.
 */
class NativePauseOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var onResumeClicked: (() -> Unit)? = null
    var onExitClicked: (() -> Unit)? = null

    init {
        // Semi-transparent backdrop that intercepts touch events
        setBackgroundColor(Color.argb(190, 10, 10, 15))
        isClickable = true
        isFocusable = true

        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
            background = GradientDrawable().apply {
                setColor(Color.argb(245, 25, 25, 30))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.argb(80, 255, 255, 255))
            }
        }

        val title = TextView(context).apply {
            text = "قائمة التوقف — PAUSE"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(20))
        }
        card.addView(title)

        val resumeButton = Button(context).apply {
            text = "استئناف اللعب — Resume"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D32F2F")) // Fighting game red
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener {
                onResumeClicked?.invoke()
            }
        }
        val btnParams = LinearLayout.LayoutParams(dp(250), dp(48)).apply {
            bottomMargin = dp(12)
        }
        card.addView(resumeButton, btnParams)

        val exitButton = Button(context).apply {
            text = "الخروج من اللعبة — Exit Game"
            setTextColor(Color.WHITE)
            textSize = 14f
            background = GradientDrawable().apply {
                setColor(Color.argb(70, 80, 80, 90))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.argb(100, 200, 200, 200))
            }
            setOnClickListener {
                onExitClicked?.invoke()
            }
        }
        card.addView(exitButton, btnParams)

        val cardParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        addView(card, cardParams)
    }
}

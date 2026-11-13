package io.github.shaonialife.cardeck.launcher

import android.app.Dialog
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.shaonialife.cardeck.R
import kotlin.math.roundToInt

/** The grid and map use separate displays. Only the visible music card is available for this window. */
internal class DashboardDestinationDialog(
    host: GridActivity,
    private val anchor: View,
    private val menuCurrent: () -> Boolean,
    onDestination: (BaiduDestination) -> Unit,
) : Dialog(host, android.R.style.Theme_Material_Dialog_NoActionBar) {
    private val frame = FrameLayout(host)
    private val sheet = ScrollView(host)
    private val content = LinearLayout(host)
    private val heading = TextView(host)
    private val choices = BaiduDestination.entries
    private val rows = mutableListOf<Button>()
    private val bounds = Rect()
    private val visible = Rect()
    private var palette = host.carPalette
    private var compact = false
    private var confirmKey = KeyEvent.KEYCODE_UNKNOWN
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { updateBounds() }
    private val anchorListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) = dismiss()
    }

    init {
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            decorView.setPadding(0, 0, 0, 0)
        }
        setCanceledOnTouchOutside(true)
        setTitle(R.string.dashboard_destination_title)
        setContentView(frame)
        frame.setOnClickListener { dismiss() }
        frame.isFocusable = false
        frame.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

        sheet.clipToOutline = true
        sheet.elevation = dp(5).toFloat()
        sheet.isClickable = true
        frame.addView(sheet, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        content.orientation = LinearLayout.VERTICAL
        sheet.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        heading.setText(R.string.dashboard_destination_title)
        heading.textSize = 21f
        heading.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        heading.isAccessibilityHeading = true
        heading.setPadding(0, 0, 0, dp(12))
        content.addView(heading, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        for (index in 0..choices.size) {
            val choice = choices.getOrNull(index)
            val row = Button(host).apply {
                text = choice?.title ?: host.getString(R.string.dashboard_destination_cancel)
                textSize = if (choice == null) 15f else 17f
                isAllCaps = false
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = if (choice == null) Gravity.CENTER else Gravity.START or Gravity.CENTER_VERTICAL
                minWidth = 0
                minimumWidth = 0
                minHeight = dp(48)
                stateListAnimator = null
                backgroundTintList = null
                defaultFocusHighlightEnabled = false
                isFocusableInTouchMode = false
                setOnClickListener {
                    if (!isShowing) return@setOnClickListener
                    dismiss()
                    if (choice != null && menuCurrent()) onDestination(choice)
                }
            }
            rows += row
            content.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = if (index == 0) 0 else dp(6)
            })
        }
        setOnKeyListener { _, key, event -> handleKey(key, event) }
        applyPalette(palette)
    }

    override fun onStart() {
        super.onStart()
        anchor.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        anchor.addOnAttachStateChangeListener(anchorListener)
        if (!anchor.isInTouchMode) rows.first().requestFocus()
    }

    override fun onStop() {
        anchor.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        anchor.removeOnAttachStateChangeListener(anchorListener)
        super.onStop()
    }

    fun applyPalette(value: ProjectedPalette) {
        palette = value
        palette.background(sheet, R.drawable.bg_destination_sheet)
        heading.setTextColor(palette.text)
        for ((index, row) in rows.withIndex()) {
            val choice = choices.getOrNull(index)
            palette.background(row, if (choice == null) R.drawable.bg_destination_cancel else R.drawable.bg_car_media_button)
            row.setTextColor(if (choice == null) palette.secondary else palette.text)
            // Android Button's default focus halo depends on the phone theme.
            row.foreground = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(3), palette.accent)
                })
            }
        }
        styleRowContent()
    }

    private fun styleRowContent() {
        val side = dp(if (compact) 7 else 14)
        content.setPadding(side, dp(14), side, dp(8))
        for ((index, row) in rows.withIndex()) {
            val choice = choices.getOrNull(index)
            val icon = if (compact || choice == null) null else when (choice) {
                BaiduDestination.HOME -> R.drawable.ic_dock_home
                BaiduDestination.COMPANY -> R.drawable.ic_destination_work
                BaiduDestination.NEW -> R.drawable.ic_destination_search
            }
            row.compoundDrawablePadding = dp(10)
            row.setCompoundDrawablesRelative(icon?.let {
                context.getDrawable(it)?.mutate()?.apply {
                    setTint(palette.accent)
                    setBounds(0, 0, dp(22), dp(22))
                }
            }, null, null, null)
            row.setPaddingRelative(side, dp(5), side, dp(5))
        }
    }

    /** Re-evaluate on split/anchor changes, including a dashboard leaving the screen. */
    fun updateBounds(): Boolean {
        if (!menuCurrent() || !anchor.isShown || !anchor.getGlobalVisibleRect(visible)) {
            if (isShowing) dismiss()
            return false
        }
        if (visible == bounds) return true
        bounds.set(visible)
        val width = minOf(bounds.width(), dp(360))
        compact = width < dp(184)
        val inset = minOf(dp(8), width / 24, bounds.height() / 24)
        frame.setPadding(inset, inset, inset, inset)
        styleRowContent()
        frame.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(bounds.height(), MeasureSpec.AT_MOST),
        )
        window?.let { dialogWindow ->
            dialogWindow.attributes = dialogWindow.attributes.apply {
                gravity = Gravity.TOP or Gravity.LEFT
                this.width = width
                height = frame.measuredHeight
                x = bounds.left + (bounds.width() - width) / 2
                y = bounds.top + (bounds.height() - height) / 2
            }
        }
        return true
    }

    private fun handleKey(key: Int, event: KeyEvent): Boolean {
        val index = rows.indexOfFirst { it.hasFocus() }
        val step = when (key) {
            KeyEvent.KEYCODE_TAB -> if (event.isShiftPressed) -1 else 1
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 0
            else -> return false // BACK uses the dialog window's normal cancellation.
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val next = if (index >= 0) (index + step).coerceIn(rows.indices)
                else if (step < 0) rows.lastIndex else 0
            // The homepage may have opened us using a CENTER-down whose release lands here.
            // Confirm only a paired press on an already highlighted row.
            confirmKey = if (step == 0 && index >= 0) key else KeyEvent.KEYCODE_UNKNOWN
            rows[next].requestFocusFromTouch()
        } else if (event.action == KeyEvent.ACTION_UP && step == 0 && confirmKey == key) {
            confirmKey = KeyEvent.KEYCODE_UNKNOWN
            if (!event.isCanceled) rows.getOrNull(index)?.performClick()
        }
        return true
    }

    private fun dp(value: Int): Int = (value * anchor.resources.displayMetrics.density).roundToInt()
}

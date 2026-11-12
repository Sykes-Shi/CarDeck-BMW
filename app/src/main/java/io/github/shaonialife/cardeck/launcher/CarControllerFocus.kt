package io.github.shaonialife.cardeck.launcher

import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import io.github.shaonialife.cardeck.R

/** A visible cursor for CarDeck's touch-first controls. Never changes a control's background. */
internal class CarControllerFocus {
    private companion object {
        var owner = java.lang.ref.WeakReference<CarControllerFocus>(null)
    }
    private var selected: View? = null
    private var ring: Drawable? = null

    fun clear() {
        ring?.let { selected?.overlay?.remove(it) }
        ring = null
        selected = null
        if (owner.get() === this) owner.clear()
    }

    fun select(view: View) {
        if (selected === view && ring != null) return
        if (owner.get() !== this) owner.get()?.clear()
        clear()
        val border = requireNotNull(view.context.getDrawable(R.drawable.car_controller_focus)).mutate()
        border.setBounds(0, 0, view.width, view.height)
        view.overlay.add(border)
        selected = view
        ring = border
        owner = java.lang.ref.WeakReference(this)
    }

    private fun controls(root: View): List<Pair<View, ControllerFocusTraversal.Point>> {
        val visible = mutableListOf<Pair<View, ControllerFocusTraversal.Point>>()
        fun visit(view: View) {
            if (!view.isShown || !view.isEnabled || view.alpha <= 0.01f) return
            val bounds = Rect()
            if (!view.getGlobalVisibleRect(bounds) || bounds.width() < 2 || bounds.height() < 2) return
            if (view.isClickable && (view.hasOnClickListeners() || view is android.widget.CompoundButton)) {
                visible.add(view to ControllerFocusTraversal.Point(bounds.centerX(), bounds.centerY()))
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(root)
        return visible
    }

    fun selectEdge(root: View, first: Boolean) {
        val views = controls(root)
        (if (first) views.firstOrNull() else views.lastOrNull())?.first?.let(::select)
    }

    fun handle(event: KeyEvent, root: View, turnPage: (Int) -> Boolean = { false }): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_DPAD)) return false
        val key = event.keyCode
        if (key != KeyEvent.KEYCODE_TAB && key !in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER) return false
        if (event.action == KeyEvent.ACTION_UP || event.repeatCount > 0) return true
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val views = controls(root)
        if (views.isEmpty()) return false
        val index = views.indexOfFirst { it.first === selected }
        if (key == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (index < 0) select(views.first().first) else views[index].first.performClick()
            return true
        }
        val points = views.map { it.second }
        val next = if (key == KeyEvent.KEYCODE_TAB) ControllerFocusTraversal.move(points, index, !event.isShiftPressed)
            else ControllerFocusTraversal.directional(points, index,
                key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT,
                key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_DPAD_DOWN)
        if (next.pageDelta == 0 || !turnPage(next.pageDelta)) select(views[next.index].first)
        return true
    }
}

/** The phone settings Activity has its own input behavior. Only car-hosted pages use this base. */
abstract class CarControllerActivity : androidx.appcompat.app.AppCompatActivity() {
    private val controllerFocus = CarControllerFocus()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val displayId = display?.displayId ?: -1
        if (displayId > 0 && displayId == VirtualLauncher.activeAppDisplayId() && !VirtualLauncher.mapFullscreen &&
            controllerFocus.handle(event, window.decorView)
        ) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) controllerFocus.clear()
        return super.dispatchTouchEvent(event)
    }

    override fun onStop() {
        controllerFocus.clear()
        super.onStop()
    }
}

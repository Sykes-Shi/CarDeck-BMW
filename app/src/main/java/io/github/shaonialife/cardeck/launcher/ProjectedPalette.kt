package io.github.shaonialife.cardeck.launcher

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.children
import io.github.shaonialife.cardeck.R

/** Uses a scoped theme so Android and third-party apps keep their own display preferences. */
internal class ProjectedPalette(context: Context, val day: Boolean) {
    private val styled = if (day) ContextThemeWrapper(context, R.style.CarPaletteDay) else context
    private fun color(attr: Int): Int = TypedValue().also { check(styled.theme.resolveAttribute(attr, it, true)) }.data
    val surface = color(R.attr.deckSurface)
    val text = color(R.attr.deckText)
    val secondary = color(R.attr.deckSecondary)
    val accent = color(R.attr.deckAccent)
    private val onAccent = color(R.attr.deckOnAccent)
    private val track = ColorStateList.valueOf(ColorUtils.setAlphaComponent(secondary, 85))

    fun background(view: View, drawable: Int) {
        view.background = styled.getDrawable(drawable)
    }

    fun cellLabel(view: TextView) {
        background(view, R.drawable.bg_app_label)
        view.setTextColor(text)
    }

    /** Called when the theme changes or a new media view is attached, never on the playback tick. */
    fun media(view: View) {
        when (view.id) {
            R.id.carMediaRoot -> view.setBackgroundColor(surface)
            R.id.carMediaCoverFrame, R.id.dashCoverFrame, R.id.dashPhoneNavigationNotice ->
                background(view, R.drawable.bg_car_media_surface)
            R.id.carMediaBack, R.id.carMediaOpenOriginal, R.id.dashOpenPlayer, R.id.dashBaiduDestination ->
                background(view, R.drawable.bg_car_media_button)
            R.id.dashBaiduCruise -> background(view, R.drawable.bg_car_media_cruise)
            R.id.dashPlayPause, R.id.carMediaPlayPause -> background(view, R.drawable.bg_car_media_play)
            R.id.dashPrev, R.id.dashNext, R.id.carMediaPrevious, R.id.carMediaNext ->
                background(view, R.drawable.bg_car_media_skip)
        }
        when (view) {
            is TextView -> {
                // Anonymous headings and subtitles in both layout orientations retain their XML role.
                val muted = view.getTag(R.id.carMutedTextRole) as? Boolean ?: (
                    view.currentTextColor == view.context.getColor(R.color.car_text_muted)
                ).also { view.setTag(R.id.carMutedTextRole, it) }
                view.setTextColor(if (muted) secondary else text)
                if (view.id == R.id.dashBaiduCruise) view.compoundDrawableTintList = ColorStateList.valueOf(accent)
            }
            is ImageView -> when (view.id) {
                R.id.dashPlayPause, R.id.carMediaPlayPause -> view.imageTintList = ColorStateList.valueOf(onAccent)
                R.id.dashPrev, R.id.dashNext, R.id.carMediaPrevious, R.id.carMediaNext, R.id.carMediaBack ->
                    view.imageTintList = ColorStateList.valueOf(text)
            }
            is ProgressBar -> {
                view.progressTintList = ColorStateList.valueOf(accent)
                view.progressBackgroundTintList = track
            }
            is ViewGroup -> view.children.forEach(::media)
        }
    }
}

package io.github.shaonialife.cardeck.launcher

import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import androidx.core.graphics.ColorUtils

/** 复用后台已解码的小封面；柔化交给渲染器，主线程不重新解码或逐像素处理。 */
internal class MediaBackdrop(private val image: ImageView, private val veil: View) {
    private var shownArt: Bitmap? = null
    private var shownColor: Int? = null
    private var shownDay: Boolean? = null

    init {
        image.alpha = 0f
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        val radius = 28f * image.resources.displayMetrics.density
        image.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
    }

    fun bind(art: Bitmap?, color: Int, palette: ProjectedPalette) {
        if (shownArt !== art) {
            shownArt = art
            image.setImageBitmap(art)
            image.alpha = if (art == null) 0f else 0.55f
        }
        if (shownColor != color || shownDay != palette.day) {
            shownDay = palette.day
            shownColor = color
            image.setBackgroundColor(color)
            val surface = palette.surface
            // Keep secondary text readable even on a white album over a light wallpaper.
            val top = if (palette.day) {
                val hsl = FloatArray(3)
                ColorUtils.colorToHSL(color, hsl)
                hsl[1] = hsl[1].coerceAtMost(0.42f)
                hsl[2] = 0.85f
                ColorUtils.blendARGB(ColorUtils.HSLToColor(hsl), surface, 0.20f)
            } else ColorUtils.blendARGB(color, surface, 0.42f)
            veil.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    ColorUtils.setAlphaComponent(top, 238),
                    surface,
                ),
            )
        }
    }

    fun clear() {
        shownArt = null
        image.setImageDrawable(null)
        image.alpha = 0f
    }
}

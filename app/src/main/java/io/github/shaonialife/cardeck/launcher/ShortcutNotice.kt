package io.github.shaonialife.cardeck.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import io.github.shaonialife.cardeck.render.OverlayCard
import kotlin.math.ceil

internal object ShortcutNotice {
    fun create(message: String, screenWidth: Int, screenHeight: Int): OverlayCard? {
        if (screenWidth < 150 || screenHeight < 80) return null
        val size = (screenHeight * 0.045f).coerceIn(18f, 56f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val width = ceil(paint.measureText(message) + size * 1.6f).toInt().coerceAtMost(screenWidth)
        val height = ceil(size * 1.95f).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        paint.color = 0xF0252C33.toInt()
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), size * .5f, size * .5f, paint)
        paint.color = android.graphics.Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(message, width / 2f, (height - paint.ascent() - paint.descent()) / 2f, paint)
        val x = (screenWidth - width) / 2f
        val y = (screenHeight - height) * .80f
        return OverlayCard(bitmap, x, y, x + width, y + height)
    }
}

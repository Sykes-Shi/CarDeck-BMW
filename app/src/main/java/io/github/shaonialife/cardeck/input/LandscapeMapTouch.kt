package io.github.shaonialife.cardeck.input

import io.github.shaonialife.cardeck.render.CARD_MARGIN_PX

/** Inverse of the compositor's landscape map rectangle, in stream pixels. */
internal fun landscapeMapTouch(
    x: Int, y: Int, dockWidth: Int, fullscreen: Boolean, tileWidth: Int, tileHeight: Int, slidePx: Float,
): Pair<Int, Int>? {
    if (x < dockWidth) return null
    if (fullscreen) return (x - dockWidth) to y
    val localX = x - dockWidth - CARD_MARGIN_PX + slidePx
    val localY = y - CARD_MARGIN_PX
    if (localX < 0f || localX >= tileWidth || localY !in 0 until tileHeight) return null
    return localX.toInt() to localY
}

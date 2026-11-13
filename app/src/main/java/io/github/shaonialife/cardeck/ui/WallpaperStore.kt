package io.github.shaonialife.cardeck.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Immutable identity carried from a load request through the cache to the displayed image. */
internal data class WallpaperSelection(val revision: Long, val day: Boolean) {
    fun <T> load(custom: () -> T?, bundled: (String) -> T?): T? =
        custom() ?: bundled(if (day) "wallpaper_day.jpg" else "wallpaper_default.jpg")
}

internal object WallpaperStore {
    private const val USER_FILE = "wallpaper.jpg"

    private val versionCounter = AtomicLong()

    /** 当前壁纸版本（更换后递增；接收方据此判断是否需要重载）。 */
    fun selection(day: Boolean) = WallpaperSelection(versionCounter.get(), day)

    // ---- 变更通知（监听器模式；接收方注册后应立即读一次当前状态兜底） ----

    private val listeners = CopyOnWriteArraySet<(Long) -> Unit>()

    /** 注册自定义壁纸变更监听（写入线程通知，视图在主线程处理）。 */
    fun addListener(listener: (Long) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Long) -> Unit) {
        listeners.remove(listener)
    }

    private fun markChanged() {
        val version = versionCounter.incrementAndGet()
        listeners.forEach { runCatching { it(version) } }
    }

    /** 用户壁纸文件（null = 使用内置默认）。 */
    private fun userWallpaperFile(context: Context): File? = File(context.filesDir, USER_FILE).takeIf { it.exists() && it.length() > 0 }

    private fun decodeUser(
        context: Context,
        maxSize: Int,
    ): Bitmap? {
        val file = userWallpaperFile(context) ?: return null
        return runCatching {
            val source = ImageDecoder.createSource(file)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                if (info.size.width > maxSize || info.size.height > maxSize) {
                    decoder.setTargetSampleSize(
                        maxOf(info.size.width, info.size.height) / maxSize + 1,
                    )
                }
            }
        }.getOrNull()?.takeIf { it.width > 0 && it.height > 0 }
    }

    private fun decodeDefault(
        context: Context,
        asset: String,
        maxSize: Int,
    ): Bitmap? =
        runCatching {
            context.assets.open(asset).use { input ->
                val options =
                    BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                        BitmapFactory.decodeStream(input, null, this)
                    }
                context.assets.open(asset).use { stream ->
                    options.inJustDecodeBounds = false
                    options.inSampleSize = sampleSize(options.outWidth, options.outHeight, maxSize)
                    BitmapFactory.decodeStream(stream, null, options)
                }
            }
        }.getOrNull()

    // Ordered writes keep repeated picks and reset in the order the user requested them.
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "wallpaper-writer") }

    fun setFromUri(context: Context, uri: Uri, done: (Boolean) -> Unit) = write(context, done) { appContext ->
        val file = File(appContext.filesDir, USER_FILE)
        val staged = File.createTempFile("wallpaper-", ".tmp", appContext.filesDir)
        try {
            val count = appContext.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { output -> input.copyTo(output) }
            } ?: 0L
            count > 0L && staged.renameTo(file)
        } finally {
            staged.delete()
        }
    }

    fun resetToDefault(context: Context, done: (Boolean) -> Unit) = write(context, done) { appContext ->
        val file = File(appContext.filesDir, USER_FILE)
        !file.exists() || file.delete()
    }

    private fun write(context: Context, done: (Boolean) -> Unit, change: (Context) -> Boolean) {
        val appContext = context.applicationContext
        writer.execute {
            val changed = runCatching { change(appContext) }.getOrDefault(false)
            if (changed) markChanged()
            android.os.Handler(android.os.Looper.getMainLooper()).post { done(changed) }
        }
    }

    private fun sampleSize(
        width: Int,
        height: Int,
        maxSize: Int,
    ): Int {
        var sample = 1
        while (width / sample > maxSize || height / sample > maxSize) sample *= 2
        return sample
    }

    // ---- 桌面切片：全流画布的 aspectFill 位图（GridActivity/DockActivity 共用） ----

    private data class CanvasKey(val selection: WallpaperSelection, val width: Int, val height: Int)
    private val canvasCache = android.util.LruCache<CanvasKey, Bitmap>(2)

    /**
     * 按「整个视频流画布」做 aspectFill 的壁纸位图（调用方把切片偏移到自己的窗口位置）。
     * 按尺寸+版本缓存（桌面与 dock 各一份）；后台线程调用，返回的位图只读。
     */
    @Synchronized
    fun aspectFillCanvas(
        context: Context,
        canvasWidth: Int,
        canvasHeight: Int,
        selection: WallpaperSelection,
    ): Bitmap? {
        if (canvasWidth <= 0 || canvasHeight <= 0 || selection.revision != versionCounter.get()) return null
        val key = CanvasKey(selection, canvasWidth, canvasHeight)
        canvasCache.get(key)?.let { return it }
        val maxSize = maxOf(canvasWidth, canvasHeight) * 2
        val source = selection.load(
            custom = { decodeUser(context, maxSize) },
            bundled = { decodeDefault(context, it, maxSize) },
        ) ?: return null
        val bitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val scale =
            maxOf(
                canvasWidth.toFloat() / source.width,
                canvasHeight.toFloat() / source.height,
            )
        val drawWidth = source.width * scale
        val drawHeight = source.height * scale
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        // 必须用「目标矩形」重载缩放绘制——drawBitmap(bmp, x, y, paint) 按原图像素
        // 尺寸绘制：源图小于画布时（如竖屏照片 < 横屏画布宽）右侧留空（透明黑）
        val dest =
            android.graphics.Rect(
                ((canvasWidth - drawWidth) / 2f).toInt(),
                ((canvasHeight - drawHeight) / 2f).toInt(),
                ((canvasWidth - drawWidth) / 2f).toInt() + drawWidth.toInt(),
                ((canvasHeight - drawHeight) / 2f).toInt() + drawHeight.toInt(),
            )
        canvas.drawBitmap(source, null, dest, paint)
        source.recycle()
        if (selection.revision != versionCounter.get()) return null
        canvasCache.put(key, bitmap)
        return bitmap
    }
}

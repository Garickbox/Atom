package com.garickbox.atom

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.mesh.core.InAppLogger
import java.io.File

/**
 * B97: MediaProjection screenshot для adb pairing OCR.
 * Захватывает текущий экран (включая системные диалоги) в PNG.
 */
object ScreenshotService {
    private var pendingResultCode: Int = 0
    private var pendingResultData: Intent? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    @Volatile private var ready = false

    fun isReady(): Boolean = ready

    /** Вызывается из MainActivity после разрешения */
    fun setup(ctx: Context, resultCode: Int, data: Intent) {
        try {
            val mgr = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            projection = mgr.getMediaProjection(resultCode, data)
            ready = projection != null
            InAppLogger.d("Screenshot", "B97 projection ready=" + ready)
        } catch (e: Exception) {
            InAppLogger.err("Screenshot", "B97 setup: " + e.message)
            ready = false
        }
    }

    /** Захват экрана → /sdcard/Download/Atom/logs/screenshot_TS.png */
    fun capture(ctx: Context): File? {
        val proj = projection ?: run {
            InAppLogger.d("Screenshot", "B97 no projection")
            return null
        }
        try {
            val dm = ctx.resources.displayMetrics
            val w = dm.widthPixels
            val h = dm.heightPixels
            val dpi = dm.densityDpi

            val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            val vd = proj.createVirtualDisplay(
                "atom-screenshot",
                w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )

            val latch = java.util.concurrent.CountDownLatch(1)
            val outFile = File(ctx.getExternalFilesDir("logs") ?: ctx.filesDir,
                "screenshot_" + System.currentTimeMillis() + ".png")

            reader.setOnImageAvailableListener({ r ->
                try {
                    r.acquireLatestImage()?.use { img ->
                        val plane = img.planes[0]
                        val buffer = plane.buffer
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val rowPadding = rowStride - pixelStride * w
                        val bmp = Bitmap.createBitmap(
                            w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888
                        )
                        bmp.copyPixelsFromBuffer(buffer)
                        val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h)
                        outFile.outputStream().use { out ->
                            cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        cropped.recycle()
                        bmp.recycle()
                    }
                } catch (e: Exception) {
                    InAppLogger.err("Screenshot", "B97 capture: " + e.message)
                } finally {
                    latch.countDown()
                }
            }, Handler(Looper.getMainLooper()))

            latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
            try { vd.release() } catch (_: Exception) {}
            try { reader.close() } catch (_: Exception) {}

            if (outFile.exists() && outFile.length() > 0) {
                InAppLogger.d("Screenshot",
                    "B97 captured: " + outFile.absolutePath + " (" + outFile.length() + "B)")
                return outFile
            }
            return null
        } catch (e: Exception) {
            InAppLogger.err("Screenshot", "B97 capture fail: " + e.message)
            return null
        }
    }
}

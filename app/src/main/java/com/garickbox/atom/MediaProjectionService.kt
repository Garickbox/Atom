package com.garickbox.atom

import android.app.Notification
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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mesh.core.InAppLogger
import java.io.File

class MediaProjectionService : Service() {
    companion object {
        const val NOTIF_ID = 7500
        const val ACTION_START = "com.garickbox.atom.MPS_START"
        @Volatile private var projection: MediaProjection? = null

        fun isReady(): Boolean = projection != null

        fun start(ctx: Context, code: Int, data: Intent) {
            try {
                val i = Intent(ctx, MediaProjectionService::class.java).apply {
                    action = ACTION_START
                    putExtra("code", code)
                    putExtra("data", data)
                }
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) { InAppLogger.err("MPService", "start: " + e.message) }
        }

        fun capture(ctx: Context): File? {
            val proj = projection ?: return null
            try {
                val dm = ctx.resources.displayMetrics
                val w = dm.widthPixels; val h = dm.heightPixels; val dpi = dm.densityDpi
                val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
                val vd = proj.createVirtualDisplay("atom-cap", w, h, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null)
                val latch = java.util.concurrent.CountDownLatch(1)
                val out = File(ctx.getExternalFilesDir("logs") ?: ctx.filesDir,
                    "screenshot_" + System.currentTimeMillis() + ".png")
                reader.setOnImageAvailableListener({ r ->
                    try {
                        r.acquireLatestImage()?.use { img ->
                            val plane = img.planes[0]; val buf = plane.buffer
                            val ps = plane.pixelStride; val rs = plane.rowStride
                            val pad = rs - ps * w
                            val bmp = Bitmap.createBitmap(w + pad / ps, h, Bitmap.Config.ARGB_8888)
                            bmp.copyPixelsFromBuffer(buf)
                            val crop = Bitmap.createBitmap(bmp, 0, 0, w, h)
                            out.outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            crop.recycle(); bmp.recycle()
                        }
                    } catch (e: Exception) { InAppLogger.err("MPService", "cap: " + e.message) }
                    finally { latch.countDown() }
                }, Handler(Looper.getMainLooper()))
                latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
                try { vd.release() } catch (_: Exception) {}
                try { reader.close() } catch (_: Exception) {}
                return if (out.exists() && out.length() > 0) out else null
            } catch (e: Exception) {
                InAppLogger.err("MPService", "capture: " + e.message); return null
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) { stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIF_ID, buildNotif())
        val code = intent.getIntExtra("code", 0)
        val data = intent.getParcelableExtra("data", Intent::class.java) ?: run {
            InAppLogger.err("MPService", "no data"); stopSelf(); return START_NOT_STICKY
        }
        try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mgr.getMediaProjection(code, data)
            // B97rev4: Android 14+ требует registerCallback ДО createVirtualDisplay
            p?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    InAppLogger.d("MPService", "projection stopped by system")
                    projection = null
                }
            }, Handler(Looper.getMainLooper()))
            projection = p
            InAppLogger.d("MPService", "ready=" + (projection != null))
        } catch (e: Exception) {
            InAppLogger.err("MPService", "getProjection: " + e.message)
            projection = null; stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { projection?.stop() } catch (_: Exception) {}
        projection = null; super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null

    private fun buildNotif(): Notification =
        NotificationCompat.Builder(this, NotificationHelper.CHANNEL_GENERAL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Atom: скриншот активен")
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).build()
}

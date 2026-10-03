package com.example.device

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * MediaProjection capture. Starts ONLY after the user accepts the system consent dialog
 * (MainActivity forwards the result via [onConsentResult]). Used as fallback when
 * AccessibilityService.takeScreenshot is unavailable (API < 30).
 */
class ScreenCaptureService : Service() {
  private var projection: MediaProjection? = null
  private var reader: ImageReader? = null
  private var display: VirtualDisplay? = null
  private val main = Handler(Looper.getMainLooper())

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) { teardown(); stopSelf(); return START_NOT_STICKY }
    startForegroundCompat()
    val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
    @Suppress("DEPRECATION") val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
    if (code != Activity.RESULT_OK || data == null) { _active.value = false; stopSelf(); return START_NOT_STICKY }
    val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    projection = mpm.getMediaProjection(code, data).also { p ->
      p.registerCallback(object : MediaProjection.Callback() { override fun onStop() { teardown() } }, main)
    }
    setupDisplay()
    _active.value = true
    instance = this
    return START_NOT_STICKY
  }

  private fun setupDisplay() {
    val dm = DisplayMetrics()
    @Suppress("DEPRECATION") (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
    reader?.close(); display?.release()
    reader = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 2)
    display = projection?.createVirtualDisplay("jarvis-capture", dm.widthPixels, dm.heightPixels, dm.densityDpi,
      DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, main)
  }

  /** Grab one frame; recreates the display if orientation changed. */
  fun grab(): Bitmap? {
    val dm = DisplayMetrics()
    @Suppress("DEPRECATION") (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
    val r = reader ?: return null
    if (r.width != dm.widthPixels || r.height != dm.heightPixels) { setupDisplay(); Thread.sleep(250) }
    val img = reader?.acquireLatestImage() ?: return null
    return try {
      val plane = img.planes[0]
      val rowPadding = plane.rowStride - plane.pixelStride * img.width
      val bmp = Bitmap.createBitmap(img.width + rowPadding / plane.pixelStride, img.height, Bitmap.Config.ARGB_8888)
      bmp.copyPixelsFromBuffer(plane.buffer)
      Bitmap.createBitmap(bmp, 0, 0, img.width, img.height).also { if (it !== bmp) bmp.recycle() }
    } finally { img.close() }
  }

  private fun teardown() {
    display?.release(); display = null
    reader?.close(); reader = null
    projection?.stop(); projection = null
    _active.value = false; instance = null
  }

  override fun onDestroy() { teardown(); super.onDestroy() }

  private fun startForegroundCompat() {
    val nm = getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("capture", "Screen capture", NotificationManager.IMPORTANCE_LOW))
    val n: Notification = Notification.Builder(this, "capture").setContentTitle("JARVIS screen capture active")
      .setContentText("Screen sirf aapke command par padhi ja rahi hai").setSmallIcon(android.R.drawable.ic_menu_camera).setOngoing(true).build()
    if (Build.VERSION.SDK_INT >= 29) startForeground(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(42, n)
  }

  companion object {
    const val EXTRA_CODE = "code"; const val EXTRA_DATA = "data"; const val ACTION_STOP = "stop"
    @Volatile var instance: ScreenCaptureService? = null
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun consentIntent(ctx: Context): Intent =
      (ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent()

    /** Call with the Activity result of [consentIntent]. No capture starts if the user declined. */
    fun onConsentResult(ctx: Context, resultCode: Int, data: Intent?) {
      if (resultCode != Activity.RESULT_OK || data == null) return
      val i = Intent(ctx, ScreenCaptureService::class.java).putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data)
      ctx.startForegroundService(i)
    }
    fun stop(ctx: Context) { ctx.startService(Intent(ctx, ScreenCaptureService::class.java).setAction(ACTION_STOP)) }

    suspend fun capture(): Bitmap? {
      val d = CompletableDeferred<Bitmap?>()
      Thread { d.complete(instance?.grab()) }.start()
      return withTimeoutOrNull(3000) { d.await() }
    }
  }
}

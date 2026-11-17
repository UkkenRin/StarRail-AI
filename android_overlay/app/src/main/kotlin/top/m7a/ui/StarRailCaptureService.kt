package top.m7a.ui

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import kotlin.math.min

/**
 * User-authorized system display capture. This VirtualDisplay is a projection
 * render target, NOT the separate 1280x720 game display created by upstream M7A.
 *
 * No images leave process memory; no network, OCR, model calls or touch input.
 * Session tokens are not persisted and are consumed only once.
 */
class StarRailCaptureService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var worker: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    @Volatile private var closing = false
    private var lastSampleMs = 0L
    private var sessionId = 0L
    @Volatile private var processedFrames = 0L
    private var capturedContentSize: CaptureDimensions? = null
    private val foregroundProbe by lazy { AndroidForegroundProbe(applicationContext) }
    private val changes = FrameChangeDetector()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            endSession("已手动停止屏幕观察")
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || closing) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        @Suppress("DEPRECATION")
        val grant = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_GRANT, Intent::class.java)
        else intent.getParcelableExtra(EXTRA_GRANT)

        if (intent.getIntExtra(EXTRA_RESULT, Activity.RESULT_CANCELED) != Activity.RESULT_OK || grant == null) {
            LocalCaptureFrames.notice("没有有效的系统录屏授权")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            // Android 14+ requires a mediaProjection FGS BEFORE getMediaProjection.
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

            worker = HandlerThread("StarRailLocalFrames").also { it.start() }
            workerHandler = Handler(checkNotNull(worker).looper)

            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val active = manager.getMediaProjection(Activity.RESULT_OK, grant)
                ?: error("系统未返回屏幕共享会话")
            projection = active
            sessionId = LocalCaptureFrames.starting()
            CaptureSessionHealth.started(applicationContext)
            changes.reset()
            lastSampleMs = 0L
            processedFrames = 0L

            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    endSession("系统已结束屏幕共享（可能因锁屏或撤销授权）", projectionAlreadyStopped = true)
                    stopSelf()
                }

                override fun onCapturedContentResize(width: Int, height: Int) {
                    if (width > 0 && height > 0) {
                        capturedContentSize = CaptureDimensions(width, height)
                        resizeCapture(width, height)
                    }
                }

                override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                    LocalCaptureFrames.updateVisibility(sessionId, isVisible)
                }
            }
            projectionCallback = callback
            active.registerCallback(callback, mainHandler)

            val initial = capturedContentSize ?: screenDimensions()
            val size = captureDimensions(initial.width, initial.height)
            val nextReader = newReader(size)
            reader = nextReader
            virtualDisplay = active.createVirtualDisplay(
                "StarRail-AI-Local-Capture",
                size.width, size.height, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                nextReader.surface,
                null, mainHandler,
            )
        } catch (error: Exception) {
            endSession("屏幕观察启动失败：" + (error.message ?: error.javaClass.simpleName).take(160))
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun screenDimensions(): CaptureDimensions {
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).maximumWindowMetrics.bounds
            return CaptureDimensions(bounds.width(), bounds.height())
        }
        val metrics = resources.displayMetrics
        return CaptureDimensions(metrics.widthPixels, metrics.heightPixels)
    }

    /** Resize the same projection session rather than requesting a forbidden second display. */
    private fun resizeCapture(width: Int, height: Int) {
        if (closing || width <= 0 || height <= 0) return
        val display = virtualDisplay ?: return
        val size = captureDimensions(width, height)
        val previous = reader
        if (previous != null && previous.width == size.width && previous.height == size.height) return
        runCatching {
            val replacement = newReader(size)
            display.resize(size.width, size.height, resources.displayMetrics.densityDpi)
            display.setSurface(replacement.surface)
            reader = replacement
            previous?.setOnImageAvailableListener(null, null)
            previous?.close()
        }.onFailure {
            endSession("屏幕尺寸变更后无法继续采集")
            stopSelf()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Before Android 14 there is no captured-content resize callback.
        if (Build.VERSION.SDK_INT < 34) {
            val size = screenDimensions()
            resizeCapture(size.width, size.height)
        }
    }

    private fun newReader(size: CaptureDimensions): ImageReader =
        ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 2).also { output ->
            output.setOnImageAvailableListener({ source ->
                val acquired = runCatching { source.acquireLatestImage() }
                if (acquired.isFailure) {
                    val kind = acquired.exceptionOrNull()?.javaClass?.simpleName ?: "Unknown"
                    mainHandler.post {
                        if (!closing) {
                            endSession("系统图像读取异常（" + kind + "）")
                            stopSelf()
                        }
                    }
                    return@setOnImageAvailableListener
                }
                val image = acquired.getOrNull() ?: return@setOnImageAvailableListener
                try {
                    if (closing) return@setOnImageAvailableListener
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastSampleMs < 1000L) return@setOnImageAvailableListener
                    lastSampleMs = now
                    val preview = downsample(image) ?: return@setOnImageAvailableListener
                    val samples = IntArray(64) { n ->
                        val pixel = preview.getPixel(
                            n % 8 * preview.width / 8,
                            n / 8 * preview.height / 8,
                        )
                        (Color.red(pixel) * 299 + Color.green(pixel) * 587 +
                            Color.blue(pixel) * 114) / 1000
                    }
                    val changed = changes.changed(samples, image.width, image.height)
                    LocalCaptureFrames.showFrame(
                        sessionId, preview, image.width, image.height, changed,
                        foregroundProbe.sample(),
                    )
                    processedFrames += 1L
                    CaptureSessionHealth.frame(applicationContext, processedFrames)
                } catch (failure: Exception) {
                    // Unhandled ImageReader callbacks can kill the entire App
                    // process; report a sanitized, terminal state instead.
                    mainHandler.post {
                        if (!closing) {
                            endSession("读取屏幕帧失败（" + failure.javaClass.simpleName + "）")
                            stopSelf()
                        }
                    }
                } finally {
                    runCatching { image.close() }
                }
            }, workerHandler)
        }

    /** Downsample RGBA_8888 off the UI thread; retain a small bitmap, never a raw screen dump. */
    private fun downsample(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        if (plane.pixelStride < 4 || image.width <= 0 || image.height <= 0) return null
        val out = captureDimensions(image.width, image.height, 640)
        val source = plane.buffer
        val maxIndex = source.limit()
        val rgba = IntArray(out.width * out.height)
        var offset = 0
        for (y in 0 until out.height) {
            val sourceY = min(image.height - 1, y * image.height / out.height)
            for (x in 0 until out.width) {
                val sourceX = min(image.width - 1, x * image.width / out.width)
                val start = sourceY * plane.rowStride + sourceX * plane.pixelStride
                if (start < 0 || start + 2 >= maxIndex) return null
                val red = source.get(start).toInt() and 255
                val green = source.get(start + 1).toInt() and 255
                val blue = source.get(start + 2).toInt() and 255
                rgba[offset++] = (255 shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        return Bitmap.createBitmap(rgba, out.width, out.height, Bitmap.Config.ARGB_8888)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "StarRail AI 本地画面观察", NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "用户授权的屏幕采集，可在通知中停止" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(): Notification {
        val stopIntent = Intent(this, StarRailCaptureService::class.java).setAction(ACTION_STOP)
        val stop = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("StarRail AI · 本地画面观察")
            .setContentText("画面仅存本机内存，尚未发送给 AI")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "停止观察", stop,
            ).build())
            .build()
    }

    /**
     * The OS may already have invalidated MediaProjection before invoking
     * Callback.onStop(). Cleanup must not throw into the main thread and
     * accidentally crash the whole app. One stop, no token reuse.
     */
    private fun endSession(reason: String, projectionAlreadyStopped: Boolean = false) {
        if (closing) return
        closing = true
        val closedReader = reader.also { reader = null }
        runCatching { closedReader?.setOnImageAvailableListener(null, null) }
        val closedDisplay = virtualDisplay.also { virtualDisplay = null }
        runCatching { closedDisplay?.release() }
        runCatching { closedReader?.close() }
        val oldProjection = projection.also { projection = null }
        val oldCallback = projectionCallback.also { projectionCallback = null }
        runCatching { oldCallback?.let { oldProjection?.unregisterCallback(it) } }
        if (!projectionAlreadyStopped) runCatching { oldProjection?.stop() }
        val stoppedWorker = worker.also { worker = null }
        runCatching { stoppedWorker?.quitSafely() }
        workerHandler = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        if (sessionId > 0L) LocalCaptureFrames.stop(sessionId, reason)
        else LocalCaptureFrames.notice(reason)
        runCatching {
            CaptureSessionHealth.finished(applicationContext, reason, processedFrames)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        endSession("应用任务已关闭，屏幕观察停止")
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        endSession("本地屏幕观察已结束")
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "top.starrailai.capture.START"
        const val ACTION_STOP = "top.starrailai.capture.STOP"
        const val EXTRA_RESULT = "starrail_projection_result_code"
        const val EXTRA_GRANT = "starrail_projection_grant"
        private const val CHANNEL_ID = "starrail_local_capture"
        private const val NOTIFICATION_ID = 7814
    }
}

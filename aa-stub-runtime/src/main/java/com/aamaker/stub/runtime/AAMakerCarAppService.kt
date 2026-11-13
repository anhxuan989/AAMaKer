package com.aamaker.stub.runtime

import android.app.Activity
import android.app.ActivityOptions
import android.app.Application
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.Presentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

class AAMakerCarAppServiceImpl : androidx.car.app.CarAppService() {

    init {
        initStaticAppInfo()
    }

    override fun onCreate() {
        super.onCreate()
        initStaticAppInfo()
    }

    private fun initStaticAppInfo() {
        try {
            val appInfo = androidx.car.app.AppInfo(1, 6, "1.4.0")
            val field = androidx.car.app.CarAppService::class.java.getDeclaredField("mAppInfo")
            field.isAccessible = true
            field.set(this, appInfo)
        } catch (_: Throwable) {
        }
    }

    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return AAMakerCarSession()
    }
}

class AAMakerCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        return AAMakerProjectionScreen(carContext)
    }
}

class AAMakerProjectionScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

    private var virtualDisplay: VirtualDisplay? = null
    private var virtualDisplayImageReader: ImageReader? = null
    private var carOutputSurface: Surface? = null
    private val renderBuffers = arrayOfNulls<Bitmap>(2)
    private val renderCanvases = arrayOfNulls<SafeSoftwareCanvas>(2)
    private var activeBufferIdx: Int = 0
    private val isSurfacePostInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private var surfacePostThread: HandlerThread? = null
    private var surfacePostHandler: Handler? = null
    private var lastForcedLockedLayoutMs: Long = 0L
    private var lastSpecialViewSampleMs: Long = 0L
    private var lastAutoRetryLaunchMs: Long = 0L
    private var autoRetryLaunchCount: Int = 0
    private var lastLaunchAttemptMs: Long = 0L
    private var lastUserInteractionMs: Long = 0L
    private var surfaceAvailableTimestampMs: Long = 0L
    private var presentation: AppMirrorPresentation? = null
    private var isSurfaceReady: Boolean = false
    private var isProjectionMode: Boolean = true
    private var hasAutoLaunched: Boolean = false
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0
    private var surfaceDpi: Int = 160

    private var isScrollDragActive: Boolean = false
    private var scrollDownTime: Long = 0L
    private var scrollCurX: Float = 0f
    private var scrollCurY: Float = 0f
    private var scrollDisplayId: Int = Display.DEFAULT_DISPLAY

    private val mainHandler = Handler(Looper.getMainLooper())
    private val inputExecutor = Executors.newSingleThreadExecutor()
    private var persistentSuProcess: Process? = null
    private var persistentSuWriter: OutputStreamWriter? = null
    private var cachedOpenAppIcon: CarIcon? = null
    private var cachedOpenAppBitmap: Bitmap? = null
    private val attachedFabTag = "aamaker_bottom_left_open_fab"
    private var projectionWakeLock: PowerManager.WakeLock? = null
    private var cachedPowerManager: PowerManager? = null
    private var cachedKeyguardManager: KeyguardManager? = null
    private var lifecycleCallbacksRegistered: Boolean = false
    private var screenLockReceiverRegistered: Boolean = false
    private var isLockScreenMirrorTickerActive: Boolean = false
    private val appliedLockFlagsActivities = java.util.WeakHashMap<Activity, Boolean>()

    private var cachedWmgInstance: Any? = null
    private var cachedWmgViewsField: java.lang.reflect.Field? = null
    private var cachedWmgRootsField: java.lang.reflect.Field? = null
    private var cachedActivityThread: Any? = null
    private var cachedActivitiesField: java.lang.reflect.Field? = null
    private var reflectionInitialized: Boolean = false

    @Suppress("PrivateApi", "DiscouragedPrivateApi")
    private fun ensureReflectionInitialized() {
        if (reflectionInitialized) return
        reflectionInitialized = true
        try {
            val wmgClass = Class.forName("android.view.WindowManagerGlobal")
            cachedWmgInstance = wmgClass.getMethod("getInstance").invoke(null)
            cachedWmgViewsField = wmgClass.getDeclaredField("mViews").apply { isAccessible = true }
            cachedWmgRootsField = wmgClass.getDeclaredField("mRoots").apply { isAccessible = true }
        } catch (_: Throwable) {
        }
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            cachedActivityThread = atClass.getMethod("currentActivityThread").invoke(null)
            cachedActivitiesField = atClass.getDeclaredField("mActivities").apply { isAccessible = true }
        } catch (_: Throwable) {
        }
    }

    private fun postMainAsync(runnable: Runnable) {
        try {
            val msg = Message.obtain(mainHandler, runnable)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                msg.isAsynchronous = true
            }
            mainHandler.sendMessage(msg)
        } catch (_: Throwable) {
            mainHandler.post(runnable)
        }
    }

    private fun postMainDelayedAsync(runnable: Runnable, delayMillis: Long) {
        try {
            val msg = Message.obtain(mainHandler, runnable)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                msg.isAsynchronous = true
            }
            mainHandler.sendMessageDelayed(msg, delayMillis)
        } catch (_: Throwable) {
            mainHandler.postDelayed(runnable, delayMillis)
        }
    }

    private fun getOrCreateSurfacePostHandler(): Handler {
        surfacePostHandler?.let { return it }
        val thread = HandlerThread("AAMakerSurfacePoster", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        val handler = Handler(thread.looper)
        surfacePostThread = thread
        surfacePostHandler = handler
        return handler
    }

    private fun markUserInteraction() {
        lastUserInteractionMs = SystemClock.uptimeMillis()
    }

    private val lockScreenMirrorTicker = object : Runnable {
        override fun run() {
            if (!isSurfaceReady) {
                isLockScreenMirrorTickerActive = false
                return
            }
            var drawElapsedMs = 0L
            val now = SystemClock.uptimeMillis()
            val screenLocked = isPhoneScreenLockedOrOff()
            val isInteractionBoosted = (now - lastUserInteractionMs) < 3500L || isScrollDragActive
            val vDisplay = virtualDisplay?.display
            val vDisplayId = vDisplay?.displayId ?: Display.DEFAULT_DISPLAY
            if (vDisplay != null && vDisplayId != Display.DEFAULT_DISPLAY) {
                try {
                    if (screenLocked) {
                        unstopAllWindowRoots(invokeRelayout = false)
                    }
                    if (!isSurfacePostInFlight.get()) {
                        val t0 = SystemClock.uptimeMillis()
                        renderFrameToCarSurfaceNow(vDisplayId, screenLocked, isInteractionBoosted)
                        drawElapsedMs = (SystemClock.uptimeMillis() - t0).coerceAtLeast(0L)
                    }
                } catch (_: Throwable) {
                }
            }
            if (isSurfaceReady) {
                val nextDelayMs = when {
                    isInteractionBoosted -> (22L + drawElapsedMs / 2L).coerceIn(22L, 50L)
                    screenLocked -> (33L + drawElapsedMs / 2L).coerceIn(33L, 65L)
                    else -> (45L + drawElapsedMs).coerceIn(45L, 120L)
                }
                postMainDelayedAsync(this, nextDelayMs)
            } else {
                isLockScreenMirrorTickerActive = false
            }
        }
    }

    private fun requestCoalescedFrameRender() {
        if (!isSurfaceReady) return
        val vDisplayId = virtualDisplay?.display?.displayId ?: return
        if (vDisplayId == Display.DEFAULT_DISPLAY) return
        mainHandler.removeCallbacks(lockScreenMirrorTicker)
        postMainAsync(lockScreenMirrorTicker)
    }

    private fun isPhoneScreenLockedOrOff(): Boolean {
        return try {
            val appCtx = carContext.applicationContext
            val pm = cachedPowerManager
                ?: (appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.also { cachedPowerManager = it }
            val km = cachedKeyguardManager
                ?: (appCtx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.also { cachedKeyguardManager = it }
            val interactive = pm?.isInteractive ?: true
            val keyguardLocked = km?.isKeyguardLocked ?: false
            !interactive || keyguardLocked
        } catch (_: Throwable) {
            false
        }
    }

    private fun ensurePresentationShowing(vDisplay: Display) {
        try {
            if (presentation == null || presentation?.isShowing != true) {
                presentation?.dismiss()
                presentation = AppMirrorPresentation(carContext, vDisplay).also { it.show() }
            }
        } catch (_: Throwable) {
        }
    }

    private fun startLockScreenMirrorTicker() {
        if (!isLockScreenMirrorTickerActive) {
            isLockScreenMirrorTickerActive = true
            mainHandler.removeCallbacks(lockScreenMirrorTicker)
            postMainAsync(lockScreenMirrorTicker)
        }
    }

    private fun stopLockScreenMirrorTicker() {
        isLockScreenMirrorTickerActive = false
        mainHandler.removeCallbacks(lockScreenMirrorTicker)
    }

    private fun unstopAllWindowRoots(invokeRelayout: Boolean = false) {
        try {
            ensureReflectionInitialized()
            val wmgInstance = cachedWmgInstance ?: return
            if (invokeRelayout) {
                try {
                    val setStoppedMethod = wmgInstance.javaClass.getDeclaredMethod(
                        "setStoppedState",
                        android.os.IBinder::class.java,
                        Boolean::class.javaPrimitiveType
                    ).apply { isAccessible = true }
                    setStoppedMethod.invoke(wmgInstance, null, false)
                } catch (_: Throwable) {
                }
            }
            val rawRoots = cachedWmgRootsField?.get(wmgInstance)
            if (rawRoots is List<*>) {
                for (root in rawRoots) {
                    if (root == null) continue
                    val rootClass = root.javaClass
                    try {
                        val stoppedField = rootClass.getDeclaredField("mStopped").apply { isAccessible = true }
                        if (stoppedField.getBoolean(root)) {
                            stoppedField.setBoolean(root, false)
                            if (invokeRelayout) {
                                try {
                                    val setWinStopped = rootClass.getDeclaredMethod(
                                        "setWindowStopped",
                                        Boolean::class.javaPrimitiveType
                                    ).apply { isAccessible = true }
                                    setWinStopped.invoke(root, false)
                                } catch (_: Throwable) {
                                }
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun renderFrameToCarSurfaceNow(
        displayId: Int,
        screenLocked: Boolean = isPhoneScreenLockedOrOff(),
        isInteractionBoosted: Boolean = false
    ) {
        if (isSurfacePostInFlight.get()) return
        val outSurface = carOutputSurface ?: return
        if (!outSurface.isValid) return
        val targetW = surfaceWidth.takeIf { it > 0 } ?: return
        val targetH = surfaceHeight.takeIf { it > 0 } ?: return

        val candidateDecors = findAnyActivityDecorViewsInProcess(displayId)
        if (candidateDecors.isEmpty()) {
            val now = SystemClock.uptimeMillis()
            if (now - surfaceAvailableTimestampMs > 1800L &&
                now - lastAutoRetryLaunchMs > 2800L &&
                autoRetryLaunchCount < 2
            ) {
                lastAutoRetryLaunchMs = now
                autoRetryLaunchCount++
                launchOriginalAppActivity(forceCarDisplay = true)
            }
            renderLoadingBannerOnSurface(outSurface, targetW, targetH)
            return
        }

        try {
            val bufIdx = activeBufferIdx
            var bmp = renderBuffers[bufIdx]
            var safeCanvas = renderCanvases[bufIdx]
            if (bmp == null || bmp.isRecycled || bmp.width != targetW || bmp.height != targetH || safeCanvas == null) {
                bmp?.recycle()
                bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                safeCanvas = SafeSoftwareCanvas(bmp)
                renderBuffers[bufIdx] = bmp
                renderCanvases[bufIdx] = safeCanvas
            }

            safeCanvas.drawColor(Color.parseColor("#10141C"))

            val now = SystemClock.uptimeMillis()

            val layoutMinInterval = if (isInteractionBoosted) 20L else 120L
            val allowLockedLayout = screenLocked && (now - lastForcedLockedLayoutMs >= layoutMinInterval)
            var didLockedLayout = false

            val orderedDecors = candidateDecors.asReversed()
            val loc = IntArray(2)
            for ((index, decor) in orderedDecors.withIndex()) {
                try {
                    if (decor.visibility != View.VISIBLE) {
                        decor.visibility = View.VISIBLE
                    }
                    val lp = decor.layoutParams
                    val isFullScreenWindow = index == 0 ||
                        lp == null ||
                        (lp.width == ViewGroup.LayoutParams.MATCH_PARENT && lp.height == ViewGroup.LayoutParams.MATCH_PARENT)

                    if (decor.width <= 0 || decor.height <= 0 || (allowLockedLayout && decor.isLayoutRequested)) {
                        didLockedLayout = true
                        if (isFullScreenWindow) {
                            decor.measure(
                                View.MeasureSpec.makeMeasureSpec(targetW, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(targetH, View.MeasureSpec.EXACTLY)
                            )
                            decor.layout(0, 0, targetW, targetH)
                        } else {
                            val wSpec = if (lp.width > 0) {
                                View.MeasureSpec.makeMeasureSpec(lp.width, View.MeasureSpec.EXACTLY)
                            } else {
                                View.MeasureSpec.makeMeasureSpec(targetW, View.MeasureSpec.AT_MOST)
                            }
                            val hSpec = if (lp.height > 0) {
                                View.MeasureSpec.makeMeasureSpec(lp.height, View.MeasureSpec.EXACTLY)
                            } else {
                                View.MeasureSpec.makeMeasureSpec(targetH, View.MeasureSpec.AT_MOST)
                            }
                            decor.measure(wSpec, hSpec)
                            decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
                        }
                    }

                    val dw = decor.width.takeIf { it > 0 } ?: targetW
                    val dh = decor.height.takeIf { it > 0 } ?: targetH
                    val saveCount = safeCanvas.save()
                    var winOffsetX = 0f
                    var winOffsetY = 0f
                    if (isFullScreenWindow) {
                        if (dw != targetW || dh != targetH) {
                            safeCanvas.scale(targetW.toFloat() / dw.toFloat(), targetH.toFloat() / dh.toFloat())
                        }
                    } else {
                        decor.getLocationOnScreen(loc)
                        winOffsetX = loc[0].toFloat().coerceIn(0f, (targetW - dw).coerceAtLeast(0).toFloat())
                        winOffsetY = loc[1].toFloat().coerceIn(0f, (targetH - dh).coerceAtLeast(0).toFloat())
                        safeCanvas.translate(winOffsetX, winOffsetY)
                    }

                    if (now - lastSpecialViewSampleMs >= 200L) {
                        lastSpecialViewSampleMs = now
                        sampleSpecialViewsThrottled(decor)
                    }
                    safeCanvas.activeSurfaceViewFallback = latestSampledSurfaceBitmap
                    decor.draw(safeCanvas)
                    drawSampledTextureViewsIfNeeded(decor, safeCanvas)
                    safeCanvas.restoreToCount(saveCount)
                } catch (_: Throwable) {
                }
            }
            if (didLockedLayout) {
                lastForcedLockedLayoutMs = now
            }

            drawBottomLeftOpenAppButtonOnCanvas(safeCanvas, targetW, targetH)

            activeBufferIdx = 1 - bufIdx
            val bitmapToPost = bmp
            if (isSurfacePostInFlight.compareAndSet(false, true)) {
                getOrCreateSurfacePostHandler().post {
                    try {
                        val currentOut = carOutputSurface
                        if (currentOut != null && currentOut.isValid && !bitmapToPost.isRecycled) {
                            val surfaceCanvas = currentOut.lockCanvas(null)
                            try {
                                surfaceCanvas.drawBitmap(bitmapToPost, 0f, 0f, null)
                            } finally {
                                currentOut.unlockCanvasAndPost(surfaceCanvas)
                            }
                        }
                    } catch (_: Throwable) {
                    } finally {
                        isSurfacePostInFlight.set(false)
                    }
                }
            }
        } catch (_: Throwable) {
            isSurfacePostInFlight.set(false)
        }
    }

    private var latestSampledSurfaceBitmap: Bitmap? = null
    private val textureViewSampleCache = java.util.WeakHashMap<TextureView, Bitmap>()
    private val surfaceViewBitmapCache = java.util.WeakHashMap<SurfaceView, Bitmap>()
    private val surfaceViewCopyInFlight = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<SurfaceView, Boolean>()
    )

    private fun sampleSpecialViewsThrottled(view: View) {
        if (view.visibility != View.VISIBLE) return
        if (view is SurfaceView) {
            requestSurfaceViewPixelCopyIfNeeded(view)
            return
        }
        if (view is TextureView) {
            if (view.isAvailable && view.width > 0 && view.height > 0) {
                try {
                    val sampleW = (view.width / 2).coerceIn(160, 640)
                    val sampleH = (view.height / 2).coerceIn(90, 360)
                    var cached = textureViewSampleCache[view]
                    if (cached == null || cached.isRecycled || cached.width != sampleW || cached.height != sampleH) {
                        cached?.recycle()
                        cached = Bitmap.createBitmap(sampleW, sampleH, Bitmap.Config.ARGB_8888)
                        textureViewSampleCache[view] = cached
                    }
                    if (cached != null) {
                        view.getBitmap(cached)
                    }
                } catch (_: Throwable) {
                }
            }
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i) ?: continue
                sampleSpecialViewsThrottled(child)
            }
        }
    }

    private fun drawSampledTextureViewsIfNeeded(decor: View, canvas: Canvas) {
        if (textureViewSampleCache.isEmpty()) return
        val decorLoc = IntArray(2)
        val viewLoc = IntArray(2)
        decor.getLocationOnScreen(decorLoc)
        for ((texView, bmp) in textureViewSampleCache.entries) {
            if (texView == null || bmp == null || bmp.isRecycled) continue
            if (!texView.isShown || texView.width <= 0 || texView.height <= 0) continue
            try {
                texView.getLocationOnScreen(viewLoc)
                val left = (viewLoc[0] - decorLoc[0]).toFloat()
                val top = (viewLoc[1] - decorLoc[1]).toFloat()
                val dst = RectF(left, top, left + texView.width, top + texView.height)
                canvas.drawBitmap(bmp, null, dst, null)
            } catch (_: Throwable) {
            }
        }
    }

    private fun requestSurfaceViewPixelCopyIfNeeded(surfaceView: SurfaceView) {
        val w = surfaceView.width
        val h = surfaceView.height
        if (w <= 0 || h <= 0) return
        val holderSurface = try {
            surfaceView.holder?.surface
        } catch (_: Throwable) {
            null
        } ?: return
        if (!holderSurface.isValid) return
        if (surfaceViewCopyInFlight.contains(surfaceView)) return

        val sampleW = (w / 2).coerceIn(160, 640)
        val sampleH = (h / 2).coerceIn(90, 360)
        var cached = surfaceViewBitmapCache[surfaceView]
        if (cached == null || cached.isRecycled || cached.width != sampleW || cached.height != sampleH) {
            cached?.recycle()
            cached = try {
                Bitmap.createBitmap(sampleW, sampleH, Bitmap.Config.ARGB_8888)
            } catch (_: Throwable) {
                null
            }
            if (cached != null) {
                surfaceViewBitmapCache[surfaceView] = cached
            }
        }
        val targetBmp = cached ?: return
        surfaceViewCopyInFlight.add(surfaceView)
        try {
            PixelCopy.request(
                holderSurface,
                targetBmp,
                { copyResult ->
                    surfaceViewCopyInFlight.remove(surfaceView)
                    if (copyResult == PixelCopy.SUCCESS) {
                        latestSampledSurfaceBitmap = targetBmp
                    }
                },
                getOrCreateSurfacePostHandler()
            )
        } catch (_: Throwable) {
            surfaceViewCopyInFlight.remove(surfaceView)
        }
    }

    private val fabBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B80F172A")
        style = Paint.Style.FILL
    }
    private val fabStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8038BDF8")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private fun drawBottomLeftOpenAppButtonOnCanvas(canvas: Canvas, width: Int, height: Int) {
        try {
            val density = (surfaceDpi.toFloat() / 160f).coerceIn(1f, 3f)
            val btnSize = 38f * density
            val margin = 14f * density
            val pad = 8f * density
            val left = margin
            val top = height - margin - btnSize
            val right = left + btnSize
            val bottom = top + btnSize
            val rect = RectF(left, top, right, bottom)
            canvas.drawOval(rect, fabBgPaint)
            fabStrokePaint.strokeWidth = (1.5f * density).coerceAtLeast(1f)
            canvas.drawOval(rect, fabStrokePaint)
            val iconBmp = getOrCreateOpenAppBitmap()
            val iconDst = RectF(left + pad, top + pad, right - pad, bottom - pad)
            canvas.drawBitmap(iconBmp, null, iconDst, null)
        } catch (_: Throwable) {
        }
    }

    private fun renderLoadingBannerOnSurface(surface: Surface, width: Int, height: Int) {
        if (! isSurfacePostInFlight.compareAndSet(false, true)) return
        val iconBmp = getOrCreateOpenAppBitmap()
        val appName = try {
            val info = carContext.packageManager.getApplicationInfo(carContext.packageName, 0)
            carContext.packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            carContext.packageName
        }
        val msg = when (currentLang()) {
            "vi" -> "🚗 Đang nạp giao diện $appName lên Android Auto..."
            else -> "🚗 Loading $appName UI onto Android Auto..."
        }
        getOrCreateSurfacePostHandler().post {
            try {
                if (!surface.isValid) return@post
                val canvas = surface.lockCanvas(null)
                try {
                    canvas.drawColor(Color.parseColor("#0F172A"))
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#38BDF8")
                        textSize = 26f
                        textAlign = Paint.Align.CENTER
                    }
                    canvas.drawText(msg, width / 2f, height / 2f, paint)
                    val density = (surfaceDpi.toFloat() / 160f).coerceIn(1f, 3f)
                    val btnSize = 38f * density
                    val margin = 14f * density
                    val pad = 8f * density
                    val left = margin
                    val top = height - margin - btnSize
                    val right = left + btnSize
                    val bottom = top + btnSize
                    val rect = RectF(left, top, right, bottom)
                    canvas.drawOval(rect, fabBgPaint)
                    fabStrokePaint.strokeWidth = (1.5f * density).coerceAtLeast(1f)
                    canvas.drawOval(rect, fabStrokePaint)
                    val iconDst = RectF(left + pad, top + pad, right - pad, bottom - pad)
                    canvas.drawBitmap(iconBmp, null, iconDst, null)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
            } catch (_: Throwable) {
            } finally {
                isSurfacePostInFlight.set(false)
            }
        }
    }

    private val lockScreenActivityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
            applyLockScreenFlagsToActivity(activity, forceApply = true)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            applyLockScreenFlagsToActivity(activity, forceApply = true)
        }

        override fun onActivityStarted(activity: Activity) {
            applyLockScreenFlagsToActivity(activity, forceApply = false)
        }

        override fun onActivityResumed(activity: Activity) {
            applyLockScreenFlagsToActivity(activity, forceApply = false)
            requestCoalescedFrameRender()
        }

        override fun onActivityPaused(activity: Activity) {}

        override fun onActivityStopped(activity: Activity) {
            val vDisplayId = virtualDisplay?.display?.displayId ?: Display.DEFAULT_DISPLAY
            if (isSurfaceReady && vDisplayId != Display.DEFAULT_DISPLAY && isPhoneScreenLockedOrOff()) {
                postMainAsync {
                    unstopAllWindowRoots(invokeRelayout = false)
                    forceResumeActivityRecord(activity)
                    requestCoalescedFrameRender()
                }
            }
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {
            appliedLockFlagsActivities.remove(activity)
        }
    }

    private val screenLockStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == Intent.ACTION_SCREEN_OFF ||
                action == Intent.ACTION_USER_PRESENT ||
                action == Intent.ACTION_SCREEN_ON
            ) {
                val vDisplay = virtualDisplay?.display ?: return
                val vDisplayId = vDisplay.displayId
                if (!isSurfaceReady || vDisplayId == Display.DEFAULT_DISPLAY) return
                acquireProjectionWakeLock()
                unstopAllWindowRoots(invokeRelayout = true)
                applyLockScreenFlagsToAllProcessActivities(vDisplayId)
                forceResumeAllProcessActivitiesOnDisplay(vDisplayId)
                startLockScreenMirrorTicker()
                requestCoalescedFrameRender()

                postMainDelayedAsync({
                    if (isSurfaceReady) {
                        unstopAllWindowRoots(invokeRelayout = false)
                        forceResumeAllProcessActivitiesOnDisplay(vDisplayId)
                        val candidates = findAnyActivityDecorViewsInProcess(vDisplayId)
                        if (candidates.isEmpty()) {
                            launchOriginalAppActivity(forceCarDisplay = true)
                        } else {
                            requestCoalescedFrameRender()
                        }
                    }
                }, 220L)
                postMainDelayedAsync({
                    if (isSurfaceReady) {
                        unstopAllWindowRoots(invokeRelayout = false)
                        forceResumeAllProcessActivitiesOnDisplay(vDisplayId)
                        requestCoalescedFrameRender()
                    }
                }, 650L)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun applyLockScreenFlagsToActivity(activity: Activity, forceApply: Boolean = false) {
        try {
            if (activity.isFinishing || activity.isDestroyed) return
            if (!forceApply && appliedLockFlagsActivities[activity] == true) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                activity.setShowWhenLocked(true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.setInheritShowWhenLocked(true)
            }
            val win = activity.window
            if (win != null) {
                win.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                )
                appliedLockFlagsActivities[activity] = true
            }
        } catch (_: Throwable) {
        }
    }

    private fun forceResumeActivityRecord(activity: Activity) {
        try {
            if (activity.isFinishing || activity.isDestroyed) return
            applyLockScreenFlagsToActivity(activity, forceApply = false)
            val decor = activity.window?.decorView
            if (decor != null && decor.visibility != View.VISIBLE) {
                decor.visibility = View.VISIBLE
            }
            try {
                val mStoppedField = Activity::class.java.getDeclaredField("mStopped").apply { isAccessible = true }
                if (mStoppedField.getBoolean(activity)) {
                    mStoppedField.setBoolean(activity, false)
                }
            } catch (_: Throwable) {
            }

            val atClass = Class.forName("android.app.ActivityThread")
            val currentAt = atClass.getMethod("currentActivityThread").invoke(null)
            val activitiesField = atClass.getDeclaredField("mActivities").apply { isAccessible = true }
            val activitiesMap = activitiesField.get(currentAt) as? Map<*, *> ?: return
            for (record in activitiesMap.values) {
                if (record == null) continue
                val actField = record.javaClass.getDeclaredField("activity").apply { isAccessible = true }
                val act = actField.get(record) as? Activity ?: continue
                if (act === activity) {
                    try {
                        val stoppedField = record.javaClass.getDeclaredField("stopped").apply { isAccessible = true }
                        stoppedField.setBoolean(record, false)
                    } catch (_: Throwable) {
                    }
                    try {
                        val hideForNowField = record.javaClass.getDeclaredField("hideForNow").apply { isAccessible = true }
                        hideForNowField.setBoolean(record, false)
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun forceResumeAllProcessActivitiesOnDisplay(targetDisplayId: Int) {
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAt = atClass.getMethod("currentActivityThread").invoke(null)
            val activitiesField = atClass.getDeclaredField("mActivities").apply { isAccessible = true }
            val activitiesMap = activitiesField.get(currentAt) as? Map<*, *> ?: return
            for (record in activitiesMap.values) {
                if (record == null) continue
                val actField = record.javaClass.getDeclaredField("activity").apply { isAccessible = true }
                val act = actField.get(record) as? Activity ?: continue
                if (act.isFinishing || act.isDestroyed) continue
                if (act.javaClass.name.contains("CarAppPermissionActivity")) continue
                val actDisplayId = act.window?.decorView?.display?.displayId ?: act.display?.displayId
                if (actDisplayId == null || actDisplayId == targetDisplayId || actDisplayId != Display.DEFAULT_DISPLAY) {
                    forceResumeActivityRecord(act)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun applyLockScreenFlagsToAllProcessActivities(targetDisplayId: Int) {
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAt = atClass.getMethod("currentActivityThread").invoke(null)
            val activitiesField = atClass.getDeclaredField("mActivities").apply { isAccessible = true }
            val activitiesMap = activitiesField.get(currentAt) as? Map<*, *> ?: return
            for (record in activitiesMap.values) {
                if (record == null) continue
                val actField = record.javaClass.getDeclaredField("activity").apply { isAccessible = true }
                val act = actField.get(record) as? Activity ?: continue
                if (act.isFinishing || act.isDestroyed) continue
                val actDisplayId = act.window?.decorView?.display?.displayId ?: act.display?.displayId
                if (actDisplayId == null || actDisplayId == targetDisplayId || actDisplayId != Display.DEFAULT_DISPLAY) {
                    applyLockScreenFlagsToActivity(act, forceApply = false)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun registerLockScreenSupport(targetDisplayId: Int) {
        val app = carContext.applicationContext as? Application
        if (app != null && !lifecycleCallbacksRegistered) {
            try {
                app.registerActivityLifecycleCallbacks(lockScreenActivityLifecycleCallbacks)
                lifecycleCallbacksRegistered = true
            } catch (_: Throwable) {
            }
        }
        if (!screenLockReceiverRegistered) {
            try {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    carContext.applicationContext.registerReceiver(
                        screenLockStateReceiver,
                        filter,
                        Context.RECEIVER_NOT_EXPORTED
                    )
                } else {
                    carContext.applicationContext.registerReceiver(screenLockStateReceiver, filter)
                }
                screenLockReceiverRegistered = true
            } catch (_: Throwable) {
            }
        }
        acquireProjectionWakeLock()
        applyLockScreenFlagsToAllProcessActivities(targetDisplayId)
        startLockScreenMirrorTicker()
    }

    private fun unregisterLockScreenSupport() {
        stopLockScreenMirrorTicker()
        val app = carContext.applicationContext as? Application
        if (app != null && lifecycleCallbacksRegistered) {
            try {
                app.unregisterActivityLifecycleCallbacks(lockScreenActivityLifecycleCallbacks)
            } catch (_: Throwable) {
            }
            lifecycleCallbacksRegistered = false
        }
        if (screenLockReceiverRegistered) {
            try {
                carContext.applicationContext.unregisterReceiver(screenLockStateReceiver)
            } catch (_: Throwable) {
            }
            screenLockReceiverRegistered = false
        }
        releaseProjectionWakeLock()
    }

    private fun acquireProjectionWakeLock() {
        try {
            val currentLock = projectionWakeLock
            if (currentLock != null && currentLock.isHeld) return
            val pm = carContext.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AAMaKer:CarProjectionWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            projectionWakeLock = wakeLock
        } catch (_: Throwable) {
        }
    }

    private fun releaseProjectionWakeLock() {
        try {
            projectionWakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Throwable) {
        } finally {
            projectionWakeLock = null
        }
    }

    private fun getOrCreateOpenAppBitmap(): Bitmap {
        cachedOpenAppBitmap?.let { return it }
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        val gap = 6f
        val pad = 12f
        val cell = (size - pad * 2f - gap) / 2f
        val radius = 5f
        canvas.drawRoundRect(RectF(pad, pad, pad + cell, pad + cell), radius, radius, paint)
        canvas.drawRoundRect(RectF(pad + cell + gap, pad, size - pad, pad + cell), radius, radius, paint)
        canvas.drawRoundRect(RectF(pad, pad + cell + gap, pad + cell, size - pad), radius, radius, paint)
        canvas.drawRoundRect(RectF(pad + cell + gap, pad + cell + gap, size - pad, size - pad), radius, radius, paint)
        cachedOpenAppBitmap = bmp
        return bmp
    }

    private fun getOrCreateOpenAppCarIcon(): CarIcon {
        cachedOpenAppIcon?.let { return it }
        return try {
            val bmp = getOrCreateOpenAppBitmap()
            CarIcon.Builder(IconCompat.createWithBitmap(bmp))
                .setTint(CarColor.DEFAULT)
                .build()
                .also { cachedOpenAppIcon = it }
        } catch (_: Throwable) {
            CarIcon.APP_ICON
        }
    }

    private fun ensureBottomLeftOpenAppOverlay(displayId: Int) {
        try {
            val decorViews = findTargetDecorViewsOnDisplay(displayId)
            val topDecor = decorViews.firstOrNull() as? FrameLayout
                ?: (presentation?.window?.decorView as? FrameLayout)
                ?: return

            val existing = topDecor.findViewWithTag<View>(attachedFabTag)
            if (existing != null) {
                existing.bringToFront()
                return
            }

            val ctx = topDecor.context
            val density = ctx.resources.displayMetrics.density.coerceAtLeast(1f)
            val btnSizePx = (38f * density).roundToInt()
            val marginPx = (14f * density).roundToInt()
            val padPx = (8f * density).roundToInt()

            val bgDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#B80F172A"))
                setStroke((1.5f * density).roundToInt().coerceAtLeast(1), Color.parseColor("#8038BDF8"))
            }

            val fabView = ImageView(ctx).apply {
                tag = attachedFabTag
                background = bgDrawable
                setImageBitmap(getOrCreateOpenAppBitmap())
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(padPx, padPx, padPx, padPx)
                elevation = 16f * density
                isClickable = true
                isFocusable = false
                setOnClickListener {
                    isProjectionMode = true
                    launchOriginalAppActivity(forceCarDisplay = true)
                    invalidate()
                }
            }

            val lp = FrameLayout.LayoutParams(btnSizePx, btnSizePx).apply {
                gravity = Gravity.BOTTOM or Gravity.LEFT
                leftMargin = marginPx
                bottomMargin = marginPx
            }
            topDecor.addView(fabView, lp)
            fabView.bringToFront()
        } catch (_: Throwable) {
        }
    }

    private fun isBottomLeftOpenAppHit(x: Float, y: Float): Boolean {
        val h = surfaceHeight.takeIf { it > 0 } ?: return false
        val w = surfaceWidth.takeIf { it > 0 } ?: return false

        val maxHitX = minOf(88f, w * 0.12f).coerceAtLeast(64f)
        val minHitY = h - minOf(88f, h * 0.18f).coerceAtLeast(64f)
        return x in 0f..maxHitX && y in minHitY..h.toFloat()
    }

    private fun currentLang(): String = java.util.Locale.getDefault().language.lowercase()

    private fun isRootShellAllowed(): Boolean {
        return try {
            val appCtx = carContext.applicationContext
            val ai = appCtx.packageManager.getApplicationInfo(
                appCtx.packageName,
                android.content.pm.PackageManager.GET_META_DATA
            )
            ai.metaData?.getBoolean("com.aamaker.USE_ROOT_SHELL", false) == true
        } catch (_: Throwable) {
            false
        }
    }

    init {
        try {
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        } catch (_: Throwable) {

        }
    }

    override fun onGetTemplate(): Template {
        val packageName = carContext.packageName
        val appLabel = try {
            val appInfo = carContext.packageManager.getApplicationInfo(packageName, 0)
            carContext.packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName
        }
        val lang = currentLang()

        val openAppTitle = when (lang) {
            "vi" -> "Mở App"
            "zh" -> "打开应用"
            "es" -> "Abrir App"
            "hi" -> "ऐप खोलें"
            "ar" -> "فتح التطبيق"
            "pt" -> "Abrir App"
            "ru" -> "Открыть"
            "ja" -> "アプリを開く"
            "de" -> "App öffnen"
            "fr" -> "Ouvrir l'App"
            else -> "Open App"
        }

        val projectionActionStrip = ActionStrip.Builder()
            .addAction(Action.PAN)
            .build()

        val mapActionStrip = ActionStrip.Builder()
            .addAction(Action.PAN)
            .build()

        if (isProjectionMode) {
            return NavigationTemplate.Builder()
                .setActionStrip(projectionActionStrip)
                .setMapActionStrip(mapActionStrip)
                .setPanModeListener { _ ->

                }
                .setBackgroundColor(CarColor.SECONDARY)
                .build()
        }

        val paneActionStrip = ActionStrip.Builder()
            .addAction(Action.PAN)
            .addAction(
                Action.Builder()
                    .setIcon(getOrCreateOpenAppCarIcon())
                    .setTitle(openAppTitle)
                    .setOnClickListener {
                        isProjectionMode = true
                        launchOriginalAppActivity(forceCarDisplay = true)
                        invalidate()
                    }
                    .build()
            )
            .build()

        val row1Title = when (lang) {
            "vi" -> "Ứng dụng: $appLabel"
            "zh" -> "应用: $appLabel"
            "es" -> "Aplicación: $appLabel"
            "hi" -> "ऐप: $appLabel"
            "ar" -> "التطبيق: $appLabel"
            "pt" -> "Aplicativo: $appLabel"
            "ru" -> "Приложение: $appLabel"
            "ja" -> "アプリ: $appLabel"
            "de" -> "App: $appLabel"
            "fr" -> "Application : $appLabel"
            else -> "App: $appLabel"
        }
        val row1Text = when (lang) {
            "vi" -> "Gói: $packageName (Đã mở khóa Android Auto)"
            "zh" -> "包名: $packageName (已解锁 Android Auto)"
            "es" -> "Paquete: $packageName (Android Auto desbloqueado)"
            "hi" -> "पैकेज: $packageName (Android Auto अनलॉक)"
            "ar" -> "الحزمة: $packageName (تم فتح Android Auto)"
            "pt" -> "Pacote: $packageName (Android Auto desbloqueado)"
            "ru" -> "Пакет: $packageName (Разблокировано для Android Auto)"
            "ja" -> "パッケージ: $packageName (Android Auto ロック解除済)"
            "de" -> "Paket: $packageName (Android Auto freigeschaltet)"
            "fr" -> "Package : $packageName (Débloqué pour Android Auto)"
            else -> "Package: $packageName (Android Auto Unlocked)"
        }

        val row1 = Row.Builder()
            .setTitle(row1Title)
            .addText(row1Text)
            .build()

        val row2Title = if (isSurfaceReady) {
            when (lang) {
                "vi" -> "Màn hình xe hơi: Đang trình chiếu (${surfaceWidth}x${surfaceHeight})"
                "zh" -> "车载屏幕: 正在投射 (${surfaceWidth}x${surfaceHeight})"
                "es" -> "Pantalla del coche: Proyectando (${surfaceWidth}x${surfaceHeight})"
                "hi" -> "कार स्क्रीन: प्रोजेक्ट हो रहा है (${surfaceWidth}x${surfaceHeight})"
                "ar" -> "شاشة السيارة: قيد العرض (${surfaceWidth}x${surfaceHeight})"
                "pt" -> "Tela do carro: Projetando (${surfaceWidth}x${surfaceHeight})"
                "ru" -> "Экран авто: Проекция (${surfaceWidth}x${surfaceHeight})"
                "ja" -> "車載画面: 投影中 (${surfaceWidth}x${surfaceHeight})"
                "de" -> "Fahrzeugdisplay: Projiziere (${surfaceWidth}x${surfaceHeight})"
                "fr" -> "Écran auto : Projection (${surfaceWidth}x${surfaceHeight})"
                else -> "Car Screen: Projecting (${surfaceWidth}x${surfaceHeight})"
            }
        } else {
            when (lang) {
                "vi" -> "Trạng thái: Sẵn sàng khởi chạy"
                "zh" -> "状态: 准备就绪"
                "es" -> "Estado: Listo para iniciar"
                "hi" -> "स्थिति: शुरू करने के लिए तैयार"
                "ar" -> "الحالة: جاهز للتشغيل"
                "pt" -> "Status: Pronto para iniciar"
                "ru" -> "Статус: Готово к запуску"
                "ja" -> "ステータス: 起動準備完了"
                "de" -> "Status: Startbereit"
                "fr" -> "Statut : Prêt à démarrer"
                else -> "Status: Ready to launch"
            }
        }
        val row2Text = when (lang) {
            "vi" -> "Nhấn nút bên dưới để chiếu giao diện lên màn hình Android Auto"
            "zh" -> "点击下方按钮将应用界面投射到 Android Auto 屏幕"
            "es" -> "Toque el botón de abajo para proyectar en Android Auto"
            "hi" -> "Android Auto स्क्रीन पर प्रोजेक्ट करने के लिए नीचे दिए गए बटन को दबाएं"
            "ar" -> "اضغط على الزر أدناه لعرض الواجهة على شاشة Android Auto"
            "pt" -> "Toque no botão abaixo para projetar na tela do Android Auto"
            "ru" -> "Нажмите кнопку ниже для проекции на экран Android Auto"
            "ja" -> "下のボタンを押して Android Auto 画面に投影します"
            "de" -> "Tippen Sie unten, um auf den Android Auto-Bildschirm zu projizieren"
            "fr" -> "Appuyez ci-dessous pour projeter sur l'écran Android Auto"
            else -> "Tap the button below to project onto the Android Auto screen"
        }

        val row2 = Row.Builder()
            .setTitle(row2Title)
            .addText(row2Text)
            .build()

        val projectBtnTitle = when (lang) {
            "vi" -> "Chiếu lên AA"
            "zh" -> "投射到 AA"
            "es" -> "Proyectar en AA"
            "hi" -> "AA पर दिखाएं"
            "ar" -> "عرض على AA"
            "pt" -> "Projetar no AA"
            "ru" -> "На экран AA"
            "ja" -> "AA に投影"
            "de" -> "Auf AA projizieren"
            "fr" -> "Projeter sur AA"
            else -> "Project to AA"
        }
        val openPhoneBtnTitle = when (lang) {
            "vi" -> "Mở trên ĐT"
            "zh" -> "在手机打开"
            "es" -> "Abrir en Tel."
            "hi" -> "फ़ोन पर खोलें"
            "ar" -> "فتح بالهاتف"
            "pt" -> "Abrir no Cel."
            "ru" -> "На телефоне"
            "ja" -> "スマホで開く"
            "de" -> "Am Telefon"
            "fr" -> "Sur téléphone"
            else -> "Open on Phone"
        }

        val pane = Pane.Builder()
            .addRow(row1)
            .addRow(row2)
            .addAction(
                Action.Builder()
                    .setTitle(projectBtnTitle)
                    .setBackgroundColor(CarColor.PRIMARY)
                    .setOnClickListener {
                        isProjectionMode = true
                        invalidate()
                        launchOriginalAppActivity(forceCarDisplay = true)
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle(openPhoneBtnTitle)
                    .setOnClickListener {
                        launchOriginalAppActivity(forceCarDisplay = false)
                    }
                    .build()
            )
            .build()

        return PaneTemplate.Builder(pane)
            .setTitle(appLabel)
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(paneActionStrip)
            .build()
    }

    private val finishScrollDragRunnable = Runnable {
        finishActiveScrollDragIfNeeded()
    }

    private fun finishActiveScrollDragIfNeeded() {
        if (!isScrollDragActive) return
        isScrollDragActive = false
        val displayId = scrollDisplayId
        if (displayId == Display.DEFAULT_DISPLAY) return
        val upTime = SystemClock.uptimeMillis()
        dispatchMotionEventToDisplayViews(
            displayId = displayId,
            downTime = scrollDownTime,
            eventTime = upTime,
            action = MotionEvent.ACTION_UP,
            rawX = scrollCurX,
            rawY = scrollCurY
        )
        requestCoalescedFrameRender()
    }

    override fun onClick(x: Float, y: Float) {
        val displayId = virtualDisplay?.display?.displayId ?: return
        if (displayId == Display.DEFAULT_DISPLAY) return
        markUserInteraction()
        mainHandler.removeCallbacks(finishScrollDragRunnable)
        finishActiveScrollDragIfNeeded()
        if (isBottomLeftOpenAppHit(x, y)) {
            isProjectionMode = true
            launchOriginalAppActivity(forceCarDisplay = true)
            invalidate()
            return
        }
        ensureBottomLeftOpenAppOverlay(displayId)
        injectTapOnVirtualDisplay(displayId, x, y)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        val displayId = virtualDisplay?.display?.displayId ?: return
        if (displayId == Display.DEFAULT_DISPLAY) return
        markUserInteraction()

        val w = (surfaceWidth.takeIf { it > 0 } ?: 1280).toFloat()
        val h = (surfaceHeight.takeIf { it > 0 } ?: 720).toFloat()
        val cx = w * 0.5f
        val cy = h * 0.5f
        val minX = 24f
        val maxX = (w - 24f).coerceAtLeast(48f)
        val minY = 24f
        val maxY = (h - 24f).coerceAtLeast(48f)

        val stepX = -distanceX * 1.55f
        val stepY = -distanceY * 1.55f
        val now = SystemClock.uptimeMillis()

        if (findAnyActivityDecorViewsInProcess(displayId).isNotEmpty()) {
            val scrollAction = Runnable {
                try {
                    if (!isScrollDragActive || scrollDisplayId != displayId) {
                        isScrollDragActive = true
                        scrollDisplayId = displayId
                        scrollDownTime = now
                        scrollCurX = cx
                        scrollCurY = cy
                        dispatchMotionEventToDisplayViews(
                            displayId = displayId,
                            downTime = scrollDownTime,
                            eventTime = now,
                            action = MotionEvent.ACTION_DOWN,
                            rawX = scrollCurX,
                            rawY = scrollCurY
                        )
                    } else {
                        val nextX = scrollCurX + stepX
                        val nextY = scrollCurY + stepY

                        if (nextX !in minX..maxX || nextY !in minY..maxY) {
                            dispatchMotionEventToDisplayViews(
                                displayId = displayId,
                                downTime = scrollDownTime,
                                eventTime = now,
                                action = MotionEvent.ACTION_UP,
                                rawX = scrollCurX,
                                rawY = scrollCurY
                            )
                            scrollDownTime = now
                            scrollCurX = cx
                            scrollCurY = cy
                            dispatchMotionEventToDisplayViews(
                                displayId = displayId,
                                downTime = scrollDownTime,
                                eventTime = now,
                                action = MotionEvent.ACTION_DOWN,
                                rawX = scrollCurX,
                                rawY = scrollCurY
                            )
                        }
                    }

                    scrollCurX = (scrollCurX + stepX).coerceIn(minX, maxX)
                    scrollCurY = (scrollCurY + stepY).coerceIn(minY, maxY)
                    dispatchMotionEventToDisplayViews(
                        displayId = displayId,
                        downTime = scrollDownTime,
                        eventTime = now,
                        action = MotionEvent.ACTION_MOVE,
                        rawX = scrollCurX,
                        rawY = scrollCurY
                    )
                    requestCoalescedFrameRender()

                    mainHandler.removeCallbacks(finishScrollDragRunnable)
                    postMainDelayedAsync(finishScrollDragRunnable, 85L)
                } catch (_: Throwable) {
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                scrollAction.run()
            } else {
                postMainAsync(scrollAction)
            }
            return
        }

        val dx = (-distanceX * 2.5f).coerceIn(-cx * 0.8f, cx * 0.8f)
        val dy = (-distanceY * 2.5f).coerceIn(-cy * 0.8f, cy * 0.8f)
        val startX = (cx - dx / 2f).coerceIn(minX, maxX)
        val startY = (cy - dy / 2f).coerceIn(minY, maxY)
        val endX = (cx + dx / 2f).coerceIn(minX, maxX)
        val endY = (cy + dy / 2f).coerceIn(minY, maxY)
        injectSwipeOnVirtualDisplay(displayId, startX, startY, endX, endY, 90L)
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        val displayId = virtualDisplay?.display?.displayId ?: return
        if (displayId == Display.DEFAULT_DISPLAY) return
        markUserInteraction()
        if (focusX > 0f && focusY > 0f && scaleFactor == 2.0f) {

            injectTapOnVirtualDisplay(displayId, focusX, focusY)
        }
    }

    private fun findTargetDecorViewsOnDisplay(displayId: Int): List<View> {
        ensureReflectionInitialized()
        val result = ArrayList<View>(4)
        try {
            val wmgInstance = cachedWmgInstance
            val rawViews = if (wmgInstance != null) cachedWmgViewsField?.get(wmgInstance) else null
            if (rawViews is List<*>) {
                for (item in rawViews) {
                    val view = item as? View ?: continue
                    val isMirrorPres = view.tag == AppMirrorPresentation.PRESENTATION_ROOT_TAG
                    if (view.visibility == View.VISIBLE &&
                        view.isAttachedToWindow &&
                        view.display?.displayId == displayId &&
                        !isMirrorPres
                    ) {
                        result.add(view)
                    }
                }
            }
        } catch (_: Throwable) {
        }

        if (result.isEmpty()) {
            try {
                val currentAt = cachedActivityThread
                val activitiesMap = (if (currentAt != null) cachedActivitiesField?.get(currentAt) else null) as? Map<*, *>
                if (activitiesMap != null) {
                    for (record in activitiesMap.values) {
                        if (record == null) continue
                        val actField = record.javaClass.getDeclaredField("activity").apply { isAccessible = true }
                        val act = actField.get(record) as? Activity ?: continue
                        val decor = act.window?.decorView ?: continue
                        if (decor.visibility == View.VISIBLE && decor.display?.displayId == displayId) {
                            result.add(decor)
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return result.asReversed()
    }

    private fun findAnyActivityDecorViewsInProcess(preferredDisplayId: Int): List<View> {
        val direct = findTargetDecorViewsOnDisplay(preferredDisplayId)
        if (direct.isNotEmpty()) return direct

        ensureReflectionInitialized()
        val candidates = ArrayList<View>(4)

        try {
            val wmgInstance = cachedWmgInstance
            val rawViews = if (wmgInstance != null) cachedWmgViewsField?.get(wmgInstance) else null
            if (rawViews is List<*>) {
                for (item in rawViews) {
                    val view = item as? View ?: continue
                    val isMirrorPres = view.tag == AppMirrorPresentation.PRESENTATION_ROOT_TAG
                    if (!isMirrorPres && view.display?.displayId == preferredDisplayId) {
                        if (view.visibility != View.VISIBLE) {
                            view.visibility = View.VISIBLE
                        }
                        candidates.add(view)
                    }
                }
                if (candidates.isEmpty()) {
                    for (item in rawViews) {
                        val view = item as? View ?: continue
                        val isMirrorPres = view.tag == AppMirrorPresentation.PRESENTATION_ROOT_TAG
                        if (!isMirrorPres && view.width > 0 && view.height > 0) {
                            if (view.visibility != View.VISIBLE) {
                                view.visibility = View.VISIBLE
                            }
                            candidates.add(view)
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }

        if (candidates.isEmpty()) {
            try {
                val currentAt = cachedActivityThread
                val activitiesMap = (if (currentAt != null) cachedActivitiesField?.get(currentAt) else null) as? Map<*, *>
                if (activitiesMap != null) {
                    for (record in activitiesMap.values) {
                        if (record == null) continue
                        val actField = record.javaClass.getDeclaredField("activity").apply { isAccessible = true }
                        val act = actField.get(record) as? Activity ?: continue
                        if (act.isFinishing || act.isDestroyed) continue
                        if (act.javaClass.name.contains("CarAppPermissionActivity")) continue
                        val decor = act.window?.decorView ?: continue
                        if (decor.visibility != View.VISIBLE) {
                            decor.visibility = View.VISIBLE
                        }
                        if (decor !in candidates) {
                            candidates.add(decor)
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return candidates.asReversed()
    }

    private val reusablePointerProps = arrayOf(
        MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
    )
    private val reusablePointerCoords = arrayOf(MotionEvent.PointerCoords())
    private val reusableWindowLoc = IntArray(2)

    private fun dispatchMotionEventToDisplayViews(
        displayId: Int,
        downTime: Long,
        eventTime: Long,
        action: Int,
        rawX: Float,
        rawY: Float
    ): Boolean {
        val decorViews = findAnyActivityDecorViewsInProcess(displayId)
        if (decorViews.isEmpty()) return false

        if (isPhoneScreenLockedOrOff()) {
            unstopAllWindowRoots(invokeRelayout = false)
        }

        for (decor in decorViews) {
            try {
                val scaleX = if (surfaceWidth > 0 && decor.width > 0 && decor.width != surfaceWidth) {
                    decor.width.toFloat() / surfaceWidth.toFloat()
                } else {
                    1f
                }
                val scaleY = if (surfaceHeight > 0 && decor.height > 0 && decor.height != surfaceHeight) {
                    decor.height.toFloat() / surfaceHeight.toFloat()
                } else {
                    1f
                }
                decor.getLocationOnScreen(reusableWindowLoc)
                val localX = (rawX * scaleX) - reusableWindowLoc[0]
                val localY = (rawY * scaleY) - reusableWindowLoc[1]
                val coord = reusablePointerCoords[0]
                coord.clear()
                coord.x = localX
                coord.y = localY
                coord.pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
                coord.size = 1f

                val ev = MotionEvent.obtain(
                    downTime,
                    eventTime,
                    action,
                    1,
                    reusablePointerProps,
                    reusablePointerCoords,
                    0,
                    0,
                    1f,
                    1f,
                    0,
                    0,
                    InputDevice.SOURCE_TOUCHSCREEN,
                    0
                )
                val handled = decor.dispatchTouchEvent(ev)
                ev.recycle()
                if (handled) {
                    return true
                }
            } catch (_: Throwable) {
            }
        }
        return true
    }

    private fun injectMotionTapInProcess(displayId: Int, x: Float, y: Float): Boolean {
        if (findAnyActivityDecorViewsInProcess(displayId).isEmpty()) return false
        val downAction = Runnable {
            val downTime = SystemClock.uptimeMillis()
            dispatchMotionEventToDisplayViews(
                displayId = displayId,
                downTime = downTime,
                eventTime = downTime,
                action = MotionEvent.ACTION_DOWN,
                rawX = x,
                rawY = y
            )
            requestCoalescedFrameRender()
            postMainDelayedAsync({
                val upTime = SystemClock.uptimeMillis()
                dispatchMotionEventToDisplayViews(
                    displayId = displayId,
                    downTime = downTime,
                    eventTime = upTime,
                    action = MotionEvent.ACTION_UP,
                    rawX = x,
                    rawY = y
                )
                requestCoalescedFrameRender()
                postMainDelayedAsync({ requestCoalescedFrameRender() }, 32L)
            }, 18L)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            downAction.run()
        } else {
            postMainAsync(downAction)
        }
        return true
    }

    private fun injectMotionSwipeInProcess(
        displayId: Int,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long
    ): Boolean {
        if (findAnyActivityDecorViewsInProcess(displayId).isEmpty()) return false
        postMainAsync {
            val downTime = SystemClock.uptimeMillis()
            val steps = 6
            val totalDuration = max(60L, durationMs)
            val stepDelay = max(10L, totalDuration / steps)

            dispatchMotionEventToDisplayViews(
                displayId = displayId,
                downTime = downTime,
                eventTime = downTime,
                action = MotionEvent.ACTION_DOWN,
                rawX = x1,
                rawY = y1
            )

            for (i in 1..steps) {
                val fraction = i.toFloat() / steps.toFloat()
                val curX = x1 + (x2 - x1) * fraction
                val curY = y1 + (y2 - y1) * fraction
                val delay = stepDelay * i
                postMainDelayedAsync({
                    val moveTime = SystemClock.uptimeMillis()
                    dispatchMotionEventToDisplayViews(
                        displayId = displayId,
                        downTime = downTime,
                        eventTime = moveTime,
                        action = MotionEvent.ACTION_MOVE,
                        rawX = curX,
                        rawY = curY
                    )
                    requestCoalescedFrameRender()
                }, delay)
            }

            postMainDelayedAsync({
                val upTime = SystemClock.uptimeMillis()
                dispatchMotionEventToDisplayViews(
                    displayId = displayId,
                    downTime = downTime,
                    eventTime = upTime,
                    action = MotionEvent.ACTION_UP,
                    rawX = x2,
                    rawY = y2
                )
                requestCoalescedFrameRender()
            }, stepDelay * steps + 8L)
        }
        return true
    }

    private fun injectTapOnVirtualDisplay(displayId: Int, x: Float, y: Float) {

        val injectedInProcess = injectMotionTapInProcess(displayId, x, y)
        if (injectedInProcess) return

        if (isRootShellAllowed()) {
            val ix = x.roundToInt()
            val iy = y.roundToInt()
            runRootInputCommand("input -d $displayId tap $ix $iy")
            return
        }

        try {
            val appCtx = carContext.applicationContext
            val intent = Intent("com.aamaker.action.INJECT_TOUCH").apply {
                setPackage("com.aamaker.app")
                putExtra("display_id", displayId)
                putExtra("touch_type", "tap")
                putExtra("x1", x)
                putExtra("y1", y)
                putExtra("duration_ms", 55L)
            }
            appCtx.sendBroadcast(intent)
        } catch (_: Throwable) {
        }
    }

    private fun injectSwipeOnVirtualDisplay(
        displayId: Int,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long
    ) {

        val injectedInProcess = injectMotionSwipeInProcess(displayId, x1, y1, x2, y2, durationMs)
        if (injectedInProcess) return

        if (isRootShellAllowed()) {
            val ix1 = x1.roundToInt()
            val iy1 = y1.roundToInt()
            val ix2 = x2.roundToInt()
            val iy2 = y2.roundToInt()
            runRootInputCommand("input -d $displayId swipe $ix1 $iy1 $ix2 $iy2 $durationMs")
            return
        }

        try {
            val appCtx = carContext.applicationContext
            val intent = Intent("com.aamaker.action.INJECT_TOUCH").apply {
                setPackage("com.aamaker.app")
                putExtra("display_id", displayId)
                putExtra("touch_type", "swipe")
                putExtra("x1", x1)
                putExtra("y1", y1)
                putExtra("x2", x2)
                putExtra("y2", y2)
                putExtra("duration_ms", durationMs)
            }
            appCtx.sendBroadcast(intent)
        } catch (_: Throwable) {
        }
    }

    private fun runRootInputCommand(cmd: String) {
        inputExecutor.execute {
            try {
                var writer = persistentSuWriter
                val proc = persistentSuProcess
                if (writer == null || proc == null || !proc.isAlive) {
                    val shellBin = String(charArrayOf('s', 'u'))
                    val newProc = ProcessBuilder(shellBin).start()
                    writer = OutputStreamWriter(newProc.outputStream)
                    persistentSuProcess = newProc
                    persistentSuWriter = writer
                }
                writer.write("$cmd\n")
                writer.flush()
            } catch (_: Throwable) {
                persistentSuWriter = null
                persistentSuProcess = null
            }
        }
    }

    private fun triggerPrivilegedDisplayLaunchIfNeeded(
        appCtx: Context,
        targetDisplayId: Int,
        compName: String
    ) {
        if (compName.isEmpty() || targetDisplayId == Display.DEFAULT_DISPLAY) return
        try {
            val bridgeIntent = Intent("com.aamaker.action.LAUNCH_ON_DISPLAY").apply {
                setPackage("com.aamaker.app")
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra("display_id", targetDisplayId)
                putExtra("component_name", compName)
            }
            appCtx.sendBroadcast(bridgeIntent)
        } catch (_: Throwable) {
        }
        if (isRootShellAllowed()) {
            runRootInputCommand(
                "cmd deviceidle tempwhitelist -d 300000 ${appCtx.packageName} >/dev/null 2>&1; am start --display $targetDisplayId --activity-single-top --activity-clear-top -n $compName"
            )
        }
    }

    private fun launchOriginalAppActivity(forceCarDisplay: Boolean = true) {
        try {
            val now = SystemClock.uptimeMillis()
            if (forceCarDisplay && now - lastLaunchAttemptMs < 900L) {
                return
            }
            lastLaunchAttemptMs = now

            val appCtx = carContext.applicationContext
            val pm = appCtx.packageManager
            var launchIntent = pm.getLaunchIntentForPackage(appCtx.packageName)

            if (launchIntent == null) {
                val mainIntent = Intent(Intent.ACTION_MAIN).apply {
                    setPackage(appCtx.packageName)
                }
                val resolveList = pm.queryIntentActivities(mainIntent, 0)
                val targetActivity = resolveList.firstOrNull {
                    !it.activityInfo.name.contains("CarAppPermissionActivity")
                }?.activityInfo
                if (targetActivity != null) {
                    launchIntent = Intent(Intent.ACTION_MAIN).apply {
                        setClassName(appCtx.packageName, targetActivity.name)
                    }
                }
            }

            if (launchIntent != null) {
                val targetDisplay = if (forceCarDisplay) virtualDisplay?.display else null
                val targetDisplayId = targetDisplay?.displayId ?: Display.DEFAULT_DISPLAY

                if (targetDisplay != null &&
                    targetDisplayId != Display.DEFAULT_DISPLAY &&
                    findTargetDecorViewsOnDisplay(targetDisplayId).isNotEmpty()
                ) {
                    unstopAllWindowRoots(invokeRelayout = false)
                    forceResumeAllProcessActivitiesOnDisplay(targetDisplayId)
                    requestCoalescedFrameRender()
                    return
                }

                if (targetDisplay != null && targetDisplayId != Display.DEFAULT_DISPLAY) {
                    registerLockScreenSupport(targetDisplayId)
                    launchIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_NO_USER_ACTION
                    )
                } else {
                    launchIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }

                val activityOptions = ActivityOptions.makeBasic().apply {
                    if (targetDisplay != null && targetDisplayId != Display.DEFAULT_DISPLAY) {
                        setLaunchDisplayId(targetDisplayId)
                    }
                    if (Build.VERSION.SDK_INT >= 34) {
                        try {
                            setPendingIntentBackgroundActivityStartMode(
                                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                            )
                        } catch (_: Throwable) {
                        }
                    }
                }
                val optionsBundle = activityOptions.toBundle()

                val launchContext = if (targetDisplay != null && targetDisplayId != Display.DEFAULT_DISPLAY) {
                    try {
                        val dispCtx = appCtx.createDisplayContext(targetDisplay)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            dispCtx.createWindowContext(
                                WindowManager.LayoutParams.TYPE_APPLICATION,
                                null
                            )
                        } else {
                            dispCtx
                        }
                    } catch (_: Throwable) {
                        try {
                            appCtx.createDisplayContext(targetDisplay)
                        } catch (_: Throwable) {
                            appCtx
                        }
                    }
                } else {
                    appCtx
                }

                var launchedOnVirtualDisplay = false

                if (targetDisplay != null && targetDisplayId != Display.DEFAULT_DISPLAY) {
                    if (presentation == null || presentation?.isShowing != true) {
                        try {
                            presentation?.dismiss()
                            presentation = AppMirrorPresentation(carContext, targetDisplay).also { it.show() }
                        } catch (_: Throwable) {
                        }
                    }
                    val presWindowCtx = presentation?.window?.context ?: presentation?.context
                    if (presWindowCtx != null) {
                        try {
                            presWindowCtx.startActivity(launchIntent, optionsBundle)
                            launchedOnVirtualDisplay = true
                        } catch (_: Throwable) {
                        }
                    }
                }

                if (!launchedOnVirtualDisplay) {
                    try {
                        launchContext.startActivity(launchIntent, optionsBundle)
                        launchedOnVirtualDisplay = true
                    } catch (_: Throwable) {
                    }
                }

                if (!launchedOnVirtualDisplay) {
                    try {
                        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        val pendingIntent = PendingIntent.getActivity(
                            launchContext,
                            targetDisplayId,
                            launchIntent,
                            piFlags,
                            optionsBundle
                        )
                        pendingIntent.send(launchContext, 0, null, null, null, null, optionsBundle)
                        launchedOnVirtualDisplay = true
                    } catch (_: Throwable) {
                    }
                }

                if (targetDisplay != null && targetDisplayId != Display.DEFAULT_DISPLAY) {
                    val compName = launchIntent.component?.flattenToShortString().orEmpty()
                    if (compName.isNotEmpty()) {
                        if (!launchedOnVirtualDisplay || isPhoneScreenLockedOrOff()) {

                            triggerPrivilegedDisplayLaunchIfNeeded(appCtx, targetDisplayId, compName)
                        } else {

                            postMainDelayedAsync({
                                if (isSurfaceReady && findAnyActivityDecorViewsInProcess(targetDisplayId).isEmpty()) {
                                    triggerPrivilegedDisplayLaunchIfNeeded(appCtx, targetDisplayId, compName)
                                }
                            }, 420L)
                        }
                    }

                    applyLockScreenFlagsToAllProcessActivities(targetDisplayId)
                    schedulePresentationDismissWhenActivityReady(targetDisplayId)
                }
            } else {
                val noLauncherMsg = when (currentLang()) {
                    "vi" -> "Không tìm thấy Launcher Activity mặc định trong APK"
                    else -> "Default Launcher Activity not found in APK"
                }
                CarToast.makeText(
                    carContext,
                    noLauncherMsg,
                    CarToast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            val errPrefix = when (currentLang()) {
                "vi" -> "Lỗi mở Activity"
                else -> "Error launching Activity"
            }
            CarToast.makeText(
                carContext,
                "$errPrefix: ${e.message}",
                CarToast.LENGTH_LONG
            ).show()
        }
    }

    private fun schedulePresentationDismissWhenActivityReady(displayId: Int, attempt: Int = 0) {
        postMainDelayedAsync({
            try {
                if (!isSurfaceReady) return@postMainDelayedAsync
                if (isPhoneScreenLockedOrOff()) {
                    unstopAllWindowRoots(invokeRelayout = false)
                }
                applyLockScreenFlagsToAllProcessActivities(displayId)
                requestCoalescedFrameRender()
                if (findAnyActivityDecorViewsInProcess(displayId).isEmpty() && attempt < 15 && isSurfaceReady) {
                    schedulePresentationDismissWhenActivityReady(displayId, attempt + 1)
                }
            } catch (_: Throwable) {
            }
        }, 250L)
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        val width = surfaceContainer.width
        val height = surfaceContainer.height
        val dpi = surfaceContainer.dpi
        if (width <= 0 || height <= 0) return

        isSurfaceReady = true
        surfaceWidth = width
        surfaceHeight = height
        surfaceDpi = dpi.takeIf { it > 0 } ?: 160
        carOutputSurface = surface
        surfaceAvailableTimestampMs = SystemClock.uptimeMillis()
        autoRetryLaunchCount = 0

        renderLoadingBannerOnSurface(surface, width, height)

        try {
            val displayManager = carContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            virtualDisplay?.release()
            virtualDisplayImageReader?.close()

            val bgHandler = getOrCreateSurfacePostHandler()
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).apply {
                setOnImageAvailableListener({ r ->
                    try {
                        r.acquireLatestImage()?.close()
                    } catch (_: Throwable) {
                    }
                }, bgHandler)
            }
            virtualDisplayImageReader = reader

            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION

            virtualDisplay = try {
                displayManager.createVirtualDisplay(
                    "AAMakerVirtualDisplay_${carContext.packageName}",
                    width,
                    height,
                    dpi,
                    reader.surface,
                    flags
                )
            } catch (_: SecurityException) {
                displayManager.createVirtualDisplay(
                    "AAMakerVirtualDisplay_${carContext.packageName}",
                    width,
                    height,
                    dpi,
                    reader.surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                )
            }

            virtualDisplay?.display?.let { vDisplay ->
                registerLockScreenSupport(vDisplay.displayId)
                presentation?.dismiss()
                presentation = AppMirrorPresentation(carContext, vDisplay).also { it.show() }
                ensureBottomLeftOpenAppOverlay(vDisplay.displayId)
            }

            if (!hasAutoLaunched) {
                hasAutoLaunched = true
                postMainDelayedAsync({
                    if (isSurfaceReady) {
                        launchOriginalAppActivity(forceCarDisplay = true)
                    }
                }, 80L)
            }
        } catch (_: Throwable) {
            renderFallbackBanner(surface, width, height)
        }
        invalidate()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        isSurfaceReady = false
        hasAutoLaunched = false
        isScrollDragActive = false
        mainHandler.removeCallbacks(finishScrollDragRunnable)
        carOutputSurface = null
        autoRetryLaunchCount = 0
        isSurfacePostInFlight.set(false)
        unregisterLockScreenSupport()
        try {
            presentation?.dismiss()
            presentation = null
            virtualDisplay?.release()
            virtualDisplay = null
            virtualDisplayImageReader?.close()
            virtualDisplayImageReader = null
            surfacePostHandler?.removeCallbacksAndMessages(null)
            surfacePostThread?.quitSafely()
            surfacePostHandler = null
            surfacePostThread = null
            for (i in renderBuffers.indices) {
                renderBuffers[i]?.recycle()
                renderBuffers[i] = null
                renderCanvases[i] = null
            }
            persistentSuWriter?.close()
            persistentSuProcess?.destroy()
            persistentSuWriter = null
            persistentSuProcess = null
        } catch (_: Throwable) {
        }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {

    }

    override fun onStableAreaChanged(stableArea: Rect) {

    }

    private fun renderFallbackBanner(surface: Surface, width: Int, height: Int) {
        getOrCreateSurfacePostHandler().post {
            try {
                if (!surface.isValid) return@post
                val canvas: Canvas = surface.lockCanvas(null)
                try {
                    canvas.drawColor(Color.parseColor("#10141C"))
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        textSize = 36f
                        textAlign = Paint.Align.CENTER
                    }
                    canvas.drawText(
                        "AAMaKer Surface Bridge Active (${width}x${height})",
                        width / 2f,
                        height / 2f,
                        paint
                    )
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private class SafeSoftwareCanvas(bitmap: Bitmap) : Canvas(bitmap) {

        var activeSurfaceViewFallback: Bitmap? = null
        private val tmpClipRect = Rect()
        private val hwBitmapCache = java.util.WeakHashMap<Bitmap, Bitmap>()

        private fun unwrapHardwareBitmap(bitmap: Bitmap): Bitmap {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE) {
                synchronized(hwBitmapCache) {
                    val cached = hwBitmapCache[bitmap]
                    if (cached != null && !cached.isRecycled) {
                        return cached
                    }
                    val swCopy = try {
                        bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (_: Throwable) {
                        null
                    }
                    if (swCopy != null) {
                        hwBitmapCache[bitmap] = swCopy
                        return swCopy
                    }
                }
            }
            return bitmap
        }

        override fun drawBitmap(bitmap: Bitmap, left: Float, top: Float, paint: Paint?) {
            try {
                super.drawBitmap(unwrapHardwareBitmap(bitmap), left, top, paint)
            } catch (_: Throwable) {
            }
        }

        override fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: RectF, paint: Paint?) {
            try {
                super.drawBitmap(unwrapHardwareBitmap(bitmap), src, dst, paint)
            } catch (_: Throwable) {
            }
        }

        override fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: Rect, paint: Paint?) {
            try {
                super.drawBitmap(unwrapHardwareBitmap(bitmap), src, dst, paint)
            } catch (_: Throwable) {
            }
        }

        override fun drawBitmap(bitmap: Bitmap, matrix: Matrix, paint: Paint?) {
            try {
                super.drawBitmap(unwrapHardwareBitmap(bitmap), matrix, paint)
            } catch (_: Throwable) {
            }
        }

        override fun drawRect(rect: RectF, paint: Paint) {
            try {
                super.drawRect(rect, paint)
            } catch (_: Throwable) {
                try {
                    val safePaint = Paint(paint).apply { shader = null }
                    super.drawRect(rect, safePaint)
                } catch (_: Throwable) {
                }
            }
        }

        override fun drawRect(r: Rect, paint: Paint) {
            try {
                super.drawRect(r, paint)
            } catch (_: Throwable) {
                try {
                    val safePaint = Paint(paint).apply { shader = null }
                    super.drawRect(r, safePaint)
                } catch (_: Throwable) {
                }
            }
        }

        override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            try {
                super.drawRect(left, top, right, bottom, paint)
            } catch (_: Throwable) {
                try {
                    val safePaint = Paint(paint).apply { shader = null }
                    super.drawRect(left, top, right, bottom, safePaint)
                } catch (_: Throwable) {
                }
            }
        }

        override fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) {
            try {
                super.drawRoundRect(rect, rx, ry, paint)
            } catch (_: Throwable) {
                try {
                    val safePaint = Paint(paint).apply { shader = null }
                    super.drawRoundRect(rect, rx, ry, safePaint)
                } catch (_: Throwable) {
                }
            }
        }

        override fun drawRoundRect(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            rx: Float,
            ry: Float,
            paint: Paint
        ) {
            try {
                super.drawRoundRect(left, top, right, bottom, rx, ry, paint)
            } catch (_: Throwable) {
                try {
                    val safePaint = Paint(paint).apply { shader = null }
                    super.drawRoundRect(left, top, right, bottom, rx, ry, safePaint)
                } catch (_: Throwable) {
                }
            }
        }

        override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {
            try {
                super.drawCircle(cx, cy, radius, paint)
            } catch (_: Throwable) {
                try {
                    val safePaint = Paint(paint).apply { shader = null }
                    super.drawCircle(cx, cy, radius, safePaint)
                } catch (_: Throwable) {
                }
            }
        }

        override fun drawOval(oval: RectF, paint: Paint) {
            try {
                super.drawOval(oval, paint)
            } catch (_: Throwable) {
                try {
                    val safePaint = Paint(paint).apply { shader = null }
                    super.drawOval(oval, safePaint)
                } catch (_: Throwable) {
                }
            }
        }

        override fun drawPath(path: Path, paint: Paint) {
            try {
                super.drawPath(path, paint)
            } catch (_: Throwable) {
                try {
                    val safePaint = Paint(paint).apply { shader = null }
                    super.drawPath(path, safePaint)
                } catch (_: Throwable) {
                }
            }
        }

        override fun drawPaint(paint: Paint) {
            try {
                super.drawPaint(paint)
            } catch (_: Throwable) {
            }
        }

        override fun drawColor(color: Int, mode: PorterDuff.Mode) {

            if (mode == PorterDuff.Mode.CLEAR) {
                val svBmp = activeSurfaceViewFallback
                if (svBmp != null && !svBmp.isRecycled) {
                    try {
                        if (getClipBounds(tmpClipRect) && !tmpClipRect.isEmpty) {
                            super.drawBitmap(svBmp, null, tmpClipRect, null)
                        }
                    } catch (_: Throwable) {
                    }
                }
                return
            }
            try {
                super.drawColor(color, mode)
            } catch (_: Throwable) {
            }
        }
    }

    private class AppMirrorPresentation(
        outerContext: Context,
        display: Display
    ) : Presentation(outerContext, display) {

        private var rootContainer: FrameLayout? = null

        @Suppress("DEPRECATION")
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window?.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
            window?.decorView?.tag = PRESENTATION_ROOT_TAG
            val root = FrameLayout(context).apply {
                setBackgroundColor(Color.parseColor("#0F172A"))
            }
            rootContainer = root
            setContentView(root)
        }

        fun attachEmbeddedDecorView(decor: View, width: Int, height: Int) {
            try {
                val container = rootContainer ?: return
                if (decor.parent !== container) {
                    (decor.parent as? ViewGroup)?.removeView(decor)
                    container.addView(
                        decor,
                        FrameLayout.LayoutParams(width, height)
                    )
                }
            } catch (_: Throwable) {
            }
        }

        companion object {
            const val PRESENTATION_ROOT_TAG = "aamaker_presentation_decor"
        }
    }
}

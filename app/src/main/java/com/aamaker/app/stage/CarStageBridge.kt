package com.aamaker.app.stage

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import com.aamaker.app.i18n.AppLanguage
import com.aamaker.app.i18n.CarStageLocalization
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

data class CarStageState(
    val isBound: Boolean = false,
    val isBinding: Boolean = false,
    val isScanningMdns: Boolean = false,
    val discoveredAdbPort: Int? = null,
    val discoveredPairingPort: Int? = null,
    val engineMode: String = "STOPPED",
    val activeDisplayId: Int = -1,
    val activePackage: String? = null,
    val lastError: String = "",
    val config: CarStageConfig = CarStageConfig(),
    val logs: List<String> = emptyList()
)

/**
 * Isolated client bridge for the Car Stage tab.
 * Supports BOTH Shizuku UserService AND Built-in Local Wireless ADB in parallel.
 */
object CarStageBridge {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(CarStageState())
    val state: StateFlow<CarStageState> = _state.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var shizukuService: ICarStageService? = null
    private var userServiceArgs: Shizuku.UserServiceArgs? = null

    @Volatile
    private var carSurface: Surface? = null
    @Volatile
    private var carWidth: Int = 1280
    @Volatile
    private var carHeight: Int = 720
    @Volatile
    private var carDpi: Int = 240

    private val splitCompositor = CarStageSplitCompositor()
    private val localAdb = LocalWirelessAdbClient()

    // Touch / Scroll gesture state for smooth swipe & drag on Android Auto SurfaceCallback
    private val touchHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var lastTouchIsSecondary: Boolean = false
    @Volatile
    private var scrollActive: Boolean = false
    @Volatile
    private var scrollDownTime: Long = 0L
    @Volatile
    private var scrollCurX: Float = 0f
    @Volatile
    private var scrollCurY: Float = 0f
    @Volatile
    private var scrollIsSecondary: Boolean = false
    @Volatile
    private var seamDragArmed: Boolean = false
    @Volatile
    private var pinchAccumScale: Float = 1f
    @Volatile
    private var lastPinchInjectTime: Long = 0L
    private val endScrollRunnable = Runnable { finishScrollGesture() }
    private val endSeamDragRunnable = Runnable { finishSeamDrag() }

    private val shizukuConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = ICarStageService.Stub.asInterface(binder)
            shizukuService = svc
            runCatching { svc.setImeOnPhone(_state.value.config.imeOnPhone) }
            val displayId = runCatching { svc.activeDisplayId }.getOrDefault(-1)
            val mode = runCatching { svc.engineMode }.getOrDefault("STOPPED")
            _state.update {
                it.copy(
                    isBound = true,
                    isBinding = false,
                    activeDisplayId = displayId,
                    engineMode = mode,
                    lastError = ""
                )
            }
            appendLog("Shizuku Car Stage connected (mode=$mode, displayId=$displayId)")
            // If Android Auto surface is already attached, start fresh 50:50 pickers without forcing old apps
            if (carSurface != null && carSurface?.isValid == true) {
                startDisplayShizuku(carWidth, carHeight, carDpi)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shizukuService = null
            if (_state.value.config.bridgeMode == StageBridgeMode.SHIZUKU) {
                _state.update {
                    it.copy(
                        isBound = false,
                        isBinding = false,
                        activeDisplayId = -1,
                        engineMode = "STOPPED"
                    )
                }
            }
            appendLog("Shizuku Car Stage disconnected")
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        val loaded = CarStagePrefs.load(context)
        _state.update { it.copy(config = loaded) }
    }

    fun updateConfig(context: Context, transform: (CarStageConfig) -> CarStageConfig) {
        val prevMode = _state.value.config.bridgeMode
        val prevIme = _state.value.config.imeOnPhone
        val updated = transform(_state.value.config)
        CarStagePrefs.save(context, updated)
        _state.update { it.copy(config = updated) }
        if (prevIme != updated.imeOnPhone) {
            runCatching { shizukuService?.setImeOnPhone(updated.imeOnPhone) }
        }
        if (prevMode != updated.bridgeMode && _state.value.isBound) {
            disconnect()
        }
    }

    fun setBridgeMode(context: Context, mode: StageBridgeMode) {
        if (_state.value.config.bridgeMode == mode) return
        if (_state.value.isBound || _state.value.isBinding) {
            disconnect()
        }
        updateConfig(context) { it.copy(bridgeMode = mode) }
        appendLog("Switched bridge mode to ${mode.name}")
    }

    fun togglePinnedPackage(context: Context, packageName: String) {
        updateConfig(context) { cfg ->
            val next = cfg.pinnedPackages.toMutableSet()
            if (!next.add(packageName)) {
                next.remove(packageName)
            }
            cfg.copy(pinnedPackages = next)
        }
    }

    fun swapStageSlots(context: Context) {
        updateConfig(context) { cfg ->
            cfg.copy(
                primaryPackage = cfg.secondaryPackage,
                secondaryPackage = cfg.primaryPackage
            )
        }
        appendLog("Swapped Primary & Secondary Car Stage slots")
        val cfg = _state.value.config
        if (_state.value.isBound && _state.value.config.bridgeMode == StageBridgeMode.SHIZUKU && cfg.layoutPreset.isSplit) {
            if (cfg.primaryPackage.isNotBlank()) {
                launchOrMoveShizuku(cfg.primaryPackage)
            }
            if (cfg.secondaryPackage.isNotBlank()) {
                runCatching { shizukuService?.launchOrMoveSecondaryApp(cfg.secondaryPackage, cfg.moveExistingTask) }
            }
        }
    }

    /**
     * Triggered by the "App 1" button on Android Auto:
     * Shows/toggles the left-side in-surface app picker so the user can pick/change App 1.
     */
    fun openApp1Picker(context: Context) {
        val cfg = _state.value.config
        if (!cfg.layoutPreset.isSplit) {
            applySplitLayout(context, StageLayoutPreset.SPLIT_50_50)
            openLeftInSurfacePicker(context)
        } else {
            if (splitCompositor.showLeftPicker) {
                splitCompositor.setLeftPicker(show = false)
            } else {
                openLeftInSurfacePicker(context)
            }
        }
    }

    /**
     * Triggered by the "App 2" button on Android Auto:
     * Splits 50:50 by default and opens the 2nd app (or right-side app picker if none selected yet).
     */
    fun openApp2Split5050(context: Context) {
        val cfg = _state.value.config
        if (!cfg.layoutPreset.isSplit) {
            applySplitLayout(context, StageLayoutPreset.SPLIT_50_50)
        } else {
            // Already split: toggle right-side app picker so user can pick/switch App 2 immediately
            if (splitCompositor.showRightPicker) {
                splitCompositor.setRightPicker(show = false)
            } else {
                openRightInSurfacePicker(context)
            }
        }
    }

    fun cycleSplitPreset(context: Context) {
        val order = listOf(
            StageLayoutPreset.SINGLE,
            StageLayoutPreset.SPLIT_50_50,
            StageLayoutPreset.SPLIT_60_40,
            StageLayoutPreset.SPLIT_70_30
        )
        val current = _state.value.config.layoutPreset
        val next = order[(order.indexOf(current) + 1) % order.size]
        applySplitLayout(context, next)
    }

    fun applySplitLayout(context: Context, preset: StageLayoutPreset) {
        appContext = context.applicationContext
        val currentPrimary = _state.value.activePackage
            ?: _state.value.config.primaryPackage.ifBlank { _state.value.config.pinnedPackages.firstOrNull().orEmpty() }
        updateConfig(context) { cfg ->
            cfg.copy(
                layoutPreset = preset,
                primaryPackage = currentPrimary
            )
        }
        val bounds = CarStageLayoutEngine.computeSlots(carWidth, carHeight, preset)
        appendLog(
            "Applied split ${preset.key}: left=${bounds.primary.width()}x${bounds.primary.height()}" +
                (bounds.secondary?.let { ", right=${it.width()}x${it.height()}" } ?: "")
        )
        if (_state.value.isBound && _state.value.config.bridgeMode == StageBridgeMode.SHIZUKU) {
            startDisplayShizuku(carWidth, carHeight, carDpi)
            if (currentPrimary.isNotBlank()) {
                launchOrMoveShizuku(currentPrimary)
            }
            if (preset.isSplit) {
                // Show App 1 live on the left, and in-surface App Picker grid on the right
                openRightInSurfacePicker(context)
            }
        }
    }

    private fun loadPickerTiles(context: Context, excludePackage: String = ""): List<StagePickerApp> {
        val cfg = _state.value.config
        val pm = context.packageManager
        val rawPackages = if (cfg.pinnedPackages.isNotEmpty()) {
            cfg.pinnedPackages.filter { it != excludePackage }.ifEmpty { cfg.pinnedPackages.toList() }
        } else {
            // First-time open with no pinned apps: automatically list all launchable installed apps
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            runCatching {
                pm.queryIntentActivities(intent, 0)
                    .mapNotNull { it.activityInfo?.packageName }
                    .distinct()
                    .filter { it != context.packageName && it != excludePackage }
            }.getOrDefault(emptyList())
        }
        return rawPackages.map { pkg ->
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            val bmp = runCatching { drawableToBitmap(pm.getApplicationIcon(pkg), 84) }.getOrNull()
            StagePickerApp(pkg, label, bmp)
        }.sortedBy { it.label.lowercase() }
    }

    private fun openLeftInSurfacePicker(context: Context) {
        val strings = CarStageLocalization.of(AppLanguage.loadSavedLanguage(context))
        val tiles = loadPickerTiles(context)
        splitCompositor.setLeftPicker(show = true, title = strings.carPickerTitle, apps = tiles)
    }

    private fun openRightInSurfacePicker(context: Context) {
        val cfg = _state.value.config
        val strings = CarStageLocalization.of(AppLanguage.loadSavedLanguage(context))
        val tiles = loadPickerTiles(context, excludePackage = cfg.primaryPackage)
        splitCompositor.setRightPicker(show = true, title = strings.carPickSecondAppTitle, apps = tiles)
    }

    fun selectPrimaryApp(context: Context, packageName: String) {
        updateConfig(context) { cfg -> cfg.copy(primaryPackage = packageName) }
        splitCompositor.setLeftPicker(show = false)
        appendLog("Selected 1st split app (left): $packageName")
        launchOrMoveApp(packageName)
    }

    private fun drawableToBitmap(drawable: Drawable, sizePx: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return Bitmap.createScaledBitmap(drawable.bitmap, sizePx, sizePx, true)
        }
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return bmp
    }

    fun selectSecondaryApp(context: Context, packageName: String) {
        updateConfig(context) { cfg -> cfg.copy(secondaryPackage = packageName) }
        splitCompositor.setRightPicker(show = false)
        appendLog("Selected 2nd split app (right): $packageName")
        val svc = shizukuService
        if (_state.value.config.bridgeMode == StageBridgeMode.SHIZUKU && svc != null) {
            val moveTask = _state.value.config.moveExistingTask
            val ok = runCatching { svc.launchOrMoveSecondaryApp(packageName, moveTask) }.getOrDefault(false)
            appendLog("Shizuku launchOrMoveSecondaryApp($packageName) -> $ok")
        } else {
            launchOrMoveApp(packageName)
        }
    }

    fun isShizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun startMdnsScan(context: Context) {
        _state.update { it.copy(isScanningMdns = true) }
        localAdb.startMdnsDiscovery(
            context = context,
            onConnectPortFound = { host, port ->
                _state.update { st ->
                    val nextCfg = st.config.copy(adbHost = host, adbPort = port)
                    CarStagePrefs.save(context, nextCfg)
                    st.copy(
                        discoveredAdbPort = port,
                        config = nextCfg
                    )
                }
            },
            onPairingPortFound = { _, port ->
                _state.update { it.copy(discoveredPairingPort = port) }
            },
            onLog = { appendLog(it) }
        )
    }

    fun stopMdnsScan() {
        localAdb.stopMdnsDiscovery()
        _state.update { it.copy(isScanningMdns = false) }
    }

    fun connect(context: Context) {
        if (_state.value.isBound || _state.value.isBinding) return
        when (_state.value.config.bridgeMode) {
            StageBridgeMode.SHIZUKU -> connectShizuku(context)
            StageBridgeMode.LOCAL_WIRELESS_ADB -> connectLocalWirelessAdb()
        }
    }

    private fun connectShizuku(context: Context) {
        if (!isShizukuReady()) {
            _state.update { it.copy(lastError = "Shizuku is not running or permission not granted") }
            appendLog("Connect aborted: Shizuku not ready")
            return
        }
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, CarStageDisplayServer::class.java.name)
            )
                .daemon(false)
                .processNameSuffix("car_stage")
                .debuggable(false)
                .version(1)
            userServiceArgs = args
            _state.update { it.copy(isBinding = true, lastError = "") }
            appendLog("Binding CarStageDisplayServer via Shizuku...")
            Shizuku.bindUserService(args, shizukuConnection)
        } catch (t: Throwable) {
            _state.update { it.copy(isBinding = false, lastError = t.message ?: "Bind failed") }
            appendLog("Bind failed: ${t.message}")
        }
    }

    private fun connectLocalWirelessAdb() {
        val host = _state.value.config.adbHost.ifBlank { "127.0.0.1" }
        val port = _state.value.config.adbPort
        _state.update { it.copy(isBinding = true, lastError = "") }
        appendLog("Connecting Local Wireless ADB to $host:$port...")

        scope.launch {
            val res = localAdb.connect(host, port)
            res.onSuccess { banner ->
                _state.update {
                    it.copy(
                        isBound = true,
                        isBinding = false,
                        engineMode = "LOCAL_ADB",
                        lastError = ""
                    )
                }
                appendLog("Local Wireless ADB connected ($banner)")
            }.onFailure { err ->
                _state.update {
                    it.copy(
                        isBound = false,
                        isBinding = false,
                        engineMode = "STOPPED",
                        lastError = err.message ?: "ADB connection failed"
                    )
                }
                appendLog("Local ADB failed: ${err.message}")
            }
        }
    }

    fun disconnect() {
        stopMdnsScan()
        runCatching { shizukuService?.stopStageDisplay() }
        userServiceArgs?.let { args ->
            runCatching { Shizuku.unbindUserService(args, shizukuConnection, true) }
        }
        shizukuService = null
        localAdb.disconnect()

        _state.update {
            it.copy(
                isBound = false,
                isBinding = false,
                activeDisplayId = -1,
                engineMode = "STOPPED",
                activePackage = null
            )
        }
        appendLog("Disconnected Car Stage bridge")
    }

    fun startDisplay(width: Int = 1280, height: Int = 720, dpi: Int = 240): Int {
        return when (_state.value.config.bridgeMode) {
            StageBridgeMode.SHIZUKU -> startDisplayShizuku(width, height, dpi)
            StageBridgeMode.LOCAL_WIRELESS_ADB -> {
                startDisplayLocalAdb()
                0
            }
        }
    }

    fun attachCarSurface(context: Context, surface: Surface, width: Int, height: Int, dpi: Int) {
        init(context)
        // Start every Android Auto session fresh on 50:50 dual app pickers (no forced stale apps)
        _state.update { st ->
            st.copy(
                activePackage = null,
                config = st.config.copy(
                    layoutPreset = StageLayoutPreset.SPLIT_50_50,
                    primaryPackage = "",
                    secondaryPackage = ""
                )
            )
        }
        carSurface = surface
        carWidth = width.coerceIn(480, 3840)
        carHeight = height.coerceIn(320, 2160)
        carDpi = dpi.coerceIn(120, 640)
        appendLog("Android Auto surface attached (${carWidth}x${carHeight} @ ${carDpi}dpi)")

        if (!_state.value.isBound && !_state.value.isBinding) {
            connect(context)
            return
        }
        if (_state.value.config.bridgeMode == StageBridgeMode.SHIZUKU && _state.value.isBound) {
            startDisplayShizuku(carWidth, carHeight, carDpi)
        }
    }

    fun updateVisibleArea(visibleArea: Rect) {
        splitCompositor.updateVisibleArea(visibleArea)
    }

    fun detachCarSurface() {
        carSurface = null
        splitCompositor.stop()
        runCatching { shizukuService?.stopStageDisplay() }
        _state.update { it.copy(activeDisplayId = -1, engineMode = "STOPPED") }
        appendLog("Android Auto surface detached")
    }

    fun sendBackKey() {
        when (_state.value.config.bridgeMode) {
            StageBridgeMode.SHIZUKU -> runCatching { shizukuService?.sendBackKey() }
            StageBridgeMode.LOCAL_WIRELESS_ADB -> {
                val id = _state.value.activeDisplayId.coerceAtLeast(0)
                scope.launch { localAdb.execShell("input -d $id keyevent 4") }
            }
        }
    }

    /**
     * Force-stops and cleanly relaunches the active app(s) on Car Stage to recover from black screens.
     */
    fun restartActiveApps() {
        val cfg = _state.value.config
        val primaryPkg = _state.value.activePackage ?: cfg.primaryPackage
        val secondaryPkg = cfg.secondaryPackage
        scope.launch {
            when (cfg.bridgeMode) {
                StageBridgeMode.SHIZUKU -> {
                    val svc = shizukuService ?: return@launch
                    // Re-initialize display surface first in case it went stale/black
                    if (carSurface?.isValid == true) {
                        startDisplayShizuku(carWidth, carHeight, carDpi)
                    }
                    if (primaryPkg.isNotBlank()) {
                        val ok = runCatching { svc.restartAppOnDisplay(primaryPkg, false) }.getOrDefault(false)
                        appendLog("Restarted App 1 ($primaryPkg) -> $ok")
                    }
                    if (cfg.layoutPreset.isSplit && secondaryPkg.isNotBlank() && !splitCompositor.showRightPicker) {
                        val ok2 = runCatching { svc.restartAppOnDisplay(secondaryPkg, true) }.getOrDefault(false)
                        appendLog("Restarted App 2 ($secondaryPkg) -> $ok2")
                    }
                }
                StageBridgeMode.LOCAL_WIRELESS_ADB -> {
                    if (primaryPkg.isNotBlank()) {
                        localAdb.execShell("am force-stop $primaryPkg")
                        launchOrMoveLocalAdb(primaryPkg)
                        appendLog("Restarted $primaryPkg via Local ADB")
                    }
                }
            }
        }
    }

    fun injectTap(x: Float, y: Float) {
        if (scrollActive) finishScrollGesture()
        when (_state.value.config.bridgeMode) {
            StageBridgeMode.SHIZUKU -> {
                val bounds = splitCompositor.currentBounds
                val sec = bounds?.secondary
                if (bounds != null && sec != null) {
                    // 0. If ratio dropdown menu is open, handle selection or dismiss
                    if (splitCompositor.showRatioMenu) {
                        val pickedPreset = splitCompositor.hitTestRatioMenu(x, y)
                        splitCompositor.setRatioMenu(false)
                        val ctx = appContext
                        if (pickedPreset != null && ctx != null) {
                            applySplitLayout(ctx, pickedPreset)
                        }
                        return
                    }
                    // 1. Tapping center seam handle toggles ratio dropdown AND arms live horizontal drag
                    if (splitCompositor.hitTestSeamHandle(x, y)) {
                        seamDragArmed = true
                        splitCompositor.setRatioMenu(!splitCompositor.showRatioMenu)
                        return
                    }
                    // 2. If left-slot in-surface picker is open, check which app tile was tapped
                    if (splitCompositor.showLeftPicker && x < sec.left) {
                        lastTouchIsSecondary = false
                        val pickedPkg = splitCompositor.hitTestLeftPickerApp(x, y)
                        val ctx = appContext
                        if (pickedPkg != null && ctx != null) {
                            selectPrimaryApp(ctx, pickedPkg)
                        }
                        return
                    }
                    // 3. If right-slot in-surface picker is open, check which app tile was tapped
                    if (splitCompositor.showRightPicker && x >= sec.left) {
                        lastTouchIsSecondary = true
                        val pickedPkg = splitCompositor.hitTestRightPickerApp(x, y)
                        val ctx = appContext
                        if (pickedPkg != null && ctx != null) {
                            selectSecondaryApp(ctx, pickedPkg)
                        }
                        return
                    }
                }
                if (bounds != null && sec != null && x >= sec.left) {
                    seamDragArmed = false
                    lastTouchIsSecondary = true
                    val (mx, my) = splitCompositor.mapTouchToSecondary(x, y)
                    runCatching { shizukuService?.injectTapOnSecondary(mx, my) }
                } else {
                    seamDragArmed = false
                    lastTouchIsSecondary = false
                    val (mx, my) = if (bounds != null) {
                        splitCompositor.mapTouchToPrimary(x, y)
                    } else {
                        Pair(x, y)
                    }
                    runCatching { shizukuService?.injectTap(mx, my) }
                }
            }
            StageBridgeMode.LOCAL_WIRELESS_ADB -> {
                val id = _state.value.activeDisplayId.coerceAtLeast(0)
                scope.launch { localAdb.execShell("input -d $id tap ${x.toInt()} ${y.toInt()}") }
            }
        }
    }

    /**
     * Translates Android Auto SurfaceCallback.onScroll(distanceX, distanceY) into a continuous
     * ACTION_DOWN -> ACTION_MOVE -> ACTION_UP gesture on the active pane.
     */
    fun handleScroll(distanceX: Float, distanceY: Float) {
        // 1. Live horizontal drag of the center seam to freely customize split ratio (30%..75%)
        if (seamDragArmed && kotlin.math.abs(distanceX) >= kotlin.math.abs(distanceY)) {
            touchHandler.removeCallbacks(endSeamDragRunnable)
            splitCompositor.adjustRatioByDelta(-distanceX)
            touchHandler.postDelayed(endSeamDragRunnable, 220L)
            return
        }
        // 2. Scroll left or right in-surface app picker grids smoothly
        if (splitCompositor.showLeftPicker && !lastTouchIsSecondary) {
            splitCompositor.scrollLeftPicker(distanceY)
            return
        }
        if (splitCompositor.showRightPicker && lastTouchIsSecondary) {
            splitCompositor.scrollRightPicker(distanceY)
            return
        }
        val svc = shizukuService ?: return
        if (_state.value.config.bridgeMode != StageBridgeMode.SHIZUKU) return

        val bounds = splitCompositor.currentBounds
        val sec = bounds?.secondary
        val useSecondary = sec != null && lastTouchIsSecondary
        val paneW = (if (useSecondary) sec!!.width() else bounds?.primary?.width() ?: carWidth).toFloat()
        val paneH = (if (useSecondary) sec!!.height() else bounds?.primary?.height() ?: carHeight).toFloat()

        touchHandler.removeCallbacks(endScrollRunnable)

        if (!scrollActive) {
            scrollActive = true
            scrollIsSecondary = useSecondary
            scrollDownTime = SystemClock.uptimeMillis()
            scrollCurX = paneW * 0.5f
            scrollCurY = paneH * 0.5f
            runCatching {
                svc.injectMotion(MotionEvent.ACTION_DOWN, scrollCurX, scrollCurY, scrollDownTime, scrollIsSecondary)
            }
        }

        // GestureDetector.onScroll passes (prev - current), so finger motion is (-distanceX, -distanceY)
        scrollCurX = (scrollCurX - distanceX).coerceIn(8f, (paneW - 8f).coerceAtLeast(16f))
        scrollCurY = (scrollCurY - distanceY).coerceIn(8f, (paneH - 8f).coerceAtLeast(16f))
        runCatching {
            svc.injectMotion(MotionEvent.ACTION_MOVE, scrollCurX, scrollCurY, scrollDownTime, scrollIsSecondary)
        }

        touchHandler.postDelayed(endScrollRunnable, 120L)
    }

    /**
     * Translates Android Auto SurfaceCallback.onScale(focusX, focusY, scaleFactor) into a
     * throttled, non-overlapping 2-finger pinch zoom gesture on the targeted pane only.
     * Disarms seam dragging so pinching NEVER resizes the split-screen divider.
     */
    fun handleScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        // Always cancel any seam-drag state so 2-finger pinch never resizes the 2 apps
        if (seamDragArmed || splitCompositor.isDraggingSeam) {
            seamDragArmed = false
            touchHandler.removeCallbacks(endSeamDragRunnable)
            splitCompositor.setDraggingSeam(false)
        }
        val svc = shizukuService ?: return
        if (_state.value.config.bridgeMode != StageBridgeMode.SHIZUKU) return
        if (scrollActive) finishScrollGesture()

        val bounds = splitCompositor.currentBounds
        val sec = bounds?.secondary
        val isSecondary = bounds != null && sec != null && focusX >= sec.left
        if (isSecondary && splitCompositor.showRightPicker) return
        if (!isSecondary && splitCompositor.showLeftPicker) return

        pinchAccumScale = (pinchAccumScale * scaleFactor.coerceIn(0.75f, 1.35f)).coerceIn(0.45f, 2.2f)
        val now = SystemClock.uptimeMillis()
        if (now - lastPinchInjectTime < 180L || kotlin.math.abs(pinchAccumScale - 1f) < 0.08f) {
            return
        }
        val commitScale = pinchAccumScale
        pinchAccumScale = 1f
        lastPinchInjectTime = now

        val (mx, my) = when {
            isSecondary -> splitCompositor.mapTouchToSecondary(focusX, focusY)
            bounds != null -> splitCompositor.mapTouchToPrimary(focusX, focusY)
            else -> Pair(focusX, focusY)
        }
        scope.launch {
            runCatching { svc.injectPinchZoom(mx, my, commitScale, isSecondary) }
        }
    }

    private fun finishSeamDrag() {
        seamDragArmed = false
        splitCompositor.setDraggingSeam(false)
        val ratio = splitCompositor.currentPrimaryRatio.coerceIn(0.30f, 0.75f)
        appendLog("Custom split ratio committed: ${(ratio * 100).toInt()}:${((1f - ratio) * 100).toInt()}")
        commitCustomSplitRatio(ratio)
    }

    /**
     * Cleanly resizes both VirtualDisplays and ImageReaders with adaptive DPI
     * to match the user's custom dragged ratio so apps re-layout sharply without distortion.
     */
    private fun commitCustomSplitRatio(ratio: Float) {
        val svc = shizukuService ?: return
        val targetSurface = carSurface?.takeIf { it.isValid } ?: return
        val cfg = _state.value.config
        if (_state.value.config.bridgeMode != StageBridgeMode.SHIZUKU || !cfg.layoutPreset.isSplit) return

        val seamPx = 4
        val leftW = ((carWidth - seamPx) * ratio).toInt().coerceIn(240, carWidth - 240)
        val customBounds = StageSlotBounds(
            primary = Rect(0, 0, leftW, carHeight),
            secondary = Rect(leftW + seamPx, 0, carWidth, carHeight)
        )
        val secRect = customBounds.secondary!!
        val primaryDpi = CarStageLayoutEngine.computeSlotDpi(leftW, carHeight, carDpi, isSplit = true)
        val secondaryDpi = CarStageLayoutEngine.computeSlotDpi(secRect.width(), carHeight, carDpi, isSplit = true)

        val wasShowingPicker = splitCompositor.showRightPicker
        splitCompositor.start(targetSurface, customBounds, ratio)
        val primaryId = runCatching {
            svc.startStageDisplay(splitCompositor.primarySurface, leftW, carHeight, primaryDpi, cfg.preferVirtualDevice)
        }.getOrDefault(-1)
        val secId = runCatching {
            svc.startSecondaryDisplay(splitCompositor.secondarySurface, secRect.width(), carHeight, secondaryDpi)
        }.getOrDefault(-1)

        val primaryPkg = _state.value.activePackage ?: cfg.primaryPackage
        if (primaryId > 0 && primaryPkg.isNotBlank()) {
            runCatching { svc.launchOrMoveApp(primaryPkg, true) }
        }
        val ctx = appContext
        if (wasShowingPicker && ctx != null) {
            openRightInSurfacePicker(ctx)
        } else if (secId > 0 && cfg.secondaryPackage.isNotBlank()) {
            runCatching { svc.launchOrMoveSecondaryApp(cfg.secondaryPackage, true) }
        }
    }

    private fun finishScrollGesture() {
        if (!scrollActive) return
        scrollActive = false
        touchHandler.removeCallbacks(endScrollRunnable)
        runCatching {
            shizukuService?.injectMotion(
                MotionEvent.ACTION_UP,
                scrollCurX,
                scrollCurY,
                scrollDownTime,
                scrollIsSecondary
            )
        }
    }

    private fun startDisplayShizuku(width: Int, height: Int, dpi: Int): Int {
        val svc = shizukuService ?: return -1
        val cfg = _state.value.config
        val preferVd = cfg.preferVirtualDevice
        val targetSurface = carSurface?.takeIf { it.isValid }
        val fullW = if (targetSurface != null) carWidth else width
        val fullH = if (targetSurface != null) carHeight else height
        val d = if (targetSurface != null) carDpi else dpi

        return try {
            val displayId = if (targetSurface != null && cfg.layoutPreset.isSplit) {
                val bounds = CarStageLayoutEngine.computeSlots(fullW, fullH, cfg.layoutPreset)
                val secRect = bounds.secondary!!
                val primaryDpi = CarStageLayoutEngine.computeSlotDpi(
                    bounds.primary.width(), bounds.primary.height(), d, isSplit = true
                )
                val secondaryDpi = CarStageLayoutEngine.computeSlotDpi(
                    secRect.width(), secRect.height(), d, isSplit = true
                )
                splitCompositor.start(targetSurface, bounds, cfg.layoutPreset.primaryRatio)
                val primaryId = svc.startStageDisplay(
                    splitCompositor.primarySurface,
                    bounds.primary.width(),
                    bounds.primary.height(),
                    primaryDpi,
                    preferVd
                )
                val secId = svc.startSecondaryDisplay(
                    splitCompositor.secondarySurface,
                    secRect.width(),
                    secRect.height(),
                    secondaryDpi
                )
                appendLog(
                    "Split compositor started: leftId=$primaryId (${bounds.primary.width()}x${bounds.primary.height()}@${primaryDpi}dpi), " +
                        "rightId=$secId (${secRect.width()}x${secRect.height()}@${secondaryDpi}dpi)"
                )
                // Always show left/right app picker grids whenever App 1 or App 2 is not chosen yet
                val ctx = appContext
                val primaryPkg = _state.value.activePackage ?: cfg.primaryPackage
                if (primaryPkg.isBlank() && ctx != null) {
                    openLeftInSurfacePicker(ctx)
                }
                if (cfg.secondaryPackage.isBlank() && ctx != null) {
                    openRightInSurfacePicker(ctx)
                } else if (cfg.secondaryPackage.isNotBlank()) {
                    val secOk = runCatching {
                        svc.launchOrMoveSecondaryApp(cfg.secondaryPackage, cfg.moveExistingTask)
                    }.getOrDefault(false)
                    if (!secOk && ctx != null) {
                        openRightInSurfacePicker(ctx)
                    }
                }
                primaryId
            } else {
                splitCompositor.stop()
                svc.stopSecondaryDisplay()
                val singleDpi = CarStageLayoutEngine.computeSlotDpi(fullW, fullH, d, isSplit = false)
                svc.startStageDisplay(targetSurface, fullW, fullH, singleDpi, preferVd)
            }
            val mode = svc.engineMode ?: "STOPPED"
            val err = svc.lastError ?: ""
            _state.update {
                it.copy(
                    activeDisplayId = displayId,
                    engineMode = mode,
                    lastError = err
                )
            }
            appendLog("startStageDisplay -> displayId=$displayId, mode=$mode")
            displayId
        } catch (t: Throwable) {
            _state.update { it.copy(lastError = t.message ?: "Start display failed") }
            appendLog("startDisplay error: ${t.message}")
            -1
        }
    }

    private fun startDisplayLocalAdb() {
        scope.launch {
            val out = localAdb.execShell("dumpsys display | grep -E 'mDisplayId=' | tail -n 1")
            out.onSuccess { text ->
                val parsedId = Regex("mDisplayId=(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                _state.update {
                    it.copy(
                        activeDisplayId = parsedId,
                        engineMode = "LOCAL_ADB",
                        lastError = ""
                    )
                }
                appendLog("Local ADB display ready (displayId=$parsedId)")
            }.onFailure { err ->
                _state.update { it.copy(lastError = err.message ?: "ADB shell failed") }
                appendLog("Local ADB display check error: ${err.message}")
            }
        }
    }

    fun launchOrMoveApp(packageName: String): Boolean {
        return when (_state.value.config.bridgeMode) {
            StageBridgeMode.SHIZUKU -> launchOrMoveShizuku(packageName)
            StageBridgeMode.LOCAL_WIRELESS_ADB -> {
                launchOrMoveLocalAdb(packageName)
                true
            }
        }
    }

    private fun launchOrMoveShizuku(packageName: String): Boolean {
        val svc = shizukuService ?: return false
        if (_state.value.activeDisplayId <= 0) {
            val id = startDisplayShizuku(1280, 720, 240)
            if (id <= 0) return false
        }
        return try {
            val moveTask = _state.value.config.moveExistingTask
            val ok = svc.launchOrMoveApp(packageName, moveTask)
            val err = if (ok) "" else (svc.lastError ?: "")
            _state.update {
                it.copy(
                    activePackage = if (ok) packageName else it.activePackage,
                    lastError = err
                )
            }
            appendLog("Shizuku launchOrMoveApp($packageName, moveTask=$moveTask) -> $ok")
            ok
        } catch (t: Throwable) {
            _state.update { it.copy(lastError = t.message ?: "Launch failed") }
            appendLog("launchOrMoveApp error: ${t.message}")
            false
        }
    }

    private fun launchOrMoveLocalAdb(packageName: String) {
        val displayId = _state.value.activeDisplayId.coerceAtLeast(0)
        scope.launch {
            val cmd = if (displayId > 0) {
                "monkey -p $packageName -c android.intent.category.LAUNCHER 1 && am display move-stack 1 $displayId 2>/dev/null || am start --display $displayId -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $packageName"
            } else {
                "monkey -p $packageName -c android.intent.category.LAUNCHER 1"
            }
            val res = localAdb.execShell(cmd)
            res.onSuccess {
                _state.update { it.copy(activePackage = packageName, lastError = "") }
                appendLog("Local ADB launched $packageName (displayId=$displayId)")
            }.onFailure { err ->
                _state.update { it.copy(lastError = err.message ?: "ADB launch failed") }
                appendLog("Local ADB launch error: ${err.message}")
            }
        }
    }

    private fun appendLog(line: String) {
        _state.update { st ->
            val next = (st.logs + line).takeLast(30)
            st.copy(logs = next)
        }
    }
}

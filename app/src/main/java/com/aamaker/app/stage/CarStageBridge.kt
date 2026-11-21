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
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import com.aamaker.app.i18n.AppLanguage
import com.aamaker.app.i18n.CarStageLocalization
import com.mag.anyauto.LsposedBridge
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
    val isPairingServiceRunning: Boolean = false,
    val isPaired: Boolean = false,
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

    private val splitCompositor = CarStageSplitCompositor().apply {
        setOnViewportSizeChangedListener { primaryVp, secondaryVp ->
            scheduleViewportResizeSync(primaryVp, secondaryVp)
        }
    }
    private val localAdb = LocalWirelessAdbClient()

    @Volatile
    private var pendingPrimaryVp: Rect? = null
    @Volatile
    private var pendingSecondaryVp: Rect? = null
    private val syncViewportResizeRunnable = Runnable { applyPendingViewportResize() }

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
    private val autoCollapseTopLeftRunnable = Runnable {
        if (splitCompositor.controlsExpanded) {
            splitCompositor.setControlsExpanded(false)
        }
    }

    private fun scheduleTopLeftAutoCollapse() {
        val delayMs = _state.value.config.autoHideSeconds.coerceIn(3, 10) * 1000L
        touchHandler.removeCallbacks(autoCollapseTopLeftRunnable)
        touchHandler.postDelayed(autoCollapseTopLeftRunnable, delayMs)
    }

    fun toggleTopLeftControls() {
        val next = !splitCompositor.controlsExpanded
        splitCompositor.setControlsExpanded(next)
        if (next) {
            scheduleTopLeftAutoCollapse()
        } else {
            touchHandler.removeCallbacks(autoCollapseTopLeftRunnable)
        }
    }

    private val clientBinderToken = Binder()
    @Volatile
    private var shizukuListenersRegistered = false
    private const val SHIZUKU_PERMISSION_REQ_CODE = 4201
    private const val USER_SERVICE_VERSION = 6

    @Volatile
    private var displayAttachedToCarSurface: Boolean = false
    private val serverSpawnLock = Any()
    private var compositorWakeLock: PowerManager.WakeLock? = null

    private fun acquireCompositorWakeLock(context: Context) {
        runCatching {
            var wl = compositorWakeLock
            if (wl == null) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                wl = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aamaker:CarStageCompositor")?.apply {
                    setReferenceCounted(false)
                }
                compositorWakeLock = wl
            }
            if (wl != null && !wl.isHeld) {
                wl.acquire()
            }
        }
    }

    private fun releaseCompositorWakeLock() {
        runCatching {
            if (compositorWakeLock?.isHeld == true) {
                compositorWakeLock?.release()
            }
        }
    }

    @Volatile
    private var pendingLocalAdbNonce: String? = null
    @Volatile
    private var pendingLocalAdbLatch: java.util.concurrent.CountDownLatch? = null

    fun onLocalAdbServerBinderRegistered(binder: IBinder, nonce: String?) {
        val expected = pendingLocalAdbNonce
        if (expected != null && nonce != expected) {
            return
        }
        val svc = ICarStageService.Stub.asInterface(binder) ?: return
        shizukuService = svc
        runCatching {
            binder.linkToDeath({
                if (shizukuService?.asBinder() === binder) {
                    shizukuService = null
                    displayAttachedToCarSurface = false
                    _state.update {
                        it.copy(
                            isBound = false,
                            isBinding = false,
                            activeDisplayId = -1,
                            engineMode = "STOPPED"
                        )
                    }
                    appendLog("Local Wireless ADB CarStageServer disconnected")
                }
            }, 0)
        }
        _state.update {
            it.copy(
                isBound = true,
                isBinding = false,
                isPaired = true,
                engineMode = "TRUSTED_DISPLAY",
                lastError = ""
            )
        }
        splitCompositor.setStatusBanner()
        appendLog("CarStageDisplayServer bound via Local Wireless ADB (UID 2000)")
        pendingLocalAdbLatch?.countDown()

        // Perform IPC setup & display creation asynchronously outside ContentProvider.call()
        // so the server's publish transaction returns immediately without re-entrant Binder deadlocks.
        scope.launch {
            runCatching { svc.registerClientToken(clientBinderToken) }
            runCatching { svc.setImeOnPhone(_state.value.config.imeOnPhone) }
            runCatching { svc.setKeepPlaybackAlive(_state.value.config.keepPlaybackAlive) }
            if (carSurface != null && carSurface?.isValid == true) {
                startDisplayShizuku(carWidth, carHeight, carDpi)
            }
        }
    }

    fun onPairingServiceStateChanged(isRunning: Boolean) {
        _state.update { it.copy(isPairingServiceRunning = isRunning) }
    }

    fun onMdnsConnectPortDiscovered(host: String, port: Int) {
        val ctx = appContext
        _state.update { st ->
            val nextCfg = st.config.copy(adbHost = host, adbPort = port)
            if (ctx != null) CarStagePrefs.save(ctx, nextCfg)
            st.copy(
                discoveredAdbPort = port,
                config = nextCfg
            )
        }
        appendLog("mDNS discovered connect port: $host:$port")
    }

    fun onMdnsPairingPortDiscovered(host: String, port: Int) {
        _state.update { it.copy(discoveredPairingPort = if (port > 0) port else null) }
        if (port > 0) {
            appendLog("mDNS discovered pairing port: $host:$port")
        }
    }

    fun startShizukuStylePairingService(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        runCatching {
            val intent = AdbPairingService.startIntent(appCtx)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                appCtx.startForegroundService(intent)
            } else {
                appCtx.startService(intent)
            }
            appendLog("Started Shizuku-style Wireless ADB Pairing notification service")
        }.onFailure { err ->
            appendLog("Pairing service start error: ${err.message}")
        }
    }

    fun stopShizukuStylePairingService(context: Context) {
        val appCtx = context.applicationContext
        runCatching {
            appCtx.startService(AdbPairingService.stopIntent(appCtx))
        }
        _state.update { it.copy(isPairingServiceRunning = false) }
    }

    fun pairWithCodeFromUi(context: Context, pairingPort: Int, pairingCode: String) {
        val appCtx = context.applicationContext
        appContext = appCtx
        val host = _state.value.config.adbHost.ifBlank { "127.0.0.1" }
        _state.update { it.copy(isBinding = true, lastError = "") }
        appendLog("Pairing Local Wireless ADB on $host:$pairingPort via SPAKE2...")
        scope.launch {
            pairAndConnectBlocking(
                context = appCtx,
                host = host,
                pairingPort = pairingPort,
                pairingCode = pairingCode,
                knownConnectPort = _state.value.discoveredAdbPort ?: _state.value.config.adbPort
            )
        }
    }

    internal fun pairAndConnectBlocking(
        context: Context,
        host: String,
        pairingPort: Int,
        pairingCode: String,
        knownConnectPort: Int
    ): Result<Boolean> {
        val appCtx = context.applicationContext
        appContext = appCtx
        val cleanHost = host.ifBlank { "127.0.0.1" }
        val pairRes = localAdb.pair(appCtx, cleanHost, pairingPort, pairingCode)
        return pairRes.fold(
            onSuccess = {
                _state.update { st ->
                    val nextMode = if (!isShizukuReady()) StageBridgeMode.LOCAL_WIRELESS_ADB else st.config.bridgeMode
                    val nextCfg = st.config.copy(bridgeMode = nextMode, adbHost = cleanHost)
                    CarStagePrefs.save(appCtx, nextCfg)
                    st.copy(isPaired = true, config = nextCfg, lastError = "")
                }
                appendLog("SPAKE2 + TLSv1.3 pairing succeeded on $cleanHost:$pairingPort")
                val targetConnectPort = _state.value.discoveredAdbPort
                    ?: knownConnectPort.takeIf { it > 0 && it != pairingPort }
                    ?: _state.value.config.adbPort
                connectLocalWirelessAdbBlocking(appCtx, cleanHost, targetConnectPort)
            },
            onFailure = { err ->
                val msg = err.message ?: "Wireless ADB pairing failed"
                _state.update { it.copy(isBinding = false, lastError = msg) }
                appendLog("Wireless ADB pairing failed: $msg")
                Result.failure(err)
            }
        )
    }

    private val shizukuConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) {
                _state.update { it.copy(isBinding = false, isBound = false) }
                return
            }
            val svc = ICarStageService.Stub.asInterface(binder)
            shizukuService = svc
            runCatching { svc.registerClientToken(clientBinderToken) }
            runCatching { svc.setImeOnPhone(_state.value.config.imeOnPhone) }
            runCatching { svc.setKeepPlaybackAlive(_state.value.config.keepPlaybackAlive) }
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
            splitCompositor.setStatusBanner()
            appendLog("Shizuku Car Stage connected (mode=$mode, displayId=$displayId)")
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
        val appCtx = context.applicationContext
        appContext = appCtx
        val loaded = CarStagePrefs.load(appCtx)
        _state.update { it.copy(config = loaded) }
        LsposedBridge.setContext(appCtx)
        registerShizukuListenersOnce(appCtx)
    }

    private fun registerShizukuListenersOnce(context: Context) {
        if (shizukuListenersRegistered) return
        shizukuListenersRegistered = true
        runCatching {
            Shizuku.addBinderReceivedListenerSticky({
                if (_state.value.config.bridgeMode == StageBridgeMode.SHIZUKU &&
                    !_state.value.isBound && !_state.value.isBinding
                ) {
                    if (carSurface?.isValid == true || isShizukuReady()) {
                        connect(context)
                    }
                }
            }, touchHandler)
            Shizuku.addBinderDeadListener({
                if (!LsposedBridge.isDirectModeAvailable(context)) {
                    shizukuService = null
                    _state.update {
                        it.copy(
                            isBound = false,
                            isBinding = false,
                            activeDisplayId = -1,
                            engineMode = "STOPPED"
                        )
                    }
                }
            }, touchHandler)
            Shizuku.addRequestPermissionResultListener({ code, grantResult ->
                if (code == SHIZUKU_PERMISSION_REQ_CODE && grantResult == PackageManager.PERMISSION_GRANTED) {
                    connect(context)
                }
            }, touchHandler)
        }
    }

    fun updateConfig(context: Context, transform: (CarStageConfig) -> CarStageConfig) {
        val prevMode = _state.value.config.bridgeMode
        val prevIme = _state.value.config.imeOnPhone
        val prevKeepAlive = _state.value.config.keepPlaybackAlive
        val updated = transform(_state.value.config)
        CarStagePrefs.save(context, updated)
        _state.update { it.copy(config = updated) }
        if (prevIme != updated.imeOnPhone) {
            runCatching { shizukuService?.setImeOnPhone(updated.imeOnPhone) }
        }
        if (prevKeepAlive != updated.keepPlaybackAlive) {
            runCatching { shizukuService?.setKeepPlaybackAlive(updated.keepPlaybackAlive) }
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
        if (_state.value.isBound && shizukuService != null && cfg.layoutPreset.isSplit) {
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
        val appCtx = context.applicationContext
        appContext = appCtx
        val currentPrimary = _state.value.activePackage
            ?: _state.value.config.primaryPackage.ifBlank { _state.value.config.pinnedPackages.firstOrNull().orEmpty() }
        updateConfig(appCtx) { cfg ->
            cfg.copy(
                layoutPreset = preset,
                primaryPackage = currentPrimary
            )
        }
        val targetSurface = carSurface?.takeIf { it.isValid }
        val viewports = if (targetSurface != null) {
            splitCompositor.reconfigureLayoutInPlace(split = preset.isSplit, ratio = preset.primaryRatio)
        } else {
            null
        }

        if (targetSurface != null && viewports == null) {
            val bounds = CarStageLayoutEngine.computeSlots(carWidth, carHeight, preset)
            splitCompositor.start(
                carSurface = targetSurface,
                slotBounds = bounds,
                ratio = preset.primaryRatio,
                fullWidth = carWidth,
                fullHeight = carHeight,
                preservePickers = true
            )
        }

        val pVp = viewports?.first ?: CarStageLayoutEngine.computePrimaryViewport(
            CarStageLayoutEngine.computeSlots(carWidth, carHeight, preset)
        )
        val sVp = viewports?.second
        appendLog(
            "Applied split ${preset.key}: left=${pVp.width()}x${pVp.height()}" +
                (sVp?.let { ", right=${it.width()}x${it.height()}" } ?: "")
        )

        if (targetSurface != null) {
            if (currentPrimary.isBlank()) {
                openLeftInSurfacePicker(appCtx)
            }
            if (preset.isSplit && _state.value.config.secondaryPackage.isBlank()) {
                openRightInSurfacePicker(appCtx)
            }
        }
        if (_state.value.isBound && shizukuService != null) {
            scope.launch {
                val svc = shizukuService ?: return@launch
                if (displayAttachedToCarSurface && _state.value.activeDisplayId > 0 && targetSurface != null) {
                    val isSplitNow = sVp != null
                    val pDpi = CarStageLayoutEngine.computeSlotDpi(pVp.width(), pVp.height(), carDpi, isSplit = isSplitNow)
                    val resizedPrimary = runCatching {
                        svc.resizeStageDisplay(pVp.width(), pVp.height(), pDpi)
                    }.getOrDefault(false)
                    if (!resizedPrimary) {
                        startDisplayShizuku(carWidth, carHeight, carDpi)
                        return@launch
                    }
                    if (sVp != null) {
                        val sDpi = CarStageLayoutEngine.computeSlotDpi(sVp.width(), sVp.height(), carDpi, isSplit = true)
                        val resizedSec = runCatching {
                            svc.resizeSecondaryDisplay(sVp.width(), sVp.height(), sDpi)
                        }.getOrDefault(false)
                        if (!resizedSec) {
                            val secId = runCatching {
                                svc.startSecondaryDisplay(splitCompositor.secondarySurface, sVp.width(), sVp.height(), sDpi)
                            }.getOrDefault(-1)
                            val secPkg = _state.value.config.secondaryPackage
                            if (secId > 0 && secPkg.isNotBlank() && !splitCompositor.showRightPicker) {
                                runCatching { svc.launchOrMoveSecondaryApp(secPkg, _state.value.config.moveExistingTask) }
                            }
                        }
                    } else {
                        runCatching { svc.stopSecondaryDisplay() }
                    }
                } else {
                    startDisplayShizuku(carWidth, carHeight, carDpi)
                    if (currentPrimary.isNotBlank()) {
                        launchOrMoveShizuku(currentPrimary)
                    }
                }
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
        val appCtx = context.applicationContext
        appContext = appCtx
        updateConfig(appCtx) { cfg -> cfg.copy(primaryPackage = packageName) }
        splitCompositor.setLeftPicker(show = false)
        val strings = CarStageLocalization.of(AppLanguage.loadSavedLanguage(appCtx))
        splitCompositor.setStatusBanner(strings.statusConnecting, packageName)
        appendLog("Selected 1st split app (left): $packageName")

        scope.launch {
            val svc = ensureBoundServiceBlocking(appCtx)
            if (svc == null) {
                openLeftInSurfacePicker(appCtx)
                return@launch
            }
            if (carSurface?.isValid == true && (_state.value.activeDisplayId <= 0 || !displayAttachedToCarSurface)) {
                startDisplayShizuku(carWidth, carHeight, carDpi)
            }
            val ok = launchOrMoveShizuku(packageName)
            if (ok) {
                splitCompositor.setStatusBanner()
            } else {
                openLeftInSurfacePicker(appCtx)
            }
        }
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
        val appCtx = context.applicationContext
        appContext = appCtx
        updateConfig(appCtx) { cfg -> cfg.copy(secondaryPackage = packageName) }
        splitCompositor.setRightPicker(show = false)
        val strings = CarStageLocalization.of(AppLanguage.loadSavedLanguage(appCtx))
        splitCompositor.setStatusBanner(strings.statusConnecting, packageName)
        appendLog("Selected 2nd split app (right): $packageName")

        scope.launch {
            val svc = ensureBoundServiceBlocking(appCtx)
            if (svc == null) {
                openRightInSurfacePicker(appCtx)
                return@launch
            }
            if (carSurface?.isValid == true && (_state.value.activeDisplayId <= 0 || !displayAttachedToCarSurface)) {
                startDisplayShizuku(carWidth, carHeight, carDpi)
            }
            val moveTask = _state.value.config.moveExistingTask
            var ok = runCatching { svc.launchOrMoveSecondaryApp(packageName, moveTask) }.getOrDefault(false)
            if (!ok && carSurface?.isValid == true) {
                startDisplayShizuku(carWidth, carHeight, carDpi)
                ok = runCatching { svc.launchOrMoveSecondaryApp(packageName, moveTask) }.getOrDefault(false)
            }
            appendLog("launchOrMoveSecondaryApp($packageName) -> $ok")
            if (ok) {
                splitCompositor.setStatusBanner()
            } else {
                openRightInSurfacePicker(appCtx)
            }
        }
    }

    private fun ensureBoundServiceBlocking(context: Context): ICarStageService? {
        val appCtx = context.applicationContext
        appContext = appCtx

        shizukuService?.takeIf { runCatching { it.asBinder().isBinderAlive }.getOrDefault(false) }?.let {
            return it
        }

        // Wait if an async bind is already underway
        if (_state.value.isBinding) {
            val deadline = SystemClock.uptimeMillis() + 7000L
            while (_state.value.isBinding && SystemClock.uptimeMillis() < deadline) {
                shizukuService?.takeIf { runCatching { it.asBinder().isBinderAlive }.getOrDefault(false) }?.let {
                    return it
                }
                runCatching { Thread.sleep(100L) }
            }
            shizukuService?.takeIf { runCatching { it.asBinder().isBinderAlive }.getOrDefault(false) }?.let {
                return it
            }
        }

        val preferLocalAdb = _state.value.config.bridgeMode == StageBridgeMode.LOCAL_WIRELESS_ADB ||
            (!isShizukuReady() && (localAdb.isConnected(appCtx) || localAdb.hasPairedKeys(appCtx)))

        if (preferLocalAdb) {
            val host = _state.value.config.adbHost.ifBlank { "127.0.0.1" }
            val port = _state.value.discoveredAdbPort ?: _state.value.config.adbPort
            _state.update { it.copy(isBinding = true, lastError = "") }
            connectLocalWirelessAdbBlocking(appCtx, host, port)
            return shizukuService?.takeIf { runCatching { it.asBinder().isBinderAlive }.getOrDefault(false) }
        }

        connectShizuku(appCtx)
        val deadline = SystemClock.uptimeMillis() + 5000L
        while (_state.value.isBinding && SystemClock.uptimeMillis() < deadline) {
            shizukuService?.takeIf { runCatching { it.asBinder().isBinderAlive }.getOrDefault(false) }?.let {
                return it
            }
            runCatching { Thread.sleep(100L) }
        }
        return shizukuService?.takeIf { runCatching { it.asBinder().isBinderAlive }.getOrDefault(false) }
    }

    fun isShizukuReady(): Boolean {
        val ctx = appContext
        if (ctx != null && LsposedBridge.isDirectModeAvailable(ctx)) {
            return true
        }
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
        val appCtx = context.applicationContext
        appContext = appCtx
        if (_state.value.isBound || _state.value.isBinding) return
        when (_state.value.config.bridgeMode) {
            StageBridgeMode.SHIZUKU -> connectShizuku(appCtx)
            StageBridgeMode.LOCAL_WIRELESS_ADB -> connectLocalWirelessAdb(appCtx)
        }
    }

    private fun connectShizuku(context: Context) {
        val appCtx = context.applicationContext
        val strings = CarStageLocalization.of(AppLanguage.loadSavedLanguage(appCtx))

        // 1. Direct in-process mode when Root + LSPosed system_server hook is active
        if (LsposedBridge.isDirectModeAvailable(appCtx)) {
            val directSvc = CarStageDisplayServer(appCtx, inProcess = true)
            shizukuService = directSvc
            runCatching { directSvc.setImeOnPhone(_state.value.config.imeOnPhone) }
            runCatching { directSvc.setKeepPlaybackAlive(_state.value.config.keepPlaybackAlive) }
            _state.update {
                it.copy(
                    isBound = true,
                    isBinding = false,
                    engineMode = "TRUSTED_DISPLAY",
                    lastError = ""
                )
            }
            splitCompositor.setStatusBanner()
            appendLog("Car Stage connected via Direct VirtualDisplay mode")
            if (carSurface?.isValid == true) {
                startDisplayShizuku(carWidth, carHeight, carDpi)
            }
            return
        }

        // 2. Standard Shizuku mode (with automatic fallback to paired Local Wireless ADB if Shizuku is not running)
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!binderAlive) {
            if (localAdb.isConnected(appCtx) || localAdb.hasPairedKeys(appCtx)) {
                appendLog("Shizuku not running; falling back to paired Local Wireless ADB...")
                connectLocalWirelessAdb(appCtx)
                return
            }
            _state.update { it.copy(lastError = "Shizuku is not running") }
            splitCompositor.setStatusBanner(strings.statusDisconnected, strings.btnGrantShizuku)
            appendLog("Connect waiting: Shizuku binder not ready yet")
            return
        }

        val permGranted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!permGranted) {
            if (localAdb.isConnected(appCtx) || localAdb.hasPairedKeys(appCtx)) {
                appendLog("Shizuku permission not granted; falling back to paired Local Wireless ADB...")
                connectLocalWirelessAdb(appCtx)
                return
            }
            _state.update { it.copy(lastError = "Shizuku permission not granted") }
            splitCompositor.setStatusBanner(strings.btnGrantShizuku, strings.statusDisconnected)
            runCatching { Shizuku.requestPermission(SHIZUKU_PERMISSION_REQ_CODE) }
            appendLog("Requested Shizuku permission for Car Stage")
            return
        }

        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(appCtx.packageName, CarStageDisplayServer::class.java.name)
            )
                .daemon(false)
                .processNameSuffix("car_stage")
                .debuggable(false)
                .version(USER_SERVICE_VERSION)
            userServiceArgs = args
            _state.update { it.copy(isBinding = true, lastError = "") }
            splitCompositor.setStatusBanner(strings.statusConnecting, strings.bridgeModeShizuku)
            appendLog("Binding CarStageDisplayServer (v$USER_SERVICE_VERSION) via Shizuku...")
            Shizuku.bindUserService(args, shizukuConnection)
        } catch (t: Throwable) {
            _state.update { it.copy(isBinding = false, lastError = t.message ?: "Bind failed") }
            splitCompositor.setStatusBanner(strings.statusDisconnected, t.message ?: "")
            appendLog("Bind failed: ${t.message}")
        }
    }

    private fun connectLocalWirelessAdb(context: Context) {
        val appCtx = context.applicationContext
        val strings = CarStageLocalization.of(AppLanguage.loadSavedLanguage(appCtx))
        val host = _state.value.config.adbHost.ifBlank { "127.0.0.1" }
        val port = _state.value.discoveredAdbPort ?: _state.value.config.adbPort
        _state.update { it.copy(isBinding = true, lastError = "") }
        splitCompositor.setStatusBanner(strings.statusConnecting, strings.bridgeModeLocalAdb)
        appendLog("Connecting Local Wireless ADB to $host:$port...")

        scope.launch {
            connectLocalWirelessAdbBlocking(appCtx, host, port)
        }
    }

    internal fun connectLocalWirelessAdbBlocking(
        context: Context,
        host: String,
        port: Int
    ): Result<Boolean> {
        val appCtx = context.applicationContext
        appContext = appCtx
        val res = localAdb.connect(appCtx, host, port)
        return res.fold(
            onSuccess = { connectedPort ->
                _state.update { st ->
                    val nextCfg = st.config.copy(adbHost = host, adbPort = connectedPort)
                    CarStagePrefs.save(appCtx, nextCfg)
                    st.copy(
                        isPaired = true,
                        discoveredAdbPort = connectedPort,
                        config = nextCfg,
                        lastError = ""
                    )
                }
                appendLog("Local Wireless ADB connected on $host:$connectedPort. Starting UID 2000 servers...")
                val spawned = spawnCarStageServerViaLocalAdb(appCtx)
                // Warm up Dual-Window privileged server in the background as well
                scope.launch {
                    runCatching { com.aamaker.app.input.PrivilegedServerCoordinator.getOrStartServer(appCtx) }
                }
                if (spawned) {
                    Result.success(true)
                } else {
                    val errMsg = "Failed to bind CarStageDisplayServer (UID 2000) via Local Wireless ADB"
                    val strings = CarStageLocalization.of(AppLanguage.loadSavedLanguage(appCtx))
                    _state.update {
                        it.copy(
                            isBound = false,
                            isBinding = false,
                            engineMode = "STOPPED",
                            lastError = errMsg
                        )
                    }
                    splitCompositor.setStatusBanner(strings.statusDisconnected, errMsg)
                    appendLog(errMsg)
                    Result.failure(IllegalStateException(errMsg))
                }
            },
            onFailure = { err ->
                val strings = CarStageLocalization.of(AppLanguage.loadSavedLanguage(appCtx))
                val msg = err.message ?: "ADB connection failed"
                _state.update {
                    it.copy(
                        isBound = false,
                        isBinding = false,
                        engineMode = "STOPPED",
                        lastError = msg
                    )
                }
                splitCompositor.setStatusBanner(strings.statusDisconnected, msg)
                appendLog("Local ADB failed: $msg")
                Result.failure(err)
            }
        )
    }

    private fun spawnCarStageServerViaLocalAdb(context: Context): Boolean {
        synchronized(serverSpawnLock) {
            val existing = shizukuService
            if (existing != null && runCatching { existing.asBinder().isBinderAlive }.getOrDefault(false)) {
                return true
            }
            val apkPath = context.applicationInfo.sourceDir
            if (apkPath.isNullOrBlank()) return false

            val nonceBytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(nonceBytes)
            val nonce = nonceBytes.joinToString("") { "%02x".format(it) }
            val latch = java.util.concurrent.CountDownLatch(1)
            pendingLocalAdbNonce = nonce
            pendingLocalAdbLatch = latch

            val serverCls = CarStageDisplayServer::class.java.name
            // Bracketed pattern `[a]amaker_car_stage` + `--nice-name=aamaker_car_stage` + `setsid` ensures pkill
            // terminates only previous server instances, NEVER matches wrapper shells, and detaches from adbd session.
            val cmd = "pkill -f '[a]amaker_car_stage' >/dev/null 2>&1; " +
                "if command -v setsid >/dev/null 2>&1; then " +
                "CLASSPATH='$apkPath' setsid /system/bin/app_process /system/bin --nice-name=aamaker_car_stage $serverCls '$nonce' </dev/null >/dev/null 2>&1 & " +
                "else " +
                "CLASSPATH='$apkPath' /system/bin/app_process /system/bin --nice-name=aamaker_car_stage $serverCls '$nonce' </dev/null >/dev/null 2>&1 & " +
                "fi"
            val submitted = localAdb.execBackgroundShell(context, cmd)
            if (!submitted) {
                pendingLocalAdbNonce = null
                pendingLocalAdbLatch = null
                return false
            }
            try {
                latch.await(8000L, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
            } finally {
                pendingLocalAdbNonce = null
                pendingLocalAdbLatch = null
            }
            return shizukuService?.let { runCatching { it.asBinder().isBinderAlive }.getOrDefault(false) } == true
        }
    }

    fun disconnect() {
        stopMdnsScan()
        releaseCompositorWakeLock()
        appContext?.let { stopShizukuStylePairingService(it) }
        runCatching { shizukuService?.stopStageDisplay() }
        runCatching { shizukuService?.destroy() }
        userServiceArgs?.let { args ->
            runCatching { Shizuku.unbindUserService(args, shizukuConnection, true) }
        }
        shizukuService = null
        displayAttachedToCarSurface = false
        localAdb.disconnect(appContext)

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
        if (shizukuService != null) {
            return startDisplayShizuku(width, height, dpi)
        }
        val ctx = appContext ?: return -1
        scope.launch {
            val svc = ensureBoundServiceBlocking(ctx)
            if (svc != null && carSurface?.isValid == true) {
                startDisplayShizuku(carWidth, carHeight, carDpi)
            }
        }
        return -1
    }

    fun attachCarSurface(context: Context, surface: Surface, width: Int, height: Int, dpi: Int) {
        val appCtx = context.applicationContext
        init(appCtx)
        acquireCompositorWakeLock(appCtx)
        val queuedFromPhone = _state.value.activePackage.orEmpty()
        // Start fresh on 50:50 dual app pickers unless user explicitly queued an app right before opening AA
        _state.update { st ->
            st.copy(
                activePackage = queuedFromPhone.ifBlank { null },
                config = st.config.copy(
                    layoutPreset = StageLayoutPreset.SPLIT_50_50,
                    primaryPackage = queuedFromPhone,
                    secondaryPackage = ""
                )
            )
        }
        carSurface = surface
        displayAttachedToCarSurface = false
        carWidth = width.coerceIn(480, 3840)
        carHeight = height.coerceIn(320, 2160)
        carDpi = dpi.coerceIn(120, 640)
        appendLog("Android Auto surface attached (${carWidth}x${carHeight} @ ${carDpi}dpi)")

        // Immediately start the GL compositor and render the dual in-surface app pickers
        // so the car screen NEVER shows a blank/black surface even before server binding finishes.
        val bounds = CarStageLayoutEngine.computeSlots(carWidth, carHeight, StageLayoutPreset.SPLIT_50_50)
        splitCompositor.start(
            carSurface = surface,
            slotBounds = bounds,
            ratio = StageLayoutPreset.SPLIT_50_50.primaryRatio,
            fullWidth = carWidth,
            fullHeight = carHeight,
            preservePickers = false
        )
        if (queuedFromPhone.isBlank()) {
            openLeftInSurfacePicker(appCtx)
        } else {
            splitCompositor.setLeftPicker(show = false)
        }
        openRightInSurfacePicker(appCtx)

        if (!_state.value.isBound && !_state.value.isBinding) {
            connect(appCtx)
            return
        }
        if (shizukuService != null && _state.value.isBound) {
            scope.launch {
                startDisplayShizuku(carWidth, carHeight, carDpi)
            }
        }
    }

    fun updateVisibleArea(visibleArea: Rect) {
        splitCompositor.updateVisibleArea(visibleArea)
    }

    fun updateStableArea(stableArea: Rect) {
        splitCompositor.updateStableArea(stableArea)
    }

    private fun scheduleViewportResizeSync(primaryVp: Rect, secondaryVp: Rect?) {
        pendingPrimaryVp = Rect(primaryVp)
        pendingSecondaryVp = secondaryVp?.let { Rect(it) }
        touchHandler.removeCallbacks(syncViewportResizeRunnable)
        touchHandler.postDelayed(syncViewportResizeRunnable, 140L)
    }

    private fun applyPendingViewportResize() {
        val pVp = pendingPrimaryVp ?: return
        val sVp = pendingSecondaryVp
        val svc = shizukuService ?: return
        if (!displayAttachedToCarSurface || _state.value.activeDisplayId <= 0) return
        if (carSurface?.isValid != true) return

        scope.launch {
            val isSplit = sVp != null
            val pDpi = CarStageLayoutEngine.computeSlotDpi(pVp.width(), pVp.height(), carDpi, isSplit = isSplit)
            val okP = runCatching { svc.resizeStageDisplay(pVp.width(), pVp.height(), pDpi) }.getOrDefault(false)
            if (sVp != null) {
                val sDpi = CarStageLayoutEngine.computeSlotDpi(sVp.width(), sVp.height(), carDpi, isSplit = true)
                val okS = runCatching { svc.resizeSecondaryDisplay(sVp.width(), sVp.height(), sDpi) }.getOrDefault(false)
                if (!okS && _state.value.config.layoutPreset.isSplit) {
                    val secId = runCatching {
                        svc.startSecondaryDisplay(splitCompositor.secondarySurface, sVp.width(), sVp.height(), sDpi)
                    }.getOrDefault(-1)
                    val secPkg = _state.value.config.secondaryPackage
                    if (secId > 0 && secPkg.isNotBlank() && !splitCompositor.showRightPicker) {
                        runCatching { svc.launchOrMoveSecondaryApp(secPkg, _state.value.config.moveExistingTask) }
                    }
                }
            } else {
                runCatching { svc.stopSecondaryDisplay() }
            }
            if (okP) {
                appendLog(
                    "Synced 1:1 stage viewport -> left=${pVp.width()}x${pVp.height()}@${pDpi}dpi" +
                        (sVp?.let { ", right=${it.width()}x${it.height()}" } ?: "")
                )
            }
        }
    }

    fun detachCarSurface() {
        touchHandler.removeCallbacks(autoCollapseTopLeftRunnable)
        touchHandler.removeCallbacks(syncViewportResizeRunnable)
        releaseCompositorWakeLock()
        carSurface = null
        displayAttachedToCarSurface = false
        splitCompositor.stop()
        runCatching { shizukuService?.stopStageDisplay() }
        _state.update { it.copy(activeDisplayId = -1, engineMode = "STOPPED") }
        appendLog("Android Auto surface detached")
    }

    fun sendBackKey() {
        val svc = shizukuService
        if (svc != null) {
            runCatching { svc.sendBackKey() }
            return
        }
        val id = _state.value.activeDisplayId
        if (id > 0 && _state.value.config.bridgeMode == StageBridgeMode.LOCAL_WIRELESS_ADB) {
            scope.launch { localAdb.execShell(appContext, "input -d $id keyevent 4") }
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
            val ctx = appContext
            val svc = shizukuService ?: (if (ctx != null) ensureBoundServiceBlocking(ctx) else null)
            if (svc != null) {
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
        }
    }

    fun injectTap(x: Float, y: Float) {
        if (scrollActive) finishScrollGesture()

        // 0. Always hit-test the fixed Top-Left control pill/bar first
        val topLeftAction = splitCompositor.hitTestTopLeftControl(x, y)
        if (topLeftAction != null) {
            val ctx = appContext
            when (topLeftAction) {
                "expand" -> {
                    splitCompositor.setControlsExpanded(true)
                    scheduleTopLeftAutoCollapse()
                }
                "collapse" -> {
                    touchHandler.removeCallbacks(autoCollapseTopLeftRunnable)
                    splitCompositor.setControlsExpanded(false)
                }
                "app1" -> {
                    touchHandler.removeCallbacks(autoCollapseTopLeftRunnable)
                    splitCompositor.setControlsExpanded(false)
                    if (ctx != null) openApp1Picker(ctx)
                }
                "app2" -> {
                    touchHandler.removeCallbacks(autoCollapseTopLeftRunnable)
                    splitCompositor.setControlsExpanded(false)
                    if (ctx != null) openApp2Split5050(ctx)
                }
                "restart" -> {
                    touchHandler.removeCallbacks(autoCollapseTopLeftRunnable)
                    splitCompositor.setControlsExpanded(false)
                    restartActiveApps()
                }
                "back" -> {
                    scheduleTopLeftAutoCollapse()
                    sendBackKey()
                }
            }
            return
        }

        if (splitCompositor.controlsExpanded) {
            touchHandler.removeCallbacks(autoCollapseTopLeftRunnable)
            splitCompositor.setControlsExpanded(false)
        }

        val bounds = splitCompositor.currentBounds
        val sec = bounds?.secondary

        // Handle in-surface UI (Ratio menu, Seam handle, Left/Right App Picker grids) regardless of bridge connection state
        if (bounds != null) {
            if (sec != null) {
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
            } else {
                // Single-pane mode with Left Picker open
                if (splitCompositor.showLeftPicker) {
                    lastTouchIsSecondary = false
                    val pickedPkg = splitCompositor.hitTestLeftPickerApp(x, y)
                    val ctx = appContext
                    if (pickedPkg != null && ctx != null) {
                        selectPrimaryApp(ctx, pickedPkg)
                    }
                    return
                }
            }
        }

        // If bridge is not connected yet, tapping the car surface retries connecting automatically
        if (!_state.value.isBound && !_state.value.isBinding) {
            appContext?.let { connect(it) }
        }

        val svc = shizukuService
        if (svc != null) {
            if (bounds != null && sec != null && x >= sec.left) {
                seamDragArmed = false
                lastTouchIsSecondary = true
                val (mx, my) = splitCompositor.mapTouchToSecondary(x, y)
                scope.launch {
                    runCatching { svc.injectTapOnSecondary(mx, my) }
                }
            } else {
                seamDragArmed = false
                lastTouchIsSecondary = false
                val (mx, my) = if (bounds != null) {
                    splitCompositor.mapTouchToPrimary(x, y)
                } else {
                    Pair(x, y)
                }
                scope.launch {
                    runCatching { svc.injectTap(mx, my) }
                }
            }
            return
        }

        val id = _state.value.activeDisplayId
        if (id > 0 && _state.value.config.bridgeMode == StageBridgeMode.LOCAL_WIRELESS_ADB) {
            scope.launch { localAdb.execShell(appContext, "input -d $id tap ${x.toInt()} ${y.toInt()}") }
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

        val bounds = splitCompositor.currentBounds
        val sec = bounds?.secondary
        val useSecondary = sec != null && lastTouchIsSecondary
        val (bufW, bufH) = if (useSecondary) {
            splitCompositor.currentSecondaryBufferSize
        } else {
            splitCompositor.currentPrimaryBufferSize
        }
        val paneW = bufW.coerceAtLeast(160).toFloat()
        val paneH = bufH.coerceAtLeast(160).toFloat()

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
     * Cleanly resizes both VirtualDisplays and SurfaceTexture buffers in-place with adaptive DPI
     * to match the user's custom dragged ratio at exact 1:1 pixel resolution so apps re-layout sharply without distortion.
     */
    private fun commitCustomSplitRatio(ratio: Float) {
        val targetSurface = carSurface?.takeIf { it.isValid } ?: return
        val cfg = _state.value.config
        if (!cfg.layoutPreset.isSplit) return

        val viewports = splitCompositor.reconfigureLayoutInPlace(split = true, ratio = ratio) ?: return
        val pVp = viewports.first
        val sVp = viewports.second
        val svc = shizukuService ?: return

        scope.launch {
            val isSplit = sVp != null
            val primaryDpi = CarStageLayoutEngine.computeSlotDpi(pVp.width(), pVp.height(), carDpi, isSplit = isSplit)
            val resizedPrimary = runCatching {
                svc.resizeStageDisplay(pVp.width(), pVp.height(), primaryDpi)
            }.getOrDefault(false)
            if (!resizedPrimary) {
                startDisplayShizuku(carWidth, carHeight, carDpi)
                return@launch
            }
            if (sVp != null) {
                val secondaryDpi = CarStageLayoutEngine.computeSlotDpi(sVp.width(), sVp.height(), carDpi, isSplit = true)
                val resizedSec = runCatching {
                    svc.resizeSecondaryDisplay(sVp.width(), sVp.height(), secondaryDpi)
                }.getOrDefault(false)
                if (!resizedSec) {
                    val secId = runCatching {
                        svc.startSecondaryDisplay(splitCompositor.secondarySurface, sVp.width(), sVp.height(), secondaryDpi)
                    }.getOrDefault(-1)
                    if (secId > 0 && cfg.secondaryPackage.isNotBlank() && !splitCompositor.showRightPicker) {
                        runCatching { svc.launchOrMoveSecondaryApp(cfg.secondaryPackage, true) }
                    }
                }
            }
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

    @Synchronized
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
                val rawBounds = CarStageLayoutEngine.computeSlots(fullW, fullH, cfg.layoutPreset)
                splitCompositor.start(
                    carSurface = targetSurface,
                    slotBounds = rawBounds,
                    ratio = cfg.layoutPreset.primaryRatio,
                    fullWidth = fullW,
                    fullHeight = fullH,
                    preservePickers = true
                )
                val (pBufW, pBufH) = splitCompositor.currentPrimaryBufferSize
                val (sBufW, sBufH) = splitCompositor.currentSecondaryBufferSize
                val effectiveSplit = splitCompositor.currentBounds?.secondary != null
                if (effectiveSplit) {
                    val primaryDpi = CarStageLayoutEngine.computeSlotDpi(pBufW, pBufH, d, isSplit = true)
                    val secondaryDpi = CarStageLayoutEngine.computeSlotDpi(sBufW, sBufH, d, isSplit = true)
                    val primaryId = svc.startStageDisplay(
                        splitCompositor.primarySurface,
                        pBufW,
                        pBufH,
                        primaryDpi,
                        preferVd
                    )
                    val secId = svc.startSecondaryDisplay(
                        splitCompositor.secondarySurface,
                        sBufW,
                        sBufH,
                        secondaryDpi
                    )
                    displayAttachedToCarSurface = primaryId > 0
                    appendLog(
                        "Split compositor started (1:1): leftId=$primaryId (${pBufW}x${pBufH}@${primaryDpi}dpi), " +
                            "rightId=$secId (${sBufW}x${sBufH}@${secondaryDpi}dpi)"
                    )
                    val ctx = appContext
                    val primaryPkg = _state.value.activePackage ?: cfg.primaryPackage
                    if (primaryPkg.isBlank() && ctx != null) {
                        openLeftInSurfacePicker(ctx)
                    } else if (primaryPkg.isNotBlank() && primaryId > 0) {
                        splitCompositor.setLeftPicker(show = false)
                        runCatching { svc.launchOrMoveApp(primaryPkg, cfg.moveExistingTask) }
                    }
                    if (cfg.secondaryPackage.isBlank() && ctx != null) {
                        openRightInSurfacePicker(ctx)
                    } else if (cfg.secondaryPackage.isNotBlank() && secId > 0) {
                        splitCompositor.setRightPicker(show = false)
                        val secOk = runCatching {
                            svc.launchOrMoveSecondaryApp(cfg.secondaryPackage, cfg.moveExistingTask)
                        }.getOrDefault(false)
                        if (!secOk && ctx != null) {
                            openRightInSurfacePicker(ctx)
                        }
                    }
                    primaryId
                } else {
                    svc.stopSecondaryDisplay()
                    val singleDpi = CarStageLayoutEngine.computeSlotDpi(pBufW, pBufH, d, isSplit = false)
                    val primaryId = svc.startStageDisplay(
                        splitCompositor.primarySurface,
                        pBufW,
                        pBufH,
                        singleDpi,
                        preferVd
                    )
                    displayAttachedToCarSurface = primaryId > 0
                    val ctx = appContext
                    val primaryPkg = _state.value.activePackage ?: cfg.primaryPackage
                    if (primaryPkg.isBlank() && ctx != null) {
                        openLeftInSurfacePicker(ctx)
                    } else if (primaryPkg.isNotBlank() && primaryId > 0) {
                        splitCompositor.setLeftPicker(show = false)
                        runCatching { svc.launchOrMoveApp(primaryPkg, cfg.moveExistingTask) }
                    }
                    primaryId
                }
            } else if (targetSurface != null) {
                svc.stopSecondaryDisplay()
                val rawBounds = CarStageLayoutEngine.computeSlots(fullW, fullH, StageLayoutPreset.SINGLE)
                splitCompositor.start(
                    carSurface = targetSurface,
                    slotBounds = rawBounds,
                    ratio = 1.0f,
                    fullWidth = fullW,
                    fullHeight = fullH,
                    preservePickers = true
                )
                val (pBufW, pBufH) = splitCompositor.currentPrimaryBufferSize
                val singleDpi = CarStageLayoutEngine.computeSlotDpi(pBufW, pBufH, d, isSplit = false)
                val primaryId = svc.startStageDisplay(
                    splitCompositor.primarySurface,
                    pBufW,
                    pBufH,
                    singleDpi,
                    preferVd
                )
                displayAttachedToCarSurface = primaryId > 0
                val ctx = appContext
                val primaryPkg = _state.value.activePackage ?: cfg.primaryPackage
                if (primaryPkg.isBlank() && ctx != null) {
                    openLeftInSurfacePicker(ctx)
                } else if (primaryPkg.isNotBlank() && primaryId > 0) {
                    splitCompositor.setLeftPicker(show = false)
                    runCatching { svc.launchOrMoveApp(primaryPkg, cfg.moveExistingTask) }
                }
                primaryId
            } else {
                displayAttachedToCarSurface = false
                splitCompositor.stop()
                svc.stopSecondaryDisplay()
                val singleDpi = CarStageLayoutEngine.computeSlotDpi(fullW, fullH, d, isSplit = false)
                svc.startStageDisplay(null, fullW, fullH, singleDpi, preferVd)
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

    fun launchOrMoveApp(packageName: String): Boolean {
        val ctx = appContext
        if (ctx != null) {
            updateConfig(ctx) { cfg -> cfg.copy(primaryPackage = packageName) }
        }
        splitCompositor.setLeftPicker(show = false)

        // Never open Car Stage apps on the phone's Display 0 when Android Auto is not attached yet;
        // queue the app so it projects onto Android Auto immediately upon CarStageCarAppService surface attach.
        if (carSurface == null || carSurface?.isValid != true) {
            _state.update { it.copy(activePackage = packageName, lastError = "") }
            appendLog("Queued $packageName — will project onto Android Auto when Car Stage opens on car screen")
            return true
        }

        val svc = shizukuService
        if (svc != null && _state.value.activeDisplayId > 0 && displayAttachedToCarSurface) {
            return launchOrMoveShizuku(packageName)
        }

        if (ctx != null) {
            scope.launch {
                val boundSvc = ensureBoundServiceBlocking(ctx)
                if (boundSvc == null) {
                    openLeftInSurfacePicker(ctx)
                    return@launch
                }
                if (carSurface?.isValid == true && (_state.value.activeDisplayId <= 0 || !displayAttachedToCarSurface)) {
                    startDisplayShizuku(carWidth, carHeight, carDpi)
                }
                val ok = launchOrMoveShizuku(packageName)
                if (ok) {
                    splitCompositor.setStatusBanner()
                } else {
                    openLeftInSurfacePicker(ctx)
                }
            }
            return true
        }
        return false
    }

    private fun launchOrMoveShizuku(packageName: String): Boolean {
        val svc = shizukuService ?: return false
        val hasCarSurface = carSurface?.isValid == true
        if (_state.value.activeDisplayId <= 0 || (hasCarSurface && !displayAttachedToCarSurface)) {
            val id = startDisplayShizuku(carWidth, carHeight, carDpi)
            if (id <= 0) return false
        }
        splitCompositor.setLeftPicker(show = false)
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
            appendLog("launchOrMoveApp($packageName, moveTask=$moveTask) -> $ok")
            ok
        } catch (t: Throwable) {
            _state.update { it.copy(lastError = t.message ?: "Launch failed") }
            appendLog("launchOrMoveApp error: ${t.message}")
            false
        }
    }

    private fun appendLog(line: String) {
        _state.update { st ->
            val next = (st.logs + line).takeLast(30)
            st.copy(logs = next)
        }
    }
}

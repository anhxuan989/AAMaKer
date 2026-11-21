package com.aamaker.app.input

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.view.Display
import android.view.Surface
import com.aamaker.core.patcher.security.BridgeAuthManager
import com.aamaker.core.patcher.security.PackageNameValidator
import com.mag.anyauto.Shell
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Synchronous IPC bridge ContentProvider (`content://com.aamaker.app.dualbridge`) allowing
 * patched CarAppService apps (verified by signing cert + per-install secret token) to:
 * 1. Create / resize a privileged `PUBLIC | TRUSTED` VirtualDisplay backed by their GPU Surface.
 * 2. Launch any installed secondary app onto that VirtualDisplay (`am start --display <id>`),
 *    optionally ignoring audio focus so both apps play audio simultaneously.
 * 3. Query installed launchable apps so the user can pick/switch the 2nd app right on the car screen.
 * 4. Inject sub-2ms MotionEvent / KeyEvent onto the secondary VirtualDisplay.
 */
class AAMakerDualDisplayProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context?.applicationContext ?: return null
        AAMakerCentralBridgeExecutor.setContext(ctx)

        if (method == METHOD_REGISTER_PRIVILEGED_SERVER) {
            val callingUid = Binder.getCallingUid()
            if (callingUid != 0 && callingUid != 2000) {
                return null
            }
            val serverBinder = extras?.getBinder("server_binder") ?: return null
            val nonce = extras.getString("handshake_nonce")
            PrivilegedServerCoordinator.onServerBinderRegistered(serverBinder, nonce)
            return Bundle().apply { putBoolean("ok", true) }
        }

        if (method == METHOD_REGISTER_CAR_STAGE_SERVER) {
            val callingUid = Binder.getCallingUid()
            if (callingUid != 0 && callingUid != 2000) {
                return null
            }
            val serverBinder = extras?.getBinder("server_binder") ?: return null
            val nonce = extras.getString("handshake_nonce")
            com.aamaker.app.stage.CarStageBridge.onLocalAdbServerBinderRegistered(serverBinder, nonce)
            return Bundle().apply { putBoolean("ok", true) }
        }

        if (method == METHOD_REGISTER_ANYAUTO_DISPLAY_SERVER) {
            val callingUid = Binder.getCallingUid()
            if (callingUid != 0 && callingUid != 2000) {
                return null
            }
            val serverBinder = extras?.getBinder("server_binder") ?: return null
            Shell.onLocalAdbDisplayBinderRegistered(serverBinder)
            return Bundle().apply { putBoolean("ok", true) }
        }

        // All client methods require cryptographic caller verification (local AAMaKer signing key + bridge token)
        val verifiedCallerPkg = verifyClientCaller(ctx, extras) ?: return Bundle().apply {
            putBoolean("ok", false)
            putString("error", "unauthorized")
        }

        return when (method) {
            METHOD_CREATE_SECONDARY_DISPLAY -> handleCreateSecondaryDisplay(ctx, verifiedCallerPkg, extras)
            METHOD_RELEASE_SECONDARY_DISPLAY -> handleReleaseSecondaryDisplay(verifiedCallerPkg, extras)
            METHOD_LAUNCH_SECONDARY_APP -> handleLaunchSecondaryApp(ctx, verifiedCallerPkg, extras)
            METHOD_INJECT_SECONDARY_MOTION -> handleInjectSecondaryMotion(ctx, verifiedCallerPkg, extras)
            METHOD_INJECT_SECONDARY_KEY -> handleInjectSecondaryKey(ctx, verifiedCallerPkg, extras)
            METHOD_LIST_LAUNCHABLE_APPS -> handleListLaunchableApps(ctx, verifiedCallerPkg)
            METHOD_SET_PHONE_BRIGHTNESS -> handleSetPhoneBrightness(ctx, extras)
            else -> null
        }
    }

    private fun verifyClientCaller(context: Context, extras: Bundle?): String? {
        if (extras == null) return null
        val callingUid = Binder.getCallingUid()
        if (callingUid == android.os.Process.myUid()) {
            return context.packageName
        }

        val callerPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelable(AAMakerTouchBridgeService.EXTRA_CALLER_IDENTITY, PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelable(AAMakerTouchBridgeService.EXTRA_CALLER_IDENTITY)
        }
        val senderPkg = callerPendingIntent?.creatorPackage
            ?: context.packageManager.getPackagesForUid(callingUid)?.firstOrNull()
            ?: return null

        if (!PackageNameValidator.isValidPackageName(senderPkg)) return null
        val uidPackages = context.packageManager.getPackagesForUid(callingUid).orEmpty()
        if (senderPkg !in uidPackages) return null

        if (!BridgeAuthManager.hasLocalPatchingSigningKeyCertMatch(context, senderPkg)) {
            return null
        }
        val token = extras.getString(BridgeAuthManager.EXTRA_BRIDGE_TOKEN)
        if (!BridgeAuthManager.isValidToken(context, token)) {
            return null
        }
        return senderPkg
    }

    private fun handleCreateSecondaryDisplay(
        context: Context,
        callerPkg: String,
        extras: Bundle?
    ): Bundle {
        val result = Bundle()
        if (extras == null) {
            result.putInt("display_id", Display.INVALID_DISPLAY)
            return result
        }
        val surface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelable("surface", Surface::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelable("surface")
        }
        val width = extras.getInt("width", 640).coerceIn(200, 4096)
        val height = extras.getInt("height", 720).coerceIn(200, 4096)
        val dpi = extras.getInt("dpi", 160).coerceIn(100, 640)
        val slot = extras.getString("slot", "secondary").replace(Regex("[^a-zA-Z0-9_-]"), "")

        if (surface == null || !surface.isValid) {
            result.putInt("display_id", Display.INVALID_DISPLAY)
            result.putString("error", "invalid_surface")
            return result
        }

        val sessionKey = "${callerPkg}_$slot"
        val displayName = "AAMakerVirtualDisplay_${callerPkg}_$slot"
        val binder = PrivilegedServerCoordinator.getOrStartServer(context)
        var displayId = if (binder != null) {
            PrivilegedServerCoordinator.transactCreateOrUpdateDisplay(
                binder = binder,
                sessionKey = sessionKey,
                displayName = displayName,
                width = width,
                height = height,
                dpi = dpi,
                surface = surface
            )
        } else {
            Display.INVALID_DISPLAY
        }

        // Fallback: create VirtualDisplay directly inside AAMaKer process (works when LSPosed system_server hook
        // is active, or when AAMaKer was granted CAPTURE_VIDEO_OUTPUT / PROJECT_MEDIA)
        if (displayId <= Display.DEFAULT_DISPLAY) {
            displayId = InProcessVirtualDisplayRegistry.createOrUpdate(
                context = context,
                sessionKey = sessionKey,
                displayName = displayName,
                width = width,
                height = height,
                dpi = dpi,
                surface = surface
            )
        } else {
            surface.release()
        }

        if (displayId > Display.DEFAULT_DISPLAY) {
            AAMakerCentralBridgeExecutor.registerAuthorizedDisplayOwner(displayId, callerPkg)
            AAMakerCentralBridgeExecutor.enableMultiWindowSystemSettings()
        }
        result.putBoolean("ok", displayId > Display.DEFAULT_DISPLAY)
        result.putInt("display_id", displayId)
        return result
    }

    private fun handleReleaseSecondaryDisplay(callerPkg: String, extras: Bundle?): Bundle {
        val slot = extras?.getString("slot", "secondary")?.replace(Regex("[^a-zA-Z0-9_-]"), "") ?: "secondary"
        val sessionKey = "${callerPkg}_$slot"
        PrivilegedServerCoordinator.transactReleaseDisplay(sessionKey)
        InProcessVirtualDisplayRegistry.release(sessionKey)
        return Bundle().apply { putBoolean("ok", true) }
    }

    private fun handleLaunchSecondaryApp(
        context: Context,
        callerPkg: String,
        extras: Bundle?
    ): Bundle {
        val out = Bundle()
        if (extras == null) return out
        val displayId = extras.getInt("display_id", Display.INVALID_DISPLAY)
        val targetPkg = extras.getString("target_package").orEmpty().trim()
        val mixAudio = extras.getBoolean("mix_audio", true)

        if (displayId <= Display.DEFAULT_DISPLAY || !PackageNameValidator.isValidPackageName(targetPkg)) {
            out.putBoolean("ok", false)
            return out
        }

        val launched = AAMakerCentralBridgeExecutor.executeLaunchSecondaryAppOnDisplay(
            context = context,
            displayId = displayId,
            targetPackageName = targetPkg,
            mixAudioFocus = mixAudio,
            authorizedHostPackage = callerPkg
        )
        out.putBoolean("ok", launched)
        return out
    }

    private fun handleInjectSecondaryMotion(
        context: Context,
        callerPkg: String,
        extras: Bundle?
    ): Bundle {
        val out = Bundle()
        if (extras == null) return out
        val displayId = extras.getInt("display_id", Display.INVALID_DISPLAY)
        val gestureType = extras.getString("gesture_type").orEmpty()
        val action = extras.getInt("action", 0)
        val downTime = extras.getLong("down_time", 0L)
        val eventTime = extras.getLong("event_time", 0L)
        val x = extras.getFloat("x", 0f)
        val y = extras.getFloat("y", 0f)
        val x2 = extras.getFloat("x2", x)
        val y2 = extras.getFloat("y2", y)
        val durationMs = extras.getLong("duration_ms", 80L)

        if (displayId <= Display.DEFAULT_DISPLAY || !x.isFinite() || !y.isFinite()) {
            out.putBoolean("ok", false)
            return out
        }

        AAMakerCentralBridgeExecutor.registerAuthorizedDisplayOwner(displayId, callerPkg)
        val binder = PrivilegedServerCoordinator.peekActiveBinder()

        when (gestureType) {
            "tap" -> {
                val injectedFast = if (binder != null) {
                    PrivilegedServerCoordinator.transactInjectTap(binder, displayId, x, y)
                } else {
                    false
                }
                if (!injectedFast) {
                    AAMakerCentralBridgeExecutor.executeTouchCommandFallback(
                        context = context,
                        displayId = displayId,
                        verifiedSenderPackage = callerPkg,
                        type = AAMakerTouchBridgeService.TYPE_TAP,
                        x1 = x,
                        y1 = y,
                        x2 = x,
                        y2 = y,
                        durationMs = 50L
                    )
                }
                out.putBoolean("ok", true)
                return out
            }
            "swipe" -> {
                val injectedFast = if (binder != null) {
                    PrivilegedServerCoordinator.transactInjectSwipe(binder, displayId, x, y, x2, y2, durationMs)
                } else {
                    false
                }
                if (!injectedFast) {
                    AAMakerCentralBridgeExecutor.executeTouchCommandFallback(
                        context = context,
                        displayId = displayId,
                        verifiedSenderPackage = callerPkg,
                        type = AAMakerTouchBridgeService.TYPE_SWIPE,
                        x1 = x,
                        y1 = y,
                        x2 = x2,
                        y2 = y2,
                        durationMs = durationMs
                    )
                }
                out.putBoolean("ok", true)
                return out
            }
        }

        val injectedFast = if (binder != null) {
            PrivilegedServerCoordinator.transactInjectMotion(binder, displayId, action, downTime, eventTime, x, y)
        } else {
            false
        }

        if (!injectedFast && action == android.view.MotionEvent.ACTION_UP) {
            AAMakerCentralBridgeExecutor.executeTouchCommandFallback(
                context = context,
                displayId = displayId,
                verifiedSenderPackage = callerPkg,
                type = AAMakerTouchBridgeService.TYPE_TAP,
                x1 = x,
                y1 = y,
                x2 = x,
                y2 = y,
                durationMs = 50L
            )
        }
        out.putBoolean("ok", true)
        return out
    }

    private fun handleInjectSecondaryKey(
        context: Context,
        callerPkg: String,
        extras: Bundle?
    ): Bundle {
        val out = Bundle()
        if (extras == null) return out
        val displayId = extras.getInt("display_id", Display.INVALID_DISPLAY)
        val keyCode = extras.getInt("key_code", android.view.KeyEvent.KEYCODE_BACK)
        if (displayId <= Display.DEFAULT_DISPLAY) {
            out.putBoolean("ok", false)
            return out
        }

        val binder = PrivilegedServerCoordinator.peekActiveBinder()
        val ok = if (binder != null) {
            PrivilegedServerCoordinator.transactInjectKey(binder, displayId, keyCode)
        } else {
            false
        }
        if (!ok && keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            AAMakerCentralBridgeExecutor.executeBackKey(context, displayId, callerPkg)
        }
        out.putBoolean("ok", true)
        return out
    }

    private fun handleListLaunchableApps(context: Context, callerPkg: String): Bundle {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolves = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }

        val seen = HashSet<String>()
        val items = ArrayList<Pair<String, String>>()
        for (ri in resolves) {
            val actInfo = ri.activityInfo ?: continue
            val pkg = actInfo.packageName ?: continue
            if (pkg == context.packageName || pkg == callerPkg || pkg == "com.google.android.projection.gearhead") continue
            if (!seen.add(pkg)) continue
            val appInfo = actInfo.applicationInfo
            val isPureSystem = appInfo != null &&
                (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0 &&
                pkg.startsWith("com.android.") &&
                !pkg.contains("chrome", ignoreCase = true) &&
                !pkg.contains("browser", ignoreCase = true)
            if (isPureSystem) continue

            val label = try {
                ri.loadLabel(pm)?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: pkg
            } catch (_: Throwable) {
                pkg
            }
            items.add(pkg to label)
        }

        items.sortBy { it.second.lowercase() }
        val capped = items.take(80)
        val pkgs = ArrayList<String>(capped.size)
        val labels = ArrayList<String>(capped.size)
        for ((p, l) in capped) {
            pkgs.add(p)
            labels.add(l)
        }
        return Bundle().apply {
            putBoolean("ok", true)
            putStringArrayList("packages", pkgs)
            putStringArrayList("labels", labels)
        }
    }

    private fun handleSetPhoneBrightness(context: Context, extras: Bundle?): Bundle {
        val dim = extras?.getBoolean("dim", false) ?: false
        val keepSessionAwake = extras?.getBoolean("keep_session_awake", dim) ?: dim
        val restoreBrightness = extras?.getInt("restore_brightness", -1) ?: -1
        val restoreMode = extras?.getInt("restore_mode", -1) ?: -1
        val restoreAutoRotate = extras?.getInt("restore_auto_rotate", -1) ?: -1
        val restoreUserRotation = extras?.getInt("restore_user_rotation", -1) ?: -1
        val binder = PrivilegedServerCoordinator.getOrStartServer(context)
        val panelOffApplied = if (binder != null) {
            PrivilegedServerCoordinator.transactSetPhonePanelPower(binder, dim, keepSessionAwake)
        } else {
            false
        }
        AAMakerCentralBridgeExecutor.executeSetPhoneBrightness(
            dim = dim,
            restoreBrightness = restoreBrightness,
            restoreMode = restoreMode,
            panelOffApplied = panelOffApplied,
            restoreAutoRotate = restoreAutoRotate,
            restoreUserRotation = restoreUserRotation
        )
        return Bundle().apply {
            putBoolean("ok", true)
            putBoolean("panel_off_applied", panelOffApplied)
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.aamaker.app.dualbridge"
        const val METHOD_REGISTER_PRIVILEGED_SERVER = "register_privileged_server"
        const val METHOD_REGISTER_CAR_STAGE_SERVER = "register_car_stage_server"
        const val METHOD_REGISTER_ANYAUTO_DISPLAY_SERVER = "register_anyauto_display_server"
        const val METHOD_CREATE_SECONDARY_DISPLAY = "create_secondary_display"
        const val METHOD_RELEASE_SECONDARY_DISPLAY = "release_secondary_display"
        const val METHOD_LAUNCH_SECONDARY_APP = "launch_secondary_app"
        const val METHOD_INJECT_SECONDARY_MOTION = "inject_secondary_motion"
        const val METHOD_INJECT_SECONDARY_KEY = "inject_secondary_key"
        const val METHOD_LIST_LAUNCHABLE_APPS = "list_launchable_apps"
        const val METHOD_SET_PHONE_BRIGHTNESS = "set_phone_brightness"
    }
}

internal object PrivilegedServerCoordinator {

    @Volatile
    private var activeBinder: IBinder? = null

    @Volatile
    private var pendingNonce: String? = null

    @Volatile
    private var pendingLatch: CountDownLatch? = null

    private val startLock = Any()

    @Volatile
    private var lastFailedStartMs: Long = 0L

    fun peekActiveBinder(): IBinder? {
        val current = activeBinder
        return if (current != null && current.isBinderAlive && pingServer(current)) {
            current
        } else {
            null
        }
    }

    fun onServerBinderRegistered(binder: IBinder, nonce: String?) {
        val expectedNonce = pendingNonce
        if (expectedNonce != null && nonce != expectedNonce) {
            return
        }
        try {
            binder.linkToDeath({
                if (activeBinder === binder) {
                    activeBinder = null
                }
            }, 0)
        } catch (_: Throwable) {
        }
        activeBinder = binder
        lastFailedStartMs = 0L
        pendingLatch?.countDown()
    }

    fun getOrStartServer(context: Context): IBinder? {
        peekActiveBinder()?.let { return it }

        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastFailedStartMs < 8_000L) {
            return null
        }

        synchronized(startLock) {
            peekActiveBinder()?.let { return it }
            if (android.os.SystemClock.uptimeMillis() - lastFailedStartMs < 8_000L) {
                return null
            }

            val apkPath = context.applicationInfo.sourceDir
            if (apkPath.isNullOrBlank()) return null

            val nonceBytes = ByteArray(16)
            SecureRandom().nextBytes(nonceBytes)
            val nonce = nonceBytes.joinToString("") { "%02x".format(it) }
            val latch = CountDownLatch(1)
            pendingNonce = nonce
            pendingLatch = latch

            val serverCls = AAMakerPrivilegedDisplayServer::class.java.name
            val launchCmd = "pkill -f '[a]amaker_priv_display' >/dev/null 2>&1; " +
                "CLASSPATH='$apkPath' /system/bin/app_process /system/bin --nice-name=aamaker_priv_display $serverCls '$nonce' >/dev/null 2>&1 &"
            val submitted = AAMakerCentralBridgeExecutor.submitPrivilegedCommandImmediate(launchCmd, context)
            if (!submitted) {
                lastFailedStartMs = android.os.SystemClock.uptimeMillis()
                pendingNonce = null
                pendingLatch = null
                return null
            }

            try {
                latch.await(5000L, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
            } finally {
                pendingNonce = null
                pendingLatch = null
            }
            val started = peekActiveBinder()
            if (started == null) {
                lastFailedStartMs = android.os.SystemClock.uptimeMillis()
            }
            return started
        }
    }

    private fun pingServer(binder: IBinder): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(AAMakerPrivilegedDisplayServer.DESCRIPTOR)
            val ok = binder.transact(AAMakerPrivilegedDisplayServer.TRANSACTION_PING, data, reply, 0)
            if (!ok) return false
            reply.readException()
            reply.readString() == AAMakerPrivilegedDisplayServer.SERVICE_PING_NAME
        } catch (_: Throwable) {
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun transactCreateOrUpdateDisplay(
        binder: IBinder,
        sessionKey: String,
        displayName: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface
    ): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(AAMakerPrivilegedDisplayServer.DESCRIPTOR)
            data.writeString(sessionKey)
            data.writeString(displayName)
            data.writeInt(width)
            data.writeInt(height)
            data.writeInt(dpi)
            data.writeInt(1)
            surface.writeToParcel(data, 0)
            val ok = binder.transact(
                AAMakerPrivilegedDisplayServer.TRANSACTION_CREATE_OR_UPDATE_DISPLAY,
                data,
                reply,
                0
            )
            if (!ok) return Display.INVALID_DISPLAY
            reply.readException()
            reply.readInt()
        } catch (_: Throwable) {
            Display.INVALID_DISPLAY
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun transactReleaseDisplay(sessionKey: String) {
        val binder = activeBinder ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(AAMakerPrivilegedDisplayServer.DESCRIPTOR)
            data.writeString(sessionKey)
            binder.transact(AAMakerPrivilegedDisplayServer.TRANSACTION_RELEASE_DISPLAY, data, reply, 0)
        } catch (_: Throwable) {
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun transactInjectMotion(
        binder: IBinder,
        displayId: Int,
        action: Int,
        downTime: Long,
        eventTime: Long,
        x: Float,
        y: Float
    ): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(AAMakerPrivilegedDisplayServer.DESCRIPTOR)
            data.writeInt(displayId)
            data.writeInt(action)
            data.writeLong(downTime)
            data.writeLong(eventTime)
            data.writeFloat(x)
            data.writeFloat(y)
            val ok = binder.transact(AAMakerPrivilegedDisplayServer.TRANSACTION_INJECT_MOTION, data, reply, 0)
            if (!ok) return false
            reply.readException()
            reply.readInt() != 0
        } catch (_: Throwable) {
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun transactInjectKey(binder: IBinder, displayId: Int, keyCode: Int): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(AAMakerPrivilegedDisplayServer.DESCRIPTOR)
            data.writeInt(displayId)
            data.writeInt(keyCode)
            val ok = binder.transact(AAMakerPrivilegedDisplayServer.TRANSACTION_INJECT_KEY, data, reply, 0)
            if (!ok) return false
            reply.readException()
            reply.readInt() != 0
        } catch (_: Throwable) {
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun transactInjectTap(binder: IBinder, displayId: Int, x: Float, y: Float): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(AAMakerPrivilegedDisplayServer.DESCRIPTOR)
            data.writeInt(displayId)
            data.writeFloat(x)
            data.writeFloat(y)
            val ok = binder.transact(AAMakerPrivilegedDisplayServer.TRANSACTION_INJECT_TAP, data, reply, 0)
            if (!ok) return false
            reply.readException()
            reply.readInt() != 0
        } catch (_: Throwable) {
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun transactInjectSwipe(
        binder: IBinder,
        displayId: Int,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long
    ): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(AAMakerPrivilegedDisplayServer.DESCRIPTOR)
            data.writeInt(displayId)
            data.writeFloat(x1)
            data.writeFloat(y1)
            data.writeFloat(x2)
            data.writeFloat(y2)
            data.writeLong(durationMs)
            val ok = binder.transact(AAMakerPrivilegedDisplayServer.TRANSACTION_INJECT_SWIPE, data, reply, 0)
            if (!ok) return false
            reply.readException()
            reply.readInt() != 0
        } catch (_: Throwable) {
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun transactSetPhonePanelPower(
        binder: IBinder,
        screenOff: Boolean,
        keepSessionAwake: Boolean
    ): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(AAMakerPrivilegedDisplayServer.DESCRIPTOR)
            data.writeInt(if (screenOff) 1 else 0)
            data.writeInt(if (keepSessionAwake) 1 else 0)
            val ok = binder.transact(AAMakerPrivilegedDisplayServer.TRANSACTION_SET_PHONE_PANEL_POWER, data, reply, 0)
            if (!ok) return false
            reply.readException()
            reply.readInt() != 0
        } catch (_: Throwable) {
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun transactLaunchAppOnDisplay(
        binder: IBinder,
        displayId: Int,
        pkgName: String,
        clsName: String
    ): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(AAMakerPrivilegedDisplayServer.DESCRIPTOR)
            data.writeInt(displayId)
            data.writeString(pkgName)
            data.writeString(clsName)
            val ok = binder.transact(AAMakerPrivilegedDisplayServer.TRANSACTION_LAUNCH_APP_ON_DISPLAY, data, reply, 0)
            if (!ok) return false
            reply.readException()
            reply.readInt() != 0
        } catch (_: Throwable) {
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}

internal object InProcessVirtualDisplayRegistry {

    private data class LocalEntry(
        val sessionKey: String,
        val virtualDisplay: VirtualDisplay,
        var surface: Surface
    )

    private val map = ConcurrentHashMap<String, LocalEntry>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun createOrUpdate(
        context: Context,
        sessionKey: String,
        displayName: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface
    ): Int {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return Display.INVALID_DISPLAY

        val existing = map[sessionKey]
        if (existing != null) {
            val disp = existing.virtualDisplay.display
            if (disp != null && disp.displayId > Display.DEFAULT_DISPLAY) {
                return try {
                    existing.virtualDisplay.resize(width, height, dpi)
                    existing.virtualDisplay.surface = surface
                    if (existing.surface !== surface) {
                        runCatching { existing.surface.release() }
                    }
                    existing.surface = surface
                    disp.displayId
                } catch (_: Throwable) {
                    release(sessionKey)
                    createNew(dm, sessionKey, displayName, width, height, dpi, surface)
                }
            } else {
                release(sessionKey)
            }
        }
        return createNew(dm, sessionKey, displayName, width, height, dpi, surface)
    }

    private fun createNew(
        dm: DisplayManager,
        sessionKey: String,
        displayName: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface
    ): Int {
        val flagCandidates = intArrayOf(
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                (1 shl 6) or (1 shl 10),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        )
        for (flags in flagCandidates) {
            try {
                val vd = dm.createVirtualDisplay(
                    displayName,
                    width,
                    height,
                    dpi,
                    surface,
                    flags,
                    null,
                    mainHandler
                )
                val dispId = vd?.display?.displayId ?: Display.INVALID_DISPLAY
                if (vd != null && dispId > Display.DEFAULT_DISPLAY) {
                    map[sessionKey] = LocalEntry(sessionKey, vd, surface)
                    return dispId
                }
                vd?.release()
            } catch (_: Throwable) {
            }
        }
        runCatching { surface.release() }
        return Display.INVALID_DISPLAY
    }

    fun release(sessionKey: String) {
        val removed = map.remove(sessionKey) ?: return
        runCatching { removed.virtualDisplay.surface = null }
        runCatching { removed.virtualDisplay.release() }
        runCatching { removed.surface.release() }
    }
}


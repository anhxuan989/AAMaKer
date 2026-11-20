package com.aamaker.xposed

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.InstallSourceInfo
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Modern Xposed API (libxposed API 101/102) entrypoint for AAMaKer.
 * Runs on modern LSPosed builds; legacy LSPosed (API 82/93) falls back to [GearheadXposedHook].
 */
class GearheadModernXposedHook : XposedModule() {

    @Volatile
    private var currentProcessName: String = ""

    private val hookedSensorClasses: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val sensorFieldCache = ConcurrentHashMap<Class<*>, SensorFieldLayout>()

    private val pmHookReentrancyGuard = object : ThreadLocal<Boolean>() {
        override fun initialValue(): Boolean = false
    }

    private data class SensorFieldLayout(
        val sensorTypeField: Field?,
        val intArrayFields: List<Field>,
        val floatArrayFields: List<Field>,
        val byteArrayFields: List<Field>
    )

    private fun logInfo(msg: String) {
        Log.i(LOG_TAG, msg)
        try {
            log(Log.INFO, LOG_TAG, msg)
        } catch (_: Throwable) {
        }
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        currentProcessName = param.processName
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        val pkgName = param.packageName
        val processName = currentProcessName.ifBlank { pkgName }
        val cl = param.classLoader

        val isAAMakerOrCompanion = pkgName == AAMAKER_PACKAGE ||
            (pkgName.startsWith("com.aamaker.") && !pkgName.contains("gearhead")) ||
            ((pkgName.endsWith(".car") || pkgName.contains(".aa.")) &&
                !pkgName.startsWith("android.") &&
                !pkgName.startsWith("com.android.") &&
                !pkgName.startsWith("com.google."))

        if (isAAMakerOrCompanion) {
            hookSelfStatus(cl)
            hookLsposedBridge(cl, pkgName)
            return
        }

        if (pkgName == "android") {
            return
        }

        if (pkgName == GEARHEAD_PACKAGE) {
            if (processName.contains(":crashpad", ignoreCase = true) ||
                processName.contains(":sandboxed", ignoreCase = true)
            ) {
                return
            }
            logInfo("Kích hoạt Modern API hook an toàn trên Gearhead ($processName)")
            hookPackageInstallerSourceChecks(cl)
            hookActivityDistractionOptimization(cl)
            hookCarDrivingRestrictionsAndSensors(cl)
            return
        }

        if (pkgName == GMS_PACKAGE) {
            val isDedicatedCarProcess = processName == "com.google.android.gms.car" ||
                processName.endsWith(":car", ignoreCase = true) ||
                processName.endsWith(".car", ignoreCase = true)

            if (!isDedicatedCarProcess) {
                return
            }

            logInfo("Kích hoạt Modern API hook chuyên biệt cho tiến trình GMS Car ($processName)")
            hookPackageInstallerSourceChecks(cl)
            hookActivityDistractionOptimization(cl)
            hookCarDrivingRestrictionsAndSensors(cl)
            return
        }

        if (isSafeThirdPartyPackage(pkgName)) {
            hookThirdPartyVirtualDisplayKeepAwake(cl)
        }
    }

    private fun findClassOrNull(name: String, cl: ClassLoader): Class<*>? {
        return try {
            Class.forName(name, false, cl)
        } catch (_: Throwable) {
            null
        }
    }

    private fun findDeclaredMethodOrNull(
        cls: Class<*>,
        name: String,
        vararg paramTypes: Class<*>
    ): Method? {
        var current: Class<*>? = cls
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, *paramTypes)
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            }
        }
        return null
    }

    private fun hookAllMethodsByName(
        cls: Class<*>,
        methodName: String,
        hooker: XposedInterface.Hooker
    ) {
        for (method in cls.declaredMethods) {
            if (method.name == methodName && !Modifier.isAbstract(method.modifiers)) {
                try {
                    hook(method).intercept(hooker)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun setFieldQuietly(target: Any, fieldName: String, value: Any?) {
        var current: Class<*>? = target.javaClass
        while (current != null) {
            try {
                val field = current.getDeclaredField(fieldName)
                field.isAccessible = true
                field.set(target, value)
                return
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        throw NoSuchFieldException(fieldName)
    }

    private fun hookThirdPartyVirtualDisplayKeepAwake(cl: ClassLoader) {
        try {
            val activityCls = findClassOrNull("android.app.Activity", cl) ?: return
            val lifecycleHooker = XposedInterface.Hooker { chain ->
                val result = chain.proceed()
                try {
                    val act = chain.thisObject as? Activity
                    if (act != null) {
                        val dispId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            runCatching { act.display?.displayId ?: 0 }.getOrDefault(0)
                        } else {
                            @Suppress("DEPRECATION")
                            runCatching { act.windowManager?.defaultDisplay?.displayId ?: 0 }.getOrDefault(0)
                        }
                        if (dispId > 0) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                                runCatching { act.setShowWhenLocked(true) }
                            }
                            @Suppress("DEPRECATION")
                            runCatching {
                                act.window?.addFlags(
                                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                                        WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
                                )
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
                result
            }
            findDeclaredMethodOrNull(activityCls, "onCreate", Bundle::class.java)?.let {
                hook(it).intercept(lifecycleHooker)
            }
            findDeclaredMethodOrNull(activityCls, "onResume")?.let {
                hook(it).intercept(lifecycleHooker)
            }
        } catch (_: Throwable) {
        }
    }

    private fun hookSelfStatus(cl: ClassLoader) {
        try {
            val cls = findClassOrNull("com.aamaker.core.installer.MultiModeInstallerManager", cl) ?: return
            val method = findDeclaredMethodOrNull(cls, "isXposedModuleActive") ?: return
            hook(method).intercept { true }
            logInfo("Đã xác nhận trạng thái active (Modern API) trên AAMaKer ($AAMAKER_PACKAGE)")
        } catch (t: Throwable) {
            logInfo("Không thể hook trạng thái nội bộ (Modern API): ${t.message}")
        }
    }

    private fun hookLsposedBridge(cl: ClassLoader, pkgName: String) {
        try {
            val bridgeCls = findClassOrNull("com.mag.anyauto.LsposedBridge", cl) ?: return
            val method = findDeclaredMethodOrNull(bridgeCls, "isHookActive") ?: return
            hook(method).intercept { true }
            logInfo("Đã kích hoạt LsposedBridge (Modern API) cho gói $pkgName")
        } catch (t: Throwable) {
            logInfo("Không thể hook LsposedBridge (Modern API): ${t.message}")
        }
    }

    private fun isSafeThirdPartyPackage(pkgName: String?): Boolean {
        if (pkgName.isNullOrBlank()) return false
        if (pkgName in SYSTEM_EXCLUDED_PACKAGES) return false
        if (pkgName.startsWith("com.android.") ||
            pkgName == "android" ||
            pkgName.startsWith("com.google.android.gms") ||
            pkgName.startsWith("com.google.android.gsf") ||
            pkgName.startsWith("com.google.android.projection.gearhead")
        ) {
            return false
        }
        return true
    }

    private fun hookPackageInstallerSourceChecks(cl: ClassLoader) {
        try {
            val appPmClass = findClassOrNull("android.app.ApplicationPackageManager", cl) ?: return

            findDeclaredMethodOrNull(appPmClass, "getInstallerPackageName", String::class.java)?.let { method ->
                hook(method).intercept { chain ->
                    val result = chain.proceed()
                    val targetPkg = chain.args.firstOrNull() as? String ?: return@intercept result
                    if (!isSafeThirdPartyPackage(targetPkg)) return@intercept result
                    val currentResult = result as? String
                    if (currentResult != VENDING_PACKAGE) VENDING_PACKAGE else result
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                findDeclaredMethodOrNull(appPmClass, "getInstallSourceInfo", String::class.java)?.let { method ->
                    hook(method).intercept { chain ->
                        val result = chain.proceed()
                        val targetPkg = chain.args.firstOrNull() as? String ?: return@intercept result
                        if (!isSafeThirdPartyPackage(targetPkg)) return@intercept result
                        val originalInfo = result as? InstallSourceInfo ?: return@intercept result
                        if (originalInfo.installingPackageName == VENDING_PACKAGE &&
                            originalInfo.initiatingPackageName == VENDING_PACKAGE
                        ) {
                            return@intercept result
                        }
                        try {
                            setFieldQuietly(originalInfo, "mInitiatingPackageName", VENDING_PACKAGE)
                            setFieldQuietly(originalInfo, "mInstallingPackageName", VENDING_PACKAGE)
                            setFieldQuietly(originalInfo, "mOriginatingPackageName", VENDING_PACKAGE)
                            originalInfo
                        } catch (_: Throwable) {
                            try {
                                createSpoofedInstallSourceInfo(originalInfo) ?: result
                            } catch (_: Throwable) {
                                result
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            logInfo("Lỗi khi hook ApplicationPackageManager (Modern API): ${t.message}")
        }
    }

    private fun createSpoofedInstallSourceInfo(original: InstallSourceInfo): InstallSourceInfo? {
        for (ctor in InstallSourceInfo::class.java.declaredConstructors) {
            ctor.isAccessible = true
            val paramTypes = ctor.parameterTypes
            if (paramTypes.size == 4 &&
                paramTypes[0] == String::class.java &&
                paramTypes[2] == String::class.java &&
                paramTypes[3] == String::class.java
            ) {
                val signingInfo = try {
                    original.initiatingPackageSigningInfo
                } catch (_: Throwable) {
                    null
                }
                return ctor.newInstance(VENDING_PACKAGE, signingInfo, VENDING_PACKAGE, VENDING_PACKAGE) as InstallSourceInfo
            }
            if (paramTypes.size == 6 &&
                paramTypes[0] == String::class.java &&
                paramTypes[2] == String::class.java &&
                paramTypes[3] == String::class.java &&
                paramTypes[5] == Int::class.javaPrimitiveType
            ) {
                val signingInfo = try {
                    original.initiatingPackageSigningInfo
                } catch (_: Throwable) {
                    null
                }
                return ctor.newInstance(
                    VENDING_PACKAGE,
                    signingInfo,
                    VENDING_PACKAGE,
                    VENDING_PACKAGE,
                    VENDING_PACKAGE,
                    2
                ) as InstallSourceInfo
            }
        }
        return null
    }

    private fun isCarRelatedIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        val action = intent.action
        if (action == "androidx.car.app.CarAppService" ||
            action == "android.media.browse.MediaBrowserService" ||
            action == Intent.ACTION_MAIN
        ) {
            val categories = intent.categories
            if (categories != null) {
                for (cat in categories) {
                    if (cat.contains("CAR", ignoreCase = true) ||
                        cat == Intent.CATEGORY_LAUNCHER ||
                        cat == Intent.CATEGORY_INFO
                    ) {
                        return true
                    }
                }
            }
            if (action != Intent.ACTION_MAIN) return true
        }
        val pkg = intent.`package` ?: intent.component?.packageName
        return isSafeThirdPartyPackage(pkg)
    }

    private fun hookActivityDistractionOptimization(cl: ClassLoader) {
        try {
            val appPmClass = findClassOrNull("android.app.ApplicationPackageManager", cl) ?: return

            hookAllMethodsByName(appPmClass, "getActivityInfo") { chain ->
                if (pmHookReentrancyGuard.get() == true) {
                    return@hookAllMethodsByName chain.proceed()
                }
                pmHookReentrancyGuard.set(true)
                try {
                    val result = chain.proceed()
                    val comp = chain.args.firstOrNull() as? ComponentName
                    if (comp != null && !isSafeThirdPartyPackage(comp.packageName)) return@hookAllMethodsByName result
                    val actInfo = result as? ActivityInfo ?: return@hookAllMethodsByName result
                    if (!isSafeThirdPartyPackage(actInfo.packageName)) return@hookAllMethodsByName result
                    markActivityDistractionOptimized(actInfo)
                    result
                } finally {
                    pmHookReentrancyGuard.set(false)
                }
            }

            val queryHook = XposedInterface.Hooker { chain ->
                if (pmHookReentrancyGuard.get() == true) {
                    return@Hooker chain.proceed()
                }
                pmHookReentrancyGuard.set(true)
                try {
                    val result = chain.proceed()
                    val intent = chain.args.firstOrNull() as? Intent
                    if (!isCarRelatedIntent(intent)) return@Hooker result
                    val list = result as? List<*> ?: return@Hooker result
                    for (item in list) {
                        val resolveInfo = item as? ResolveInfo ?: continue
                        val actInfo = resolveInfo.activityInfo ?: continue
                        if (isSafeThirdPartyPackage(actInfo.packageName)) {
                            markActivityDistractionOptimized(actInfo)
                        }
                    }
                    result
                } finally {
                    pmHookReentrancyGuard.set(false)
                }
            }
            hookAllMethodsByName(appPmClass, "queryIntentActivities", queryHook)
            hookAllMethodsByName(appPmClass, "queryIntentActivitiesAsUser", queryHook)

            val resolveHook = XposedInterface.Hooker { chain ->
                if (pmHookReentrancyGuard.get() == true) {
                    return@Hooker chain.proceed()
                }
                pmHookReentrancyGuard.set(true)
                try {
                    val result = chain.proceed()
                    val intent = chain.args.firstOrNull() as? Intent
                    if (!isCarRelatedIntent(intent)) return@Hooker result
                    val resolveInfo = result as? ResolveInfo ?: return@Hooker result
                    val actInfo = resolveInfo.activityInfo ?: return@Hooker result
                    if (isSafeThirdPartyPackage(actInfo.packageName)) {
                        markActivityDistractionOptimized(actInfo)
                    }
                    result
                } finally {
                    pmHookReentrancyGuard.set(false)
                }
            }
            hookAllMethodsByName(appPmClass, "resolveActivity", resolveHook)
            hookAllMethodsByName(appPmClass, "resolveActivityAsUser", resolveHook)
        } catch (t: Throwable) {
            logInfo("Lỗi hook ActivityInfo distractionOptimized (Modern API): ${t.message}")
        }
    }

    private fun markActivityDistractionOptimized(actInfo: ActivityInfo) {
        try {
            val meta = actInfo.metaData ?: Bundle(3).also { actInfo.metaData = it }
            meta.putBoolean("distractionOptimized", true)
            meta.putBoolean("com.google.android.gms.car.distractionOptimized", true)
            meta.putBoolean("com.android.automotive.distractionOptimized", true)
            runCatching {
                setFieldQuietly(actInfo, "resizeMode", 2)
                actInfo.flags = actInfo.flags or Int.MIN_VALUE or 0x00800000 or 0x00000400
            }
        } catch (_: Throwable) {
        }
    }

    private fun hookCarDrivingRestrictionsAndSensors(cl: ClassLoader) {
        val knownSensorClasses = listOf(
            "com.google.android.gms.car.CarSensorEvent",
            "com.google.android.projection.gearhead.service.CarSensorEvent",
            "com.google.android.projection.protocol.CarSensorEvent",
            "android.support.car.hardware.CarSensorEvent",
            "androidx.car.hardware.CarSensorEvent"
        )
        for (clsName in knownSensorClasses) {
            val cls = findClassOrNull(clsName, cl)
            if (cls != null) {
                hookCarSensorEventClass(cls)
            }
        }

        findClassOrNull("android.car.drivingstate.CarUxRestrictions", cl)?.let { uxRestrictionsCls ->
            hookAllMethodsByName(uxRestrictionsCls, "isRequiresDistractionOptimization") { false }
            hookAllMethodsByName(uxRestrictionsCls, "getActiveRestrictions") { 0 }
        }

        findClassOrNull("android.car.drivingstate.CarDrivingStateEvent", cl)?.let { drivingStateCls ->
            hookAllMethodsByName(drivingStateCls, "getEventValue") { 0 }
        }
    }

    private fun hookCarSensorEventClass(cls: Class<*>) {
        if (!hookedSensorClasses.add(cls.name)) return
        try {
            for (ctor in cls.declaredConstructors) {
                hook(ctor).intercept { chain ->
                    val result = chain.proceed()
                    sanitizeCarSensorEventObject(chain.thisObject)
                    result
                }
            }
            logInfo("Đã kích hoạt Driving Bypass (Modern API) trên class cảm biến: ${cls.name}")
        } catch (t: Throwable) {
            logInfo("Lỗi hook class cảm biến ${cls.name} (Modern API): ${t.message}")
        }
    }

    private fun getOrBuildSensorFieldLayout(cls: Class<*>): SensorFieldLayout {
        sensorFieldCache[cls]?.let { return it }

        var explicitSensorTypeField: Field? = null
        val nonStaticIntFields = mutableListOf<Field>()
        val intArrays = mutableListOf<Field>()
        val floatArrays = mutableListOf<Field>()
        val byteArrays = mutableListOf<Field>()

        var hasLongTimestampField = false
        for (field in cls.declaredFields) {
            if (Modifier.isStatic(field.modifiers)) continue
            when (field.type) {
                Int::class.javaPrimitiveType -> {
                    if (field.name == "sensorType" || field.name == "mSensorType" || field.name == "type") {
                        field.isAccessible = true
                        explicitSensorTypeField = field
                    }
                    nonStaticIntFields.add(field)
                }
                Long::class.javaPrimitiveType -> {
                    hasLongTimestampField = true
                }
                IntArray::class.java -> {
                    field.isAccessible = true
                    intArrays.add(field)
                }
                FloatArray::class.java -> {
                    field.isAccessible = true
                    floatArrays.add(field)
                }
                ByteArray::class.java -> {
                    field.isAccessible = true
                    byteArrays.add(field)
                }
            }
        }

        val hasExpectedSensorArrays = (intArrays.size + floatArrays.size + byteArrays.size) >= 2
        val resolvedSensorField = explicitSensorTypeField ?: if (hasLongTimestampField && hasExpectedSensorArrays) {
            when {
                nonStaticIntFields.size == 1 -> nonStaticIntFields[0].apply { isAccessible = true }
                nonStaticIntFields.size == 2 -> {
                    val first = nonStaticIntFields[0]
                    val second = nonStaticIntFields[1]
                    (if (first.name == "a" && second.name == "b") second else first).apply { isAccessible = true }
                }
                else -> null
            }
        } else {
            null
        }

        val layout = SensorFieldLayout(
            sensorTypeField = resolvedSensorField,
            intArrayFields = intArrays,
            floatArrayFields = floatArrays,
            byteArrayFields = byteArrays
        )
        sensorFieldCache[cls] = layout
        return layout
    }

    private fun sanitizeCarSensorEventObject(eventObj: Any?) {
        if (eventObj == null) return
        try {
            val layout = getOrBuildSensorFieldLayout(eventObj.javaClass)
            val sensorTypeField = layout.sensorTypeField ?: return
            val sensorType = try {
                sensorTypeField.getInt(eventObj)
            } catch (_: Throwable) {
                return
            }

            when (sensorType) {
                SENSOR_TYPE_DRIVING_STATUS -> {
                    for (field in layout.intArrayFields) {
                        val arr = field.get(eventObj) as? IntArray ?: continue
                        for (i in arr.indices) arr[i] = 0
                    }
                    for (field in layout.byteArrayFields) {
                        val arr = field.get(eventObj) as? ByteArray ?: continue
                        for (i in arr.indices) arr[i] = 0
                    }
                }
                SENSOR_TYPE_CAR_SPEED -> {
                    for (field in layout.floatArrayFields) {
                        val arr = field.get(eventObj) as? FloatArray ?: continue
                        for (i in arr.indices) arr[i] = 0.0f
                    }
                    for (field in layout.intArrayFields) {
                        val arr = field.get(eventObj) as? IntArray ?: continue
                        for (i in arr.indices) arr[i] = 0
                    }
                }
                SENSOR_TYPE_PARKING_BRAKE -> {
                    for (field in layout.intArrayFields) {
                        val arr = field.get(eventObj) as? IntArray ?: continue
                        if (arr.isNotEmpty()) arr[0] = 1
                    }
                    for (field in layout.byteArrayFields) {
                        val arr = field.get(eventObj) as? ByteArray ?: continue
                        if (arr.isNotEmpty()) arr[0] = 1
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val LOG_TAG = "AAMaKer-Xposed"
        private const val AAMAKER_PACKAGE = "com.aamaker.app"
        private const val GEARHEAD_PACKAGE = "com.google.android.projection.gearhead"
        private const val GMS_PACKAGE = "com.google.android.gms"
        private const val VENDING_PACKAGE = "com.android.vending"

        private val SYSTEM_EXCLUDED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.vending",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.projection.gearhead",
            "com.google.android.apps.maps",
            "com.google.android.googlequicksearchbox",
            "com.google.android.tts"
        )

        private const val SENSOR_TYPE_CAR_SPEED = 2
        private const val SENSOR_TYPE_PARKING_BRAKE = 6
        private const val SENSOR_TYPE_DRIVING_STATUS = 11
    }
}

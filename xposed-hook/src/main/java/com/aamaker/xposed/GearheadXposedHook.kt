package com.aamaker.xposed

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.InstallSourceInfo
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class GearheadXposedHook : IXposedHookLoadPackage {

    private val hookedSensorClasses: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val sensorFieldCache = ConcurrentHashMap<Class<*>, SensorFieldLayout>()

    // Chống đệ quy lặp 3-4 lần khi ApplicationPackageManager ủy quyền nội bộ (ví dụ queryIntentActivities -> queryIntentActivitiesAsUser)
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
            XposedBridge.log("[$LOG_TAG] $msg")
        } catch (_: Throwable) {
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkgName = lpparam.packageName
        val processName = lpparam.processName ?: pkgName

        if (pkgName == AAMAKER_PACKAGE) {
            hookSelfStatus(lpparam)
            return
        }

        if (pkgName == GEARHEAD_PACKAGE) {
            // Chỉ kích hoạt trên tiến trình chính / projection / car của Gearhead, bỏ qua crashpad / sandbox phụ
            if (processName.contains(":crashpad", ignoreCase = true) ||
                processName.contains(":sandboxed", ignoreCase = true)
            ) {
                return
            }
            logInfo("Kích hoạt hook an toàn trên Gearhead ($processName)")
            hookPackageInstallerSourceChecks(lpparam)
            hookActivityDistractionOptimization(lpparam)
            hookCarDrivingRestrictionsAndSensors(lpparam)
            return
        }

        if (pkgName == GMS_PACKAGE) {
            // CỰC KỲ QUAN TRỌNG: GMS có hàng chục process (persistent, unstable, gapps, auth, carrier...).
            // Tuyệt đối chỉ hook tiến trình thực sự là GMS Car (:car / .car), không dùng contains("car") tránh dính "carrier"!
            val isDedicatedCarProcess = processName == "com.google.android.gms.car" ||
                processName.endsWith(":car", ignoreCase = true) ||
                processName.endsWith(".car", ignoreCase = true)

            if (!isDedicatedCarProcess) {
                return
            }

            logInfo("Kích hoạt hook chuyên biệt cho tiến trình GMS Car ($processName)")
            hookPackageInstallerSourceChecks(lpparam)
            hookActivityDistractionOptimization(lpparam)
            hookCarDrivingRestrictionsAndSensors(lpparam)
        }
    }

    private fun hookSelfStatus(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.aamaker.core.installer.MultiModeInstallerManager",
                lpparam.classLoader,
                "isXposedModuleActive",
                XC_MethodReplacement.returnConstant(true)
            )
            logInfo("Đã xác nhận trạng thái active trên AAMaKer ($AAMAKER_PACKAGE)")
        } catch (t: Throwable) {
            logInfo("Không thể hook trạng thái nội bộ: ${t.message}")
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

    private fun hookPackageInstallerSourceChecks(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val appPmClass = XposedHelpers.findClass(
                "android.app.ApplicationPackageManager",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                appPmClass,
                "getInstallerPackageName",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.hasThrowable()) return
                        val targetPkg = param.args.firstOrNull() as? String ?: return
                        if (!isSafeThirdPartyPackage(targetPkg)) return
                        val currentResult = param.result as? String
                        if (currentResult != VENDING_PACKAGE) {
                            param.result = VENDING_PACKAGE
                        }
                    }
                }
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                XposedHelpers.findAndHookMethod(
                    appPmClass,
                    "getInstallSourceInfo",
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.hasThrowable()) return
                            val targetPkg = param.args.firstOrNull() as? String ?: return
                            if (!isSafeThirdPartyPackage(targetPkg)) return
                            val originalInfo = param.result as? InstallSourceInfo ?: return
                            if (originalInfo.installingPackageName == VENDING_PACKAGE &&
                                originalInfo.initiatingPackageName == VENDING_PACKAGE
                            ) {
                                return
                            }
                            try {
                                XposedHelpers.setObjectField(originalInfo, "mInitiatingPackageName", VENDING_PACKAGE)
                                XposedHelpers.setObjectField(originalInfo, "mInstallingPackageName", VENDING_PACKAGE)
                                XposedHelpers.setObjectField(originalInfo, "mOriginatingPackageName", VENDING_PACKAGE)
                                param.result = originalInfo
                            } catch (_: Throwable) {
                                // Fallback tạo mới InstallSourceInfo đúng cho targetPkg nếu hidden field bị chặn
                                try {
                                    val spoofed = createSpoofedInstallSourceInfo(originalInfo)
                                    if (spoofed != null) {
                                        param.result = spoofed
                                    }
                                } catch (_: Throwable) {
                                }
                            }
                        }
                    }
                )
            }
        } catch (t: Throwable) {
            logInfo("Lỗi khi hook ApplicationPackageManager: ${t.message}")
        }
    }

    private fun createSpoofedInstallSourceInfo(original: InstallSourceInfo): InstallSourceInfo? {
        for (ctor in InstallSourceInfo::class.java.declaredConstructors) {
            ctor.isAccessible = true
            val paramTypes = ctor.parameterTypes
            // Android 11-12: (String initiating, SigningInfo?, String originating, String installing)
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
            // Android 13-16+: (String initiating, SigningInfo?, String originating, String installing, String? updateOwner, int packageSource)
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
                    2 // PackageInstaller.PACKAGE_SOURCE_STORE
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

    private fun hookActivityDistractionOptimization(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val appPmClass = XposedHelpers.findClass(
                "android.app.ApplicationPackageManager",
                lpparam.classLoader
            )

            XposedBridge.hookAllMethods(
                appPmClass,
                "getActivityInfo",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (pmHookReentrancyGuard.get() == true) {
                            param.setObjectExtra(EXTRA_SKIP_NESTED, true)
                            return
                        }
                        pmHookReentrancyGuard.set(true)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.getObjectExtra(EXTRA_SKIP_NESTED) == true) return
                        try {
                            if (param.hasThrowable()) return
                            val comp = param.args.firstOrNull() as? ComponentName
                            if (comp != null && !isSafeThirdPartyPackage(comp.packageName)) return
                            val actInfo = param.result as? ActivityInfo ?: return
                            if (!isSafeThirdPartyPackage(actInfo.packageName)) return
                            markActivityDistractionOptimized(actInfo)
                        } finally {
                            pmHookReentrancyGuard.set(false)
                        }
                    }
                }
            )

            val queryHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (pmHookReentrancyGuard.get() == true) {
                        param.setObjectExtra(EXTRA_SKIP_NESTED, true)
                        return
                    }
                    pmHookReentrancyGuard.set(true)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.getObjectExtra(EXTRA_SKIP_NESTED) == true) return
                    try {
                        if (param.hasThrowable()) return
                        val intent = param.args.firstOrNull() as? Intent
                        if (!isCarRelatedIntent(intent)) return
                        val list = param.result as? List<*> ?: return
                        for (item in list) {
                            val resolveInfo = item as? ResolveInfo ?: continue
                            val actInfo = resolveInfo.activityInfo ?: continue
                            if (isSafeThirdPartyPackage(actInfo.packageName)) {
                                markActivityDistractionOptimized(actInfo)
                            }
                        }
                    } finally {
                        pmHookReentrancyGuard.set(false)
                    }
                }
            }
            XposedBridge.hookAllMethods(appPmClass, "queryIntentActivities", queryHook)
            XposedBridge.hookAllMethods(appPmClass, "queryIntentActivitiesAsUser", queryHook)

            val resolveHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (pmHookReentrancyGuard.get() == true) {
                        param.setObjectExtra(EXTRA_SKIP_NESTED, true)
                        return
                    }
                    pmHookReentrancyGuard.set(true)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.getObjectExtra(EXTRA_SKIP_NESTED) == true) return
                    try {
                        if (param.hasThrowable()) return
                        val intent = param.args.firstOrNull() as? Intent
                        if (!isCarRelatedIntent(intent)) return
                        val resolveInfo = param.result as? ResolveInfo ?: return
                        val actInfo = resolveInfo.activityInfo ?: return
                        if (isSafeThirdPartyPackage(actInfo.packageName)) {
                            markActivityDistractionOptimized(actInfo)
                        }
                    } finally {
                        pmHookReentrancyGuard.set(false)
                    }
                }
            }
            XposedBridge.hookAllMethods(appPmClass, "resolveActivity", resolveHook)
            XposedBridge.hookAllMethods(appPmClass, "resolveActivityAsUser", resolveHook)
        } catch (t: Throwable) {
            logInfo("Lỗi hook ActivityInfo distractionOptimized: ${t.message}")
        }
    }

    private fun markActivityDistractionOptimized(actInfo: ActivityInfo) {
        try {
            // Chỉ ghi đè nếu metaData đã được yêu cầu (hoặc khởi tạo Bundle nhẹ chỉ cho Activity, KHÔNG đụng vào ApplicationInfo chung)
            val meta = actInfo.metaData ?: Bundle(3).also { actInfo.metaData = it }
            meta.putBoolean("distractionOptimized", true)
            meta.putBoolean("com.google.android.gms.car.distractionOptimized", true)
            meta.putBoolean("com.android.automotive.distractionOptimized", true)
        } catch (_: Throwable) {
        }
    }

    private fun hookCarDrivingRestrictionsAndSensors(
        lpparam: XC_LoadPackage.LoadPackageParam
    ) {
        val cl = lpparam.classLoader

        val knownSensorClasses = listOf(
            "com.google.android.gms.car.CarSensorEvent",
            "com.google.android.projection.gearhead.service.CarSensorEvent",
            "com.google.android.projection.protocol.CarSensorEvent",
            "android.support.car.hardware.CarSensorEvent",
            "androidx.car.hardware.CarSensorEvent"
        )
        for (clsName in knownSensorClasses) {
            val cls = XposedHelpers.findClassIfExists(clsName, cl)
            if (cls != null) {
                hookCarSensorEventClass(cls)
            }
        }

        val uxRestrictionsCls = XposedHelpers.findClassIfExists(
            "android.car.drivingstate.CarUxRestrictions",
            cl
        )
        if (uxRestrictionsCls != null) {
            try {
                XposedBridge.hookAllMethods(
                    uxRestrictionsCls,
                    "isRequiresDistractionOptimization",
                    XC_MethodReplacement.returnConstant(false)
                )
                XposedBridge.hookAllMethods(
                    uxRestrictionsCls,
                    "getActiveRestrictions",
                    XC_MethodReplacement.returnConstant(0)
                )
            } catch (_: Throwable) {
            }
        }

        val drivingStateCls = XposedHelpers.findClassIfExists(
            "android.car.drivingstate.CarDrivingStateEvent",
            cl
        )
        if (drivingStateCls != null) {
            try {
                XposedBridge.hookAllMethods(
                    drivingStateCls,
                    "getEventValue",
                    XC_MethodReplacement.returnConstant(0)
                )
            } catch (_: Throwable) {
            }
        }

        // ĐÃ GỠ BỎ:
        // 1. Parcel.readParcelable(...) -> Hook này chặn toàn bộ Binder IPC của GMS & Android Auto,
        //    đồng thời gọi XposedBridge.hookMethod động giữa lúc giao dịch IPC làm treo cứng ART VM / ANR khi khởi động AA!
        // 2. Location.getSpeed() -> Ghi đè tốc độ GPS toàn cục trong Gearhead làm hỏng điều hướng Google Maps và gây xung đột cảm biến.
    }

    private fun hookCarSensorEventClass(cls: Class<*>) {
        if (!hookedSensorClasses.add(cls.name)) return
        try {
            // Chỉ hook constructor một lần khi tạo sự kiện cảm biến, không hook hàng loạt declaredMethods
            XposedBridge.hookAllConstructors(
                cls,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        sanitizeCarSensorEventObject(param.thisObject)
                    }
                }
            )
            logInfo("Đã kích hoạt Driving Bypass trên class cảm biến: ${cls.name}")
        } catch (t: Throwable) {
            logInfo("Lỗi hook class cảm biến ${cls.name}: ${t.message}")
        }
    }

    private fun getOrBuildSensorFieldLayout(cls: Class<*>): SensorFieldLayout {
        sensorFieldCache[cls]?.let { return it }

        var explicitSensorTypeField: Field? = null
        val nonStaticIntFields = mutableListOf<Field>()
        val intArrays = mutableListOf<Field>()
        val floatArrays = mutableListOf<Field>()
        val byteArrays = mutableListOf<Field>()

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

        val resolvedSensorField = explicitSensorTypeField ?: when {
            nonStaticIntFields.size == 1 -> nonStaticIntFields[0].apply { isAccessible = true }
            nonStaticIntFields.size >= 2 -> {
                val first = nonStaticIntFields[0]
                val second = nonStaticIntFields[1]
                (if (first.name == "a" && second.name == "b") second else first).apply { isAccessible = true }
            }
            else -> null
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
            val sensorType = sensorTypeField.getInt(eventObj)

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
        private const val EXTRA_SKIP_NESTED = "aamaker_skip_nested"
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

package com.aamaker.xposed

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.InstallSourceInfo
import android.content.pm.ResolveInfo
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class GearheadXposedHook : IXposedHookLoadPackage {

    private val hookedSensorClasses: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkgName = lpparam.packageName

        if (pkgName == AAMAKER_PACKAGE) {
            hookSelfStatus(lpparam)
            return
        }

        if (pkgName == GEARHEAD_PACKAGE || pkgName == GMS_PACKAGE) {
            XposedBridge.log("[AAMaKer-Xposed] Đang kích hoạt bypass nguồn cài đặt & mở khóa khi xe di chuyển cho: $pkgName")
            hookPackageInstallerSourceChecks(lpparam)
            hookActivityDistractionOptimization(lpparam)
            hookCarDrivingRestrictionsAndSensors(lpparam, isGearheadProcess = (pkgName == GEARHEAD_PACKAGE))
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
        } catch (t: Throwable) {
            XposedBridge.log("[AAMaKer-Xposed] Không thể hook trạng thái nội bộ: ${t.message}")
        }
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
                        param.result = VENDING_PACKAGE
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
                            val originalInfo = param.result as? InstallSourceInfo ?: return
                            try {
                                XposedHelpers.setObjectField(originalInfo, "mInitiatingPackageName", VENDING_PACKAGE)
                                XposedHelpers.setObjectField(originalInfo, "mInstallingPackageName", VENDING_PACKAGE)
                                XposedHelpers.setObjectField(originalInfo, "mOriginatingPackageName", VENDING_PACKAGE)
                                param.result = originalInfo
                            } catch (e: Throwable) {
                                XposedBridge.log("[AAMaKer-Xposed] Lỗi sửa InstallSourceInfo: ${e.message}")
                            }
                        }
                    }
                )
            }
        } catch (t: Throwable) {
            XposedBridge.log("[AAMaKer-Xposed] Lỗi khi hook ApplicationPackageManager: ${t.message}")
        }
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
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val actInfo = param.result as? ActivityInfo ?: return
                        markActivityDistractionOptimized(actInfo)
                    }
                }
            )

            val queryHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val list = param.result as? List<*> ?: return
                    for (item in list) {
                        val resolveInfo = item as? ResolveInfo ?: continue
                        resolveInfo.activityInfo?.let { markActivityDistractionOptimized(it) }
                    }
                }
            }
            XposedBridge.hookAllMethods(appPmClass, "queryIntentActivities", queryHook)
            XposedBridge.hookAllMethods(appPmClass, "queryIntentActivitiesAsUser", queryHook)

            val resolveHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val resolveInfo = param.result as? ResolveInfo ?: return
                    resolveInfo.activityInfo?.let { markActivityDistractionOptimized(it) }
                }
            }
            XposedBridge.hookAllMethods(appPmClass, "resolveActivity", resolveHook)
            XposedBridge.hookAllMethods(appPmClass, "resolveActivityAsUser", resolveHook)

            XposedBridge.hookAllMethods(
                appPmClass,
                "getApplicationInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val appInfo = param.result as? ApplicationInfo ?: return
                        markApplicationDistractionOptimized(appInfo)
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[AAMaKer-Xposed] Lỗi hook ActivityInfo distractionOptimized: ${t.message}")
        }
    }

    private fun markActivityDistractionOptimized(actInfo: ActivityInfo) {
        try {
            val meta = actInfo.metaData ?: Bundle().also { actInfo.metaData = it }
            meta.putBoolean("distractionOptimized", true)
            meta.putBoolean("com.google.android.gms.car.distractionOptimized", true)
            meta.putBoolean("com.android.automotive.distractionOptimized", true)
            actInfo.applicationInfo?.let { markApplicationDistractionOptimized(it) }
        } catch (_: Throwable) {
        }
    }

    private fun markApplicationDistractionOptimized(appInfo: ApplicationInfo) {
        try {
            val meta = appInfo.metaData ?: Bundle().also { appInfo.metaData = it }
            meta.putBoolean("distractionOptimized", true)
            meta.putBoolean("com.google.android.gms.car.distractionOptimized", true)
            meta.putBoolean("com.android.automotive.distractionOptimized", true)
        } catch (_: Throwable) {
        }
    }

    private fun hookCarDrivingRestrictionsAndSensors(
        lpparam: XC_LoadPackage.LoadPackageParam,
        isGearheadProcess: Boolean
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

        hookDynamicParcelableSensorEvents(cl)

        if (isGearheadProcess) {
            try {
                XposedHelpers.findAndHookMethod(
                    Location::class.java,
                    "getSpeed",
                    XC_MethodReplacement.returnConstant(0.0f)
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    XposedHelpers.findAndHookMethod(
                        Location::class.java,
                        "getSpeedAccuracyMetersPerSecond",
                        XC_MethodReplacement.returnConstant(0.0f)
                    )
                }
            } catch (t: Throwable) {
                XposedBridge.log("[AAMaKer-Xposed] Lỗi hook Location.getSpeed trong Gearhead: ${t.message}")
            }
        }
    }

    private fun hookCarSensorEventClass(cls: Class<*>) {
        if (!hookedSensorClasses.add(cls.name)) return
        try {
            XposedBridge.hookAllConstructors(
                cls,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        sanitizeCarSensorEventObject(param.thisObject)
                    }
                }
            )
            for (method in cls.declaredMethods) {
                val returnType = method.returnType
                if (returnType == Boolean::class.javaPrimitiveType ||
                    returnType == Int::class.javaPrimitiveType ||
                    returnType == Float::class.javaPrimitiveType ||
                    returnType.name.contains("DrivingStatus") ||
                    returnType.name.contains("Speed") ||
                    returnType.name.contains("ParkingBrake")
                ) {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                sanitizeCarSensorEventObject(param.thisObject)
                            }
                        }
                    )
                }
            }
            XposedBridge.log("[AAMaKer-Xposed] Đã kích hoạt Driving Bypass trên class cảm biến: ${cls.name}")
        } catch (t: Throwable) {
            XposedBridge.log("[AAMaKer-Xposed] Lỗi hook class cảm biến ${cls.name}: ${t.message}")
        }
    }

    private fun hookDynamicParcelableSensorEvents(cl: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                Parcel::class.java,
                "readParcelable",
                ClassLoader::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val obj = param.result ?: return
                        val pkg = obj.javaClass.name
                        if (pkg.startsWith("com.google.android.gms.car") ||
                            pkg.startsWith("com.google.android.projection") ||
                            pkg.startsWith("com.google.android.apps.auto")
                        ) {
                            if (looksLikeCarSensorEvent(obj.javaClass)) {
                                hookCarSensorEventClass(obj.javaClass)
                                sanitizeCarSensorEventObject(obj)
                            }
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }

    private fun looksLikeCarSensorEvent(cls: Class<*>): Boolean {
        if (cls.simpleName.contains("CarSensorEvent", ignoreCase = true) ||
            cls.simpleName.contains("DrivingStatus", ignoreCase = true)
        ) {
            return true
        }
        var hasFloatArray = false
        var hasIntArray = false
        var intFieldCount = 0
        var longFieldCount = 0
        for (field in cls.declaredFields) {
            when (field.type) {
                FloatArray::class.java -> hasFloatArray = true
                IntArray::class.java -> hasIntArray = true
                Int::class.javaPrimitiveType -> intFieldCount++
                Long::class.javaPrimitiveType -> longFieldCount++
            }
        }
        return hasFloatArray && hasIntArray && intFieldCount >= 1 && longFieldCount >= 1
    }

    private fun sanitizeCarSensorEventObject(eventObj: Any?) {
        if (eventObj == null) return
        try {
            val cls = eventObj.javaClass
            val sensorTypeField = findSensorTypeField(cls) ?: return
            sensorTypeField.isAccessible = true
            val sensorType = sensorTypeField.getInt(eventObj)

            when (sensorType) {
                SENSOR_TYPE_DRIVING_STATUS -> {
                    zeroOutIntArrayFields(cls, eventObj)
                    zeroOutByteArrayFields(cls, eventObj)
                }
                SENSOR_TYPE_CAR_SPEED -> {
                    zeroOutFloatArrayFields(cls, eventObj)
                    zeroOutIntArrayFields(cls, eventObj)
                }
                SENSOR_TYPE_PARKING_BRAKE -> {
                    setFirstIntAndByteFieldToOne(cls, eventObj)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun findSensorTypeField(cls: Class<*>): java.lang.reflect.Field? {

        for (field in cls.declaredFields) {
            if (field.type == Int::class.javaPrimitiveType &&
                (field.name == "sensorType" || field.name == "mSensorType" || field.name == "type")
            ) {
                return field
            }
        }

        val intFields = cls.declaredFields.filter {
            it.type == Int::class.javaPrimitiveType &&
                !java.lang.reflect.Modifier.isStatic(it.modifiers)
        }
        if (intFields.size == 1) return intFields[0]
        if (intFields.size >= 2) {

            val second = intFields[1]
            val first = intFields[0]
            return if (first.name == "a" && second.name == "b") second else first
        }
        return null
    }

    private fun zeroOutIntArrayFields(cls: Class<*>, instance: Any) {
        for (field in cls.declaredFields) {
            if (field.type == IntArray::class.java && !java.lang.reflect.Modifier.isStatic(field.modifiers)) {
                field.isAccessible = true
                val arr = field.get(instance) as? IntArray ?: continue
                for (i in arr.indices) {
                    arr[i] = 0
                }
            }
        }
    }

    private fun zeroOutFloatArrayFields(cls: Class<*>, instance: Any) {
        for (field in cls.declaredFields) {
            if (field.type == FloatArray::class.java && !java.lang.reflect.Modifier.isStatic(field.modifiers)) {
                field.isAccessible = true
                val arr = field.get(instance) as? FloatArray ?: continue
                for (i in arr.indices) {
                    arr[i] = 0.0f
                }
            }
        }
    }

    private fun zeroOutByteArrayFields(cls: Class<*>, instance: Any) {
        for (field in cls.declaredFields) {
            if (field.type == ByteArray::class.java && !java.lang.reflect.Modifier.isStatic(field.modifiers)) {
                field.isAccessible = true
                val arr = field.get(instance) as? ByteArray ?: continue
                for (i in arr.indices) {
                    arr[i] = 0
                }
            }
        }
    }

    private fun setFirstIntAndByteFieldToOne(cls: Class<*>, instance: Any) {
        for (field in cls.declaredFields) {
            if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
            if (field.type == IntArray::class.java) {
                field.isAccessible = true
                val arr = field.get(instance) as? IntArray ?: continue
                if (arr.isNotEmpty()) arr[0] = 1
            } else if (field.type == ByteArray::class.java) {
                field.isAccessible = true
                val arr = field.get(instance) as? ByteArray ?: continue
                if (arr.isNotEmpty()) arr[0] = 1
            }
        }
    }

    companion object {
        private const val AAMAKER_PACKAGE = "com.aamaker.app"
        private const val GEARHEAD_PACKAGE = "com.google.android.projection.gearhead"
        private const val GMS_PACKAGE = "com.google.android.gms"
        private const val VENDING_PACKAGE = "com.android.vending"

        private const val SENSOR_TYPE_CAR_SPEED = 2
        private const val SENSOR_TYPE_PARKING_BRAKE = 6
        private const val SENSOR_TYPE_DRIVING_STATUS = 11
    }
}

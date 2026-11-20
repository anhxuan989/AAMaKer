package com.aamaker.app.stage

import android.content.Context
import android.os.Build

enum class StageBridgeMode(val key: String) {
    SHIZUKU("shizuku"),
    LOCAL_WIRELESS_ADB("local_wireless_adb");

    companion object {
        fun fromKey(key: String?): StageBridgeMode =
            entries.firstOrNull { it.key == key } ?: SHIZUKU
    }
}

data class CarStageConfig(
    val bridgeMode: StageBridgeMode = StageBridgeMode.SHIZUKU,
    val adbHost: String = "127.0.0.1",
    val adbPort: Int = 5555,
    val pinnedPackages: Set<String> = emptySet(),
    val primaryPackage: String = "",
    val secondaryPackage: String = "",
    val layoutPreset: StageLayoutPreset = StageLayoutPreset.SPLIT_50_50,
    val dockEdge: StageDockEdge = StageDockEdge.LEFT,
    val autoHideSeconds: Int = 4,
    val preferVirtualDevice: Boolean = false,
    val moveExistingTask: Boolean = true,
    val autoOrientationSync: Boolean = true,
    val keepPlaybackAlive: Boolean = true,
    val imeOnPhone: Boolean = false
)

object CarStagePrefs {
    private const val PREFS_NAME = "aamaker_car_stage_prefs"
    private const val KEY_BRIDGE_MODE = "bridge_mode"
    private const val KEY_ADB_HOST = "adb_host"
    private const val KEY_ADB_PORT = "adb_port"
    private const val KEY_PINNED_PACKAGES = "pinned_packages"
    private const val KEY_PRIMARY_PACKAGE = "primary_package"
    private const val KEY_SECONDARY_PACKAGE = "secondary_package"
    private const val KEY_LAYOUT_PRESET = "layout_preset"
    private const val KEY_DOCK_EDGE = "dock_edge"
    private const val KEY_AUTO_HIDE_SECONDS = "auto_hide_seconds"
    private const val KEY_PREFER_VIRTUAL_DEVICE = "prefer_virtual_device"
    private const val KEY_MOVE_EXISTING_TASK = "move_existing_task"
    private const val KEY_AUTO_ORIENTATION_SYNC = "auto_orientation_sync"
    private const val KEY_KEEP_PLAYBACK_ALIVE = "keep_playback_alive"
    private const val KEY_IME_ON_PHONE = "ime_on_phone"

    fun load(context: Context): CarStageConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return CarStageConfig(
            bridgeMode = StageBridgeMode.fromKey(prefs.getString(KEY_BRIDGE_MODE, StageBridgeMode.SHIZUKU.key)),
            adbHost = prefs.getString(KEY_ADB_HOST, "127.0.0.1") ?: "127.0.0.1",
            adbPort = prefs.getInt(KEY_ADB_PORT, 5555),
            pinnedPackages = prefs.getStringSet(KEY_PINNED_PACKAGES, emptySet())?.toSet() ?: emptySet(),
            primaryPackage = prefs.getString(KEY_PRIMARY_PACKAGE, "") ?: "",
            secondaryPackage = prefs.getString(KEY_SECONDARY_PACKAGE, "") ?: "",
            layoutPreset = StageLayoutPreset.fromKey(prefs.getString(KEY_LAYOUT_PRESET, StageLayoutPreset.SPLIT_50_50.key)),
            dockEdge = StageDockEdge.fromKey(prefs.getString(KEY_DOCK_EDGE, StageDockEdge.LEFT.key)),
            autoHideSeconds = prefs.getInt(KEY_AUTO_HIDE_SECONDS, 4).coerceIn(3, 10),
            preferVirtualDevice = prefs.getBoolean(KEY_PREFER_VIRTUAL_DEVICE, false),
            moveExistingTask = prefs.getBoolean(KEY_MOVE_EXISTING_TASK, true),
            autoOrientationSync = prefs.getBoolean(KEY_AUTO_ORIENTATION_SYNC, true),
            keepPlaybackAlive = prefs.getBoolean(KEY_KEEP_PLAYBACK_ALIVE, true),
            imeOnPhone = prefs.getBoolean(KEY_IME_ON_PHONE, false)
        )
    }

    fun save(context: Context, config: CarStageConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BRIDGE_MODE, config.bridgeMode.key)
            .putString(KEY_ADB_HOST, config.adbHost)
            .putInt(KEY_ADB_PORT, config.adbPort)
            .putStringSet(KEY_PINNED_PACKAGES, config.pinnedPackages)
            .putString(KEY_PRIMARY_PACKAGE, config.primaryPackage)
            .putString(KEY_SECONDARY_PACKAGE, config.secondaryPackage)
            .putString(KEY_LAYOUT_PRESET, config.layoutPreset.key)
            .putString(KEY_DOCK_EDGE, config.dockEdge.key)
            .putInt(KEY_AUTO_HIDE_SECONDS, config.autoHideSeconds)
            .putBoolean(KEY_PREFER_VIRTUAL_DEVICE, config.preferVirtualDevice)
            .putBoolean(KEY_MOVE_EXISTING_TASK, config.moveExistingTask)
            .putBoolean(KEY_AUTO_ORIENTATION_SYNC, config.autoOrientationSync)
            .putBoolean(KEY_KEEP_PLAYBACK_ALIVE, config.keepPlaybackAlive)
            .putBoolean(KEY_IME_ON_PHONE, config.imeOnPhone)
            .apply()
    }
}

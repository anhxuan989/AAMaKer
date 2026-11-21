package com.aamaker.app.stage;

import android.view.Surface;

interface ICarStageService {
    /**
     * Shizuku convention: destroy() MUST use transaction id 16777114 so Shizuku Server
     * can cleanly terminate the UserService when unbinding or when the client dies.
     */
    void destroy() = 16777114;

    /**
     * Starts or updates the Car Stage virtual display.
     * Attempts VirtualDeviceManager (Android 14+) when preferVirtualDevice is true,
     * falling back cleanly to a trusted DisplayManager VirtualDisplay.
     * Returns the created displayId, or -1 on failure.
     */
    int startStageDisplay(
        in Surface surface,
        int width,
        int height,
        int dpi,
        boolean preferVirtualDevice
    ) = 1;

    /**
     * Stops and releases the active Car Stage virtual display / virtual device.
     */
    void stopStageDisplay() = 2;

    /**
     * Launches or moves the target package onto the active Car Stage display.
     * When moveExistingTask is true, attempts IActivityTaskManager.moveRootTaskToDisplay
     * first so a running app transitions without reloading.
     * Returns true if the app was moved or launched.
     */
    boolean launchOrMoveApp(String packageName, boolean moveExistingTask) = 3;

    /**
     * Returns the active displayId, or -1 if stopped.
     */
    int getActiveDisplayId() = 4;

    /**
     * Returns the active engine mode: "VIRTUAL_DEVICE", "TRUSTED_DISPLAY", or "STOPPED".
     */
    String getEngineMode() = 5;

    /**
     * Returns the current orientation of the top task on the Car Stage display:
     * 1 = portrait, 2 = landscape, 0 = unknown.
     */
    int getTopTaskOrientation() = 6;

    /**
     * Returns the last error message, or empty string if none.
     */
    String getLastError() = 7;

    /**
     * Injects a Back key event onto the Car Stage display.
     */
    boolean sendBackKey() = 8;

    /**
     * Injects a touch tap (DOWN + UP) at (x, y) onto the Car Stage display.
     */
    boolean injectTap(float x, float y) = 9;

    /**
     * Starts or updates the Secondary (Right) VirtualDisplay for split-screen mode.
     * Returns the secondary displayId, or -1 on failure.
     */
    int startSecondaryDisplay(
        in Surface surface,
        int width,
        int height,
        int dpi
    ) = 10;

    /**
     * Stops and releases the Secondary VirtualDisplay.
     */
    void stopSecondaryDisplay() = 11;

    /**
     * Launches or moves the target package onto the Secondary (Right) VirtualDisplay.
     */
    boolean launchOrMoveSecondaryApp(String packageName, boolean moveExistingTask) = 12;

    /**
     * Injects a touch tap (DOWN + UP) at (x, y) onto the Secondary (Right) VirtualDisplay.
     */
    boolean injectTapOnSecondary(float x, float y) = 13;

    /**
     * Injects a raw MotionEvent (ACTION_DOWN / ACTION_MOVE / ACTION_UP) with explicit downTime
     * for smooth drag/scroll/swipe gestures on Primary or Secondary display.
     */
    boolean injectMotion(int action, float x, float y, long downTime, boolean isSecondary) = 14;

    /**
     * Injects a 2-finger pinch zoom gesture centered at (focusX, focusY) on Primary or Secondary display.
     */
    boolean injectPinchZoom(float focusX, float focusY, float scaleFactor, boolean isSecondary) = 15;

    /**
     * Force-stops and relaunches the target package cleanly onto Primary or Secondary display
     * to recover from black-screen states.
     */
    boolean restartAppOnDisplay(String packageName, boolean isSecondary) = 16;

    /**
     * Sets where the soft keyboard appears for Car Stage displays:
     * false = directly on the car screen (DISPLAY_IME_POLICY_LOCAL = 0),
     * true  = on the phone screen (DISPLAY_IME_POLICY_FALLBACK_DISPLAY = 1).
     */
    void setImeOnPhone(boolean onPhone) = 17;

    /**
     * Registers a client Binder token so the privileged server can linkToDeath and
     * self-terminate (releasing all VirtualDisplays) if the host app crashes or is killed.
     */
    void registerClientToken(IBinder token) = 18;

    /**
     * Resizes the active Primary VirtualDisplay in-place without destroying it or restarting apps,
     * keeping exact 1:1 pixel match with its compositor viewport.
     */
    boolean resizeStageDisplay(int width, int height, int dpi) = 19;

    /**
     * Resizes the active Secondary VirtualDisplay in-place without destroying it or restarting apps,
     * keeping exact 1:1 pixel match with its compositor viewport.
     */
    boolean resizeSecondaryDisplay(int width, int height, int dpi) = 20;

    /**
     * Enables or disables keeping Car Stage VirtualDisplays & apps awake (with the physical phone
     * panel powered off) when the phone screen turns off or times out.
     */
    void setKeepPlaybackAlive(boolean enabled) = 21;
}

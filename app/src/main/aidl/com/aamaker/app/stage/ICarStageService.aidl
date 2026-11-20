package com.aamaker.app.stage;

import android.view.Surface;

interface ICarStageService {
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
    );

    /**
     * Stops and releases the active Car Stage virtual display / virtual device.
     */
    void stopStageDisplay();

    /**
     * Launches or moves the target package onto the active Car Stage display.
     * When moveExistingTask is true, attempts IActivityTaskManager.moveRootTaskToDisplay
     * first so a running app transitions without reloading.
     * Returns true if the app was moved or launched.
     */
    boolean launchOrMoveApp(String packageName, boolean moveExistingTask);

    /**
     * Returns the active displayId, or -1 if stopped.
     */
    int getActiveDisplayId();

    /**
     * Returns the active engine mode: "VIRTUAL_DEVICE", "TRUSTED_DISPLAY", or "STOPPED".
     */
    String getEngineMode();

    /**
     * Returns the current orientation of the top task on the Car Stage display:
     * 1 = portrait, 2 = landscape, 0 = unknown.
     */
    int getTopTaskOrientation();

    /**
     * Returns the last error message, or empty string if none.
     */
    String getLastError();

    /**
     * Injects a Back key event onto the Car Stage display.
     */
    boolean sendBackKey();

    /**
     * Injects a touch tap (DOWN + UP) at (x, y) onto the Car Stage display.
     */
    boolean injectTap(float x, float y);

    /**
     * Starts or updates the Secondary (Right) VirtualDisplay for split-screen mode.
     * Returns the secondary displayId, or -1 on failure.
     */
    int startSecondaryDisplay(
        in Surface surface,
        int width,
        int height,
        int dpi
    );

    /**
     * Stops and releases the Secondary VirtualDisplay.
     */
    void stopSecondaryDisplay();

    /**
     * Launches or moves the target package onto the Secondary (Right) VirtualDisplay.
     */
    boolean launchOrMoveSecondaryApp(String packageName, boolean moveExistingTask);

    /**
     * Injects a touch tap (DOWN + UP) at (x, y) onto the Secondary (Right) VirtualDisplay.
     */
    boolean injectTapOnSecondary(float x, float y);

    /**
     * Injects a raw MotionEvent (ACTION_DOWN / ACTION_MOVE / ACTION_UP) with explicit downTime
     * for smooth drag/scroll/swipe gestures on Primary or Secondary display.
     */
    boolean injectMotion(int action, float x, float y, long downTime, boolean isSecondary);

    /**
     * Injects a 2-finger pinch zoom gesture centered at (focusX, focusY) on Primary or Secondary display.
     */
    boolean injectPinchZoom(float focusX, float focusY, float scaleFactor, boolean isSecondary);

    /**
     * Force-stops and relaunches the target package cleanly onto Primary or Secondary display
     * to recover from black-screen states.
     */
    boolean restartAppOnDisplay(String packageName, boolean isSecondary);

    /**
     * Sets where the soft keyboard appears for Car Stage displays:
     * false = directly on the car screen (DISPLAY_IME_POLICY_LOCAL = 0),
     * true  = on the phone screen (DISPLAY_IME_POLICY_FALLBACK_DISPLAY = 1).
     */
    void setImeOnPhone(boolean onPhone);

    /**
     * Cleanly terminates the privileged UserService process.
     */
    void destroy();
}

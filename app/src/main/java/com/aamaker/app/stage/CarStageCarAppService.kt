package com.aamaker.app.stage

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.aamaker.app.i18n.AppLanguage
import com.aamaker.app.i18n.CarStageLocalization
import com.aamaker.app.input.PhoneRotationStateGuard

/**
 * Dedicated Android Auto CarAppService for the isolated Car Stage feature.
 * Receives the real head unit Surface and forwards it + touch events to CarStageBridge,
 * with a minimalist ActionStrip and an in-car pinned-app picker.
 */
class CarStageCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = CarStageSession()
}

private class CarStageSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        val appCtx = carContext.applicationContext
        PhoneRotationStateGuard.captureBeforeCarSession(appCtx)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                PhoneRotationStateGuard.restoreAfterCarSession(appCtx)
            }
        })
        return CarStageProjectionScreen(carContext)
    }
}

private class CarStageProjectionScreen(carContext: CarContext) : Screen(carContext) {

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            runCatching {
                val surface = surfaceContainer.surface ?: return
                if (!surface.isValid) return
                CarStageBridge.attachCarSurface(
                    context = carContext.applicationContext,
                    surface = surface,
                    width = surfaceContainer.width,
                    height = surfaceContainer.height,
                    dpi = surfaceContainer.dpi
                )
            }
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {
            runCatching { CarStageBridge.updateVisibleArea(visibleArea) }
        }

        override fun onStableAreaChanged(stableArea: Rect) {
            runCatching { CarStageBridge.updateStableArea(stableArea) }
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            runCatching { CarStageBridge.detachCarSurface() }
        }

        override fun onClick(x: Float, y: Float) {
            runCatching { CarStageBridge.injectTap(x, y) }
        }

        override fun onScroll(distanceX: Float, distanceY: Float) {
            runCatching { CarStageBridge.handleScroll(distanceX, distanceY) }
        }

        override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
            runCatching { CarStageBridge.handleScale(focusX, focusY, scaleFactor) }
        }
    }

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
    }

    override fun onGetTemplate(): Template {
        // Controls are strictly fixed at the top-left corner inside CarStageSplitCompositor.
        // Passing Action.PAN satisfies NavigationTemplate's mandatory ActionStrip check while
        // Android Auto automatically filters out TYPE_PAN on touch head units (no overlapping dark circle).
        val stripBuilder = ActionStrip.Builder()
            .addAction(Action.PAN)

        return NavigationTemplate.Builder()
            .setActionStrip(stripBuilder.build())
            .setMapActionStrip(
                ActionStrip.Builder()
                    .addAction(Action.PAN)
                    .build()
            )
            .setPanModeListener { }
            .build()
    }
}

private class CarStageAppPickerScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val strings = CarStageLocalization.of(AppLanguage.loadSavedLanguage(carContext))
        val pinned = CarStagePrefs.load(carContext).pinnedPackages.toList()

        if (pinned.isEmpty()) {
            return MessageTemplate.Builder(strings.carPickerEmpty)
                .setTitle(strings.carPickerTitle)
                .setHeaderAction(Action.BACK)
                .build()
        }

        val pm = carContext.packageManager
        val items = pinned.map { pkg ->
            val label = runCatching {
                val info = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(info).toString()
            }.getOrDefault(pkg)
            val icon = runCatching {
                drawableToCarIcon(pm.getApplicationIcon(pkg))
            }.getOrNull() ?: CarIcon.APP_ICON
            Triple(pkg, label, icon)
        }.sortedBy { it.second.lowercase() }

        val listBuilder = ItemList.Builder()
        // Android Auto GridTemplate supports up to 6 items per list on strict hosts; cap safely at 6
        items.take(6).forEach { (pkg, label, carIcon) ->
            listBuilder.addItem(
                GridItem.Builder()
                    .setTitle(label)
                    .setImage(carIcon, GridItem.IMAGE_TYPE_ICON)
                    .setOnClickListener {
                        runCatching {
                            CarStageBridge.launchOrMoveApp(pkg)
                            screenManager.pop()
                        }
                    }
                    .build()
            )
        }

        val splitStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle(strings.carSplitButton)
                    .setOnClickListener {
                        runCatching { screenManager.push(CarStageSplitScreen(carContext)) }
                    }
                    .build()
            )
            .build()

        return GridTemplate.Builder()
            .setTitle(strings.carPickerTitle)
            .setHeaderAction(Action.BACK)
            .setActionStrip(splitStrip)
            .setSingleList(listBuilder.build())
            .build()
    }

    private fun drawableToCarIcon(drawable: Drawable, sizePx: Int = 96): CarIcon {
        val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
            Bitmap.createScaledBitmap(drawable.bitmap, sizePx, sizePx, true)
        } else {
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(canvas)
            bmp
        }
        return CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
    }
}

private class CarStageSplitScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val strings = CarStageLocalization.of(AppLanguage.loadSavedLanguage(carContext))
        val cfg = CarStagePrefs.load(carContext)

        val presets = listOf(
            StageLayoutPreset.SINGLE to strings.layoutSingle,
            StageLayoutPreset.SPLIT_50_50 to "50:50",
            StageLayoutPreset.SPLIT_60_40 to "60:40",
            StageLayoutPreset.SPLIT_70_30 to "70:30"
        )

        val listBuilder = ItemList.Builder()
        presets.forEach { (preset, label) ->
            val title = if (cfg.layoutPreset == preset) "✓ $label" else label
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(title)
                    .setOnClickListener {
                        runCatching {
                            CarStageBridge.applySplitLayout(carContext.applicationContext, preset)
                            // Return directly to the live Surface: App 1 runs live on the left,
                            // and the in-surface OLED app picker grid appears on the right!
                            screenManager.popToRoot()
                        }
                    }
                    .build()
            )
        }

        if (cfg.layoutPreset.isSplit) {
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(strings.btnSwapPanes)
                    .setOnClickListener {
                        runCatching {
                            CarStageBridge.swapStageSlots(carContext.applicationContext)
                            screenManager.popToRoot()
                        }
                    }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setTitle(strings.carSplitButton)
            .setHeaderAction(Action.BACK)
            .setSingleList(listBuilder.build())
            .build()
    }
}

/**
 * Displays App 1 live on the Surface (left) while showing the pinned app list
 * on the right panel so the user can pick the 2nd app for split-screen.
 */
private class CarStageSecondaryPickerScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val strings = CarStageLocalization.of(AppLanguage.loadSavedLanguage(carContext))
        val cfg = CarStagePrefs.load(carContext)
        val pm = carContext.packageManager
        val candidates = cfg.pinnedPackages
            .filter { it != cfg.primaryPackage }
            .ifEmpty { cfg.pinnedPackages.toList() }

        val listBuilder = ItemList.Builder()
        candidates.take(6).forEach { pkg ->
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(label)
                    .setOnClickListener {
                        runCatching {
                            CarStageBridge.selectSecondaryApp(carContext.applicationContext, pkg)
                            screenManager.popToRoot()
                        }
                    }
                    .build()
            )
        }

        val listTemplate = ListTemplate.Builder()
            .setTitle(strings.carPickSecondAppTitle)
            .setHeaderAction(Action.BACK)
            .setSingleList(listBuilder.build())
            .build()

        // Use MapWithContentTemplate on Car API 7+ so the live Surface (App 1) stays visible beside the list
        return if (carContext.carAppApiLevel >= 7) {
            runCatching {
                MapWithContentTemplate.Builder()
                    .setContentTemplate(listTemplate)
                    .build()
            }.getOrDefault(listTemplate)
        } else {
            listTemplate
        }
    }
}

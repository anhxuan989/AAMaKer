package com.mag.anyauto.car;

import android.content.ComponentName;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.constraints.ConstraintManager;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.GridItem;
import androidx.car.app.model.GridTemplate;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.Template;
import androidx.core.graphics.drawable.IconCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.aamaker.app.i18n.AppLanguage;
import com.aamaker.app.i18n.AppStrings;
import com.aamaker.app.i18n.AppTranslations;
import com.mag.anyauto.Apps;
import com.mag.anyauto.Mirror;
import com.mag.anyauto.Prefs;

import java.util.List;

/**
 * The apps chosen on the phone, as a grid by name; "Open" under the one on the display now. A
 * tap shows the app on the mirror screen below. Header: Back when an app is on the display
 * (back to it), the app icon on first use.
 */
final class AppsScreen extends Screen {
    // Keep icons compact and paginate so the Binder parcel never exceeds Android's 1 MB limit
    // even when 100+ phone apps are enabled by default.
    private static final int ICON_PX = 64;
    private static final int MAX_ITEMS_PER_PAGE = 18;
    private final Mirror mirror;
    private int page = 0;

    AppsScreen(CarContext c, Mirror mirror) {
        super(c);
        this.mirror = mirror;
        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onStart(@NonNull LifecycleOwner owner) {
                // the grid is part of using AnyAuto: keep the phone dark while it shows
                mirror.hold(true);
                // apps ticked on the phone meanwhile
                invalidate();
            }

            @Override
            public void onStop(@NonNull LifecycleOwner owner) {
                mirror.hold(false);
            }
        });
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        CarContext c = getCarContext();
        AppStrings strings = AppTranslations.INSTANCE.forLanguage(AppLanguage.Companion.loadSavedLanguage(c));
        List<Apps.App> apps = Apps.chosen(c);
        if (apps.isEmpty()) {
            return new MessageTemplate.Builder(strings.getAaLauncherCarEmptyMessage())
                    .setTitle(c.getString(com.aamaker.app.R.string.app_name))
                    .setHeaderAction(Action.APP_ICON)
                    .setIcon(CarIcon.APP_ICON)
                    .build();
        }
        ComponentName open = mirror.currentApp();
        int hostLimit = c.getCarService(ConstraintManager.class)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID);
        int pageSize = Math.max(1, Math.min(hostLimit > 0 ? hostLimit : MAX_ITEMS_PER_PAGE, MAX_ITEMS_PER_PAGE));
        int totalPages = (apps.size() + pageSize - 1) / pageSize;
        if (page >= totalPages) page = totalPages - 1;
        if (page < 0) page = 0;

        int start = page * pageSize;
        int end = Math.min(start + pageSize, apps.size());

        PackageManager pm = c.getPackageManager();
        ItemList.Builder list = new ItemList.Builder();
        for (int i = start; i < end; i++) {
            Apps.App a = apps.get(i);
            CarIcon icon;
            try {
                android.graphics.drawable.Drawable d = a.icon(pm);
                if (d != null) {
                    icon = new CarIcon.Builder(IconCompat.createWithBitmap(Apps.bitmap(d, ICON_PX))).build();
                } else {
                    icon = CarIcon.APP_ICON;
                }
            } catch (Throwable ignored) {
                icon = CarIcon.APP_ICON;
            }
            String title = (a.label != null && !a.label.trim().isEmpty())
                    ? a.label
                    : a.component.getPackageName();
            GridItem.Builder item = new GridItem.Builder()
                    .setTitle(title)
                    .setImage(icon, GridItem.IMAGE_TYPE_LARGE)
                    .setOnClickListener(() -> {
                        Prefs.setLast(c, a.component);
                        mirror.open(a.component);
                        getScreenManager().pop();
                    });
            if (a.component.equals(open)) item.setText(strings.getAaLauncherCarAppOpenLabel());
            list.addItem(item.build());
        }

        String headerTitle = totalPages > 1
                ? strings.getAaLauncherCarAppsTitle() + " (" + (page + 1) + "/" + totalPages + ")"
                : strings.getAaLauncherCarAppsTitle();

        GridTemplate.Builder builder = new GridTemplate.Builder()
                .setTitle(headerTitle)
                .setHeaderAction(open != null ? Action.BACK : Action.APP_ICON)
                .setSingleList(list.build());

        if (totalPages > 1) {
            ActionStrip.Builder strip = new ActionStrip.Builder();
            if (page > 0) {
                strip.addAction(new Action.Builder()
                        .setIcon(new CarIcon.Builder(
                                IconCompat.createWithResource(c, com.aamaker.app.R.drawable.ic_chevron_left)).build())
                        .setOnClickListener(() -> {
                            page--;
                            invalidate();
                        })
                        .build());
            }
            if (page < totalPages - 1) {
                strip.addAction(new Action.Builder()
                        .setIcon(new CarIcon.Builder(
                                IconCompat.createWithResource(c, com.aamaker.app.R.drawable.ic_chevron_right)).build())
                        .setOnClickListener(() -> {
                            page++;
                            invalidate();
                        })
                        .build());
            }
            builder.setActionStrip(strip.build());
        }

        return builder.build();
    }
}

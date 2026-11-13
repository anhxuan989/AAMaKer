package com.aamaker.stub.runtime;

import android.app.Service;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import dalvik.system.InMemoryDexClassLoader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

public class AAMakerCarAppService extends Service {

    private static volatile ClassLoader cachedIsolatedLoader;
    private Service delegateService;

    private static final class ChildFirstParentClassLoader extends ClassLoader {
        private final ClassLoader hostLoader;

        ChildFirstParentClassLoader(ClassLoader hostLoader) {
            super(ClassLoader.getSystemClassLoader().getParent());
            this.hostLoader = hostLoader;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {

            try {
                return super.loadClass(name, resolve);
            } catch (ClassNotFoundException ignored) {
            }

            boolean shouldIsolateFromHost =
                name.startsWith("com.aamaker.stub.") ||
                name.startsWith("androidx.car.app.") ||
                name.startsWith("androidx.lifecycle.") ||
                name.startsWith("androidx.core.") ||
                name.startsWith("androidx.arch.core.") ||
                name.startsWith("androidx.versionedparcelable.") ||
                name.startsWith("androidx.media.") ||
                name.startsWith("androidx.annotation.") ||
                name.startsWith("kotlin.") ||
                name.startsWith("kotlinx.") ||
                name.startsWith("com.google.common.");

            if (shouldIsolateFromHost && !name.equals("com.aamaker.stub.runtime.AAMakerCarAppService")) {
                throw new ClassNotFoundException(name);
            }

            return hostLoader.loadClass(name);
        }
    }

    private static final class IsolatedContextWrapper extends ContextWrapper {
        private final ClassLoader isolatedLoader;

        IsolatedContextWrapper(Context base, ClassLoader isolatedLoader) {
            super(base);
            this.isolatedLoader = isolatedLoader;
        }

        @Override
        public ClassLoader getClassLoader() {
            return isolatedLoader;
        }
    }

    private byte[] readRuntimeChunk(String assetName) {
        try {
            InputStream rawStream = getAssets().open(assetName);
            ByteArrayOutputStream rawBuffer = new ByteArrayOutputStream();
            byte[] temp = new byte[16384];
            int read;
            while ((read = rawStream.read(temp, 0, temp.length)) != -1) {
                rawBuffer.write(temp, 0, read);
            }
            rawStream.close();
            byte[] rawBytes = rawBuffer.toByteArray();
            if (rawBytes.length >= 2 && (rawBytes[0] & 0xFF) == 0x1F && (rawBytes[1] & 0xFF) == 0x8B) {
                GZIPInputStream gzipIn = new GZIPInputStream(new java.io.ByteArrayInputStream(rawBytes));
                ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
                while ((read = gzipIn.read(temp, 0, temp.length)) != -1) {
                    outBuffer.write(temp, 0, read);
                }
                gzipIn.close();
                return outBuffer.toByteArray();
            }
            return rawBytes;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private List<ByteBuffer> loadIsolatedDexBuffers() {
        List<ByteBuffer> buffers = new ArrayList<>(2);
        byte[] primary = readRuntimeChunk("aa_car_res.dat");
        if (primary == null) {
            primary = readRuntimeChunk("aa_car_runtime.bin");
        }
        if (primary != null && primary.length > 0) {
            buffers.add(ByteBuffer.wrap(primary));
        }
        int idx = 2;
        while (true) {
            byte[] extra = readRuntimeChunk("aa_car_res_" + idx + ".dat");
            if (extra == null) {
                extra = readRuntimeChunk("aa_car_runtime_" + idx + ".bin");
            }
            if (extra == null || extra.length == 0) {
                break;
            }
            buffers.add(ByteBuffer.wrap(extra));
            idx++;
        }
        return buffers;
    }

    private synchronized Service getOrCreateDelegate() {
        if (delegateService != null) {
            return delegateService;
        }

        ClassLoader loader = cachedIsolatedLoader;
        if (loader == null) {
            loader = getClassLoader();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    List<ByteBuffer> dexBuffers = loadIsolatedDexBuffers();
                    if (!dexBuffers.isEmpty()) {
                        ClassLoader filteringParent = new ChildFirstParentClassLoader(getClassLoader());
                        loader = new InMemoryDexClassLoader(
                            dexBuffers.toArray(new ByteBuffer[0]),
                            filteringParent
                        );
                        cachedIsolatedLoader = loader;
                    }
                } catch (Throwable ignored) {
                    loader = getClassLoader();
                }
            }
        }

        try {
            Class<?> implClass = loader.loadClass("com.aamaker.stub.runtime.AAMakerCarAppServiceImpl");
            Service instance = (Service) implClass.getDeclaredConstructor().newInstance();

            Context base = getBaseContext() != null ? getBaseContext() : this;
            IsolatedContextWrapper wrappedContext = new IsolatedContextWrapper(base, loader);

            Method attachMethod = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
            attachMethod.setAccessible(true);
            attachMethod.invoke(instance, wrappedContext);

            instance.onCreate();
            delegateService = instance;
            return instance;
        } catch (Throwable t) {
            throw new RuntimeException("Failed to initialize isolated AAMakerCarAppServiceImpl", t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        getOrCreateDelegate();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return getOrCreateDelegate().onBind(intent);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (delegateService != null) {
            return delegateService.onUnbind(intent);
        }
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (delegateService != null) {
            delegateService.onDestroy();
            delegateService = null;
        }
        super.onDestroy();
    }
}

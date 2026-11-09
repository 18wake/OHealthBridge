package io.github.libxposed.api;

import android.app.AppComponentFactory;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

/** 编译桩(签名与 API 101 一致, 不含 102 的热重载可选回调)。 */
@SuppressWarnings("unused")
public interface XposedModuleInterface {

    interface ModuleLoadedParam {
        boolean isSystemServer();

        @NonNull
        String getProcessName();
    }

    interface PackageLoadedParam {
        @NonNull
        String getPackageName();

        @NonNull
        ApplicationInfo getApplicationInfo();

        boolean isFirstPackage();

        @RequiresApi(Build.VERSION_CODES.Q)
        @NonNull
        ClassLoader getDefaultClassLoader();
    }

    interface PackageReadyParam extends PackageLoadedParam {
        @NonNull
        ClassLoader getClassLoader();

        @RequiresApi(Build.VERSION_CODES.P)
        @NonNull
        AppComponentFactory getAppComponentFactory();
    }

    interface SystemServerStartingParam {
        @NonNull
        ClassLoader getClassLoader();
    }

    default void onModuleLoaded(@NonNull ModuleLoadedParam param) {
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    default void onPackageLoaded(@NonNull PackageLoadedParam param) {
    }

    default void onPackageReady(@NonNull PackageReadyParam param) {
    }

    default void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
    }
}

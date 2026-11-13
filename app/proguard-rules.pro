# Proguard / R8 rules for AAMaKer

# Giữ thông tin debug line numbers để xem stacktrace nếu có lỗi
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Xposed Hook & Stub Runtime (bắt buộc giữ nguyên tên class/method để Xposed & Android Auto gọi qua reflection)
-keep class com.aamaker.xposed.** { *; }
-keep class com.aamaker.stub.runtime.** { *; }
-dontwarn de.robv.android.xposed.**

# Android Auto Car App Library
-keep class androidx.car.app.** { *; }
-dontwarn androidx.car.app.**

# ARSCLib & ApkSig (xử lý binary manifest & ký APK)
-keep class com.reandroid.** { *; }
-keep class com.android.apksig.** { *; }
-dontwarn com.reandroid.**
-dontwarn com.android.apksig.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**

# Shizuku & Libsu (Root/Binder IPC)
-keep class rikka.shizuku.** { *; }
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn com.topjohnwu.superuser.**

# Kotlin Coroutines
-dontwarn kotlinx.coroutines.**


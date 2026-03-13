# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Shizuku
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }

# SLF4J (Common Compress, Junrar)
-dontwarn org.slf4j.**

# Okio
-dontwarn okio.**

# Commons Compress
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**

# Core Models
-keep class com.alisu.filex.core.** { *; }
-keep enum com.alisu.filex.core.** { *; }

# Preserve line numbers for better crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

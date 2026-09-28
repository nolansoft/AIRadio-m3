# This file is the canonical source of truth for ProGuard/R8 minification keep
# rules used by the AIRadio app. Historically the keep rules below referenced
# the wrong (placeholder) package name, which never matched the real package
# declared in `app/build.gradle.kts`. They were corrected to
# `com.nolansoftware.airadio` as part of the open-source compliance
# remediation. Note: rules only activate when `isMinifyEnabled = true` in
# `app/build.gradle.kts`; the file must still be parseable by Gradle either way.

# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Retrofit
-keep class com.nolansoftware.airadio.data.api.model.** { *; }
-keep class com.nolansoftware.airadio.data.database.entity.** { *; }
-keep class com.nolansoftware.airadio.domain.model.** { *; }

# ExoPlayer
-keep class com.google.android.exoplayer2.** { *; }
-dontwarn com.google.android.exoplayer2.**

# Hilt
-keep class dagger.hilt.** { *; }
-dontwarn dagger.hilt.**

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
-keep class com.nolansoftware.airadio.domain.model.** { *; }

# UMP lacks consumer rules — must keep reflection-loaded classes.
-keep class com.google.android.ump.** { *; }

# Media3 HLS — preemptive warning suppression.
-dontwarn androidx.media3.exoplayer.hls.**

# Retrofit + Kotlin coroutines + R8 full mode.
#
# AGP 8.x enables R8 full mode by default, which is more aggressive at
# stripping generic signatures than compat mode. Retrofit 2.9.0's
# bundled rules (META-INF/proguard/retrofit2.pro) keep `Signature` and
# keep the @retrofit2.http.*-annotated interfaces, but they do NOT
# keep the generic signature of the `Continuation` parameter that the
# Kotlin compiler emits for `suspend fun` Retrofit methods.
#
# Without this rule the first call into any `suspend` Retrofit method
# (e.g. `MirrorRegistryApi.getServers()` on cold start, or any
# `RadioBrowserApi.<method>()` from a paging chunk) fails with
# `java.lang.ClassCastException: java.lang.Class cannot be cast to
# java.lang.reflect.ParameterizedType` because the proxy invocation
# casts the Continuation parameter type to `ParameterizedType` to
# extract the actual return type (`List<MirrorEntry>`,
# `List<ApiStation>`, etc.). The exception surfaces in the
# `SyncStatusBanner` as `Sync failed: ...` on first run after install
# from Play Store.
#
# Retrofit 2.10+ added this rule to its bundled proguard file. We pin
# it explicitly here until we upgrade Retrofit.
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# MirrorEntry lives in `com.nolansoftware.airadio.data.api` (NOT in the
# `data.api.model.**` package covered by the generic Gson keep rule
# above), so R8 minification would otherwise rename its `name` and
# `ip` fields, after which Gson's field-name matcher would silently
# produce empty/default values when parsing the /json/servers
# response. Pin it explicitly.
-keep class com.nolansoftware.airadio.data.api.MirrorEntry { *; }

# Release shrinking rules. MapLibre and OkHttp ship their own consumer rules; these cover
# reflective entry points Gradle/manifest reference by name and keep line numbers for crashes.

# Keep manifest-declared components' names (R8 already keeps these, but be explicit for the
# Glance widget receiver and services resolved by the system).
-keep class com.odesaplay.oko.widget.ThreatWidgetReceiver { *; }
-keep class com.odesaplay.oko.service.AlertService { *; }
-keep class com.odesaplay.oko.service.BootReceiver { *; }
-keep class com.odesaplay.oko.service.NeutralizedDismissReceiver { *; }
-keep class com.odesaplay.oko.service.AlertWatchdog { *; }
-keep class com.odesaplay.oko.service.EmergencyResurrectionWorker { *; }

# Keep source file/line info for readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Glance app widgets interact through generated/reflective entry points.
-keep class androidx.glance.** { *; }

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# MapLibre native bridge
-dontwarn org.maplibre.**
-keep class org.maplibre.android.** { *; }

# org.json (shaded on Android; keep the public API from being shrunk away where used reflectively)
-dontwarn org.json.**

# R8 / ProGuard rules for the release build.

# osmdroid loads tile sources and configuration via reflection — keep it whole.
-keep class org.osmdroid.** { *; }
-keep interface org.osmdroid.** { *; }

# MapLibre Native JNI and reflection
-keep class org.maplibre.android.** { *; }
-keep interface org.maplibre.android.** { *; }
-keep class com.mapbox.geojson.** { *; }


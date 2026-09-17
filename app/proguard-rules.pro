# R8 / ProGuard rules for the release build.

# MapLibre Native JNI and reflection
-keep class org.maplibre.android.** { *; }
-keep interface org.maplibre.android.** { *; }
-keep class com.mapbox.geojson.** { *; }


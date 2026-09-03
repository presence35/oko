# R8 / ProGuard rules for the release build.

# osmdroid loads tile sources and configuration via reflection — keep it whole.
-keep class org.osmdroid.** { *; }
-keep interface org.osmdroid.** { *; }

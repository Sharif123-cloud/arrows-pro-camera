# Arrows Pro Camera — ProGuard / R8 rules

# ── OpenCV ────────────────────────────────────────────────────────────────────
-keep class org.opencv.** { *; }
-keepclassmembers class org.opencv.** { *; }
-dontwarn org.opencv.**

# ── TensorFlow Lite core ──────────────────────────────────────────────────────
-keep class org.tensorflow.lite.** { *; }
-keepclassmembers class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# ── TFLite GPU delegate ───────────────────────────────────────────────────────
# GpuDelegateFactory and its inner classes are loaded reflectively at runtime;
# some are missing from the AAR's class manifest — suppress R8 errors.
-keep class org.tensorflow.lite.gpu.** { *; }
-keepclassmembers class org.tensorflow.lite.gpu.** { *; }
-dontwarn org.tensorflow.lite.gpu.**

# ── AutoValue annotations (referenced by tensorflow-lite-support) ─────────────
-keep class com.google.auto.value.** { *; }
-dontwarn com.google.auto.value.**

# ── Application classes ───────────────────────────────────────────────────────
-keep class com.arrowspro.camera.** { *; }

# ── Kotlin ────────────────────────────────────────────────────────────────────
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-dontwarn kotlin.**

# ── AndroidX ──────────────────────────────────────────────────────────────────
-keep class androidx.** { *; }
-dontwarn androidx.**

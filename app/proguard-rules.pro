# Arrows Pro Camera — ProGuard rules

# Keep OpenCV classes
-keep class org.opencv.** { *; }
-keepclassmembers class org.opencv.** { *; }

# Keep TFLite classes
-keep class org.tensorflow.lite.** { *; }
-keepclassmembers class org.tensorflow.lite.** { *; }

# AutoValue annotations referenced by tensorflow-lite-support
# The annotation processor is not needed at runtime; suppress R8 warnings.
-dontwarn com.google.auto.value.**
-keep class com.google.auto.value.** { *; }

# Keep our own classes
-keep class com.arrowspro.camera.** { *; }

# Kotlin
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# AndroidX
-keep class androidx.** { *; }
-dontwarn androidx.**

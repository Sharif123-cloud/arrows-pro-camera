# Arrows Pro Camera — ProGuard rules

# Keep OpenCV classes
-keep class org.opencv.** { *; }
-keepclassmembers class org.opencv.** { *; }

# Keep TFLite classes
-keep class org.tensorflow.lite.** { *; }
-keepclassmembers class org.tensorflow.lite.** { *; }

# Keep our own classes
-keep class com.arrowspro.camera.** { *; }

# Kotlin
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# AndroidX
-keep class androidx.** { *; }
-dontwarn androidx.**

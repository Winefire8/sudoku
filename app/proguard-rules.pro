# Keep OpenCV's Java API and loader
-keep class org.opencv.** { *; }

# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

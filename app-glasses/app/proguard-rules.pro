# Duchess Glasses ProGuard rules

# Keep TFLite classes
-keep class org.tensorflow.lite.** { *; }

# Keep Detection model for potential serialization
-keep class com.duchess.glasses.model.Detection { *; }

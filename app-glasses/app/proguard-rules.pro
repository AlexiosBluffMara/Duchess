# Duchess Glasses ProGuard rules

# Keep LiteRT classes
-keep class com.google.ai.edge.litert.** { *; }

# Keep Detection model for potential serialization
-keep class com.duchess.glasses.model.Detection { *; }

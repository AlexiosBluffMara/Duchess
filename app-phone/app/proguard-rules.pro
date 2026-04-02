# Duchess ProGuard rules

# Keep DAT SDK classes
-keep class com.meta.wearable.dat.** { *; }

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.** { *; }

# Keep SafetyAlert for serialization
-keep class com.duchess.companion.model.SafetyAlert { *; }

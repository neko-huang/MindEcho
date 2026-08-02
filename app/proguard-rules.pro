# MindEcho ProGuard Rules

# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-keep class com.moodecho.app.data.api.** { *; }
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

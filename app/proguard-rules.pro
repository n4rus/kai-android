# JNI — Rust exports Java_com_axiom_kai_KaiBridge_* symbols by exact name
-keep class com.axiom.kai.KaiBridge { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Room generated implementations are resolved by name
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }

# PDF text extraction via pdfbox reflection
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**

# Keep Kotlin metadata for reflection-free libs is unnecessary, but keep enum values used by name
-keepclassmembers enum * { *; }

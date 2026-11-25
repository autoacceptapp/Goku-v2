# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

-keepattributes SourceFile,LineNumberTable
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# ============================================================================
# GOKU V10.1 RELEASE R8/PROGUARD KEEP RULES
# ============================================================================

# 1. Mozilla Rhino Embedded JavaScript Engine + GokuJsBridge reflection target
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-keep class com.goku.ai.automation.GokuJsBridge { *; }

# 2. Native Zstd-JNI Compression (Memory Vault & Screen Timeline)
-keep class com.github.luben.zstd.** { *; }
-dontwarn com.github.luben.zstd.**

# 3. MediaPipe GenAI / LiteRT On-Device LLM Runtime
-keep class com.google.mediapipe.** { *; }
-keep class com.google.mediapipe.tasks.genai.** { *; }
-keep class com.google.ai.edge.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.ai.edge.**

# 4. Optional Sherpa-ONNX Wake-Word Reflection Bridge
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# 5. BouncyCastle & Conscrypt (Local ADB TLS 1.3 + Ed25519/SPAKE2)
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**

# 6. Room Database Entities, DAOs, FTS4 & Serialization / JSON Models
-keep class com.goku.ai.data.** { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>(...);
}
-keep @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}

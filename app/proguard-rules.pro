# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Gson
-keepattributes Signature
-keep class com.google.gson.** { *; }
-keep class com.openclaw.assistant.api.** { *; }


# Google Error Prone Annotations
-dontwarn com.google.errorprone.annotations.**

# Tink (Security Crypto)
-dontwarn com.google.crypto.tink.**

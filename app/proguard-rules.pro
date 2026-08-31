# Default ProGuard rules for the app.
# Keep line numbers for readable crash stack traces.
-keepattributes SourceFile,LineNumberTable

# ---------------------------------------------------------------------------------------------
# On-device ML runtime (ONNX Runtime Mobile + sherpa-onnx)
#
# Both libraries cross the JNI boundary in the direction R8 cannot see: native code resolves
# Java classes, methods and fields *by name* at runtime. Anything reachable only from native
# code looks unused to the shrinker, so it gets stripped and the app dies with a
# NoSuchMethodError the first time a model is loaded. Keep the whole surface of both.
# ---------------------------------------------------------------------------------------------

-keep class ai.onnxruntime.** { *; }
-keep interface ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep interface com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# Native method holders and the classes declaring them, wherever they live.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Config / result objects are constructed or read field-by-field from native code, so their
# members must keep their original names even inside our own package.
-keepclassmembers class com.sih.itantra.** {
    native <methods>;
}

# LibXposed API 102 entry point resolved from META-INF/xposed/java_init.list
-keep class org.francasio.sing.SingXposedEntry { *; }

# Keep module classes and ONNX Runtime
-keep class org.francasio.sing.** { *; }
-keep class ai.onnxruntime.** { *; }

-keepclasseswithmembernames class * {
    native <methods>;
}

# libxposed API is compileOnly: supplied at runtime by Vector/LSPatch/LSPosed
-dontwarn io.github.libxposed.api.**

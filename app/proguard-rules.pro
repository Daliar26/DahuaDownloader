# Dahua NetSDK вызывается из нативного кода (JNI) по именам классов и полей.
# Без этих правил R8 в release-сборке всё переименует и SDK перестанет работать.
-keep class com.company.NetSDK.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

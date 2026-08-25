# Termux JNI binding must not be renamed or stripped
-keep class com.termux.terminal.JNI { *; }
-keep class com.termux.terminal.TerminalSession { *; }
-keep class com.termux.terminal.TerminalEmulator { *; }
-keep class com.termux.view.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class dev.autopilot.terminal.data.** {
    *** Companion;
}
-keepclasseswithmembers class dev.autopilot.terminal.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.autopilot.terminal.**$$serializer { *; }
-keepclassmembers class dev.autopilot.terminal.agent.** {
    *** Companion;
}
-keepclasseswithmembers class dev.autopilot.terminal.agent.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / Conscrypt
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**

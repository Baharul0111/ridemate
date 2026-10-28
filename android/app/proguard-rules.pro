# kotlinx.serialization: keep generated serializers for @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.ridemate.**$$serializer { *; }
-keepclassmembers class app.ridemate.** { *** Companion; }
-keepclasseswithmembers class app.ridemate.** { kotlinx.serialization.KSerializer serializer(...); }
# RE2/J is reflection-free; nothing to keep.
# Crashlytics: keep file names and line numbers for readable stack traces (no message text is logged, B12).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

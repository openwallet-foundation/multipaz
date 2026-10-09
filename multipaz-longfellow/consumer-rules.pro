-keep class org.multipaz.mdoc.zkp.longfellow.** { *; }

# JNA rules
-keepclassmembers class * extends com.sun.jna.Structure {
    <fields>;
}
-keep interface * extends com.sun.jna.Library {
    <methods>;
}
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

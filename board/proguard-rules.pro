# Vendor init loads this entry point by its fixed class name from the installed APK.
-keep class com.shilapi.xcertplay.board.BoardProvisioner { public static void main(java.lang.String[]); }
-keepattributes SourceFile,LineNumberTable
# JNI entry points use fixed JVM names.
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }


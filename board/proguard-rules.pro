# Vendor init loads this entry point by its fixed class name from the installed APK.
-keep class com.shilapi.xcertplay.board.BoardProvisioner { public static void main(java.lang.String[]); }
-keepattributes SourceFile,LineNumberTable
# JNI entry points use fixed JVM names.
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# Framework receiver Binder keeps a weak reference to this dispatcher.
# Preserve the root helper strong reference even though it is only assigned in Java.
-keepclassmembers class com.shilapi.xcertplay.board.BoardProvisioner { private static java.lang.Object pairingDispatcher; }

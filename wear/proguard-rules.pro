# Shrink, don't rename: crash traces stay readable and nothing resolved by name can break.
-dontobfuscate

# NewPipe Extractor (from its README): Rhino runs YouTube's player JavaScript, partly by reflection.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn org.mozilla.javascript.engine.**
# The extractor and its parsers are what changes when YouTube does; keep them whole so an update
# never trips on a stripped method.
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn com.google.re2j.**
-dontwarn org.jspecify.**
-dontwarn javax.annotation.**

-keep class com.wristtube.update.InstallResultReceiver { *; }
-keep class com.wristtube.play.PlaybackService { *; }

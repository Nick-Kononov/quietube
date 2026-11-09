# Readable stack traces matter more than the small size win from renaming.
-dontobfuscate

# NewPipeExtractor loads localisation patterns and services reflectively, and runs YouTube's player
# JavaScript through Rhino, which generates and loads classes at runtime. Neither ships consumer rules.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-dontwarn javax.script.**
-dontwarn java.beans.**
# Desktop-JVM invokedynamic linker used only by Rhino's compiled mode; Android runs Rhino interpreted.
-dontwarn jdk.dynalink.**

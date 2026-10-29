# Companion preview builds: shrink as usual, but keep class and method names so crash reports and
# diagnostics from testers are readable without a mapping file.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

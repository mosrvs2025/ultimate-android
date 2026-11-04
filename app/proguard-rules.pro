# Compose, AndroidX and Media3 ship their own consumer rules.

# The MP3 encoder is reached through a stand-in for javax.sound's AudioFormat (see src/main/java/javax/sound).
# It cannot be exercised in unit tests on a device-less build, so keep both exactly as written.
# Only in editions that include the audio tool, so others do not carry the encoder.
-if class app.ultimate.audio.engine.AudioConverter
-keep class de.sciss.jump3r.** { *; }
-if class app.ultimate.audio.engine.AudioConverter
-keep class javax.sound.** { *; }
-dontwarn javax.sound.**

# Termux's terminal starts the shell through JNI; the native library looks these up by name.
-keep class com.termux.terminal.JNI { *; }

# Crash reports (About › Recent crashes) should say where in Ultimate a crash happened. Keep Ultimate's own class and
# method names and line numbers; everything is still shrunk and optimised, only these names stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepnames class app.ultimate.** { *; }

# Camera calibration (full editions): created by name, so keep its constructor; MediaPipe calls into its Java classes
# from native code and reads protobuf messages reflectively, so keep those whole. Harmless where they are absent.
-keep class app.ultimate.vision.HandCalibrationTool { public <init>(app.ultimate.UltimateApp); }
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
# MediaPipe's library also ships AutoValue's annotation processor, which refers to the Java compiler's javax.lang.model
# API. None of it runs on a phone; tell R8 so.
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**
-dontwarn com.google.auto.value.**

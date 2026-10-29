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

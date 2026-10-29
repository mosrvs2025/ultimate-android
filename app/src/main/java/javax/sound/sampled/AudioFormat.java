package javax.sound.sampled;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Android has no javax.sound. The jump3r MP3 encoder's convenience class describes its input with this type and uses
 * only the members below, so this minimal stand-in lets that class run unchanged. It is not a general AudioFormat.
 */
public class AudioFormat {
    public static class Encoding {
        public static final Encoding PCM_SIGNED = new Encoding("PCM_SIGNED");
        private final String name;

        public Encoding(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final Encoding encoding;
    private final float sampleRate;
    private final int sampleSizeInBits;
    private final int channels;
    private final boolean bigEndian;
    private final Map<String, Object> properties;

    public AudioFormat(Encoding encoding, float sampleRate, int sampleSizeInBits, int channels,
                       int frameSize, float frameRate, boolean bigEndian, Map<String, Object> properties) {
        this.encoding = encoding;
        this.sampleRate = sampleRate;
        this.sampleSizeInBits = sampleSizeInBits;
        this.channels = channels;
        this.bigEndian = bigEndian;
        this.properties = properties == null ? Collections.<String, Object>emptyMap() : new HashMap<String, Object>(properties);
    }

    /** Signed linear PCM, the only input the app feeds the encoder. */
    public AudioFormat(float sampleRate, int sampleSizeInBits, int channels, boolean signed, boolean bigEndian) {
        this(Encoding.PCM_SIGNED, sampleRate, sampleSizeInBits, channels, channels * sampleSizeInBits / 8, sampleRate, bigEndian, null);
    }

    public Encoding getEncoding() {
        return encoding;
    }

    public float getSampleRate() {
        return sampleRate;
    }

    public int getSampleSizeInBits() {
        return sampleSizeInBits;
    }

    public int getChannels() {
        return channels;
    }

    public boolean isBigEndian() {
        return bigEndian;
    }

    public Map<String, Object> properties() {
        return Collections.unmodifiableMap(properties);
    }
}

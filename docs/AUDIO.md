# Video to Audio: smaller files, and splitting (2.28.0)

Code: `audio/model/AudioModels.kt` (settings and planner), `audio/model/Mp3VbrTag.kt`, `audio/model/SplitPlan.kt`,
`audio/engine/AudioConverter.kt` (encoders, preview, quiet-moment finder), `audio/engine/AudioManager.kt` (the queue),
`audio/ui/AudioScreens.kt`.

## Smaller without sounding worse

The MP3 encoder is LAME (a Java port, jump3r 1.0.5). It used to run only at a **fixed** bit rate: 128 kbps costs 128 kbps for
every second, whether it is a symphony or a pause. Two things were wrong with that:

- Fixed-rate wastes bits on easy sound. **Smart sizes** use LAME's variable bit rate (VBR): Tiny (level 8), Small (6),
  Balanced (4), High (2). The encoder spends what each moment needs.
- **Mono saved nothing at a fixed rate**: 128 kbps is 128 kbps in mono or stereo. The old hint said it "halves the size", which was
  true only if you also halved the kbps. The text now says so, and under a Smart size mono really does take about half.

Measured here with the app's own encoder library on six minutes of synthetic speech at 48 kHz (synthetic, with a faint noise
floor; real recordings differ, so treat these as a guide, not a promise):

| Setting | Size | Against 128 kbps fixed (5.75 MB) |
| --- | --- | --- |
| VBR level 6, mono | 2.99 MB | -48% |
| VBR level 7, mono | 2.43 MB | -58% |
| VBR level 8, mono | 1.98 MB | -66% |
| (for comparison, not built: Opus 24 kbps voice) | 1.11 MB | -81% |

A clean recording with no background noise goes much smaller; a noisy one goes less small, because the encoder faithfully spends
bits on the noise.

### Variable-rate files need a Xing header (and the library doesn't write one)

Checked with `ffprobe`: a VBR file straight from the library has **no Xing/Info header**, so players guess the length from the first
frame. One test file showed 387 s for 359.5 s of sound; on an 8-hour recording that is a wrong length by tens of minutes and
seeking that lands in the wrong place. `Mp3VbrTag` fixes it: while encoding it follows the frames as they are written, a blank frame
with the right header goes in first, and at the end it is overwritten with the frame count, byte count and a 100-entry seek table.
Tested against real encoder output across 16, 24, 32, 44.1 and 48 kHz, mono and stereo (`Mp3VbrTagTest`), and the finished files
were read back by `ffprobe`/`ffmpeg`: lengths within 0.12 s of the truth, seeking works.

LAME also chooses its own output sample rate at the smaller levels (a 48 kHz file at level 7 came out at 32 kHz). That is its
decision about bandwidth and is fine; the result screen now reports the rate the file really has.

### Hear it before you commit

The preview's "Edited" side used to be a WAV, so it could not show what compression does. It is now encoded with the chosen
codec (MP3 or AAC) and the Original/Edited player flips between them.

## Splitting

Choose 5, 10, 15, 30 minutes, 1 or 2 hours: each file is cut into parts of that length, measured on the **original** recording
(before any speed change), after the trim if there is one. A leftover shorter than a quarter of a part joins the last part instead
of being a stray file. Parts are named `name - part 03 of 08` (numbers padded so they sort).

Implementation: one input becomes one queue job per part, each with its own start and end, so the queue, progress, results list,
rename and share all work unchanged. Parts are separate conversions, each seeking to its own start.

**Cut at quiet moments** (on by default): before converting, each planned cut is moved to the quietest pause within 30 seconds of
it. Only about a minute around each cut is decoded, so finding the cuts costs seconds even in an 8-hour file. The loudness is
measured in 100 ms slices and a pause must last 400 ms; of two equally quiet places the nearer one wins. A cut that can't be
looked at keeps its planned place (and says so in the log).

Limits: Normalize and fades are applied to each part on its own (the screen says so).

## Not yet verified

The calculations are unit-tested, the header against real decoders, but **none of this has run on a phone**: the encoder's
speed on an 8-hour file, the quiet-moment search on real recordings, and how the Tiny/Small levels actually sound (use the preview).

## Next

Opus (`audio/opus`, in an Ogg `.opus` file) is the next big step for speech: about a fifth of the size of a fixed 128 kbps MP3 in
the measurement above. Android's encoder and Ogg muxer exist from Android 10, but it needs checking on a real device (and a
fallback to MP3 where a phone lacks the encoder), and `.opus` plays in fewer apps than MP3. Also possible: shortening long silences,
and a lower sample rate for voice (needs a proper anti-alias filter first: the resampler is plain linear interpolation).

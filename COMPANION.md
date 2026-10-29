# Ultimate Companion preview

Install the signed APK from the `ultimate-companion-preview` GitHub Release.
The companion build uses `app.ultimate.companion` so it coexists with Claude's
Ultimate/Ultimate+ install. The original signing key was unavailable; no claim
of update compatibility with those installs is made. Companion previews use
one permanent signing key held in repository Actions secrets.

Open **Send to computer**, turn it on, and open the displayed address on a
computer on the same trusted Wi-Fi. Offer files from the phone. The page shows
searchable files, media previews with byte-range seeking, uploads with progress,
and current/last background job status. Uploading audio/video with the playback
option asks for confirmation on the phone before opening a player.

Tap **Enable camera** on the phone, grant camera permission, then **Phone camera**
in the browser. The feed is a low-resolution JPEG video preview, without sound.
Flip and Stop remain controlled by the phone. Leaving the sharing screen stops
the server and disconnects clients. Backgrounding the app disables its camera.
This is not a virtual webcam driver, screen mirroring, or duplex audio/video.
Local HTTP is not encrypted; only offer files to devices on a trusted network.

## APK publishing

`.github/workflows/ultimate-companion.yml` runs Android unit tests and builds a
signed APK with readable diagnostic stacks. Successful builds replace the two rolling release assets:
`Ultimate-Companion.apk` and `SHA256SUMS.txt`. No APKs enter Git history. Temporary
Actions artifacts expire after seven days. Download URL stays the same:

https://github.com/mosrvs2025/rea/releases/download/ultimate-companion-preview/Ultimate-Companion.apk

The workflow needs `COMPANION_KEYSTORE_BASE64` and `COMPANION_KEYSTORE_PASSWORD`.
Do not replace the signing key: later companion APKs must use the same key to
update installed companion builds. CI supplies an increasing version code.
Android still asks the user to approve APK installation; this workflow does not
silently install updates. No GitHub Pages changes are needed for APK delivery.

## Verification boundaries

Browser layout, search, and disabled-camera guidance were checked using a local
preview with sample phone data. Android build and unit-test results are recorded
in Actions. Real-device camera orientation, playback handoff, file-transfer
interruption, and lifecycle behavior still need phone verification. Export
diagnostics now preserve exception details and distinguish a saved output from
a failed follow-up step; the reported device export error has not been reproduced.

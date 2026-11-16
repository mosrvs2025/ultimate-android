# Live video and audio between your devices (design)

Goal: watch (and listen to) one of your own devices' cameras from another of your devices, live, with sound; later, use a spare
phone as a home camera. Everything stays between devices paired with the owner's mesh code. No cloud service of ours is involved.

## What exists (2.25.0)
A device that has been armed ("Let my other devices see through this camera") answers `cam.frame` requests with its newest
still picture in small pieces (`CamFrames`). Viewers pull them: about 3 to 4 pictures a second, no sound.

## What is being added: WebRTC
* **Camera device** (armed): one camera capturer feeds a video source. It is sent as a WebRTC video track (hardware H.264/VP8) with a
  microphone audio track to each viewer, and the same frames (throttled) keep the still-picture path alive as a fallback.
* **Viewer**: asks for a session over the mesh, receives the stream, shows video in the camera window and plays the audio. It also
  turns frames into pictures (10 to 15 a second) for the Cameras page, so a computer's browser or the Quest sees live video too.
* **Setting up a call** ("signaling") uses the mesh's existing signed, encrypted commands, not a new server:
  `rtc.offer` (viewer to camera, the SDP cut into pieces under the mesh's 16 KB line), `rtc.answer` (viewer polls for the camera's
  answer, in pieces), `rtc.bye`. ICE is not trickled: each side waits until its candidates are gathered, so one offer and one
  answer are enough.
* **Reaching each other**: on the same network the devices find each other directly. Across networks a public STUN server (a
  default, changeable list) tells each side its outside address; that server sees the device's address, nothing else. Where direct
  connections are impossible (some mobile or corporate networks) a relay (TURN) would be needed; none is built in, and the app
  falls back to the still-picture path and says so.
* **Privacy and safety, kept deliberately**: the camera is shared only after the owner arms it from that device's own screen; a
  notification stays up while it is shared and shows who is watching (Android also requires it); only devices with the pairing code
  can start a session; media is encrypted (DTLS-SRTP). There is no hidden or covert mode, and none will be added.

## Stages
1. (2.26.0) WebRTC sender, receiver, signaling; audio; fall back to stills. Pure parts (message pieces, session state) unit-tested.
2. (2.26.0) Frames to the Cameras page for browsers.
3. (2.27.0+) Home-camera mode: motion alerts to your other devices, short event clips kept on the viewing phone, a spare phone
   left on a charger and armed from its own screen.

## What can only be checked with real devices
The media itself (video quality, audio, delay, battery, heat, connecting across networks). The pure signaling and the session
logic are tested without devices.

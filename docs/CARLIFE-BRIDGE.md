# CarLife audio bridge — first experimental version

Target: iPhone wireless CarPlay → DiPlay on the existing Android phone →
CarProjection 0.4.0 → wired CarLife on the Lexus ES200.

This version changes audio only. Video remains Android screen capture and touch
remains the already-tested accessibility path. It does not implement direct H.264,
direct HID, a car microphone uplink, or transport ownership outside the Activity.

## Installation and test

1. Update CarProjection to 0.4.0. Keep its existing projection/accessibility permissions.
2. From the paired [CarProjection Actions build](https://github.com/Aki894/CarProjection/actions), install the artifact `DiPlay-CarLife-bridge-apk`. Its debug application ID is
   `com.shihab.diplay.hudtest`; it coexists with upstream `com.shihab.diplay`.
   The app label is **DiPlay CarLife (实验)**. Keep the original app installed;
   its settings and pairings are not shared with the bridge variant.
3. Stop the original DiPlay before using the variant. Configure/pair the bridge variant
   with the same wireless method that worked before. Only one receiver should run.
4. In CarProjection enable **DiPlay 直接音频（实验）**. Start CarLife screen projection
   normally, then bring the bridge DiPlay to the foreground.
5. Play music, then trigger navigation guidance. Check phone speaker silence, car gain
   at 0/30/100%, music ducking during guidance, and continued touch/back operation.
6. Disable the experimental switch to compare with the existing capture route.
   To return entirely to the known baseline, stop the bridge variant and open original DiPlay.
7. Test iPhone disconnect/reconnect and USB disconnect/reconnect. Export CarProjection
   logs containing `[BRIDGE]`, `[TTS-AUDIO]`, `[USB]`, and DiPlay audio diagnostics.

## Routing and lifetime

DiPlay normalizes decoded mono/stereo PCM16 at 8–48kHz to 48kHz stereo and sends
it through a local Binder-negotiated pipe. There is no network listener. The service
checks the calling UID's installed package names; only the two DiPlay package IDs
above are allowed. The receiver rejects routing unless the user enabled the switch,
USB is connected, projection is running, and no audio test is active. Package-name
authorization is not certificate pinning; a user-installed replacement DiPlay has
the same authority. This is a local experimental integration.

Each side bounds pending decoded audio to six 20ms blocks/120ms per stream;
the receiver accepts at most six streams and drops the oldest samples on overflow.
Music starts with a 40ms prebuffer. Incomplete decoder frames wait up to 60ms
for the next chunk (or EOF) rather than padding every AAC tail into an extra block.
Music is attenuated to 25% while a non-media stream has audible PCM; streams are
summed with saturation, then downmixed/resampled through the existing TTS converter.
CarProjection's selected TTS rate (default 48k mono) and output gain still apply.
Idle TTS sessions end after 500ms. Stream EOF drains the receiver's tail; peer death,
mode changes, USB reset and tests invalidate old streams. The decoder route checks
receiver readiness every 500ms. Failed/unavailable routes resume local DiPlay playback;
this can briefly produce local sound and is not a guarantee of gapless handover.

Turning the experimental switch on replaces system audio capture. Android app audio
outside DiPlay is not forwarded in this mode. CarPlay music/navigation/Siri/phone output
can enter the same mixer, but phone/Siri duplex operation is not certified by this change.
The existing phone microphone path remains unchanged. Decoder formats above 48kHz,
more than two channels, or non-PCM16 are not supported by the bridge and use local playback.

## Builds and authentication

Ordinary source/CI builds remain identity-free. The fork's Actions job explicitly
selects the upstream public DiPlay 0.2.9 APK, verifies its published SHA-256, and stages
only its two experimental accessory runtime assets under the runner temporary directory
using `scripts/prepare_bridge_auth.py`. It then uses the existing explicit
`DIPLAY_AUTH_ASSETS_DIR` / `assembleStandaloneDebug` mechanism. Credentials are not
committed, printed, or uploaded separately. They are present in the standalone APK,
as they are in the upstream preview, and remain extractable and subject to iOS acceptance.
This does not confer Apple certification. Upstream notices and licenses are retained.

The optional `DIPLAY_DEBUG_KEYSTORE_PATH` selects the existing CarProjection **public
test signing key** for repeatable debug updates. It is not a production signing key
and never imports upstream's Android signing identity. Do not use this signing setup
for a production release.

## Next milestones

After physical audio regression: measure end-to-end latency and underruns, inspect
CarPlay H.264 configuration against HU-negotiated dimensions/profile/framing, then
implement direct video with a screen-capture fallback. Direct touch/HID comes after
coordinate and gesture semantics are verified. Independent reconnect state machines
and service-owned USB transport follow; they are not included in this first bridge.

## Direct video experiment (CarProjection 0.4.1 / DiPlay carLife.2)

Connect CarProjection to the car, enable its direct video switch, then connect
iPhone in DiPlay CarLife. The car target replaces the phone's advertised canvas
and selects H.264, full safe area and the selected car FPS for the next session.
Reconnection is required if the current session was established before discovery.
Source preferences are not overwritten. Only the main screen is sent through a
local framed pipe; SPS dimensions must match the negotiated car canvas.

Both audio and video remain independently selectable. Video confirmation is
`[BRIDGE] video direct active=WxH H.264` plus growing `video USB sent` counters
and an actual image on the HU. If USB accepts packets but the car decoder stays
black, turn the video switch off to recover the original mirror. The phone
preview/encoder and accessibility input remain; Android cursor overlay is absent
on the direct car stream and pointer mapping still needs dedicated HID work.

## carLife.3 stability update

Use with CarProjection 0.4.2. Repeated identical AVC configuration preserves the
video pipe instead of reopening/requesting another keyframe. PCM queue admission
now waits for bounded receiver backpressure rather than dropping older samples.
The receiver restores 300ms music prebuffer / 500ms capacity and keeps voice
latency short. A 250ms stalled producer admission restores local audio playback.
CarProjection exports source frame and overwrite counts and detects HU silence
after learning the car's periodic status cadence. Both APKs must be updated.

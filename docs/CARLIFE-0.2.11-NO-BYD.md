# DiPlay 0.2.11 CarLife.6

This fork includes the complete upstream 0.2.11 history. It keeps its Android TV / D-pad support, preferred Wi-Fi Direct channel selection, scoped VPN, reconnect fixes, audio diagnostics and metadata corrections.

## Generic receiver scope

Removed BYD HUD and cluster outputs, vendor steering-wheel codes, vehicle-field probes, local ADB clients, ADB permission grants and hotspot automation, dashboard map presentations / overlays, parked-video UI tied to vendor gear readings, vendor assets, and vendor launcher/map demo applications. The optional AirPlay video-in-car protocol remains disabled by default. Old stored vendor feature preferences have no active readers.

Generic GPS reporting, normal touch and media/voice keys, Android TV remote input, and the navigation widget remain. The widget now uses generic iAP2 route and song parsers with no ADB or vehicle output dependency. Historical upstream documentation may mention removed features.

The removals follow LoopLink's approach, adapted to the newer upstream and our bridge additions: https://github.com/umarz317/LoopLink/commit/77ab5b1702d566fc7b0927fa8a1c6e12827139d2 . Its removal of the unused vendor navigation speaker stream is also incorporated. GPL-3.0 licensing and author attribution remain intact.

## Existing bridge

Direct H.264, PCM and background native-input transport are retained. Existing CarProjection 0.5.0 remains compatible; no bridge protocol change. Remote-key dispatch uses the upstream Android TV/non-touch detection and does not replace the background Binder input bridge. CarLife car dimensions still take precedence over Android window dimensions.

The experimental application ID and stable test signing remain unchanged. Update over the existing DiPlay CarLife app. Reconnect CarPlay after updating. Verify wireless pairing, car-sized video, music/navigation, native touchpad focus, Back and background operation on the vehicle.

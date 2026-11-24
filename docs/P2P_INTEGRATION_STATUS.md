# Dahua P2P integration status

## Current implementation

The Android app now has an optional experimental P2P mode.

- Open-source transport: `github.com/blackjid/dahua-p2p`, pinned in `p2pbridge/go.mod`.
- License: MIT; see `p2pbridge/THIRD_PARTY_NOTICES.md`.
- The Go component is exposed to Android through a gomobile-generated AAR during GitHub Actions.
- The app starts a P2P session using the recorder serial number and the recorder's own username/password.
- A loopback TCP bridge listens on `127.0.0.1:37778` and attempts to tunnel to recorder TCP port `37777`.
- Existing NetSDK login, archive search, and piece-download code is retained; direct IP mode remains available.
- P2P is stopped after failed login, logout, and app shutdown.

## Important compatibility limitation

The upstream Go project documents and tests its P2P transport primarily for RTSP. Its API can open a TCP realm to a specified port, but using that realm for Dahua NetSDK/DVRIP traffic on port 37777 is not confirmed by upstream documentation or by a test on the target recorder.

Therefore the in-app P2P switch is explicitly marked experimental. A successful P2P handshake alone does not prove that NetSDK login, archive search, or file download will work through the tunnel. Those operations must be verified on the target model and firmware. If the NetSDK login fails, turn off P2P and use the direct IP path over a trusted local network or VPN.

## Build

GitHub Actions installs Go, Android NDK, and gomobile, then builds `app/libs/dahua-p2p.aar` before Gradle builds the APK. The AAR is generated in CI and is not committed as a binary.

## Security

- The bridge uses the user's own device serial number and credentials; it does not bypass device authentication.
- Recorder passwords and session data are not written to source control.
- No private DMSS/SmartPSS application keys or credentials are used.
- For remote access fallback, use a VPN to the trusted network. Do not expose the recorder's TCP port directly to the public internet.

## Validation still required

- Green CI build for the latest branch head.
- Install and test on an ARM64 Android device.
- Verify P2P availability for the target serial number.
- Verify that the P2P realm accepts port 37777 and that NetSDK login succeeds.
- Verify archive search, partial download, reconnect, logout, and cleanup through P2P.

Until those hardware checks pass, describe the app as a build candidate with experimental P2P support—not as a fully validated P2P release.

# Dahua P2P integration status

## Repository audit

The current Android app contains a NetSDK wrapper that logs in using an IP address and TCP port. The P2P proxy layer is not yet wired into the app.

At the time of this audit:

- `app/libs/INetSDK.jar` is referenced by Gradle.
- `app/src/main/jniLibs/arm64-v8a/libToUProxy.so` is present in the repository.
- `app/libs/libToUProxy.jar` is absent.
- `app/build.gradle.kts` does not declare the P2P proxy JAR.
- The current `NetSdk.login(...)` method accepts a host/IP and port; it does not create a P2P tunnel.

## Why the P2P implementation is blocked

The native proxy library alone does not provide the Java API used by the Android P2P client. The matching, vendor-supplied `libToUProxy.jar` (or the currently supported replacement from Dahua) is required to compile and call the proxy client.

The integration also needs the official P2P service configuration for the target SDK distribution, such as the server endpoint, port, and vendor-issued application key/identity where required. These values must come from Dahua or an authorized SDK package. They must not be guessed, copied from DMSS, or committed to the repository.

## Intended connection flow

1. Initialize the official Dahua P2P proxy client using the documented SDK API and authorized service configuration.
2. Register the recorder serial number and the recorder's supported service port with the proxy client.
3. Wait for the proxy to report a ready/online local forwarding port.
4. Pass the local loopback address and forwarded port to the existing NetSDK login wrapper.
5. Keep the existing archive search and piece-download implementation unchanged.
6. Close the NetSDK login and P2P forwarding session on logout, cancellation, and application shutdown.

The exact method names and lifecycle must be implemented against the version of the official JAR actually supplied; do not assume API compatibility between SDK releases.

## Required inputs before implementation

- The official Dahua Android P2P proxy JAR matching the checked-in native library, or a complete current SDK package.
- Vendor documentation for that exact SDK version.
- Authorized P2P service endpoint and application credentials/configuration, supplied through a secure configuration mechanism rather than source control.
- Confirmation that the recorder has P2P enabled and reports online in its own settings.

## Security and validation requirements

- Never log or commit recorder passwords, application keys, tokens, or private SDK files.
- Do not reuse private DMSS/SmartPSS credentials or reverse-engineered service parameters.
- Do not treat a successful local TCP connection as proof that the recorder login succeeded.
- Test initialization, offline device handling, timeout, reconnect, logout, and cleanup on the target Android ABI.
- Preserve the existing direct-IP login path as a fallback.

## Additional maintenance work on the integration branch

- Download queue entries are now persisted in app preferences.
- Partial DAV files are no longer deleted during application startup, so an interrupted download can be retried after restarting the app.
- Interrupted active jobs are restored in an error/retryable state; the user must reconnect to the recorder and tap Retry.
- The Android build workflow now declares a pull-request trigger. The workflow has not yet been confirmed green for this branch.

## Current result

This branch includes application reliability fixes and documentation, but it does not claim that P2P connectivity is implemented or working. Functional P2P work remains blocked until the official matching SDK artifact and authorized service configuration are supplied by Dahua. Do not merge this branch as a completed P2P feature.

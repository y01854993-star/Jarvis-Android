# Jarvis Android

## Build and install

Pushes to `main` run the Android build and upload a `jarvis-apk` workflow artifact. Without release-signing secrets this is a debug APK for testing only; it is not published as a GitHub release. A manually dispatched workflow publishes a versioned release only when signing secrets are present.

To enable signed releases, create and back up a dedicated Android release keystore. Add these GitHub Actions secrets:

- `ANDROID_RELEASE_KEYSTORE_BASE64`: base64-encoded keystore file.
- `ANDROID_RELEASE_KEYSTORE_PASSWORD`: keystore password.
- `ANDROID_RELEASE_KEY_ALIAS`: signing-key alias.
- `ANDROID_RELEASE_KEY_PASSWORD`: signing-key password.

Never commit the keystore, passwords, or API keys. API-provider keys are entered in the app and saved using Android Keystore-backed encryption; no Gemini key is embedded into the APK. If the signing key changes from an older installed build, Android may require uninstalling that older app before installing the new one. Back up the signing keystore securely because future APK updates must use the same key.

## App setup and privacy

Set device-specific `allowed_caller`, `pc_mac`, and contact values in `app/src/main/assets/config.json` before distributing a personalized build. Avoid committing real contact numbers or other personal data. The app uses broad Android capabilities for hands-free operation; grant only permissions needed for features you intend to use. Camera access is requested when the camera feature is used.

The app blocks cleartext network traffic by default. HTTP is allowed only for the built-in local model endpoints at `localhost` and `127.0.0.1`; custom servers should use HTTPS. Locally stored task history is encrypted with the app's Android Keystore key and can be removed using the app's wipe-data control.

The HUD includes a persistent task-history view. It records the recent user request, result, and status (completed, cancelled, or failed), with a bounded history of 100 entries.

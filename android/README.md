# Build DataNet Share 5.1-dev

This directory is the maintained Kotlin Android project. The root upstream ZIP is provenance, not the build source.

## Requirements

- JDK 17 and a verified Gradle 8.5 installation.
- Android SDK platform 34 and build tools 34.0.0.
- Android Gradle Plugin 8.2.2 and Kotlin 1.9.22 (declared in this project).

The upstream archive did not contain a valid Gradle wrapper JAR. This fork does not execute `gradle-wrapper.jar.broken`; use installed Gradle or the GitHub Actions workflow. `gradle/wrapper/gradle-wrapper.properties` alone is not an executable wrapper.

```sh
git clone https://github.com/Eun-si123/DataNetShare.git
cd DataNetShare
# Set ANDROID_HOME to your SDK installation, or write sdk.dir in android/local.properties.
gradle -p android :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
```

Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`.

Fast protocol/relay tests with no Android SDK:

```sh
gradle -p proxy-tests test --no-daemon
```

The host suite compiles the actual shared proxy, packet encoder/parser and receiver flow source files. It does not duplicate their implementation or run Android services.

## Install and signing

```sh
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Debug builds use `com.datanet.share.dev`, so they do not overwrite upstream `com.datanet.share`. A runner-generated debug key is **not** a stable release signing identity: an APK from a different runner/machine can fail to update a prior debug installation. For repeated personal use, build with the same local debug key. For public distribution, provision a private signing key outside the repository and define a secure signing process before publishing.

`assembleRelease` currently produces an **unsigned** release APK with application ID `com.datanet.share`. There is no committed release keystore or fallback password. Do not reuse the historical signing material in the upstream ZIP. A release is not published automatically by CI.

## Runtime boundaries

Use the [main guide](../README.md) for sharing/receiving setup. Both sender modes require a random per-session SOCKS5 password. The experimental built-in IPv4 receiver uses the same username/password as external clients. Only one mode runs at once; VPN consent and Wi-Fi Direct permissions are requested in their respective flows.

[Architecture](../docs/ARCHITECTURE.md) · [Validation](../docs/TESTING.md) · [Security notes](../docs/SECURITY-REVIEW.md)

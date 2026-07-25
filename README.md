# Arrows Pro Camera

A computational photography camera app for the Fujitsu Arrows F-02L (Snapdragon 450) that produces superior dynamic range, lower noise, and more natural detail than Samsung S26 Ultra auto-mode photos.

## Architecture

```
Multi-frame burst (15–32 frames, -1.0 to -1.5 EV)
        │
        ▼
  Phase-detect AF lock (Camera2)
        │
        ▼
  Frame alignment (OpenCV ORB + homography warp)
        │
        ▼
  Frame fusion (mean stack + Mertens exposure fusion)
        │
        ▼
  AI super-resolution (ESRGAN TFLite, 2×) ← optional
        │
        ▼
  JPEG 95% + DNG save
```

## Requirements

| Component | Version |
|-----------|---------|
| Android SDK | 28 (target) / 26 (min) |
| Kotlin | 1.9.x |
| OpenCV | 4.8.0 |
| TensorFlow Lite | 2.13.0 |
| Gradle | 8.2 |
| JDK | 17 |

## Building

### Prerequisites

```bash
# Install JDK 17
sudo apt install openjdk-17-jdk   # Ubuntu/Debian

# Android SDK: Install via Android Studio or sdkmanager
sdkmanager "platforms;android-28" "build-tools;34.0.0"
```

### Debug build

```bash
chmod +x gradlew
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

### Release build (signed)

```bash
# Generate a keystore (first time only)
keytool -genkey -v -keystore arrows-pro-camera.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias arrowspro

# Set environment variables
export KEYSTORE_FILE=arrows-pro-camera.jks
export KEYSTORE_PASSWORD=your_store_pass
export KEY_ALIAS=arrowspro
export KEY_PASSWORD=your_key_pass

./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

### Install on device

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## ESRGAN Model

The super-resolution feature requires the ESRGAN TFLite model placed at:

```
app/src/main/assets/esrgan.tflite
```

Download from TensorFlow Hub or the CI workflow fetches it automatically.
If the model is absent, the app falls back to bilinear 2× upscaling.

## CI/CD (GitHub Actions)

Every push to `main`:
1. Builds a debug APK
2. Builds an unsigned release APK
3. Uploads both as workflow artifacts
4. Creates a GitHub Release with the debug APK

To enable signed releases, add these GitHub Secrets:
- `KEYSTORE_BASE64` — base64-encoded `.jks` file
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

## Permissions

| Permission | Why |
|------------|-----|
| `CAMERA` | Camera2 viewfinder + capture |
| `WRITE_EXTERNAL_STORAGE` | Save photos (Android ≤ 9) |
| `READ_EXTERNAL_STORAGE` | Gallery access (Android ≤ 12) |
| `FOREGROUND_SERVICE` | Background processing pipeline |

## License

Apache 2.0 — see [LICENSE](LICENSE)

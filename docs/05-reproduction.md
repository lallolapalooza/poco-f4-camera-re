# Reproduction: decompilation and build

## Where everything lives

Nothing from this work is left in `/tmp`. The repository
(`/home/asdf/Downloads/poco-f4-camera-re`) is self-contained:

| Path | Contents |
|---|---|
| `apk/`, `apktool_out/`, `decompiled/`, `decompiled2/`, `native/` | analysis inputs |
| `tools/` | jadx 1.5.6, apktool 2.11.1, Android SDK, Gradle 8.7, relocated emulator AVD |
| `reimpl/` | the app and its JVM tests |
| `docs/`, `artifacts/` | documentation and verification screenshots |
| `session-artifacts/` | scratch kept for traceability: `aimodels-from-system-dump/` (185 MB AI models extracted while surveying assets), `tool-inspection/` (AAR/JAR extracts, APK candidate scans), `ui-dumps/`, `kotlin-daemon/`, `jadx-temp/`, `emulator/`, `emulator-installed.apk` |
| `*.log` at the root | jadx, apktool, Gradle and emulator logs |

The emulator image created for verification was moved out of `~/.android` into
`tools/avd`. Run it with:

```bash
ANDROID_AVD_HOME=$PWD/tools/avd \
  tools/android-sdk/emulator/emulator -avd poco34 -no-window -no-audio \
  -no-snapshot -gpu swiftshader_indirect -camera-back virtualscene
```

Shared, pre-existing tool caches stay where they are by design: `~/.gradle`
(dependency cache used by other projects) and `~/.android/avd/shot.avd`.


## What was used

- `jadx` 1.5.6 (release zip, `tools/jadx`)
- `apktool` 2.11.1 (`tools/apktool.jar`)
- Android SDK command-line tools, platform 34, build-tools 34.0.0 (`tools/android-sdk`)
- Gradle 8.7 + JDK 21 (`/usr/lib/jvm/java-21-openjdk`); JDK 27 is too new for AGP 8.5
- `unzip`, `nm`, `strings` for the native libraries

No root or `sudo` is required; everything lives under the project directory.

## Decoding the APK

```bash
# resources + manifest + assets (arsc decoded, no smali)
java -jar tools/apktool.jar d -f -s -o apktool_out apk/MiuiCamera.apk

# Java sources (resources skipped to keep jadx under the memory limit)
JAVA_OPTS="-Xmx7g" tools/jadx/bin/jadx -d decompiled2 --no-res \
  --threads-count 6 --show-bad-code apk/MiuiCamera.apk
```

Notes:

- A full run **with** resources on a 22 GB machine was killed by the OOM killer at
  88% (`JADX_EXIT=137`). The partial output is in `decompiled/`. The
  `--no-res` run completes (exit code 3 = finished with 37 per-class errors; the
  affected classes are still emitted).
- `apktool -s` skips smali; if smali is needed for a stub-heavy class, rerun
  without `-s`. `jadx --show-bad-code` is important because Xiaomi's obfuscated
  ternary-heavy state machines otherwise decompile to empty methods.

## Building the reimplementation

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME=$PWD/tools/android-sdk
./tools/gradle-8.7/bin/gradle -p reimpl assembleDebug
./tools/gradle-8.7/bin/gradle -p reimpl test
```

`reimpl/local.properties` points at the local SDK. The GestureRecognizer model
(`app/src/main/assets/gesture_recognizer.task`, 8.4 MB) is the official
MediaPipe float16 model.

## Verifying decompiled claims

Useful greps:

```bash
# palm shutter
grep -rn "MODE_HAND_GESTURE\|HandGestureDecoder" decompiled2/sources

# voice shutter
grep -rn "SPEECH_SHUTTER\|MultiWakeupEngine" decompiled2/sources

# AI scene
grep -rn "AI_SCENE_MODE\|AiSceneMultipleASD" decompiled2/sources

# computational photography
grep -rn "SuperNight\|MFNR\|ULTRA_PIXEL" decompiled2/sources
```

Resources were decoded by apktool, so localized UI strings can be read directly,
for example:

```bash
grep -n "speech_shutter_open_tip" apktool_out/res/values/strings.xml
grep -n "speech_shutter_open_tip" apktool_out/res/values-zh-rCN/strings.xml
```

## Verification performed

Clean build and unit tests:

```
./tools/gradle-8.7/bin/gradle -p reimpl clean :app:assembleDebug :app:testDebugUnitTest
# BUILD SUCCESSFUL, 109 tests, 0 failures
```

Runtime smoke test on an Android 14 (API 34) x86_64 emulator with the virtual-scene
camera (`artifacts/emulator-ui.png`, `artifacts/emulator-capture-supernight.jpg`):

| Check | Result |
|---|---|
| Cold launch, camera binding, preview | OK, no crash |
| Frame fan-out (16 fps) + ML Kit | OK; scene chip showed `Architecture (19)` on the virtual scene |
| Shutter → HDR (3 bracketed frames) | OK; `HDR saved`, JPEG written to `Pictures/PocoF4RE` |
| Shutter → Super Night (5 frames) + beauty + watermark + saliency blur | OK; `Super Night saved 960x1280` |
| Voice shutter with no recognition service | controller reported inert, no crash (`W/VoiceShutter: No speech recognition service`) |
| Palm shutter on x86_64 emulator | MediaPipe ships no x86_64 JNI, so the detector degrades to inert; `lib/arm64-v8a/libmediapipe_tasks_vision_jni.so` is packaged for real devices; the hold/cooldown logic is covered by JVM tests |

Bug found and fixed during the run: `androidx.camera.core.ImageProxy` declares a
default `toBitmap()` method. A Kotlin extension with the same name is silently
shadowed by the member, so the app was using CameraX's decoder, which ignores
EXIF/rotation, and photos were saved landscape. The decoder is now named
`decodeCapturedJpeg()`; it applies EXIF orientation, falls back to
`ImageInfo.rotationDegrees`, and enforces portrait output for the portrait-locked
activity. The log line `captured jpeg ... rotation=90 out=960x1280` confirms all
five burst frames were oriented before fusion.

## Native libraries

```bash
unzip -oq apk/MiuiCamera.apk 'lib/arm64-v8a/*' -d native
nm -D --defined-only native/lib/arm64-v8a/libhandengine.arcsoft.so
```

The JNI entry-point names are the authoritative interface definition; for the
palm detector they are `init(int cameraId)`, `detectGesture(byte[] yuv, int w,
int h, int rotation)` and `unInit()` on
`com.android.camera.handgesture.HandGesture`.

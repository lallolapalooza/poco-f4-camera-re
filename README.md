# Poco F4 / MIUI Camera — reverse engineering and reimplementation

Everything for this work lives in this directory. The stock camera APK was
decompiled, its gesture/AI/computational-photography behaviour documented, and
the features rebuilt as a standalone Android app.

## Target

| Property | Value |
|---|---|
| APK | `apk/MiuiCamera.apk` (137.6 MB) |
| Package | `com.android.camera` |
| Version | 5.1.001140.0 (versionCode 510011400) |
| SDK | min 29, target 33, compile 33 |
| Device | Poco F4 system image (`product/priv-app/MiuiCamera`) |
| Decompiled classes | 9,237 (jadx 1.5.6, `decompiled2/sources`) |

Three camera APKs exist in the dump; `MiuiCamera` is the one analysed:
`MiuiCamera.apk`, `CameraExtensionsProxy.apk`, `CameraTools_beta.apk`.

## Layout

| Path | Contents |
|---|---|
| `apk/` | original `MiuiCamera.apk` |
| `apktool_out/` | decoded manifest, resources, localized strings, assets |
| `decompiled2/` | complete jadx Java sources (authoritative reference) |
| `decompiled/` | first jadx pass, kept only because it OOM-killed at 88% |
| `native/` | extracted `lib/arm64-v8a` vendor libraries |
| `docs/` | reverse-engineering notes and reimplementation mapping |
| `reimpl/` | buildable Android app (`dev.pocof4.camera`) |
| `artifacts/` | emulator UI screenshot + processed capture |
| `session-artifacts/` | scratch kept for traceability (model dumps, AAR extracts, UI dumps, logs) |
| `poco-f4-camera-re-session-archive.zip` | full session archive: transcript, 4 subagent transcripts, untruncated tool logs, jobs/agents state, standalone viewer |
| `tools/` | jadx 1.5.6, apktool 2.11.1, Android SDK 34, Gradle 8.7, relocated AVD |
| `*.log` | jadx, apktool, Gradle and emulator logs |

## Documentation

| Document | Covers |
|---|---|
| [docs/00-overview.md](docs/00-overview.md) | target identity, architecture, native library map |
| [docs/01-palm-gesture-shutter.md](docs/01-palm-gesture-shutter.md) | ArcSoft hand engine, 16 fps/62 ms cadence, 48-frame cooldown, forced 3 s timer, pref keys |
| [docs/02-voice-shutter.md](docs/02-voice-shutter.md) | Xiaomi wake-word engine (16 kHz PCM16, 640-sample frames), localized phrases, broadcast → trigger 110 |
| [docs/03-ai-features.md](docs/03-ai-features.md) | HAL `AI_SCENE_DETECTED` path, 46 scene ids, thresholds, beauty, AI watermark |
| [docs/04-computational-photography.md](docs/04-computational-photography.md) | 17-feature catalog, implemented vs hardware-bound split |
| [docs/05-reproduction.md](docs/05-reproduction.md) | exact decompile/build commands, pitfalls, verification, artifact locations |
| [reimpl/README.md](reimpl/README.md) | app architecture and feature mapping |

Every behavioural claim in the docs cites a decompiled file; inferences are
marked `[INFERENCE]`.

## Reimplementation

`reimpl/` is a CameraX app (`minSdk 29`) that reproduces the behaviour without
the vendor HAL:

| Stock feature | Reimplementation |
|---|---|
| Palm shutter (`HandGestureDecoder` + ArcSoft) | MediaPipe GestureRecognizer (`Open_Palm`) + hold/cooldown state machine, 3 s countdown |
| Voice shutter (`SpeechShutterImpl` + `MultiWakeupEngine`) | continuous `SpeechRecognizer` + localized keyword matcher, 2.5 s debounce |
| AI scene detection (`AiSceneMultipleASD`) | ML Kit labelling/faces + luma heuristics mapped to the same 46 ids with stock debounce rules |
| AI beauty (`libCameraEffectJNI`) | face/skin-mask guided smoothing |
| AI watermark (`ASDEngine`) | scene badge + timestamp + device line |
| HDR | exposure-bracketed burst (Camera2Interop) + Mertens fusion + tone curve |
| Super Night (ArcSoft, RAW) | aligned burst + robust temporal merge + shadow lift |
| Portrait depth bokeh | saliency mask + variable-radius bokeh blur |

Pure logic (state machines, classifier, image math) has no `android.*` imports
and is covered by JVM tests.

### Build and test

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk      # JDK 17/21; JDK 27 is too new for AGP 8.5
export ANDROID_HOME=$PWD/tools/android-sdk

./tools/gradle-8.7/bin/gradle -p reimpl clean :app:assembleDebug :app:testDebugUnitTest
```

Output: `reimpl/app/build/outputs/apk/debug/app-debug.apk`.
Result: build successful, **109 tests, 0 failures**.

### Run

```bash
adb install -r reimpl/app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant dev.pocof4.camera android.permission.CAMERA
adb shell pm grant dev.pocof4.camera android.permission.RECORD_AUDIO
```

The emulator image built for verification was moved out of `~/.android`:

```bash
ANDROID_AVD_HOME=$PWD/tools/avd \
  tools/android-sdk/emulator/emulator -avd poco34 -no-window -no-audio \
  -no-snapshot -gpu swiftshader_indirect -camera-back virtualscene
```

## Verification

- Clean build + full JVM suite: 109/109 pass.
- API 34 emulator: camera preview, scene chip (`Architecture (19)`), HDR save,
  5-frame Super Night + beauty + watermark + saliency save (`960x1280`), no crash.
- Voice shutter degrades to inert when no recognition service is installed.
- Palm shutter cannot run on x86_64 (MediaPipe ships arm64 only); the arm64 JNI
  is packaged and the missing-library path is handled without crashing.
- Bug found during the run: CameraX's `ImageProxy.toBitmap()` member shadowed the
  Kotlin extension, silently bypassing EXIF/rotation handling. The decoder is now
  `decodeCapturedJpeg()` and applies EXIF, rotation, and portrait output.

## Known limits

- No vendor hand engine or wake-word model: palm inference runs at 16 fps in
  MediaPipe IMAGE mode; voice shutter depends on the platform recognizer and
  possibly an offline language pack.
- HDR brackets require camera rebinds; slower devices capture the bracket over a
  longer window than a HAL burst.
- Hardware/HAL-bound features are documented but not faked: 48 MP remosaic,
  960/3840 fps, RAW-domain Super Night, HDR10/HLG encoding, time-freeze/clone.

# Poco F4 / MIUI Camera — reverse-engineering overview

## Target

| Property | Value | Evidence |
|---|---|---|
| APK | `MiuiCamera.apk` (137,603,498 bytes) | `apk/MiuiCamera.apk` |
| Package | `com.android.camera` | `apktool_out/AndroidManifest.xml` |
| Version | 5.1.001140.0 (versionCode 510011400) | `apktool_out/apktool.yml` |
| minSdk / target / compileSdk | 29 / 33 / 33 | `AndroidManifest.xml`, `apktool.yml` |
| Dex files | 5 (`classes.dex` … `classes5.dex`) | APK zip listing |
| Classes decompiled | 9,237 | `jadx2.log` |
| Source device | Poco F4 system image (`product/priv-app/MiuiCamera`) | mount path |

The APK ships both `MiuiCamera.apk` and a pre-optimized `oat/arm64/MiuiCamera.odex` +
`.vdex`; the dex inside the APK is complete, so decompilation does not need the odex.

## Artifacts in this repository

| Path | Contents |
|---|---|
| `apk/MiuiCamera.apk` | original APK |
| `apktool_out/` | decoded manifest, resources, `res/values*/strings.xml`, `assets/` |
| `decompiled2/sources/` | complete jadx Java sources (resources excluded) |
| `decompiled/` | partial first jadx pass (OOM at 88%, kept for reference only) |
| `native/` | extracted `lib/arm64-v8a` native libraries |
| `reimpl/` | Android reimplementation (CameraX + MediaPipe + ML Kit) |
| `docs/` | this documentation set |

## Stock architecture (as far as it matters for these features)

The app is a single-activity camera (`com.android.camera.Camera`) with per-mode
modules (`CaptureModule`, `VideoModule`, `SuperMoonModule`, …). Cross-component
communication uses a protocol bus: components attach to
`ModeCoordinatorImpl` and are looked up through interfaces in
`com.android.camera.protocol.protocols` (e.g. `CameraAction`, `TopAlert`,
`SpeechShutterDetect`). Feature toggles are persisted per-mode in
`DataItemRunning` under `pref_*` keys and mirrored in `CameraSettings`.

```
Camera activity
  ├── module (Camera2Module / CaptureModule / …)
  │     ├── CameraActionImpl.onShutterButtonClick(triggerMode)
  │     └── broadcast receiver (ACTION_SPEECH_SHUTTER)
  ├── PreviewDecodeManager ── HandGestureDecoder (16 fps)
  ├── SpeechShutterImpl ── com.xiaomi.asr.engine.MultiWakeupEngine
  ├── ASD interceptor chain ── AiSceneMultipleASD
  └── native vendor libraries (ArcSoft, MiAlgo, Xiaomi ASR)
```

Trigger modes are integer constants in
`com.android.camera.constant.TriggerConstant`:

| Constant | Value | Used by |
|---|---|---|
| `MODE_SHUTTER_BUTTON` | 10 | touch shutter |
| `MODE_HAND_GESTURE` | 100 | palm shutter |
| `MODE_SPEECH_SHUTTER` | 110 | voice shutter |

Both non-touch paths converge on `CameraAction.onShutterButtonClick(triggerMode)`;
`TimerBurstManager.getCountDownTimes()` turns mode 100 into a forced 3-second
countdown when the user timer is off.

## Native libraries relevant to this work

| Library | Role | Exported symbols |
|---|---|---|
| `libcamera_arcsoft_handgesture.so` | JNI bridge for palm detection | `Java_com_android_camera_handgesture_HandGesture_{init,detectGesture,unInit,setLogLevel}` |
| `libhandengine.arcsoft.so` | ArcSoft hand engine | `ARC_HE_Init`, `ARC_HE_Process`, `ARC_HE_Uninit`, `ARC_HE_GetVersion` |
| `libcamera_handgesture_mpbase.so` | ArcSoft memory/utility base | `MMemAlloc`, `Mpbase_GetVersion`, … |
| `libmulti-wakeup-engine.so` | Xiaomi ASR phrase/wake-word engine | JNI `wakeupInit/FeedData/Reset/Destroy/GetFrameSize` |
| `libmialgo_saliency.so`, `libmialgo_saliency_jni.so` | saliency for AI retouch/portrait | JNI wrappers in `com.android.camera.saliencychecker` |
| `libCameraEffectJNI.so` | vendor image-effect chain (beauty, filters) | — |
| `libDocumentProcess.so`, `libYuvWatermark.so` | document correction, YUV watermark | — |
| `libvideo_extra_interpolator.so`, `libvideo_extra_color_converter.so` | video frame interpolation / colour conversion | — |

## Feature documents

| Feature | Document | Reimplementation package |
|---|---|---|
| Palm (hand-gesture) shutter | [01-palm-gesture-shutter.md](01-palm-gesture-shutter.md) | `dev.pocof4.camera.gesture` |
| Voice shutter | [02-voice-shutter.md](02-voice-shutter.md) | `dev.pocof4.camera.voice` |
| AI scene / beauty / watermark | [03-ai-features.md](03-ai-features.md) | `dev.pocof4.camera.ai` |
| Computational photography | [04-computational-photography.md](04-computational-photography.md) | `dev.pocof4.camera.photo` |
| Tooling and reproduction | [05-reproduction.md](05-reproduction.md) | — |

## Reimplementation shape

`reimpl/` is a standalone Android app (`dev.pocof4.camera`, minSdk 29) that hosts:

- a CameraX preview + `ImageCapture` engine (`camera/CameraEngine.kt`) with
  ~16 fps frame fan-out, matching `HandGestureDecoder.getDecodeInterval() == 62 ms`;
- exposure bracketing through `Camera2Interop` for HDR, and same-exposure burst
  for Super Night;
- the three trigger paths wired in `MainActivity`: button, palm (3 s countdown),
  voice keyword;
- an AI pipeline for scene classification, beautification and watermarking;
- multi-frame HDR / night / saliency-blur processors in pure Kotlin.

The algorithms that can be expressed without vendor silicon are reimplemented
and unit-tested on the JVM. Sensor/HAL-bound features (48 MP remosaic, 960 fps,
RAW-domain Super Night, HDR10 encoding) are documented but intentionally not
faked.

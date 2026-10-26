# Poco F4 camera — reimplementation

Standalone Android app that rebuilds the interesting parts of MIUI Camera
5.1.001140.0 without the vendor HAL:

| Feature | Implementation | Stock equivalent |
|---|---|---|
| Palm shutter | MediaPipe GestureRecognizer (`Open_Palm`) + hold/cooldown state machine, 3 s countdown | ArcSoft HandEngine via `HandGestureDecoder` |
| Voice shutter | continuous `SpeechRecognizer` + localized phrase matcher | Xiaomi `MultiWakeupEngine` wake-word engine |
| AI scene detection | ML Kit labelling + face detection + luma heuristics mapped to the 46 stock scene ids | HAL `AI_SCENE_DETECTED` vendor tag |
| AI beauty | ML Kit faces + YCbCr skin mask + guided box smoothing | vendor `libCameraEffectJNI` beauty chain |
| AI watermark | scene badge + time + device line | `ASDEngine` → watermark overlay |
| HDR | exposure-bracketed burst (Camera2Interop) + Mertens fusion + tone curve | HAL HDR / bracketed pipeline |
| Super Night | aligned burst + robust temporal merge + shadow lift | ArcSoft Super Night (RAW) |
| Portrait blur | spectral/centre-surround saliency mask + variable bokeh blur | vendor depth-map bokeh |

## Build

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME=<android-sdk>
../tools/gradle-8.7/bin/gradle assembleDebug
../tools/gradle-8.7/bin/gradle testDebugUnitTest
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Layout

```
app/src/main/java/dev/pocof4/camera/
  core/      shared contracts (frames, settings, scene ids)
  camera/    CameraX engine (16 fps frame fan-out, bursts, MediaStore save)
  gesture/   palm detector + pure hold state machine
  voice/     speech controller + pure keyword matcher
  ai/        scene analyzer/classifier, beauty, watermark
  photo/     pure-Kotlin HDR, night, saliency cores + Bitmap bridges
  MainActivity.kt
app/src/test/java/dev/pocof4/camera/   JVM tests for every pure core
```

Design rules:

- pure logic (state machines, classifiers, image math) has no `android.*`
  imports and is unit-tested on the JVM;
- Android bridges are thin and keep all heavy work off the main thread;
- the analysis pipeline matches the stock 16 fps cadence
  (`HandGestureDecoder.getDecodeInterval() == 62 ms`).

## Limitations

- No vendor hand-engine or wake-word model: palm detection runs at 16 fps in
  MediaPipe IMAGE mode; voice shutter uses the platform recognizer, so it needs
  a recognition service and may require a downloaded offline language pack.
- HDR uses AE bracketing through CameraX rebinds; devices with slow rebinds will
  capture the bracket over a longer window than the stock HAL burst.
- Sensor-bound features (48 MP remosaic, 960/3840 fps, RAW Super Night, HDR10
  encoding) are documented in `../docs/04-computational-photography.md` but not
  faked.

See `../docs/` for the full reverse-engineering notes, stock class references
and evidence citations.

# NAM Player — Android

[![Build APK](https://github.com/YOUR_USERNAME/NAMPlayer/actions/workflows/build.yml/badge.svg)](https://github.com/YOUR_USERNAME/NAMPlayer/actions/workflows/build.yml)

تطبيق أندرويد لتشغيل ملفات `.nam` (Neural Amp Modeler) في الوقت الحقيقي مع دعم USB Audio.

## الوظائف
- ✅ تشغيل ملفات `.nam` (WaveNet, LSTM, Linear)
- ✅ ONNX Runtime acceleration + NNAPI
- ✅ Resampler تلقائي
- ✅ Cabinet IR (.wav)
- ✅ 3-Band EQ (Bass/Mid/Treble)
- ✅ Noise Gate
- ✅ Preset System
- ✅ تسجيل WAV
- ✅ USB Audio Interface (Focusrite, Behringer, Line 6...)
- ✅ VU Meters + Latency + CPU stats

## بناء APK على GitHub

1. Fork هذا المشروع
2. اذهب إلى **Actions** → **Build APK** → **Run workflow**
3. بعد ~5 دقائق: **Artifacts** → **NAMPlayer-debug** → حمّل الـ APK

## سلسلة DSP
```
Guitar → [Noise Gate] → [NAM/ONNX] → [Cabinet IR] → [3-Band EQ] → [Limiter] → Output
```

## متطلبات
- Android 8.0+ (API 26)
- يدعم USB Host للـ Audio Interface

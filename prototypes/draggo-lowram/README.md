# Drag & Go low-memory prototype skeleton

Standalone prototype package, `com.example.draggo`, with Canvas UI, accessibility service, and isolated OCR service. Integrate into an Android app project and verify SDK/plugin compatibility.

## Known limitations
- Not build- or device-tested yet.
- TextClassifier RemoteActions vary by Android version/device; fallback actions can be crowded out by the four-item limit.
- OCR crop uses node bounds, not precise segmentation. Bounds can be inaccurate and screenshots may fail on protected surfaces.
- OCR result broadcast and service/process teardown require API 26-35 device validation.
- No FileProvider/image sharing yet; image path is only used for OCR.
- RAM targets (15-20 MB idle, <80 MB peak) are not guaranteed; measure with Android Profiler and dumpsys meminfo.

# Drag & Go contextual actions (integrated)

Drag & Go is implemented directly in the existing Dynamic Island Android application.

## Enable and use
1. Install/run the app and enable the **Island accessibility service** in Android Accessibility settings.
2. In another app, long-press accessible text. Drag & Go shows a small contextual action pill.
3. Use **Copy**, **Share**, **Search**, or **Translate**. Android TextClassifier-provided smart actions are added when available.
4. If a long-pressed node exposes no text, Android 11+ screenshot capture attempts a bounds-limited crop and on-device ML Kit OCR; recognized text gets the same action pill.

## Implementation
- Uses the app's existing `IslandService` accessibility service; no second service, panel, or overlay permission.
- Small custom Canvas pill (software layer) instead of Compose for this transient feature.
- OCR is only invoked when accessible text is empty, on Android 11+, and the bitmap is clipped/downscaled before ML Kit.
- Context action overlay dismisses after 8 seconds or after selection.
- Actions are local: copy, share, Google web search, Google Translate; device TextClassifier actions when exposed by Android.

## Limitations
- Text and actions depend on the source app's accessibility tree and Android TextClassifier.
- Screenshot capture can fail for secure/protected content, and OCR requires Android 11+.
- OCR/long-press has not been device-tested yet; memory/GPU targets are not yet profiled. This feature uses a transient software Canvas UI and avoids Compose/ML Kit until OCR is requested.

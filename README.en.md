<div align="center">

<img src=".github/assets/logo-256.png" width="160" alt="Yomikae">

[Français](README.md) · **English**

# Yomikae

### Read Korean webtoons in English or French, translated on your phone

A manga and webtoon reader for Android that translates pages automatically, **with no server and no account**:
reading the text, translating it and writing it back into the bubbles all happen on the device.

[![License: Apache-2.0](https://img.shields.io/github/license/DelDaxter/Yomikae?labelColor=27303D&color=0877d2)](/LICENSE)

</div>

## What Yomikae does

- **Translates downloaded chapters** (Korean → English or French; Japanese planned) and shows the translated page in
  the reader, with a button to switch back to the original.
- **Fully local**: PaddleOCR text reading and the Hy-MT2 1.8B translation model run on the phone (GPU). A PC server
  remains available as an option for the curious.
- **Series memory**: if you also have the official English edition of a series, the app uses it as a reference
  (names, tone, phrasing) to translate the chapters it does not have yet.
- **Unified entry**: one entry per work even if you follow it on three sources; each chapter shows the best version
  available for your reading language (official edition, otherwise the translated raw).
- **Translation queue** with time remaining, reorderable, and automatic translation while reading or as soon as a
  chapter is downloaded.
- And everything you expect from a reader: sources through extensions, library, tracking, backups.

## Installation (beginner)

**You need**: an Android 8 phone or newer. For the embedded translation model, a recent phone with at least 8 GB of
memory (tested on a Galaxy S26 Ultra). On a more modest phone, the Google ML Kit engine is still available (weaker,
but light).

1. **Download the APK** from the [latest release](https://github.com/DelDaxter/Yomikae/releases/latest): the file
   **`Yomikae-vX.Y.Z-arm64-v8a.apk`** (Android phones and tablets, the right one in 99% of cases; the other file,
   "autres-appareils" (other devices), is only for a Chromebook or an emulator). Open it on the phone. Android will ask
   you to allow installs from this source: accept. Yomikae installs next to your other readers without replacing them.
2. **Install sources.** The extension repositories are already registered at first launch: go to
   *Browse → Extensions* and install the ones you want, for example **Naver Webtoon** for official Korean raws and
   **Webtoons.com** for official English. On Naver, search titles in Korean.
3. **Set up translation**: *More → Settings → Translation*.
   - *Source language*: Korean. *Target language*: English or French.
   - *Translation engine*: **On this device**. Under *Embedded model*, tap the model to download it
     (1.8 GB, once, over Wi-Fi).
   - *Text reading (OCR)*: PaddleOCR, already in the APK, nothing to download.
4. **Translate a chapter**: download a Korean chapter (⬇ icon on its row), then tap the 文A translation icon that
   appears next to it. Follow the progress in *More → Translation queue*. Open the chapter: it is translated. Expect
   2 to 3 seconds per page on a recent phone.

### Going further

- **Translate while reading** (on by default): opening a downloaded chapter puts its translation first in the queue
  and prepares the next one.
- **Translate new downloads**: global setting in *Translation*, or series by series (entry → ⋮ →
  *Automatic translation…*).
- **Unified entry**: from the entry you want to keep, ⋮ → *Unify entries…*, tick the other entries of the same work.
  Title, synopsis and chapters switch to your reading language when an edition has it. The language button next to
  the update interval changes the reading language of that series only.
- **Series memory**: entry → ⋮ → *Series memory*: choose the translated reference edition, *Read the reference
  text*, then *Match the bubbles*. If chapter numbers differ between editions (prologue counted as "1" on one side),
  *Detect* finds the offset.
- **Glossaries**: a global glossary (genre terms, nobility, forms of address) is provided and editable in
  *Translation → Global glossary*; each series has its own in its series memory.

## Updates

Every version is built and signed automatically on GitHub (*Releases*). The app tells you when a new version is
available and installs it over the old one, without losing anything.

## For developers

JDK 21 and the Android SDK (API 36), then `./gradlew :app:assembleDebug`. The debug app (`app.yomikae.dev`) lives
alongside the normal one.

## Licenses

Yomikae is licensed under Apache-2.0. The translation building blocks all use permissive licenses: PaddleOCR
(Apache-2.0), ONNX Runtime (MIT), LiteRT-LM (Apache-2.0), Hy-MT2 model (Apache-2.0). Google ML Kit remains available as
a light engine (Google's proprietary library, not required).

Text reading (PaddleOCR, 18 MB) ships in the APK. The translation model (1.8 GB) does not: it is downloaded once, on
demand, and verified (SHA-256).

## Thanks

Thanks to all the open-source projects Yomikae draws on and builds upon: reader, extensions, OCR and translation
models, and their communities.

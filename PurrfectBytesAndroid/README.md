# PurrfectBytes Android

Turns a sentence into a language-learning video and uploads it to YouTube, all on the
phone. It does not use the PurrfectBytes web server.

## What it does

1. **Get the text.** Type or paste it, or read it from a photo (camera or gallery).
   Text recognition runs on the phone (ML Kit) for Latin, Chinese, Japanese, Korean and
   Devanagari script.
2. **Speak it.** Microsoft Edge TTS over the internet (the default), or the phone's own
   text-to-speech engine, in 20 languages. With Edge TTS the voice can be chosen.
3. **Render the video.** 1280x720 at 30 frames a second: the text on the background
   picture, a red box and the cat logo on the character that is being spoken. Every piece
   of speech is rendered once and then played as often as asked, without encoding it
   again.
4. **Write title and description** with Gemini or Claude.
5. **Upload to YouTube**, optionally into a playlist.

How the text is read can be set in more detail. These are features of the web app as
well, with the same rules and the same limits:

- **Conversation mode.** Every line of the text is a turn in a conversation of two
  voices. Lines take turns, or name their speaker (`직원: 혼자 오셨어요?`): the name
  stays on screen but is never spoken or highlighted. Needs Edge TTS.
- **Speed sequence.** Repetitions at mixed speeds, such as 3 normal, 4 slow, 3 normal.
  Slow repetitions are marked "SLOW" in the corner of the video.
- **Pronunciation override.** A second text that is spoken in place of the first where
  the engine misreads a word. The video still shows the first.
- **Text sources.** A saved source gives its credit line to the description, word for
  word.
- **Review of vocabulary and grammar.** The model lists what it would explain; what is
  unticked is left out of the description.
- **Recent videos.** The last five videos are kept and can be watched or uploaded later.

Where the two apps differ: slow speech is slower here (30% against the web app's 20%),
the second speaker of a conversation gets a voice of his or her own without being asked
(the web app reads both with the same voice until one is chosen), and the text size and
the QR code cannot be changed.

## Setup

You need JDK 17 or newer and the Android SDK (API 34).

Create `local.properties` in this folder. It is ignored by git:

```properties
sdk.dir=/path/to/Android/Sdk
GEMINI_API_KEY=...
ANTHROPIC_API_KEY=...
```

The keys are optional. Without a key, "Auto-Fill Title & Description" reports that the
key is missing for that model; everything else works.

## Build

```bash
./gradlew installDebug        # build and install on the connected phone
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease     # smaller and faster, but unsigned: sign it before installing
./gradlew lintDebug
```

The APK contains the native libraries for arm64 phones only. Android Studio's Run button
adds what the chosen device or emulator needs. To package all four CPU types
(about 110 MB more):

```bash
./gradlew assembleDebug -PallAbis
```

## Tests

```bash
./gradlew testDebugUnitTest
```

The tests run on the computer, without a phone or emulator:

- Frames are laid out and drawn by Android's own text engine (Robolectric), so line
  breaking and right-to-left text are tested for real.
- `VideoGeneratorServiceTest` encodes real videos with the FFmpeg installed on the
  computer and checks frame by frame where the highlight is. `RenderFlowTest` does the
  same from the tap on "Render MP4" on. Both are skipped when `ffmpeg` and `ffprobe` are
  not installed.
- Edge TTS is tested against a stand-in for Microsoft's service on the same computer.
- Gemini, Claude and YouTube are replaced by stand-ins. **No test goes online or uses
  API credits.**
- Two tests of `YouTubeMetadataFormatTest` compare the prompts with those in the web
  app's source, and fail when the web app's prompts have changed. They are skipped when
  `PurrfectBytesWeb` is not next to this folder.

The first run downloads the Android runtime for Robolectric (about 300 MB).

## How the code is organised

```
app/src/main/java/com/purrfectbytes/android/
├── MainActivity.kt            switches between the main screen and the camera
├── viewmodels/
│   ├── MainViewModel          one function per user action
│   └── MainUiState            state of the screen
├── ui/
│   ├── screens/               MainScreen and its cards, CameraScreen
│   ├── components/            photo with tappable text boxes, message banner
│   └── theme/
├── services/
│   ├── SpeedSequence, Dialogue   sequences and conversations, as in the web app
│   ├── RenderPlan             what has to be generated for a video, and in which order
│   ├── TTSService             speech from Edge or from the phone's engine
│   ├── EdgeTTSEngine          the connection to Edge TTS
│   ├── EdgeTtsProtocol        its message format (no network, no Android)
│   ├── EdgeVoiceCatalogue     the voices to choose from
│   ├── SpeechCache            speech that was generated before
│   ├── HighlightTiming        which character is highlighted when
│   ├── FrameRenderer          draws a frame
│   ├── VideoGeneratorService  frames + audio -> MP4, with FFmpeg
│   ├── VideoLibrary           the videos that are kept
│   ├── SourceStore            the saved sources of texts
│   ├── TextRecognitionProcessor, LanguageDetector     ML Kit
│   ├── YouTubeMetadataGenerator, YouTubeMetadataFormat  title and description
│   ├── YouTubeAuthManager     sign-in (AppAuth)
│   ├── YouTubeAccountService, YouTubeVideoUploader     YouTube Data API
│   ├── MediaStorage           working files and their clean-up
│   └── AppSettings            choices remembered between runs
└── di/AppModule               what Hilt cannot build by itself
```

## Things to know when maintaining it

**Edge TTS is an unofficial service.** It needs no key, but Microsoft can change it
without notice. The app tells the service which Edge version it is; versions that get too
old are refused with HTTP 403. When that happens, copy `CHROMIUM_FULL_VERSION` from the
current [edge-tts](https://github.com/rany2/edge-tts) release
(`src/edge_tts/constants.py`) into `EdgeTtsProtocol.kt`. The web app gets the same fix
through `uv sync`.

**The list of voices comes from the same service** and is kept for a week. When it
cannot be loaded, the text is read by the voice of its language, or by the voice that was
chosen before.

**The prompts are copies of the web app's.** `YouTubeMetadataFormat.kt` holds the same
two prompts and the same title rules as
`PurrfectBytesWeb/src/services/youtube_metadata_service.py`. When a prompt changes
there, copy it here; a test fails until that is done.

**YouTube sign-in uses a Google client of type "iOS".** Google accepts the redirect
address the app needs (`com.googleusercontent.apps...:/oauth2redirect`) only for that
type. If Google ever stops accepting it, create a new OAuth client and put its ID into
`YouTubeAuthManager.kt` and into `appAuthRedirectScheme` in `app/build.gradle.kts`.
While the OAuth consent screen is in "Testing" mode, Google ends every sign-in after
7 days.

**The API keys are inside the APK**, readable by anyone who has the file. Keep the APK
to yourself, and set a spending limit for the keys.

**The YouTube sign-in is not backed up.** It gives full access to the channel, so it is
left out of cloud backup and device transfer. After moving to a new phone, connect again.

**Working files live in the cache folder**: speech, photos taken in the app, previews
and videos. A file is deleted when a newer one replaces it, and what is left over is
deleted when the app starts. The exception is the last five videos
(`VideoLibrary.MAX_VIDEOS`), which stay until newer ones take their place. Android may
clear the cache folder when the phone runs out of space, so a video that matters should
be uploaded.

**Speech is kept to be used again** (`SpeechCache`, up to 500 pieces in the cache
folder): a video that is rendered a second time copies its speech instead of asking
Edge TTS again. Speech of the phone's own engine is not kept.

**Saved sources are the only data that cannot be made again.** They are in
`files/saved_sources.json`, in the form of the web app's `saved_sources.json`, and are
part of the phone's backup.

**Release builds** shrink the code with R8. Classes that are converted to or from JSON
must be listed in `app/proguard-rules.pro`, otherwise their fields are removed and the
requests go out empty.

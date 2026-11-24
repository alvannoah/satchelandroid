# Satchel for Android

The student app for **Satchel**, an academic document repository that compares
documents by content (TF-IDF and cosine similarity) to detect duplicates, rank
search results and recommend related material. Final-year project, Diploma in
Software Engineering.

Satchel has three parts:

| Part | Repository |
|---|---|
| Backend and similarity engine (FastAPI, MySQL) | `SimilarityEngine` |
| Web app for students, lecturers, moderators and administrators (React) | `satchelfrontend` |
| This Android app, for students | `satchelandroid` |

The app uses the same API as the web app, deployed at `https://satchel-alvan.me/api`.
The endpoints and sign-in steps it relies on are described in the backend's
`docs/ANDROID_APP.md`.

## What students can do

- **Sign in** with email and password, or with Google (PKCE through a Custom Tab).
  The login token is encrypted with an Android Keystore key. The sign-in screen shows
  the institution's name and logo and follows its registration rules.
- **Choose their courses** on first sign-in, and change them later under My courses.
- **Browse and search** the library: search ranked by content (or by keyword count, the
  baseline), filters for programme, course, type, source, year, semester, academic year
  and reading state, a switch between their own courses and all courses, and three
  layouts (cards, compact rows, grid).
- **Read documents in the app**: the server's PDF copy of every document, page by page
  with zoom; documents open where the student stopped, and progress is saved to the
  server (also when read offline). A "Continue reading" list and read/in-progress marks
  in every list.
- **See related documents** with their similarity scores, and possible duplicates of
  their own uploads.
- **Download documents to read offline**, select several at once, share them or save
  them to the phone's Downloads folder.
- **Practise** with quizzes generated from a document's key terms (or written by an AI
  model, when the server has that switched on).
- **Upload** PDF, Word and PowerPoint files, see the duplicate check, and follow each
  upload's review status.

Moderation and administration are only in the web app.

## Building

Requirements: Android Studio (or the Android SDK with API 37) and JDK 17 or later.

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # unit tests
./gradlew assembleRelease      # app/build/outputs/apk/release/, signed if a key is configured
```

The app needs Android 10 (API 29) or later.

### Which server

The app talks to the deployed server by default. To use a backend running on your
computer, add a line to `local.properties`:

```properties
satchel.apiUrl=http://10.0.2.2:8000
```

`10.0.2.2` is the emulator's name for the computer. For a phone over USB, run
`adb reverse tcp:8000 tcp:8000` and use `http://localhost:8000`. Debug builds allow
plain `http` only to these addresses; release builds use HTTPS only.

### Signing release builds

Release builds are signed when `keystore.properties` exists in the project folder:

```properties
storeFile=/path/to/satchel-release.jks
storePassword=...
keyAlias=satchel
keyPassword=...
```

Neither this file nor the key belongs in the repository (both are git-ignored). Keep
a backup of the key: an app signed with a different key cannot be installed over this
one.

## How it is built

Kotlin and Jetpack Compose, with a deliberately small set of libraries: OkHttp and
kotlinx.serialization for the API, and the AndroidX lifecycle and Compose libraries.
There is no dependency-injection framework, navigation library or Retrofit; their
small replacements are in the code:

- `SatchelApplication.kt`: one container of shared objects (session, API client, caches).
- `data/`: the API client and models, the encrypted session, PKCE, document files
  (cache, offline downloads, the phone's Downloads), PDF page rendering, reading the text
  of Word and PowerPoint files, and reading-progress sync.
- `ui/Navigation.kt`: five tabs, each with its own back stack; each screen's state lives
  as long as its place on the stack.
- `ui/screens/`: one file per screen.

Pages are drawn with Android's built-in `PdfRenderer`. New fields from the server always
have defaults, so the app keeps working against an older deployment.

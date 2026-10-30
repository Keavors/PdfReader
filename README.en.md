English · [Русский](README.md)

# PDF Reader

A simple PDF reader for Android with no ads and no extra permissions: a list of recent files,
viewing with a toolbar, outline, jump to page, and a reading mode that leaves only the document on screen.

## Features

- Open PDFs through the system file picker (SAF), from a file manager, via the "Share" button
  and from messengers. Documents that other apps grant access to only once are copied into
  private storage so they stay in recents (up to 256 MB).
- Recent files: up to 15 entries with the opening date and the page where you stopped;
  duplicates of one file under different URIs collapse into one. Long press: show the folder,
  remove from the list, delete the file from the device.
- Viewing: vertical or horizontal paging, continuous strip or one page at a time, night mode
  (inversion), keep the screen on, lock the orientation.
- Document outline, jump to a page by number, links inside the document, password-protected documents.
- A tap on the page hides the toolbar, the scroll handle and the system bars; another tap brings them back.
- Rotating the screen does not reload the document and does not reset zoom and scroll.
- The ⋮ menu of a document: "Save as…" copies the file to any folder on the phone through the
  system save screen, where you can also create a new folder.
- A file from a messenger, mail or a file manager opens on top of that app, without a separate
  window: Back returns where it came from. Launched from its icon, the app starts with the list
  of files, and Back from a document returns to the list.

Rendering is done by [android-pdf-viewer](https://github.com/mhiew/android-pdf-viewer) (pdfium).
The app requests no storage permissions. The recent list is excluded from backups.

## Layout

```
app/src/main/java/com/example/pdfreader/
├── MainActivity.kt                    the app screen: recent files and the viewer
├── ViewerActivity.kt                  a document from another app: the same screen in that app's task
├── DocumentExporter.kt                "Save as…": a copy of the document in the chosen place
├── RecentFiles.kt                     recent list model and store
├── RecentFilesAdapter.kt              recent list
├── RecentFileActionsDialogFragment.kt entry actions: folder, remove, delete
├── ImportedDocuments.kt               copies of one-shot documents in private storage
├── ReaderSettings.kt                  viewing settings (SharedPreferences)
├── SettingsDialogFragment.kt          viewing mode dialog
├── OutlineDialogFragment.kt           outline
├── JumpToPageDialogFragment.kt        jump to page
├── PasswordDialogFragment.kt          document password
├── ConfirmDialogFragment.kt           confirmations
├── LockableScrollHandle.kt            a scroll handle that can be hidden for good
└── ReportingLinkHandler.kt            links in the document + "the tap was on a link" signal
```

Unit tests (Robolectric): `app/src/test/` — recent files, settings, document import and saving.

## Build

Needs JDK 17+ and the Android SDK (platform 37). Minimum Android 8.0 (API 26).

```bash
./gradlew testDebugUnitTest   # unit tests
./gradlew assembleDebug       # APK in app/build/outputs/apk/debug/
./gradlew assembleRelease     # release APK, signed with the debug key
```

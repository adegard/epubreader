# EpubReader

A simple EPUB reader for Android with text-to-speech, library management, and customizable themes.

## Features

- **EPUB parsing** with OPF spine-order chapter detection
- **Page-fitting text blocks** — text fills the screen, no scrolling needed
- **Text-to-speech** — local TTS engine with auto-fallback to online Google Translate TTS
- **Swipe navigation** — swipe left/right to turn pages
- **Library** — tracks up to 50 books with auto-saved reading positions
- **Bookmarks** — save and jump to bookmarks
- **Themes** — Day, Night, and OLED black
- **Adjustable text size** (10sp–28sp) and TTS speed (0.5×–2×)
- **Voice selection** for local TTS engine
- **Collapsible menu** with Bookmark, Bookmarks, Theme, and Settings
- **Hide bottom bars** setting for distraction-free reading
- **Progress indicator** — chapter, block, and overall percentage

## Install

Download `EpubReader.apk` from [Releases](https://github.com/adegard/epubreader/releases) or build from source.

Enable "Install from unknown sources" in your device settings if needed.

## Build from source

Requires Android SDK (API 34) and JDK 17.

```bash
./gradlew :app:assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Usage

1. Tap **Open EPUB** to select a `.epub` file
2. Swipe left/right or use **Prev/Next** buttons to navigate pages
3. Tap **Play** to start text-to-speech
4. Tap the **☰** menu button to access bookmarks, theme, and settings
5. In **Settings**, toggle "Hide bottom bars" for distraction-free reading

## Permissions

- **Internet** — for online TTS (Google Translate voice)
- **Storage** — to read EPUB files via Android document picker

## License

MIT

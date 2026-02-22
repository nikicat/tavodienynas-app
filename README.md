# Tavo Dienynas

Android app for accessing [manodienynas.lt](https://www.manodienynas.lt/) school diary with built-in translation support.

## Features

- Native Android wrapper for manodienynas.lt
- On-device translation using Google ML Kit (Lithuanian to English, Russian, Polish, Ukrainian)
- Translation caching for improved performance
- Secure WebView with restricted navigation
- File download support
- Pull-to-refresh
- Deep link support

## Requirements

- Android 7.0 (API 24) or higher
- Internet connection
- ~50-100 MB for translation models (downloaded on first use)

## Building

### Debug Build

```bash
./gradlew installDebug
```

### Release Build

Create `local.properties` with signing configuration:

```properties
RELEASE_STORE_FILE=/path/to/keystore.jks
RELEASE_STORE_PASSWORD=your_store_password
RELEASE_KEY_ALIAS=your_key_alias
RELEASE_KEY_PASSWORD=your_key_password
```

Build the release bundle:

```bash
./gradlew bundleRelease
```

Output: `app/build/outputs/bundle/release/app-release.aab`

## Publishing to Google Play

Upload the release AAB via Google Play Console. When creating a release, always **create a new release** rather than reusing a pending one — resubmitting an existing release may cause Google to review the old bundle.

### Native debug symbols warning

Google Play Console will show a warning: *"This App Bundle contains native code, and you've not uploaded debug symbols."* This is expected — the native `.so` files come from ML Kit and are already stripped by Google. There are no debug symbols to include. The warning is informational and does not block publication.

## Translation

The app uses Google ML Kit for on-device translation. Supported target languages:

| Language | Code | URL Path |
|----------|------|----------|
| English | en | /en/ |
| Russian | ru | /ru/ |
| Polish | pl | /en/ (site doesn't support Polish) |
| Ukrainian | uk | /ua/ |

Translation models are downloaded automatically on first use and cached locally.

## Privacy

This app does not collect any personal data. All translation and caching happens on-device.

**[Privacy Policy](https://gist.github.com/nikicat/ba02cf9ed75920912abe4c44de329c3b)**

## License

MIT

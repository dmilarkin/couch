# Couch

An IPTV app for Google TV (built for a TCL set, works on any Android TV / Google TV device) with an Apple TV style interface and an iPhone companion that runs in Safari.

**What it does**

- Signs in with an Xtream Codes login (server, username, password). An M3U link of the usual `get.php?username=…&password=…` kind works too, because it contains the same login.
- Downloads your provider's whole catalog into a local database, so browsing and search are instant.
- Builds shelves on top of the provider's categories: language (read from tags like `EN |`, `FR -`, `[GR]`), decade, genre, top rated and recently added.
- **Library filter:** switch off whole languages (say, Greek) or individual provider categories. Hidden things disappear everywhere: home, browse, search and live TV.
- Merges duplicate copies of a movie (4K, FHD, HD, other languages) into one title with a version picker.
- Remembers where you stopped in every movie and episode. Continue Watching, My List, and the next episode playing automatically.
- Series: season tabs and an episode strip.
- Player: playback speed (0.5x to 2x), quality (stream variants or the provider's versions), audio tracks, built-in subtitles, online subtitles from OpenSubtitles, zoom modes. Live TV has channel up/down and a now/next guide.
- Optional ratings and artwork from TMDB, plus IMDb and Rotten Tomatoes scores from OMDb.
- **Phone remote:** the TV shows a QR code and an address like `http://192.168.1.40:8642`. Scan the code, or type the address into any phone's browser on the same Wi-Fi, and a page served by the TV opens. From there you can sign in, set the library filter, browse, search, tap "Play on TV", and control playback (pause, seek, speed, subtitles, next episode). Your phone and TV just need to be on the same Wi-Fi. There's no App Store install and no Bluetooth.

---

## 1. Build the app (about 10 minutes, no coding)

You need a free GitHub account. GitHub builds the APK for you in the cloud.

1. Unzip `couch.zip`.
2. On github.com, create a new **public** repository (for example `couch`). Leave it empty.
3. Click **uploading an existing file** and drag in everything *inside* the unzipped `couch` folder. Commit.
   - On a Mac, Finder hides the `.github` folder. Press `Cmd + Shift + .` to show hidden files before dragging. If `.github/workflows/build.yml` still didn't upload, open **Add file → Create new file** on GitHub, type `.github/workflows/build.yml` as the name, paste the file's contents and commit.
4. Open the **Actions** tab. A build named "Build Couch APK" runs for about 5 minutes. When it shows a green check, the APK is published at:

   ```
   https://github.com/YOUR-NAME/YOUR-REPO/releases/download/latest/couch.apk
   ```

Prefer Android Studio? Open the `couch` folder, let Gradle sync, then use **Build → Build APK(s)**.

## 2. Install it on the TV (one time)

1. On the TV: **Settings → System → About**, then click **Android TV OS build** 7 times until it says you're a developer.
2. Install **Downloader** (by AFTVnews) from the Play Store.
3. Open Downloader and type the release link from step 1. Typing is easier with the Google TV app on your iPhone, which has a keyboard in its remote.
4. When asked, allow Downloader to install unknown apps (or turn it on under **Settings → Apps → Security & restrictions → Unknown sources**). Then tap **Install**.
5. Couch shows up under **Apps** on the home screen. You can move it to your favorites row.

**Updating later:** every push to the repo rebuilds the APK at the same link. Open Downloader, enter the same link and install over the old version. Your login, watch history and filters stay, because every build uses the same signing key (`app/couch-release.jks`).

If your repo is private, Downloader can't reach the link. Download `couch.apk` from the Actions run on your phone or computer instead, and send it with the "Send Files to TV" app.

## 3. First run

1. Open Couch on the TV. It shows a QR code.
2. Scan it with the iPhone camera and tap the link. Type your provider's server, username and password (or paste the M3U link) and tap **Connect**.
3. The TV downloads your library. Big providers take a minute or two.
4. Meanwhile the phone opens the **Library filter**. Untick the languages you never watch (or tap "Only English"), then go through the Movies, Series and Live tabs to hide whole categories. Tap **Save**.

Tip: in Safari, **Share → Add to Home Screen** turns the remote into an app icon.

## 4. Optional free extras

Add these keys in **Settings** on the phone. Each one is optional.

| What | Where to get it | What it adds |
|---|---|---|
| TMDB API key | themoviedb.org → Settings → API | Better backdrops, plots, TMDB ratings, cast |
| OMDb API key | omdbapi.com/apikey.aspx | IMDb, Rotten Tomatoes and Metacritic scores (needs TMDB too) |
| OpenSubtitles API key | opensubtitles.com → API consumers | Online subtitle search in the player |
| OpenSubtitles username and password | your opensubtitles.com account | More subtitle downloads per day |

This product uses the TMDB API but is not endorsed or certified by TMDB.

## Using it

**On the TV remote**

- Home: move across posters and the backdrop changes. OK opens a title. On Continue Watching, OK resumes right away and a long press opens the details.
- Movies and Series tabs: filter by category, language, decade and genre, and sort by date, rating or title.
- Live TV: categories on the left, channels in the middle, the guide on the right. Hold OK on a channel to add it to Favorites.
- Player, with the controls hidden: left/right skips 10 seconds (hold to go faster), OK pauses, up/down shows the controls. On live TV, up/down changes the channel.
- Player, with the controls showing: the bar has Audio, Subtitles, Speed, Quality, Version, Zoom and Next episode. Back hides the controls, and Back again leaves the player.

**On the phone:** Home, Browse, Search, Remote and Settings tabs. The Remote tab lists the subtitle tracks, so you can switch them off, back on, or to another language, or search online. "Play on TV" works whenever Couch is open on the TV. If the TV app is in the background, the title starts the moment you open Couch.

## Troubleshooting

- **The phone page won't load.** Make sure the phone and TV are on the same Wi-Fi. Guest networks often block devices from seeing each other. The address is shown in the TV's Settings.
- **"The provider refused the stream".** You may be at your connection limit (another device is watching). Some providers also only allow certain players, so try another **Player identity** in Settings.
- **Live channels stutter or won't start.** In Settings, switch off **Live TV as HLS** to use MPEG-TS instead.
- **A movie plays with no sound.** The TV can't decode that audio (usually DTS). Try another version. Or uncomment the `media3-ffmpeg-decoder` line in `app/build.gradle.kts`, using a version that matches Media3 from the Jellyfin releases page, and rebuild. That adds software decoding.
- **Movies are missing from a language shelf.** Languages come from the provider's naming. Anything without a tag shows as "No language tag" in the Library filter.
- **Plain M3U playlists** (no username and password in the link) aren't supported. Nearly every provider offers the Xtream format.

## How it's built

- Kotlin, Jetpack Compose for TV (`androidx.tv:tv-material`), Media3 ExoPlayer with HLS and OkHttp, Room for the catalog, Coil for images.
- `data/` has the Xtream client (streaming JSON parser for huge catalogs), the title parser (language, year, quality, genre), the Room database, catalog sync, TMDB/OMDb and OpenSubtitles.
- `remote/` has the small web server on the TV (NanoHTTPD, port 8642) that serves `assets/web/index.html` to your phone. By default any phone on your Wi-Fi can use it. Turn on **Phones need the QR code** in the TV's Settings to allow only phones that scanned the code (it carries a pairing token).
- `ui/` has the screens.

The signing key is committed so updates install over each other. That's fine for a personal sideloaded app. Don't reuse it for anything published.

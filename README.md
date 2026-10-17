# JD's Apps

One place to open every live app and download every APK from [geek4945-creator](https://github.com/geek4945-creator), plus what is planned or on hold.

- **Web app (PWA):** https://geek4945-creator.github.io/apps-hub/
- **Android APK:** see the [latest release](https://github.com/geek4945-creator/apps-hub/releases/latest) (`com.geek4945.appshub`)

Each app shows an initial/colour tile, a one-sentence description, a version badge, **Open web** / **Download APK** buttons and when it was last updated (Pacific time). Search and filter by **status** (Live, In progress, Idea, Held) and by platform (has APK / has web / both). Live apps are listed first. Items that are not published yet show their status and have no download or open links; a GitHub link appears only if a repo exists.

## The list is data
Everything comes from [`apps.json`](apps.json) (copied to `docs/apps.json` for the web app and `android/app/src/main/assets/apps.json` as the Android offline fallback). The Android app also re-downloads `apps.json` from this repo's `main` branch on launch and on **Refresh**, so list changes never need a new APK.

Fields per app: `id, name, description, status, status_note, platform, version, apk_url, apk_name, apk_private, extra_downloads[], web_url, repo_url, release_url, last_updated (UTC ISO), color, tags, notes, web_ok, apk_ok`.

### Adding or updating an app
1. Edit [`sources.json`](sources.json): a Live app needs `repo`, optional `web_url`, and `apk_asset` (a regex for the release asset name). A planned item only needs `status` (`In progress`, `Idea` or `Held`), `status_note`, and a description. When it ships, add the repo/links and set `status` to `Live`.
2. Run `python3 tools/refresh_apps.py` (uses the GitHub API; set `GITHUB_TOKEN` or be logged in with `gh`). It picks each app's newest release that has a matching APK, checks the links, sorts live apps first, and rewrites all three copies of `apps.json`. If one lookup fails the previous entry is kept.
3. Commit and push `main`.

### Nightly refresh
[`.github/workflows/nightly-refresh.yml`](.github/workflows/nightly-refresh.yml) runs the same script at **midnight America/Los_Angeles** (cron fires at 07:00 and 08:00 UTC; the job only continues when it is hour 00 in Los Angeles, so daylight saving is handled), commits `apps.json` if anything changed and triggers a Pages rebuild. Run it any time from the Actions tab (*Run workflow*). Private repos (TVmate Touch's APK) cannot be read by the Actions token, so their last known entry is kept; refresh locally with your own login to update them.

## How updates work
There are two kinds of update, and neither needs you to do anything except tap "Install" now and then.

**1. The list (every time you open the app).** On every open, on pull-down and on **Refresh**, the Android app downloads the live `apps.json` from this repo (`raw.githubusercontent.com`, cache bypassed). The last good copy is cached on the phone, so it still works offline; a copy bundled in the APK is the first-run fallback. The web app does the same: `apps.json` is fetched network-first (the service worker only serves its cached copy when you are offline) and it re-fetches when you return to the tab or tap ↻. New app versions therefore show up as soon as the nightly job (midnight Pacific) commits them.

**2. The hub app itself (at most once a day).** After refreshing the list, if more than 24 hours have passed since the last check (or you tapped Refresh / *Check for updates now*), the app asks GitHub for this repo's latest release. If its version is newer than the installed one it downloads the APK in the background (progress shown in a banner at the top), checks that it is this app (same package, higher versionCode, expected size), and shows **Install update**. Tapping it opens Android's own installer, where you confirm. Android does not allow silent installs of sideloaded apps, so that confirmation cannot be skipped. If GitHub's API is unavailable the check falls back to the hub's own entry in `apps.json`. An update that was found but not installed is remembered and offered again on the next open.

**One-time permission:** the first time you install an update Android sends you to *Install unknown apps* for **JD's Apps**; switch on *Allow from this source*, press back, and the installer opens. Updates install over the existing app (same package `com.geek4945.appshub`, same signing key), keeping your theme choice and cache.

Publishing an update: bump `versionCode`/`versionName` in `android/app/build.gradle`, build the signed release, and create a GitHub release `vX.Y.Z` with the APK attached as `jds-apps-hub-vX.Y.Z.apk`. Installed apps pick it up within a day (or on the next manual Refresh).

## Build the Android app
Requirements: JDK 17, Android SDK 34, Gradle 8.7+.
```bash
cd android
gradle testDebugUnitTest assembleDebug
gradle assembleRelease      # signed if /workspace/apps-hub-keystore/keystore.properties exists
```
Release signing reads `storeFile, storePassword, keyAlias, keyPassword` from `-PkeystoreProps=<file>` or env `KEYSTORE_PROPS` (default `/workspace/apps-hub-keystore/keystore.properties`). The keystore and passwords are **never** in the repo; back them up, because an APK signed with another key cannot update an installed one.

## Tests
```bash
python3 -m unittest discover -s tests      # apps.json schema, copies in sync, status order, links
cd android && gradle testDebugUnitTest      # parser, search, filters, Pacific-time formatting
```

## Install the APK
Allow "Install unknown apps" for your browser or file manager, open the downloaded APK, install. Min Android 6.0 (API 23).

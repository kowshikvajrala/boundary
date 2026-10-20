# Boundary

Boundary is a personal fork of [Curbox](https://github.com/curbox-app/curbox-android) (GPL 3.0), an Android app, website and keyword blocker. It is sideloaded on the owner's own phone and is not published to any store.

- Repo: `kowshikvajrala/boundary` (private). Remote `origin`; Curbox is remote `upstream`.
- Architecture, invariants and code conventions: @agents.md (Curbox's own guide). Follow it, including the settings change delay checklist for gated fields and the rule that the accessibility service must never crash.

## Owner preferences

- **Never build locally.** Build and release only through GitHub Actions (see Releasing).
- **No Shizuku and no root.** Do not build features that depend on them.
- Ask before big or irreversible changes. When a request is ambiguous, ask with a short multiple choice.
- Explain things simply; the owner is not an Android developer.

## Identity and build

- `applicationId` `app.boundary.blocker`, app name `Boundary`, both set at the top of `app/build.gradle.kts`.
- The Kotlin package and namespace stay `neth.iecal.curbox`. A full rename to `app.boundary.blocker` was released as 1.0.3 and then reverted at the owner's request. Do not retry it without asking. See Pitfalls.
- One build only: no product flavors and no debug variant (`beforeVariants` disables debug). `./gradlew :app:assembleRelease` is the only APK.
- `BuildConfig.FDROID_VARIANT = true` hides the account and sync UI. Cloud sync was removed because it talks to Curbox's own servers.
- Broadcast and API action strings use the `app.boundary.blocker.` prefix, so the app never talks to a real Curbox install.
- The accessibility service label is `@string/blocker_service_label` ("Boundary App Blocker"), set in both the manifest and `xml/app_blocker_service_config.xml`. `AntiUninstallBlocker` matches on that label, so change both together.
- User-visible text says Boundary. Keep "Curbox" in Nethical's bio, the donation strings, the share message, links to curbox.app and the Curbox repos, LICENSE and CITATION. Those describe the original author's work.

## Signing

- The keystore is at `/home/ansible/syncpair-signing/release.jks`, with its passwords in `passwords.txt` next to it. It is shared with the owner's SyncPair app. Never print, copy or commit its contents.
- GitHub secrets: `SIGNING_KEY` (base64 keystore), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. The workflow writes `keystore.properties`; Gradle signs the release only when that file exists.
- Signer SHA-256 begins `EF:E3:F9:A4` and ends `E4:41`.

## Releasing

`.github/workflows/release.yml` runs on a `v*` tag push: unit tests, signed `assembleRelease`, then a GitHub release with `boundary-<version>.apk`. The release notes come from `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`. **`versionCode` must always go up and is never reused**, even for deleted releases, because Android will not install a lower code over a higher one.
2. Write `changelogs/<versionCode>.txt` in plain, user facing language.
3. Commit, then `git tag -a vX.Y.Z -m "Boundary X.Y.Z"`, push `main`, push the tag.
4. Poll with `gh api repos/kowshikvajrala/boundary/actions/runs/<id> --jq '.status, .conclusion'`. `gh run watch` often dies on GitHub API 502 errors.
5. If a build fails before publishing, fix it, move the tag (`git tag -fa`, `git push -f origin <tag>`) and let it rebuild. Never move the tag of a published release; ship a new version instead.
6. After publishing, report to the owner what changed, what to test on the phone, and that it has not been tested on a device.

GitHub Actions quirks seen here: a tag push sometimes does not start a run (start one with `gh workflow run release.yml --ref <tag>`); runs can show "in progress" long after finishing, and then refuse to cancel as "completed"; `android-actions/setup-android` breaks the build (the runner already has the SDK).

## Features added in this fork

| Feature | Where |
|---|---|
| Full screen wait screen with the app logo and theme colors | `ui/activity/WarningActivity.kt`, `res/layout/dialog_warning_overlay.xml`, `drawable/ic_logo_mark.xml`, theme `Theme.Curbox.WarningFullScreen` |
| "Never unlock" groups show a small top pill instead of the wait screen (apps, keywords, reels) | `ui/overlay/BlockedPillOverlay.kt` (on `BaseBlockingService.blockedPill`), called from `AppBlocker`, `KeywordBlocker`, `ReelBlocker` |
| Blocklists: download domain lists (HaGeZi NSFW is built in as `list:adult`, or any https link) and use them as `list:<id>` keywords | `utils/BlocklistStore.kt`, `KeywordMatcher`, `keywordBlocker/BlocklistsFragment.kt`, the "Block adult sites" switch in `CreateKeywordGroupFragment` |
| The unlock notification lists every unlocked group with its own time left | `AppBlocker.showNextCooldownNotification`, `utils/TimerNotification.kt` (`breakdown`) |
| Unlock time counts only while the group's apps are in front; leftover time is frozen for up to 1 hour | `AppBlocker.syncUnlocksWithForegroundApp`, `pausedUnlocks` |
| Eye breaks (20 20 20 rule) with a live countdown on the Reducers card | `anti_stimulants/EyeBreakReminder.kt` (in the service, broadcasts its schedule), `ReducersFragment.setupEyeBreakCard` |

Behavior notes:
- Blocklists follow the group's schedule and usage limit exactly like typed keywords. New keyword groups keep Curbox's 60 minute default allowance; the owner chose not to change it.
- Blocklist files live in `filesDir/blocklists/`, not in Room or the settings JSON. Deleting a list is refused while any group uses it, so the settings change delay cannot be bypassed.
- Eye breaks are not gated by the settings change delay, because they are a reminder, not a restriction. A screen off of 20 seconds or more resets the count.

## Tried and removed (do not re-add without asking)

- **`isAccessibilityTool="true"`** (1.0.5): did not help the owner's banking apps. Removed in 1.0.7.
- **Banking pause** (1.0.6): turned the service off while a banking app was open. The owner found the adb grant and notification fallback too much. Removed in 1.0.7.
- **Full package rename** (1.0.3): reverted, and the release deleted. The next version jumped to 1.0.4 (versionCode 5).

## Pitfalls learned the hard way

- `Curbox.kt` and `blockers/uihider/script/Lexer.kt` contain a literal NUL character. The Edit tool refuses them and `git grep -I` skips them as binary. Edit them with Python on bytes.
- When copying an XML block that nests the same tag (for example a `MaterialCardView` inside a `MaterialCardView`), find the closing tag with its newline and indentation, or the copy is cut short. Validate changed XML with `python3 -c "import xml.dom.minidom; xml.dom.minidom.parse('<file>')"` before pushing.
- `ReducersFragment` builds its cards in `onCreateView`, where `viewLifecycleOwner` does not exist yet. Put lifecycle work in `onViewCreated`.
- A top level package named `app.*` is shadowed by any property called `app` (as in `BackupManager`), so fully qualified names break. Use imports.
- A string kept unchanged on purpose must keep its resource name too, or the code referencing it breaks.
- Kotlin raw strings (`"""`) do not process escapes. `ScriptLanguageTest` had `\\n` inside one and failed until fixed. Curbox's own CI never ran the unit tests.
- `AppBlocker` also runs its check on `TYPE_WINDOW_CONTENT_CHANGED`, which the keyboard emits. Logic about "which app is in front" must use window state changes only and skip the keyboard and system UI.
- Append to files with a trailing newline. An append without one once merged two `.gitignore` lines.

## Release history

| Version | Code | Summary |
|---|---|---|
| 1.0.0 | 1 | Fork: new identity, single signed build, wait screen, blocklists, Actions release |
| 1.0.1 | 2 | Accessibility service named after the app (manifest label still said Curbox) |
| 1.0.2 | 3 | "Boundary App Blocker" label, Boundary text throughout, "Block adult sites" switch |
| 1.0.3 | 4 | Full package rename. Reverted, release deleted |
| 1.0.4 | 5 | Wait screen uses app logo and theme, never unlock pill, notification lists every unlock |
| 1.0.5 | 6 | `isAccessibilityTool` flag (later removed) |
| 1.0.6 | 7 | Banking pause (later removed) |
| 1.0.7 | 8 | Removed Banking pause and the flag |
| 1.0.8 | 9 | Eye breaks |
| 1.0.9 | 10 | Live countdown on the Eye breaks card |
| 1.0.10 | 11 | Unlock time counts only while the apps are in use |

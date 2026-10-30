# Boundary

Boundary is a personal fork of [Curbox](https://github.com/curbox-app/curbox-android) (GPL 3.0), an Android app, website and keyword blocker. It is sideloaded on the owner's own phone and is not published to any store.

- Repo: `kowshikvajrala/boundary` (private). Remote `origin`; Curbox is remote `upstream`.
- Architecture, invariants and code conventions: @agents.md (Curbox's own guide). Follow it, including the settings change delay checklist for gated fields and the rule that the accessibility service must never crash.
- **Where agents.md is out of date for this fork:** there are no `full`, `playstore` or `fdroid` flavors, no `src/sync` or `src/fdroid` folders, no debug builds and no cloud sync. The Curbox API (`api/`, the AIDL file, `ApiFragment`, `CURBOX_API.md`) and the `:apitester` module were deleted in 1.8.0, and Focus mode, Auto DND, grayscale and Shizuku were deleted in 1.9.0, so ignore those parts of agents.md too (see Tried and removed). Ignore its flavor table, sync notes and `./gradlew ...FullDebug` commands; this file's Releasing section replaces them. Everything about the accessibility service, multiple processes, settings and Room still applies.

## Owner preferences

- **Local builds are for testing only.** Run `./gradlew :app:testReleaseUnitTest :app:assembleRelease` locally before every release (tools: see Local build). Signed releases are built and published only through GitHub Actions.
- **No Shizuku and no root.** Do not build features that depend on them.
- Ask before big or irreversible changes. When a request is ambiguous, ask with a short multiple choice.
- Explain things simply; the owner is not an Android developer.

## Identity and build

- `applicationId` `app.boundary.blocker`, app name `Boundary`, both set at the top of `app/build.gradle.kts`.
- The Kotlin package and namespace stay `neth.iecal.curbox`. A full rename to `app.boundary.blocker` was released as 1.0.3 and then reverted at the owner's request. Do not retry it without asking. See Pitfalls.
- One build only: no product flavors and no debug variant (`beforeVariants` disables debug). `./gradlew :app:assembleRelease` is the only APK.
- Cloud sync was removed because it talks to Curbox's own servers. Its leftover code (`data/sync`, `SyncFragment`, `AccountController`, `CryptoBox`, the Info tab account card, the "synced browsing" usage rows and the `FDROID_VARIANT` flag) was deleted after 2.1.1.
- Broadcast and API action strings use the `app.boundary.blocker.` prefix, so the app never talks to a real Curbox install.
- The accessibility service label is `@string/blocker_service_label` ("Boundary App Blocker"), set in both the manifest and `xml/app_blocker_service_config.xml`. `AntiUninstallBlocker` matches on that label, so change both together.
- User-visible text says Boundary. Keep "Curbox" in links to curbox.app and the Curbox repos, LICENSE and CITATION (the bio, donation and share cards are gone, see Features). Those describe the original author's work.

## Signing

- The keystore is at `/home/ansible/syncpair-signing/release.jks`, with its passwords in `passwords.txt` next to it. It is shared with the owner's SyncPair app. Never print, copy or commit its contents.
- GitHub secrets: `SIGNING_KEY` (base64 keystore), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. The workflow writes `keystore.properties`; Gradle signs the release only when that file exists.
- Signer SHA-256 begins `EF:E3:F9:A4` and ends `E4:41`.

## Local build

JDK 21 and the Android SDK are in the home folder, not on PATH: `~/dev-tools/jdk-21` and `~/dev-tools/android-sdk`. `local.properties` (gitignored) points `sdk.dir` there.

```bash
export JAVA_HOME=~/dev-tools/jdk-21 ANDROID_HOME=~/dev-tools/android-sdk PATH=~/dev-tools/jdk-21/bin:$PATH
./gradlew :app:testReleaseUnitTest :app:assembleRelease
```

Without `keystore.properties` this gives `app/build/outputs/apk/release/app-release-unsigned.apk`, which is for checking only. Never write `keystore.properties` locally.

## Releasing

`.github/workflows/release.yml` runs on a `v*` tag push: signed `assembleRelease`, then a GitHub release (unit tests run locally instead, see Local build) with `boundary-<version>.apk`. The release notes come from `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`. **`versionCode` must always go up by 1 and is never reused**, even for deleted releases, because Android will not install a lower code over a higher one. Choose `versionName` by semantic versioning, based on what the release contains (the owner asked for this from 1.0.10 on):
   - **Patch** (`1.1.0` → `1.1.1`): only fixes; nothing new for the owner to learn.
   - **Minor** (`1.1.3` → `1.2.0`): adds a feature or visibly changes how one works, while existing setups keep working. Reset patch to 0.
   - **Major** (`1.4.2` → `2.0.0`): breaks or reshapes existing use, for example the owner must redo setup (re enable accessibility, re add widgets) or lose data. Reset minor and patch to 0, and confirm with the owner first.
   - A release with both fixes and a feature is a minor bump. When unsure, say which bump you chose and why.
2. Write `changelogs/<versionCode>.txt` in plain, user facing language.
3. Run the local build and tests. Commit, then `git tag -a vX.Y.Z -m "Boundary X.Y.Z"`, push `main`, push the tag.
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
| One unlock notification for all blockers (`blockers/UnlockNotification.kt`, id 1005), fed by the merged UNLOCKS timers in `WidgetTimers`: it counts down to the soonest running unlock and lists app groups, keyword groups and short videos, paused ones last. The blockers keep their own `TimerNotification` only for callbacks (`postsNotification = false`). It is an Android 16 Live Update (status bar chip with a countdown): it sets the `android.requestPromotedOngoing` extra by its string name and a chronometer `when` at the end time, uses BigTextStyle (InboxStyle cannot be promoted) and declares `POST_PROMOTED_NOTIFICATIONS`. The app still compiles and targets SDK 35; if promotion turns out to need targetSdk 36, that is the next step. The Permissions screen shows a "Live updates" row on Android 16 (`canPostPromotedNotifications` called by reflection) | `AppBlocker.showNextCooldownNotification`, `utils/TimerNotification.kt` (`breakdown`), `PermissionsFragment` |
| Keyword groups pause too: an unlock counts only while a page matching the group is open (`KeywordBlocker.syncUnlocksWithPage`, fed by every website observation, null when no page; screen off pauses, `ACTION_USER_PRESENT` resumes if the same page is still in front because the tracker does not re-report an unchanged page). Same `pauseUnlockWhenAway`/`keepLeftoverMs` fields and keep rule as app groups; paused unlocks go to the widget and notification as negative values. The settings screen shows the section when `WarningConfigFragment` gets `supportsPauseUnlock` (app and keyword groups, not reels) | `KeywordBlocker`, `WarningConfigFormController.supportsPauseUnlock` |
| Unlock time counts only while the group's apps are in front (per app group switch "Pause unlock time when you leave", on by default). Leftover time is kept for a set time (default 1 hour) counted from the first time the apps were left; reopening does not restart it (`firstLeftAtMs` carries it through a resumed unlock). Dropping leftover ends the unlock like running out does: `dropLeftover` records `UnlockLocks.unlockEnded` at the moment the keep time ran out (or now, if it ran out while the apps were open), so a lock after unlock starts then; a `leftover:<id>` handler runnable drops it on time even while the apps stay closed, rescheduled after settings load. Keyword groups do the same (`KeywordBlocker.dropLeftover`). Turning the switch on or keeping time longer is gated | `AppBlocker.syncUnlocksWithForegroundApp`, `pausedUnlocks`, `pauseUnlockWhenAway`, `keepLeftoverMs` |
| X (Twitter) in the Reels blocker and counter. Its viewer is Compose with no ids or text, so it is matched by tree path and full screen size. It is blocked but not counted (`countsReels = false`): counting by pager scroll position was unreliable, so the owner had it removed in 1.1.1 | `hardcoded/ReelAppConfig.kt`, `ReelAppData.countsReels`, `ReelsCountTracker` |
| Wait screen messages: one box each ("Add another message"), long messages switch to smaller left aligned text | `WarningConfigFormController.addMessageBox`, `res/layout/item_warning_message.xml`, `WarningActivity.showWarningMessage` |
| Wait before unlocking in seconds, minutes or hours; countdown shows a clock past one minute; vibration capped at 30 s | `WarningConfigFormController.SecondsField`, `WarningActivity.proceedCountdownText` |
| Lock after unlock ends: when a timed unlock runs out, the group is fully locked for a set time (pill, no wait screen). Apps, keyword groups and the Reels blocker. Lock starts at the unlock's end time and follows the current config | `blockers/UnlockLocks.kt`, `AppBlocker.endUnlock` (for app groups it also closes the group's apps with `killBackgroundProcesses` when the lock starts, on each blocked open and every minute until the lock ends: `closeLockedApps`, `keepLockedAppsClosed`; not for reels or keyword locks, which would close all of Instagram or the browser; with notification access, `LockedAppNotificationListener`, in the blocker process, snoozes the locked apps' notifications until the lock ends, from `AppBlocker.lockedUntil` kept by `refreshLockedPackages`), `KeywordBlocker.evaluateAndBlock`, `ReelBlocker.isCooldownActive`, `lockAfterUnlockMs` |
| Home lock: a group that uses a lock after unlock can never be weakened (turned off, shortened, paused, deleted, temporarily disabled), at home or away (`HomeLock.weakensLock`, checked in `DataStoreManager.updateGated` and the temporary disable functions). Emergency off is the only way to loosen one. There is no away pass any more (one existed until 1.10.0, and in between Away mode lifted the guard, which the owner rejected because changes made while away carried over to home). Away mode only pauses: "Turn on Away mode" on the Home lock screen explains, reads one location and starts it only if the owner is away (`HomeLockFragment.checkAway`, `DataStoreManager.startAwayMode`). During it `AppBlocker` and `ReelBlocker` stop blocking and locks stop (no closing apps, no hidden notifications, no lock rows in the widget); keyword blocking stays on. Opening a blocked app, watching short videos or hitting a blocked site during Away mode calls `AwayModeHomeCheck.checkIfBackHome` (shared, at most one location read a minute, needs ACCESS_BACKGROUND_LOCATION), which ends Away mode if the owner is home. "I am home, turn blocking back on" ends it by hand. Home cannot move while any lock is in use or Away mode is on (moving it while away would make home count as away). Mock locations are refused; "away" means the reading's whole accuracy circle is outside the 150 m home radius | `utils/HomeLock.kt`, `blockers/AwayModeHomeCheck.kt`, `utils/HomeLocationChecker.kt`, `advanced/HomeLockFragment.kt`, `Settings.homeLocation`, `Settings.isAwayModeActive` |
| Tamper protection "Away from home" method: removed after 1.10.0 at the owner's request (Away mode and Emergency off cover it). `AntiUninstallMode.AWAY_FROM_HOME` stays so stored settings load; its card is hidden and it behaves like the one day wait | `AntiUninstallFragment` |
| Payment pause: for payment apps that refuse to run with an accessibility service on. Switches Boundary's service off for 5 minutes via `Settings.Secure` (needs WRITE_SECURE_SETTINGS from a one time adb grant; no pause is offered without it), opens the chosen app, then switches it back on. No daily limit (owner's choice). Ends via `PaymentPauseJob`, with safety nets in `BootReceiver`, `ServiceWatchdogJob` (which also skips its "service down" warning during a pause) and `FragmentActivity.onCreate`. With usage access, `PaymentPauseWatchService` (foreground, main process) checks the front app every second and ends the pause at once when an app from an active app group, a Reels blocker app or (with website blocking on) any browser opens; a blocked app stays watched even if it is also on the payment list, `PaymentPause.start` refuses blocked apps, and the picker hides them (`IGNORED_APPS`), because the owner found that listing a blocked app as a payment app bypassed its block (`PaymentPause.watchedPackages`). Settings, the package installers and common OEM security apps are always watched too, so the pause cannot be used to remove the device admin, force stop or uninstall. Auto start: when a window state change switches from another app into a payment app, `AppBlocker.maybeStartPaymentPause` sends the user home, closes the app and opens `PaymentPauseStartActivity` (invisible, main process, because the pause state lives in main process SharedPreferences), which starts the pause and reopens the app 700 ms after the service goes off. It never fires on the first event after the service starts (`lastWindowPackage` is null), so a pause ending inside the payment app does not loop. Known gap: the Android 13+ "Active apps" Stop button in quick settings, and (without usage access) everything above | `utils/PaymentPause.kt`, `services/PaymentPauseJob.kt`, `services/PaymentPauseWatchService.kt`, `advanced/PaymentPauseFragment.kt`, `Settings.paymentPauseApps` |
| Widgets: "Locks" (running locks after unlock) and "Unlocked" (running unlocks; a frozen unlock shows "paused" with its time left) list app groups, keyword groups and Reels blocker apps with live Chronometer countdowns (up to 3 rows). Each blocker in the service process publishes its timers to `WidgetTimers`, which merges them, sends `TimersWidgetProvider.ACTION_TIMERS_CHANGED` only when they change and redraws when the next one ends; a widget asks for everything with `AppBlocker.INTENT_ACTION_REFRESH_WIDGETS` when added. "Payment pause" widget opens `PaymentPauseChooserActivity` to pick a payment app | `blockers/WidgetTimers.kt`, `ui/widgets/TimersWidgetProvider.kt` (+ `LocksWidgetProvider`, `UnlocksWidgetProvider`), `ui/widgets/PaymentPauseWidgetProvider.kt`, `ui/activity/PaymentPauseChooserActivity.kt` |
| Permissions screen: every permission Boundary uses with on/off and a Fix button (accessibility, overlay, notifications, battery, device admin, adb secure settings, usage access, notification access, location), rechecked on resume | `advanced/PermissionsFragment.kt`, `res/layout/item_permission_status.xml` |
| Keyword groups have their own "Block unsupported browsers" switch: while the group is active, browsers Boundary cannot read are sent home. It is part of the group, so turning it off is gated (`RestrictionComparator.keywordGroup`) and covered by Home lock. The old blocker-wide menu option still works and cannot turn a group's switch off | `KeywordGroup.blockUnsupportedBrowsers`, `KeywordBlocker.isGroupBlockingUnsupportedBrowsers`, `CreateKeywordGroupFragment` |
| Emergency off: one button that turns every restriction off at once with no delay and no location check (all groups off, Reels blocker off, tamper protection off and its device admin removed, change delay and its tamper gate off, pending changes applied first, Away mode off), then refuses until `emergencyOffWaitDays` have passed since `emergencyOffLastUsedAtMs`. The owner picks the wait (1 to 365 days) in the confirm dialog at the moment of use, and it cannot be changed until it has passed (owner's request; an editable gated setting existed briefly). The `EMERGENCY_OFF_WAIT` gated field and its comparator rule stay only so any stored pending change still loads | `DataStoreManager.useEmergencyOff`, `advanced/EmergencyOffFragment.kt`, `Settings.emergencyOffWaitDays` |
| App websites follow their app: while a browser shows an app's website (instagram.com, facebook.com, x.com, tiktok.com, reddit.com, snapchat.com, threads.net, youtube.com), `AppBlocker` treats the browser as that app, with the same wait screen, unlock, lock and pill. Proceed reopens the browser (`launch_package`). Unlock time counts while the site is open. Only for apps in an active app group; keyword groups are separate. Other sites can be typed into an app group's "Websites" box (`AppGroup.websites`, cleaned by `AppWebsites.normalize`); they join the group as `site:<domain>` keys in `groupPackagesById` and `blockedAppsList`, so unlocks, locks and each open work like an app (skipped for usage stats and closing apps). A group may have only websites. Removing a website is weaker (`RestrictionComparator.appGroup`), so it is gated and covered by Home lock The editor lists the chosen apps' built in sites as "included" rows without a remove button. A typed site's pill shows a globe and the site name (`AppBlocker.showPill`) | `hardcoded/AppWebsites.kt`, `AppBlocker.onWebsiteObserved`, `appShownInBrowser`, `CreateAppGroupFragment.addWebsite` |
| Backup: the whole `Settings` is saved (so new features are included automatically), plus a `blocklists` section with each blocklist's id, name and link, which `BackupManager.restore` re-adds and downloads again. Away mode is reset on restore; Emergency off timing is kept so a reinstall cannot reset its wait | `utils/SettingsBackupCodec.kt` (`decodeFull`), `utils/BackupManager.kt` |
| Eye breaks (20 20 20 rule) with a live countdown on the Reducers card | `anti_stimulants/EyeBreakReminder.kt` (in the service, broadcasts its schedule), `ReducersFragment.setupEyeBreakCard` |

The donation popup (`DonationPrompt`, `dialog_donation`) and the Info tab's About the developer, Donate, Join the focus pack (Discord) and Follow cards were removed at the owner's request in 1.3.2, because the fork has moved far from Curbox. Do not bring them back. The browser extension card and the Curbox docs link were removed too. Credit to Curbox's author stays in LICENSE, CITATION and the Readme, as GPL requires.

Behavior notes:
- Blocklists are blocked exactly like typed keywords.
- The "Block adult sites" description shows the list's real size from `BlocklistStore.Source.domainCount` (`CreateKeywordGroupFragment.showAdultListSize`), refreshed on open, on resume and after a download, because the list grows with each update. Do not write a fixed number into that text.
- Blocklist files live in `filesDir/blocklists/`, not in Room or the settings JSON. Deleting a list is refused while any group uses it, so the settings change delay cannot be bypassed.
- Eye breaks are not gated by the settings change delay, because they are a reminder, not a restriction. A screen off of 20 seconds or more resets the count.

## Tried and removed (do not re-add without asking)

- **Reading tab** (2.2.0 to 2.4.0, removed after 2.4.0 at the owner's request, with all its code): chapter tracking from browser addresses (`ChapterParser`, `ReadingTracker`), Kenmei CSV import, status lists, the new chapter check (`ReleaseCheckJob` ids 7720/7721, MangaDex API and series page reading), duplicate merging by MangaDex names, To review, covers, reading stats and the "read today" line on keyword wait screens. `AppBlockerService.releaseRemovedFeatures` cancels the old job ids. It also deletes the `reading_db` database and `filesDir/covers` (owner's choice, so no reading data is left); the `isReadingTrackerEnabled` and `releaseCheckHours` settings fields were dropped (Gson ignores them in stored JSON). The general fixes made along the way stay: the bottom bar hides while the keyboard is open (`FragmentActivity.showsTabs`).
- **Active Hours (schedule) and Usage Limit** (removed after 1.14.0 at the owner's request) from app groups, keyword groups and the Short form content blocker ("Configure Schedule" there). App and keyword groups now block all day from the first open; only an unlock lets them through. Short videos are blocked all day, except for the optional daily reel count allowance (`ReelBlockerConfig.reelCount`); 0 or no count means blocked all day, which `RestrictionComparator.reelBlocker` treats as strictest. Gone: `AppGroup.config`/`KeywordGroup.config` (`ScheduledUsageConfig`, `AppTimeConfig`, `AppUsageConfig`, `TimeInterval`), the reel `schedule` and `usage`, the legacy `blockingType`/`setting`/`linkedTimeGroupId` fields and their migrations, the schedule and usage editors, schedule conflict dialogs, "time left" rows in the group lists, website time counting toward limits, and the onboarding daily limit chips. Stored JSON with those fields still loads (Gson ignores unknown fields), and old backups still restore.
- **`isAccessibilityTool="true"`** (1.0.5): did not help the owner's banking apps. Removed in 1.0.7.
- **Banking pause** (1.0.6): turned the service off while a banking app was open. The owner found the adb grant and notification fallback too much. Removed in 1.0.7. Re-added in a different form as Payment pause (see Features) at the owner's request, with the adb grant done once from their Mac.
- **Auto DND and grayscale** (stopped in 1.8.0, code deleted in 1.9.0 at the owner's request). Only `GrayscaleControl` (check and turn off) and `DndHelper` remain, for `AppBlockerService.releaseRemovedFeatures`, which on service start turns off Do Not Disturb only if Boundary turned it on, and grayscale only if a grayscale group was active. Their settings fields (`autoDndGroups`, `grayscaleGroups`) and gated field names stay so stored settings and pending changes still load.
- **Focus mode** (tab removed in 1.8.0, code deleted in 1.9.0 at the owner's request): the Focus tab, `FocusModeBlocker`, NFC focus tags (`NfcFocusHandler`, `NfcTriggerActivity`) and the launcher shortcut are gone. NFC *unlock* (`NfcUnlockUtils`) stays. Focus settings fields and Room focus stats stay (changing Room wipes data). The "Focus goal" unlock requirement can no longer be met, so its switch only shows for groups that already use it, letting them turn it off.
- **Leftover settings and texts** (cleaned in 1.12.0): the `manualFocusGroups`, `activeManualFocusGroupId`, `activePomodoroState`, `autoDndGroups` and `grayscaleGroups` fields, their model classes, the `AUTO_DND_GROUPS` and `GRAYSCALE_GROUPS` gated fields (a migration drops pending changes for unknown fields), `GrayscaleControl`, 16 dead layouts and about 290 unused texts in every language. Room's focus stats table stays (changing Room wipes data). The Usage page's "More from Nethical" (Langowage) card and its empty state's docs, GitHub and Discord buttons were removed too.
- **Summary tab** (added in 1.12.0, removed after it at the owner's request): `SummaryFragment`, its layouts and icon, `DailyCounters` (unlock and lock counts) and the `lockAfterMs` argument of `UnlockLocks.unlockEnded`. The old `daily_counters` preferences file is simply left unused.
- **Shizuku** (deleted in 1.9.0; the owner never wants it): the library, its exported provider, `ShizukuRunner`, `AppSuspendHelper`, the onboarding "grant all", the force stop on the wait screen, doze tweaks and "Set device owner". Self repair in Service protection now uses the adb granted WRITE_SECURE_SETTINGS through `PaymentPause.setServiceEnabled` (skipped during a payment pause). `ServiceProtectionConfig.selfHealWithShizuku` keeps its name so stored settings load.
- **Boundary API** (deleted in 1.8.0): another app could control Boundary after approval. The owner did not use it and wanted no extra door.
- **Reading browsers built into other apps** (written after 1.13.0, removed before 1.14.0 at the owner's request because it was uncertain): looked for a large WebView with an address only text above it in non browser apps (Telegram, Instagram). Some in-app browsers show only the page title, so it could not be relied on. Links opened there are not checked.
- **Internet blocking** (discussed, not built): only a local VPN can cut one app's internet without root; Private DNS was the alternative. The owner dropped the idea after 1.8.0.
- **Full package rename** (1.0.3): reverted, and the release deleted. The next version jumped to 1.0.4 (versionCode 5).

## Pitfalls learned the hard way

- `Curbox.kt` and `blockers/uihider/script/Lexer.kt` contain a literal NUL character. The Edit tool refuses them and `git grep -I` skips them as binary. Edit them with Python on bytes.
- When copying an XML block that nests the same tag (for example a `MaterialCardView` inside a `MaterialCardView`), find the closing tag with its newline and indentation, or the copy is cut short. Validate changed XML with `python3 -c "import xml.dom.minidom; xml.dom.minidom.parse('<file>')"` before pushing.
- `ReducersFragment` builds its cards in `onCreateView`, where `viewLifecycleOwner` does not exist yet. Put lifecycle work in `onViewCreated`.
- A top level package named `app.*` is shadowed by any property called `app` (as in `BackupManager`), so fully qualified names break. Use imports.
- A string kept unchanged on purpose must keep its resource name too, or the code referencing it breaks.
- Kotlin raw strings (`"""`) do not process escapes. `ScriptLanguageTest` had `\\n` inside one and failed until fixed. Curbox's own CI never ran the unit tests.
- Boundary's own overlays (reel counter, pill) send accessibility events with Boundary's package while another app is in front. Treat an own package event as "Boundary in front" only when `rootInActiveWindow` is Boundary, or app timers stop (fixed in 1.3.1).
- Blockers read settings from a DataStore flow that emits a moment after the service starts, so their config starts as the default (off). `AntiUninstallBlocker` therefore treats an active device admin as "protected" until its config has loaded, and is set up first in `onServiceConnected` (fixed after the owner could reach uninstall right after a reboot).
- `AppBlocker` also runs its check on `TYPE_WINDOW_CONTENT_CHANGED`, which the keyboard emits. Logic about "which app is in front" must use window state changes only and skip the keyboard and system UI.
- `WebsiteUsageTracker.onEvent` must not forget the current site on events from the keyboard, system UI or Boundary's overlays while the browser is still the active window. It did until 2.0.0, which let app websites through and paused their unlocks; it now leaves only when `rootInActiveWindow` is another app.
- Chrome hides its address bar while scrolling, so `WebsiteUsageTracker` keeps the last page per browser (`lastSiteByBrowser`) and assumes it again when the browser is back in front with no address bar visible (`resumeLastSite`). Events from `com.android.systemui` (notification panel, lock screen) are not treated as leaving the page; screen off still pauses through the blockers' own receivers. Before this, keyword and app website unlocks stayed paused until the next page load.
- The unlock settings screen has no sliders (removed after 2.0.0 at the owner's request); number boxes carry their minimum in `NumericInput.minimumValue`. The QR/NFC unlock time pop up (`WarningUnlockTimingDialog`) still uses one, as its only control.
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
| 1.1.0 | 12 | X (Twitter) full screen videos in the Reels blocker and counter |
| 1.1.1 | 13 | X removed from the reel counter (count was unreliable); still blocked |
| 1.2.0 | 14 | Wait screen fits long messages; one box per message instead of comma splitting |
| 1.3.0 | 15 | Wait in seconds/minutes/hours, Lock after unlock ends, Home lock with turn off all when away |
| 1.3.1 | 16 | Own overlays (reel counter, pill) no longer stop the app usage countdown or end usage sessions |
| 1.3.2 | 17 | Donation popup removed; Info tab keeps only version, tracking, backup, crash logs, language |
| 1.4.0 | 18 | Tamper protection "Away from home" method |
| 1.5.0 | 19 | Payment pause (5 min, adb granted), ends early on blocked apps, browsers and Settings |
| 1.5.1 | 20 | Tamper protection guards right after a reboot (before settings load; set up first) |
| 1.5.2 | 21 | Blocked apps can no longer be payment pause apps (picker, start and watcher) |
| 1.6.0 | 22 | Locks close app group apps and snooze their notifications (switchable); payment pause starts by itself when a payment app opens |
| 1.7.0 | 23 | Locks widget (live countdowns) and Payment pause widget |
| 1.8.0 | 24 | Per keyword group "Block unsupported browsers"; removed Auto DND, grayscale, Focus tab, Boundary API |
| 1.9.0 | 25 | Away mode (keywords stay, home check on blocked sites), away pass skips all gates, Unlocked widget, Permissions screen, Focus/DND/grayscale/Shizuku code deleted |
| 1.10.0 | 26 | Emergency off (once per chosen number of days); away pass no longer skips the delay or tamper protection |
| 1.11.0 | 27 | No away pass; Away mode only pauses and ends when a blocked app, short video or site opens at home; locked groups loosen only via Emergency off; tamper "Away from home" removed; Emergency off asks for its wait when used |
| 1.12.0 | 28 | Summary tab with unlock and lock counts; backups keep blocklist links; Nethical promo removed; leftover settings, layouts and texts cleaned |
| 1.13.0 | 29 | App websites follow their app's group (built in list plus a Websites box per app group); Summary tab removed |
| 1.14.0 | 30 | Website time counts toward app group limits; editor lists the apps' built in sites; globe pill for typed sites |
| 2.0.0 | 31 | Active Hours and Usage Limit removed (groups block all day; reels blocked all day except a count allowance); per group pause unlock switch and keep leftover time, fixed leftover reset; Websites list styled like keywords; real adult list size |
| 2.0.1 | 32 | App websites and keyword pages no longer forgotten on keyboard, system UI or overlay events; sliders removed from the unlock settings screen |
| 2.1.0 | 33 | Live Updates status bar chip (Android 16, untested on device); one unlock notification for all blockers; keyword groups pause unlock time when their pages are not open |
| 2.1.1 | 34 | Website unlocks resume on return with the address bar hidden (last page per browser); notification panel no longer pauses website time |
| 2.1.2 | 35 | Dropped leftover unlock time starts the lock after unlock; leftover sync code removed |
| 2.2.0 | 36 | Reading tab: chapter tracking from browser addresses, status lists, search, Kenmei CSV import; own reading database |
| 2.3.0 | 37 | New chapter check (MangaDex API and the reading site's series page; next unread link; New filter); duplicates merged by MangaDex names, similar names reviewed; To review list; merge search; bottom bar hides with the keyboard |
| 2.4.0 | 38 | Reading stats (today, last 7 days, top series), MangaDex covers, time read today on keyword wait screens |
| 2.5.0 | 39 | Reading tab and all its features removed; its data deleted on update |

Versions up to 1.0.10 only bumped the patch number, even for features. From 1.1.0 on, releases follow semantic versioning (see Releasing).

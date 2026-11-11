# Installing the app on the truck via Play Console internal testing

**What works on your account (2026-11-03):** the **regular** internal-testing track, the same way Vince Files reached the truck. The app is **`com.vincel.silveradoev`** and declares `android.hardware.type.automotive` as *not required*.

The first attempt, `com.vincel.silveradostats` on the dedicated *Android Automotive OS* track, stayed "Inactive". That choice can't be undone, so it's abandoned. You can delete that draft app in Play Console if you like.

Internal testing only, never production: the app's while-driving screens go against Google's car-app guidelines, which are only enforced at review (`PLAN.md`, Limits and decisions).

## Updating (the normal case: the app already exists)
1. Raise `versionCode` and `versionName` in `android/app/build.gradle.kts`. Every new bundle needs a higher `versionCode`.
2. From the repo root, run `android/scripts/release-bundle.sh`. It runs the unit tests, builds and signs the bundle, prints the version, and writes `release/silverado-ev-v<code>.aab`. It refuses to run if that file already exists.
3. *Test and release › Testing › Internal testing* (the normal one) › *Create new release*. Upload the newest `release/silverado-ev-v<code>.aab`. If Claude uploads it through the browser, its `file_upload` tool is limited to 10 MB, so check the size first (v7 was 9.3 MB). An upload by hand doesn't have that limit. Name it, then **Next › Save and publish**. If **Publishing overview** shows pending changes, send or publish them there too.
4. The truck's Play Store updates it, or use Play Store › menu › *My apps* › *Updates*.

*Policy › App content* lists 11 declarations as needing attention, among them Foreground service permissions since 0.10.0. None of them has blocked an internal-testing release, and they have been left unfilled. Play's review page shows two warnings on every upload, about deobfuscation and native debug files. They are optional, and neither affects how the app runs.

The upload key is `~/.android-keys/silveradostats-upload.jks`, and all builds are signed with it. **Back it up.** `release/` is git-ignored, so the tag doesn't hold the bundles.

## First install (done; steps kept for a new setup)
1. **Create app:** Play Console › *Create app*, name "Silverado Stats", App, Free.
   - Don't add the Android Automotive OS form factor.
   - Use the exact package from the bundle: Play reads it from the upload.
2. **Testers:** *Test and release › Testing › Internal testing* (the normal one) › *Testers*. Tick the same email list as Vince Files, and **Save**.
3. **Release:** *Create new release*.
   - Upload the newest `release/silverado-ev-v<code>.aab`.
   - Accept Play App Signing if asked.
   - Name it, then **Next › Save and publish**. If **Publishing overview** shows pending changes, send or publish them there too.
   - Play may ask for some *App content* declarations first (privacy policy, data safety, ads, target audience). Answer them truthfully: no ads, no data collected or shared off-device, adults.
4. **Opt in:** *Testers* tab › copy **Join on the web**. It works once a release is rolled out. Open it in your browser signed in as the truck's Google account, then **Accept invite**.
5. **Install:** follow "download it on Google Play", then **Install** › choose the truck. The truck must be on and online. It shows on the truck as "com.vincel.silveradoev (unreviewed)", like Vince Files.
6. **Permissions on the truck:** in Park, open the app's **Diagnostics** tab and tap **Request runtime permissions** in the Permissions section, or go to Settings › Apps › the app › Permissions. Out of Park that tab shows only "Available in Park".

## Things that are normal on GM
- **Truck online.** The truck needs internet for Play installs. OnStar/Connected Services data is the known-good path. Installs over Wi-Fi with the head unit as a client are unverified on GM, and some GM EVs reportedly can't join Wi-Fi at all.
- **Install delay.** An install can take minutes to a couple of hours to appear. Check Play Store › menu › *My apps* on the truck.
- **Stuck waiting for Wi-Fi.** On the truck's Play Store, set *App downloads* and *Auto-update apps* to "Over any network".
- **No Play listing search** for internal apps. Use the join link.
- **After a GM OTA (Android update), re-check** permissions and Bluetooth settings. Owners report resets.

Sources: https://developer.android.com/training/cars/distribute and https://github.com/mossyhub/openautolink/blob/main/docs/install-gm.md. Background is in `docs/research/`; its [README](research/README.md) says which of its recommendations no longer apply, including the dedicated track.

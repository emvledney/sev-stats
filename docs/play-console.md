# Installing the app on the truck via Play Console internal testing

**What works on your account (2026-11-03):** the **regular** internal-testing track, the same way Vince Files reached the truck. The app is **`com.vincel.silveradoev`** and declares `android.hardware.type.automotive` as *not required*.

The first attempt, `com.vincel.silveradostats` on the dedicated *Android Automotive OS* track, stayed "Inactive". That choice can't be undone, so it's abandoned. You can delete that draft app in Play Console if you like.

## Steps for com.vincel.silveradoev
1. **Create app:** Play Console › *Create app*, name "Silverado Stats", App, Free.
   - Don't add the Android Automotive OS form factor.
   - Use the exact package from the bundle: Play reads it from the upload.
2. **Testers:** *Test and release › Testing › Internal testing* (the normal one) › *Testers*. Tick the same email list as Vince Files, and **Save**.
3. **Release:** *Create new release*.
   - Upload `release/silverado-ev-v3.aab` (or the newest `release/*.aab`).
   - Accept Play App Signing if asked.
   - Name it, then **Next › Save and publish**. If **Publishing overview** shows pending changes, send or publish them there too.
4. **Opt in:** *Testers* tab › copy **Join on the web**. Open it in your browser signed in as the truck's Google account, then **Accept invite**.
5. **Install:** follow "download it on Google Play", then **Install** › choose the truck. The truck must be on and online. It shows on the truck as "com.vincel.silveradoev (unreviewed)", like Vince Files.
6. **Permissions on the truck:** open the app and tap **Request** in the Permissions section, or go to Settings › Apps › the app › Permissions.

## Updating
Every new bundle needs a higher `versionCode`. Upload it to the same internal track. The truck's Play Store updates it, or use Play Store › menu › *My apps* › *Updates*.

The upload key is `~/.android-keys/silveradostats-upload.jks`, and all builds are signed with it. **Back it up.**

---

## Reference: the original Automotive-track instructions (not used)
# Installing the app on the truck via Play Console internal testing (Phase 3)

This is the only sanctioned way to get your own app onto a GM Google built-in head unit. ADB is not available on production GM units. Other owners use this exact path on 2024–2025 Silverado EV, Sierra EV, Blazer EV and Equinox EV, including Android 14.

Sources: https://developer.android.com/training/cars/distribute and https://github.com/mossyhub/openautolink/blob/main/docs/install-gm.md. Details are in `docs/research/play-distribution*.md` and `docs/research/gm-platform*.md`.

## Before you start
- **Developer account verified.** New personal accounts need identity and Android-device verification before publishing anything.
- **Truck Play account.** Know the Google account signed into the truck's Play Store: Play Store on the truck → profile icon.
- **Truck online.** The truck needs internet for Play installs. OnStar/Connected Services data is the known-good path. Installs over Wi-Fi with the head unit as a client are unverified on GM, and some GM EVs reportedly can't join Wi-Fi at all.
- **Package name: `com.vincel.silveradostats`** (chosen 2026-11-03; set in `android/gradle.properties`). It's permanent, and because the AAOS dedicated track can't be undone, this app is AAOS-only.
- **Upload key.** I generate an upload keystore during the build. Back it up (password manager plus offline copy). Losing it means a Google support request to reset it.

## Steps

0. **Check the device catalog first.** Play Console › *Device catalog*: filter the form factor to *Car* and look for the Silverado EV / GM "VCU" head unit.
   - The next step is irreversible for the package. If the unit isn't listed, or you're unsure, tell me. We'll spike first under a throwaway package name, or use the regular mobile track with `automotive` required=false.
1. **Create the app.** Play Console → *Create app*.
   - Name: "Silverado Stats" (it stays private on internal testing).
   - App, Free. Accept the declarations.
2. **Add the Automotive form factor.** *Test and release* → *Advanced settings* → *Form factors* → **+ Add form factor** → *Android Automotive OS* → *Manage*.
   - Choose **"Use a dedicated release track for Android Automotive OS"**. This is irreversible.
   - Agree to the review policy and save.
   - Play may ask for AAOS screenshots to finish the opt-in. I'll capture them from the emulator: 2 portrait 800×1280 and 2 landscape. Google's page says 1024×786, likely a typo for 1024×768; use whatever Play accepts.
   - Internal testing gets **no** car-quality review; closed testing gets a non-blocking one; open and production get a blocking one.
3. **Add testers.** *Test and release* → *Testing* → **Internal testing**. Use the form-factor selector at the top to choose **Android Automotive OS**.
   - *Testers* tab → tick the same email list you already use for Vince Files, or create one with the truck's Play account → save.
4. **Upload the release.** *Create new release*.
   - Accept Play App Signing on the first release.
   - Upload `android/app/build/outputs/bundle/release/app-release.aab` (I'll produce this).
   - Add release notes → *Next* → *Save and publish* / *Start rollout*.
   - Back on the *Testers* tab, copy the **"Join on the web"** link. It works once a release is rolled out.
   - Play may ask for some *App content* declarations first (privacy policy, data safety, ads, target audience). Answer them truthfully: no ads, no data collected or shared off-device, adults.
5. **Opt in.** Open the "Join on the web" link in a desktop browser signed in as the truck's Google account → **Become a tester**.
6. **Install to the truck.**
   - From the opt-in page, open the app's Play page (internal apps aren't searchable).
   - **Install** → choose the truck in the device list. Have the truck on and online when you do this.
   - It can take minutes to a couple of hours to appear. On the truck you can also check Play Store › menu › *My apps*.
   - On the truck's Play Store, set *App downloads* and *Auto-update apps* to "Over any network" if installs sit waiting for Wi-Fi.
7. **Grant permissions on the truck.** Open the app. Then Settings → Apps → Silverado Stats → Permissions, and allow the car-energy and car-speed permissions. The names vary slightly by build.

## Updating
- Every new build needs a **higher `versionCode`**, the same package name and the same upload key.
- Upload to the same internal track. The truck's Play Store updates it, or force it via Play Store › menu › *My apps* › *Updates*.

## Things that are normal on GM
- **No Play listing search** for internal apps. Use the link.
- **Detailed screens disappear when you shift out of Park.** Android Automotive does this to anything not marked distraction-optimized. The glance screen stays.
- **After a GM OTA (Android update), re-check** permissions and Bluetooth/Wi-Fi settings. Owners report resets.

> **Superseded in part, see [README](README.md):** the app is on the regular internal track, with `android.hardware.type.automotive` and the templates host declared `required=false`, not as listed in T4.

### T1. Play track type vs car-app form-factor review (source: https://developer.android.com/training/cars/distribute, fetched 2026-11-03)
| Track type | Form factor review | Notes |
|---|---|---|
| Internal app sharing | None | Android Auto only |
| Internal testing | None | ≤100 testers by email list; builds arrive within minutes; not searchable (https://support.google.com/googleplay/android-developer/answer/9845334) |
| Closed testing | Non-blocking | You are told about non-compliance but the build is still approved |
| Open testing | Blocking | |
| Production | Blocking | New personal accounts first need a closed test with 12+ testers for 14 days (https://support.google.com/googleplay/android-developer/answer/14151465) |

### T2. Play Console paths and identifiers
| Item | Value | Source |
|---|---|---|
| Form-factor opt-in | Test and release > Advanced settings > Form factors > + Add form factor > Android Automotive OS > Manage > Releases: dedicated track > Save (AAOS choice cannot be undone) | https://support.google.com/googleplay/android-developer/answer/13295490 |
| Internal testers | Test and release > Testing > Internal testing > Testers tab > create email list > copy opt-in link > Save changes | https://support.google.com/googleplay/android-developer/answer/9845334 |
| Track allowed for templated/media AAOS apps | 'Android Automotive OS only' (dedicated); parked apps may use mobile or dedicated | https://developer.android.com/training/cars/distribute |
| Publishing API track names | automotive:qa (internal), automotive:beta (open), automotive:production; closed = automotive:<name> | https://developers.google.com/android-publisher/tracks |
| AAOS screenshots (when required) | ≥2 portrait at 800x1280 and ≥2 landscape at 1024x786 (as the docs state), taken on a generic emulator image, no OEM branding | https://developer.android.com/training/cars/distribute |
| Unpublish | Test and release > Advanced settings > App Availability > Unpublished | https://support.google.com/googleplay/android-developer/answer/13295490 |

### T3. Supported car app categories (https://developer.android.com/training/cars, Nov 2026)
| Category | AAOS? | Usage | Tracks | Car App Library category string |
|---|---|---|---|---|
| Media (audio) | Yes | Driving + parked | All (templated media: EAP, internal/closed only) | androidx.car.app.category.MEDIA (templated) |
| Communication (messaging/calling) | No (Android Auto only) | — | — | MESSAGING / CALLING |
| Navigation | Yes | Driving + parked | All | androidx.car.app.category.NAVIGATION |
| POI (parking, charging, fuel) | Yes | Driving + parked | All | androidx.car.app.category.POI (CHARGING/PARKING deprecated since 1.3) |
| IoT | Yes | Driving + parked | All | androidx.car.app.category.IOT |
| Weather | Yes | Driving + parked | All | androidx.car.app.category.WEATHER |
| Video | Yes | Primarily parked | All | n/a (Views/Compose) |
| Games | Yes | Only parked (GA since Sep 2026) | All | n/a |
| Browsers (labs) | Yes | Only parked | Internal testing only | n/a |
| Vehicle stats / dashboard | No such category | — | Internal testing only (no review) | Closest: IOT (Car Stats Viewer uses it) or POI |

### T4. Manifest essentials for a templated AAOS app (https://developer.android.com/training/cars/apps/automotive-os ; https://developer.android.com/training/cars/platforms/automotive-os)
| Element | Value |
|---|---|
| uses-feature | android.hardware.type.automotive required="true" (templated apps: true or unset) |
| uses-feature | android.software.car.templates_host required="true" |
| uses-feature (must not be required) | android.hardware.wifi, android.hardware.screen.portrait, android.hardware.screen.landscape, android.hardware.camera → required="false" |
| application meta-data | com.android.automotive → @xml/automotive_app_desc containing `<automotiveApp><uses name="template"/></automotiveApp>` |
| application meta-data | androidx.car.app.minCarApiLevel (e.g. 6, as Car Stats Viewer uses) |
| activity | androidx.car.app.activity.CarAppActivity, exported=true, theme @android:style/Theme.DeviceDefault.NoActionBar, launchMode singleTask, MAIN/LAUNCHER, meta-data distractionOptimized=true |
| service | CarAppService with intent-filter action androidx.car.app.CarAppService + category androidx.car.app.category.IOT |
| Surface drawing (optional) | uses-permission androidx.car.app.ACCESS_SURFACE + androidx.car.app.MAP_TEMPLATES (MapWithContentTemplate) or androidx.car.app.NAVIGATION_TEMPLATES (NavigationTemplate) — https://developer.android.com/training/cars/apps/library/draw-maps |
| Gradle | androidx.car.app:app + androidx.car.app:app-automotive (stable 1.7.0; 1.8.0-rc01; 1.9.0-alpha03, Oct 7 2026); minSdk 29; useLibrary("android.car") for CarPropertyManager |

### T5. Template limits relevant to a live dashboard
| Limit | Value | Source |
|---|---|---|
| Templates per task | 5 (the last must be Navigation/Pane/Message/MediaPlayback/SignIn/LongMessage); exceeding it → error and app closed | https://developer.android.com/training/cars/apps/library/template-restrictions |
| Pane/List refresh exemption | Same template type and title, same row count and row titles (values may change in row text) | https://developer.android.com/reference/androidx/car/app/model/PaneTemplate ; .../ListTemplate |
| Default content limits (fallback) | list 6, grid 6, place_list 6, route_list 3, pane 4 | https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/car/app/app/src/main/res/values/integers.xml |
| Pane rows | ≤ CONTENT_LIMIT_TYPE_PANE (constant = 4); ≤2 text lines per row; no Toggle or OnClick | https://developer.android.com/reference/androidx/car/app/model/PaneTemplate.Builder |
| Pane actions | ≤2 | same |
| Pane image | 1 image, 480x480 dp bounding box (Car API 4) | https://developer.android.com/reference/androidx/car/app/model/Pane.Builder |
| Host update throttle | Documented as throttled; observed ~1 s before and ~7–8 s after the June 2024 host update (Polestar) | https://developer.android.com/reference/androidx/car/app/Screen ; https://github.com/Ixam97/CarStatsViewer/issues/225 |
| App-driven refresh query | ConstraintManager.isAppDrivenRefreshEnabled() (Car API 6) | https://developer.android.com/reference/androidx/car/app/constraints/ConstraintManager |

### T6. Car Stats Viewer: vehicle properties and permissions it uses (code at master, pushed 2026-10-31; IDs from AOSP VehicleProperty.aidl)
| Property | ID (hex / dec) | Type | Permission declared |
|---|---|---|---|
| PERF_VEHICLE_SPEED | 0x11600207 / 291504647 | FLOAT | android.car.permission.CAR_SPEED |
| EV_BATTERY_INSTANTANEOUS_CHARGE_RATE | 0x1160030C / 291504908 | FLOAT (mW) | android.car.permission.CAR_ENERGY |
| EV_BATTERY_LEVEL | 0x11600309 / 291504905 | FLOAT (Wh) | CAR_ENERGY |
| EV_BATTERY_AVERAGE_TEMPERATURE | 0x1160030E / 291504910 | FLOAT (°C) | CAR_ENERGY (inferred) |
| EV_CHARGE_PORT_CONNECTED | 0x1120030B / 287310603 | BOOLEAN | android.car.permission.CAR_ENERGY_PORTS |
| GEAR_SELECTION | 0x11400400 / 289408000 | INT32 | android.car.permission.CAR_POWERTRAIN |
| IGNITION_STATE | 0x11400409 / 289408009 | INT32 | CAR_POWERTRAIN |
| ENV_OUTSIDE_TEMPERATURE | 0x11600703 / 291505923 | FLOAT | android.car.permission.CAR_EXTERIOR_ENVIRONMENT |
| INFO_EV_BATTERY_CAPACITY | 0x11600106 / 291504390 | FLOAT (Wh) | android.car.permission.CAR_INFO |
| INFO_MAKE / INFO_MODEL | 0x11100101 / 0x11100102 | STRING | CAR_INFO |
| INFO_MODEL_YEAR | 0x11400103 / 289407235 | INT32 | CAR_INFO |
| DISTANCE_DISPLAY_UNITS | 0x11400600 / 289408512 | INT32 | android.car.permission.READ_CAR_DISPLAY_UNITS |
Sources: https://raw.githubusercontent.com/Ixam97/CarStatsViewer/master/automotive/src/main/java/com/ixam97/carStatsViewer/carPropertiesClient/CarProperties.kt ; https://raw.githubusercontent.com/Ixam97/CarStatsViewer/master/automotive/src/main/AndroidManifest.xml ; https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/automotive/vehicle/aidl_property/android/hardware/automotive/vehicle/VehicleProperty.aidl

### T7. Precedent apps
| App | Distribution | Car category / UI | GM evidence | Source (date) |
|---|---|---|---|---|
| Car Stats Viewer – Legacy | Play internal tracks (≤100 each), many forks | Custom activities tagged DO; runs while driving on Polestar/Volvo | None (0 GitHub issue hits) | https://github.com/Ixam97/CarStatsViewer (2026-10) |
| Car Stats Viewer – Play Edition | Closed alpha since Jun 2024, 23 regions incl. USA | IOT; TabTemplate/PaneTemplate refreshed ≤1 Hz; dev flavor draws on a Surface via NavigationTemplate | None | https://github.com/Ixam97/CarStatsViewer/issues/225 |
| Aximote: Car Data & Trip Log (com.carlytics.apps) | Public Play, car form factor; updated 2026-10-30 | Unknown category; 'In-Car App for real-time feedback while driving' | Listing names Chevrolet, Cadillac, GMC | https://play.google.com/store/apps/details?id=com.carlytics.apps |
| ABRP (AAOS) | Public Play | Navigation (templated) | Installed and used on a 2025 GMC Sierra EV (2025) | https://abrp.featurebase.app/p/unusable-on-aaos |
| Homey 'Car Dashboard' | Closed testing; passed review 2026-09-02 | IoT-style tile grid | Claims GM brands supported; no GM user report | https://community.homey.app/t/158804 |
| Home Assistant companion (AAOS) | Public Play or OEM store | IoT templates (entities, sensors) | Not verified | https://companion.home-assistant.io/docs/android-auto/ |

### T8. Play account and build requirements (2026)
| Requirement | Applies to internal testing? | Source |
|---|---|---|
| Closed test with 12+ testers for 14 consecutive days (personal accounts created after 2023-11-13) | No, production only | https://support.google.com/googleplay/android-developer/answer/14151465 |
| Verify a real Android 10+ non-rooted phone with the Play Console app (new personal accounts) | Required before publishing; internal testing not addressed, so do it | https://support.google.com/googleplay/android-developer/answer/14316361 |
| Target API: AAOS ≥ API 35 from 2026-08-31 (mobile ≥ 36) | Page silent on internal testing; target 36 to be safe | https://support.google.com/googleplay/android-developer/answer/11926878 |
| AAB + Play App Signing for new apps | Yes (standing Play rule, recalled rather than re-fetched) | https://developer.android.com/training/cars/distribute |
| Data safety form | Active internal-test-only apps are exempt | https://support.google.com/googleplay/android-developer/answer/9845334 |
### T1. GM Ultium/Global-B head-unit platform facts (verified unless marked *inference*)
| Item | Value | Source (date) |
|---|---|---|
| Silverado EV infotainment ECU | "VCU (Virtual Cockpit Unit)"; RPO IVD / IVE | NHTSA GM OTA 662.30 (PDF 2026-07-31) https://static.nhtsa.gov/odi/tsbs/2026/MC-11035387-0001.pdf ; TSB 23-NA-227 (Apr 2024) https://static.nhtsa.gov/odi/tsbs/2024/MC-10252977-0001.pdf |
| Shared radio SW (2024 Blazer EV + Silverado EV) | W27E-172.5.1-M169-SQBR4-126.1 | 23-NA-227 |
| Earlier Silverado EV SW | W38E-170.5.1-M166-SQBR3-187.2 (RPO IVD) | 23-NA-160 (Jan 2024 / rev Apr 2025) https://static.nhtsa.gov/odi/tsbs/2024/MC-10250255-0001.pdf |
| Lyriq SW (same family) | 170.5.1-M166-SQBR3-187.1 (Sep 2023); W27E-172.5.1-M169-SQBR4-126.3 (Mar 2024) | https://static.nhtsa.gov/odi/tsbs/2023/MC-10244212-0001.pdf ; https://static.nhtsa.gov/odi/tsbs/2024/MC-10251375-0001.pdf |
| SQBR6 (Silverado/Sierra EV) | version 86277880; dealer-installed (~hours); 2026 MY ships with it | ev.com 2025-09-13 https://ev.com/news/gm-rolls-out-major-software-update-for-silverado-ev-and-sierra-ev-pickups |
| Android 14 build tag | UQBM (e.g. 2024 Equinox EV "W53E-180.4.2-UQBM-286.5", A11 radio) | XDA OAL thread 2026-10-01 https://xdaforums.com/t/open-source-openautolink-android-auto-usb-apple-carplay-for-aaos.4785192/ |
| "Android update" OTAs | GM Update 662.30 (2024 Silverado EV/Sierra EV); GM Update 654.50 (2024–25 Lyriq), both "removes Alexa Built-in" | NHTSA MC-11035387, https://static.nhtsa.gov/odi/tsbs/2026/MC-11027913-0001.pdf |
| SoC (Qualcomm Global-B radio) | Qualcomm Snapdragon SA8155P; 2×6 GB LPDDR4X; 128 GB UFS; RH850 VIP MCU (CAN, power); QNX hypervisor host + AAOS/GAS guest; efuses burned | surrealdev 2026-08-15 https://surrealdev.com/rooting-the-cadillac-part-1-lay-of-the-land/ ; 2026-06-16 https://surrealdev.com/the-abcs-of-global-b/ |
| ABI on EVs | arm64-v8a (owner reports; Blazer EV USB ctrl a800000.dwc3). Conflicting x86_64 claims exist → build ABI-agnostic | XDA p7/p15; OAL GmReconProbe.kt |
| ICE trucks (not EV) | gminfo3.7/3.8, Intel Atom x7-A3960, x86_64, 2400×960 @ density 200, AAOS 10→12 (Y169/Y172/Y175/Y177/Y181) | https://github.com/lvalen91/gminfo_resources |
| Silverado EV displays | 17.7" center touchscreen (LT/RST/Trail Boss), 11" DIC, 14" HUD optional; WT (2024–25) 11" center w/ CarPlay/AA. Pixel resolution: not published | https://www.chevrolet.com/electric/silverado-ev ; CarGurus |

### T2. Build-tag decoding (*inference from Android dessert letters, corroborated*)
| Tag | Android | Seen on |
|---|---|---|
| QIHM… | 10 (Q) | 2024 ICE Silverado HD Y169 |
| SIHM… | 12 (S) | ICE Y172–Y181 |
| SQBR3/4/5/6 | 12/12L (S, API 32) | Lyriq, Blazer EV, Silverado EV, Equinox EV |
| UQBM | 14 (U, API 34) | 2024–26 Equinox EV, CT5, Silverado EV (dealer/OTA), Lyriq 2025 |

### T3. GM public emulator images (https://developer.gm.com/v1/android-emulator/repository.xml, fetched 2026-11-03)
| SDK path | Display name | API / ext | ABI | Archive URL | Size |
|---|---|---|---|---|---|
| system-images;android-34;android-automotive-lyriq;x86_64 | Cadillac LYRIQ | 34 / ext 7 | x86_64 | https://developer.gm.com/android-emulator/VCU1.0/Android14/L233/bastet_car_lyriq-img-user-latest.zip | 2,524,533,784 B |
| system-images;android-34;android-automotive-celestiq;x86_64 | Cadillac CELESTIQ | 34 / ext 7 | x86_64 | https://developer.gm.com/android-emulator/VCU1.0/Android14/L246/bastet_car_celestiq-img-user-latest.zip | 2,524,505,432 B |
Steps (developer.gm.com/android-emulators): Tools > SDK Manager > SDK Update Sites > Add (paste XML URL) > SDK Platforms > Android 14L (API Level 34) > select image; Device Manager > + > Create Virtual Device > Automotive > "Cadillac Lyriq display". Generic Google images: API 33, 34-ext9, 35-ext15 (Automotive with Google APIs/Play) via SDK Manager (https://developer.android.com/training/cars/testing/emulator).

### T4. App-installation routes on a production GM EV head unit
| Route | Status (2026) | Runs while driving? | Source |
|---|---|---|---|
| adb over USB (in-cab USB-C/A) | Not possible (ports are host-side behind bridge chip; GM-authenticated ADB) | n/a | XDA p16/p17/p30; JTech May 2026; OAL GmReconProbe |
| adb over Wi-Fi / wireless debugging | Not possible (never detected; VLANs) | n/a | XDA p17 |
| USB-stick APK via file-manager trick | Worked ≤SQBR5; blocked on SQBR6/UQBM | No (sideloaded ⇒ never DO) | EV Outdoors (2026-05-08 update); AOSP CPMS |
| Play Console Internal testing (dedicated AAOS track) | Works (Silverado/Sierra/Blazer/Equinox EV, Optiq, CT5; AAOS 12L & 14) | Yes if activity declares distractionOptimized | developer.android.com/training/cars/distribute; OAL install-gm.md; XDA |
| Play Closed testing | Works (form-factor review non-blocking) | Same as above | developer.android.com/training/cars/distribute |
| Play Production | Blocking car-quality review; dashboard not an allowed AAOS category | — | same |
| GM AppShop / developer program | B2B only; no consumer sideload program | — | developer.gm.com FAQ |

### T5. Play Console menu paths (dedicated AAOS internal test)
| Step | Path |
|---|---|
| Opt into AAOS | Test and release → Advanced settings → Form factors → + Add form factor → Android Automotive OS → Manage → "Use a dedicated release track for Android Automotive OS" (irreversible) |
| Release | Test and release → Testing → Internal testing → [form-factor selector: Android Automotive OS] → Create new release → upload signed .aab → Start rollout to internal testing |
| Testers | Internal testing → Testers → Create email list (truck's Play account) → tick list → Save; copy "Join on web" link → Become a tester |
| Install | Play web listing → Install / Install on more devices → choose the car |
| Requirements | US$25 + identity & Android-device verification for new personal accounts; internal testing has no 12-tester/14-day requirement; AAOS target API 35 for new apps/updates from 2026-08-31 |
Sources: https://developer.android.com/training/cars/distribute ; https://github.com/mossyhub/openautolink/blob/main/docs/install-gm.md ; https://support.google.com/googleplay/android-developer/answer/14151465 ; https://support.google.com/googleplay/android-developer/answer/11926878

### T6. VHAL properties observed by a Play-installed third-party app on a real GM EV (2024 Blazer EV "C234", Apr 2026)
| Property | ID | Permission (GM A14 protection) | Observed |
|---|---|---|---|
| INFO_MAKE / INFO_MODEL / INFO_MODEL_YEAR | 0x11100101 / 0x11100102 / 0x11400103 | CAR_INFO (normal) | Chevrolet / C234 / 2024 |
| INFO_FUEL_TYPE | 0x11410105 | CAR_INFO | [10] electric |
| INFO_EV_CONNECTOR_TYPE | 0x11410107 | CAR_INFO | [1,5] J1772+CCS1 |
| INFO_EV_BATTERY_CAPACITY | 0x11600106 | CAR_INFO | 85,660 Wh |
| EV_BATTERY_LEVEL | 0x11600309 | CAR_ENERGY (dangerous/runtime) | live Wh |
| RANGE_REMAINING | 0x11600308 | CAR_ENERGY | live m |
| EV_BATTERY_INSTANTANEOUS_CHARGE_RATE | 0x1160030C | CAR_ENERGY | live (mW; 0 parked) |
| EV_CHARGE_PORT_OPEN / _CONNECTED | 0x1120030A / 0x1120030B | CAR_ENERGY_PORTS (normal) | live |
| EV_CURRENT_BATTERY_CAPACITY | 0x1160030D | CAR_ENERGY | NOT exposed by GM HAL |
| EV_BATTERY_AVERAGE_TEMPERATURE | 0x1160030E | CAR_ENERGY | unverified on GM |
| PERF_VEHICLE_SPEED | 0x11600207 | CAR_SPEED (dangerous) | needs runtime grant |
| PERF_ODOMETER | 0x11600204 | CAR_MILEAGE (privileged on GM) | blocked |
| TIRE_PRESSURE / ABS / TC | 0x07410F07 / 0x1120040A / 0x1120040B | CAR_TIRES / CAR_DYNAMICS_STATE (privileged on GM) | blocked |
| ENV_OUTSIDE_TEMPERATURE | 0x11600703 | CAR_EXTERIOR_ENVIRONMENT (normal) | requested by OAL |
Not in VHAL at all: pack voltage, pack current, cell voltages, Ah capacity, SoH, module temperatures. Sources: https://github.com/mossyhub/openautolink/blob/main/docs/ev-energy-model-reverse-engineering.md ; https://github.com/mossyhub/openautolink/blob/main/app/src/main/AndroidManifest.xml ; https://github.com/mossyhub/openautolink/blob/main/app/src/main/java/com/openautolink/app/input/VehicleDataForwarderImpl.kt

### T7. External I/O surfaces usable by an ordinary app on GM EV AAOS (for a read-only external data source)
| Surface | Evidence | Notes |
|---|---|---|
| USB host via UsbManager | Carlink app ↔ Carlinkit 1314:1521 on Silverado/Blazer/Equinox EV | "USB device not supported" toast + 2 permission prompts every attach (no android.car.usb.handler) |
| USB Ethernet adapter | OAL (Apr 2026, Blazer EV): head unit eth0 = 192.168.222.108 ↔ Pi 192.168.222.222 TCP 5288–5290 | brief "unsupported device" toast, still works |
| Car Wi-Fi hotspot | OAL WPP binds ap_br_swlan0; phone↔head-unit app TCP | client isolation / OnStar-plan dependence not verified |
| Bluetooth (RFCOMM/advertise/scan) | OAL uses BLUETOOTH_CONNECT/SCAN/ADVERTISE | BLE GATT central to an OBD dongle not specifically verified on GM |
| USB mass storage (FAT32) | Owners read/write files on USB sticks via file managers | Use for log export |

### T8. Phone projection by GM EV (US)
| Vehicle | CarPlay/AA | Source |
|---|---|---|
| Silverado EV LT/RST/Trail Boss, Sierra EV, Blazer EV, Equinox EV, Optiq, Escalade IQ | No | TheDrive 2026-09-22 |
| Silverado EV WT 2024–25 | Yes (11" screen); reportedly dropped for 2026 | CarGurus; search summaries |
| Hummer EV | Yes ≤MY2025; removed MY2026 | InsideEVs 2025-10-24 |
| Cadillac Lyriq | Yes (only US GM EV) | InsideEVs 2025-10-24; TheDrive |
| 2027 ICE Silverado/Sierra | Yes (split window) | InsideEVs 2026-09-15 |
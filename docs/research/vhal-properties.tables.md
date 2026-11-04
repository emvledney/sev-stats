### Table 1: Vehicle properties relevant to an EV dashboard (AOSP main / android-17.0.0_r1)
'First AOSP' is the first release tag whose VehiclePropertyIds.java contains the constant (10 = API 29, 11 = 30, 12 = 31/32, 13 = 33, 14 = 34, 15 = 35, 16 = 36). 'A12' and 'A14' say whether that version's car service (PropertyHalServiceIds) knows the property. A property unknown to the car service is unavailable to apps even if the VHAL implements it. Protection levels come from service/AndroidManifest.xml. Note: the dumpsys hex for RANGE_REMAINING is 0x11600308.

| Property | ID (hex) | Type | Unit / values | Area | Change mode | First AOSP | READ permission for a third-party app | Protection | A12 | A14 |
|---|---|---|---|---|---|---|---|---|---|---|
| EV_BATTERY_LEVEL | 0x11600309 | Float | Wh | GLOBAL | CONTINUOUS | 10 | android.car.permission.CAR_ENERGY | dangerous | yes | yes |
| INFO_EV_BATTERY_CAPACITY | 0x11600106 | Float | Wh (nominal, when new) | GLOBAL | STATIC | 10 | android.car.permission.CAR_INFO | normal | yes | yes |
| EV_CURRENT_BATTERY_CAPACITY | 0x1160030D | Float | Wh (usable now; aging + temperature) | GLOBAL | ON_CHANGE | 14 | CAR_ENERGY | dangerous | no | yes |
| EV_BATTERY_INSTANTANEOUS_CHARGE_RATE | 0x1160030C | Float | mW; +charging / −discharging | GLOBAL | CONTINUOUS | 10 | CAR_ENERGY | dangerous | yes | yes |
| EV_BATTERY_AVERAGE_TEMPERATURE | 0x1160030E | Float | °C | GLOBAL | CONTINUOUS | 15 (flag android_vic_vehicle_properties) | CAR_ENERGY | dangerous | no | no |
| EV_CHARGE_STATE | 0x11400F41 | Integer | EvChargeState: 0 UNKNOWN, 1 CHARGING, 2 FULLY_CHARGED, 3 NOT_CHARGING, 4 ERROR | GLOBAL | ON_CHANGE | 13 | CAR_ENERGY | dangerous | no | yes |
| EV_CHARGE_PORT_OPEN | 0x1120030A | Boolean | – (write needs CONTROL_CAR_ENERGY_PORTS, signature) | GLOBAL | ON_CHANGE | 10 | android.car.permission.CAR_ENERGY_PORTS | normal | yes | yes |
| EV_CHARGE_PORT_CONNECTED | 0x1120030B | Boolean | – | GLOBAL | ON_CHANGE | 10 | CAR_ENERGY_PORTS | normal | yes | yes |
| EV_CHARGE_CURRENT_DRAW_LIMIT | 0x11600F3F | Float | A (configArray[0] = vehicle max A) | GLOBAL | ON_CHANGE | 13 | CAR_ENERGY | dangerous | no | yes |
| EV_CHARGE_PERCENT_LIMIT | 0x11600F40 | Float | % 0–100 | GLOBAL | ON_CHANGE | 13 | CAR_ENERGY | dangerous | no | yes |
| EV_CHARGE_TIME_REMAINING | 0x11400F43 | Integer | s (0 if not charging) | GLOBAL | CONTINUOUS | 13 | CAR_ENERGY | dangerous | no | yes |
| EV_CHARGE_SWITCH | 0x11200F42 | Boolean | read only for third parties (write needs CONTROL_CAR_ENERGY, signature) | GLOBAL | ON_CHANGE | 13 | CAR_ENERGY | dangerous | no | yes |
| EV_REGENERATIVE_BRAKING_STATE | 0x11400F44 | Integer | 0 UNKNOWN, 1 DISABLED, 2 PARTIALLY_ENABLED, 3 FULLY_ENABLED | GLOBAL | ON_CHANGE | 13 | CAR_ENERGY | dangerous | no | yes |
| EV_BRAKE_REGENERATION_LEVEL | 0x1140040C | Integer | level (AreaIdConfig min/max) | GLOBAL | ON_CHANGE | 14 | android.car.permission.CAR_POWERTRAIN | normal | no | yes |
| EV_STOPPING_MODE | 0x1140040D | Integer | EvStoppingMode: 0 OTHER, 1 CREEP, 2 ROLL, 3 HOLD | GLOBAL | ON_CHANGE | 14 | CAR_POWERTRAIN | normal | no | yes |
| EV_BATTERY_DISPLAY_UNITS | 0x11400603 | Integer | VehicleUnit (WATT_HOUR, AMPERE_HOURS, KILOWATT_HOUR) | GLOBAL | ON_CHANGE | 10 | android.car.permission.READ_CAR_DISPLAY_UNITS | normal | yes | yes |
| RANGE_REMAINING | 0x11600308 | Float | m | GLOBAL | CONTINUOUS | 10 | CAR_ENERGY | dangerous | yes | yes |
| INSTANTANEOUS_EV_EFFICIENCY | 0x11600212 | Float | km/kWh | GLOBAL | CONTINUOUS | 16 | android.car.permission.CAR_MILEAGE_3P | dangerous (Android 16+ only) | no | no |
| ENV_OUTSIDE_TEMPERATURE | 0x11600703 | Float | °C | GLOBAL | CONTINUOUS | 10 | android.car.permission.CAR_EXTERIOR_ENVIRONMENT | normal | yes | yes |
| HVAC_TEMPERATURE_CURRENT | 0x15600502 | Float | °C | SEAT | ON_CHANGE | 10 | android.car.permission.CONTROL_CAR_CLIMATE | signature\|privileged (not obtainable) | – | – |
| HVAC_TEMPERATURE_SET | 0x15600503 | Float | °C | SEAT | ON_CHANGE | 10 | CONTROL_CAR_CLIMATE | signature\|privileged | – | – |
| HVAC_TEMPERATURE_DISPLAY_UNITS | 0x1140050E | Integer | VehicleUnit | GLOBAL | ON_CHANGE | 10 | READ_CAR_DISPLAY_UNITS | normal | yes | yes |
| PERF_VEHICLE_SPEED | 0x11600207 | Float | m/s (negative in reverse) | GLOBAL | CONTINUOUS | 10 | android.car.permission.CAR_SPEED | dangerous (LOCATION group) | yes | yes |
| PERF_VEHICLE_SPEED_DISPLAY | 0x11600208 | Float | m/s | GLOBAL | CONTINUOUS | 10 | CAR_SPEED | dangerous | yes | yes |
| WHEEL_TICK | 0x11510306 | Long[] | ticks | GLOBAL | CONTINUOUS | 10 | CAR_SPEED | dangerous | yes | yes |
| PERF_ODOMETER | 0x11600204 | Float | km | GLOBAL | CONTINUOUS | 10 | Android ≤15: android.car.permission.CAR_MILEAGE; Android 16+: CAR_MILEAGE_3P | ≤15: signature\|privileged; 16+: dangerous | not obtainable | not obtainable |
| PERF_STEERING_ANGLE | 0x11600209 | Float | degrees | GLOBAL | CONTINUOUS | 10 | ≤15: READ_CAR_STEERING; 16+: READ_CAR_STEERING_3P | signature / dangerous (16+) | no | no |
| GEAR_SELECTION | 0x11400400 | Integer | VehicleGear: N=0x1, R=0x2, P=0x4, D=0x8 | GLOBAL | ON_CHANGE | 10 | CAR_POWERTRAIN | normal | yes | yes |
| CURRENT_GEAR | 0x11400401 | Integer | VehicleGear | GLOBAL | ON_CHANGE | 10 | CAR_POWERTRAIN | normal | yes | yes |
| IGNITION_STATE | 0x11400409 | Integer | 0 UNDEFINED, 1 LOCK, 2 OFF, 3 ACC, 4 ON, 5 START | GLOBAL | ON_CHANGE | 10 | CAR_POWERTRAIN | normal | yes | yes |
| PARKING_BRAKE_ON | 0x11200402 | Boolean | – | GLOBAL | ON_CHANGE | 10 | CAR_POWERTRAIN | normal | yes | yes |
| NIGHT_MODE | 0x11200407 | Boolean | – | GLOBAL | ON_CHANGE | 10 | CAR_EXTERIOR_ENVIRONMENT | normal | yes | yes |
| TIRE_PRESSURE | 0x17600309 | Float | kPa | WHEEL | CONTINUOUS | 10 | ≤15: android.car.permission.CAR_TIRES; 16+: CAR_TIRES_3P | signature / dangerous (16+) | no | no |
| CRITICALLY_LOW_TIRE_PRESSURE | 0x1760030A | Float | kPa | WHEEL | STATIC | 12 | CAR_TIRES | signature\|privileged | no | no |
| DISTANCE_DISPLAY_UNITS / VEHICLE_SPEED_DISPLAY_UNITS / TIRE_PRESSURE_DISPLAY_UNITS | 0x11400600 / 0x11400604 / 0x11400602 | Integer | VehicleUnit | GLOBAL | ON_CHANGE | 10 | READ_CAR_DISPLAY_UNITS | normal | yes | yes |
| INFO_MAKE / INFO_MODEL / INFO_MODEL_YEAR | 0x11100101 / 0x11100102 / 0x11400103 | String / String / Integer | – | GLOBAL | STATIC | 10 | CAR_INFO | normal | yes | yes |
| INFO_MODEL_TRIM | 0x1110010D | String | – | GLOBAL | STATIC | 16 | CAR_INFO | normal | no | no |
| INFO_VIN | 0x11100100 | String | – | GLOBAL | STATIC | 10 | android.car.permission.CAR_IDENTIFICATION | signature\|privileged (not obtainable) | – | – |
| INFO_FUEL_TYPE / INFO_EV_CONNECTOR_TYPE | 0x11410105 / 0x11410107 | Integer[] | FuelType / EvConnectorType | GLOBAL | STATIC | 10 | CAR_INFO | normal | yes | yes |
| INFO_EV_PORT_LOCATION | 0x11400109 | Integer | PortLocationType | GLOBAL | STATIC | 10 | CAR_INFO | normal | yes | yes |
| INFO_EXTERIOR_DIMENSIONS | 0x1141010B | Integer[] | mm | GLOBAL | STATIC | 11 | CAR_INFO | normal | yes | yes |
| ENGINE_RPM | 0x11600305 | Float | rpm | GLOBAL | CONTINUOUS | 10 | ≤15: CAR_ENGINE_DETAILED; 16+: CAR_ENGINE_DETAILED_3P | signature / dangerous (16+) | no | no |
| ENGINE_COOLANT_TEMP / ENGINE_OIL_TEMP | 0x11600301 / 0x11600304 | Float | °C | GLOBAL | CONTINUOUS | 10 | android.car.permission.CAR_ENGINE_DETAILED | signature\|privileged | no | no |
| VEHICLE_CURB_WEIGHT | 0x11400F46 | Integer | kg (configArray[0] = gross kg) | GLOBAL | STATIC | 13 | android.car.permission.PRIVILEGED_CAR_INFO | signature\|privileged | – | no |
| TRAILER_PRESENT | 0x11400F45 | Integer | TrailerState | GLOBAL | ON_CHANGE | 13 | PRIVILEGED_CAR_INFO | signature\|privileged | – | no |
| ACCELERATOR_ / BRAKE_PEDAL_COMPRESSION_PERCENTAGE | 0x1160030F / 0x11600310 | Float | % | GLOBAL | CONTINUOUS | 16 | android.car.permission.READ_CAR_PEDALS | dangerous (16+) | no | no |
| BRAKE_PAD_WEAR_PERCENTAGE | 0x17600311 | Float | % | WHEEL | ON_CHANGE | 16 | android.car.permission.READ_BRAKE_INFO | dangerous (16+) | no | no |
| OBD2_LIVE_FRAME (CarDiagnosticManager) | 0x11E00D00 | MIXED | OBD2 frame | GLOBAL | – | 10 | android.car.permission.CAR_DIAGNOSTICS | signature\|privileged | no | no |
| Any GM vendor property | 0x2xxxxxxx (group VENDOR) | – | – | – | – | – | android.car.permission.CAR_VENDOR_EXTENSION or GET_CAR_VENDOR_CATEGORY_* | signature\|privileged | no | no |

Sources: https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/main/car-lib/src/android/car/VehiclePropertyIds.java ; https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/automotive/vehicle/aidl_property/android/hardware/automotive/vehicle/VehicleProperty.aidl ; https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/main/service/AndroidManifest.xml ; https://android.googlesource.com/platform/packages/services/Car/+/refs/tags/android-12.1.0_r1/service/src/com/android/car/hal/PropertyHalServiceIds.java ; https://android.googlesource.com/platform/packages/services/Car/+/refs/tags/android-14.0.0_r1/service/src/com/android/car/hal/PropertyHalServiceIds.java ; release tags android-10.0.0_r1 … android-17.0.0_r1 of the same files. All read 2026-11-03.

Property ID layout: ID = group (0x10000000 SYSTEM / 0x20000000 VENDOR) | area (0x01000000 GLOBAL, 0x05000000 SEAT, 0x07000000 WHEEL) | type (0x00200000 BOOLEAN, 0x00400000 INT32, 0x00600000 FLOAT, 0x00100000 STRING) | 16-bit index.

### Table 2: Car permission strings and protection levels (Car service AndroidManifest.xml)
| Permission string | protectionLevel | Permission group | In manifest since |
|---|---|---|---|
| android.car.permission.CAR_INFO | normal (install-time) | – | Android 10 |
| android.car.permission.CAR_ENERGY_PORTS | normal | – | 10 |
| android.car.permission.CAR_POWERTRAIN | normal | – | 10 |
| android.car.permission.CAR_EXTERIOR_ENVIRONMENT | normal | – | 10 |
| android.car.permission.READ_CAR_DISPLAY_UNITS | normal | – | 10 |
| android.car.permission.CAR_ENERGY | dangerous (runtime dialog) | android.car.permission-group.CAR_MONITORING | 10 |
| android.car.permission.CAR_SPEED | dangerous | android.permission-group.LOCATION | 10 |
| android.car.permission.CAR_MILEAGE_3P | dangerous | android.permission-group.LOCATION | 16 |
| android.car.permission.CAR_TIRES_3P | dangerous | CAR_MONITORING | 16 |
| android.car.permission.CAR_ENGINE_DETAILED_3P | dangerous | CAR_MONITORING | 16 |
| android.car.permission.READ_CAR_STEERING_3P | dangerous | LOCATION | 16 |
| android.car.permission.READ_CAR_PEDALS / READ_BRAKE_INFO | dangerous | CAR_MONITORING | 16 |
| android.car.permission.CAR_DRIVING_STATE_3P | dangerous | CAR_MONITORING | 16 |
| android.car.permission.CAR_MILEAGE | signature\|privileged | – | 10 |
| android.car.permission.CAR_TIRES | signature\|privileged | – | 10 |
| android.car.permission.CAR_ENGINE_DETAILED | signature\|privileged | – | 10 |
| android.car.permission.CAR_IDENTIFICATION (VIN) | signature\|privileged | – | 10 |
| android.car.permission.CONTROL_CAR_CLIMATE | signature\|privileged | – | 10 |
| android.car.permission.PRIVILEGED_CAR_INFO | signature\|privileged | – | 13 |
| android.car.permission.CONTROL_CAR_ENERGY / CONTROL_CAR_ENERGY_PORTS / ADJUST_RANGE_REMAINING | signature\|privileged | – | – |
| android.car.permission.CAR_VENDOR_EXTENSION / GET_CAR_VENDOR_CATEGORY_* | signature\|privileged | – | – |
| android.car.permission.CAR_DIAGNOSTICS / CLEAR_CAR_DIAGNOSTICS | signature\|privileged | – | 10 |
| android.car.permission.CONTROL_CAR_HORN | signature\|privileged (javadoc wrongly says dangerous) | – | 16 |
| Android Auto only: com.google.android.gms.permission.CAR_FUEL / CAR_SPEED / CAR_MILEAGE | (Android Auto projection) | – | – |

Sources: https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/main/service/AndroidManifest.xml ; https://android.googlesource.com/platform/packages/services/Car/+/refs/tags/android-16.0.0_r1/service/AndroidManifest.xml ; https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/main/car-lib/src/android/car/Car.java ; https://developer.android.com/training/cars/apps/library/car-hardware-api

### Table 3: Car App Library (androidx.car.app.hardware) on AAOS
| API | VHAL properties used (androidx-main source) | AAOS permissions | Android Auto permission | Car App API | Notes |
|---|---|---|---|---|---|
| CarInfo.fetchModel | INFO_MAKE, INFO_MODEL, INFO_MODEL_YEAR | CAR_INFO | – | 3 | |
| CarInfo.fetchEnergyProfile | INFO_EV_CONNECTOR_TYPE, INFO_FUEL_TYPE | CAR_INFO | CAR_FUEL | 3 | |
| CarInfo.fetchExteriorDimensions | INFO_EXTERIOR_DIMENSIONS | CAR_INFO | n/a | 7 | API 30+ head units only |
| CarInfo.addEnergyLevelListener | EV_BATTERY_LEVEL + EV_CURRENT_BATTERY_CAPACITY (for %), FUEL_LEVEL, FUEL_LEVEL_LOW, RANGE_REMAINING, DISTANCE_/FUEL_VOLUME_DISPLAY_UNITS | CAR_ENERGY, CAR_ENERGY_PORTS, READ_CAR_DISPLAY_UNITS | CAR_FUEL | 3 | Default rate 5 Hz; battery % unavailable on head units without EV_CURRENT_BATTERY_CAPACITY (Android 12/13) |
| CarInfo.addSpeedListener | PERF_VEHICLE_SPEED, PERF_VEHICLE_SPEED_DISPLAY, VEHICLE_SPEED_DISPLAY_UNITS | CAR_SPEED, READ_CAR_DISPLAY_UNITS | CAR_SPEED | 3 | |
| CarInfo.addMileageListener | PERF_ODOMETER | not available to Play AAOS apps (CAR_MILEAGE is signature) | CAR_MILEAGE | 3 | |
| CarInfo.addEvStatusListener | EV_CHARGE_PORT_OPEN, EV_CHARGE_PORT_CONNECTED | CAR_ENERGY_PORTS | – | – | |
| CarInfo.addTollListener | ELECTRONIC_TOLL_COLLECTION_CARD_STATUS | CAR_INFO (per PropertyUtils) | – | 3 | |
| CarSensors (accelerometer / gyroscope / compass / location) | – | – | – | 3 | Returns STATUS_UNIMPLEMENTED on AAOS; use SensorManager / LocationManager |
| CarClimate | HVAC_* | CONTROL_CAR_CLIMATE (signature) | – | – | Not usable by third-party apps |

None of these APIs exposes charge rate, battery temperature or charge state. Use CarPropertyManager directly for those. Sources: https://developer.android.com/training/cars/apps/library/car-hardware-api ; https://github.com/androidx/androidx/blob/androidx-main/car/app/app-automotive/src/main/java/androidx/car/app/hardware/info/AutomotiveCarInfo.java ; https://github.com/androidx/androidx/blob/androidx-main/car/app/app-automotive/src/main/java/androidx/car/app/hardware/common/PropertyUtils.java ; https://github.com/androidx/androidx/blob/androidx-main/car/app/app-automotive/src/main/java/androidx/car/app/hardware/common/PropertyRequestProcessor.java

### Table 4: GM platform facts
| Item | Value | Source | Confidence |
|---|---|---|---|
| 2024 Silverado EV / Sierra EV infotainment ECU | 'VCU (Virtual Cockpit Unit)'; GM Update 662.30: 'Improvements to the infotainment system from Android update' | https://static.nhtsa.gov/odi/tsbs/2026/MC-11035387-0001.pdf | high |
| GM emulator SDK update site | https://developer.gm.com/v1/android-emulator/repository.xml (Android Studio > SDK Manager > SDK Update Sites) | GM developer portal JS and XML, read 2026-11-03 | high |
| GM emulator image 1 | system-images;android-34;android-automotive-lyriq;x86_64, tag 'Cadillac LYRIQ', .../VCU1.0/Android14/L233/bastet_car_lyriq-img-user-latest.zip, 2,524,533,784 bytes | same XML | high |
| GM emulator image 2 | system-images;android-34;android-automotive-celestiq;x86_64, .../VCU1.0/Android14/L246/bastet_car_celestiq-img-user-latest.zip | same XML | high |
| AVD hardware profile | 'Cadillac Lyriq display' (Device Manager > Automotive) | GM portal AndroidEmulator page text | high |
| GM SDK license | GM Developer Agreement (Dec 2021); forbids reverse engineering, disassembly or decompiling of GM Software; must be accepted by the user | repository.xml license block | high |
| Ultium EV Android version | Shipped on Android 12; owners report Android 14 ('AAOS14', build 'UQBM') updates in 2025–2026 | https://www.silveradoevforum.com/threads/android-14-update-uqbm-for-sev.4120/ ; https://www.equinoxevforum.com/threads/android-14.6580/ (search snippets only) | medium |
| GM ICE 2024 Silverado head unit (not the EV) | gminfo3.7, Intel Atom x7-A3960, Android 12 (API 32), build W231E-Y181.3.2-SIHM22B-499.3 | https://github.com/stevenrhawk/carlink_native/blob/main/documents/reference/gminfo/README.md | high (for ICE) |

### Table 5: Real-vehicle evidence
| Evidence | Vehicle / OS | What it shows | Date | Source |
|---|---|---|---|---|
| Home Assistant companion app (Play) car sensors | 2024 Honda Prologue (GM-built AAOS) | Battery level, range, charging status and GPS work; no odometer | 2025-03-06 | https://github.com/home-assistant/android/issues/4871 |
| HA app disabled after ~24 h | 2024 Blazer EV | Play app with all permissions granted is later disabled (CarWatchdog-style) | 2025-06-16 | https://github.com/home-assistant/android/issues/5418 |
| HA app disabled after weeks ('consuming too many resources') | 2025 Cadillac CT5 | Prioritize-app flow; app disappears | 2025-09-29 | https://github.com/home-assistant/android/issues/5418 |
| carlink_native: CarPropertyManager showed 'all sensors failed'; switched to CarHardwareManager | 2024 Silverado ICE, gminfo3.7, Android 12 | Root cause unclear (androidx uses the same in-process API; likely missing runtime grants or an ICE vehicle) | 2026-04-16 | https://github.com/stevenrhawk/carlink_native/pull/2 |
| Car Stats Viewer subscribes to EV_BATTERY_INSTANTANEOUS_CHARGE_RATE at 100 Hz and flips sign to get consumption | Polestar 2/4, Volvo | Negative = discharging on real cars; some builds lack the power property | 2023–2026 | https://github.com/Ixam97/CarStatsViewer |
| Template host throttles refresh to ~7–8 s | Polestar (Google host) | Limits live templated dashboards while driving | 2024-06-26 | https://github.com/Ixam97/CarStatsViewer/issues/225 |
| ABRP on GM AAOS | 2025 Sierra EV | Navigation bugs only; no evidence of SoC ingestion | 2025-07 to 2025-09 | https://abrp.featurebase.app/p/unusable-on-aaos |
| Public GM VHAL property dump | – | None found | – | – |

### Table 6: AOSP reference VHAL sample rates (OEM values will differ; read CarPropertyConfig.getMin/MaxSampleRate at runtime)
| Property | minSampleRate (Hz) | maxSampleRate (Hz) |
|---|---|---|
| EV_BATTERY_INSTANTANEOUS_CHARGE_RATE | 1 | 10 |
| EV_BATTERY_LEVEL | 1 | 100 |
| RANGE_REMAINING | 1 | 2 |
| EV_BATTERY_AVERAGE_TEMPERATURE | 1 | 2 |
| ENV_OUTSIDE_TEMPERATURE | 1 | 2 |
| PERF_VEHICLE_SPEED / PERF_ODOMETER / EV_CHARGE_TIME_REMAINING | 1 | 10 |

CarPropertyManager constants: SENSOR_RATE_ONCHANGE = 0, NORMAL = 1, UI = 5, FAST = 10, FASTEST = 100. Source: https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/automotive/vehicle/aidl/impl/current/default_config/config/DefaultProperties.json ; https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/main/car-lib/src/android/car/hardware/property/CarPropertyManager.java

### Table 7: User's requested metrics vs. AAOS availability on a third-party app (GM head unit on Android 14)
| Metric | AAOS (third-party) | How / why not |
|---|---|---|
| SoC % | Yes, if GM implements it | EV_BATTERY_LEVEL / EV_CURRENT_BATTERY_CAPACITY × 100 (fall back to INFO_EV_BATTERY_CAPACITY) |
| Pack power in/out (kW) | Yes, if GM implements it | EV_BATTERY_INSTANTANEOUS_CHARGE_RATE / 1e6 (+ charging) |
| Capacity, kWh | Partial | INFO_EV_BATTERY_CAPACITY (nominal), EV_CURRENT_BATTERY_CAPACITY (current usable) |
| Capacity, Ah | No | Needs pack voltage (OBD) or a hard-coded nominal voltage |
| Pack voltage / current | No | No VHAL property; vendor properties are privileged; use OBD/UDS |
| Cell-level voltages | No | No VHAL property; use OBD/UDS |
| Battery temperature (average) | No on Android 14 | EV_BATTERY_AVERAGE_TEMPERATURE needs an Android 15+ car service |
| Cell/module, coolant, motor temperatures | No | No VHAL property; use OBD/UDS |
| Outside temperature | Yes | ENV_OUTSIDE_TEMPERATURE (normal permission) |
| Cabin temperature | No | HVAC_TEMPERATURE_CURRENT needs CONTROL_CAR_CLIMATE (signature) |
| Charge state / time remaining / limit | Yes on Android 13+ | EV_CHARGE_STATE, EV_CHARGE_TIME_REMAINING, EV_CHARGE_PERCENT_LIMIT, EV_CHARGE_CURRENT_DRAW_LIMIT |
| Speed, gear, ignition | Yes | CAR_SPEED (dangerous), CAR_POWERTRAIN (normal) |
| Odometer, tire pressure, VIN | No (odometer and tire pressure only on Android 16+ via *_3P) | signature permissions |
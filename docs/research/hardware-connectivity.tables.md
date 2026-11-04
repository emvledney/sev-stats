### Table 1 – OBD and CAN adapters compared for Silverado EV (GM Ultium / VIP)
| Adapter | Link to host | Chip / controller | CAN FD | 29-bit headers, custom flow control, long ISO-TP | Sleep current | Reported on Ultium? | Role in plan | Sources |
|---|---|---|---|---|---|---|---|---|
| OBDLink MX+ | BT 3.0 Classic (SPP) | STN2255 / STN2256 | No | Yes: ATCP, ATCRA, ATFCSH/SD/SM, STCFCPA; STPX up to 4 KB | 2 mA (62 mA running) | Yes, Silverado EV owners with Car Scanner | Laptop via rfcomm; phone fallback; AAOS over Classic BT unverified | https://www.obdlink.com/products/obdlink-mxp/ ; https://www.scantool.net/scantool/downloads/682/obdlink_frpm_f.pdf |
| OBDLink CX | BLE 5.1 | STN2310 (FCC Y82-DA14531MOD) | No | STN command set (CX not in the STPX size table) | <1 mA (55 mA running) | No specific report | **First choice for a direct BLE link from the AAOS app** (if BLE works) | https://www.obdlink.com/products/obdlink-cx/ ; FRPM |
| OBDLink EX | USB 2.0 | STN2232 | No | Yes; 4 KB | <1 mA | — | Laptop serial; possible AAOS USB-serial fallback | https://www.obdlink.com/products/obdlink-ex/ ; FRPM |
| Vgate vLinker MC+ | BT3.0 + BLE4.0 (retail listing) | Not disclosed; claims ELM327/ELM329 and STN command sets | Not stated | Claimed | About 3 mA | Yes (Silverado EV forum) | Backup BLE dongle | https://www.silveradoevforum.com/threads/what-ev-obd2-dongles-you-all-using.4225/ ; https://www.neweggbusiness.com/p/9B-0FB-0ACC-001Z0 |
| Vgate vLinker FD / FD+ / FS | BT3.0 (FD), BLE (FD+), USB (FS) | "FD" means Ford MS-CAN for FORScan | No (not CAN FD) | — | About 3 mA | — | Not needed | https://www.vgatemall.com/products-detail/i-29/ |
| VEEPEAK OBDCheck BLE (ELM327 v1.5 clone) | BLE: service FFF0, write FFF2, notify FFF1 | Clone | No | Only 3-byte ATSH (4-byte rejected) | n/a | Yes (2024 BrightDrop Zevo) | Cheap test only | https://github.com/Coldarn/hacs_gm_ultium |
| MeatPi WiCAN Pro | Wi-Fi 2.4 GHz access point + station, BLE, USB | ESP32-S3 + unnamed OBD chip | Not stated | ELM327 v2.3, ELM329, STN, slcan, RealDash; GM BT1 profile | <1 mA (README) / 2.8 mA (2024) | Yes (Equinox EV, 2027 Bolt); Silverado EV/Sierra EV profiles | Wi-Fi fallback; Home Assistant/ABRP; US$89 | https://www.meatpi.com/products/wican-pro/ ; https://github.com/meatpiHQ/wican-fw |
| Macchina A0 | Wi-Fi/BLE | ESP32 TWAI (S pin GPIO21) | No | Firmware-defined (bri3d isotp-ble-bridge) | — | — | DIY BLE bridge base (classic CAN) | https://github.com/bri3d/esp32-isotp-ble-bridge |
| comma red panda | USB | STM32H725 | Yes, on all 3 buses | Through panda Python library; elm327 safety mode allows only 0x18DA..F1 / 0x18DB33F1 / 0x6xx / 0x7xx | — | — | CAN FD discovery fallback | https://panda.comma.ai ; https://raw.githubusercontent.com/commaai/opendbc/master/opendbc/safety/modes/elm327.h |
| CANable 2.0 | USB (gs_usb or slcan) | STM32G431 | Hardware yes; candleLight firmware has no FD frames | can-isotp in kernel | — | — | Budget classic SocketCAN | https://canable.io/index.html ; https://github.com/candle-usb/candleLight_fw |
| PEAK PCAN-USB FD (IPEH-004022) | USB (peak_usb) | — | Yes, 40 kbit/s to 12 Mbit/s; 500 V isolation | can-isotp in kernel | — | — | **Best laptop interface**, about US$347 | https://gridconnect.com/can-usb-fd-adapter.html |
| Kvaser Leaf v3 | USB (kvaser_usb) | — | Yes, up to 8 Mbit/s | can-isotp in kernel | — | — | Alternative, US$407 | https://www2.kvaser.com/product/leaf-v3 |

### Table 2 – Published ELM/STN settings for GM BT1 (Silverado EV, Sierra EV, Hummer EV) and their SocketCAN equivalents
| Source (date) | Protocol / init | Request header | Receive filter | Flow control | Data IDs (service 0x22) | Source URL |
|---|---|---|---|---|---|---|
| WiCAN bt1.json (BT1: Hummer EV, Silverado EV, Sierra EV) | `ATST96;` then `ATSP7;ATCP14` | `ATSHDACBF1` → 0x14DACBF1 | `ATCRA142AF1CB` | `ATFCSH14DACBF1;ATFCSD300000;ATFCSM1` | 2227C6 SOC `[B8:B9]/655.35` (a 2027 Bolt needed `[B3:B4]`) | https://raw.githubusercontent.com/meatpiHQ/wican-fw/main/vehicle_profiles/bt1/bt1.json |
| WiCAN gmc/sierra-ev.json (2025-11-08) | `ATSP6;ATST64;` | `ATSH14DACBF1` | `ATCRA142AF1CB` | — | 2227C6 SOC, 2227AF HV energy remaining /100, 220046 temp (B4-40), 2227C7 range /103, 225401 DC charge power /4350, 2227C0 distance since full charge | https://raw.githubusercontent.com/meatpiHQ/wican-fw/main/vehicle_profiles/gmc/sierra-ev.json |
| Coldarn hacs_gm_ultium (2026, BrightDrop Zevo) | `ATSP7` | `ATSH DACBF1` → 0x18DACBF1 (BEC); DMCM 0x17 | Default | Default | BEC: 27C6, 27AF, 2AF5 (cell V /10000), 2AF1, 27C7, 5401, 33E5; DMCM: 2885 (pack V /100), 2414 (current s16/20); skip 2B43 | https://github.com/Coldarn/hacs_gm_ultium |
| OBDb chevrolet.json (make level) | — | hdr DA11/DA18/DA28, pri 14 | rax 2AF111 / 2AF118 / 2AF128 | fcm1 = true | 616 commands | https://github.com/OBDb/Chevrolet-Silverado-EV |
| SocketCAN equivalent (inference) | `ip link set can0 type can bitrate 500000 restart-ms 100` | `isotpsend -s 14DACBF1 -d 142AF1CB can0` (or `-s 18DACBF1 -d 18DAF1CB`) | Same as -d | Handled by kernel can-isotp | e.g. `echo 22 27 C6` | https://raw.githubusercontent.com/linux-can/can-utils/master/isotpsend.c |

### Table 3 – Ways an AAOS app on the GM head unit could reach a dongle or bridge
| Transport | APIs, permissions, manifest | Evidence on GM AAOS | Status | Sources |
|---|---|---|---|---|
| BLE GATT | BluetoothLeScanner / BluetoothGatt; `android.permission.BLUETOOTH_SCAN` (neverForLocation), `android.permission.BLUETOOTH_CONNECT`; `uses-feature android.hardware.bluetooth_le required=false` | CDD [7.4.3/A-0-1] SHOULD support BLE; Bosch VCU radio supports Classic and LE; no third-party app example found | **Untested – test first** | https://source.android.com/docs/compatibility/14/android-14-cdd |
| Bluetooth Classic SPP (RFCOMM) | createRfcommSocketToServiceRecord; pairing through car Settings | None found | Untested, lower priority | https://source.android.com/docs/automotive/ivi_connectivity |
| USB host | UsbManager; `uses-feature android.hardware.usb.host required=false`; USB_DEVICE_ATTACHED filter plus device_filter.xml | Raw USB works for Carlink apps on Silverado EV / Sierra EV / Blazer EV; permission prompt on every attach; EV "USB Device Not Supported" quirk | Works for some device types; USB-serial untested | https://xdaforums.com/t/general-motors-google-built-in-tinkering.4668105/page-21 |
| Wi-Fi to dongle/bridge access point | WifiNetworkSpecifier plus `removeCapability(NET_CAPABILITY_INTERNET)`; CHANGE_WIFI_STATE, ACCESS_WIFI_STATE, CHANGE_NETWORK_STATE, INTERNET; `android.hardware.wifi required=false` | Head unit joined a Carlinkit access point from an app (prompt shown); exact SSID+BSSID is auto-approved after the first time | Works (with one-time prompt) | https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap ; https://xdaforums.com/t/general-motors-google-built-in-tinkering.4668105/page-16 |
| Wi-Fi through the OnStar hotspot | Normal sockets | Unknown whether head-unit apps can reach hotspot clients | Unknown | — |
| adb sideload | — | Locked to GM's certificate client; hardware hack needed | **Not allowed (would modify the truck)** | https://xdaforums.com/t/general-motors-google-built-in-tinkering.4668105/page-30 |

### Table 4 – GM head unit and AAOS facts
| Item | Value | Source |
|---|---|---|
| EV head unit | Qualcomm arm64, QNX hypervisor, AAOS as guest; Renesas coprocessor handles CAN | https://xdaforums.com/t/general-motors-google-built-in-tinkering.4668105/page-20 |
| Gas-truck unit (for contrast) | gminfo3.7, Intel Atom A3960, x86_64, build full_gminfo37_gb-user 10 → 12 | https://xdaforums.com/t/general-motors-google-built-in-tinkering.4668105/ |
| Android version (EV) | 12L; Android 14 rolling out to 2025-26 units | https://xdaforums.com/t/general-motors-google-built-in-tinkering.4668105/page-25 |
| GM emulator SDK update site | https://developer.gm.com/v1/android-emulator/repository.xml → system-images;android-34;android-automotive-lyriq;x86_64 and ...celestiq;x86_64 | https://developer.gm.com/android-emulators |
| Bosch VCU (FCC 2AUXS-VCUNH1) radio | Alps UGKZDA2001AC; Bluetooth Classic + LE; Wi-Fi with internal and external antenna | https://device.report/robert-bosch/vcunh1 |
| Distraction-optimized opt-in | `<meta-data android:name="distractionOptimized" android:value="true"/>`; only honored for Play-installed apps | https://developer.android.com/training/cars/testing/emulator |

### Table 5 – Linux setup on CachyOS/Arch
| Need | Package or command | Repo / version | Source |
|---|---|---|---|
| CAN tools | can-utils (candump, cansend, isotpsend/recv/dump) | AUR 2025.01-3 | https://aur.archlinux.org/packages/can-utils |
| Python CAN | python-can | extra 4.6.1 | https://archlinux.org/packages/extra/x86_64/python-can/ |
| ISO-TP / UDS | python-can-isotp, python-udsoncan | AUR 2.0.7-1, 1.26.1-1 | https://aur.archlinux.org/packages/python-udsoncan |
| GUIs | savvycan, cangaroo | AUR V220-1, 0.2.2.r64 | https://aur.archlinux.org/packages/savvycan |
| ELM/STN dongles | python-pyserial (extra), python-obd (AUR 0.7.3), python-bleak (extra 3.0.2), bluez-deprecated-tools (rfcomm) | — | https://archlinux.org/packages/extra/any/python-bleak/ |
| Classic CAN, listen-only first | `sudo ip link set can0 type can bitrate 500000 listen-only on && sudo ip link set can0 up` | Kernel docs | https://docs.kernel.org/networking/can.html |
| Normal mode with auto-restart | `sudo ip link set can0 down; sudo ip link set can0 type can bitrate 500000 listen-only off restart-ms 100; sudo ip link set can0 up` | — | same |
| CAN FD (only if needed) | `ip link set can0 up type can bitrate 500000 sample-point 0.75 dbitrate 2000000 dsample-point 0.8 fd on` (GM port data rate unverified: 2 or 5 Mbit/s) | — | https://docs.kernel.org/networking/can.html ; https://diag.net/msg/m1f2v9xm5s4xp8agm94j4c0t3i |
| CAN FD ISO-TP | `isotpsend -L 72:64:0 -s 14DACBF1 -d 142AF1CB can0` | can-utils | https://raw.githubusercontent.com/linux-can/can-utils/master/isotpsend.c |
| Kernel modules present | can, can-raw, can-isotp, gs_usb, peak_usb, kvaser_usb, slcan, mcp251xfd, ftdi_sio, cdc-acm | Local check | — |

### Table 6 – Relevant STN/ELM commands (OBDLink FRPM, Oct 2025)
| Command | Purpose |
|---|---|
| ATSP7 / ATSP6 | ISO 15765-4 CAN 29-bit / 11-bit, 500 kbit/s |
| ATCP hh | CAN priority (top bits of a 29-bit ID), e.g. 14 |
| ATSH xxyyzz | Request header (low 24 bits) |
| ATCRA pattern | Receive address filter, e.g. 142AF1CB |
| ATFCSH / ATFCSD / ATFCSM1 | User-defined ISO-TP flow-control header, data and mode |
| STCFCPA tx, rx | Add flow-control address pair, e.g. `STCFCPA 18DA10F1, 18DAF110` |
| STPX h:..,d:..,t:..,r:.. | Send any message with header, data, timeout and response count (up to 4 KB on MX+/EX) |
| STCMM 0 | Monitor mode "Receive only – no CAN ACKs" (default) |
| STCTOR fc, cf | Flow-control and consecutive-frame timeouts (defaults 75/150 ms) |
| STSLVL, STSLVLS `<volts,sec`, STSLVG, STSLCS | Voltage-based sleep and wake configuration and status |
| ATRV | Read 12 V supply |
Source: https://www.scantool.net/scantool/downloads/682/obdlink_frpm_f.pdf

### Table 7 – OBD port (J1962) and DB9 pinouts
| Pin | GM VIP OBD port | Notes | Source |
|---|---|---|---|
| 6 / 14 | High-speed CAN H / L through the SDGM | 120 Ω measured at the port on Gen3/VIP | https://diag.net/msg/m1f2v9xm5s4xp8agm94j4c0t3i |
| 1 | Not used on VIP (no single-wire GMLAN) | | same |
| 4 / 5 | Chassis / signal ground | | https://www.dashlogic.com/docs/technical/obdii_connector_pinout |
| 16 | +12 V, always on | | https://github.com/Coldarn/hacs_gm_ultium |
| 3/11, 12/13 | Sources disagree (object-detection CAN, MS-CAN, chassis CAN) | Unverified for Silverado EV | https://pinoutguide.com/CarElectronics/gm_car_obd_ii_pinout.shtml |
| DB9, CiA 303-1 (PEAK/Kvaser) | 2 = CAN_L, 7 = CAN_H, 3 = GND | | https://www.crowdsupply.com/ali-slim/ollie/updates/ollie-db9-to-obd-ii-cable-support |
| DB9, SparkFun CAB-10087 | 3 = CAN_H, 5 = CAN_L, GND on 1 or 2 | Not CiA compatible | https://sparkfun.com/products/10087 |

### Table 8 – Bridge parts list (approximate US$)
| Option | Parts | Approx. cost | Sources |
|---|---|---|---|
| A: off-the-shelf BLE dongle, no bridge | OBDLink CX | about $80 | https://www.obdlink.com/products/obdlink-cx/ |
| B: ESP32-S3 BLE/Wi-Fi bridge (CAN FD capable) | ESP32-S3 DevKit (about $15) + MCP2518FD board (Pi Hut £18.30 or MIKROE-3060 about $45) + OBD pigtail (about $12) + 12→5 V buck with voltage cutoff (about $10) + enclosure | $55–85 | https://thepihut.com/products/can-transceiver-mcp2518-board ; https://www.sparkfun.com/mikroe-mcp2518fd-click.html ; https://github.com/bri3d/esp32-isotp-ble-bridge |
| C: Raspberry Pi bridge | Pi Zero 2 W (about $15) + Waveshare 2-CH CAN FD HAT ($46.99; `dtoverlay=mcp251xfd,spi0-0,interrupt=25`) + OBD-to-screw-terminal cable + USB-C power from a truck port + microSD | $90–110 | https://www.waveshare.com/wiki/2-CH_CAN_FD_HAT |
| D: Wi-Fi dongle | WiCAN Pro ($89) | $89 | https://www.meatpi.com/products/wican-pro/ |
| Laptop discovery kit | PCAN-USB FD (about $347) or CANable 2.0 (classic, about $40) + CiA-pinout OBD-DB9 cable + OBDLink EX or MX+ | $100–450 | https://gridconnect.com/can-usb-fd-adapter.html
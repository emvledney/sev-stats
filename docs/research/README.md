# Research notes: index

All 15 files here are point-in-time notes from 2026-11-03, written before anything ran on the truck. They are kept unedited, apart from a one-line banner at the top of the files whose recommendations were later overtaken.

**The verify files re-checked the research against web sources; none of it was tested on this truck.**

Where a note disagrees with these, these win:
- what the truck does: [`docs/session1-results.md`](../session1-results.md) and `tools/probe/dids/silverado_ev.toml`
- what was decided, and what is safe: [`PLAN.md`](../../PLAN.md) (its safety model overrules every note here)
- how the app gets onto the truck: [`docs/play-console.md`](../play-console.md)

## Topics

Each topic has three files: `<topic>.md` (findings, open questions, recommendations), `<topic>.tables.md` (the data tables) and `<topic>.verify.md` (the review).

| Topic | Covers | Still the reference for |
|---|---|---|
| `ultium-uds` | OBD-II port, 29-bit addressing, Mode 0x22 DIDs and scalings, adapter behavior | Decodes and their sources, service safety classification, NRC meanings, pinout |
| `vhal-properties` | Android vehicle properties, permissions, Car App Library | What an app can and can't read, permission levels, real-vehicle reports |
| `gm-platform` | GM head-unit hardware and builds, install routes, phone projection | Build-tag decoding (`SQBR`, `UQBM`), no adb, GM emulator images |
| `play-distribution` | Play Console tracks, review rules, template limits | No review on internal testing, never promoting past it, template limits |
| `hardware-connectivity` | Adapters, adb, ways to reach a dongle from the head unit | No owner-usable adb, gateway isolation, adapter sleep figures |

## Superseded map

| Topic in the notes | Where | What is true now |
|---|---|---|
| The dedicated Automotive OS track is required, or the only path | `play-distribution.md` summary, [pd-3], [pd-6], Recommendations; `gm-platform.md` Recommendations; `gm-platform.tables.md` T4 and T5; `gm-platform.verify.md` opening; `hardware-connectivity.verify.md` opening and [hw-1]; `vhal-properties.verify.md` Play steps | That track stayed Inactive on this account and can't be undone for a package. The regular internal-testing track is what works. `play-distribution.verify.md`, opening list and [pd-3], is the one note that warned about this. |
| `android.hardware.type.automotive` `required=true` | `play-distribution.md` build settings; `play-distribution.tables.md` T4; `gm-platform.md` and `vhal-properties.md` Recommendations | The manifest declares it `required=false`, which the regular track needs. |
| Target API advice (34, or "just target 36") | `hardware-connectivity.md` Recommendations; `play-distribution.md` [pd-17] and build settings | No change is called for. The app stays at targetSdk 35 with compileSdk 36; 0.4.0 at targetSdk 35 uploaded and ran on the regular track. |
| Dense dashboard in Park only, distraction-optimized glance only | `play-distribution.md` UI plan; `gm-platform.md` and `vhal-properties.md` Recommendations | Overtaken on 2026-11-03: the main screen is distraction-optimized with a locked-down moving mode, on internal testing only, with Battery Glance as the fallback until the owner had it removed on 2026-11-07. The owner confirmed it stays up in Drive. |
| Buy an OBDLink CX, EX, PEAK or WiCAN; a BLE, USB or Wi-Fi architecture; a connectivity probe app | `hardware-connectivity.md` summary, [hw-8], [hw-13], Recommendations; `hardware-connectivity.tables.md` Tables 1, 3 and 8; `ultium-uds.md` Recommendations | The OBDLink MX+ over Bluetooth Classic is the only adapter, and it works from the head unit. Wi-Fi adapters are ruled out by the owner. The laptop has no CAN interface, and the probe dropped SocketCAN. Nothing needs buying. |
| Optional `0x19` read-DTC | `hardware-connectivity.md` Recommendations | Refused by the allowlist. DTC reads are OBD `03`/`07`/`0A` only. |
| `40:434F`, `4127`, `4124` as a temperature fallback | `ultium-uds.md` Recommendations; `ultium-uds.tables.md` Tables 3 and 9 | `434F` reads 60 °C with a 17 °C pack on this truck, and all three are never displayed. Compare temperatures with outside temp and coolant. |
| `2AF5` as pack-wide statistics | `ultium-uds.md` Recommendations | On this truck `2AF5` covers half B and `2AF6` covers half A. Series count from `2885 ÷ 2AF5 average` still works, because each half sums to the pack voltage. |
| Ah by coulomb counting | `ultium-uds.md` Recommendations | Not built. The app shows a nominal estimate. |
| Pack size and addressing hypotheses, such as 20 modules on a 2024 truck | `ultium-uds.md` open questions; `ultium-uds.verify.md` new facts and missed questions | Answered for this truck: 24 modules, 192 groups, `CB` at both priorities, `1D:2414` absent. See `docs/session1-results.md`. |

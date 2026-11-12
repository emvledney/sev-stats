# OBDII / EOBD GM based Ultium EV vehicles (2026-n.d.)

197 sensor definitions extracted from the supplied APK.

These are Car Scanner's definitions, not a statement that every reading is supported or validated on your truck.

Offsets are zero-based within the DID data, after ISO-TP reassembly and removal of `62` and the two DID bytes.
`u16be(0)` means an unsigned 16-bit big-endian value at data offset 0; `s` means signed.
For Formula rows, `A`, `B`, `C`, ... are data bytes 0, 1, 2, ...; `@&` is bitwise AND.
The raw scaling is preserved as `raw * multiplier / divider + addend`; units are the profile's native units.
Formula rows use their formula directly; their ByteSet offset/length fields are not decoding instructions.

Full adapter setup/restore strings and original sensor fields are in the adjacent JSON/CSV files.
See [the investigation report](README.md) for truck-specific findings and source anomalies.

## CB: BECM — battery energy control module

Request CAN ID `14DACBF1`; response CAN ID `142AF1CB`.

| ID | DID | Name | Formula | Unit |
|---|---|---|---|---|
| 16240045 | `2AF5` | [BECM] Average Cell Voltage | `u16be(0) * 1 / 10000 + 0` | V |
| 16240046 | `2AF5` | [BECM] Max Cell Voltage | `u16be(4) * 1 / 10000 + 0` | V |
| 16240047 | `2AF5` | [BECM] Min Cell Voltage | `u16be(2) * 1 / 10000 + 0` | V |
| 16240048 | `2AF7` | [BECM] Battery Pack Voltage | `u16be(0) * 0.01 / 1 + 0` | V |
| 16240049 | `2AF7` | [BECM] Battery Pack 2 Voltage | `u16be(2) * 0.01 / 1 + 0` | V |
| 16240000 | `2AF7` | [BECM] Battery Pack 1+2 Sum Voltage | `(A*256+B)*0.01+(C*256+D)*0.01` | V |
| 16240050 | `2AF8` | [BECM] Battery Pack Current | `u24be(0) * 2 / 10000 + -2000` | A |
| 16240051 | `2AF8` | [BECM] Battery Pack 2 Current | `u24be(3) * 2 / 10000 + -2000` | A |
| 16240052 | `2AF8` | [BECM] Battery Pack Total Current | `u24be(6) * 2 / 10000 + -2000` | A |
| 16240053 | `1C43` | [BECM] Drive Motor Control Module Coolant Temperature | `u8be(0) * 1 / 1 + -40` | degC |
| 16240054 | `1C43` | [BECM] Drive Motor Control Module Coolant Temperature | `u8be(0) * 1 / 1 + -40` | degC |
| 16240055 | `2A08` | [BECM] AC Charger Coupler Temperature | `u8be(0) * 1 / 1 + -40` | degC |
| 16240056 | `2A08` | [BECM] AC Charger Coupler Temperature 4 | `u8be(3) * 1 / 1 + -40` | degC |
| 16240057 | `2A08` | [BECM] DC Charger Coupler Temperature | `u8be(4) * 1 / 1 + -40` | degC |
| 16240058 | `2A08` | [BECM] DC Charger Coupler Temperature 2 | `u8be(5) * 1 / 1 + -40` | degC |
| 16240059 | `4E68` | [BECM] Battery Pack Coolant Temperature | `u8be(0) * 1 / 1 + -40` | degC |
| 16240060 | `2708` | [BECM] A/C Compressor Motor Current | `(A@&127)-1` | A |
| 16240061 | `270D` | [BECM] A/C Compressor Motor Speed Actual | `u16be(0) * 25 / 100 + -1000` | rpm |
| 16240062 | `270C` | [BECM] A/C Compressor Motor Speed Commanded | `u16be(0) * 25 / 100 + -1000` | rpm |
| 16240063 | `270C` | [BECM] A/C Compressor Motor Voltage | `u8be(0) * 250 / 100 + -12` | V |
| 16240064 | `2984` | [BECM] A/C Refrigerant High Side Pressure | `u16be(0) * 541.7 / 10000 + 0` | kPa |
| 16240065 | `2985` | [BECM] A/C Refrigerant High Side Pressure Sensor Voltage | `u8be(0) * 5.5 / 255 + 0` | V |
| 16240066 | `2988` | [BECM] A/C Refrigerant High Side Pressure 2 | `u16be(0) * 541.7 / 10000 + 0` | kPa |
| 16240067 | `2989` | [BECM] A/C Refrigerant High Side Pressure 2 Sensor Voltage | `u8be(0) * 5.5 / 255 + 0` | V |
| 16240068 | `2986` | [BECM] A/C Refrigerant Low Side Pressure | `u8be(0) * 5 / 1 + 0` | kPa |
| 16240069 | `2987` | [BECM] A/C Refrigerant Low Side Pressure Sensor Voltage | `u8be(0) * 5.5 / 255 + 0` | V |
| 16240070 | `0046` | [BECM] Ambient temperature sensor | `u8be(0) * 1 / 1 + -40` | degC |
| 16240071 | `2770` | [BECM] A/C Refrigerant Temperature Sensor 1 | `u8be(0) * 1 / 1 + -40` | degC |
| 16240072 | `2773` | [BECM] A/C Refrigerant Temperature Sensor 2 | `u8be(0) * 1 / 1 + -40` | degC |
| 16240073 | `2776` | [BECM] A/C Refrigerant Temperature Sensor 3 | `u8be(0) * 1 / 1 + -40` | degC |
| 16240074 | `2779` | [BECM] A/C Refrigerant Temperature Sensor 4 | `u8be(0) * 1 / 1 + -40` | degC |
| 16240075 | `277C` | [BECM] A/C Refrigerant Temperature Sensor 5 | `u8be(0) * 1 / 1 + -40` | degC |
| 16240076 | `277F` | [BECM] A/C Refrigerant Temperature Sensor 6 | `u8be(0) * 1 / 1 + -40` | degC |
| 16240077 | `2782` | [BECM] A/C Refrigerant Temperature Sensor 7 | `u8be(0) * 1 / 1 + -40` | degC |
| 16240078 | `2785` | [BECM] A/C Refrigerant Temperature Sensor 8 | `u8be(0) * 1 / 1 + -40` | degC |
| 16240079 | `2ADB` | [BECM] Chiller Power Feedback | `u8be(0) * 31 / 255 + 0` | kW |
| 16240080 | `2ADA` | [BECM] Chiller Power Requested | `u8be(0) * 31 / 255 + 0` | kW |
| 16240081 | `2709` | [BECM] Electric A/C Control Module Temperature | `u8be(0) * 1 / 1 + -60` | degC |
| 16240082 | `270B` | [BECM] Electric A/C Control Module Temperature 2 | `u8be(0) * 1 / 1 + -60` | degC |
| 16240083 | `270A` | [BECM] Electric A/C Control Module Output Driver Temperature | `u8be(0) * 1 / 1 + -60` | degC |
| 16240084 | `270B` | [BECM] Electric A/C Control Module Motor Current | `u8be(0) * 76.5 / 255 + -1.5` | A |
| 16240085 | `3812` | [BECM] Cooling Fan Command | `u8be(0) * 100 / 255 + 0` | % |
| 16240086 | `27BF` | [BECM] Charge Cycle Energy Gained - Regenerative Braking | `u24be(0) * 1000 / 10000 + 0` | km |
| 16240087 | `27CC` | [BECM] Charge Cycle Energy Gained - Total | `u16be(0) * 1 / 1 + 0` | km |
| 16240088 | `27BA` | [BECM] Charge Cycle Energy Usage - Driving and Accessories | `u24be(0) * 100 / 10000 + 0` | kWh |
| 16240089 | `27B4` | [BECM] Charge Cycle Energy Usage - Driving and Accessories (distance) | `u24be(0) * 1000 / 10000 + 0` | km |
| 16240090 | `27B0` | [BECM] Charge Cycle Energy Usage - Driving and Accessories (%) | `u8be(0) * 100 / 255 + 0` | % |
| 16240091 | `27B3` | [BECM] Charge Cycle Energy Usage - Remote Start (%) | `u8be(0) * 100 / 255 + 0` | % |
| 16240092 | `27B3` | [BECM] Charge Cycle Energy Usage - Remote Start | `u24be(0) * 100 / 10000 + 0` | kWh |
| 16240093 | `27B7` | [BECM] Charge Cycle Energy Usage - Remote Start (distance) | `u24be(0) * 1000 / 10000 + 0` | km |
| 16240094 | `27B1` | [BECM] Charge Cycle Energy Usage - Thermal Management (%) | `u8be(0) * 100 / 255 + 0` | % |
| 16240095 | `27BB` | [BECM] Charge Cycle Energy Usage - Thermal Management | `u24be(0) * 100 / 10000 + 0` | kWh |
| 16240096 | `27B5` | [BECM] Charge Cycle Energy Usage - Thermal Management (distance) | `u24be(0) * 1000 / 10000 + 0` | km |
| 16240097 | `27B9` | [BECM] Charge Cycle Energy Usage - Total | `u24be(0) * 1000 / 10000 + 0` | kWh |
| 16240098 | `27B8` | [BECM] Charge Cycle Energy Usage - Total (distance) | `u24be(0) * 1000 / 10000 + 0` | km |
| 16240099 | `27C0` | [BECM] Distance Traveled Since Last Full Charge | `u24be(0) * 1000 / 10000 + 0` | km |
| 16240100 | `27BE` | [BECM] Time Since Last Full Charge | `u16be(0) * 1 / 1 + 0` | min |
| 16240101 | `27CD` | [BECM] Energy Gain Rate - This Charge Cycle | `u16be(0) * 1 / 1 + 0` | km/h |
| 16240102 | `27C7` | [BECM] Battery Energy Storage Remaining (distance) | `u24be(0) * 156.2 / 10000 + 0` | km |
| 16240103 | `27C6` | [BECM] Battery Energy Storage Remaining (%) | `u16be(0) * 100 / 65535 + 0` | % |
| 16240104 | `27AE` | [BECM] Battery Energy Storage Capability | `u16be(0) * 100 / 10000 + 0` | kWh |
| 16240105 | `27AF` | [BECM] Battery Energy Storage Remaining | `u16be(0) * 100 / 10000 + 0` | kWh |
| 16240106 | `2AF1` | [BECM] Battery Module 1 Temperature 1 | `u8be(0) * 1 / 1 + -80` | degC |
| 16240107 | `2AF1` | [BECM] Battery Module 1 Temperature 2 | `u8be(1) * 1 / 1 + -80` | degC |
| 16240108 | `2AF1` | [BECM] Battery Module 2 Temperature 1 | `u8be(2) * 1 / 1 + -80` | degC |
| 16240109 | `2AF1` | [BECM] Battery Module 2 Temperature 2 | `u8be(3) * 1 / 1 + -80` | degC |
| 16240110 | `2AF1` | [BECM] Battery Module 3 Temperature 1 | `u8be(4) * 1 / 1 + -80` | degC |
| 16240111 | `2AF1` | [BECM] Battery Module 3 Temperature 2 | `u8be(5) * 1 / 1 + -80` | degC |
| 16240112 | `2AF1` | [BECM] Battery Module 4 Temperature 1 | `u8be(6) * 1 / 1 + -80` | degC |
| 16240113 | `2AF1` | [BECM] Battery Module 4 Temperature 2 | `u8be(7) * 1 / 1 + -80` | degC |
| 16240114 | `2AF1` | [BECM] Battery Module 5 Temperature 1 | `u8be(8) * 1 / 1 + -80` | degC |
| 16240115 | `2AF1` | [BECM] Battery Module 5 Temperature 2 | `u8be(9) * 1 / 1 + -80` | degC |
| 16240116 | `2AF1` | [BECM] Battery Module 6 Temperature 1 | `u8be(10) * 1 / 1 + -80` | degC |
| 16240117 | `2AF1` | [BECM] Battery Module 6 Temperature 2 | `u8be(11) * 1 / 1 + -80` | degC |
| 16240118 | `2AF1` | [BECM] Battery Module 7 Temperature 1 | `u8be(12) * 1 / 1 + -80` | degC |
| 16240119 | `2AF1` | [BECM] Battery Module 7 Temperature 2 | `u8be(13) * 1 / 1 + -80` | degC |
| 16240120 | `2AF1` | [BECM] Battery Module 8 Temperature 1 | `u8be(14) * 1 / 1 + -80` | degC |
| 16240121 | `2AF1` | [BECM] Battery Module 8 Temperature 2 | `u8be(15) * 1 / 1 + -80` | degC |
| 16240122 | `2AF1` | [BECM] Battery Module 9 Temperature 1 | `u8be(16) * 1 / 1 + -80` | degC |
| 16240123 | `2AF1` | [BECM] Battery Module 9 Temperature 2 | `u8be(17) * 1 / 1 + -80` | degC |
| 16240124 | `2AF1` | [BECM] Battery Module 10 Temperature 1 | `u8be(18) * 1 / 1 + -80` | degC |
| 16240125 | `2AF1` | [BECM] Battery Module 10 Temperature 2 | `u8be(19) * 1 / 1 + -80` | degC |
| 16240126 | `2AF1` | [BECM] Battery Module 11 Temperature 1 | `u8be(20) * 1 / 1 + -80` | degC |
| 16240127 | `2AF1` | [BECM] Battery Module 11 Temperature 2 | `u8be(21) * 1 / 1 + -80` | degC |
| 16240128 | `2AF1` | [BECM] Battery Module 12 Temperature 1 | `u8be(22) * 1 / 1 + -80` | degC |
| 16240129 | `2AF1` | [BECM] Battery Module 12 Temperature 2 | `u8be(23) * 1 / 1 + -80` | degC |
| 16240130 | `2AF3` | [BECM] Battery 1 Max Temperature | `u8be(2) * 1 / 1 + -80` | degC |
| 16240131 | `2AF4` | [BECM] Battery 2 Max Temperature | `u8be(2) * 1 / 1 + -80` | degC |
| 16240132 | `2AF3` | [BECM] Battery 1 Min Temperature | `u8be(1) * 1 / 1 + -80` | degC |
| 16240133 | `2AF4` | [BECM] Battery 2 Min Temperature | `u8be(1) * 1 / 1 + -80` | degC |
| 16240134 | `2AF3` | [BECM] Battery 1 Average Temperature | `u8be(0) * 1 / 1 + -80` | degC |
| 16240135 | `2AF4` | [BECM] Battery 2 Average Temperature | `u8be(0) * 1 / 1 + -80` | degC |
| 16240136 | `2B44` | [BECM] Battery Total Energy Storage Capacity | `u16be(0) * 1 / 100 + 0` | Ah |
| 16240137 | `2B44` | [BECM] Battery Total Energy Storage Capacity Deviation | `u8be(2) * 1 / 1 + 0` | Ah |
| 16240138 | `27AE` | [BECM] Battery Total Energy Storage Capability | `u8be(2) * 100 / 10000 + 0` | kWh |
| 16240139 | `27AF` | [BECM] Battery Total Energy Storage Remaining kWh | `u16be(0) * 100 / 10000 + 0` | kWh |
| 16240140 | `27C7` | [BECM] Battery Total Energy Storage Remaining Distance | `u24be(0) * 156.25 / 10000 + 0` | km |
| 16240141 | `27C6` | [BECM] Battery Total Energy Storage Remaining % | `u16be(0) * 100 / 65535 + 0` | % |
| 16240142 | `27C0` | [BECM] Distance since last full charge | `u24be(0) * 1000 / 10000 + 0` | km |
| 16240143 | `2B43` | [BECM] Battery Pack 1 State of Charge | `u8be(0) * 100 / 255 + 0` | % |
| 16240144 | `2AF5` | [BECM] Battery Pack Average Cell Voltage | `u16be(0) * 1 / 10000 + 0` | V |
| 16240145 | `2ADA` | [BECM] Chiller Power Requested | `u8be(0) * 31 / 255 + 0` | kW |
| 16240146 | `2ADB` | [BECM] Chiller Power Feedback | `u8be(0) * 31 / 255 + 0` | kW |
| 16240147 | `0046` | [BECM] Ambient Air Temperature | `u8be(0) * 1 / 1 + -40` | degC |
| 16240148 | `2708` | [BECM] A/C Compressor Motor Current | `u8be(0) * 126 / 255 + 0` | A |
| 16240149 | `2708` | [BECM] A/C Compressor Motor Voltage | `u8be(0) * 637 / 255 + -12` | V |
| 16240150 | `270C` | [BECM] A/C Compressor Motor Speed Command | `u16be(0) * 2500 / 10000 + -1000` | rpm |
| 16240151 | `270D` | [BECM] A/C Compressor Motor Speed | `u16be(0) * 2500 / 10000 + -1000` | rpm |
| 16240152 | `2986` | [BECM] A/C Low Side Pressure Sensor | `u8be(0) * 5 / 1 + 0` | kPa |
| 16240153 | `2987` | [BECM] A/C Low Side Pressure Sensor % | `u8be(0) * 100 / 255 + 0` | % |
| 16240154 | `2984` | [BECM] A/C High Side Pressure Sensor 1 | `u16be(0) * 541.7 / 10000 + 0` | kPa |
| 16240155 | `2985` | [BECM] A/C High Side Pressure Sensor 1 % | `u8be(0) * 100 / 255 + 0` | % |
| 16240156 | `2988` | [BECM] A/C High Side Pressure Sensor 2 | `u16be(0) * 541.7 / 10000 + 0` | kPa |
| 16240157 | `2989` | [BECM] A/C High Side Pressure Sensor 2 % | `u8be(0) * 100 / 255 + 0` | % |
| 16240158 | `2770` | [BECM] A/C Temperature Sensor 1 | `u8be(0) * 1 / 1 + -40` | degC |
| 16240159 | `2773` | [BECM] A/C Temperature Sensor 2 | `u8be(0) * 1 / 1 + -40` | degC |
| 16240160 | `2776` | [BECM] A/C Temperature Sensor 3 | `u8be(0) * 1 / 1 + -40` | degC |
| 16240161 | `2779` | [BECM] A/C Temperature Sensor 4 | `u8be(0) * 1 / 1 + -40` | degC |
| 16240162 | `277C` | [BECM] A/C Temperature Sensor 5 | `u8be(0) * 1 / 1 + -40` | degC |
| 16240163 | `277F` | [BECM] A/C Temperature Sensor 6 | `u8be(0) * 1 / 1 + -40` | degC |
| 16240164 | `2785` | [BECM] A/C Temperature Sensor 8 | `u8be(0) * 1 / 1 + -40` | degC |
| 16240165 | `2709` | [BECM] Electric A/C Compressor Control Module Temperature | `u8be(0) * 1 / 1 + -60` | degC |
| 16240166 | `2707` | [BECM] Electric A/C Compressor Control Module Motor Current | `u8be(0) * 30 / 100 + -1.5` | A |
| 16240167 | `270A` | [BECM] Electric A/C Compressor Control Module Output Driver Temperature | `u8be(0) * 1 / 1 + -60` | degC |
| 16240168 | `2BFF` | [BECM] Coolant Heater Voltage Sensor | `u8be(0) * 637 / 255 + -12` | V |
| 16240169 | `297F` | [BECM] Battery Charger Phase W Current | `u8be(0) * 0.5 / 1 + 0` | A |
| 16240170 | `297A` | [BECM] Battery Charger Input Voltage Sensor 1 | `u8be(0) * 383 / 255 + 0` | V |
| 16240171 | `297B` | [BECM] Battery Charger Input Voltage Sensor 2 | `u8be(0) * 383 / 255 + 0` | V |
| 16240172 | `297C` | [BECM] Battery Charger Input Voltage Sensor 3 | `u8be(0) * 383 / 255 + 0` | V |
| 16240173 | `297C` | [BECM] Battery Charger AC Input Power | `u8be(0) * 25.5 / 255 + 0` | kW |
| 16240174 | `2982` | [BECM] Battery Charger AC Input Power | `u8be(0) * 127.5 / 255 + 0` | A |
| 16240175 | `2983` | [BECM] Battery Charger DC Voltage Command | `u8be(0) * 5 / 1 + 0` | V |
| 16240176 | `2979` | [BECM] Battery Charger DC Current | `u8be(0) * 0.5 / 1 + 0` | A |
| 16240177 | `5401` | [BECM] Battery Charger DC Power | `u8be(0) * 15 / 255 + 0` | kW |
| 16240178 | `29CC` | [BECM] Battery Charger 2 DC Voltage Command | `u8be(0) * 1275 / 255 + 0` | V |
| 16240179 | `29C2` | [BECM] Battery Charger 2 DC Current | `u8be(0) * 72.5 / 255 + -55` | A |
| 16240180 | `29CB` | [BECM] Battery Charger 2 AC Current Command | `u8be(0) * 127.5 / 255 + 0` | A |
| 16240181 | `29C3` | [BECM] Battery Charger 2 Input Voltage Sensor 1 | `u8be(0) * 383 / 255 + 0` | V |
| 16240182 | `29C4` | [BECM] Battery Charger 2 Input Voltage Sensor 2 | `u8be(0) * 383 / 255 + 0` | V |
| 16240183 | `29C5` | [BECM] Battery Charger 2 Input Voltage Sensor 3 | `u8be(0) * 383 / 255 + 0` | V |
| 16240184 | `29C5` | [BECM] Long Term Charge Power Limit | `u16be(0) * 100 / 10000 + 0` | kW |
| 16240185 | `4676` | [BECM] Long Term Discharge Power Limit | `u16be(0) * 100 / 10000 + 0` | kW |
| 16240186 | `540B` | [BECM] Battery Charger Thermal Power Limit | `u16be(0) * 1 / 1 + 0` | kW |
| 16240187 | `2A35` | [BECM] Battery Pack Positive Half Pack Voltage | `s16be(0) * 312.5 / 10000 + 0` | V |
| 16240188 | `2A36` | [BECM] Battery Pack Negative Half Pack Voltage | `s16be(0) * 312.5 / 10000 + 0` | V |

## 17: DMCM1 — drive motor control module 1

Request CAN ID `14DA17F1`; response CAN ID `142AF117`.

| ID | DID | Name | Formula | Unit |
|---|---|---|---|---|
| 16240001 | `131F` | [DMCM1] Accelerator pedal position | `u16be(0) * 1280 / 65535 + 0` | % |
| 16240002 | `2429` | [DMCM1] Axle Torque | `u16be(0) * 1 / 1 + -22534` | Nm |
| 16240003 | `2428` | [DMCM1] Axle Torque Commanded | `u16be(0) * 1 / 1 + -22534` | Nm |
| 16240004 | `242D` | [DMCM1] Axle Torque Immediate | `u16be(0) * 1 / 1 + -22534` | Nm |
| 16240005 | `2883` | [DMCM1] Drive Motor Current | `s16be(0) * 5 / 100 + 0` | A |
| 16240006 | `28A7` | [DMCM1] Drive Motor Current Commanded | `s16be(0) * 4 / 100 + 0` | A |
| 16240007 | `34DE` | [DMCM1] Drive Motor Magnet Strenght | `u8be(0) * 1 / 1 + 0` | % |
| 16240008 | `284D` | [DMCM1] Drive Motor Magnet Position | `u8be(0) * 2 / 1 + 0` | deg |
| 16240009 | `28AF` | [DMCM1] Drive Motor Speed | `u16be(0) * 3 / 1 + -30000` | rpm |
| 16240010 | `28CB` | [DMCM1] Drive Motor Temperature | `u8be(0) * 1 / 1 + -40` | degC |
| 16240011 | `28AB` | [DMCM1] Drive Motor Torque | `s16be(0) * 1250 / 10000 + 0` | Nm |
| 16240012 | `28AA` | [DMCM1] Drive Motor Torque Commanded | `s16be(0) * 1250 / 10000 + 0` | Nm |
| 16240013 | `2434` | [DMCM1] Driver Requested Axle Torque | `u16be(0) * 1 / 1 + -22534` | Nm |
| 16240014 | `242B` | [DMCM1] Regenerative Braking Torque Achieved | `u8be(0) * 32 / 1 + 0` | Nm |
| 16240015 | `000D` | [DMCM1] Vehicle speed | `u8be(0) * 1 / 1 + 0` | km/h |
| 16240016 | `2AD8` | [DMCM1] Backup system 1 Supply Circuit | `u8be(0) * 1 / 10 + 0` | V |
| 16240017 | `2AD9` | [DMCM1] Backup system 2 Supply Circuit | `u8be(0) * 1 / 10 + 0` | V |
| 16240018 | `2885` | [DMCM1] Drive Motor Inverter Supply Voltage | `u8be(0) * 100 / 10000 + 0` | V |
| 16240019 | `1C26` | [DMCM1] Drive Motor Inverter Temperature Sensor 1 | `u8be(0) * 1 / 1 + -40` | degC |
| 16240020 | `1C28` | [DMCM1] Drive Motor Inverter Temperature Sensor 3 | `u8be(0) * 1 / 1 + -40` | degC |
| 16240021 | `1C2A` | [DMCM1] Drive Motor Inverter Temperature Sensor 5 | `u8be(0) * 1 / 1 + -40` | degC |
| 16240022 | `2B33` | [DMCM1] Drive Motor Phase V Current | `s16be(0) * 800 / 10000 + 0` | A |
| 16240023 | `2B34` | [DMCM1] Drive Motor Phase W Current | `s16be(0) * 800 / 10000 + 0` | A |
| 16240024 | `28DE` | [DMCM1] Drive Motor Phase U Current | `s16be(0) * 400 / 10000 + 0` | A |
| 16240025 | `27E8` | [DMCM1] Drive Unit Fluid Temperature Sensor | `u8be(0) * 1 / 1 + -40` | degC |
| 16240026 | `33E5` | [DMCM1] Battery Voltage | `u8be(0) * 25.5 / 255 + 0` | V |
| 16240027 | `50A1` | [DMCM1] Battery Voltage Circuit 1 | `u16be(0) * 1 / 1000 + 0` | V |
| 16240028 | `50A2` | [DMCM1] Battery Voltage Circuit 2 | `u16be(0) * 1 / 1000 + 0` | V |
| 16240029 | `59FC` | [DMCM1] Battery Voltage Circuit 3 | `u16be(0) * 1 / 1000 + 0` | V |

## 1D: DMCM2 — drive motor control module 2

Request CAN ID `14DA1DF1`; response CAN ID `142AF11D`.

| ID | DID | Name | Formula | Unit |
|---|---|---|---|---|
| 16240030 | `2883` | [DMCM2] Drive Motor Current | `s16be(0) * 5 / 100 + 0` | V |
| 16240031 | `28A7` | [DMCM2] Drive Motor Current Command | `s16be(0) * 4 / 100 + 0` | V |
| 16240032 | `34DE` | [DMCM2] Drive Motor Magnets Strength | `s8be(0) * 1 / 1 + 0` | % |
| 16240033 | `28AF` | [DMCM2] Drive Motor Speed | `u16be(0) * 3 / 1 + -30000` | rpm |
| 16240034 | `28CB` | [DMCM2] Drive Motor Temperature | `u8be(0) * 1 / 1 + -40` | degC |
| 16240035 | `28AB` | [DMCM2] Drive Motor Torque | `s16be(0) * 1250 / 10000 + 0` | Nm |
| 16240036 | `28AA` | [DMCM2] Drive Motor Torque Command | `s16be(0) * 1250 / 10000 + 0` | Nm |
| 16240037 | `000D` | [DMCM2] Vehicle speed | `u8be(0) * 1 / 1 + 0` | km/h |
| 16240038 | `1C26` | [DMCM2] Drive Motor Inverter Temperature Sensor 1 | `u8be(0) * 1 / 1 + -40` | degC |
| 16240039 | `1C28` | [DMCM2] Drive Motor Inverter Temperature Sensor 3 | `u8be(0) * 1 / 1 + -40` | degC |
| 16240040 | `1C2A` | [DMCM2] Drive Motor Inverter Temperature Sensor 5 | `u8be(0) * 1 / 1 + -40` | degC |
| 16240041 | `27E8` | [DMCM2] Drive Unit Fluid Temperature Sensor | `u8be(0) * 1 / 1 + -40` | degC |
| 16240042 | `2AD8` | [DMCM2] Backup system 1 Supply Circuit | `u8be(0) * 1 / 10 + 0` | V |
| 16240043 | `2885` | [DMCM2] Drive Motor Inverter Supply Voltage | `u8be(0) * 100 / 10000 + 0` | V |
| 16240044 | `33E5` | [DMCM2] Battery Voltage | `u8be(0) * 25.5 / 255 + 0` | V |

## 28: EBCM — brake control module

Request CAN ID `14DA28F1`; response CAN ID `142AF128`.

| ID | DID | Name | Formula | Unit |
|---|---|---|---|---|
| 16240189 | `4A7A` | [ABS] Front left wheel speed | `u8be(0) * 1 / 1 + 0` | km/h |
| 16240190 | `4A7A` | [ABS] Front right wheel speed | `u8be(1) * 1 / 1 + 0` | km/h |
| 16240191 | `4A7A` | [ABS] Rear left wheel speed | `u8be(2) * 1 / 1 + 0` | km/h |
| 16240192 | `4A7A` | [ABS] Rear right wheel speed | `u8be(3) * 1 / 1 + 0` | km/h |
| 16240193 | `4C2D` | [ABS] Steering wheel angle | `s16be(0) * 220 / 10000 + 0` | deg |
| 16240194 | `4C2F` | [ABS] Lateral acceleration | `s16be(0) * 156.2 / 10000 + 0` | m/s2 |
| 16240195 | `4C30` | [ABS] Longtitudinal acceleration | `s16be(0) * 156.2 / 10000 + 0` | m/s2 |
| 16240196 | `4A7C` | [ABS] Brake pressure | `s8be(0) * 100 / 1 + -1000` | kPa |


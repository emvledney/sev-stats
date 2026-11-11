"""GM Global B / Ultium diagnostic addressing.

Ultium trucks answer at the OBD port only on 29-bit CAN (ISO 15765-4, 500k).
A module with address ``ecu`` is reached at one of two priorities:

  priority 0x18 (ISO normal-fixed):  request 0x18DA<ecu>F1 -> response 0x18DAF1<ecu>
  priority 0x14 (GM variant):        request 0x14DA<ecu>F1 -> response 0x142AF1<ecu>

Modules differ in which priority they answer (the Silverado EV battery module
0xCB answers at 0x14; the body controller 0x40 only at 0x18). Standard OBD
requests are broadcast on 0x18DB33F1 and answered on 0x18DAF1<ecu>.
"""

from __future__ import annotations

FUNCTIONAL_29 = 0x18DB33F1
FUNCTIONAL_RESP_29 = (0x18DAF100, 0x1FFFFF00)  # id, mask
TESTER = 0xF1


def ids(ecu: int, pri: int) -> tuple[int, int]:
    """(request id, response id) for module ``ecu`` at priority 0x14 or 0x18."""
    if not 0 <= ecu <= 0xFF:
        raise ValueError(f"ECU address must be one byte, got {ecu:#x}")
    tx = (pri << 24) | 0xDA0000 | (ecu << 8) | TESTER
    if pri == 0x18:
        rx = 0x18DAF100 | ecu
    elif pri == 0x14:
        rx = 0x142AF100 | ecu
    else:
        raise ValueError("priority must be 0x14 or 0x18")
    return tx, rx

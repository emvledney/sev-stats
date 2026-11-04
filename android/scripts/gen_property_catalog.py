#!/usr/bin/env python3
"""Regenerates app/src/main/kotlin/dev/silveradostats/vehicle/AospPropertyCatalog.kt.

Usage: python3 gen_property_catalog.py VehiclePropertyIds.java Car.java > AospPropertyCatalog.kt
Inputs: AOSP packages/services/Car car-lib sources (android/car/VehiclePropertyIds.java, android/car/Car.java).
INVALID and INFO_VIN are skipped on purpose.
"""
import re,sys
vpi=open(sys.argv[1]).read(); carj=open(sys.argv[2]).read()
permmap=dict(re.findall(r'public static final String (PERMISSION_[A-Z0-9_]+)\s*=\s*"([^"]+)";',carj))
blocks=re.findall(r'\*/\s*((?:@[^;]*?\s+)*)public static final int ([A-Z0-9_]+) = (-?\d+);',vpi,re.S)
def extract(ann, tag):
    i=ann.find(tag)
    if i<0: return None
    j=i+len(tag); depth=1
    while depth and j<len(ann):
        if ann[j]=='(': depth+=1
        elif ann[j]==')': depth-=1
        j+=1
    return ann[i+len(tag):j-1]
def perms(s):
    out=[]
    for name in re.findall(r'(PERMISSION_[A-Z0-9_]+|ACCESS_FINE_LOCATION)',s):
        if name=='ACCESS_FINE_LOCATION': out.append('android.permission.ACCESS_FINE_LOCATION')
        else: out.append(permmap[name])
    return out
rows=[]
for ann,name,val in blocks:
    if name in ('INVALID','INFO_VIN'): continue
    v=int(val)&0xffffffff
    r=extract(ann,'@RequiresPermission.Read(')
    if r is None:
        # plain annotation (not .Write)
        a2=re.sub(r'@RequiresPermission\.Write\(','@XW(',ann)
        r=extract(a2,'@RequiresPermission(')
    rp=perms(r) if r is not None else []
    rows.append((v,name,rp))
rows.sort()
print("// GENERATED from AOSP car-lib VehiclePropertyIds.java (main / android-17.0.0_r1).")
print("// Do not edit by hand. INFO_VIN is deliberately omitted (never read, never shown).")
print("package dev.silveradostats.vehicle\n")
print("/** Name and read-permission (any of) for every public AOSP vehicle property id. */")
print("internal object AospPropertyCatalog {")
print("  private const val C = \"android.car.permission.\"\n")
print("  /** id -> (name, read permissions; empty = not readable by apps). */")
print("  val entries: Map<Int, Pair<String, List<String>>> = mapOf(")
for v,n,rp in rows:
    ps=", ".join(('C + "%s"'%p[len("android.car.permission."):]) if p.startswith("android.car.permission.") else '"%s"'%p for p in rp)
    print('    0x%08X to (%s to listOf(%s)),'%(v,'"%s"'%n,ps))
print("  )")
print("}")

#!/usr/bin/env python3
"""Compiles a text AndroidManifest.xml into Android's binary XML format.

Only the attributes this project uses are supported. The package name,
version and SDK levels are injected the way the Android Gradle plugin does.
Usage: axml.py IN.xml OUT.bin --package P --version-code N --version-name S
               --min-sdk N --target-sdk N
"""
import argparse
import struct
import xml.etree.ElementTree as ET

ANDROID_NS = "http://schemas.android.com/apk/res/android"

# attribute name -> (resource id, kind)
ATTRS = {
    "theme": (0x01010000, "ref"),
    "label": (0x01010001, "string"),
    "icon": (0x01010002, "ref"),
    "name": (0x01010003, "string"),
    "exported": (0x01010010, "bool"),
    "configChanges": (0x0101001F, "flags"),
    "minSdkVersion": (0x0101020C, "int"),
    "versionCode": (0x0101021B, "int"),
    "versionName": (0x0101021C, "string"),
    "targetSdkVersion": (0x01010270, "int"),
    "foregroundServiceType": (0x01010599, "fgs"),
}
# framework resource references used by the manifest (from android.R)
REFS = {
    "@android:style/Theme.Material.Light.DarkActionBar": 0x01030238,
    "@android:mipmap/sym_def_app_icon": 0x010D0000,
}
FGS_TYPES = {"dataSync": 0x1}
CONFIG_FLAGS = {
    "keyboardHidden": 0x0020, "orientation": 0x0080, "screenLayout": 0x0100,
    "screenSize": 0x0400, "smallestScreenSize": 0x0800,
}
TYPE_REFERENCE, TYPE_STRING, TYPE_INT_DEC, TYPE_INT_HEX, TYPE_BOOLEAN = 0x01, 0x03, 0x10, 0x11, 0x12


def encode(root):
    strings = []      # attribute names with resource ids come first (resource map order)
    index = {}

    def s(v):
        if v not in index:
            index[v] = len(strings)
            strings.append(v)
        return index[v]

    def split(tag):
        if tag.startswith("{"):
            ns, local = tag[1:].split("}")
            return ns, local
        return None, tag

    # Pass 1: attribute names with resource ids, in id order, so the resource map is a prefix.
    used = sorted({split(k)[1] for el in root.iter() for k in el.attrib if split(k)[0] == ANDROID_NS},
                  key=lambda n: ATTRS[n][0])
    for n in used:
        s(n)
    res_map = [ATTRS[n][0] for n in used]
    s("android")
    s(ANDROID_NS)

    body = []

    def chunk(ctype, header_extra, payload):
        header_size = 8 + len(header_extra)
        return struct.pack("<HHI", ctype, header_size, header_size + len(payload)) + header_extra + payload

    body.append(chunk(0x0100, struct.pack("<II", 1, 0xFFFFFFFF), struct.pack("<II", s("android"), s(ANDROID_NS))))

    def element(el):
        ns, name = split(el.tag)
        attrs = []
        for k, v in el.attrib.items():
            ans, an = split(k)
            if ans == ANDROID_NS:
                rid, kind = ATTRS[an]
                if kind == "string":
                    raw, typ, data = s(v), TYPE_STRING, s(v)
                elif kind == "int":
                    raw, typ, data = 0xFFFFFFFF, TYPE_INT_DEC, int(v)
                elif kind == "bool":
                    raw, typ, data = 0xFFFFFFFF, TYPE_BOOLEAN, 0xFFFFFFFF if v == "true" else 0
                elif kind == "flags":
                    f = 0
                    for part in v.split("|"):
                        f |= CONFIG_FLAGS[part]
                    raw, typ, data = 0xFFFFFFFF, TYPE_INT_HEX, f
                elif kind == "fgs":
                    f = 0
                    for part in v.split("|"):
                        f |= FGS_TYPES[part]
                    raw, typ, data = 0xFFFFFFFF, TYPE_INT_HEX, f
                elif kind == "ref":
                    raw, typ, data = 0xFFFFFFFF, TYPE_REFERENCE, REFS[v]
                attrs.append((rid, s(ANDROID_NS), s(an), raw, typ, data))
            else:
                attrs.append((0xFFFFFFFF, 0xFFFFFFFF, s(an), s(v), TYPE_STRING, s(v)))
        # The framework expects attributes sorted by resource id; unnamespaced ones go last.
        attrs.sort(key=lambda a: a[0])
        payload = struct.pack("<IIHHHHHH", 0xFFFFFFFF, s(name), 20, 20, len(attrs), 0, 0, 0)
        for rid, ans, an, raw, typ, data in attrs:
            payload += struct.pack("<IIIHBBI", ans, an, raw, 8, 0, typ, data)
        body.append(chunk(0x0102, struct.pack("<II", 1, 0xFFFFFFFF), payload))
        for child in el:
            element(child)
        body.append(chunk(0x0103, struct.pack("<II", 1, 0xFFFFFFFF), struct.pack("<II", 0xFFFFFFFF, s(name))))

    element(root)
    body.append(chunk(0x0101, struct.pack("<II", 1, 0xFFFFFFFF), struct.pack("<II", s("android"), s(ANDROID_NS))))

    # String pool (UTF-16).
    data = b""
    offsets = []
    for st in strings:
        offsets.append(len(data))
        enc = st.encode("utf-16-le")
        data += struct.pack("<H", len(st)) + enc + b"\x00\x00"
    while len(data) % 4:
        data += b"\x00"
    strings_start = 28 + 4 * len(offsets)
    pool_payload = b"".join(struct.pack("<I", o) for o in offsets) + data
    pool = chunk(0x0001, struct.pack("<IIIII", len(strings), 0, 0, strings_start, 0), pool_payload)
    resmap = chunk(0x0180, b"", b"".join(struct.pack("<I", r) for r in res_map))
    content = pool + resmap + b"".join(body)
    return struct.pack("<HHI", 0x0003, 8, 8 + len(content)) + content


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("out")
    ap.add_argument("--package", required=True)
    ap.add_argument("--version-code", required=True)
    ap.add_argument("--version-name", required=True)
    ap.add_argument("--min-sdk", required=True)
    ap.add_argument("--target-sdk", required=True)
    a = ap.parse_args()
    ET.register_namespace("android", ANDROID_NS)
    root = ET.parse(a.src).getroot()
    q = lambda n: "{%s}%s" % (ANDROID_NS, n)
    root.set("package", a.package)
    root.set(q("versionCode"), a.version_code)
    root.set(q("versionName"), a.version_name)
    sdk = ET.Element("uses-sdk", {q("minSdkVersion"): a.min_sdk, q("targetSdkVersion"): a.target_sdk})
    root.insert(0, sdk)
    with open(a.out, "wb") as f:
        f.write(encode(root))


if __name__ == "__main__":
    main()

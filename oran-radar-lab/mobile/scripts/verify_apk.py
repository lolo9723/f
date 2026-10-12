"""Fail a build on truncated DEX or missing embedded JavaScript."""
import hashlib
import io
import struct
import sys
import zipfile
import zlib

with zipfile.ZipFile(sys.argv[1]) as apk:
    bad = apk.testzip()
    if bad:
        raise SystemExit(f"Invalid ZIP CRC: {bad}")
    names = apk.namelist()
    assert "assets/index.android.bundle" in names, "Embedded JavaScript is missing"
    assert apk.getinfo("assets/index.android.bundle").file_size > 100_000
    bundle = apk.read("assets/index.android.bundle")
    assert struct.unpack_from("<Q", bundle)[0] == 0x1F1903C103BC1FC6, "Hermes magic"
    assert len(bundle) == struct.unpack_from("<I", bundle, 32)[0], "Hermes length"
    assert hashlib.sha1(bundle[:-20]).digest() == bundle[-20:], "Hermes checksum"
    dex = [name for name in names if name.endswith(".dex")]
    assert dex, "No DEX files"
    for name in dex:
        data = apk.read(name)
        assert data[:4] == b"dex\n", f"DEX magic: {name}"
        assert len(data) == struct.unpack_from("<I", data, 32)[0], f"DEX length: {name}"
        assert hashlib.sha1(data[32:]).digest() == data[12:32], f"DEX signature: {name}"
        assert zlib.adler32(data[12:]) == struct.unpack_from("<I", data, 8)[0], f"DEX checksum: {name}"
    libs = [name for name in names if name.startswith("lib/arm64-v8a/") and name.endswith(".so")]
    assert libs, "ARM64 libraries are missing"
    for name in libs:
        data = apk.read(name)
        assert data[:5] == b"\x7fELF\x02", f"ELF64 header: {name}"
        offset = struct.unpack_from("<Q", data, 32)[0]
        size, count = struct.unpack_from("<HH", data, 54)
        alignments = [struct.unpack_from("<Q", data, offset + i * size + 48)[0]
                      for i in range(count)
                      if struct.unpack_from("<I", data, offset + i * size)[0] == 1]
        assert alignments and min(alignments) >= 16_384, f"16 KB page support: {name}"
    print(f"APK content valid: {len(dex)} DEX files and embedded JavaScript")
    print(f"16 KB page alignment valid: {len(libs)} ARM64 libraries")
    python_libs = 0
    for name in names:
        if name.startswith('assets/chaquopy/') and name.endswith('.imy'):
            with zipfile.ZipFile(io.BytesIO(apk.read(name))) as archive:
                assert archive.testzip() is None, f'Python archive CRC: {name}'
                for member in archive.namelist():
                    if not member.endswith('.so'):
                        continue
                    data = archive.read(member)
                    if data[:5] != b'\x7fELF\x02':
                        continue
                    offset = struct.unpack_from('<Q', data, 32)[0]
                    size, count = struct.unpack_from('<HH', data, 54)
                    alignments = [struct.unpack_from('<Q', data, offset + i * size + 48)[0]
                                  for i in range(count) if struct.unpack_from('<I', data, offset + i * size)[0] == 1]
                    assert alignments and min(alignments) >= 16384, f'Python 16 KB page support: {name}/{member}'
                    python_libs += 1
    if python_libs:
        print(f'16 KB Python page alignment valid: {python_libs} libraries')

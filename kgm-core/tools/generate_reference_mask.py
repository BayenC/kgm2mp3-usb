#!/usr/bin/env python3
"""Freeze non-audio cipher reference data for JVM tests.

The encoder in the JVM tests uses these pre-expanded masks, not the production
decoder's tables or mask calculation. Inputs are the unmodified, attributed
OpenConverter lookup tables in third_party/OpenConverter/original/KgmDecoder.kt.
This script uses Python arbitrary-width integers for positions beyond 2 GiB.
"""
from pathlib import Path
import hashlib
import re

MODULE = Path(__file__).resolve().parents[1]
SOURCE = MODULE.parent / "third_party/OpenConverter/original/KgmDecoder.kt"
DESTINATION = MODULE / "src/test/resources"


def extract_table(name, source):
    content = re.search(
        r"private val " + name + r" = byteArrayOf\((.*?)\n    \)", source, re.S
    ).group(1)
    table = [int(value, 16) for value in re.findall(r"0x([0-9a-fA-F]+)", content)]
    assert len(table) == 272
    return table


def main():
    source = SOURCE.read_text()
    base = extract_table("MASK_V2_PRE_DEF", source)
    even = extract_table("TABLE1", source)
    odd = extract_table("TABLE2", source)

    def reference_mask(position):
        quotient = position // 16
        result = base[position % 272]
        while quotient >= 17:
            result ^= even[quotient % 272]
            quotient //= 16
            result ^= odd[quotient % 272]
            quotient //= 16
        return result

    DESTINATION.mkdir(parents=True, exist_ok=True)
    mask = bytes(reference_mask(i) for i in range(70001))
    (DESTINATION / "legacy-v3-reference-mask.bin").write_bytes(mask)
    key = bytes((i * 13 + 7) & 255 for i in range(16)) + bytes(1)
    vectors = []
    for offset in (0, 17, 271, 4350, 69630, 2147483637, 2147483648, 9223372036854775743):
        plain = bytes((i * 31 + 17) & 255 for i in range(32))
        # The nibble transform is its own inverse. An independent encoder uses
        # that identity directly instead of calling the decoder or its helpers.
        cipher = bytes(
            (value ^ ((value & 15) * 16)) ^ key[(offset + i) % 17] ^ reference_mask(offset + i)
            for i, value in enumerate(plain)
        )
        vectors.append(f"{offset}:{plain.hex()}:{cipher.hex()}")
    (DESTINATION / "legacy-v3-long-offset-vectors.txt").write_text("\n".join(vectors) + "\n")
    print("Reference mask SHA-256:", hashlib.sha256(mask).hexdigest())


if __name__ == "__main__":
    main()

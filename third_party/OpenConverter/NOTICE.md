# OpenConverter attribution

The KGM lookup tables and byte transform in `kgm-core` were adapted from:

- Project: [nowa277/OpenConverter](https://github.com/nowa277/OpenConverter)
- Revision: `e037007cc07b619434dc3b00e53b67a09842f849`
- Original files: `android/app/src/main/kotlin/com/openconverter/app/decoders/KgmDecoder.kt` and `FormatSniffer.kt`
- License: Apache License 2.0; the unmodified upstream license is in `LICENSE`.
- Algorithm reference cited by upstream: `huangbao/MyKgmWasm`.

Unmodified copies of the referenced Kotlin files are retained in `original/`.
They are reference material and are not compiled into this application.

Modifications made for this project: independent JVM module/API, v3/type1 variant
validation, bounded and unsigned header parsing, Long stream offsets, cancellation,
strict format identification, caller-owned streams, and rejection of unknown audio.
VPR/KGG support and upstream application code were not imported.

No upstream NOTICE file was present in the pinned repository tree. Copyright and
license statements in the original material have been preserved.

Source SHA-256 hashes:

- `FormatSniffer.kt`: `d1e4bf41d38314943ef3a558d585f8031718627d562f784dea5a3608bf94e50c`
- `KgmDecoder.kt`: `2c9bc13b14bf2144541a537312bd8b86b6c4fc799a91f0b18f4b1e45ac111004`

# KGM core

This dependency-free Kotlin/JVM module decodes the legacy KGM/KGMA **version 3,
type 1** variant. It identifies the variant by the file header rather than the
extension; newer variants, KGG, and VPR are rejected. No Root or application
database is needed for this supported variant.

`KgmDecoder.decrypt(input, output, bufferSize, checkCancelled)` returns an
`AudioFormat`. The cancellation callback may throw `InterruptedIOException` or
another application cancellation exception, which propagates unchanged. The
module never closes caller-owned streams. Invalid/unknown input throws
`KgmDecodingException` (`IOException`). Buffer memory is bounded at 1 MiB and
declared headers at 64 KiB. Stream offsets are `Long`.

`AudioSniffer.detect(prefix)` identifies MP3, FLAC, WAV, OGG, M4A, or AAC, or
returns `null`. Unknown data never defaults to MP3. It recognizes container
headers; it does not prove that every audio frame is intact. The legacy cipher
does not include a reliable payload size/integrity field, so an audio parser
must validate the complete decrypted/transcoded file before USB replacement.

`FlacStreamInfo.parse(first42Bytes)` reads the original PCM digest, sample count,
sample rate, channels, and bit depth. A zero MD5 means unknown; `hasMd5` is false.
These facts let the application conservatively handle encrypted FLAC sources
with bytes after the audio: recover only after a complete decoded-PCM MD5 equals
the stored nonzero digest, perform a lossless re-encode, then require the same
PCM digest and a clean strict decode. The core never strips an assumed trailer.

Upstream attribution and unmodified sources are preserved under
`../third_party/OpenConverter/`.

## Verification

Run `./gradlew :kgm-core:test`. Tests use only synthetic bytes, frozen non-audio
reference masks, and independently encrypted fixtures. They cover chunk sizes
from one byte, unusual provider zero-length reads, offsets beyond 2 GiB,
malformed/bounded headers, unknown variants/audio, and cancellation.

`tools/generate_reference_mask.py` reproduces the reference masks/vectors from
the attributed upstream tables. The tests' encoder does not call production
decoder helpers. No downloaded/user audio is committed.

An explicitly supplied real sample can be decrypted locally with:

```sh
KGM_SAMPLE='/full/path/song.kgm' KGM_OUTPUT='/private/tmp/song-validation.flac' \
  ./gradlew :kgm-core:validateRealSample
```

Then validate the complete audio with FFmpeg, for example
`ffmpeg -v error -i /private/tmp/song-validation.flac -f null -`.

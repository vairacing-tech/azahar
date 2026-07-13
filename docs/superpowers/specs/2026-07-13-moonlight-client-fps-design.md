# Moonlight Client FPS Design

## Goal

Make the Moonlight stream deliver the frame rate requested by the client. The
host must support any positive client rate that the selected hardware encoder
reports as supported, including 30 and 60 FPS.

## Current Problem

Azahar renders to the MediaCodec input surface at about 60 Hz. Setting
`MediaFormat.KEY_FRAME_RATE` does not cap a surface-input encoder, so a 30 FPS
session currently emits about 60 encoded frames per second. RTP timestamps are
also derived from a frame counter and the requested FPS, which makes their
timeline disagree with the actual MediaCodec presentation timestamps.

## Design

The existing NVHTTP and RTSP negotiation remains the source of the requested
resolution, codec, bitrate, and FPS. `EncoderSelector` continues to reject
formats that the hardware encoder does not support.

`EncoderSession` will configure the surface-input encoder with the requested
frame rate, operating rate, and Android's maximum-FPS-to-encoder format key. It
will not request repeated previous frames because vendor encoders may emit those
copies after applying the maximum-FPS limit. Frame limiting therefore happens
inside MediaCodec before predictive frames are created. This avoids sending
dependent P frames whose references were dropped or exceeding the client rate.

`VideoRtpTransport` will derive each 90 kHz RTP timestamp from the encoded
frame's `presentationTimeUs`. The first accepted frame establishes timestamp
zero. Later timestamps preserve elapsed presentation time and are forced to be
monotonic if a device returns duplicate or regressing timestamps.

The existing bounded two-frame sender queue remains unchanged. Codec, bitrate,
resolution, and audio behavior are outside this change.

## Failure Handling

If the selected encoder does not support the requested size and rate,
negotiation is rejected through the existing clear stream-configuration error.
If a vendor ignores the maximum-FPS key, runtime frame-count logging will expose
the mismatch during device validation; the native renderer throttle is a
fallback only if real-device evidence requires it.

## Tests And Validation

Unit tests will cover RTP timestamps at 30 and 60 FPS, non-zero initial PTS,
irregular spacing, and duplicate/regressing PTS values. Encoder format
configuration will be kept in a focused helper so the requested maximum rate is
testable without starting MediaCodec.

The Android unit suite and release build must pass. Real validation will start
30 and 60 FPS client sessions and measure encoded/sent frame counters over at
least 10 seconds after startup. The emitted rate must stay within 10 percent of
the requested rate, without a growing sender queue or stream failure.

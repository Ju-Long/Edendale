# Video Enhancement Pipeline

Migration from SwiftVLC to AVFoundation + FFmpeg with a real-time Metal
upscaling pipeline. Each section is independently workable. Sections A–F have
no cross-dependencies and can run in parallel. Section G integrates them into
PlayerSession. Section H adds system features on top.

```
A: Decoder Protocol ─────────────────────────────┐
B: FFmpeg Build + Swift Bridge ──┐                │
C: AVFoundation Frame Decoder ───┤                │
D: Metal Rendering Surface ──────┼─→ G: Session Integration ─→ H: System Features
E: Metal Enhancement Pipeline ───┤
F: Subtitle Engine (libass) ─────┘
```

---

## Section A — Unified Decoder Protocol

**Goal:** Define the shared interface both decoders conform to, plus the media
info and frame types the rest of the pipeline consumes.

**Create:** `Shared/Playback/DecoderProtocol.swift`

```swift
import AVFoundation
import CoreMedia
import CoreVideo

struct MediaInfo {
    let duration: CMTime
    let videoTracks: [VideoTrackInfo]
    let audioTracks: [AudioTrackInfo]
    let subtitleTracks: [SubtitleTrackInfo]
    let naturalSize: CGSize
    let frameRate: Float
    let isHDR: Bool
}

struct VideoTrackInfo {
    let index: Int
    let codec: String          // "h264", "hevc", "vp9", "av1" …
    let size: CGSize
    let bitDepth: Int          // 8, 10, 12
    let isHardwareDecodable: Bool
}

struct AudioTrackInfo {
    let index: Int
    let codec: String
    let channelCount: Int
    let sampleRate: Int
    let language: String?
    let title: String?
}

struct SubtitleTrackInfo {
    let index: Int
    let codec: String          // "ass", "srt", "webvtt", "pgs", "dvdsub"
    let language: String?
    let title: String?
    let isImageBased: Bool     // PGS, VobSub vs text-based
}

struct DecodedVideoFrame {
    let pixelBuffer: CVPixelBuffer
    let presentationTime: CMTime
    let duration: CMTime
}

struct DecodedSubtitleEvent {
    let text: String           // raw ASS/SRT markup
    let start: CMTime
    let end: CMTime
    let trackIndex: Int
}

enum DecoderState {
    case idle, opening, ready, playing, paused, seeking, ended, error(Error)
}

@MainActor
protocol MediaDecoder: AnyObject {
    var state: DecoderState { get }
    var currentTime: CMTime { get }
    var mediaInfo: MediaInfo? { get }
    var onStateChanged: ((DecoderState) -> Void)? { get set }
    var onTimeChanged: ((CMTime) -> Void)? { get set }

    func open(url: URL) async throws -> MediaInfo
    func play()
    func pause()
    func seek(to time: CMTime) async throws
    func setRate(_ rate: Float)
    func selectAudioTrack(_ index: Int)
    func selectSubtitleTrack(_ index: Int?)
    func close()
}
```

**Also create:** `Shared/Playback/FormatRouter.swift`

Routes a URL to the correct decoder based on container + codec probing:

```swift
enum DecoderKind { case avFoundation, ffmpeg }

struct FormatRouter {
    /// Probe the URL and decide which decoder to use.
    /// AVFoundation: MP4/MOV/M4V + H.264/HEVC/ProRes/AV1
    /// FFmpeg: everything else (MKV, AVI, TS, VP9, DTS audio, etc.)
    static func route(_ url: URL) async -> DecoderKind
}
```

Probing strategy:
- Check container by extension first (`.mkv`, `.avi`, `.flv` → FFmpeg immediately)
- For `.mp4`/`.mov`/`.m4v`, create a quick `AVURLAsset` and check
  `isPlayable` + inspect track codecs via `formatDescriptions`
- If any video track is VP8/VP9/unsupported or audio is DTS → FFmpeg
- Network URLs (SMB): probe with FFmpeg's `avformat_open_input` since
  AVFoundation can't open `smb://`

**Acceptance:** protocol compiles, FormatRouter returns correct kind for MP4,
MKV, AVI, and SMB URLs. Unit-testable with local fixture files.

---

## Section B — FFmpeg Apple Build + Swift Bridge

**Goal:** Build FFmpeg as xcframeworks for iOS, tvOS, macOS, and visionOS.
Create a thin Swift layer that decodes video/audio to native Apple types.

### B.1 — FFmpeg xcframeworks

**Create:** `Vendor/FFmpeg/` with a build script

Target libraries: libavcodec, libavformat, libavutil, libswresample, libswscale

Build configuration:
- Enable VideoToolbox hardware decode (`--enable-videotoolbox`,
  `--enable-hwaccel=h264_videotoolbox`, `hevc_videotoolbox`, `vp9_videotoolbox`)
- Enable protocols: `file`, `http`, `https`, `tcp`, `udp`, `smb`
  (or use libdsm for SMB if VLC's was via its own access module)
- Enable demuxers: `matroska`, `avi`, `mpegts`, `flv`, `ogg`, `mov`, `mp3`,
  `wav`, `flac`, `ass`, `srt`, `concat`
- Enable decoders: `h264`, `hevc`, `vp8`, `vp9`, `av1`, `mpeg2video`,
  `mpeg4`, `aac`, `mp3`, `flac`, `opus`, `vorbis`, `ac3`, `eac3`, `dts`,
  `truehd`, `ass`, `srt`, `subrip`, `webvtt`, `pgssub`, `dvdsub`
- Disable everything else (`--disable-encoders`, `--disable-muxers`,
  `--disable-programs`, `--disable-doc`)
- Compile with `-fembed-bitcode=off`, position-independent code
- Output: `FFmpeg.xcframework` per library, or a single merged framework

Archive the built xcframeworks so CI doesn't rebuild from source every time.
Add to the Xcode project as binary dependencies.

### B.2 — Swift bridge

**Create:** `Shared/Playback/FFmpeg/` directory

`FFmpegDecoder.swift` — conforms to `MediaDecoder`:

```
open(url:)
  → avformat_open_input + avformat_find_stream_info
  → populate MediaInfo from AVStream metadata
  → open best video/audio decoders (prefer VTB hw decoder)
  → start decode loop on a background actor

Decode loop (runs on a dedicated actor):
  → av_read_frame into packet queue
  → video packets → avcodec_send_packet/avcodec_receive_frame
  → if VTB: AVFrame.data[3] is already a CVPixelBuffer
  → if sw decode: convert AVFrame → CVPixelBuffer via vImageBuffer
     or CVPixelBufferCreateWithPlanarBytes
  → push DecodedVideoFrame to a ring buffer (3–5 frames)
  → audio packets → decode → push to audio ring buffer

Audio output:
  → feed decoded PCM to an AVAudioEngine tap or AudioUnit
  → handle channel layout mapping (5.1, 7.1 → device layout)

Seek:
  → avformat_seek_file with AVSEEK_FLAG_BACKWARD
  → flush codec buffers
  → decode until target PTS
```

`FFmpegBridge.h` / C interop layer where needed for FFmpeg's C API. Use a
Swift–C bridging header or a wrapping C module (`module.modulemap`).

**Acceptance:** FFmpegDecoder can open an MKV with HEVC+DTS, decode video
frames to CVPixelBuffer using VideoToolbox, and decode audio. Runs on all four
Apple platforms.

---

## Section C — AVFoundation Frame Decoder

**Goal:** Build the AVFoundation decoder path conforming to `MediaDecoder`,
using `AVPlayerItemVideoOutput` for frame extraction.

**Create:** `Shared/Playback/AVFoundation/AVFoundationDecoder.swift`

```
Architecture:
  AVPlayer
   └─ AVPlayerItem
       ├─ AVPlayerItemVideoOutput (pixel buffer access)
       └─ KVO/notifications for state, time, end

open(url:)
  → AVURLAsset(url:) with .prefersPreciseDurationAndTiming
  → load tracks, duration, naturalSize async
  → populate MediaInfo from AVAssetTrack metadata
  → create AVPlayerItem, attach AVPlayerItemVideoOutput
  → configure output with kCVPixelFormatType_32BGRA
    (or _420YpCbCr8BiPlanarVideoRange for efficiency,
     Metal handles YUV→RGB anyway)

Frame delivery:
  → CADisplayLink / CVDisplayLink callback
  → output.hasNewPixelBuffer(forItemTime:) check
  → output.copyPixelBuffer(forItemTime:itemTimeForDisplay:)
  → wrap in DecodedVideoFrame, push to the rendering surface

Transport:
  → play/pause/seek/rate map directly to AVPlayer
  → periodic time observer → onTimeChanged
  → .status KVO → onStateChanged
  → AVPlayerItemDidPlayToEndTime → .ended

Track selection:
  → AVPlayerItem.select(AVMediaSelectionOption) for audio/subs
  → map AVMediaSelectionGroup to AudioTrackInfo/SubtitleTrackInfo
```

Handle HDR: preserve the pixel buffer's color space attachments
(`kCVImageBufferColorPrimariesKey`, transfer function, YCbCr matrix) so the
Metal pipeline can tone-map correctly.

**Acceptance:** AVFoundationDecoder plays an MP4 (H.264 and HEVC), delivers
CVPixelBuffers at display rate, supports seek, track selection, and rate
changes. Runs on all four Apple platforms.

---

## Section D — Metal Rendering Surface

**Goal:** Build the view that receives processed frames and presents them.
Replaces SwiftVLC's `PiPVideoView` / `VideoView`.

**Create:** `Shared/Playback/Rendering/EnhancedVideoView.swift`

### D.1 — Core rendering view

```
MTKView subclass (or CAMetalLayer-backed UIView/NSView):
  → MTLDevice, MTLCommandQueue owned by a shared MetalContext singleton
  → on each display link tick:
      1. Dequeue the latest processed MTLTexture from the pipeline
      2. Create a render pass that blits the texture to the drawable
      3. Present with afterMinimumDuration for frame pacing
  → handle resize: update drawable size to match view bounds
  → aspect ratio fitting/filling (replaces PlayerLogic.aspectFillScale)
```

SwiftUI wrapper (`EnhancedVideoPlayer.swift`):
```swift
#if os(macOS)
struct EnhancedVideoPlayer: NSViewRepresentable { ... }
#else
struct EnhancedVideoPlayer: UIViewRepresentable { ... }
#endif
```

### D.2 — Frame intake

```swift
/// Thread-safe frame buffer between decoder and renderer.
/// Triple-buffered: decoder writes, renderer reads, no contention.
final class FrameRingBuffer: Sendable {
    func push(_ frame: DecodedVideoFrame)
    func latestFrame(after time: CMTime) -> DecodedVideoFrame?
}
```

The decoder pushes `DecodedVideoFrame` (CVPixelBuffer + PTS) into the ring
buffer. The Metal view pulls the latest frame whose PTS ≤ current display
time.

### D.3 — CVPixelBuffer → MTLTexture bridge

```swift
/// Converts CVPixelBuffer to MTLTexture zero-copy via IOSurface.
/// CVMetalTextureCacheCreateTextureFromImage avoids any GPU upload.
final class PixelBufferTextureCache {
    func texture(from pixelBuffer: CVPixelBuffer) -> MTLTexture
}
```

This is zero-copy on Apple Silicon — the CVPixelBuffer's IOSurface backing is
mapped directly as a Metal texture.

### D.4 — Platform variants

- **macOS/iOS/visionOS:** `MTKView` with `CAMetalLayer`
- **tvOS:** same `MTKView`, but handle focus and remote idle dimming
- **visionOS spatial:** for stereo content, keep the existing
  `VisionAVPlayerHost` path (AVPlayerViewController handles MV-HEVC natively);
  enhancement pipeline only applies to the 2D rectilinear VLC-replacement path

**Acceptance:** EnhancedVideoView displays a test pattern texture at 60fps on
all platforms. Accepts CVPixelBuffer input and renders with correct aspect
ratio. No decoder needed — test with a solid-color or gradient CVPixelBuffer.

---

## Section E — Metal Enhancement Pipeline

**Goal:** The GPU compute pipeline that upscales and enhances each frame.
Operates on MTLTextures, fully independent of the decoder and renderer.

**Create:** `Shared/Playback/Enhancement/` directory

### E.1 — Pipeline architecture

```
Input texture (source resolution, e.g. 720p)
  │
  ├─ [if enhancement off] → pass through to renderer
  │
  ▼
  MetalFX Spatial Upscaler (or Lanczos fallback)
  → intermediate texture (target resolution, e.g. 4K)
  │
  ▼
  Contrast Adaptive Sharpening (CAS) compute pass
  → sharpened texture
  │
  ▼
  Temporal Noise Reduction (optional)
  → denoised texture (final output)
  │
  ▼
  Output texture → renderer
```

### E.2 — Upscaler (`SpatialUpscaler.swift`)

Primary: `MTLFXSpatialScaler` (macOS 13+, iOS 16+, tvOS 16+)
```swift
let descriptor = MTLFXSpatialScalerDescriptor()
descriptor.inputWidth = sourceWidth
descriptor.inputHeight = sourceHeight
descriptor.outputWidth = targetWidth
descriptor.outputHeight = targetHeight
descriptor.colorTextureFormat = .bgra8Unorm
descriptor.outputTextureFormat = .bgra8Unorm
descriptor.colorProcessingMode = .perceptual
let scaler = descriptor.makeSpatialScaler(device: device)
```

Fallback for older OS: custom Lanczos compute shader (a 4-tap or 6-tap kernel
is sufficient and runs well within budget).

Target resolution logic:
- Source < 1080p → upscale to 1080p or display resolution, whichever is smaller
- Source 1080p → upscale to display resolution if display is 4K
- Source ≥ display resolution → skip upscale, CAS-only mode

### E.3 — Contrast Adaptive Sharpening (`CASShader.metal`)

AMD's CAS algorithm adapted for Metal compute:
```metal
// Single compute pass, one thread per output pixel.
// Reads a 3×3 neighborhood from the upscaled texture.
// Computes per-pixel sharpening weight based on local contrast.
// Avoids ringing on edges, enhances detail in flat areas.

kernel void contrastAdaptiveSharpening(
    texture2d<float, access::read>  input  [[texture(0)]],
    texture2d<float, access::write> output [[texture(1)]],
    constant float &sharpness             [[buffer(0)]],
    uint2 gid [[thread_position_in_grid]])
{
    // Sample 3×3 neighborhood
    // Compute min/max of cross and box neighborhoods
    // Derive sharpening coefficient from local contrast
    // Apply weighted sharpening
    // Write output
}
```

Sharpness parameter: 0.0 (no sharpening) → 1.0 (maximum). Default 0.5.

### E.4 — Temporal Noise Reduction (`TemporalDenoise.metal`)

Optional pass for noisy low-bitrate sources:
```
- Keep a history texture (previous frame, same resolution as output)
- For each pixel, compare current vs history
- If difference < threshold: blend (weighted average, e.g. 80% history + 20% current)
- If difference > threshold: use current (motion detected, no ghosting)
- Write current to history for next frame
```

This is a simplified version of temporal AA without motion vectors. Effective
for static/slow scenes in compressed video. The threshold and blend weight are
user-adjustable.

### E.5 — Pipeline controller (`EnhancementPipeline.swift`)

```swift
@Observable
final class EnhancementPipeline {
    var isEnabled: Bool
    var preset: EnhancementPreset   // .off, .sharpenOnly, .balanced, .quality
    var sharpness: Float            // 0.0–1.0
    var denoiseStrength: Float      // 0.0–1.0

    /// Process a source frame and return the enhanced texture.
    func process(
        source: MTLTexture,
        commandBuffer: MTLCommandBuffer
    ) -> MTLTexture
}

enum EnhancementPreset {
    case off            // passthrough
    case sharpenOnly    // CAS only, no upscale
    case balanced       // MetalFX + CAS
    case quality        // MetalFX + CAS + temporal denoise
}
```

### E.6 — Performance budget

Target: < 8ms total per frame at 4K output on M1 (leaves 8ms+ headroom for
decode + render at 60fps).

| Pass                  | Expected cost (M1, 4K out) |
|-----------------------|---------------------------|
| MetalFX spatial       | ~1–2ms                    |
| CAS compute           | ~0.5–1ms                  |
| Temporal denoise      | ~1–2ms                    |
| **Total**             | **~3–5ms**                |

If frame budget is exceeded, drop temporal denoise first, then skip upscale
(CAS-only on source resolution is always fast enough).

**Acceptance:** Pipeline processes a 720p test texture → 4K output with visible
sharpening improvement. Measurable via Metal GPU profiler. Runs on all four
platforms. Preset switching works without pipeline stalls.

---

## Section F — Subtitle Engine

**Goal:** Render text-based (ASS/SSA/SRT) and image-based (PGS/VobSub)
subtitles as textures for compositing in the Metal pipeline.

**Create:** `Shared/Playback/Subtitles/` directory

### F.1 — libass integration

Build libass as an xcframework alongside FFmpeg (it depends on FreeType,
FriBidi, HarfBuzz — all buildable for Apple platforms).

`AssRenderer.swift`:
```
- Initialize ass_library, ass_renderer
- Set frame size to output resolution
- Set default font (system font via CTFontCopyAttribute)
- For each subtitle event from the decoder:
    → ass_process_chunk or ass_process_data
- On each frame:
    → ass_render_frame(renderer, now_ms)
    → returns ASS_Image linked list (bitmaps + position + color)
    → composite into a single RGBA texture (subtitle overlay)
```

### F.2 — SRT / WebVTT renderer

For simple timed-text subtitles without ASS styling:
- Parse SRT/WebVTT into timed text events
- Render text to a texture using Core Text (`CTFramesetterCreateWithAttributedString`)
- Position at bottom center with configurable margin
- Support basic formatting: bold, italic, color tags

### F.3 — Image-based subtitles (PGS, VobSub)

- FFmpeg decodes these to `AVSubtitleRect` with bitmap data
- Convert bitmap to MTLTexture directly
- Position according to the rect's x/y coordinates

### F.4 — Compositing

The subtitle texture is alpha-blended onto the enhanced video frame as the
final step in the Metal pipeline, after CAS/denoise:

```metal
kernel void compositeSubtitles(
    texture2d<float, access::read>  video     [[texture(0)]],
    texture2d<float, access::read>  subtitle  [[texture(1)]],
    texture2d<float, access::write> output    [[texture(2)]],
    uint2 gid [[thread_position_in_grid]])
{
    float4 v = video.read(gid);
    float4 s = subtitle.read(gid);
    output.write(float4(mix(v.rgb, s.rgb, s.a), 1.0), gid);
}
```

**Acceptance:** ASS subtitles render with correct styling (fonts, colors,
positioning, animations). SRT renders with legible default styling. PGS
bitmaps display at correct positions. All composited onto video frames in
the Metal pipeline.

---

## Section G — PlayerSession Integration

**Depends on:** A, B, C, D, E, F

**Goal:** Replace all SwiftVLC references in the player layer with the new
decoder + rendering pipeline.

### G.1 — Files to modify

| File | Change |
|------|--------|
| `PlayerSession.swift` | Replace `Player` (VLC) with `MediaDecoder` protocol. Replace `makePlayer` factory with decoder creation via `FormatRouter`. Replace VLC event loop with decoder callbacks. |
| `PlayerScreen.swift` | Replace `VideoPlayer` (SwiftVLC view) with `EnhancedVideoPlayer`. Remove `scaleEffect` aspect-fill hack (Metal view handles this). |
| `PlayerLogic.swift` | Remove `aspectFillScale()` if Metal view handles aspect. Keep transport state logic. |
| `PlayerChromeModel.swift` | Adapt time/state callbacks from `MediaDecoder` instead of VLC events. |
| `VideoAdjustmentController.swift` | Replace `player.withAdjustments` with Metal pipeline uniform updates. Brightness/contrast/gamma/saturation/hue become shader parameters in the enhancement pipeline. |
| `VideoPlayer.swift` | Delete (was SwiftVLC wrapper). |
| `PlayerGestureLayer.swift` | No decoder changes expected. Keep as-is. |
| `PlayerUpNextView.swift` | No decoder changes expected. Keep as-is. |

### G.2 — Session lifecycle changes

```
present(item:)
  → FormatRouter.route(url) → .avFoundation or .ffmpeg
  → create the appropriate MediaDecoder
  → decoder.open(url:)
  → connect decoder frame output → FrameRingBuffer → EnhancedVideoView
  → connect decoder time/state callbacks → PlayerChromeModel
  → decoder.play()

switchMedia(to:)
  → decoder.close()
  → FormatRouter.route(newURL) (may switch decoder type)
  → create new decoder, open, connect, play

end()
  → decoder.close()
  → release Metal resources
```

### G.3 — VisionOS routing

Keep the existing dual-path:
- Spatial/stereo/MV-HEVC → `VisionAVPlayerHost` (AVPlayerViewController)
- Flat 2D content → new pipeline (`EnhancedVideoPlayer`)
- `VisionMediaInspector.inspect()` routing stays the same

### G.4 — Video adjustments migration

`VideoAdjustmentController` currently calls `player.withAdjustments` (VLC API).
Migrate to setting uniforms on the Metal pipeline:

```swift
// Before (VLC):
player?.withAdjustments {
    $0.brightness = values.brightness
    $0.contrast = values.contrast
    ...
}

// After (Metal):
enhancementPipeline.adjustments = VideoAdjustments(
    brightness: values.brightness,
    contrast: values.contrast,
    gamma: values.gamma,
    saturation: values.saturation,
    hue: values.hue
)
```

Add a color correction compute pass to the Metal pipeline (between upscale
and CAS) that applies these as a color matrix transform.

### G.5 — Package dependency changes

- Remove: `SwiftVLC` (harflabs/SwiftVLC)
- Add: FFmpeg xcframeworks, libass xcframework
- Keep: all other dependencies unchanged

**Acceptance:** Full playback of MP4 (via AVFoundation) and MKV (via FFmpeg)
with working transport, track selection, subtitle display, and video
adjustments. No SwiftVLC references remain. All four platforms build.

---

## Section H — PiP, AirPlay & System Features

**Depends on:** D, G

**Goal:** Restore platform integration features that SwiftVLC provided.

### H.1 — Picture in Picture

**iOS/iPadOS:**
- `AVPictureInPictureController` requires `AVSampleBufferDisplayLayer`
- After Metal processing, wrap the output `CVPixelBuffer` in a
  `CMSampleBuffer` and enqueue to `AVSampleBufferDisplayLayer`
- The display layer serves dual purpose: PiP source + can be the primary
  rendering surface (alternative to MTKView)
- Attach `AVPictureInPictureController(contentSource:
  .init(sampleBufferDisplayLayer: layer, playbackDelegate: self))`

**macOS:**
- Same `AVSampleBufferDisplayLayer` approach, or use
  `AVPictureInPictureController` with `AVPlayerLayer` if the source is
  AVFoundation (skip Metal processing during PiP for efficiency)

**tvOS:** N/A (no PiP on tvOS)

**visionOS:** handled by `AVPlayerViewController` on the native path

### H.2 — Now Playing / Media Remote

- `MPNowPlayingInfoCenter` — already handled by PlayerSession, verify it
  still updates correctly with the new decoder callbacks
- `MPRemoteCommandCenter` — same, verify play/pause/seek commands route
  through the new decoder

### H.3 — AirPlay

- For AVFoundation decoder: AirPlay works automatically via AVPlayer
- For FFmpeg decoder: route audio through AVAudioSession with
  `.allowAirPlay` option; video AirPlay requires `AVSampleBufferDisplayLayer`
  (same as PiP surface)

### H.4 — Audio session

- Configure `AVAudioSession` for movie playback category
- Handle route changes (headphones unplugged → pause)
- Multi-channel passthrough for Atmos/DTS on capable receivers

**Acceptance:** PiP works on iOS and macOS. Now Playing info updates. AirPlay
streams video and audio. Audio route changes handled correctly.

---

## Enhancement UI (part of Section G)

**Create:** `Shared/Views/Player/VideoEnhancementControls.swift`

Same pattern as `VideoAdjustmentControls.swift`:

```swift
struct VideoEnhancementControls: View {
    @Bindable var controller: VideoEnhancementController

    // Preset picker: Off / Sharpen Only / Balanced / Quality
    // Sharpness slider (0.0–1.0)
    // Denoise slider (0.0–1.0)
    // Show Original toggle (bypass pipeline for A/B)
    // Resolution info: "720p → 2160p" showing source → output
}
```

Wire into the player side panel alongside the existing picture adjustment
controls.

---

## Build order (if sequential)

1. **A** — protocol (small, fast, unblocks B, C, G)
2. **B + C** — both decoders in parallel
3. **D** — rendering surface (can use test data)
4. **E** — enhancement pipeline (can use test textures)
5. **F** — subtitles (can test independently)
6. **G** — integration (needs all above)
7. **H** — system features (needs G)
8. **I** — frame generation (needs D, E)
9. **J** — storage providers (needs B, G; see J.12)

---

## Section I — Frame Generation (Motion-Compensated Frame Interpolation)

**Depends on:** D (rendering surface), E (enhancement pipeline)

**Goal:** Double the display framerate by synthesizing intermediate frames
between real decoded frames. A 24fps source displays at 48fps, 30fps at 60fps.
Uses custom GPU optical flow — no game-engine motion vectors or depth required.

**Approach:** Path B (custom GPU frame interpolation). MetalFX's
`MTLFXFrameInterpolator` requires per-pixel motion vectors, depth buffers, and
camera parameters that video playback cannot provide. Instead, we estimate
motion between consecutive enhanced frames using a Metal compute shader and
synthesize the intermediate frame via bidirectional warping + blending.

```
Frame N-1 (enhanced) ──┐
                        ├─→ Motion Estimation ─→ Motion Vectors (RG16Float)
Frame N   (enhanced) ──┘         │
                                 ▼
                        Frame Interpolation
                        (warp N-1 forward 0.5×, warp N backward 0.5×,
                         blend + hole-fill)
                                 │
                                 ▼
                        Synthetic Frame N-0.5
```

Display timeline with interpolation enabled:
```
Without:  N-1 ──────── N ──────── N+1           (24 fps)
With:     N-1 ── +0.5 ── N ── +0.5 ── N+1       (48 fps)
```

### I.1 — Motion Estimation Compute Shader

- [x] **Create `Shared/Playback/Enhancement/MotionEstimation.metal`**

Hierarchical block motion estimation on the GPU:

```
Pass 1 — Coarse (16×16 macroblocks):
  → Planned: downsample both frames to quarter resolution (bilinear).
    Not done yet: the search runs at ME resolution, so motion beyond
    ±16 px per frame is not found.
  → For each 16×16 block in frame N, search a ±16 pixel window in frame N-1
  → Minimize cost = mean absolute luma difference + a charge per pixel of
    offset (`kMaxMotionCost` at the window edge), starting from zero motion,
    so flat and noisy areas settle on zero instead of an arbitrary vector
  → Count blocks whose best match still differs by more than
    `sceneCutBlockError` (scene-cut input, see I.3)
  → Output: coarse motion vector per block (RG16Float)

Pass 2 — Refine (4×4 sub-blocks):
  → For each 4×4 sub-block, refine the coarse vector with a ±4 pixel search
  → Work at full resolution using the coarse vector as the search center;
    leave it only for a clearly better match (same per-pixel charge)
  → Output: refined motion vector texture (RG16Float, 1/4 pixel density)

Pass 3 — Per-pixel interpolation:
  → Bilinear-interpolate the block-level motion vectors to per-pixel density
  → Optional: median filter (3×3) to suppress outlier vectors
```

Kernel signatures:
```metal
kernel void motionEstimationCoarse(
    texture2d<float, access::read>  prevFrame        [[texture(0)]],
    texture2d<float, access::read>  currFrame        [[texture(1)]],
    texture2d<float, access::write> motionOut        [[texture(2)]],
    constant uint                   &blockSize       [[buffer(0)]],
    constant uint                   &searchRadius    [[buffer(1)]],
    device atomic_uint              *unmatchedBlocks [[buffer(2)]],
    constant float                  &unmatchedError  [[buffer(3)]],
    uint2                           gid              [[thread_position_in_grid]]);

kernel void motionEstimationRefine(
    texture2d<float, access::read>  prevFrame   [[texture(0)]],
    texture2d<float, access::read>  currFrame   [[texture(1)]],
    texture2d<float, access::read>  coarseMV    [[texture(2)]],
    texture2d<float, access::write> refinedMV   [[texture(3)]],
    constant uint                   &blockSize  [[buffer(0)]],
    constant uint                   &coarseBlock [[buffer(1)]],
    uint2                           gid         [[thread_position_in_grid]]);

kernel void motionVectorDensify(
    texture2d<float, access::read>  blockMV     [[texture(0)]],
    texture2d<float, access::write> pixelMV     [[texture(1)]],
    constant uint2                  &blockSize  [[buffer(0)]],
    uint2                           gid         [[thread_position_in_grid]]);
```

- [x] **Add motion estimation kernels to `MetalShaderSource.embeddedShaderSource`**

Update the embedded fallback source string in `MetalShaderSource.swift` to
include the new kernels. Verify `MetalShaderSource.library(for:)` resolves
them from both compiled .metal files and the embedded fallback.

### I.2 — Frame Warping + Synthesis Shader

- [x] **Create `Shared/Playback/Enhancement/FrameInterpolation.metal`**

Bidirectional warping with occlusion-aware blending:

```
Input:  frame N-1, frame N, per-pixel motion vectors
Output: synthetic frame at t=0.5

For each output pixel (x, y):
  1. Forward warp:  sample frame N-1 at (x + mv.x * 0.5, y + mv.y * 0.5)
  2. Backward warp: sample frame N   at (x - mv.x * 0.5, y - mv.y * 0.5)
  3. Occlusion check:
     → compute consistency: if |forward_pos - backward_pos| > threshold,
       one direction is occluded — favor the non-occluded sample
  4. Blend: weighted average of forward and backward warped samples
     → equal weight (0.5/0.5) for non-occluded pixels
     → full weight to the visible sample at occlusion boundaries
  5. Hole-fill: for pixels where both warps land outside the frame,
     bilinear sample from the nearest valid pixel in frame N
```

Kernel signature:
```metal
kernel void frameInterpolate(
    texture2d<float, access::read>  prevFrame   [[texture(0)]],
    texture2d<float, access::read>  currFrame   [[texture(1)]],
    texture2d<float, access::read>  motionVec   [[texture(2)]],
    texture2d<float, access::write> output      [[texture(3)]],
    constant float                  &blendTime  [[buffer(0)]],
    uint2                           gid         [[thread_position_in_grid]]);
```

`blendTime` is 0.5 for midpoint interpolation but can be parameterized for
future multi-frame interpolation (e.g. 0.25 and 0.75 for 4× framerate).

### I.3 — FrameInterpolator Swift Controller

- [x] **Create `Shared/Playback/Enhancement/FrameInterpolator.swift`**

```swift
final class FrameInterpolator: @unchecked Sendable {
    let device: MTLDevice

    // Pipeline states
    private var coarseMEState: MTLComputePipelineState?
    private var refineMEState: MTLComputePipelineState?
    private var densifyState: MTLComputePipelineState?
    private var interpolateState: MTLComputePipelineState?

    // Previous frame history
    private var previousFrame: MTLTexture?
    private var hasValidPrevious: Bool = false

    // Intermediate textures (cached, recreated on dimension change)
    private var coarseMotionTexture: MTLTexture?    // quarter-res RG16Float
    private var refinedMotionTexture: MTLTexture?   // sub-block RG16Float
    private var pixelMotionTexture: MTLTexture?     // full-res RG16Float
    private var interpolatedFrame: MTLTexture?      // full-res output

    private let lock = NSLock()

    init?(device: MTLDevice, library: MTLLibrary?)

    /// Generate an interpolated frame between the previous and current frame.
    /// Returns nil on the first frame (no history) or if interpolation is
    /// not possible. The caller presents this BEFORE the real current frame.
    func interpolate(
        current: MTLTexture,
        commandBuffer: MTLCommandBuffer
    ) -> MTLTexture?

    /// Update history with the current frame AFTER it has been presented.
    /// Must be called every real frame to keep history in sync.
    func commitFrame(_ frame: MTLTexture, commandBuffer: MTLCommandBuffer)

    /// Discard history (seek, track switch, media change, scene cut).
    func reset()
}
```

Scene-cut detection uses the coarse pass's motion-compensated error, not
the raw frame difference (a plain difference cannot tell a pan from a cut).
A 16×16 block is unmatched when its best match still differs by more than
`sceneCutBlockError` (0.06 mean luma); when at least `sceneCutBlockFraction`
(30%) of blocks are unmatched, the pair is a cut. The decision stays on the
GPU: the coarse pass counts unmatched blocks into a buffer cleared at the
start of the same command buffer, and a final `holdPreviousOnSceneCut` pass
replaces the synthetic frame with frame N-1, for both the custom and MetalFX
backends. No CPU readback, so the decision applies to the current pair.

Calibration (1920×1080 frames from macOS wallpapers, share of unmatched
blocks at 0.06): sub-pixel pans up to 16 px, zoom, grain σ≤6 and fades up
to 8%/frame ≤ 3%; pans of 24–40 px ≤ 6%; cuts between different images
57–100%. The fraction is kept low because flat areas (letterbox bars, dark
scenes) always match. Detailed content moving beyond the ±16 px search also
trips it and is shown without a synthetic frame.

### I.4 — EnhancedVideoView Draw Loop Integration

- [x] **Modify `EnhancedVideoView.swift` draw loop for frame interpolation**

Decoded frames reach the renderer when they are due, with no lookahead, so
the frame between N-1 and N can only be built once N has arrived. The
synthetic frame has to reach the screen before N, so N is held back by one
display refresh:

```
refresh k (new frame N arrived; history holds N-1):
  → run enhancement pipeline → enhanced_N
  → FrameInterpolator.interpolate(current: enhanced_N) → synthetic N-0.5
  → FrameInterpolator.commitFrame(enhanced_N)      // after interpolate()
  → present synthetic N-0.5, keep a copy of enhanced_N

refresh k+1:
  → present the held enhanced_N

refreshes with no new frame:
  → present nothing; the last image stays on screen
```

Calling `commitFrame()` before `interpolate()` blends a frame with itself,
and presenting the synthetic frame after N steps backwards in time. Holding
N delays video by one refresh (≈21 ms at 24 fps).

`FrameInterpolationScheduler` makes the per-refresh decision from frame
timestamps rather than an alternating flag:
  - New frame that directly follows the previous one → synthetic frame
    first, then N on the next refresh
  - First frame, seek, dropped frame or stall (gap ≥ 1.5 frame durations)
    → present N directly
  - Paused (scrubbing), direct/test sources, sources above 30 fps
    → regular draw path, no interpolation

Other `EnhancedVideoView` details:
  - `preferredFramesPerSecond` is 2× the source content framerate
    (e.g. 48 for 24fps content, 60 for 30fps content)
  - Frame pacing: macOS uses `present(afterMinimumDuration:)` with
    `1.0 / (2.0 * sourceFrameRate)`

- [x] **Handle edge cases in draw loop**
  - First frame after seek/open: no previous frame → present real frame only
  - Scene cut detected: the synthetic slot repeats frame N-1 (GPU-side, I.3)
  - Ring buffer empty: drop any held frame, fall back to the regular path
  - Pause: reset interpolator; scrubbing while paused never interpolates
  - App backgrounding: pause interpolation, resume on foreground

### I.5 — PlaybackEngine + EnhancementPipeline Wiring

- [x] **Expose source framerate from decoders**

Both `AVFoundationDecoder` and `FFmpegDecoder` already populate
`MediaInfo.frameRate`. Ensure `PlaybackEngine` exposes this so
`EnhancedVideoView` can compute the 2× target display rate.

- [x] **Wire FrameInterpolator into PlaybackEngine**

```swift
// In PlaybackEngine.init():
self.frameInterpolator = FrameInterpolator(
    device: enhancementPipeline.device,
    library: MetalShaderSource.library(for: enhancementPipeline.device)
)

// On seek / media switch:
frameInterpolator?.reset()
```

- [x] **Add interpolation toggle to EnhancementPipeline**

Add `var frameInterpolationEnabled: Bool = false` to `EnhancementPipeline`.
When toggled off, the draw loop skips interpolation entirely and presents
at the source framerate. The toggle should be independent of the existing
preset system (interpolation can combine with any preset).

### I.6 — UI Controls

- [x] **Add frame interpolation toggle to `VideoEnhancementControls.swift`**

Below the existing denoise slider, add:
  - Toggle: "Motion Smoothing" (on/off)
  - Info label: "24 fps → 48 fps" showing actual source and display rates
  - Only visible when source framerate ≤ display refresh rate / 2

- [x] **Gate interpolation to capable displays**

Only offer the toggle when the display refresh rate is > source framerate.
On ProMotion displays (120Hz), a 24fps source could go to 48fps or even
96fps (4×). Start with 2× only.

### I.7 — Tests

- [x] **Create `EdendaleTests/FrameInterpolationTests.swift`**

Test cases:
  - Motion estimation produces non-zero vectors for a known horizontal pan
    (shifted test texture)
  - Motion estimation produces near-zero vectors for a static scene
  - Scene-cut detection triggers on completely different frames
  - Frame interpolation output differs from both input frames
  - Interpolated frame for a horizontal shift is visually between the inputs
    (sample center pixel, verify intermediate position)
  - FrameInterpolator returns nil on first frame (no history)
  - FrameInterpolator.reset() clears history correctly
  - Performance budget: motion estimation + interpolation < 6ms at 1080p
    on Apple Silicon

- [x] **Add scene-cut and flat-area tests to `FrameInterpolationTests.swift`**

  - Unrelated frames: the synthetic frame repeats the previous frame
  - Still frame with letterbox bars and a flat box: unchanged (flat areas
    used to pick up a (−20, −20) px vector and smear neighbouring content)
  - ~4% brightness fade: blended, not treated as a cut

- [x] **Add draw-order tests to `FrameInterpolationTests.swift`**

  - `FrameInterpolationScheduler`: synthetic frame before the real one, the
    held frame on the next refresh, no synthesis across seeks, dropped
    frames, or resets
  - GPU: `interpolate()` before `commitFrame()` lands on the true midpoint
    of a pan

- [ ] **Add interpolation draw-loop tests to `EnhancedVideoRenderingTests.swift`** (deferred — needs MainActor rendering context)

  - Verify `EnhancedVideoView` presents a synthetic frame before each real frame
  - Verify frame count doubles when interpolation is enabled
  - Verify seek resets interpolation state

### I.8 — Performance Budget

Target: motion estimation + interpolation < 6ms total per interpolated frame
at 1080p output on M1. Combined with the existing enhancement pipeline
(< 5ms), total GPU time per display frame stays under 11ms (comfortable
within 16ms budget for 60fps output).

| Pass                            | Expected cost (M1, 1080p) |
|---------------------------------|---------------------------|
| Coarse motion estimation (16×16)| ~1–2ms                    |
| Refined motion estimation (4×4) | ~1–2ms                    |
| Motion vector densify           | ~0.3ms                    |
| Bidirectional warp + blend      | ~1–2ms                    |
| **Total interpolation**         | **~3–6ms**                |

At 4K output, costs roughly 4× — may exceed budget.  **Implemented: half-res ME**
(sources wider than `halfResMEThreshold` (default 1920) run ME at half
resolution, then bilinear-upscale the motion vectors to full res).

Additional 4K kernels:
  - `bilinearDownscale` — 2:1 box filter for frame downsampling
  - `motionVectorUpscale` — bilinear MV upscale (normalised space, no magnitude adjust)

Performance stats (`FrameInterpolator.stats`):
  - `lastFrameMs` / `averageMs` — exponential moving average over 60 frames
  - `frameCount` — total interpolated frames
  - `isHalfRes` — whether the current frame used the half-res ME path
  - Measured via `commandBuffer.gpuEndTime - gpuStartTime` when available

### I.9 — MetalFX Frame Interpolator Backend

- [x] **Prototype `MTLFXFrameInterpolator` with estimated inputs**

Implemented `MetalFXInterpolatorBackend` (macOS 26+ / iOS 26+) that feeds
GPU-estimated motion vectors into Apple's `MTLFXFrameInterpolator`:

  - `FrameInterpolator.backend` switches between `.custom` (default) and `.metalFX`
  - `FrameInterpolator.isMetalFXAvailable` checks device support at runtime
  - Uses `pixelMotionTexture` (RG16Float) as `motionTexture`
  - Flat depth texture (all pixels at far plane — no 3D depth for video)
  - Camera params: 90° FOV, 0.1/1000 near/far, aspect from frame dimensions
  - Motion vector scale: width × height (normalised MVs → pixel space)
  - UI toggle: "MetalFX Interpolator" appears under Motion Smoothing when available
  - 3 tests: availability check, backend switching, output verification
  - The custom path (I.2) remains the reliable default for all Apple Silicon

---

### Section I — Tracking

| Step | Description                              | Status |
|------|------------------------------------------|--------|
| I.1  | Motion estimation compute shader         | [x]    |
| I.2  | Frame warping + synthesis shader         | [x]    |
| I.3  | FrameInterpolator Swift controller       | [x]    |
| I.4  | Draw loop integration                    | [x]    |
| I.5  | PlaybackEngine wiring                    | [x]    |
| I.6  | UI controls                              | [x]    |
| I.7  | Tests                                    | [x]    |
| I.8  | Performance profiling & budget           | [x]    |
| I.9  | MetalFX interpolator backend (optional)  | [x]    |

---

## Section J — Storage Providers

**Depends on:** B (FFmpeg reader), G (session integration). Independent of the
rendering work in C–F and I.

**Goal:** Let a library source live in Google Drive, OneDrive, Dropbox,
WebDAV, S3-compatible storage, NFS, or SFTP, alongside local folders and SMB
shares, on every Apple platform including Apple TV. Imports keep the existing
contract: list the source, classify file names locally, persist, then enrich
from TMDB in the background. No Edendale server or account is involved.

```
Link Source ─→ connector (sign-in or login) ─→ list / enumerate ─→ LibraryController
                                                (credential-free URLs in SwiftData)

Play ─→ FormatRouter ─→ FFmpegDecoder ─→ EDFFmpegReader custom I/O
                                          ├─ SMB / NFS / SFTP: libsmb2 / libnfs / SwiftNIO SSH
                                          └─ HTTP providers: RemoteByteSource (URLSession)
                                                 └─ token or login from the Keychain
```

### J.1 — Current state

Checked against this branch with SwiftVLC 0.8.0 and FFmpeg 7.1.1.

What already exists:
- `MediaConnector` (`Shared/Controllers/Connectors/MediaConnector.swift`):
  `validate()` and `list(directory:)`, returning credential-free URLs.
  `SMBConnector` is the only conformer.
- Browsing goes through libvlc (`VLCNetworkBrowser`), which is
  scheme-agnostic. `smb://` playback routes to FFmpeg (`FormatRouter.isSMB`)
  and reads through libsmb2 custom I/O in `FFmpegReader.m`.
- The bundled `libvlc.a` also exports the libnfs and libssh2 APIs on iOS,
  tvOS, visionOS, and macOS (for example `nfs_pread`, `nfs_opendir`,
  `libssh2_sftp_readdir_ex`, `libssh2_hostkey_hash`), and ships NFS, SFTP,
  and FTP access modules plus Bonjour and UPnP discovery. Its libssh2
  (1.11.0 on libgcrypt) offers only finite-field Diffie-Hellman key exchange
  and `ssh-rsa`/`ssh-dss` host keys, which current OpenSSH servers refuse, so
  SFTP uses SwiftNIO SSH instead (J.9).
- Watch progress is keyed by TMDB ID and `MediaParser` reads only the file
  name, so new sources need no sync or classification changes.
- TMDB sign-in already pairs `ASWebAuthenticationSession` with a QR code for
  approving on another device, and stores its token with `KeychainStore`,
  which writes synchronizable items.

Gaps:
1. SMB is hard-wired. `MediaSourceKind` has only `local` and `smb`;
   `LibraryController.connector(for:)` switches on it; `BrowseLocation` and
   `NetworkFolderPickerView` take an `SMBConnector`; `AddNetworkSourceView`
   creates one directly.
2. Credentials are a username and password per host, injected into the URL
   (`LibraryController.remoteScope`). There is no account with refreshable
   tokens.
3. No authenticated HTTP playback. Other URLs fall through to FFmpeg's
   built-in `http(s)` (`location = url.absoluteString` in `openURL:`), which
   sends no auth header, can't refresh one mid-stream, and doesn't verify TLS
   certificates by default (`tls_verify` defaults to 0 in
   `libavformat/tls.h`). Nothing plays `http(s)` URLs today.
4. `rescanAllFolders()` runs every time the Downloaded page appears and lists
   folders one at a time. For a cloud source that is API traffic on every
   visit.
5. Remote items store a duration of 0, and `PlayerLogic.siblingVideoFiles`
   only lists local folders.
6. The Remove Source dialog says the server's saved login is forgotten
   (`SourceRow.removeMessage`), but `removeFolder` never calls
   `NetworkCredentialStore.remove`.
7. Apple TV never receives iCloud Keychain items. Apple's
   `kSecAttrSynchronizable` documentation: "Items that you store on tvOS never
   leave the device where you create them, and items that you store on other
   devices don't synchronize to tvOS devices." Synced SMB logins and the TMDB
   session don't reach Apple TV today either.

### J.2 — Providers

| Provider | Sign-in | Listing | Playback | Apple TV | Outside approval | Size |
|---|---|---|---|---|---|---|
| Provider apps' folders in Files/Finder | None (system picker) | Existing local import | Existing; the provider usually downloads the whole file first | No | None | Test and document |
| Google Drive | OAuth + PKCE, `drive.readonly` | Drive v3 `files.list` per folder | `alt=media` with Bearer and `Range`; no pre-authorized links | iPhone handoff (J.7) | Restricted-scope verification | L |
| OneDrive (personal, work/school) | OAuth + PKCE, `Files.Read offline_access` | Graph `children`, `delta` | Pre-authenticated `downloadUrl` with `Range` | Device code or handoff | Entra app registration | M |
| Dropbox | OAuth + PKCE, offline token | `list_folder` (recursive, cursor) | `get_temporary_link` (4 hours) | Handoff | Production approval | M |
| WebDAV (Nextcloud, ownCloud, Synology, QNAP, pCloud, Koofr, `rclone serve webdav`) | Basic/Digest | `PROPFIND`, depth 1 | `Range` GET | Typed or handoff | None | M |
| NFS | None (AUTH_SYS) | libvlc (works today) or libnfs | libnfs custom I/O | Yes | Export needs `insecure` | S |
| SFTP | Password (keys later) | SwiftNIO SSH | SwiftNIO SSH custom I/O | Typed or handoff | None | S–M |
| S3-compatible (AWS, B2, R2, Wasabi, MinIO) | Access key and secret | `ListObjectsV2` | SigV4 pre-signed GET | Typed or handoff | None | M |
| UPnP/DLNA (later) | None | libvlc UPnP discovery | Plain HTTP from the server | Yes | iOS multicast entitlement | M |

Not planned: Box (its token exchange needs a client secret, which an
open-source client can't keep), MEGA (client-side encryption), and FTP
(plaintext passwords). Plex, Jellyfin, and Emby APIs are a separate
media-server feature because they bring their own metadata.

`rclone serve webdav` can front Google Drive and many other services, so
WebDAV also gives power users a path that needs no provider approval.

### J.3 — Canonical source URLs

Stored `filePath` and `folderPath` values stay credential-free URLs. Every
item URL ends with the real file name, so `MediaParser` and the extension
filter work unchanged. A stable provider ID sits before it, so renames and
Drive's duplicate names don't collide.

| Kind (`MediaSourceKind` raw value) | Item URL |
|---|---|
| `smb` (existing) | `smb://host/share/path/Name.ext` |
| `nfs` | `nfs://host/export/path/Name.ext` |
| `sftp` | `sftp://host[:port]/path/Name.ext` |
| `webdav` | `davs://host[:port]/path/Name.ext` (`dav://` for plain HTTP) |
| `s3` | `s3://<account>/<bucket>/<key path>/Name.ext` |
| `gdrive` | `gdrive://<account>/<fileId>/Name.ext` |
| `onedrive` | `onedrive://<account>/<driveId>/<itemId>/Name.ext` |
| `dropbox` | `dropbox://<account>/<fileId>/Name.ext` (the percent-encoded `id:…`) |

- `<account>` is the first 32 hex digits of the SHA-256 of `kind:subject`:
  Google's `sub`, the Microsoft user `id`, Dropbox's `account_id`, or for S3
  the endpoint, bucket, and access key ID. It is hostname-safe, identical on
  every device, and doesn't expose an email address. As with SMB hosts today,
  the URL host is the key used to look up the credential.
- Folder URLs have the same shape with the folder ID. `VideoFolder` gains a
  readable `displayPath` (for example `Google Drive › My Drive › Movies`) for
  `SourceRow` and the Downloaded list.
- Raw values are persisted; never rename a case.

**Acceptance:** build/parse round trips for every kind; a parsed item URL
yields the account key, the provider ID, and the file name `MediaParser`
expects.

### J.4 — Connector layer

**Modify:** `MediaConnector.swift`, `SMBConnector.swift`,
`NetworkFolderPickerView.swift`, `AddNetworkSourceView.swift`,
`LibraryController.swift`, `VideoFolder.swift`

**Create:** `Shared/Controllers/Connectors/ConnectorFactory.swift`, one
connector file per provider

```swift
enum MediaSourceKind: String, CaseIterable, Identifiable, Sendable {
    case local, smb                                     // existing
    case nfs, sftp, webdav, s3
    case googleDrive = "gdrive", oneDrive = "onedrive", dropbox
}

struct ConnectorEntry: Identifiable, Hashable, Sendable {
    let name: String
    let url: URL
    let isDirectory: Bool
    var size: Int64? = nil
    var duration: TimeInterval? = nil   // Drive videoMediaMetadata, Graph video facet
    var modified: Date? = nil
}

protocol MediaConnector: Sendable {
    var kind: MediaSourceKind { get }
    var root: URL { get }
    /// Username or account email shown with the source; never a secret.
    var accountLabel: String? { get }
    func validate() async throws
    func list(directory: URL) async throws -> [ConnectorEntry]
    /// Every video under `folder`. The default walks `list(directory:)`
    /// breadth-first (today's `collectRemoteVideoURLs`, same 2,000-folder cap);
    /// Dropbox and OneDrive override it with their recursive listings.
    func enumerateVideos(under folder: URL) async throws -> [ConnectorEntry]
}
```

- `credential` leaves the protocol. Only the password-based connectors (SMB,
  SFTP, WebDAV, S3) carry one.
- `ConnectorFactory` rebuilds a connector from a `VideoFolder` or an item URL,
  replacing the switch in `LibraryController.connector(for:)`. It returns
  `nil` when the account or login is missing, so the source can show "Sign in
  again".
- `BrowseLocation` holds a hashable `AnyMediaConnector` box (kind and root),
  so `NetworkFolderPickerView` browses any connector.
- `AddNetworkSourceView` becomes "Link Source": a provider list, then the
  existing server form (with fields per protocol) or a sign-in step (J.6,
  J.7), then the shared folder picker. Google Drive's picker root shows My
  Drive, Shared with me, and Shared drives.
- `VideoFolder` gains optional attributes, a lightweight migration like
  `sourceKindRaw`: `displayPath`, `accountKey`, `lastScannedAt`, and
  `changeCursor`.

**Acceptance:** SMB linking, browsing, rescans, and playback behave as
before. `ConnectorTests` cover the factory, the default enumeration, and the
hashable browse location.

### J.5 — Streaming: `RemoteByteSource` and FFmpeg custom I/O

**Create:** `Shared/Playback/Remote/RemoteByteSource.swift`,
`Shared/Playback/Remote/RemoteContentResolver.swift`

**Modify:** `FFmpegReader.h`, `FFmpegReader.m`, `FFmpegDecoder.swift`,
`FormatRouter.swift`

Every HTTP-based provider streams through one `URLSession` byte source that
FFmpeg reads with custom I/O, the way SMB reads through libsmb2 today.

```objc
/// Random-access bytes for FFmpeg custom I/O. Reads run on the reader's
/// worker queue and may block; -cancel is thread-safe and fails blocked reads.
@protocol EDByteSource <NSObject>
@property (nonatomic, readonly) int64_t length;               // -1 if unknown
- (NSInteger)readAtOffset:(int64_t)offset
                     into:(uint8_t *)buffer
                   length:(NSInteger)length;                  // 0 = EOF, < 0 = error
- (void)cancel;
@end

// EDFFmpegReader
- (BOOL)openByteSource:(id<EDByteSource>)source name:(NSString *)name
                 error:(NSError **)error NS_SWIFT_NAME(open(byteSource:name:));
```

```swift
protocol RemoteContentResolver: Sendable {
    /// A request for the file's bytes: Bearer-authorized (Drive), or a
    /// short-lived pre-authorized link (OneDrive, Dropbox, S3). `refresh`
    /// forces a new token or link after a 401, 403, or 410.
    func contentRequest(refresh: Bool) async throws -> URLRequest
}
```

`RemoteByteSource`:
- Reads 4 MiB `Range` chunks, prefetches the next chunk while reads are
  sequential, and keeps up to 8 chunks (32 MiB), so the MKV cues or MP4
  `moov` at the end of a file stay cached through the open.
- Uses an ephemeral `URLSession` (no disk cache or cookies), which brings
  system certificate validation, HTTP/2, and the system proxy.
- Takes `length` from the listing's `size`, otherwise from the first
  `Content-Range`.
- Never logs pre-authorized URLs, tokens, or request headers.

| Response | Action |
|---|---|
| `206` | Serve the range |
| `200` at offset 0 | Accept: the server ignored `Range` (Microsoft Graph documents this) |
| `200` at any other offset | Retry once, then fail the read |
| `401` | Refresh the token once (single-flight), then retry |
| `403` rate limit, `429`, `5xx` | Back off (0.5 s, 1 s, 2 s, with jitter), then fail |
| `403` expired signed link, `410` (Dropbox) | Resolve a new link once, then retry |
| `404` | Fail with "This file is no longer in <provider>" |

Wiring:
- `FormatRouter` sends every remote scheme (`smb`, `nfs`, `sftp`, `dav`,
  `davs`, `s3`, `gdrive`, `onedrive`, `dropbox`) to FFmpeg, as it does for
  SMB.
- `FFmpegDecoder.open(url:)` gets the item's resolver from `ConnectorFactory`,
  builds the byte source before dispatching to its worker queue, and cancels
  it from `interrupt` and `close`.
- The reader's read/seek callbacks mirror `ed_smb_read`/`ed_smb_seek`, honor
  `_interrupted`, and answer `AVSEEK_SIZE` from `length`.
- NFS and SFTP read through `RemoteFileByteSource`: NFS with the libnfs
  symbols libvlc already exports, SFTP with SwiftNIO SSH (`SFTPConnection`).
- The generic branch in `openURL:` rejects remote schemes instead of handing
  them to FFmpeg's unverified `https`, so no token or signed link can reach it.
- visionOS: `VisionMediaInspector` can't open custom schemes, so remote items
  keep using the FFmpeg path until the AVFoundation resource loader (J.12,
  step 7) lands.

**Acceptance:** with a `URLProtocol` stub that serves the existing fixtures
with `Range` support, FFmpeg opens, plays, seeks, and switches tracks through
`RemoteByteSource` and reports the same media info as the file path.
Token-refresh, expired-link, ignored-`Range`, and cancellation cases pass.
Open and seek latency are measured with the playback probe at 50 ms and
150 ms of simulated round-trip time.

### J.6 — Accounts and sign-in

**Create:** `Shared/Controllers/Accounts/OAuthClient.swift`,
`CloudAccountStore.swift`, `CloudTokenProvider.swift`,
`Shared/Views/Settings/AccountsSection.swift`

- **No SDKs.** `OAuthClient` implements the authorization-code flow with PKCE
  (S256, CryptoKit) through `ASWebAuthenticationSession`, which captures the
  redirect itself, plus the device authorization grant (RFC 8628) for Apple TV
  where a provider allows it. This follows `KeychainStore`'s dependency-free
  approach; GoogleSignIn, MSAL, and SwiftyDropbox aren't needed.
- **Registrations:**
  - Google: one OAuth client of type iOS for `com.BaBaSaMa.Edendale` (Google
    uses the iOS type for iOS and macOS apps). Redirect
    `com.googleusercontent.apps.<id>:/oauth2redirect`; scopes `openid email
    https://www.googleapis.com/auth/drive.readonly`.
  - Microsoft: an Entra app for personal and work/school accounts, as a
    public client with a custom-scheme redirect. Enable public client flows
    for the device code flow. Scopes `Files.Read offline_access User.Read`.
  - Dropbox: a scoped app with Full Dropbox access. Scopes
    `files.metadata.read files.content.read account_info.read`; request
    `token_access_type=offline` for a refresh token.
  - Client IDs and app keys aren't secrets for PKCE clients, but they still
    go through `.secret/Secrets.xcconfig` into Info.plist like
    `TMDB_READ_ACCESS_TOKEN`, with entries in `Shared/Example.xcconfig` and
    `ci_scripts/ci_post_clone.sh`. No client secret ships anywhere.
- **Storage:** `CloudAccountStore` keeps one Keychain item per account
  (`cloud-account-<kind>-<accountKey>`: provider, subject, display email,
  refresh token, granted scopes). Access tokens live only in memory.
  `CloudTokenProvider` is an actor that runs at most one refresh per account
  and hands the result to every waiter.
- **Sync:** `KeychainStore` writes synchronizable items, so:
  - One sign-in covers iPhone, iPad, Mac, and Vision Pro.
  - Apple TV never receives it (J.1, gap 7); it gets accounts through J.7.
  - Deleting a synchronizable item deletes every synced copy, so removing a
    source never deletes an account. Sign-out is explicit in Settings →
    Accounts, says it applies to the user's synced devices, and can also
    revoke Edendale's access at the provider, which ends access on any Apple
    TV sharing that grant too.
- **Settings → Accounts** lists each linked account (provider, email, sources
  using it) with Sign Out. A source whose account is gone shows "Sign in
  again".

**Acceptance:** PKCE matches the RFC 7636 Appendix B test vector; concurrent
token requests trigger one refresh; device-code polling handles
`authorization_pending`, `slow_down`, a declined request, and
`expired_token`; no token appears in SwiftData, stored URLs, or logs.

### J.7 — Apple TV sign-in

Google sign-in does work on Apple TV. YouTube shows a code or QR code that the
user approves on a phone or computer (`yt.be/activate`). That screen is
Google's device authorization flow, and Google limits the scopes it can grant
to sign-in basics (`openid`, `email`, `profile`), YouTube (`youtube`,
`youtube.readonly`), and, for Drive, only `drive.file` and `drive.appdata`.
`drive.file` covers only files Edendale created or the user opened with
Edendale, so it can't browse an existing movie folder, and `drive.readonly` is
not available on that screen. A web page finishing the sign-in on the TV's
behalf would need an Edendale server, which AGENTS.md rules out.

So Apple TV signs in the way users already approve YouTube, on their phone,
but the full Google sign-in runs in Edendale on the iPhone or iPad, which
then hands the account to the TV over an encrypted local connection
(DeviceDiscoveryUI):

1. On Apple TV, **Link Source → Google Drive → Continue on iPhone or iPad**
   opens `DevicePicker`. It lists iPhones and iPads on the same network that
   are signed in to the Apple TV user's iCloud account, or a family member's,
   and have Edendale installed.
2. Choosing one opens `NWConnection(to: endpoint, using: .applicationService)`,
   which the system encrypts, and sends a request naming the provider and the
   TV.
3. The iPhone app, which starts an `NWListener(using: .applicationService)` at
   launch, asks for confirmation ("Link Google Drive on Living Room?"). It
   offers an account already linked on the phone, or runs the normal
   `ASWebAuthenticationSession` sign-in with `drive.readonly`.
4. The phone sends a versioned handoff message (provider, account key, display
   email, refresh token, scopes) and closes the connection.
5. The TV refreshes an access token to validate it, stores the account in its
   own Keychain (tvOS never syncs it), and continues to the folder picker.

Setup:
- The tvOS and iOS targets already share the bundle ID `com.BaBaSaMa.Edendale`,
  which DeviceDiscoveryUI requires (universal purchase).
- `Edendale/Info-tvOS.plist`: `NSApplicationServices` → `Browses` → one entry
  with `NSApplicationServiceIdentifier` (for example
  `Edendale-AccountHandoff`), `NSApplicationServiceUsageDescription`, and
  `NSApplicationServicePlatformSupport` (`iOS`, `iPadOS`).
- `Edendale/Info.plist`: `NSApplicationServices` → `Advertises` → the same
  identifier.
- The picker lists no devices in Simulator; test on hardware.
- Still to check on hardware: what the iPhone shows when Edendale is in the
  background. If the listener can't bring the app forward, post a local
  notification that opens the confirmation screen.

The same handoff can carry any source to the TV: Dropbox (which has no device
flow), and SMB, SFTP, WebDAV, or S3 logins that are tedious to type with the
Siri Remote.

| Provider | Apple TV sign-in |
|---|---|
| Google Drive | Handoff |
| Dropbox | Handoff |
| OneDrive | Device code on the TV (QR code for the `verification_uri`, plus the code: Microsoft doesn't support `verification_uri_complete`), or handoff |
| SMB, NFS, SFTP, WebDAV, S3 | Typed on the TV, or handoff |

**Acceptance:** handoff messages encode and decode, and unknown versions are
rejected; the TV rejects a handoff whose token fails to refresh; on hardware,
linking Drive on Apple TV from an iPhone works with Edendale in the
foreground and in the background.

### J.8 — Library integration

**Modify:** `LibraryController.swift`, `DownloadedView.swift`,
`SourceRow.swift`, `PlayerLogic.swift`

- Import and rescan call `enumerateVideos(under:)`. `ScannedFile.duration`
  takes `ConnectorEntry.duration`, so Drive and OneDrive items show their
  runtime instead of `--:--`.
- `rescanAllFolders()` skips a remote source scanned in the last 15 minutes
  (`lastScannedAt`) unless the user asks (⌘R or Rescan). Change cursors come
  next, stored in `changeCursor`: Dropbox `list_folder/continue`, OneDrive
  `delta`, Drive `changes.list`.
- An offline or signed-out source shows its state in `SourceRow` instead of
  setting `errorMessage` on every visit.
- Later: `PlayerLogic.siblingVideoFiles` lists a remote item's folder through
  its connector.
- The removal mismatch (J.1, gap 6): with synced logins, deleting one when a
  source is removed would sign the user's other devices out of that server.
  Recommended: make the Remove dialog accurate now, and manage saved logins
  in Settings → Accounts.
- Classification order is unchanged: list, parse file names locally, persist,
  then enrich in the background.

### J.9 — Provider notes

**Google Drive**
- List a folder with `GET /drive/v3/files`:
  `q='<folderId>' in parents and trashed = false`,
  `fields=nextPageToken,files(id,name,mimeType,size,modifiedTime,videoMediaMetadata(durationMillis),shortcutDetails)`,
  `pageSize=1000`, `supportsAllDrives=true`,
  `includeItemsFromAllDrives=true`. Roots: `root` (My Drive), a
  `sharedWithMe` query, and `GET /drive/v3/drives` (shared drives).
- Follow shortcuts (`application/vnd.google-apps.shortcut`) to their targets,
  skip other `application/vnd.google-apps.*` types (Docs, Sheets), and filter
  videos by extension rather than MIME type.
- Stream with `GET /drive/v3/files/<id>?alt=media`, `Authorization: Bearer`,
  and `Range`. A file Google flags as abusive fails with a clear message;
  never send `acknowledgeAbuse` without the user's consent.
- Back off on `403` `userRateLimitExceeded` or `rateLimitExceeded`, and on
  `429`.
- `durationMillis` can be missing until Drive finishes processing a video.
- Verification, because `drive.readonly` is a Restricted scope:
  - Requires verified ownership of edendale.babasama.com (Search Console), a
    public homepage, a privacy policy that discloses how Edendale accesses and
    uses Google user data, an unlisted YouTube demo video of the sign-in and
    scope use, and the scopes declared in the Cloud Console. The homepage and
    policy belong on the `web` branch.
  - No third-party security assessment is needed while Google data never
    passes through a server Edendale operates. Keep it that way.
  - Until verified, a user cap and a warning screen apply. While the consent
    screen is in Testing, refresh tokens expire after 7 days.
  - Google allows 100 refresh tokens per Google Account per client ID; the
    Apple TV handoff reuses the phone's token rather than minting another.
  - Start verification as soon as the Drive build can record the demo video;
    it is the long pole.

**OneDrive** (Microsoft Graph)
- List with `GET /me/drive/items/{id}/children`,
  `$select=id,name,size,folder,file,video,lastModifiedDateTime`, following
  `@odata.nextLink`. `delta` gives the whole tree plus change tracking; verify
  folder-level `delta` for work/school accounts.
- Stream from `GET /me/drive/items/{id}?select=id,@microsoft.graph.downloadUrl`.
  That URL needs no `Authorization` header, "might expire within minutes",
  and takes `Range` itself (not `/content`); it may ignore `Range` and return
  `200`.
- `Files.Read` is the least-privileged delegated permission for personal and
  work/school accounts; the `/common` tenant covers both.
- Device code: tenants `/common`, `/consumers`, or `/organizations`; the user
  has 15 minutes; personal accounts sign in again on the approving device.

**Dropbox**
- `POST /2/files/list_folder` with `recursive: true`, then
  `list_folder/continue`; keep the cursor for rescans (`list_folder/longpoll`
  can wait for changes).
- `POST /2/files/get_temporary_link` with the file's `id:`. The link lasts 4
  hours, then returns `410 Gone`; resolve a new one.
- New apps start in development status with up to 500 linked users. Once 50
  users link, the app has two weeks to apply for production status, or new
  links freeze.

**WebDAV**
- `PROPFIND` with `Depth: 1`, asking for `resourcetype`, `getcontentlength`,
  `getlastmodified`, and `displayname`; parse the `multistatus` with
  `XMLParser` and decode each `href` (absolute or relative, percent-encoded).
  Most servers disable `Depth: infinity`, so enumeration is breadth-first.
- Basic and Digest through `URLSession` authentication challenges, with the
  login in `NetworkCredentialStore` (keyed by host). Nextcloud and ownCloud
  use `/remote.php/dav/files/<user>/`.
- Self-signed certificates and plain HTTP on the LAN are open decisions
  (J.11).

**NFS**
- Browse through the existing libvlc browser (`nfs://`), or
  `nfs_opendir`/`nfs_readdir`.
- Play through libnfs custom I/O: `nfs_mount`, `nfs_open`, `nfs_pread`,
  `nfs_lseek`, `nfs_fstat64`, `nfs_set_timeout`.
- iOS can't bind privileged ports, so the export needs the `insecure` option;
  say so in the connection error.

**SFTP**
- SSH comes from Apple's SwiftNIO SSH (`swift-nio-ssh`, a Swift package):
  curve25519 and ECDH key exchange, Ed25519 and ECDSA host keys, and AES-GCM,
  which is what current OpenSSH servers offer. libvlc's libssh2 can't
  negotiate with them (J.1). SwiftNIO SSH has no RSA host keys and no
  keyboard-interactive login, so a server that offers only those gets a
  clear error.
- A small SFTP version 3 client on top (`SFTPProtocol`, `SFTPConnection`)
  lists folders, resolves symbolic links, and reads files with pipelined
  32 KiB requests. Edendale owns host-key checking rather than libvlc's `sftp`
  module.
- Trust on first use: show the server's SHA-256 host-key fingerprint and key
  type when linking, pin it in the Keychain, and refuse a changed key until
  the user approves it again.
- Password login first; key-based login later.

**S3-compatible**
- SigV4 signing with CryptoKit; `ListObjectsV2` with `prefix`, `delimiter=/`,
  and `continuation-token`.
- Stream through pre-signed GET URLs, re-signing after a `403` for expiry.
  The endpoint, region, bucket, and path-style addressing (MinIO) are stored
  with the source; the access key ID and secret fit `NetworkCredential`.

### J.10 — Privacy, secrets, and design

- No Edendale server or account: sign-in, listing, and streaming run between
  the device and the provider. These are the "user-controlled sync/storage
  services" AGENTS.md allows; README lists each provider and what it receives
  (the user's sign-in, folder listings, and file byte ranges).
- Tokens and passwords live only in the Keychain. Stored URLs, `displayPath`,
  logs, and error messages never contain them.
- No client secrets anywhere (Box is excluded for that reason). Client IDs
  follow the TMDB token's `.secret/Secrets.xcconfig` path.
- File-name classification stays local and precedes TMDB enrichment; listing
  a source is that source's own read, as with SMB.
- The library index stays on the device. Accounts are credentials and sync
  like SMB logins, except to Apple TV.
- The `web` branch stays static. It gains privacy-policy text for these
  providers (Google verification requires it), never an OAuth relay or token
  endpoint.
- Icons follow DESIGN.md: the app's own glyph family with provider names in
  text. If a provider logo is used, follow that provider's brand rules.
- Info.plist additions: `NSApplicationServices` (J.7), and
  `NSBonjourServices` if "servers nearby" discovery is added (`_smb._tcp`,
  `_nfs._tcp`, `_sftp-ssh._tcp`, `_webdav._tcp`, `_webdavs._tcp`).

### J.11 — Open decisions

1. **Account sync.** Recommended: keep `KeychainStore`'s synchronizable items
   (one sign-in for iPhone, iPad, Mac, and Vision Pro; Apple TV through the
   handoff). The alternative is device-only accounts, a stricter reading of
   AGENTS.md rule 6.
2. **Google verification.** Commit to it (no paid assessment while
   client-only), or accept the unverified user cap.
3. **Order.** Google Drive first (J.12), or the quicker NFS, SFTP, and WebDAV
   sources first.
4. **Home-server TLS.** Allow self-signed certificates with per-host pinning
   and plain HTTP on the LAN (ATS `NSAllowsLocalNetworking`), or require
   valid HTTPS.
5. **SMB login removal.** Correct the Remove dialog (recommended), or delete
   the synced login when the last source for that host is removed.

### J.12 — Build order

1. J.4 connector layer, plus J.8's rescan throttling and removal fix. No new
   provider yet.
2. J.5 `RemoteByteSource` and FFmpeg byte-source I/O, against a local stub.
3. J.6 accounts and OAuth, then Google Drive in Testing mode. Start Google
   verification.
4. J.7 Apple TV handoff, bringing Drive to tvOS.
5. OneDrive (device code on tvOS), then Dropbox.
6. NFS and SFTP custom I/O, WebDAV, and S3; optionally Bonjour "servers
   nearby".
7. Later: an `AVAssetResourceLoaderDelegate` over `RemoteByteSource`
   (AVFoundation and visionOS spatial playback from remote sources), remote
   siblings, change cursors, and UPnP.

Parity (AGENTS.md rule 7): Android and Windows need their own native
implementations, the `web` branch needs the privacy-policy text, and
`main`'s README should list the supported storage services. No code is
shared.

### J.13 — Tests

- `ConnectorTests`: canonical URL round trips, account keys, the factory, the
  default enumeration, and the hashable browse location.
- `RemoteByteSourceTests` (new): a `URLProtocol` stub serving fixtures with
  `Range` support covers chunking, prefetch, cached backward seeks, `401` →
  one refresh, `410` → a new link, an ignored `Range`, backoff, and
  cancellation.
- `FFmpegDecoderTests`: open, seek, and switch tracks on the subtitle fixture
  through `RemoteByteSource`.
- `OAuthTests` (new): the PKCE vector, authorization URL parameters, token and
  device-code responses, and single-flight refresh.
- `CloudListingTests` (new): recorded Drive, Graph, Dropbox, WebDAV, and S3
  responses covering pagination, shortcuts, folders, and filtering. No real
  credentials in tests or CI.
- `AccountHandoffTests` (new): message encoding and version rejection.
- `SFTPProtocolTests` (new): SFTP requests byte for byte, replies with every
  attribute layout, framing across partial reads, and host-key fingerprints
  that match `ssh-keygen -l`.
- Hardware only: the Apple TV handoff and real-account playback on each
  provider. README records the manual steps.

### Section J — Tracking

| Step | Description                                  | Status |
|------|----------------------------------------------|--------|
| J.3  | Canonical source URLs                        | [ ]    |
| J.4  | Connector layer                              | [ ]    |
| J.5  | `RemoteByteSource` + FFmpeg custom I/O       | [ ]    |
| J.6  | Accounts, OAuth, Settings → Accounts         | [ ]    |
| J.7  | Apple TV sign-in (DeviceDiscoveryUI handoff) | [ ]    |
| J.8  | Library integration                          | [ ]    |
| J.9  | Google Drive                                 | [ ]    |
| J.9  | OneDrive                                     | [ ]    |
| J.9  | Dropbox                                      | [ ]    |
| J.9  | NFS, SFTP                                    | [ ]    |
| J.9  | WebDAV, S3-compatible                        | [ ]    |
| J.13 | Tests                                        | [ ]    |
| —    | Google restricted-scope verification         | [ ]    |
| —    | Files/Finder provider-folder check           | [ ]    |

References, checked 2026-09-27. Provider rules change, so re-check them when
registering:
- Google device flow scopes:
  https://developers.google.com/identity/protocols/oauth2/limited-input-device
- Drive scope classes:
  https://developers.google.com/workspace/drive/api/guides/api-specific-auth
- Restricted-scope verification:
  https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification
- Refresh-token expiry and limits:
  https://developers.google.com/identity/protocols/oauth2
- Google OAuth client type for iOS and macOS:
  https://developers.google.com/identity/sign-in/ios/start-integrating
- YouTube TV sign-in: https://support.google.com/youtube/answer/3015415
- `kSecAttrSynchronizable` (no tvOS sync):
  https://developer.apple.com/documentation/security/ksecattrsynchronizable
- DeviceDiscoveryUI:
  https://developer.apple.com/documentation/devicediscoveryui/connecting-a-tvos-app-to-other-devices-over-the-local-network
- Microsoft device code flow:
  https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-device-code
- Graph download URLs and ranges:
  https://learn.microsoft.com/en-us/graph/api/driveitem-get-content
- Dropbox development status:
  https://www.dropbox.com/developers/reference/developer-guide

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
  → Downsample both frames to quarter resolution (bilinear)
  → For each 16×16 block in frame N, search a ±16 pixel window in frame N-1
  → Minimize sum of absolute luma differences (SAD)
  → Output: coarse motion vector per block (RG16Float, quarter-res)

Pass 2 — Refine (4×4 sub-blocks):
  → For each 4×4 sub-block, refine the coarse vector with a ±4 pixel search
  → Work at full resolution using the coarse vector as the search center
  → Output: refined motion vector texture (RG16Float, 1/4 pixel density)

Pass 3 — Per-pixel interpolation:
  → Bilinear-interpolate the block-level motion vectors to per-pixel density
  → Optional: median filter (3×3) to suppress outlier vectors
```

Kernel signatures:
```metal
kernel void motionEstimationCoarse(
    texture2d<float, access::read>  prevFrame   [[texture(0)]],
    texture2d<float, access::read>  currFrame   [[texture(1)]],
    texture2d<float, access::write> motionOut   [[texture(2)]],
    constant uint2                  &blockSize  [[buffer(0)]],
    constant uint                   &searchRadius [[buffer(1)]],
    uint2                           gid         [[thread_position_in_grid]]);

kernel void motionEstimationRefine(
    texture2d<float, access::read>  prevFrame   [[texture(0)]],
    texture2d<float, access::read>  currFrame   [[texture(1)]],
    texture2d<float, access::read>  coarseMV    [[texture(2)]],
    texture2d<float, access::write> refinedMV   [[texture(3)]],
    constant uint2                  &blockSize  [[buffer(0)]],
    constant uint                   &searchRadius [[buffer(1)]],
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

Scene-cut detection: if the SAD score from the coarse motion pass exceeds a
threshold (e.g. > 40% of pixels have high error), skip interpolation for that
frame pair — it's a hard cut, not motion.

### I.4 — EnhancedVideoView Draw Loop Integration

- [x] **Modify `EnhancedVideoView.swift` draw loop for frame interpolation**

The draw loop currently runs at `targetFrameRate` (default 60fps) and
presents one enhanced frame per callback. With interpolation, each source
frame produces two display frames:

```
draw call 0 (interpolated):
  → FrameInterpolator.interpolate(current: enhanced_N) → synthetic N-0.5
  → present synthetic frame
  → FrameInterpolator.commitFrame(enhanced_N)

draw call 1 (real):
  → dequeue next source frame from ring buffer
  → run enhancement pipeline → enhanced_N
  → present enhanced_N

(repeat)
```

Changes to `EnhancedVideoView`:
  - Add `var frameInterpolator: FrameInterpolator?` property
  - Add `var interpolationEnabled: Bool` toggle
  - Track `isInterpolatedFrame` flag, toggled each draw call
  - On interpolated frames: skip ring buffer dequeue, use cached enhanced
    texture as `current`, call `interpolate()`, present the result
  - On real frames: dequeue, enhance, present, call `commitFrame()`
  - Set `preferredFramesPerSecond` to 2× the source content framerate
    (e.g. 48 for 24fps content, 60 for 30fps content)
  - Frame pacing: macOS `present(afterMinimumDuration:)` already handles
    this — set duration to `1.0 / (2.0 * sourceFrameRate)`

- [x] **Handle edge cases in draw loop**
  - First frame after seek/open: no previous frame → present real frame only
  - Scene cut detected: skip interpolation, present real frame
  - Ring buffer empty: don't interpolate stale frames
  - Pause/resume: reset interpolator on resume to avoid stale history
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

- [ ] **Add interpolation draw-loop tests to `EnhancedVideoRenderingTests.swift`** (deferred — needs MainActor rendering context)

  - Verify `EnhancedVideoView` alternates between interpolated and real frames
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

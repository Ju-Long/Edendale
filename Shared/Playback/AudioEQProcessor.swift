//
//  AudioEQProcessor.swift
//  Edendale
//
//  Thread-safe 10-band parametric equalizer built on vDSP biquad
//  filters. Both the FFmpeg and AVFoundation audio paths feed PCM
//  through the same processor instance; the lock protects coefficient
//  updates against concurrent process calls from the audio tap thread.
//

import Accelerate
import AVFoundation
import CoreMedia
import os

final class AudioEQProcessor: @unchecked Sendable {

    static let bandCount = 10
    static let centerFrequencies: [Double] = [
        60, 170, 310, 600, 1000, 3000, 6000, 12000, 14000, 16000
    ]
    private static let bandwidthOctaves: Double = 1.0

    private let lock = OSAllocatedUnfairLock<State>(initialState: State())

    private struct State {
        var sampleRate: Double = 48000
        var channelCount: Int = 2
        var preampGain: Float = 1.0
        var bandGains: [Float] = Array(repeating: 0, count: bandCount)
        var isFlat = true
        var biquads: [vDSP.Biquad<Float>] = []
        /// One channel's samples on the way into and out of its filters,
        /// kept between buffers so processing allocates nothing.
        var input: [Float] = []
        var output: [Float] = []
    }

    func configure(sampleRate: Double, channelCount: Int) {
        lock.withLockUnchecked { state in
            guard sampleRate > 0, channelCount > 0 else { return }
            state.sampleRate = sampleRate
            state.channelCount = channelCount
            Self.rebuildBiquads(&state)
        }
    }

    func update(preamp: Float, bands: [Float]) {
        lock.withLockUnchecked { state in
            state.preampGain = powf(10, preamp / 20)
            state.bandGains = bands
            state.isFlat = preamp == 0 && bands.allSatisfy { $0 == 0 }
            Self.rebuildBiquads(&state)
        }
    }

    var isFlat: Bool {
        lock.withLockUnchecked { $0.isFlat }
    }

    // MARK: - AudioBufferList processing (MTAudioProcessingTap)

    func process(_ bufferList: UnsafeMutablePointer<AudioBufferList>, frameCount: Int) {
        lock.withLockUnchecked { state in
            guard !state.isFlat, !state.biquads.isEmpty else { return }
            let abl = UnsafeMutableAudioBufferListPointer(bufferList)
            let channels = min(abl.count, state.biquads.count)
            for ch in 0..<channels {
                guard let data = abl[ch].mData?.assumingMemoryBound(to: Float.self) else { continue }
                let frames = Int(abl[ch].mDataByteSize) / MemoryLayout<Float>.size
                guard frames > 0 else { continue }
                Self.filter(&state, channel: ch, samples: data, stride: 1, frames: frames)
            }
        }
    }

    // MARK: - CMSampleBuffer processing (FFmpeg path)

    /// Filters decoded PCM in place. The FFmpeg path calls this on the main
    /// actor as it hands audio to the renderer, so every pass over the
    /// samples is a vDSP call: a per-sample Swift loop over interleaved
    /// surround audio took about half the main thread in Debug builds and
    /// stalled the video.
    func processSampleBuffer(_ sampleBuffer: CMSampleBuffer) {
        guard let blockBuffer = CMSampleBufferGetDataBuffer(sampleBuffer),
              let formatDesc = CMSampleBufferGetFormatDescription(sampleBuffer),
              let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(formatDesc)
        else { return }

        let isFloat = asbd.pointee.mFormatFlags & kAudioFormatFlagIsFloat != 0
        guard isFloat, asbd.pointee.mBitsPerChannel == 32 else { return }

        var contiguousLength = 0
        var totalLength = 0
        var dataPointer: UnsafeMutablePointer<CChar>?
        guard CMBlockBufferGetDataPointer(
            blockBuffer, atOffset: 0, lengthAtOffsetOut: &contiguousLength,
            totalLengthOut: &totalLength, dataPointerOut: &dataPointer
        ) == noErr, let dataPointer, contiguousLength == totalLength else { return }

        let channels = max(Int(asbd.pointee.mChannelsPerFrame), 1)
        let isNonInterleaved = asbd.pointee.mFormatFlags & kAudioFormatFlagIsNonInterleaved != 0
        let totalFloats = totalLength / MemoryLayout<Float>.size
        let frames = totalFloats / channels
        guard frames > 0 else { return }
        let samples = UnsafeMutableRawPointer(dataPointer).bindMemory(to: Float.self, capacity: totalFloats)

        lock.withLockUnchecked { state in
            guard !state.isFlat, !state.biquads.isEmpty else { return }
            for ch in 0..<min(channels, state.biquads.count) {
                if channels == 1 || isNonInterleaved {
                    Self.filter(&state, channel: ch, samples: samples + ch * frames, stride: 1, frames: frames)
                } else {
                    Self.filter(&state, channel: ch, samples: samples + ch, stride: channels, frames: frames)
                }
            }
        }
    }

    /// Runs one channel through its filter chain and the preamp, in place.
    /// `stride` is the distance between the channel's consecutive samples:
    /// 1 for planar audio, the channel count for interleaved audio.
    private static func filter(
        _ state: inout State,
        channel: Int,
        samples: UnsafeMutablePointer<Float>,
        stride: Int,
        frames: Int
    ) {
        // A track's buffers keep one size, so this reallocates only when
        // the stream changes.
        if state.input.count != frames {
            state.input = [Float](repeating: 0, count: frames)
            state.output = [Float](repeating: 0, count: frames)
        }
        var unity: Float = 1
        var gain = state.preampGain
        let count = vDSP_Length(frames)
        vDSP_vsmul(samples, vDSP_Stride(stride), &unity, &state.input, 1, count)
        state.biquads[channel].apply(input: state.input, output: &state.output)
        vDSP_vsmul(state.output, 1, &gain, samples, vDSP_Stride(stride), count)
    }

    // MARK: - Biquad construction

    private static func rebuildBiquads(_ state: inout State) {
        guard state.channelCount > 0, state.sampleRate > 0, !state.isFlat else {
            state.biquads = []
            return
        }

        var coefficients: [Double] = []
        for i in 0..<bandCount {
            coefficients.append(contentsOf: peakingEQCoefficients(
                frequency: centerFrequencies[i],
                gainDB: Double(state.bandGains[i]),
                bandwidth: bandwidthOctaves,
                sampleRate: state.sampleRate
            ))
        }

        state.biquads = (0..<state.channelCount).compactMap { _ in
            vDSP.Biquad<Float>(
                coefficients: coefficients,
                channelCount: 1,
                sectionCount: vDSP_Length(bandCount),
                ofType: Float.self
            )
        }
    }

    private static func peakingEQCoefficients(
        frequency: Double, gainDB: Double,
        bandwidth: Double, sampleRate: Double
    ) -> [Double] {
        guard frequency < sampleRate / 2 else {
            return [1, 0, 0, 0, 0]
        }
        if abs(gainDB) < 0.01 {
            return [1, 0, 0, 0, 0]
        }

        let A = pow(10, gainDB / 40)
        let w0 = 2 * Double.pi * frequency / sampleRate
        let sinW0 = sin(w0)
        let cosW0 = cos(w0)
        let alpha = sinW0 * sinh(log(2) / 2 * bandwidth * w0 / sinW0)

        let b0 = 1 + alpha * A
        let b1 = -2 * cosW0
        let b2 = 1 - alpha * A
        let a0 = 1 + alpha / A
        let a1 = -2 * cosW0
        let a2 = 1 - alpha / A

        return [b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0]
    }
}

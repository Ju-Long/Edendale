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
                let input = Array(UnsafeBufferPointer(start: data, count: frames))
                let output = state.biquads[ch].apply(input: input)
                if state.preampGain != 1 {
                    var gain = state.preampGain
                    vDSP_vsmul(output, 1, &gain, data, 1, vDSP_Length(frames))
                } else {
                    output.withUnsafeBufferPointer { src in
                        data.update(from: src.baseAddress!, count: frames)
                    }
                }
            }
        }
    }

    // MARK: - CMSampleBuffer processing (FFmpeg path)

    func processSampleBuffer(_ sampleBuffer: CMSampleBuffer) {
        guard let blockBuffer = CMSampleBufferGetDataBuffer(sampleBuffer),
              let formatDesc = CMSampleBufferGetFormatDescription(sampleBuffer),
              let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(formatDesc)
        else { return }

        let isFloat = asbd.pointee.mFormatFlags & kAudioFormatFlagIsFloat != 0
        guard isFloat else { return }

        var length = 0
        var dataPointer: UnsafeMutablePointer<CChar>?
        guard CMBlockBufferGetDataPointer(
            blockBuffer, atOffset: 0, lengthAtOffsetOut: nil,
            totalLengthOut: &length, dataPointerOut: &dataPointer
        ) == noErr, let dataPointer else { return }

        let channels = max(Int(asbd.pointee.mChannelsPerFrame), 1)
        let isNonInterleaved = asbd.pointee.mFormatFlags & kAudioFormatFlagIsNonInterleaved != 0
        let totalFloats = length / MemoryLayout<Float>.size

        lock.withLockUnchecked { state in
            guard !state.isFlat, !state.biquads.isEmpty else { return }

            if channels == 1 || isNonInterleaved {
                let framesPerChannel = totalFloats / channels
                guard framesPerChannel > 0 else { return }
                for ch in 0..<min(channels, state.biquads.count) {
                    let offset = ch * framesPerChannel
                    let ptr = dataPointer.withMemoryRebound(
                        to: Float.self, capacity: totalFloats
                    ) { $0 + offset }
                    let input = Array(UnsafeBufferPointer(start: ptr, count: framesPerChannel))
                    let output = state.biquads[ch].apply(input: input)
                    if state.preampGain != 1 {
                        var gain = state.preampGain
                        vDSP_vsmul(output, 1, &gain, ptr, 1, vDSP_Length(framesPerChannel))
                    } else {
                        output.withUnsafeBufferPointer { src in
                            ptr.update(from: src.baseAddress!, count: framesPerChannel)
                        }
                    }
                }
            } else {
                let framesCount = totalFloats / channels
                guard framesCount > 0 else { return }
                let floats = dataPointer.withMemoryRebound(
                    to: Float.self, capacity: totalFloats
                ) { UnsafeMutableBufferPointer(start: $0, count: totalFloats) }

                var channelBufs = (0..<channels).map { ch -> [Float] in
                    var buf = [Float](repeating: 0, count: framesCount)
                    for f in 0..<framesCount { buf[f] = floats[f * channels + ch] }
                    return buf
                }
                for ch in 0..<min(channels, state.biquads.count) {
                    let output = state.biquads[ch].apply(input: channelBufs[ch])
                    if state.preampGain != 1 {
                        var gain = state.preampGain
                        var processed = [Float](repeating: 0, count: framesCount)
                        vDSP_vsmul(output, 1, &gain, &processed, 1, vDSP_Length(framesCount))
                        channelBufs[ch] = processed
                    } else {
                        channelBufs[ch] = Array(output)
                    }
                }
                for ch in 0..<channels {
                    for f in 0..<framesCount {
                        floats[f * channels + ch] = channelBufs[ch][f]
                    }
                }
            }
        }
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

package com.frosti.shell

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/** Owned and called only by the microphone thread. */
class WakeWord(assets: AssetManager, commands: String = "") {
    private val spotter = KeywordSpotter(assets, KeywordSpotterConfig(
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "wake/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                decoder = "wake/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                joiner = "wake/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"),
            tokens = "wake/tokens.txt", modelType = "zipformer2", numThreads = 1),
        keywordsFile = "wake/keywords.txt", keywordsThreshold = 0.25f, keywordsScore = 1f))
    private val stream = spotter.createStream()
    /** Satellite-mode command phrases ("/"-separated, labelled), spotted only after a wake. */
    private val commandStream = commands.takeIf { it.isNotEmpty() }?.let { spotter.createStream(it) }

    fun feed(pcm: ByteArray, length: Int): Boolean = spot(stream, pcm, length) != null

    /** The label of the command heard, e.g. "open_app=youtube", or null. */
    fun command(pcm: ByteArray, length: Int): String? = commandStream?.let { spot(it, pcm, length) }

    private fun spot(stream: com.k2fsa.sherpa.onnx.OnlineStream, pcm: ByteArray, length: Int): String? {
        // shortcut: fixed x8 gain, measured speech reaches the spotter at ~-35 dBFS from across the room;
        // close/loud speech clips. Upgrade to a capped per-block AGC if close-up wakes start failing.
        val samples = FloatArray(length / 2) { i ->
            ((((pcm[i * 2 + 1].toInt() shl 8) or (pcm[i * 2].toInt() and 255)).toShort()) / 32768f * 8f).coerceIn(-1f, 1f)
        }
        stream.acceptWaveform(samples, 16000)
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            val keyword = spotter.getResult(stream).keyword
            if (keyword.isNotEmpty()) {
                spotter.reset(stream)
                return keyword
            }
        }
        return null
    }

    fun reset() { spotter.reset(stream); commandStream?.let { spotter.reset(it) } }
    fun release() { stream.release(); commandStream?.release(); spotter.release() }
}

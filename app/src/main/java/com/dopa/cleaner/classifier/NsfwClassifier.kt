package com.dopa.cleaner.classifier

import android.content.Context
import android.graphics.Bitmap
import com.dopa.cleaner.core.NsfwScores
import kotlinx.coroutines.channels.Channel
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class NsfwClassifier private constructor(
    private val slots: List<Slot>,
) : AutoCloseable {

    private val pool = Channel<Slot>(capacity = slots.size).apply { slots.forEach { trySend(it) } }

    val parallelism: Int get() = slots.size

    private class Slot(val interpreter: Interpreter, private val inputW: Int, private val inputH: Int) {
        val input: ByteBuffer = ByteBuffer.allocateDirect(4 * inputW * inputH * 3).order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputW * inputH)
        val output = Array(1) { FloatArray(NsfwScores.LABELS.size) }

        fun run(bitmap: Bitmap): NsfwScores {
            val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw IllegalStateException("Bitmap conversion failed")
            val scaled = if (argb.width == inputW && argb.height == inputH) argb else Bitmap.createScaledBitmap(argb, inputW, inputH, true)
            try {
                scaled.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)
            } finally {
                if (scaled !== bitmap && scaled !== argb) scaled.recycle()
                if (argb !== bitmap) argb.recycle()
            }
            input.rewind()
            for (p in pixels) {
                input.putFloat(((p shr 16) and 0xFF) / 255f)
                input.putFloat(((p shr 8) and 0xFF) / 255f)
                input.putFloat((p and 0xFF) / 255f)
            }
            input.rewind()
            interpreter.run(input, output)
            return NsfwScores.fromArray(output[0].copyOf())
        }
    }

    suspend fun classify(bitmap: Bitmap): NsfwScores {
        val slot = pool.receive()
        try {
            return slot.run(bitmap)
        } finally {
            pool.trySend(slot)
        }
    }

    override fun close() {
        slots.forEach { runCatching { it.interpreter.close() } }
    }

    companion object {
        const val MODEL_ASSET = "models/nsfw_mobilenet_v2_140_224.tflite"

        @Throws(IOException::class)
        fun create(context: Context): NsfwClassifier {
            val model = loadModel(context)
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val instances = (cores / 2).coerceIn(1, 4)
            val threadsEach = (cores / instances).coerceIn(1, 4)
            val interpreters = (0 until instances).map {
                Interpreter(model.duplicate().order(ByteOrder.nativeOrder()), Interpreter.Options().setNumThreads(threadsEach))
            }
            val first = interpreters.first()
            val inTensor = first.getInputTensor(0)
            val shape = inTensor.shape()
            require(shape.size == 4 && shape[3] == 3) { "Unexpected input shape ${shape.contentToString()}" }
            require(inTensor.dataType() == DataType.FLOAT32) { "Unexpected input type ${inTensor.dataType()}" }
            val outShape = first.getOutputTensor(0).shape()
            require(outShape.last() == NsfwScores.LABELS.size) { "Unexpected output shape ${outShape.contentToString()}" }
            return NsfwClassifier(interpreters.map { Slot(it, inputW = shape[2], inputH = shape[1]) })
        }

        private fun loadModel(context: Context): MappedByteBuffer {
            val fd = try {
                context.assets.openFd(MODEL_ASSET)
            } catch (e: IOException) {
                throw IOException("Modelo NSFW ausente em assets/$MODEL_ASSET. Rode ./gradlew fetchNsfwModel.", e)
            }
            fd.use { afd ->
                FileInputStream(afd.fileDescriptor).use { stream ->
                    return stream.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
                }
            }
        }
    }
}

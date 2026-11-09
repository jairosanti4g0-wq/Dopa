package com.dopa.cleaner.classifier

import android.content.Context
import android.graphics.Bitmap
import com.dopa.cleaner.core.NsfwScores
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class NsfwClassifier private constructor(
    private val interpreter: Interpreter,
    private val inputW: Int,
    private val inputH: Int,
) : AutoCloseable {

    private val mutex = Mutex()
    private val input: ByteBuffer = ByteBuffer
        .allocateDirect(4 * inputW * inputH * 3)
        .order(ByteOrder.nativeOrder())
    private val pixels = IntArray(inputW * inputH)
    private val output = Array(1) { FloatArray(NsfwScores.LABELS.size) }

    suspend fun classify(bitmap: Bitmap): NsfwScores = mutex.withLock {
        val scaled = if (bitmap.width == inputW && bitmap.height == inputH && bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw IllegalStateException("Bitmap conversion failed")
            Bitmap.createScaledBitmap(argb, inputW, inputH, true)
        }
        scaled.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)
        input.rewind()
        for (p in pixels) {
            input.putFloat(((p shr 16) and 0xFF) / 255f)
            input.putFloat(((p shr 8) and 0xFF) / 255f)
            input.putFloat((p and 0xFF) / 255f)
        }
        input.rewind()
        interpreter.run(input, output)
        NsfwScores.fromArray(output[0].copyOf())
    }

    override fun close() {
        interpreter.close()
    }

    companion object {
        const val MODEL_ASSET = "models/nsfw_mobilenet_v2_140_224.tflite"

        @Throws(IOException::class)
        fun create(context: Context): NsfwClassifier {
            val model = loadModel(context)
            val options = Interpreter.Options().apply {
                setNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            }
            val interpreter = Interpreter(model, options)
            val inTensor = interpreter.getInputTensor(0)
            val shape = inTensor.shape()
            require(shape.size == 4 && shape[3] == 3) { "Unexpected input shape ${shape.contentToString()}" }
            require(inTensor.dataType() == DataType.FLOAT32) { "Unexpected input type ${inTensor.dataType()}" }
            val outShape = interpreter.getOutputTensor(0).shape()
            require(outShape.last() == NsfwScores.LABELS.size) { "Unexpected output shape ${outShape.contentToString()}" }
            return NsfwClassifier(interpreter, inputW = shape[2], inputH = shape[1])
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

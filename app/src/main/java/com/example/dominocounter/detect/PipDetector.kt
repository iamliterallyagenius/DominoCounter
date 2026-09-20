package com.example.dominocounter.detect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** The app was built without `assets/pip_detector.tflite` — train it first (see /training). */
class ModelMissingException : Exception("assets/${PipDetector.MODEL_ASSET} not found")

/** [x],[y] is the centre, in pixels of the photo that was scanned. */
class DetectedPip(val x: Float, val y: Float, val w: Float, val h: Float, val value: Int)

class DetectionResult(val pips: List<DetectedPip>, val inferenceMs: Long)

/**
 * Finds every pip on the table in one still photo.
 *
 * Deliberately not a live-video analyzer: it runs once per shutter press, so the model can be
 * run at a resolution high enough for small pips and still feel instant.
 *
 * Owns a native interpreter — call [close] when done. Everything model-specific (input size,
 * output layout, what a class is worth, thresholds) is read from the model files rather than
 * hard-coded, so retraining never means editing this class.
 */
class PipDetector(private val context: Context) : AutoCloseable {

    private class Model(
        val interpreter: Interpreter,
        val inputW: Int,
        val inputH: Int,
        /** True for 3 x H x W input (PyTorch exporters), false for H x W x 3 (TensorFlow exporters). */
        val channelsFirst: Boolean,
        val outDimA: Int,
        val outDimB: Int,
        val classValues: IntArray,
        val conf: Float,
        val iou: Float
    ) {
        val input: ByteBuffer = ByteBuffer.allocateDirect(4 * inputW * inputH * 3).order(ByteOrder.nativeOrder())
        val output: ByteBuffer = ByteBuffer.allocateDirect(4 * outDimA * outDimB).order(ByteOrder.nativeOrder())
        val outputFloats = FloatArray(outDimA * outDimB)
    }

    private var model: Model? = null

    /**
     * Blocking and CPU-heavy: call off the main thread.
     *
     * @throws ModelMissingException if the model asset isn't in the app
     */
    @Synchronized
    fun detect(photo: Bitmap): DetectionResult {
        val started = SystemClock.elapsedRealtime()
        val m = model ?: load().also { model = it }

        val letterbox = Letterbox(photo.width, photo.height, m.inputW, m.inputH)
        writeInput(photo, letterbox, m)

        m.input.rewind()
        m.output.rewind()
        m.interpreter.run(m.input, m.output)
        m.output.rewind()
        m.output.asFloatBuffer().get(m.outputFloats)

        val boxes = YoloDecoder.nms(
            YoloDecoder.decode(m.outputFloats, m.outDimA, m.outDimB, m.inputW, m.inputH, m.conf),
            m.iou
        )
        val pips = boxes
            .map {
                DetectedPip(
                    x = letterbox.toSourceX(it.cx),
                    y = letterbox.toSourceY(it.cy),
                    w = letterbox.toSourceLength(it.w),
                    h = letterbox.toSourceLength(it.h),
                    value = m.classValues[it.classId]
                )
            }
            // A hit inside the grey letterbox padding isn't on the photo at all.
            .filter { it.x in 0f..photo.width.toFloat() && it.y in 0f..photo.height.toFloat() }

        return DetectionResult(pips, SystemClock.elapsedRealtime() - started)
    }

    /** Loads the model now instead of on the first [detect]. Same failures as [detect]. */
    @Synchronized
    fun warmUp() {
        if (model == null) model = load()
    }

    @Synchronized
    override fun close() {
        model?.interpreter?.close()
        model = null
    }

    // ------------------------------------------------------------------ loading

    private fun load(): Model {
        if (context.assets.list("")?.contains(MODEL_ASSET) != true) throw ModelMissingException()

        // Needs an uncompressed asset (see noCompress in build.gradle.kts) so it can be mapped.
        val buffer = context.assets.openFd(MODEL_ASSET).use { fd ->
            FileInputStream(fd.fileDescriptor).channel
                .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }

        val interpreter = Interpreter(
            buffer,
            Interpreter.Options().setNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
        )
        try {
            val inputTensor = interpreter.getInputTensor(0)
            val inShape = inputTensor.shape() // [1, H, W, 3] or [1, 3, H, W], depending on the exporter
            check(inShape.size == 4 && (inShape[3] == 3 || inShape[1] == 3)) {
                "Unexpected model input shape ${inShape.toList()}"
            }
            val channelsFirst = inShape[3] != 3
            check(inputTensor.dataType() == DataType.FLOAT32) {
                "Model input is ${inputTensor.dataType()}; export it as float32"
            }

            val outShape = interpreter.getOutputTensor(0).shape() // [1, A, B]
            check(outShape.size == 3) { "Unexpected model output shape ${outShape.toList()}" }
            val classes = minOf(outShape[1], outShape[2]) - 4

            val config = readConfig()
            val classValues = config?.optJSONArray("classValues")?.let { array ->
                IntArray(array.length()) { array.getInt(it) }
            } ?: run {
                check(classes == 1) { "A $classes-class model needs assets/$CONFIG_ASSET to say what each class is worth" }
                intArrayOf(1)
            }
            check(classValues.size == classes) {
                "Model has $classes classes but $CONFIG_ASSET lists ${classValues.size}"
            }

            return Model(
                interpreter = interpreter,
                inputW = if (channelsFirst) inShape[3] else inShape[2],
                inputH = if (channelsFirst) inShape[2] else inShape[1],
                channelsFirst = channelsFirst,
                outDimA = outShape[1],
                outDimB = outShape[2],
                classValues = classValues,
                conf = config?.optDouble("conf", DEFAULT_CONF.toDouble())?.toFloat() ?: DEFAULT_CONF,
                iou = config?.optDouble("iou", DEFAULT_IOU.toDouble())?.toFloat() ?: DEFAULT_IOU
            )
        } catch (t: Throwable) {
            interpreter.close()
            throw t
        }
    }

    private fun readConfig(): JSONObject? {
        if (context.assets.list("")?.contains(CONFIG_ASSET) != true) return null
        return JSONObject(context.assets.open(CONFIG_ASSET).bufferedReader().use { it.readText() })
    }

    // ------------------------------------------------------------------ preprocessing

    /** Scales the photo into the model's input, keeping its shape, as RGB floats in 0..1. */
    private fun writeInput(photo: Bitmap, box: Letterbox, m: Model) {
        val canvasBitmap = Bitmap.createBitmap(m.inputW, m.inputH, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(m.inputW * m.inputH)
        try {
            val scaledW = Math.round(photo.width * box.scale).coerceAtLeast(1)
            val scaledH = Math.round(photo.height * box.scale).coerceAtLeast(1)
            val scaled = halveUntilClose(photo, scaledW, scaledH)

            Canvas(canvasBitmap).apply {
                drawColor(Color.rgb(PAD_GREY, PAD_GREY, PAD_GREY)) // the grey Ultralytics pads with
                drawBitmap(
                    scaled, null,
                    RectF(box.padX, box.padY, box.padX + photo.width * box.scale, box.padY + photo.height * box.scale),
                    Paint(Paint.FILTER_BITMAP_FLAG)
                )
            }
            if (scaled !== photo) scaled.recycle()

            canvasBitmap.getPixels(pixels, 0, m.inputW, 0, 0, m.inputW, m.inputH)
        } finally {
            canvasBitmap.recycle()
        }

        m.input.rewind()
        InputPacker.pack(pixels, m.inputW, m.inputH, m.channelsFirst, m.input.asFloatBuffer())
    }

    /**
     * One bilinear pass only samples a 2x2 neighbourhood, so shrinking a 12 MP photo to
     * 960 px in a single step would skip pixels and can erase a pip. Halving until within 2x
     * of the target averages everything first.
     */
    private fun halveUntilClose(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        var current = src
        while (current.width / 2 >= targetW && current.height / 2 >= targetH) {
            val next = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
            if (current !== src) current.recycle()
            current = next
        }
        return current
    }

    companion object {
        const val MODEL_ASSET = "pip_detector.tflite"
        const val CONFIG_ASSET = "pip_model.json"

        private const val DEFAULT_CONF = 0.3f
        private const val DEFAULT_IOU = 0.5f
        private const val PAD_GREY = 114
    }
}

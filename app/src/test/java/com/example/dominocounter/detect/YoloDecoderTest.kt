package com.example.dominocounter.detect

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class YoloDecoderTest {

    /** A flat output tensor plus its two non-batch dimensions, as the interpreter would hand it over. */
    private class Tensor(val raw: FloatArray, val dimA: Int, val dimB: Int)

    /**
     * Builds an output tensor from per-anchor rows of `[cx, cy, w, h, score...]`. Real models
     * have thousands of anchors and a handful of features, so unused anchors are zero-padded
     * up to [anchors]; the decoder relies on that shape to tell the two layouts apart.
     */
    private fun featuresFirst(rows: List<FloatArray>, anchors: Int = 12): Tensor {
        val features = rows.first().size
        val raw = FloatArray(features * anchors)
        rows.forEachIndexed { a, row -> row.forEachIndexed { f, v -> raw[f * anchors + a] = v } }
        return Tensor(raw, features, anchors)
    }

    private fun featuresLast(rows: List<FloatArray>, anchors: Int = 12): Tensor {
        val features = rows.first().size
        val raw = FloatArray(features * anchors)
        rows.forEachIndexed { a, row -> row.forEachIndexed { f, v -> raw[a * features + f] = v } }
        return Tensor(raw, anchors, features)
    }

    private fun decode(t: Tensor, conf: Float = 0.25f, size: Int = 960) =
        YoloDecoder.decode(t.raw, t.dimA, t.dimB, size, size, conf)

    /** Two overlapping hits on one pip and one weak miss, one class. */
    private val onePip = listOf(
        floatArrayOf(0.50f, 0.50f, 0.10f, 0.10f, 0.90f),
        floatArrayOf(0.50f, 0.51f, 0.10f, 0.10f, 0.80f),
        floatArrayOf(0.20f, 0.20f, 0.05f, 0.05f, 0.10f)
    )

    @Test
    fun `features-first layout is decoded and scaled from normalised to input pixels`() {
        val dets = decode(featuresFirst(onePip))

        assertThat(dets).hasSize(2)
        assertThat(dets[0].cx).isWithin(1e-3f).of(480f)
        assertThat(dets[0].w).isWithin(1e-3f).of(96f)
        assertThat(dets[0].score).isEqualTo(0.90f)
    }

    @Test
    fun `features-last layout gives the same detections`() {
        assertThat(decode(featuresLast(onePip))).isEqualTo(decode(featuresFirst(onePip)))
    }

    @Test
    fun `pixel-space boxes are left alone`() {
        val rows = listOf(
            floatArrayOf(480f, 480f, 10f, 10f, 0.9f),
            floatArrayOf(100f, 100f, 10f, 10f, 0.9f)
        )
        val dets = decode(featuresFirst(rows))

        assertThat(dets).hasSize(2)
        assertThat(dets[0].cx).isEqualTo(480f)
        assertThat(dets[0].w).isEqualTo(10f)
    }

    @Test
    fun `strongest class wins per anchor`() {
        // Two classes: [cx, cy, w, h, score(class 0), score(class 1)].
        val rows = listOf(
            floatArrayOf(0.3f, 0.3f, 0.1f, 0.1f, 0.2f, 0.9f),
            floatArrayOf(0.7f, 0.7f, 0.1f, 0.1f, 0.6f, 0.3f)
        )
        val dets = decode(featuresFirst(rows, anchors = 12))

        assertThat(dets.map { it.classId }).containsExactly(1, 0).inOrder()
    }

    @Test
    fun `nothing above the threshold decodes to nothing`() {
        assertThat(decode(featuresFirst(onePip), conf = 0.95f)).isEmpty()
    }

    @Test
    fun `nms keeps one of two overlapping boxes and the higher score`() {
        val kept = YoloDecoder.nms(decode(featuresFirst(onePip)), 0.5f)

        assertThat(kept).hasSize(1)
        assertThat(kept[0].score).isEqualTo(0.90f)
    }

    @Test
    fun `nms keeps touching but distinct pips`() {
        // Two pips side by side, boxes just touching: IoU is 0, both must survive.
        val a = Detection(100f, 100f, 20f, 20f, 0, 0.9f)
        val b = Detection(120f, 100f, 20f, 20f, 0, 0.8f)

        assertThat(YoloDecoder.nms(listOf(a, b), 0.5f)).hasSize(2)
    }

    @Test
    fun `nms is class-agnostic`() {
        val a = Detection(100f, 100f, 20f, 20f, 3, 0.9f)
        val b = Detection(101f, 100f, 20f, 20f, 5, 0.8f)

        assertThat(YoloDecoder.nms(listOf(a, b), 0.5f)).containsExactly(a)
    }

    @Test
    fun `letterbox maps model coordinates back onto a landscape photo`() {
        val box = Letterbox(srcW = 4000, srcH = 3000, dstW = 960, dstH = 960)

        assertThat(box.scale).isWithin(1e-6f).of(0.24f)
        assertThat(box.padX).isWithin(1e-3f).of(0f)
        assertThat(box.padY).isWithin(1e-3f).of(120f)
        assertThat(box.toSourceX(480f)).isWithin(1e-2f).of(2000f)
        assertThat(box.toSourceY(480f)).isWithin(1e-2f).of(1500f)
        assertThat(box.toSourceLength(24f)).isWithin(1e-3f).of(100f)
    }

    @Test
    fun `letterbox maps model coordinates back onto a portrait photo`() {
        val box = Letterbox(srcW = 3000, srcH = 4000, dstW = 960, dstH = 960)

        assertThat(box.padX).isWithin(1e-3f).of(120f)
        assertThat(box.padY).isWithin(1e-3f).of(0f)
        assertThat(box.toSourceX(120f)).isWithin(1e-2f).of(0f)
        assertThat(box.toSourceX(840f)).isWithin(1e-2f).of(3000f)
    }
}

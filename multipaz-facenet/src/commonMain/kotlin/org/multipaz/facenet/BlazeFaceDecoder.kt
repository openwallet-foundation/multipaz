package org.multipaz.facenet

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 2D anchor box for SSD detection.
 */
data class BlazeFaceAnchor(
    val xCenter: Float,
    val yCenter: Float,
    val w: Float,
    val h: Float
)

/**
 * Detection result from BlazeFace detector containing bounding box,
 * 6 facial landmarks, confidence score, and estimated head pose.
 */
data class BlazeFaceDetection(
    val score: Float,
    val boundingBox: FaceBoundingBox,
    val rightEye: FacePoint2D,
    val leftEye: FacePoint2D,
    val noseTip: FacePoint2D,
    val mouthCenter: FacePoint2D,
    val rightEarTragus: FacePoint2D,
    val leftEarTragus: FacePoint2D,
    override val yaw: Float,
    override val pitch: Float,
    override val roll: Float
) : DetectedFacePose

/**
 * Pure Kotlin decoder for Google MediaPipe BlazeFace detection tensors.
 *
 * Implements SSD anchor generation, bounding box & 6-landmark decoding,
 * non-maximum suppression (NMS), and 3D head pose estimation from 2D keypoints.
 */
object BlazeFaceDecoder {

    const val INPUT_SIZE = 128
    const val NUM_ANCHORS = 896
    const val NUM_COORDS = 16
    const val DEFAULT_SCORE_THRESHOLD = 0.6f
    const val DEFAULT_IOU_THRESHOLD = 0.3f

    val ANCHORS: List<BlazeFaceAnchor> = generateAnchors()

    /**
     * Generates canonical 896 SSD anchors for 128x128 BlazeFace short-range model.
     */
    fun generateAnchors(): List<BlazeFaceAnchor> {
        val anchors = ArrayList<BlazeFaceAnchor>(NUM_ANCHORS)
        val strides = intArrayOf(8, 16, 16, 16)
        val minScale = 0.1484375f
        val maxScale = 0.75f
        val anchorOffsetX = 0.5f
        val anchorOffsetY = 0.5f

        fun calculateScale(strideIndex: Int): Float {
            return minScale + (maxScale - minScale) * strideIndex / (strides.size - 1.0f)
        }

        var layerId = 0
        while (layerId < strides.size) {
            val aspectRatios = mutableListOf<Float>()
            val scales = mutableListOf<Float>()

            var lastSameStrideLayer = layerId
            while (lastSameStrideLayer < strides.size && strides[lastSameStrideLayer] == strides[layerId]) {
                val scale = calculateScale(lastSameStrideLayer)
                aspectRatios.add(1.0f)
                scales.add(scale)

                val scaleNext = if (lastSameStrideLayer == strides.size - 1) {
                    1.0f
                } else {
                    calculateScale(lastSameStrideLayer + 1)
                }
                scales.add(sqrt(scale * scaleNext))
                aspectRatios.add(1.0f)

                lastSameStrideLayer++
            }

            val stride = strides[layerId]
            val featureMapHeight = ceil(INPUT_SIZE.toFloat() / stride).toInt()
            val featureMapWidth = ceil(INPUT_SIZE.toFloat() / stride).toInt()

            for (y in 0 until featureMapHeight) {
                for (x in 0 until featureMapWidth) {
                    for (anchorId in aspectRatios.indices) {
                        val xCenter = (x + anchorOffsetX) / featureMapWidth
                        val yCenter = (y + anchorOffsetY) / featureMapHeight
                        anchors.add(
                            BlazeFaceAnchor(
                                xCenter = xCenter,
                                yCenter = yCenter,
                                w = 1.0f,
                                h = 1.0f
                            )
                        )
                    }
                }
            }
            layerId = lastSameStrideLayer
        }
        return anchors
    }

    /**
     * Decodes raw regressor and classificator output tensors into structured detections.
     *
     * @param rawBoxes Flattened float array of size [896 * 16].
     * @param rawScores Flattened float array of size [896].
     * @param imageWidth Original input image width in pixels.
     * @param imageHeight Original input image height in pixels.
     * @param scoreThreshold Confidence threshold in range [0, 1].
     * @param iouThreshold Overlap threshold for Non-Maximum Suppression.
     */
    fun decode(
        rawBoxes: FloatArray,
        rawScores: FloatArray,
        imageWidth: Double,
        imageHeight: Double,
        scoreThreshold: Float = DEFAULT_SCORE_THRESHOLD,
        iouThreshold: Float = DEFAULT_IOU_THRESHOLD
    ): List<BlazeFaceDetection> {
        val maxDim = max(imageWidth, imageHeight)
        val scale = INPUT_SIZE.toDouble() / maxDim
        val padX = (INPUT_SIZE.toDouble() - imageWidth * scale) / 2.0
        val padY = (INPUT_SIZE.toDouble() - imageHeight * scale) / 2.0

        val candidates = mutableListOf<BlazeFaceDetection>()

        for (i in 0 until NUM_ANCHORS) {
            val logit = rawScores[i].coerceIn(-100.0f, 100.0f)
            val score = 1.0f / (1.0f + exp(-logit))
            if (score < scoreThreshold) continue

            val anchor = ANCHORS[i]
            val boxOffset = i * NUM_COORDS

            // XYWH format (reverse_output_order = true in MediaPipe config)
            val xc = rawBoxes[boxOffset + 0] / INPUT_SIZE.toFloat() * anchor.w + anchor.xCenter
            val yc = rawBoxes[boxOffset + 1] / INPUT_SIZE.toFloat() * anchor.h + anchor.yCenter
            val bw = rawBoxes[boxOffset + 2] / INPUT_SIZE.toFloat() * anchor.w
            val bh = rawBoxes[boxOffset + 3] / INPUT_SIZE.toFloat() * anchor.h

            fun mapX(normX: Float): Double = (normX * INPUT_SIZE.toDouble() - padX) / scale
            fun mapY(normY: Float): Double = (normY * INPUT_SIZE.toDouble() - padY) / scale

            val origCenterX = mapX(xc)
            val origCenterY = mapY(yc)
            val origW = (bw * INPUT_SIZE.toDouble()) / scale
            val origH = (bh * INPUT_SIZE.toDouble()) / scale
            val origLeft = (origCenterX - origW / 2.0).coerceAtLeast(0.0)
            val origTop = (origCenterY - origH / 2.0).coerceAtLeast(0.0)
            val bb = FaceBoundingBox(origLeft, origTop, origW, origH)

            // Keypoints: 0=rightEye, 1=leftEye, 2=noseTip, 3=mouthCenter, 4=rightEar, 5=leftEar
            val kpx = FloatArray(6)
            val kpy = FloatArray(6)
            for (k in 0 until 6) {
                val kpOffset = boxOffset + 4 + k * 2
                kpx[k] = rawBoxes[kpOffset + 0] / INPUT_SIZE.toFloat() * anchor.w + anchor.xCenter
                kpy[k] = rawBoxes[kpOffset + 1] / INPUT_SIZE.toFloat() * anchor.h + anchor.yCenter
            }

            val rightEye = FacePoint2D(mapX(kpx[0]), mapY(kpy[0]))
            val leftEye = FacePoint2D(mapX(kpx[1]), mapY(kpy[1]))
            val noseTip = FacePoint2D(mapX(kpx[2]), mapY(kpy[2]))
            val mouthCenter = FacePoint2D(mapX(kpx[3]), mapY(kpy[3]))
            val rightEar = FacePoint2D(mapX(kpx[4]), mapY(kpy[4]))
            val leftEar = FacePoint2D(mapX(kpx[5]), mapY(kpy[5]))

            val (yaw, pitch, roll) = computePose(rightEye, leftEye, noseTip, mouthCenter)

            candidates.add(
                BlazeFaceDetection(
                    score = score,
                    boundingBox = bb,
                    rightEye = rightEye,
                    leftEye = leftEye,
                    noseTip = noseTip,
                    mouthCenter = mouthCenter,
                    rightEarTragus = rightEar,
                    leftEarTragus = leftEar,
                    yaw = yaw,
                    pitch = pitch,
                    roll = roll
                )
            )
        }

        return nms(candidates, iouThreshold)
    }

    /**
     * Estimates head pose (yaw, pitch, roll in degrees) from 2D facial keypoints.
     */
    fun computePose(
        rightEye: FacePoint2D,
        leftEye: FacePoint2D,
        noseTip: FacePoint2D,
        mouthCenter: FacePoint2D
    ): Triple<Float, Float, Float> {
        val eyeDx = leftEye.x - rightEye.x
        val eyeDy = leftEye.y - rightEye.y
        val eyeDist = hypot(eyeDx, eyeDy)
        if (eyeDist <= 1.0) {
            return Triple(0f, 0f, 0f)
        }

        val ux = eyeDx / eyeDist
        val uy = eyeDy / eyeDist
        val vx = -uy
        val vy = ux

        // Roll: Angle of eye line. Positive = head tilted toward left shoulder (CCW in screen).
        val rollDeg = -atan2(eyeDy, eyeDx) * 180.0 / PI

        // Eye midpoint
        val eyeMidX = (rightEye.x + leftEye.x) / 2.0
        val eyeMidY = (rightEye.y + leftEye.y) / 2.0

        val noseDx = noseTip.x - eyeMidX
        val noseDy = noseTip.y - eyeMidY

        // Yaw: Horizontal displacement of nose along eye axis.
        // Positive = turned to person's left (facing camera right), matching MLKit.
        val noseAlongEyes = noseDx * ux + noseDy * uy
        val yawRatio = noseAlongEyes / eyeDist
        val yawDeg = atan(yawRatio / 0.833) * 180.0 / PI

        // Pitch: Ratio of nose-to-mouth vs eye-to-nose distances.
        // Positive = tilted up (looking up), matching MLKit.
        val noseAlongFace = noseDx * vx + noseDy * vy
        val mouthDx = mouthCenter.x - noseTip.x
        val mouthDy = mouthCenter.y - noseTip.y
        val mouthAlongFace = mouthDx * vx + mouthDy * vy

        val pitchDelta = (mouthAlongFace - noseAlongFace) / eyeDist - 0.10
        val pitchDeg = atan(pitchDelta * 1.5) * 180.0 / PI

        return Triple(yawDeg.toFloat(), pitchDeg.toFloat(), rollDeg.toFloat())
    }

    private fun nms(
        candidates: List<BlazeFaceDetection>,
        iouThreshold: Float
    ): List<BlazeFaceDetection> {
        val sorted = candidates.sortedByDescending { it.score }
        val selected = mutableListOf<BlazeFaceDetection>()
        for (candidate in sorted) {
            var shouldSelect = true
            for (sel in selected) {
                if (computeIoU(candidate.boundingBox, sel.boundingBox) > iouThreshold) {
                    shouldSelect = false
                    break
                }
            }
            if (shouldSelect) {
                selected.add(candidate)
            }
        }
        return selected
    }

    private fun computeIoU(b1: FaceBoundingBox, b2: FaceBoundingBox): Double {
        val x1 = max(b1.left, b2.left)
        val y1 = max(b1.top, b2.top)
        val x2 = min(b1.right, b2.right)
        val y2 = min(b1.bottom, b2.bottom)
        val intersectionW = max(0.0, x2 - x1)
        val intersectionH = max(0.0, y2 - y1)
        val intersection = intersectionW * intersectionH
        val union = b1.width * b1.height + b2.width * b2.height - intersection
        return if (union > 0.0) intersection / union else 0.0
    }
}

package org.multipaz.compose.camera

import androidx.compose.ui.graphics.Matrix
import kotlinx.io.bytestring.ByteString
import org.multipaz.facematch.CameraFrame as FacematchCameraFrame
import org.multipaz.facematch.PixelFormat

/**
 * Type containing a frame captured from the [Camera] composable.
 */
data class CameraFrame(

    /** The platform-specific image. */
    val cameraImage: CameraImage,

    /**
     * Image width in pixels.
     */
    val width: Int,

    /**
     * Image height in pixels.
     */
    val height: Int,

    /**
     * The device screen orientation angle in degrees clockwise from portrait (0) at the moment the image was taken.
     */
    val rotation: Int,

    /**
     * A matrix to convert from coordinates in [CameraImage] to coordinates in the preview.
     *
     * This can be used to render graphics on top of the preview, for example to put boxes
     * around barcodes or to draw facial landmarks.
     *
     * This may involve mirroring, scaling, translation, and rotation.
     *
     * If preview is disabled, this is the identity matrix.
     */
    val previewTransformation: Matrix

) {
    /** Determine if the rotation angle indicates the camera was used from a landscape phone orientation mode. */
    val isLandscape: Boolean = (rotation == 90 || rotation == 270)
}

/**
 * Converts this compose [CameraFrame] to an [FacematchCameraFrame].
 */
fun CameraFrame.toCameraFrame(): FacematchCameraFrame {
    return FacematchCameraFrame(
        width = width,
        height = height,
        rotationDegrees = rotation,
        pixelFormat = PixelFormat.UNKNOWN,
        data = ByteString(),
        platformHandle = cameraImage.platformHandle
    )
}

/**
 * Converts this compose [CameraFrame] to an [FacematchCameraFrame].
 */
fun CameraFrame.toPromptCameraFrame(): FacematchCameraFrame = toCameraFrame()
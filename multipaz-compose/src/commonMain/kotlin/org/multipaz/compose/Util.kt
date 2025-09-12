package org.multipaz.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisallowComposableCalls
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.bytestring.ByteString
import org.multipaz.compose.camera.CameraFrame
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

data class ApplicationInfo(
    val name: String,
    val icon: ImageBitmap
)

/**
 * Gets information about an application.
 *
 * This may throw if the application does not have permission to obtain this information or the application
 * isn't installed.
 *
 * @param appId the application identifier.
 * @return an `ApplicationInfo` instance
 */
expect fun getApplicationInfo(appId: String): ApplicationInfo

/**
 * Decodes a bitmap image.
 *
 * @param encodedData encoded data in PNG, JPEG, or other well-known file formats.
 * @return the decoded bitmap, as a [ImageBitmap].
 */
expect fun decodeImage(encodedData: ByteArray): ImageBitmap

/**
 * Encodes a bitmap to PNG.
 *
 * @param image the image to encode.
 * @return a [ByteString] with the encoded data.
 */
expect fun encodeImageToPng(image: ImageBitmap): ByteString

/**
 * Extract an arbitrary geometry rectangular bitmap from the original bitmap.
 *
 * The method can be used to crop and level the face image from the captured bitmap using MLKit face detection data
 * as needed for further processing.
 *
 * **Usage Example**
 *
 * ```kotlin
 *     // Extract the square shaped face bitmap around the center point between eyes and level it by eyes line.
 *     val faceCenterX = (leftEye.position.x + rightEye.position.x) / 2
 *     val faceCenterY = (leftEye.position.y + rightEye.position.y) / 2
 *     val eyeOffsetX = leftEye.position.x - rightEye.position.x
 *     val eyeOffsetY = leftEye.position.y - rightEye.position.y
 *     val eyeDistance = sqrt(eyeOffsetX * eyeOffsetX + eyeOffsetY * eyeOffsetY)
 *     val faceWidth = eyeDistance * 3 // Heuristic multiplier to fit the face normalized to the eyes pupilar distance.
 *     val eyesAngleRad = atan2(eyeOffsetY, eyeOffsetX)
 *     val eyesAngleDeg = eyesAngleRad * 180.0 / PI // Convert radians to degrees
 *
 *     // Call platform dependent bitmap transformation.
 *     val croppedFaceImageBitmap =  cropRotateScaleImage(
 *         frameData = frameData, // Platform-specific image data.
 *         cx = faceCenterX.toDouble(), // Point between eyes
 *         cy = faceCenterY.toDouble(), // Point between eyes
 *         angleDegrees = eyesAngleDeg, // Eyes line rotation.
 *         outputWidth = faceWidth.toInt(), // Expected face width for cropping *before* final scaling.
 *         outputHeight = faceWidth.toInt(),// Expected face height for cropping *before* final scaling.
 *         targetWidth = 256 // Final square image size (for database saving and face matching tasks).
 *     )
 * ```
 *
 * @param frameData The camera frame data containing the original platform image bitmap reference and data.
 * @param cx The x-coordinate of the transformations center point in the original image (new image center).
 * @param cy The y-coordinate of the transformations center point in the original image (new image center).
 * @param angleDegrees The angle of rotation in degrees around the center point.
 * @param outputWidth The desired width of the output image.
 * @param outputHeight The desired height of the output image.
 * @param targetWidth The desired width of the final image after scaling.
 *
 * @return An ImageBitmap representing the rotated, cropped, and scaled image.
 */
expect fun cropRotateScaleImage(
    frameData: CameraFrame,
    cx: Double,
    cy: Double,
    angleDegrees: Double,
    outputWidthPx: Int,
    outputHeightPx: Int,
    targetWidthPx: Int
): ImageBitmap

/**
 * Extract an arbitrary geometry rectangular bitmap from the original bitmap.
 *
 * The method can be used to crop and level the face image from the captured bitmap using MLKit face detection data
 * as needed for further processing.
 *
 * **Usage Example**
 *
 * ```kotlin
 *     // Extract the square shaped face bitmap around the center point between eyes and level it by eyes line.
 *     val faceCenterX = (leftEye.position.x + rightEye.position.x) / 2
 *     val faceCenterY = (leftEye.position.y + rightEye.position.y) / 2
 *     val eyeOffsetX = leftEye.position.x - rightEye.position.x
 *     val eyeOffsetY = leftEye.position.y - rightEye.position.y
 *     val eyeDistance = sqrt(eyeOffsetX * eyeOffsetX + eyeOffsetY * eyeOffsetY)
 *     val faceWidth = eyeDistance * 3 // Heuristic multiplier to fit the face normalized to the eyes pupilar distance.
 *     val eyesAngleRad = atan2(eyeOffsetY, eyeOffsetX)
 *     val eyesAngleDeg = eyesAngleRad * 180.0 / PI // Convert radians to degrees
 *
 *     // Call platform dependent bitmap transformation.
 *     val croppedFaceImageBitmap =  cropRotateScaleImage(
 *         frameData = frameData, // Platform-specific image data.
 *         cx = faceCenterX.toDouble(), // Point between eyes
 *         cy = faceCenterY.toDouble(), // Point between eyes
 *         angleDegrees = eyesAngleDeg, // Eyes line rotation.
 *         outputWidth = faceWidth.toInt(), // Expected face width for cropping *before* final scaling.
 *         outputHeight = faceWidth.toInt(),// Expected face height for cropping *before* final scaling.
 *         targetWidth = 256 // Final square image size (for database saving and face matching tasks).
 *     )
 * ```
 *
 * @param cx The x-coordinate of the transformations center point in the original image (new image center).
 * @param cy The y-coordinate of the transformations center point in the original image (new image center).
 * @param angleDegrees The angle of rotation in degrees around the center point.
 * @param outputWidth The desired width of the output image.
 * @param outputHeight The desired height of the output image.
 * @param targetWidth The desired width of the final image after scaling.
 *
 * @return An ImageBitmap representing the rotated, cropped, and scaled image.
 */
expect fun ImageBitmap.cropRotateScaleImage(
    cx: Double,
    cy: Double,
    angleDegrees: Double,
    outputWidthPx: Int,
    outputHeightPx: Int,
    targetWidthPx: Int
): ImageBitmap

/**
 * Like [androidx.compose.runtime.rememberCoroutineScope] but also makes a platform-specific UI context available in
 * the coroutine context.
 *
 * On Android this makes [org.multipaz.context.UiContext] available in the coroutine-context and
 * callers can use [org.multipaz.context.UiContext.Companion.current] to get a [android.content.Context]
 * object for which [android.content.Context.getActivity] will return the Activity the coroutine is bound to.
 *
 * On iOS this is equivalent to [androidx.compose.runtime.rememberCoroutineScope].
 *
 * @param getContext optional context.
 * @return the [CoroutineScope].
 */
@Composable
expect fun rememberUiBoundCoroutineScope(
    getContext: @DisallowComposableCalls () -> CoroutineContext = {
        EmptyCoroutineContext
    }
): CoroutineScope
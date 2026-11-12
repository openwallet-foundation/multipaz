package org.multipaz.presentment

/**
 * Base class for a purpose hint in a [PresentmentUseCase].
 */
sealed class PresentmentPurposeHint

/**
 * ISO/IEC 18013-5 purpose hint for a [PresentmentUseCase].
 *
 * @property controllerId the `PurposeControllerId` identifying the authority defining the purpose hint codes.
 * @property purposeHintCode the `PurposeHintCode` defined by the authority identified by [controllerId].
 */
data class PresentmentPurposeHintIso18013(
    val controllerId: String,
    val purposeHintCode: Int
) : PresentmentPurposeHint()

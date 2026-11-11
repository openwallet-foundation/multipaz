package org.multipaz.nfc

/**
 * iOS actual for [NfcApduRouterBase].
 */
actual abstract class NfcApduRouterBase actual constructor() {
    actual open fun start() {}
    actual open fun stop() {}
    actual abstract fun processCommandApdu(commandApdu: CommandApdu): ResponseApdu?
    actual abstract fun deactivate(reason: NfcDeactivationReason)
    actual open fun sendResponseApdu(responseApdu: ResponseApdu) {
        NfcApduRouter.forwardResponseApduIfHandled(responseApdu)
    }
}

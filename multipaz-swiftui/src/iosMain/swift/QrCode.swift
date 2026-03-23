import SwiftUI

/// Generates a QR code.
///
/// The generated image includes a Quiet Zone of four bars, as required by the standard.
///
/// - Parameters:
///   - uri: the URI to generate a QR code for.
/// - Returns: a ``UIImage`` with the QR code.
public func generateQrCode(uri: String) -> UIImage {
    let data = uri.data(using: String.Encoding.ascii)
    let filter = CIFilter(name: "CIQRCodeGenerator")!
    filter.setValue(data, forKey: "inputMessage")
    let scalingFactor = 4.0
    let transform = CGAffineTransform(scaleX: scalingFactor, y: scalingFactor)
    let output = filter.outputImage?.transformed(by: transform)
    // iOS QR Code generator doesn't add the proper Quiet Zone so we need
    // to do this ourselves. Add four modules as required by the standard.
    //
    let quietZonePadding = 4*scalingFactor
    let context = CIContext()
    let cgImage = context.createCGImage(
        output!,
        from: CGRect(
            x: -quietZonePadding,
            y: -quietZonePadding,
            width: output!.extent.width + 2*quietZonePadding,
            height: output!.extent.height + 2*quietZonePadding
        )
    )
    return UIImage(cgImage: cgImage!)
}

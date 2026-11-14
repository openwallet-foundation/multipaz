import AVFoundation
import CoreImage
import SwiftUI
import UIKit

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

/// A SwiftUI view that scans QR codes using the back camera.
///
/// Calls ``onCodeScanned`` with each recognized QR code payload string on the main thread.
/// Scanning continues until ``onCodeScanned`` returns `true`, at which point capture stops.
@MainActor
public struct ScanQrCodeView: UIViewControllerRepresentable {
    public let onCodeScanned: (String) -> Bool

    public init(onCodeScanned: @escaping (String) -> Bool) {
        self.onCodeScanned = onCodeScanned
    }

    public func makeUIViewController(context: Context) -> QrCodeScannerViewController {
        let vc = QrCodeScannerViewController()
        vc.onCodeScanned = onCodeScanned
        return vc
    }

    public func updateUIViewController(_ uiViewController: QrCodeScannerViewController, context: Context) {
        uiViewController.onCodeScanned = onCodeScanned
    }
}

public final class QrCodeScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onCodeScanned: ((String) -> Bool)?

    private let captureSession = AVCaptureSession()
    private let metadataOutput = AVCaptureMetadataOutput()
    private let sessionQueue = DispatchQueue(label: "org.multipaz.qrcode_scanner_session_queue", qos: .userInitiated)
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var statusLabel: UILabel?
    private var hasAcceptedCode = false

    public override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        checkPermissionAndSetupCamera()
    }

    public override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
        statusLabel?.frame = view.bounds.insetBy(dx: 24, dy: 24)
    }

    private func showStatusMessage(_ text: String) {
        let label: UILabel
        if let existing = statusLabel {
            label = existing
        } else {
            let created = UILabel()
            created.textColor = .white
            created.textAlignment = .center
            created.numberOfLines = 0
            created.font = .preferredFont(forTextStyle: .body)
            view.addSubview(created)
            statusLabel = created
            label = created
        }
        label.text = text
        label.frame = view.bounds.insetBy(dx: 24, dy: 24)
    }

    private func checkPermissionAndSetupCamera() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            setupCamera()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    guard let self else { return }
                    if granted {
                        self.setupCamera()
                    } else {
                        self.showStatusMessage("Camera permission is required to scan QR codes.")
                    }
                }
            }
        default:
            showStatusMessage("Camera permission is required to scan QR codes.")
        }
    }

    private func setupCamera() {
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: device) else {
            showStatusMessage("Camera is unavailable on this device.")
            return
        }

        if captureSession.canAddInput(input) {
            captureSession.addInput(input)
        }

        if captureSession.canAddOutput(metadataOutput) {
            captureSession.addOutput(metadataOutput)
            metadataOutput.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
            if metadataOutput.availableMetadataObjectTypes.contains(.qr) {
                metadataOutput.metadataObjectTypes = [.qr]
            }
        }

        let preview = AVCaptureVideoPreviewLayer(session: captureSession)
        preview.videoGravity = .resizeAspectFill
        preview.frame = view.bounds
        view.layer.addSublayer(preview)
        self.previewLayer = preview

        let session = self.captureSession
        sessionQueue.async {
            if !session.isRunning {
                session.startRunning()
            }
        }
    }

    public override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        metadataOutput.setMetadataObjectsDelegate(nil, queue: nil)
        let session = self.captureSession
        sessionQueue.async {
            if session.isRunning {
                session.stopRunning()
            }
        }
    }

    deinit {
        metadataOutput.setMetadataObjectsDelegate(nil, queue: nil)
        let session = self.captureSession
        sessionQueue.async {
            if session.isRunning {
                session.stopRunning()
            }
        }
    }

    public func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard !hasAcceptedCode else { return }
        for object in metadataObjects {
            guard let readable = object as? AVMetadataMachineReadableCodeObject,
                  readable.type == .qr,
                  let value = readable.stringValue else {
                continue
            }
            if onCodeScanned?(value) == true {
                hasAcceptedCode = true
                metadataOutput.setMetadataObjectsDelegate(nil, queue: nil)
                let session = self.captureSession
                sessionQueue.async {
                    if session.isRunning {
                        session.stopRunning()
                    }
                }
                break
            }
        }
    }
}

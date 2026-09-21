import SwiftUI
import PhotosUI
import UIKit
import Multipaz

let FACE_MATCHER_STORAGE_TABLE_SPEC = StorageTableSpec(
    name: "FaceMatcherPromptData",
    supportPartitions: false,
    supportExpiration: false,
    schemaVersion: 0
)

let FACE_MATCHER_KEY_REFERENCE_PORTRAIT = "referencePortrait"

/// Retrieves the stored reference portrait from persistent storage if available.
func getFaceMatcherReferencePortrait(storage: Storage) async -> ByteString? {
    do {
        let table = try await storage.getTable(spec: FACE_MATCHER_STORAGE_TABLE_SPEC)
        return try await table.get(key: FACE_MATCHER_KEY_REFERENCE_PORTRAIT, partitionId: nil)
    } catch {
        return nil
    }
}

/// Saves the reference portrait to persistent storage.
private func saveFaceMatcherReferencePortrait(storage: Storage, portrait: ByteString) async {
    do {
        let table = try await storage.getTable(spec: FACE_MATCHER_STORAGE_TABLE_SPEC)
        let existing = try await table.get(key: FACE_MATCHER_KEY_REFERENCE_PORTRAIT, partitionId: nil)
        if existing == nil {
            _ = try await table.insert(
                key: FACE_MATCHER_KEY_REFERENCE_PORTRAIT,
                data: portrait,
                partitionId: nil,
                expiration: KotlinInstant.companion.DISTANT_FUTURE
            )
        } else {
            try await table.update(
                key: FACE_MATCHER_KEY_REFERENCE_PORTRAIT,
                data: portrait,
                partitionId: nil,
                expiration: nil
            )
        }
    } catch {
        print("Failed to save reference portrait to storage: \(error)")
    }
}

/// Clears the stored reference portrait from persistent storage.
private func clearFaceMatcherReferencePortrait(storage: Storage) async {
    do {
        let table = try await storage.getTable(spec: FACE_MATCHER_STORAGE_TABLE_SPEC)
        _ = try await table.delete(key: FACE_MATCHER_KEY_REFERENCE_PORTRAIT, partitionId: nil)
    } catch {
        print("Failed to delete reference portrait: \(error)")
    }
}

struct FaceMatcherPromptScreen: View {
    @Environment(ViewModel.self) private var viewModel

    @State private var referencePortrait: ByteString? = nil
    @State private var isLoading: Bool = true
    @State private var showCameraSheet: Bool = false
    @State private var showCameraUnavailableAlert: Bool = false
    @State private var showSamplePortraitPicker: Bool = false
    @State private var selectedPhotoItem: PhotosPickerItem? = nil
    @State private var isVerifying: Bool = false
    @State private var toastMessage: String? = nil
    @State private var selectedMatcherDisplayName: String = ""
    @State private var detectedFaceCrop: ByteString? = nil
    @State private var isExtractingCrop: Bool = false
    @State private var cropError: String? = nil
    @State private var isCheckingLiveness: Bool = false
    @State private var capturedPortraitForDialog: ByteString? = nil
    @State private var showCapturedPortraitSheet: Bool = false

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                infoCard
                referencePortraitCard
                faceVerificationCard
            }
            .padding()
        }
        .navigationTitle("Face Matcher Prompt")
        .overlay(alignment: .bottom) {
            toastOverlay
        }
        .task {
            if selectedMatcherDisplayName.isEmpty, let defaultDisplayName = viewModel.faceMatcherRepository?.defaultMatcher?.displayName {
                selectedMatcherDisplayName = defaultDisplayName
            }
            await loadStoredPortrait()
        }
        .task(id: referencePortrait) {
            await extractFaceCrop(for: referencePortrait)
        }
        .onChange(of: selectedPhotoItem) { _, newItem in
            handlePhotoPickerResult(newItem)
        }
        .fullScreenCover(isPresented: $showCameraSheet) {
            cameraSheetContent
        }
        .sheet(isPresented: $showSamplePortraitPicker) {
            samplePortraitPickerSheet
        }
        .sheet(isPresented: $showCapturedPortraitSheet) {
            if let captured = capturedPortraitForDialog {
                capturedPortraitSheet(captured: captured)
            }
        }
        .alert("Camera Unavailable", isPresented: $showCameraUnavailableAlert) {
            Button("Pick from Sample portraits") {
                showSamplePortraitPicker = true
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Camera is not available on this device/simulator. Would you like to pick one of the sample portraits instead?")
        }
    }

    @ViewBuilder
    private var infoCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Face Matcher Prompt")
                .font(.headline)
                .fontWeight(.bold)
            Text("Store a reference portrait (persisted across restarts) to match against. When a portrait is present, you can invoke the Face Matcher Prompt to verify the user's identity via the front camera.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    @ViewBuilder
    private var referencePortraitCard: some View {
        VStack(spacing: 16) {
            HStack {
                Text("Reference Portrait")
                    .font(.headline)
                    .fontWeight(.semibold)
                Spacer()
            }

            if isLoading {
                ProgressView()
                    .padding(24)
            } else if let portrait = referencePortrait {
                portraitDisplaySection(portrait: portrait)
            } else {
                noPortraitPlaceholderSection
            }
        }
        .padding()
        .frame(maxWidth: .infinity)
        .background(Color(.systemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(Color(.separator), lineWidth: 1)
        )
    }

    @ViewBuilder
    private func portraitDisplaySection(portrait: ByteString) -> some View {
        let uiImage = UIImage(data: portrait.toNSData())

        if faceNetMatcher != nil {
            HStack(alignment: .top, spacing: 16) {
                // Left: Reference Portrait
                VStack(spacing: 4) {
                    if let uiImage {
                        Image(uiImage: uiImage)
                            .resizable()
                            .scaledToFill()
                            .frame(width: 130, height: 130)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                            .overlay(
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(Color.accentColor, lineWidth: 2)
                            )
                    } else {
                        RoundedRectangle(cornerRadius: 12)
                            .fill(Color(.secondarySystemBackground))
                            .frame(width: 130, height: 130)
                            .overlay(Text("Invalid Image").font(.caption))
                    }
                    Text("Reference Portrait")
                        .font(.caption)
                        .fontWeight(.semibold)
                    let resInfo: String = {
                        if let uiImage, let cgImage = uiImage.cgImage {
                            return "\(cgImage.width)×\(cgImage.height) • "
                        }
                        return ""
                    }()
                    Text("\(resInfo)\(portrait.toNSData().count / 1024) KB")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                // Right: FaceNet face detected
                VStack(spacing: 4) {
                    if isExtractingCrop {
                        RoundedRectangle(cornerRadius: 12)
                            .fill(Color(.secondarySystemBackground))
                            .frame(width: 130, height: 130)
                            .overlay(ProgressView())
                    } else if let detectedFaceCrop, let cropImage = UIImage(data: detectedFaceCrop.toNSData()) {
                        Image(uiImage: cropImage)
                            .resizable()
                            .scaledToFill()
                            .frame(width: 130, height: 130)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                            .overlay(
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(Color.secondary, lineWidth: 2)
                            )
                    } else {
                        RoundedRectangle(cornerRadius: 12)
                            .fill(Color(.secondarySystemBackground))
                            .frame(width: 130, height: 130)
                            .overlay(
                                Text(cropError ?? "No face detected")
                                    .font(.caption2)
                                    .foregroundStyle(.red)
                                    .multilineTextAlignment(.center)
                                    .padding(8)
                            )
                    }
                    Text("FaceNet face detected")
                        .font(.caption)
                        .fontWeight(.semibold)
                        .foregroundStyle(Color.accentColor)
                    Text(detectedFaceCrop != nil ? "\(detectedFaceCrop!.toNSData().count) bytes (112×112)" : "BlazeFace alignment")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
        } else {
            if let uiImage {
                Image(uiImage: uiImage)
                    .resizable()
                    .scaledToFill()
                    .frame(width: 160, height: 160)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(Color.accentColor, lineWidth: 2)
                    )
            } else {
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color(.secondarySystemBackground))
                    .frame(width: 160, height: 160)
                    .overlay(Text("Invalid Image").font(.caption))
            }

            let resInfo: String = {
                if let uiImage, let cgImage = uiImage.cgImage {
                    return "\(cgImage.width)×\(cgImage.height) • "
                }
                return ""
            }()
            Text("Size: \(resInfo)\(portrait.toNSData().count / 1024) KB")
                .font(.caption)
                .foregroundStyle(.secondary)
        }

        HStack(spacing: 8) {
            Button("Retake") {
                openCamera()
            }
            .buttonStyle(.bordered)

            PhotosPicker(selection: $selectedPhotoItem, matching: .images) {
                Text("Pick Image")
            }
            .buttonStyle(.bordered)

            Button("Sample") {
                showSamplePortraitPicker = true
            }
            .buttonStyle(.bordered)

            Button("Rotate 90°") {
                rotatePortrait()
            }
            .buttonStyle(.bordered)
        }
        .lineLimit(1)

        Button(role: .destructive) {
            clearPortrait()
        } label: {
            Text("Clear Portrait")
        }
        .buttonStyle(.borderedProminent)
        .tint(.red)
    }

    @ViewBuilder
    private var noPortraitPlaceholderSection: some View {
        VStack(spacing: 8) {
            Image(systemName: "person.crop.rectangle")
                .font(.system(size: 48))
                .foregroundStyle(.secondary)
            Text("No portrait stored")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .frame(height: 140)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))

        Text("Capture a photo with the camera, select an image from your gallery, or load a sample portrait.")
            .font(.caption)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)

        VStack(spacing: 10) {
            Button {
                openCamera()
            } label: {
                HStack {
                    Image(systemName: "camera")
                    Text("Capture with Camera")
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)

            PhotosPicker(selection: $selectedPhotoItem, matching: .images) {
                HStack {
                    Image(systemName: "photo")
                    Text("Pick from Gallery")
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)

            Button {
                showSamplePortraitPicker = true
            } label: {
                Text("Pick from Sample portraits")
            }
            .buttonStyle(.borderless)
        }
        .padding(.horizontal, 16)
    }

    @ViewBuilder
    private var faceVerificationCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Face Verification")
                .font(.headline)
                .fontWeight(.semibold)

            Text("Invokes the Face Matcher Prompt dialog to match the user's face in the camera stream against the stored reference portrait.")
                .font(.caption)
                .foregroundStyle(.secondary)

            let matchers = viewModel.faceMatcherRepository?.all ?? []
            if matchers.count > 1 {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Face matcher implementation")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    ComboBox(
                        options: matchers.map { $0.displayName },
                        selection: $selectedMatcherDisplayName,
                        placeholder: "Select matcher..."
                    )
                }
            }

            Button {
                verifyFace()
            } label: {
                HStack {
                    if isVerifying {
                        ProgressView()
                            .progressViewStyle(CircularProgressViewStyle(tint: .white))
                            .padding(.trailing, 8)
                    }
                    Text(isVerifying ? "Verifying..." : "Verify Face")
                        .fontWeight(.semibold)
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(referencePortrait == nil || isVerifying)

            Button {
                checkLivenessAndCapture()
            } label: {
                HStack {
                    if isCheckingLiveness {
                        ProgressView()
                            .progressViewStyle(CircularProgressViewStyle(tint: .accentColor))
                            .padding(.trailing, 8)
                    }
                    Text(isCheckingLiveness ? "Checking Liveness..." : "Check liveness and capture portrait image")
                        .fontWeight(.semibold)
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .disabled(isVerifying || isCheckingLiveness)
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.systemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(Color(.separator), lineWidth: 1)
        )
    }

    @ViewBuilder
    private var toastOverlay: some View {
        if let toastMessage {
            Text(toastMessage)
                .font(.subheadline)
                .foregroundColor(.white)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(Color.black.opacity(0.8))
                .clipShape(Capsule())
                .padding(.bottom, 24)
                .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }

    private var cameraSheetContent: some View {
        CameraCaptureView(
            onPhotoCaptured: { capturedImage in
                showCameraSheet = false
                if let pngData = capturedImage.pngData() {
                    let portraitBytes = pngData.toByteString()
                    Task {
                        await saveFaceMatcherReferencePortrait(storage: viewModel.storage, portrait: portraitBytes)
                        referencePortrait = portraitBytes
                        showToast("Reference portrait captured from camera")
                    }
                }
            },
            onCancel: {
                showCameraSheet = false
            }
        )
        .ignoresSafeArea()
    }

    private func handlePhotoPickerResult(_ newItem: PhotosPickerItem?) {
        guard let newItem else { return }
        Task {
            if let data = try? await newItem.loadTransferable(type: Data.self) {
                let portraitBytes = data.toByteString()
                await saveFaceMatcherReferencePortrait(storage: viewModel.storage, portrait: portraitBytes)
                referencePortrait = portraitBytes
                showToast("Reference portrait selected from gallery")
            }
            selectedPhotoItem = nil
        }
    }

    private func loadStoredPortrait() async {
        isLoading = true
        defer { isLoading = false }
        referencePortrait = await getFaceMatcherReferencePortrait(storage: viewModel.storage)
    }

    private func openCamera() {
        if UIImagePickerController.isSourceTypeAvailable(.camera) {
            showCameraSheet = true
        } else {
            showCameraUnavailableAlert = true
        }
    }

    @ViewBuilder
    private var samplePortraitPickerSheet: some View {
        NavigationStack {
            List(FaceTestData.shared.allPortraits, id: \.id) { sample in
                Button {
                    selectSamplePortrait(sample)
                } label: {
                    HStack(spacing: 12) {
                        if let uiImage = UIImage(data: sample.data.toNSData()) {
                            Image(uiImage: uiImage)
                                .resizable()
                                .scaledToFill()
                                .frame(width: 50, height: 50)
                                .clipShape(RoundedRectangle(cornerRadius: 8))
                        }
                        VStack(alignment: .leading, spacing: 2) {
                            Text(sample.displayName)
                                .font(.body)
                                .fontWeight(.medium)
                                .foregroundColor(.primary)
                            Text(sample.subtitle)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                        Spacer()
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            .navigationTitle("Pick Sample Portrait")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        showSamplePortraitPicker = false
                    }
                }
            }
        }
    }

    private func selectSamplePortrait(_ sample: FaceSamplePortrait) {
        Task {
            await saveFaceMatcherReferencePortrait(storage: viewModel.storage, portrait: sample.data)
            referencePortrait = sample.data
            showSamplePortraitPicker = false
            showToast("Loaded \(sample.displayName)")
        }
    }

    private func rotatePortrait() {
        guard let portrait = referencePortrait,
              let uiImage = UIImage(data: portrait.toNSData()) else { return }

        if let rotatedImage = rotateImage90Clockwise(image: uiImage),
           let pngData = rotatedImage.pngData() {
            let newBytes = pngData.toByteString()
            Task {
                await saveFaceMatcherReferencePortrait(storage: viewModel.storage, portrait: newBytes)
                referencePortrait = newBytes
                showToast("Portrait rotated 90° clockwise")
            }
        }
    }

    private func clearPortrait() {
        Task {
            await clearFaceMatcherReferencePortrait(storage: viewModel.storage)
            referencePortrait = nil
            showToast("Reference portrait cleared")
        }
    }

    private var faceNetMatcher: FaceNetFaceMatcher? {
        viewModel.faceMatcherRepository?.all.first(where: { $0 is FaceNetFaceMatcher }) as? FaceNetFaceMatcher
    }

    private func extractFaceCrop(for portrait: ByteString?) async {
        guard let portrait, let matcher = faceNetMatcher else {
            detectedFaceCrop = nil
            cropError = nil
            isExtractingCrop = false
            return
        }
        isExtractingCrop = true
        cropError = nil
        do {
            let crop = try await matcher.extractFaceCrop(portrait: portrait)
            detectedFaceCrop = crop
        } catch {
            detectedFaceCrop = nil
            cropError = error.localizedDescription
        }
        isExtractingCrop = false
    }

    private func verifyFace() {
        guard let portrait = referencePortrait else { return }
        isVerifying = true
        let matcher = viewModel.faceMatcherRepository?.all.first(where: { $0.displayName == selectedMatcherDisplayName })
            ?? viewModel.faceMatcherRepository?.defaultMatcher
        Task {
            defer { isVerifying = false }
            do {
                let matched = try await viewModel.promptModel.showFaceMatcherPrompt(
                    referencePortrait: portrait,
                    reason: ReasonHumanReadable(
                        title: "Verify Identity",
                        subtitle: "Please look at the camera to match your face",
                        requireConfirmation: false
                    ),
                    matcher: matcher,
                    document: nil
                )
                if matched.boolValue {
                    showToast("Face matched successfully!")
                } else {
                    showToast("Face verification failed or canceled")
                }
            } catch {
                showToast("Face verification dismissed or failed")
            }
        }
    }

    private func checkLivenessAndCapture() {
        isCheckingLiveness = true
        let matcher = viewModel.faceMatcherRepository?.all.first(where: { $0.displayName == selectedMatcherDisplayName })
            ?? viewModel.faceMatcherRepository?.defaultMatcher
        Task {
            defer { isCheckingLiveness = false }
            do {
                let capturedImage = try await viewModel.promptModel.showFaceLivenessPrompt(
                    reason: ReasonHumanReadable(
                        title: "Check Liveness",
                        subtitle: "Follow the prompts and hold still to capture your portrait",
                        requireConfirmation: false
                    ),
                    matcher: matcher,
                    document: nil
                )
                if let capturedImage {
                    capturedPortraitForDialog = capturedImage
                    showCapturedPortraitSheet = true
                    showToast("Liveness confirmed! Portrait captured.")
                } else {
                    showToast("Liveness check failed or canceled")
                }
            } catch {
                showToast("Liveness check dismissed or failed")
            }
        }
    }

    @ViewBuilder
    private func capturedPortraitSheet(captured: ByteString) -> some View {
        let uiImage = UIImage(data: captured.toNSData())
        let resolutionText: String = {
            if let uiImage, let cgImage = uiImage.cgImage {
                return "\(cgImage.width) × \(cgImage.height)"
            } else if let uiImage {
                return "\(Int(uiImage.size.width * uiImage.scale)) × \(Int(uiImage.size.height * uiImage.scale))"
            }
            return "Unknown"
        }()
        let sizeKb = captured.toNSData().count / 1024

        NavigationStack {
            VStack(spacing: 16) {
                if let uiImage {
                    Image(uiImage: uiImage)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 220, height: 220)
                        .clipShape(RoundedRectangle(cornerRadius: 16))
                        .overlay(
                            RoundedRectangle(cornerRadius: 16)
                                .stroke(Color.accentColor, lineWidth: 2)
                        )
                } else {
                    RoundedRectangle(cornerRadius: 16)
                        .fill(Color(.secondarySystemBackground))
                        .frame(width: 220, height: 220)
                        .overlay(Text("Invalid Image").font(.caption))
                }

                VStack(spacing: 4) {
                    Text("Resolution: \(resolutionText) (\(sizeKb) KB)")
                        .font(.headline)
                        .fontWeight(.semibold)
                        .foregroundStyle(Color.accentColor)

                    Text("Liveness check passed! High-resolution photo captured.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .padding(.horizontal)

                Button {
                    Task {
                        await saveFaceMatcherReferencePortrait(storage: viewModel.storage, portrait: captured)
                        referencePortrait = captured
                        showCapturedPortraitSheet = false
                        showToast("Reference portrait updated from captured photo")
                    }
                } label: {
                    Text("Use as Reference Portrait")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .padding(.horizontal)

                Spacer()
            }
            .padding(.top, 24)
            .navigationTitle("Captured Portrait")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") {
                        showCapturedPortraitSheet = false
                    }
                }
            }
        }
    }

    private func showToast(_ message: String) {
        withAnimation {
            toastMessage = message
        }
        Task {
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            withAnimation {
                if toastMessage == message {
                    toastMessage = nil
                }
            }
        }
    }
}

private func rotateImage90Clockwise(image: UIImage) -> UIImage? {
    let radians = CGFloat.pi / 2
    var newSize = CGRect(origin: .zero, size: image.size)
        .applying(CGAffineTransform(rotationAngle: radians)).integral.size
    newSize.width = floor(newSize.width)
    newSize.height = floor(newSize.height)

    UIGraphicsBeginImageContextWithOptions(newSize, false, image.scale)
    guard let context = UIGraphicsGetCurrentContext() else { return nil }
    context.translateBy(x: newSize.width / 2, y: newSize.height / 2)
    context.rotate(by: radians)
    image.draw(in: CGRect(x: -image.size.width / 2, y: -image.size.height / 2, width: image.size.width, height: image.size.height))
    let rotated = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()
    return rotated
}

private struct CameraCaptureView: UIViewControllerRepresentable {
    let onPhotoCaptured: (UIImage) -> Void
    let onCancel: () -> Void

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.delegate = context.coordinator
        if UIImagePickerController.isSourceTypeAvailable(.camera) {
            picker.sourceType = .camera
            if UIImagePickerController.isCameraDeviceAvailable(.front) {
                picker.cameraDevice = .front
            }
        }
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onPhotoCaptured: onPhotoCaptured, onCancel: onCancel)
    }

    class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onPhotoCaptured: (UIImage) -> Void
        let onCancel: () -> Void

        init(onPhotoCaptured: @escaping (UIImage) -> Void, onCancel: @escaping () -> Void) {
            self.onPhotoCaptured = onPhotoCaptured
            self.onCancel = onCancel
        }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey : Any]) {
            if let image = info[.originalImage] as? UIImage {
                onPhotoCaptured(image)
            } else {
                onCancel()
            }
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            onCancel()
        }
    }
}

import SwiftUI
import Multipaz

/// A flexible SwiftUI view for inputting PIN or passphrases with built-in validation.
///
/// `PassphraseInputView` adapts its UI based on the provided `PassphraseConstraints`.
/// It supports "Peek" mode (briefly showing the last typed character), secure entry,
/// and async validation.
///
/// # Usage Example
/// ```swift
/// PassphraseInputView(
///     title: "Enter PIN",
///     subtitle: "Please enter your 4-digit code",
///     constraints: PassphraseConstraints(minLength: 4, maxLength: 4, requireNumerical: true),
///     passphraseEvaluator: { code in
///         // Simulate async network check
///         try? await Task.sleep(nanoseconds: 1_000_000_000)
///         return code == "1234" ? nil : "Invalid Code"
///     },
///     onSuccess: { code in
///         print("Success: \(code)")
///     }
/// )
/// ```
public struct PassphraseInputView: View {
    
    /// The primary headline text displayed above the input.
    public let title: String
    
    /// The secondary subtitle text displayed below the title.
    public let subtitle: String
    
    /// The constraints determining length and numeric requirements.
    public let constraints: PassphraseConstraints
    
    /// An async closure that validates the input.
    /// - Parameter enteredPassphrase: The string entered by the user.
    /// - Returns: `nil` if the passphrase is correct, or an `String` describing the error if invalid.
    public let passphraseEvaluator: (_ enteredPassphrase: String) async -> String?
    
    /// A closure called when the evaluator returns `nil` (success).
    /// - Parameter enteredPassphrase: The valid passphrase that was entered.
    public let onSuccess: (_ enteredPassphrase: String) -> Void
    
    @State private var input: String = ""
    @State private var errorMessage: String? = nil
    @State private var isValidating: Bool = false
    @FocusState private var isFocused: Bool
    
    // Peek Mode State
    // We track 'oldInput' to accurately detect character additions vs deletions
    @State private var oldInput: String = ""
    @State private var visibleCharIndex: Int? = nil
    @State private var hideCharTask: Task<Void, Never>? = nil
    
    /// Initializes a new `PassphraseInputView`.
    ///
    /// - Parameters:
    ///   - title: The main header text.
    ///   - subtitle: The sub-header text.
    ///   - constraints: Rules defining length and character type.
    ///   - passphraseEvaluator: An async function to validate the input. Returns `nil` on success, or an error string on failure.
    ///   - onSuccess: Called when validation succeeds.
    public init(
        title: String,
        subtitle: String,
        constraints: PassphraseConstraints,
        passphraseEvaluator: @escaping (String) async -> String?,
        onSuccess: @escaping (String) -> Void
    ) {
        self.title = title
        self.subtitle = subtitle
        self.constraints = constraints
        self.passphraseEvaluator = passphraseEvaluator
        self.onSuccess = onSuccess
    }
    
    public var body: some View {
        VStack(spacing: 30) {
            // Header
            VStack(spacing: 10) {
                Text(title)
                    .font(.title2)
                    .fontWeight(.bold)
                    .multilineTextAlignment(.center)
                Text(subtitle)
                    .font(.subheadline)
                    .multilineTextAlignment(.center)
            }
            
            // Input Area
            Group {
                if isFixedLength {
                    fixedLengthInput
                } else {
                    variableLengthInput
                }
            }
            
            // Feedback / Loading Area
            // Uses fixed height to prevent layout jumps during validation errors
            ZStack {
                if isValidating {
                    ProgressView()
                } else if let error = errorMessage {
                    Text(error)
                        .foregroundColor(.red)
                        .font(.caption)
                        .fontWeight(.medium)
                        .transition(.opacity)
                }
            }
            .frame(height: 20)
        }
        .padding()
        .onChange(of: input) { newValue in
            processInput(newValue)
        }
        .onAppear {
            // Delay focus slightly to allow view transition to complete,
            // preventing the "keyboard lag" issue on cold starts.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                isFocused = true
            }
        }
    }
    
    /// Determines if the view should render as fixed boxes (PIN) or a text field.
    private var isFixedLength: Bool {
        return constraints.minLength == constraints.maxLength
    }
    
    /// Renders the visual "Boxes" for fixed-length PIN codes.
    private var fixedLengthInput: some View {
        ZStack {
            // LAYER 1: Visual Boxes
            HStack(spacing: 12) {
                ForEach(0..<constraints.maxLength, id: \.self) { index in
                    ZStack {
                        // Background
                        RoundedRectangle(cornerRadius: 12)
                            .fill(Color(UIColor.systemGray6))
                        
                        // Border
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(borderColor(for: Int(index)), lineWidth: 1)
                        
                        // Content
                        if index < input.count {
                            // Check for "Peek" logic
                            // Using `?? -1` to safely unwrap the optional index
                            let isPeeking = index == (visibleCharIndex ?? -1)
                            
                            ZStack {
                                // 1. The Masked Dot
                                Circle()
                                    .fill(Color.primary)
                                    .frame(width: 8, height: 8)
                                    .opacity(isPeeking ? 0 : 1)
                                
                                // 2. The Visible Character (Peek)
                                Text(charAtIndex(Int(index)))
                                    .font(.title)
                                    .fontWeight(.bold)
                                    .foregroundColor(.primary)
                                    .opacity(isPeeking ? 1 : 0)
                            }
                        }
                    }
                    .frame(width: 45, height: 55)
                }
            }
            
            // LAYER 2: Invisible Touch Target
            // This sits on top of the visual layer to capture touches and keyboard input.
            TextField("", text: $input)
                .focused($isFocused)
                .keyboardType(constraints.requireNumerical ? .numberPad : .default)
                .autocorrectionDisabled(true)
                .textInputAutocapitalization(.never)
                .foregroundColor(.clear)
                .accentColor(.clear)
                .tint(.clear)
                .frame(height: 55)
                .frame(maxWidth: .infinity)
                .contentShape(Rectangle())
        }
    }
    
    /// Renders a standard secure text field for variable-length passphrases.
    private var variableLengthInput: some View {
        HStack {
            SecureField("Enter passphrase", text: $input)
                .focused($isFocused)
                .keyboardType(constraints.requireNumerical ? .numberPad : .default)
                .submitLabel(.go)
                .onSubmit {
                    Task { await validatePassphrase() }
                }
            
            // Manual "Go" button for Number Pad (which lacks a return key)
            if constraints.requireNumerical {
                Button(action: {
                    Task { await validatePassphrase() }
                }) {
                    Image(systemName: "arrow.right.circle.fill")
                        .font(.system(size: 30))
                        .foregroundColor(.blue)
                }
                .disabled(input.count < constraints.minLength || isValidating)
                .opacity((input.count < constraints.minLength || isValidating) ? 0.5 : 1.0)
            }
        }
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 10)
                .stroke(borderColor(for: 0), lineWidth: 1)
        )
    }
    
    private func charAtIndex(_ index: Int) -> String {
        guard index < input.count else { return "" }
        let charIndex = input.index(input.startIndex, offsetBy: index)
        return String(input[charIndex])
    }
    
    private func borderColor(for index: Int) -> Color {
        if errorMessage != nil { return .red }
        
        if isFixedLength {
            if isFocused && index == input.count { return .blue }
        } else {
            if isFocused { return .blue }
        }
        
        return .gray.opacity(0.3)
    }
    
    private func processInput(_ newValue: String) {
        guard !isValidating else { return }
        
        // 1. Sanitize Input
        var sanitized = newValue
        if constraints.requireNumerical {
            sanitized = sanitized.filter { $0.isNumber }
        }
        if sanitized.count > constraints.maxLength {
            sanitized = String(sanitized.prefix(Int(constraints.maxLength)))
        }
        
        // 2. Prevent Feedback Loop
        // If sanitization changed the input, update state and exit to wait for next cycle
        if sanitized != newValue {
            input = sanitized
            return
        }
        
        // 3. Clear Error on Typing
        if !sanitized.isEmpty && errorMessage != nil {
            errorMessage = nil
        }
        
        // 4. Handle "Peek" Logic
        if sanitized.count > oldInput.count {
            // Character Added: Show Peek
            let newIndex = sanitized.count - 1
            visibleCharIndex = newIndex
            
            hideCharTask?.cancel()
            hideCharTask = Task {
                try? await Task.sleep(nanoseconds: 1_000_000_000) // 1 second
                if !Task.isCancelled {
                    withAnimation { visibleCharIndex = nil }
                }
            }
        } else if sanitized.count < oldInput.count {
            // Character Deleted: Hide Peek immediately
            visibleCharIndex = nil
            hideCharTask?.cancel()
        }
        
        oldInput = sanitized
        
        // 5. Auto-Submit (Fixed Length Only)
        if isFixedLength && sanitized.count == constraints.maxLength {
            Task {
                await validatePassphrase()
            }
        }
    }
    
    private func validatePassphrase() async {
        guard !input.isEmpty else { return }
        
        if input.count < constraints.minLength {
            errorMessage = "Input is too short"
            return
        }
        
        visibleCharIndex = nil
        isValidating = true
        
        let currentInput = input
        let result = await passphraseEvaluator(currentInput)
        
        withAnimation {
            isValidating = false
            
            if let error = result {
                // Failure
                errorMessage = error
                input = ""
                oldInput = "" // Reset tracking history
                isFocused = true // Keep keyboard active
                
                // Clear error automatically after 5 seconds
                Task {
                    try? await Task.sleep(nanoseconds: 5 * 1_000_000_000)
                    if errorMessage == error {
                        errorMessage = nil
                    }
                }
            } else {
                // Success
                onSuccess(currentInput)
            }
        }
    }
}

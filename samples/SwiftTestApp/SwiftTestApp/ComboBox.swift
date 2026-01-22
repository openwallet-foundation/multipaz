import SwiftUI
import UIKit

struct ComboBox: View {
    let options: [String]
    @Binding var selection: String
    var placeholder: String = "Select an option"

    var body: some View {
        // Applying fixedSize here ensures the parent view sees
        // the button's intrinsic height instead of "infinity"
        ComboBoxRepresentable(options: options, selection: $selection, placeholder: placeholder)
            .fixedSize(horizontal: false, vertical: true)
    }
}

// Internal Representable to handle the UIKit logic and avoid 2026 reparenting errors
private struct ComboBoxRepresentable: UIViewRepresentable {
    let options: [String]
    @Binding var selection: String
    var placeholder: String

    func makeUIView(context: Context) -> UIButton {
        let button = UIButton(type: .system)
        button.showsMenuAsPrimaryAction = true
        
        // Use Configuration for 2026 layout standards
        var config = UIButton.Configuration.plain()
        config.contentInsets = NSDirectionalEdgeInsets(top: 12, leading: 16, bottom: 12, trailing: 16)
        
        // Add the chevron icon
        config.image = UIImage(systemName: "chevron.up.chevron.down")
        config.imagePlacement = .trailing
        config.imagePadding = 10
        config.preferredSymbolConfigurationForImage = UIImage.SymbolConfiguration(scale: .small)
        
        button.configuration = config
        
        // Styling the border
        button.layer.cornerRadius = 10
        button.layer.borderWidth = 1
        button.layer.borderColor = UIColor.systemGray4.cgColor
        
        // Set alignment and priorities
        button.contentHorizontalAlignment = .fill
        button.setContentCompressionResistancePriority(.required, for: .vertical)
        button.setContentHuggingPriority(.defaultHigh, for: .vertical)

        return button
    }

    func updateUIView(_ uiView: UIButton, context: Context) {
        uiView.setTitle(selection.isEmpty ? placeholder : selection, for: .normal)
        uiView.tintColor = selection.isEmpty ? .secondaryLabel : .label
        
        let actions = options.map { option in
            UIAction(title: option, state: selection == option ? .on : .off) { _ in
                selection = option
            }
        }
        uiView.menu = UIMenu(children: actions)
    }
}

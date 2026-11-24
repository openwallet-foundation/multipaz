import SwiftUI
import Multipaz

/// The SwiftTestApp's renderers for transaction data in the consent prompt.
///
/// A `PaymentTransaction` is drawn by the app: the amount, payee and tip in the prompt, with every
/// field on the details page. Other transaction types keep the built-in rendering.
func makeSwiftTestAppTransactionDataRenderers() -> TransactionDataRendererRepository {
    let renderers = TransactionDataRendererRepository()
    renderers.addRenderer(
        identifier: PaymentTransaction.shared.identifier,
        renderer: TransactionDataRendererSwiftUi(
            summary: { context in AnyView(PaymentSummary(context: context)) },
            details: { context in AnyView(PaymentDetails(context: context)) }
        )
    )
    renderers.addRenderer(
        identifier: TextAcceptanceTransaction.shared.identifier,
        renderer: TransactionDataRendererSwiftUi(
            summary: { context in AnyView(TextAcceptanceSummary(context: context)) },
            details: { context in AnyView(TextAcceptanceDetails(context: context)) }
        )
    )
    return renderers
}

private struct TextAcceptanceSummary: View {
    let context: TransactionDataContext

    var body: some View {
        let payloads = context.transactionData.compactMap {
            $0.payload as? TextAcceptanceTransaction.Payload
        }
        let accepted = (context.userInput as? TextAcceptanceTransaction.UserInput)?.accepted == true
        VStack(alignment: .leading, spacing: 6) {
            Text(context.hasClaims
                 ? "By continuing with this transaction you agree to the following, and your identity will also be shared:"
                 : "By continuing with this transaction you agree to the following:")
                .font(.subheadline.weight(.semibold))
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
            // Show every statement, but only a preview. The details page always has the full text.
            ForEach(payloads.indices, id: \.self) { index in
                let payload = payloads[index]
                Text(payload.text)
                    .font(.subheadline)
                    .lineLimit(3)
                    .multilineTextAlignment(.leading)
                ExpiryRow(expiresAt: payload.expiresAt)
            }
            if !accepted {
                Text("Open the full agreement and accept it before continuing.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, context.hasClaims ? 12 : 0)
        .task(id: accepted) {
            context.onContinueAllowedChanged(KotlinBoolean(value: accepted))
        }
    }
}

private struct TextAcceptanceDetails: View {
    let context: TransactionDataContext

    var body: some View {
        let payloads = context.transactionData.compactMap {
            $0.payload as? TextAcceptanceTransaction.Payload
        }
        let accepted = (context.userInput as? TextAcceptanceTransaction.UserInput)?.accepted == true
        let canAccept = context.isScrolledToBottom || accepted
        VStack(alignment: .leading, spacing: 12) {
            Text(context.hasClaims
                 ? "By continuing with this transaction you agree to the following, and your identity will also be shared:"
                 : "By continuing with this transaction you agree to the following:")
                .font(.subheadline.weight(.semibold))
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
            // Every statement is rendered verbatim as plain text: no markup is interpreted,
            // no truncation, and no mutation after the holder begins confirming.
            ForEach(payloads.indices, id: \.self) { index in
                let payload = payloads[index]
                if index > 0 {
                    Divider()
                }
                Text(payload.text)
                    .font(.subheadline)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
                ExpiryRow(expiresAt: payload.expiresAt)
            }
            Divider()
            Toggle(isOn: Binding(
                get: { accepted },
                set: { checked in
                    context.onUserInputChanged(TextAcceptanceTransaction.UserInput(accepted: checked))
                    context.onContinueAllowedChanged(KotlinBoolean(value: checked))
                }
            )) {
                Text("I have read and agree to the terms above.")
                    .font(.subheadline)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
            }
            .disabled(!canAccept)
            if !context.isScrolledToBottom {
                Text("Scroll to the end of the agreement to accept it.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .task(id: accepted) {
            context.onContinueAllowedChanged(KotlinBoolean(value: accepted))
        }
    }
}

/// The expiry of a statement, shown in the device's locale and time zone instead of the raw ISO 8601
/// instant, with how long is left so the holder does not have to read the clock.
private struct ExpiryRow: View {
    let expiresAt: KotlinInstant

    var body: some View {
        let date = expiresAt.toNSDate()
        HStack(spacing: 8) {
            Image(systemName: "clock")
                .imageScale(.small)
            Text("Expires: \(expiryDateFormatter.string(from: date)) (\(expiryRelativeFormatter.localizedString(for: date, relativeTo: Date())))")
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

private let expiryDateFormatter: DateFormatter = {
    let formatter = DateFormatter()
    formatter.dateStyle = .medium
    formatter.timeStyle = .short
    return formatter
}()

private let expiryRelativeFormatter: RelativeDateTimeFormatter = {
    let formatter = RelativeDateTimeFormatter()
    formatter.unitsStyle = .full
    return formatter
}()

private struct TipOption {
    let percent: Double
    let label: String
}

private let tipOptions = [
    TipOption(percent: 0.0, label: "No tip"),
    TipOption(percent: 10.0, label: "10%"),
    TipOption(percent: 15.0, label: "15%"),
    TipOption(percent: 20.0, label: "20%"),
]

private struct DetailRow {
    let label: String
    let value: String
}

private func formatPaymentAmount(_ amount: Double) -> String {
    String(format: "%.2f", amount)
}

private func selectedTipPercent(_ context: TransactionDataContext) -> Double {
    (context.userInput as? PaymentTransaction.UserInput)?.tipPercent ?? 0.0
}

private func tipAmount(_ payload: PaymentTransaction.Payload, tipPercent: Double) -> Double {
    payload.tipRequested?.boolValue == true ? ceil(payload.amount * tipPercent) / 100.0 : 0.0
}

private struct PaymentSummary: View {
    let context: TransactionDataContext

    var body: some View {
        if let payload = context.transactionData.first?.payload as? PaymentTransaction.Payload {
            let tipPercent = selectedTipPercent(context)
            let total = payload.amount + tipAmount(payload, tipPercent: tipPercent)
            VStack(alignment: .leading, spacing: 4) {
                Text("Pay \(formatPaymentAmount(total)) \(payload.currency)")
                    .font(.title3.bold())
                Text("to \(payload.payee.name)")
                    .foregroundStyle(.secondary)
                if payload.tipRequested?.boolValue == true {
                    Picker("Tip", selection: Binding(
                        get: { tipPercent },
                        set: { percent in
                            context.onUserInputChanged(PaymentTransaction.UserInput(tipPercent: percent))
                        }
                    )) {
                        ForEach(tipOptions, id: \.percent) { option in
                            Text(option.label).tag(option.percent)
                        }
                    }
                    .pickerStyle(.segmented)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, context.hasClaims ? 12 : 0)
        }
    }
}

private struct PaymentDetails: View {
    let context: TransactionDataContext

    var body: some View {
        if let payload = context.transactionData.first?.payload as? PaymentTransaction.Payload {
            VStack(alignment: .leading, spacing: 8) {
                Text("TRANSACTION DETAILS")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                ForEach(detailRows(payload), id: \.label) { row in
                    HStack(alignment: .top) {
                        Text(row.label)
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        Text(row.value)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    Divider()
                }
            }
        }
    }

    private func detailRows(_ payload: PaymentTransaction.Payload) -> [DetailRow] {
        let tip = tipAmount(payload, tipPercent: selectedTipPercent(context))
        var rows = [
            DetailRow(label: "Payee", value: payload.payee.name),
            DetailRow(label: "Payee ID", value: payload.payee.id),
        ]
        if let website = payload.payee.website {
            rows.append(DetailRow(label: "Website", value: website))
        }
        rows.append(DetailRow(label: "Amount", value: "\(formatPaymentAmount(payload.amount)) \(payload.currency)"))
        if payload.tipRequested?.boolValue == true {
            rows.append(DetailRow(label: "Tip", value: "\(formatPaymentAmount(tip)) \(payload.currency)"))
            rows.append(DetailRow(label: "Total", value: "\(formatPaymentAmount(payload.amount + tip)) \(payload.currency)"))
        }
        rows.append(DetailRow(label: "Transaction ID", value: payload.transactionId))
        rows.append(DetailRow(
            label: "Requested by",
            value: context.requester.origin ?? context.requester.appId ?? "Unknown"
        ))
        return rows
    }
}

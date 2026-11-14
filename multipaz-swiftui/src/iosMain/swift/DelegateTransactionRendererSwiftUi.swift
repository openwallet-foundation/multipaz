import SwiftUI

/// The SDK's renderer for AP2 `DelegateTransaction` items, drawn as a spending permission.
///
/// The consent prompt shows the spending limits set large, then where the agent may buy, who is
/// paid, what, until when and for which agent, one row each. The details page lists every mandate
/// in full, as it will be signed, since AP2 requires the Mandate Content to be shown, together with
/// the origin or app that asked. The words come from `DelegateMandateText`.
///
/// `addUtopiaTypes()` does not register `DelegateTransaction`; an application that does registers
/// this renderer with it:
///
/// ```swift
/// documentTypeRepository.addDelegateTransactionType()
/// transactionDataRendererRepository.addRenderer(
///     identifier: DelegateTransaction.shared.identifier,
///     renderer: DelegateTransactionRendererSwiftUi()
/// )
/// ```
///
/// An application that wants its own look registers its own ``TransactionDataRendererSwiftUi``
/// instead, built on `DelegateMandateText`.
public final class DelegateTransactionRendererSwiftUi: TransactionDataRendererSwiftUi {
    public init() {
        super.init(
            summary: { context in AnyView(SpendingPermissionSummary(context: context)) },
            details: { context in AnyView(SpendingPermissionDetails(context: context)) }
        )
    }
}

/// What the mandates came to, once their selectively disclosed terms are filled in off the view's
/// body (that takes a hash per disclosure): still being worked out, readable, or not readable.
/// `DelegateTransaction` refuses a request whose mandates can't all be filled in before the prompt
/// is shown, so `unreadable` should not happen.
private enum Shown<Value> {
    case loading
    case value(Value)
    case unreadable
}

/// Said in place of the permission when its mandates can't be read, rather than drawing nothing:
/// a person must not approve a spending permission they were not shown.
private struct CannotShow: View {
    var body: some View {
        Label {
            Text(verbatim: DelegateMandateText.shared.CANNOT_SHOW)
                .fixedSize(horizontal: false, vertical: true)
        } icon: {
            Image(systemName: "exclamationmark.triangle")
        }
        .font(.subheadline)
        .foregroundStyle(.red)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 8)
        .accessibilityElement(children: .combine)
    }
}

/// The spending permission in the consent prompt.
///
/// The limits lead and are set large, because they are what the person has to read correctly.
/// The conditions follow as short rows. Strings come from the relying party, so they are shown
/// verbatim and bounded here; the details page shows them in full.
private struct SpendingPermissionSummary: View {
    let context: TransactionDataContext
    @State private var summary: Shown<DelegateMandateText.PermissionSummary> = .loading

    var body: some View {
        Group {
            switch summary {
            case .loading:
                EmptyView()
            case .value(let summary):
                content(summary)
            case .unreadable:
                CannotShow()
            }
        }
        .task {
            summary = await SpendingPermissionSummary.load(context)
        }
    }

    private static func load(_ context: TransactionDataContext) async -> Shown<DelegateMandateText.PermissionSummary> {
        do {
            let mandates = try await DelegateTransaction.shared.shownMandates(transactionData: context.transactionData)
            return .value(DelegateMandateText.shared.describePermission(mandates: mandates))
        } catch is CancellationError {
            return .loading
        } catch {
            return .unreadable
        }
    }

    private func content(_ summary: DelegateMandateText.PermissionSummary) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .center, spacing: 12) {
                Image(systemName: "creditcard")
                    .font(.title3)
                    .foregroundStyle(.tint)
                    .frame(width: 40, height: 40)
                    .background(Circle().fill(Color.accentColor.opacity(0.15)))
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: DelegateMandateText.shared.TITLE)
                        .font(.headline)
                    Text(verbatim: DelegateMandateText.shared.SUMMARY_EXPLAINER)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            if !summary.limits.isEmpty {
                VStack(spacing: 0) {
                    ForEach(Array(summary.limits.enumerated()), id: \.offset) { index, limit in
                        if index > 0 { Divider() }
                        HStack(alignment: .firstTextBaseline) {
                            Text(verbatim: limit.label)
                                .font(.subheadline)
                            Spacer(minLength: 12)
                            Text(verbatim: limit.amount)
                                .font(.title2.weight(.bold))
                            Text(verbatim: limit.currency)
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(.secondary)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 14)
                        .accessibilityElement(children: .combine)
                    }
                }
                .background(
                    RoundedRectangle(cornerRadius: 16)
                        .fill(Color(.secondarySystemBackground))
                )
            }

            if !summary.rows.isEmpty {
                VStack(spacing: 0) {
                    ForEach(Array(summary.rows.enumerated()), id: \.offset) { index, line in
                        if index > 0 { Divider() }
                        HStack(alignment: .top, spacing: 12) {
                            Image(systemName: symbolFor(line))
                                .foregroundStyle(.secondary)
                                .frame(width: 20)
                            Text(verbatim: line.label)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .frame(width: 72, alignment: .leading)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(verbatim: line.value)
                                    .font(.subheadline)
                                    .lineLimit(4)
                                if let detail = line.detail {
                                    Text(verbatim: detail)
                                        .font(.footnote)
                                        .foregroundStyle(.secondary)
                                        .lineLimit(1)
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .padding(.vertical, 10)
                        .accessibilityElement(children: .combine)
                    }
                }
            }
        }
        .padding(.top, context.hasClaims ? 12 : 0)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// A row's symbol, from what the row is about; a row of another kind gets one for its shape.
    private func symbolFor(_ line: DelegateMandateText.SummaryLine) -> String {
        if line.kind == DelegateMandateText.Kind.merchant { return "storefront" }
        if line.kind == DelegateMandateText.Kind.payee { return "banknote" }
        if line.kind == DelegateMandateText.Kind.items { return "bag" }
        if line.kind == DelegateMandateText.Kind.expiry { return "calendar" }
        if line.kind == DelegateMandateText.Kind.agent { return "key" }
        if line.shape == DelegateMandateText.Shape.instant { return "calendar" }
        if line.shape == DelegateMandateText.Shape.party { return "key" }
        return "info.circle"
    }
}

/// Everything the spending permission contains, mandate by mandate, as it will be signed.
///
/// The summary deduplicates what the mandates share. This page does not: AP2 requires the signed
/// Mandate Content to be shown, so each mandate is listed in full.
private struct SpendingPermissionDetails: View {
    let context: TransactionDataContext
    @State private var sections: Shown<[DelegateMandateText.MandateSection]> = .loading

    var body: some View {
        Group {
            switch sections {
            case .loading:
                EmptyView()
            case .value(let sections):
                content(sections)
            case .unreadable:
                CannotShow()
            }
        }
        .task {
            sections = await SpendingPermissionDetails.load(context)
        }
    }

    private static func load(_ context: TransactionDataContext) async -> Shown<[DelegateMandateText.MandateSection]> {
        do {
            let mandates = try await DelegateTransaction.shared.shownMandates(transactionData: context.transactionData)
            return .value(DelegateMandateText.shared.describeMandates(mandates: mandates))
        } catch is CancellationError {
            return .loading
        } catch {
            return .unreadable
        }
    }

    @ViewBuilder
    private func content(_ sections: [DelegateMandateText.MandateSection]) -> some View {
        let requestedBy = context.requester.origin ?? context.requester.appId
        VStack(alignment: .leading, spacing: 24) {
            VStack(alignment: .leading, spacing: 4) {
                Text(verbatim: DelegateMandateText.shared.TITLE)
                    .font(.title2.weight(.semibold))
                Text(verbatim: DelegateMandateText.shared.DETAILS_EXPLAINER)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            if let requestedBy {
                DetailLine(label: DelegateMandateText.shared.REQUESTED_BY, value: requestedBy)
            }
            ForEach(Array(sections.enumerated()), id: \.offset) { _, section in
                VStack(alignment: .leading, spacing: 8) {
                    Text(verbatim: section.heading)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.tint)
                    if !section.mandateType.isEmpty {
                        Text(verbatim: DelegateMandateText.shared.mandateTypeLine(mandateType: section.mandateType))
                            .font(.footnote.monospaced())
                            .foregroundStyle(.secondary)
                    }
                    ForEach(Array(section.lines.enumerated()), id: \.offset) { _, line in
                        DetailLine(
                            label: line.label,
                            value: line.value,
                            detail: line.detail,
                            monospaced: line.shape == DelegateMandateText.Shape.opaque
                        )
                    }
                }
            }
        }
        .textSelection(.enabled)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct DetailLine: View {
    let label: String
    let value: String
    var detail: String? = nil
    var monospaced: Bool = false

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: label)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(verbatim: value)
                .font(monospaced ? .subheadline.monospaced() : .subheadline)
                .fixedSize(horizontal: false, vertical: true)
            if let detail {
                Text(verbatim: detail)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Divider()
                .padding(.top, 8)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

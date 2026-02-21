import SwiftUI
import UIKit
import Combine

private struct DocumentListScrollOffsetKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value += nextValue()
    }
}

private struct CardInteractionView: UIViewRepresentable {
    var allowReordering: Bool
    var onTap: () -> Void
    var onLongPressStart: () -> Void
    var onDragChanged: (CGFloat) -> Void
    var onDragEnded: () -> Void
    
    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = .clear
        
        let tapRecognizer = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handleTap(_:)))
        view.addGestureRecognizer(tapRecognizer)
        
        let longPressRecognizer = UILongPressGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handleLongPress(_:)))
        longPressRecognizer.minimumPressDuration = 0.3
        view.addGestureRecognizer(longPressRecognizer)
        
        context.coordinator.longPressRecognizer = longPressRecognizer
        
        return view
    }
    
    func updateUIView(_ uiView: UIView, context: Context) {
        context.coordinator.parent = self
        context.coordinator.longPressRecognizer?.isEnabled = allowReordering
    }
    
    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }
    
    class Coordinator: NSObject {
        var parent: CardInteractionView
        var initialY: CGFloat = 0
        weak var longPressRecognizer: UILongPressGestureRecognizer?
        
        init(_ parent: CardInteractionView) {
            self.parent = parent
        }
        
        @objc func handleTap(_ gesture: UITapGestureRecognizer) {
            if gesture.state == .ended {
                parent.onTap()
            }
        }
        
        @objc func handleLongPress(_ gesture: UILongPressGestureRecognizer) {
            // Tracking globally against the window ensures dragging isn't warped
            // by the SwiftUI view's own visual offset changes during its animation.
            let location = gesture.location(in: nil)
            
            switch gesture.state {
            case .began:
                initialY = location.y
                parent.onLongPressStart()
            case .changed:
                let translationY = location.y - initialY
                parent.onDragChanged(translationY)
            case .ended, .cancelled, .failed:
                parent.onDragEnded()
            default:
                break
            }
        }
    }
}

/// A vertically scrolling list of documents that mimics a physical wallet experience.
///
/// In its default state, documents are displayed as a vertical list of cards. The amount of
/// overlap between cards is configurable. Users can long-press a card to drag and drop it into
/// a new position.
///
/// When a user taps a card, it enters a "focused" state. The focused card elevates and animates
/// to the top of the viewport. A dynamic content section (`showDocumentInfo`) fades in immediately
/// below it. By default, the remaining unfocused cards animate into a 3D overlapping stack at the
/// bottom of the screen.
///
/// - Parameters:
///   - documentModel: The `DocumentModel` providing the reactive flow of documents to display.
///   - focusedDocument: The currently focused document. When `nil`, the component operates in
///     standard list mode. When set to a `DocumentInfo`, that document is brought to the top and
///     detailed information is displayed.
///   - unfocusedVisiblePercent: Determines how much of each card is visible when not focused. A
///     value of `100` displays cards with standard spacing (no overlap). Lower values cause cards to
///     overlap, allowing more cards to fit on screen. Must be between 0 and 100.
///   - allowDocumentReordering: If `true`, users can long-press and drag cards to reorder them
///     when in standard list mode. Defaults to `true`.
///   - showStackWhileFocused: If `true`, unfocused cards will collapse into a 3D stack at the bottom
///     of the screen when a document is focused. If `false`, unfocused cards fade away entirely to maximize
///     screen real estate for the detail view. Defaults to `true`.
///   - showDocumentInfo: A `@ViewBuilder` closure that renders the detailed content below the focused card.
///     It is horizontally centered by default.
///   - emptyDocumentContent: A `@ViewBuilder` closure displayed inside a dashed placeholder card when the
///     `documentModel` is empty.
///   - onDocumentReordered: Callback invoked when a drag-and-drop reordering operation completes.
///     Provides the `DocumentInfo` of the moved card and its new index position in the list.
///   - onDocumentFocused: Callback invoked when a document is tapped to be focused.
///   - onDocumentFocusedTapped: Callback invoked when the currently focused document is tapped.
///   - onDocumentFocusedStackTapped: Callback invoked when the unfocused document stack is tapped while another document is in focus.
public struct VerticalDocumentList<EmptyContent: View, SelectedContent: View>: View {
    public var documentModel: DocumentModel
    public var focusedDocument: DocumentInfo?
    public var unfocusedVisiblePercent: Int
    public var allowDocumentReordering: Bool
    public var showStackWhileFocused: Bool
    
    @ViewBuilder public var showDocumentInfo: (DocumentInfo) -> SelectedContent
    @ViewBuilder public var emptyDocumentContent: () -> EmptyContent
    public var onDocumentReordered: (DocumentInfo, Int) -> Void
    public var onDocumentFocused: (DocumentInfo) -> Void
    public var onDocumentFocusedTapped: (DocumentInfo) -> Void
    public var onDocumentFocusedStackTapped: (DocumentInfo) -> Void

    @State private var displayOrder: [DocumentInfo] = []
    @State private var scrollOffset: CGFloat = 0
    
    @State private var draggedDocId: String? = nil
    @State private var dragCurrentY: CGFloat = 0
    @State private var startDragY: CGFloat = 0
    @State private var isDragging: Bool = false
    @State private var lastDragEndTime: Date = .distantPast
    
    public init(
        documentModel: DocumentModel,
        focusedDocument: DocumentInfo?,
        unfocusedVisiblePercent: Int = 25,
        allowDocumentReordering: Bool = true,
        showStackWhileFocused: Bool = true,
        @ViewBuilder showDocumentInfo: @escaping (DocumentInfo) -> SelectedContent = { _ in EmptyView() },
        @ViewBuilder emptyDocumentContent: @escaping () -> EmptyContent = { EmptyView() },
        onDocumentReordered: @escaping (DocumentInfo, Int) -> Void = { _, _ in },
        onDocumentFocused: @escaping (DocumentInfo) -> Void = { _ in },
        onDocumentFocusedTapped: @escaping (DocumentInfo) -> Void = { _ in },
        onDocumentFocusedStackTapped: @escaping (DocumentInfo) -> Void = { _ in }
    ) {
        self.documentModel = documentModel
        self.focusedDocument = focusedDocument
        self.unfocusedVisiblePercent = unfocusedVisiblePercent
        self.allowDocumentReordering = allowDocumentReordering
        self.showStackWhileFocused = showStackWhileFocused
        self.showDocumentInfo = showDocumentInfo
        self.emptyDocumentContent = emptyDocumentContent
        self.onDocumentReordered = onDocumentReordered
        self.onDocumentFocused = onDocumentFocused
        self.onDocumentFocusedTapped = onDocumentFocusedTapped
        self.onDocumentFocusedStackTapped = onDocumentFocusedStackTapped
    }
    
    public var body: some View {
        GeometryReader { proxy in
            let maxWidth = proxy.size.width
            let maxHeight = proxy.size.height
            
            let paddingHorizontal: CGFloat = 16
            let paddingTop: CGFloat = 24
            let spacing: CGFloat = 16
            
            let cardWidth = maxWidth - 2 * paddingHorizontal
            let cardHeight = cardWidth / 1.586
            
            let listStep: CGFloat = unfocusedVisiblePercent == 100
                ? cardHeight + spacing
                : cardHeight * (CGFloat(unfocusedVisiblePercent) / 100.0)
            
            let totalHeight = paddingTop + CGFloat(max(0, displayOrder.count - 1)) * listStep + cardHeight + paddingTop
            
            let maxStackIndex = max(0, displayOrder.count - 2)
            let maxVisibleCardsInStack = 5
            let maxVisibleStackOffsets = min(maxStackIndex, maxVisibleCardsInStack - 1)
            
            let stackOffset: CGFloat = 14
            let frontCardVisibleHeight = cardHeight * 0.25
            
            let detailBottomPadding: CGFloat = showStackWhileFocused
                ? frontCardVisibleHeight + CGFloat(maxVisibleStackOffsets) * stackOffset + 16
                : 16
            
            if displayOrder.isEmpty && documentModel.documentInfos.isEmpty {
                VStack {
                    Spacer().frame(height: paddingTop)
                    ZStack {
                        RoundedRectangle(cornerRadius: 24)
                            .strokeBorder(Color.gray, style: StrokeStyle(lineWidth: 3, dash: [30, 30]))
                        emptyDocumentContent()
                    }
                    .frame(width: cardWidth, height: cardHeight)
                    Spacer()
                }
                .frame(maxWidth: .infinity, alignment: .top)
            } else {
                ZStack(alignment: .topLeading) {
                    ScrollViewReader { scrollProxy in
                        ScrollView {
                            ZStack(alignment: .topLeading) {
                                Color.clear
                                    .contentShape(Rectangle())
                                    .frame(maxWidth: .infinity)
                                    .frame(height: totalHeight)
                                    .background(
                                        GeometryReader { geo in
                                            let minY = geo.frame(in: .named("DocListSpace")).minY
                                            Color.clear.preference(
                                                key: DocumentListScrollOffsetKey.self,
                                                value: minY
                                            )
                                        }
                                    )
                                    .id("TopSpacer")
                                
                                if let focusedDoc = focusedDocument {
                                    let detailHeight = max(0, maxHeight - detailBottomPadding)
                                    VStack {
                                        showDocumentInfo(focusedDoc)
                                    }
                                    .frame(maxWidth: .infinity, alignment: .top)
                                    .padding(.top, paddingTop + cardHeight * 1.05 + 24)
                                    .padding(.bottom, 24)
                                    .frame(width: maxWidth, height: detailHeight, alignment: .top)
                                    .offset(y: scrollOffset)
                                    .transition(.opacity)
                                    .zIndex(50)
                                }
                                
                                ForEach(Array(displayOrder.enumerated()), id: \.element.document.identifier) { index, docInfo in
                                    let cardState = calculateCardState(
                                        index: index, docInfo: docInfo, maxHeight: maxHeight, paddingTop: paddingTop,
                                        listStep: listStep, maxStackIndex: maxStackIndex, maxVisibleCardsInStack: maxVisibleCardsInStack,
                                        frontCardVisibleHeight: frontCardVisibleHeight, stackOffset: stackOffset
                                    )
                                    
                                    Image(uiImage: docInfo.cardArt)
                                        .resizable()
                                        .aspectRatio(contentMode: .fill)
                                        .frame(width: cardWidth, height: cardHeight)
                                        .clipShape(RoundedRectangle(cornerRadius: 24))
                                        .contentShape(Rectangle())
                                        .shadow(color: Color.black.opacity(0.15), radius: cardState.elevation, x: 0, y: cardState.elevation / 2)
                                        .scaleEffect(cardState.scale)
                                        .opacity(cardState.alpha)
                                        .overlay(
                                            CardInteractionView(
                                                allowReordering: focusedDocument == nil && allowDocumentReordering,
                                                onTap: {
                                                    guard !isDragging && Date().timeIntervalSince(lastDragEndTime) > 0.3 else { return }
                                                    if let focused = focusedDocument {
                                                        withAnimation(.spring(response: 0.4, dampingFraction: 0.8)) {
                                                            if docInfo.document.identifier == focused.document.identifier {
                                                                onDocumentFocusedTapped(focused)
                                                            } else {
                                                                onDocumentFocusedStackTapped(focused)
                                                            }
                                                        }
                                                    } else {
                                                        withAnimation(.spring(response: 0.4, dampingFraction: 0.8)) {
                                                            onDocumentFocused(docInfo)
                                                        }
                                                    }
                                                },
                                                onLongPressStart: {
                                                    let generator = UIImpactFeedbackGenerator(style: .heavy)
                                                    generator.impactOccurred()
                                                    withAnimation(.snappy) {
                                                        isDragging = true
                                                        draggedDocId = docInfo.document.identifier
                                                    }
                                                    startDragY = paddingTop + CGFloat(index) * listStep
                                                    dragCurrentY = startDragY
                                                },
                                                onDragChanged: { translationY in
                                                    guard isDragging && draggedDocId == docInfo.document.identifier else { return }
                                                    
                                                    dragCurrentY = startDragY + translationY
                                                    let newIndexRaw = Int(round((dragCurrentY - paddingTop) / listStep))
                                                    let newIndex = min(max(newIndexRaw, 0), displayOrder.count - 1)
                                                    let currentIndex = displayOrder.firstIndex(where: { $0.document.identifier == draggedDocId }) ?? index

                                                    if currentIndex != newIndex {
                                                        withAnimation(.snappy) {
                                                            let item = displayOrder.remove(at: currentIndex)
                                                            displayOrder.insert(item, at: newIndex)
                                                        }
                                                        let generator = UIImpactFeedbackGenerator(style: .light)
                                                        generator.impactOccurred()
                                                    }
                                                },
                                                onDragEnded: {
                                                    guard isDragging && draggedDocId == docInfo.document.identifier else { return }
                                                    let generator = UIImpactFeedbackGenerator(style: .medium)
                                                    generator.impactOccurred()
                                                    if let finalIndex = displayOrder.firstIndex(where: { $0.document.identifier == docInfo.document.identifier }) {
                                                        onDocumentReordered(displayOrder[finalIndex], finalIndex)
                                                    }
                                                    withAnimation(.snappy) {
                                                        draggedDocId = nil
                                                        isDragging = false
                                                        lastDragEndTime = Date()
                                                    }
                                                }
                                            )
                                        )
                                        .offset(x: paddingHorizontal, y: cardState.y)
                                        .zIndex(cardState.zIndex)
                                        .animation((docInfo.document.identifier == draggedDocId) ? .interactiveSpring() : .spring(response: 0.4, dampingFraction: 0.8), value: cardState.y)
                                        .animation(.spring(response: 0.4, dampingFraction: 0.8), value: cardState.scale)
                                        .animation(.spring(response: 0.4, dampingFraction: 0.8), value: cardState.elevation)
                                        .animation(.spring(response: 0.4, dampingFraction: 0.8), value: cardState.alpha)
                                }
                            }
                            .frame(width: maxWidth, height: totalHeight, alignment: .topLeading)
                        }
                        .coordinateSpace(name: "DocListSpace")
                        .scrollDisabled(focusedDocument != nil || isDragging)
                        .onPreferenceChange(DocumentListScrollOffsetKey.self) { value in
                            if value != -scrollOffset {
                                scrollOffset = -value
                            }
                        }
                    }
                }
            }
        }
        .onAppear {
            if displayOrder.isEmpty { displayOrder = documentModel.documentInfos }
        }
        .onChange(of: documentModel.documentInfos) { _, newInfos in
            if !isDragging { displayOrder = newInfos }
        }
    }
    
    private struct CardState {
        var y: CGFloat
        var scale: CGFloat
        var elevation: CGFloat
        var zIndex: Double
        var alpha: Double
    }
    
    private func calculateCardState(index: Int, docInfo: DocumentInfo, maxHeight: CGFloat, paddingTop: CGFloat, listStep: CGFloat, maxStackIndex: Int, maxVisibleCardsInStack: Int, frontCardVisibleHeight: CGFloat, stackOffset: CGFloat) -> CardState {
        let isFocused = docInfo.document.identifier == focusedDocument?.document.identifier
        let isDragged = docInfo.document.identifier == draggedDocId
        let isAnyFocused = focusedDocument != nil
        let focusedIndex = displayOrder.firstIndex(where: { $0.document.identifier == focusedDocument?.document.identifier }) ?? 0
        
        if isAnyFocused {
            if isFocused {
                return CardState(y: scrollOffset + paddingTop, scale: 1.05, elevation: 24, zIndex: 100, alpha: 1.0)
            } else {
                let stackIndex = index < focusedIndex ? index : index - 1
                let distanceToFront = maxStackIndex - stackIndex
                let clampedDistanceToFront = min(distanceToFront, maxVisibleCardsInStack - 1)
                let frontCardY = maxHeight - frontCardVisibleHeight
                let targetY = scrollOffset + frontCardY - CGFloat(clampedDistanceToFront) * stackOffset
                let targetScale = max(0.6, 0.95 - (CGFloat(clampedDistanceToFront) * 0.025))
                return CardState(y: targetY, scale: targetScale, elevation: 12, zIndex: Double(stackIndex), alpha: (!showStackWhileFocused || distanceToFront >= maxVisibleCardsInStack) ? 0.0 : 1.0)
            }
        } else {
            let targetY = isDragged ? dragCurrentY : (paddingTop + CGFloat(index) * listStep)
            return CardState(y: targetY, scale: isDragged ? 1.05 : 1.0, elevation: isDragged ? 24 : 12, zIndex: isDragged ? 100 : Double(index), alpha: 1.0)
        }
    }
}

import SwiftUI
import UIKit
import Combine

private struct CardListScrollOffsetKey: PreferenceKey {
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

/// A vertically scrolling list of cards that mimics a physical wallet experience.
///
/// In its default state, cards are displayed as a vertical list. The amount of
/// overlap between cards is configurable. Users can long-press a card to drag and drop it into
/// a new position.
///
/// When a user taps a card, it enters a "focused" state. The focused card elevates and animates
/// to the top of the viewport. A dynamic content section (`showCardInfo`) fades in immediately
/// below it. By default, the remaining unfocused cards animate into a 3D overlapping stack at the
/// bottom of the screen.
///
/// - Parameters:
///   - cardInfos: The list of `CardInfo` objects to display.
///   - focusedCard: The currently focused card. When `nil`, the component operates in
///     standard list mode. When set to a `CardInfo`, that card is brought to the top and
///     detailed information is displayed.
///   - unfocusedVisiblePercent: Determines how much of each card is visible when not focused. A
///     value of `100` displays cards with standard spacing (no overlap). Lower values cause cards to
///     overlap, allowing more cards to fit on screen. Must be between 0 and 100.
///   - allowCardReordering: If `true`, users can long-press and drag cards to reorder them
///     when in standard list mode. Defaults to `true`.
///   - showStackWhileFocused: If `true`, unfocused cards will collapse into a 3D stack at the bottom
///     of the screen when a card is focused. If `false`, unfocused cards fade away entirely to maximize
///     screen real estate for the detail view. Defaults to `true`.
///   - showCardInfo: A `@ViewBuilder` closure that renders the detailed content below the focused card.
///     It is horizontally centered by default.
///   - emptyContent: A `@ViewBuilder` closure displayed inside a dashed placeholder card when the
///     `cardInfos` list is empty.
///   - onCardReordered: Callback invoked when a drag-and-drop reordering operation completes.
///     Provides the `CardInfo` of the moved card and its new index position in the list.
///   - onCardFocused: Callback invoked when a card is tapped to be focused.
///   - onCardFocusedTapped: Callback invoked when the currently focused card is tapped.
///   - onCardFocusedStackTapped: Callback invoked when the unfocused card stack is tapped while another card is in focus.
public struct VerticalCardList<EmptyContent: View, SelectedContent: View>: View {
    public var cardInfos: [CardInfo]
    public var focusedCard: CardInfo?
    public var unfocusedVisiblePercent: Int
    public var allowCardReordering: Bool
    public var showStackWhileFocused: Bool
    
    @ViewBuilder public var showCardInfo: (CardInfo) -> SelectedContent
    @ViewBuilder public var emptyContent: () -> EmptyContent
    public var onCardReordered: (CardInfo, Int) -> Void
    public var onCardFocused: (CardInfo) -> Void
    public var onCardFocusedTapped: (CardInfo) -> Void
    public var onCardFocusedStackTapped: (CardInfo) -> Void

    @State private var displayOrder: [CardInfo] = []
    @State private var scrollOffset: CGFloat = 0
    
    @State private var draggedCardIndex: Int? = nil
    @State private var dragCurrentY: CGFloat = 0
    @State private var startDragY: CGFloat = 0
    @State private var isDragging: Bool = false
    @State private var lastDragEndTime: Date = .distantPast
    
    public init(
        cardInfos: [CardInfo],
        focusedCard: CardInfo?,
        unfocusedVisiblePercent: Int = 25,
        allowCardReordering: Bool = true,
        showStackWhileFocused: Bool = true,
        @ViewBuilder showCardInfo: @escaping (CardInfo) -> SelectedContent = { _ in EmptyView() },
        @ViewBuilder emptyContent: @escaping () -> EmptyContent = { EmptyView() },
        onCardReordered: @escaping (CardInfo, Int) -> Void = { _, _ in },
        onCardFocused: @escaping (CardInfo) -> Void = { _ in },
        onCardFocusedTapped: @escaping (CardInfo) -> Void = { _ in },
        onCardFocusedStackTapped: @escaping (CardInfo) -> Void = { _ in }
    ) {
        self.cardInfos = cardInfos
        self.focusedCard = focusedCard
        self.unfocusedVisiblePercent = unfocusedVisiblePercent
        self.allowCardReordering = allowCardReordering
        self.showStackWhileFocused = showStackWhileFocused
        self.showCardInfo = showCardInfo
        self.emptyContent = emptyContent
        self.onCardReordered = onCardReordered
        self.onCardFocused = onCardFocused
        self.onCardFocusedTapped = onCardFocusedTapped
        self.onCardFocusedStackTapped = onCardFocusedStackTapped
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
            
            if displayOrder.isEmpty && cardInfos.isEmpty {
                VStack {
                    Spacer().frame(height: paddingTop)
                    ZStack {
                        RoundedRectangle(cornerRadius: 24)
                            .strokeBorder(Color.gray, style: StrokeStyle(lineWidth: 3, dash: [30, 30]))
                        emptyContent()
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
                                            let minY = geo.frame(in: .named("CardListSpace")).minY
                                            Color.clear.preference(
                                                key: CardListScrollOffsetKey.self,
                                                value: minY
                                            )
                                        }
                                    )
                                    .id("TopSpacer")
                                
                                if let focused = focusedCard {
                                    let detailHeight = max(0, maxHeight - detailBottomPadding)
                                    VStack {
                                        showCardInfo(focused)
                                    }
                                    .frame(maxWidth: .infinity, alignment: .top)
                                    .padding(.top, paddingTop + cardHeight * 1.05 + 24)
                                    .padding(.bottom, 24)
                                    .frame(width: maxWidth, height: detailHeight, alignment: .top)
                                    .offset(y: scrollOffset)
                                    .transition(.opacity)
                                    .zIndex(50)
                                }
                                
                                ForEach(Array(displayOrder.enumerated()), id: \.offset) { index, cardInfo in
                                    let cardState = calculateCardState(
                                        index: index, cardInfo: cardInfo, maxHeight: maxHeight, paddingTop: paddingTop,
                                        listStep: listStep, maxStackIndex: maxStackIndex, maxVisibleCardsInStack: maxVisibleCardsInStack,
                                        frontCardVisibleHeight: frontCardVisibleHeight, stackOffset: stackOffset
                                    )
                                    
                                    ZStack(alignment: .topTrailing) {
                                        Image(uiImage: cardInfo.cardArt)
                                            .resizable()
                                            .aspectRatio(contentMode: .fill)
                                            .frame(width: cardWidth, height: cardHeight)
                                            .clipShape(RoundedRectangle(cornerRadius: 24))
                                        
                                        CardBadgesView(badges: cardInfo.badges)
                                    }
                                    .contentShape(Rectangle())
                                    .shadow(color: Color.black.opacity(0.15), radius: cardState.elevation, x: 0, y: cardState.elevation / 2)
                                        .scaleEffect(cardState.scale)
                                        .opacity(cardState.alpha)
                                        .overlay(
                                            CardInteractionView(
                                                allowReordering: focusedCard == nil && allowCardReordering,
                                                onTap: {
                                                    guard !isDragging && Date().timeIntervalSince(lastDragEndTime) > 0.3 else { return }
                                                    if let focused = focusedCard {
                                                        withAnimation(.spring(response: 0.4, dampingFraction: 0.8)) {
                                                            if cardInfo.identifier == focused.identifier {
                                                                onCardFocusedTapped(focused)
                                                            } else {
                                                                onCardFocusedStackTapped(focused)
                                                            }
                                                        }
                                                    } else {
                                                        withAnimation(.spring(response: 0.4, dampingFraction: 0.8)) {
                                                            onCardFocused(cardInfo)
                                                        }
                                                    }
                                                },
                                                onLongPressStart: {
                                                    let generator = UIImpactFeedbackGenerator(style: .heavy)
                                                    generator.impactOccurred()
                                                    withAnimation(.snappy) {
                                                        isDragging = true
                                                        draggedCardIndex = index
                                                    }
                                                    startDragY = paddingTop + CGFloat(index) * listStep
                                                    dragCurrentY = startDragY
                                                },
                                                onDragChanged: { translationY in
                                                    guard isDragging, let currentIndex = draggedCardIndex else { return }
                                                    
                                                    dragCurrentY = startDragY + translationY
                                                    let newIndexRaw = Int(round((dragCurrentY - paddingTop) / listStep))
                                                    let newIndex = min(max(newIndexRaw, 0), displayOrder.count - 1)

                                                    if currentIndex != newIndex {
                                                        withAnimation(.snappy) {
                                                            let item = displayOrder.remove(at: currentIndex)
                                                            displayOrder.insert(item, at: newIndex)
                                                            draggedCardIndex = newIndex
                                                            // Update startDragY so further translation is relative to new index
                                                            startDragY = paddingTop + CGFloat(newIndex) * listStep
                                                            // We don't update dragCurrentY here because it's what we're currently using
                                                        }
                                                        let generator = UIImpactFeedbackGenerator(style: .light)
                                                        generator.impactOccurred()
                                                    }
                                                },
                                                onDragEnded: {
                                                    guard isDragging, let finalIndex = draggedCardIndex else { return }
                                                    let generator = UIImpactFeedbackGenerator(style: .medium)
                                                    generator.impactOccurred()
                                                    onCardReordered(displayOrder[finalIndex], finalIndex)
                                                    withAnimation(.snappy) {
                                                        draggedCardIndex = nil
                                                        isDragging = false
                                                        lastDragEndTime = Date()
                                                    }
                                                }
                                            )
                                        )
                                        .offset(x: paddingHorizontal, y: cardState.y)
                                        .zIndex(cardState.zIndex)
                                        .animation((index == draggedCardIndex) ? .interactiveSpring() : .spring(response: 0.4, dampingFraction: 0.8), value: cardState.y)
                                        .animation(.spring(response: 0.4, dampingFraction: 0.8), value: cardState.scale)
                                        .animation(.spring(response: 0.4, dampingFraction: 0.8), value: cardState.elevation)
                                        .animation(.spring(response: 0.4, dampingFraction: 0.8), value: cardState.alpha)
                                }
                            }
                            .frame(width: maxWidth, height: totalHeight, alignment: .topLeading)
                        }
                        .coordinateSpace(name: "CardListSpace")
                        .scrollDisabled(focusedCard != nil || isDragging)
                        .onPreferenceChange(CardListScrollOffsetKey.self) { value in
                            if value != -scrollOffset {
                                scrollOffset = -value
                            }
                        }
                    }
                }
            }
        }
        .onAppear {
            if displayOrder.isEmpty { displayOrder = cardInfos }
        }
        .onChange(of: cardInfos.count) { _, _ in
             if !isDragging { displayOrder = cardInfos }
        }
    }
    
    private struct CardState {
        var y: CGFloat
        var scale: CGFloat
        var elevation: CGFloat
        var zIndex: Double
        var alpha: Double
    }
    
    private func calculateCardState(index: Int, cardInfo: CardInfo, maxHeight: CGFloat, paddingTop: CGFloat, listStep: CGFloat, maxStackIndex: Int, maxVisibleCardsInStack: Int, frontCardVisibleHeight: CGFloat, stackOffset: CGFloat) -> CardState {
        let isFocused = cardInfo.identifier == focusedCard?.identifier
        let isDragged = index == draggedCardIndex
        let isAnyFocused = focusedCard != nil
        let focusedIndex = displayOrder.firstIndex(where: { $0.identifier == focusedCard?.identifier }) ?? 0
        
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

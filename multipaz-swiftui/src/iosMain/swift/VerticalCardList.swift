import SwiftUI
import UIKit
import Combine

/// State object for `VerticalCardList`.
///
/// Stores and synchronizes order, dragging, and scroll properties to enable smooth layout transitions.
@Observable
public class VerticalCardListState {
    /// The current display order of the cards, tracked by identifier.
    public var displayOrderIdentifiers: [String] = []
    
    /// The identifier of the card currently being dragged, if any.
    public var draggedCardIdentifier: String? = nil
    
    /// The current Y position of the dragged card.
    public var dragCurrentY: CGFloat = 0
    
    /// Whether a drag operation just ended.
    public var dragJustEnded: Bool = false
    
    /// The identifier of the card currently focused, if any.
    public var internalFocusedCardIdentifier: String? = nil
    
    /// Whether to animate spatial transitions (like sliding cards) when entering this screen.
    /// This should typically be set to true only when navigating directly between two list states.
    public var animateListTransitions: Bool = true
    
    /// The current scroll offset, normalized against the initial content offset.
    public var scrollOffset: CGFloat = 0
    
    /// The initial content offset measured when the scroll view layout is established.
    public var initialContentOffset: CGFloat = 0
    
    /// Whether the initial content offset has been captured and resolved.
    public var isScrollOffsetInitialized: Bool = false
    
    public init() {}
    
    /// Unfocuses the currently focused card with animation, and calls the completion block when finished.
    public func unfocus(completion: @escaping () -> Void) {
        if internalFocusedCardIdentifier != nil {
            withAnimation(.spring(response: 0.4, dampingFraction: 0.8)) {
                self.internalFocusedCardIdentifier = nil
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) {
                completion()
            }
        } else {
            completion()
        }
    }
}

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

fileprivate struct ScrollViewObserver: UIViewRepresentable {
    var state: VerticalCardListState
    
    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = .clear
        DispatchQueue.main.async {
            if let scrollView = view.findAncestorScrollView() {
                context.coordinator.setup(scrollView)
            }
        }
        return view
    }
    
    func updateUIView(_ uiView: UIView, context: Context) {
        if let scrollView = uiView.findAncestorScrollView() {
            if state.isScrollOffsetInitialized {
                let targetContentOffset = state.scrollOffset + state.initialContentOffset
                if abs(scrollView.contentOffset.y - targetContentOffset) > 1 {
                    context.coordinator.isUpdatingOffset = true
                    scrollView.contentOffset.y = targetContentOffset
                    context.coordinator.isUpdatingOffset = false
                }
            }
        }
    }
    
    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }
    
    class Coordinator: NSObject {
        var parent: ScrollViewObserver
        var scrollView: UIScrollView?
        var observation: NSKeyValueObservation?
        var isUpdatingOffset = false
        
        init(_ parent: ScrollViewObserver) {
            self.parent = parent
        }
        
        func setup(_ scrollView: UIScrollView) {
            self.scrollView = scrollView
            if !parent.state.isScrollOffsetInitialized {
                parent.state.initialContentOffset = scrollView.contentOffset.y
                parent.state.scrollOffset = 0
                parent.state.isScrollOffsetInitialized = true
            } else {
                let targetContentOffset = parent.state.scrollOffset + parent.state.initialContentOffset
                if abs(scrollView.contentOffset.y - targetContentOffset) > 1 {
                    isUpdatingOffset = true
                    scrollView.contentOffset.y = targetContentOffset
                    isUpdatingOffset = false
                }
            }
            observation = scrollView.observe(\.contentOffset, options: [.new]) { [weak self] scrollView, change in
                guard let self = self else { return }
                guard !self.isUpdatingOffset else { return }
                guard scrollView.isDragging || scrollView.isDecelerating || scrollView.isTracking else { return }
                let newY = scrollView.contentOffset.y
                let newRelativeOffset = newY - self.parent.state.initialContentOffset
                if abs(self.parent.state.scrollOffset - newRelativeOffset) > 1 {
                    self.parent.state.scrollOffset = newRelativeOffset
                }
            }
        }
        
        deinit {
            observation?.invalidate()
        }
    }
}

extension UIView {
    fileprivate func findAncestorScrollView() -> UIScrollView? {
        var current: UIView? = self
        while current != nil {
            if let scrollView = current as? UIScrollView {
                return scrollView
            }
            current = current?.superview
        }
        return nil
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
public struct VerticalCardList<EmptyContent: View, SelectedContent: View>: View {
    /// The list of `CardInfo` objects to display.
    public var cardInfos: [CardInfo]
    
    /// The currently focused card. When `nil`, the component operates in standard list mode.
    public var focusedCard: CardInfo?
    
    /// Determines how much of each card is visible when not focused. A value of `100` displays
    /// cards with standard spacing (no overlap). Lower values cause cards to overlap.
    public var unfocusedVisiblePercent: Int
    
    /// If `true`, users can long-press and drag cards to reorder them in standard list mode.
    public var allowCardReordering: Bool
    
    /// If `true`, unfocused cards will collapse into a 3D stack at the bottom of the screen.
    public var showStackWhileFocused: Bool
    
    /// The state object used to control or observe the list's state.
    public var state: VerticalCardListState
    
    /// Whether to animate transitions when entering or navigating between list states.
    public var animateListTransitions: Bool
    
    /// Renders the detailed content below the focused card.
    @ViewBuilder public var showCardInfo: (CardInfo) -> SelectedContent
    
    /// Displayed inside a dashed placeholder card when the card list is empty.
    @ViewBuilder public var emptyContent: () -> EmptyContent
    
    /// Callback invoked when a drag-and-drop reordering operation completes.
    public var onCardReordered: (CardInfo, Int) -> Void
    
    /// Callback invoked when a card is tapped to be focused.
    public var onCardFocused: (CardInfo) -> Void
    
    /// Callback invoked when the currently focused card is tapped.
    public var onCardFocusedTapped: (CardInfo) -> Void
    
    /// Callback invoked when the unfocused card stack is tapped while another card is in focus.
    public var onCardFocusedStackTapped: (CardInfo) -> Void

    @State private var displayOrder: [CardInfo]
    
    @State private var startDragY: CGFloat = 0
    @State private var isDragging: Bool = false
    @State private var lastDragEndTime: Date = .distantPast
    

    
    /// Initializes a `VerticalCardList` view.
    ///
    /// - Parameters:
    ///   - cardInfos: The list of `CardInfo` objects to display.
    ///   - focusedCard: The currently focused card.
    ///   - unfocusedVisiblePercent: Percent of card visible when unfocused (0 to 100).
    ///   - allowCardReordering: Whether to allow dragging to reorder cards.
    ///   - showStackWhileFocused: Whether to show the collapsed 3D card stack at the bottom of the screen.
    ///   - state: The list state tracker object.
    ///   - animateListTransitions: Whether to animate view list transitions.
    ///   - showCardInfo: Closure rendering detailed info view for a card.
    ///   - emptyContent: Closure rendering layout when the card list is empty.
    ///   - onCardReordered: Callback triggered on card reordering completion.
    ///   - onCardFocused: Callback triggered on selecting a card.
    ///   - onCardFocusedTapped: Callback triggered on tapping a focused card.
    ///   - onCardFocusedStackTapped: Callback triggered on tapping the background stack while focused.
    public init(
        cardInfos: [CardInfo],
        focusedCard: CardInfo?,
        unfocusedVisiblePercent: Int = 25,
        allowCardReordering: Bool = true,
        showStackWhileFocused: Bool = true,
        state: VerticalCardListState = VerticalCardListState(),
        animateListTransitions: Bool = true,
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
        self.state = state
        self.animateListTransitions = animateListTransitions
        self.showCardInfo = showCardInfo
        self.emptyContent = emptyContent
        self.onCardReordered = onCardReordered
        self.onCardFocused = onCardFocused
        self.onCardFocusedTapped = onCardFocusedTapped
        self.onCardFocusedStackTapped = onCardFocusedStackTapped
        
        let initialDisplayOrder = state.displayOrderIdentifiers.isEmpty
            ? cardInfos
            : state.displayOrderIdentifiers.compactMap { id in cardInfos.first { $0.identifier == id } }
        self._displayOrder = State(initialValue: initialDisplayOrder)
        
        if !animateListTransitions {
            state.internalFocusedCardIdentifier = focusedCard?.identifier
        }
    }
    
    private func syncDisplayOrder() {
        let currentCardIdentifiers = cardInfos.map { $0.identifier }
        if state.draggedCardIdentifier == nil && state.displayOrderIdentifiers != currentCardIdentifiers {
            state.displayOrderIdentifiers = currentCardIdentifiers
        }
        
        displayOrder = state.displayOrderIdentifiers.compactMap { id in
            cardInfos.first { $0.identifier == id }
        }
    }
    
    public var body: some View {
        GeometryReader { proxy in
            let maxWidth = proxy.size.width
            let maxHeight = proxy.size.height
            
            let paddingHorizontal: CGFloat = 16
            let paddingTop: CGFloat = 24
            let spacing: CGFloat = 16
            
            let cardWidth = max(0, maxWidth - 2 * paddingHorizontal)
            let cardHeight = max(0, cardWidth / 1.586)
            
            let listStep: CGFloat = max(0, unfocusedVisiblePercent == 100
                ? cardHeight + spacing
                : cardHeight * (CGFloat(unfocusedVisiblePercent) / 100.0))
            
            let totalHeight = max(0, paddingTop + CGFloat(max(0, displayOrder.count - 1)) * listStep + cardHeight + paddingTop)
            
            let maxStackIndex = max(0, displayOrder.count - 2)
            let maxVisibleCardsInStack = 5
            let maxVisibleStackOffsets = min(maxStackIndex, maxVisibleCardsInStack - 1)
            
            let stackOffset: CGFloat = 14
            let frontCardVisibleHeight = cardHeight * 0.25
            
            let detailBottomPadding: CGFloat = max(0, showStackWhileFocused
                ? frontCardVisibleHeight + CGFloat(maxVisibleStackOffsets) * stackOffset + 16
                : 16)
            
            let internalFocusedCard = focusedCard == nil ? nil : cardInfos.first {
                $0.identifier == state.internalFocusedCardIdentifier
            }
            
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
                                ScrollViewObserver(state: state)
                                
                                Color.clear
                                    .contentShape(Rectangle())
                                    .frame(maxWidth: .infinity)
                                    .frame(height: totalHeight)
                                    .id("TopSpacer")
                                
                                if let focused = internalFocusedCard {
                                    let detailHeight = max(0, maxHeight - detailBottomPadding)
                                    VStack {
                                        showCardInfo(focused)
                                    }
                                    .frame(maxWidth: .infinity, alignment: .top)
                                    .padding(.top, paddingTop + cardHeight * 1.05 + 24)
                                    .padding(.bottom, 24)
                                    .frame(width: maxWidth, height: detailHeight, alignment: .top)
                                    .offset(y: state.scrollOffset)
                                    .transition(.opacity)
                                    .zIndex(50)
                                }
                                
                                ForEach(displayOrder, id: \.identifier) { cardInfo in
                                    let index = displayOrder.firstIndex(where: { $0.identifier == cardInfo.identifier }) ?? 0
                                    let cardState = calculateCardState(
                                        index: index, cardInfo: cardInfo, maxHeight: maxHeight, paddingTop: paddingTop,
                                        listStep: listStep, maxStackIndex: maxStackIndex, maxVisibleCardsInStack: maxVisibleCardsInStack,
                                        frontCardVisibleHeight: frontCardVisibleHeight, stackOffset: stackOffset
                                    )
                                    let isDragged = cardInfo.identifier == state.draggedCardIdentifier
                                    
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
                                                allowReordering: state.internalFocusedCardIdentifier == nil && allowCardReordering,
                                                onTap: {
                                                    guard !isDragging && !state.dragJustEnded && state.draggedCardIdentifier == nil && Date().timeIntervalSince(lastDragEndTime) > 0.3 else { return }
                                                    if let focused = internalFocusedCard {
                                                        if cardInfo.identifier == focused.identifier {
                                                            onCardFocusedTapped(focused)
                                                        } else {
                                                            onCardFocusedStackTapped(focused)
                                                        }
                                                    } else {
                                                        onCardFocused(cardInfo)
                                                    }
                                                },
                                                onLongPressStart: {
                                                    let generator = UIImpactFeedbackGenerator(style: .heavy)
                                                    generator.impactOccurred()
                                                    withAnimation(.snappy) {
                                                        isDragging = true
                                                        state.draggedCardIdentifier = cardInfo.identifier
                                                    }
                                                    startDragY = paddingTop + CGFloat(index) * listStep
                                                    state.dragCurrentY = startDragY
                                                },
                                                onDragChanged: { translationY in
                                                    guard isDragging, state.draggedCardIdentifier == cardInfo.identifier else { return }
                                                    
                                                    state.dragCurrentY = startDragY + translationY
                                                    let newIndexRaw = Int(round((state.dragCurrentY - paddingTop) / listStep))
                                                    let newIndex = min(max(newIndexRaw, 0), displayOrder.count - 1)
                                
                                                    if index != newIndex {
                                                        withAnimation(.snappy) {
                                                            let item = displayOrder.remove(at: index)
                                                            displayOrder.insert(item, at: newIndex)
                                                        }
                                                        let generator = UIImpactFeedbackGenerator(style: .light)
                                                        generator.impactOccurred()
                                                    }
                                                },
                                                onDragEnded: {
                                                    guard isDragging, state.draggedCardIdentifier == cardInfo.identifier else { return }
                                                    let generator = UIImpactFeedbackGenerator(style: .medium)
                                                    generator.impactOccurred()
                                                    onCardReordered(cardInfo, index)
                                                    
                                                    state.displayOrderIdentifiers = displayOrder.map { $0.identifier }
                                                    state.dragJustEnded = true
                                                    state.draggedCardIdentifier = nil
                                                    
                                                    withAnimation(.snappy) {
                                                        isDragging = false
                                                        lastDragEndTime = Date()
                                                    }
                                                }
                                            )
                                        )
                                        .offset(x: paddingHorizontal, y: cardState.y)
                                        .zIndex(cardState.zIndex)
                                        .animation(isDragged ? .interactiveSpring() : (animateListTransitions ? .spring(response: 0.4, dampingFraction: 0.8) : nil), value: cardState.y)
                                        .animation(animateListTransitions ? .spring(response: 0.4, dampingFraction: 0.8) : nil, value: cardState.scale)
                                        .animation(animateListTransitions ? .spring(response: 0.4, dampingFraction: 0.8) : nil, value: cardState.elevation)
                                        .animation(animateListTransitions ? .spring(response: 0.4, dampingFraction: 0.8) : nil, value: cardState.alpha)
                                }
                            }
                            .frame(width: maxWidth, height: totalHeight, alignment: .topLeading)
                        }
                        .coordinateSpace(name: "CardListSpace")
                        .scrollDisabled((focusedCard != nil && state.internalFocusedCardIdentifier != nil) || isDragging)
                    }
                }
            }
        }
        .onAppear {
            syncDisplayOrder()
            
            if animateListTransitions {
                if state.internalFocusedCardIdentifier != focusedCard?.identifier {
                    DispatchQueue.main.async {
                        withAnimation(.spring(response: 0.4, dampingFraction: 0.8)) {
                            state.internalFocusedCardIdentifier = focusedCard?.identifier
                        }
                    }
                }
            } else {
                state.internalFocusedCardIdentifier = focusedCard?.identifier
            }
        }
        .onChange(of: cardInfos.map { $0.identifier }) { _, _ in
             if !isDragging {
                 syncDisplayOrder()
             }
        }
        .onChange(of: focusedCard?.identifier) { _, newId in
            if animateListTransitions {
                if state.internalFocusedCardIdentifier != newId {
                    DispatchQueue.main.async {
                        withAnimation(.spring(response: 0.4, dampingFraction: 0.8)) {
                            state.internalFocusedCardIdentifier = newId
                        }
                    }
                }
            } else {
                state.internalFocusedCardIdentifier = newId
            }
        }
        .onChange(of: state.dragJustEnded) { _, newValue in
            if newValue {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                    state.dragJustEnded = false
                }
            }
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
        let isFocused = cardInfo.identifier == state.internalFocusedCardIdentifier
        let isDragged = cardInfo.identifier == state.draggedCardIdentifier
        let isAnyFocused = focusedCard != nil && state.internalFocusedCardIdentifier != nil
        let focusedIndex = focusedCard == nil ? 0 : (displayOrder.firstIndex(where: { $0.identifier == state.internalFocusedCardIdentifier }) ?? 0)
        
        if isAnyFocused {
            if isFocused {
                return CardState(y: state.scrollOffset + paddingTop, scale: 1.05, elevation: 24, zIndex: 100, alpha: 1.0)
            } else {
                let stackIndex = index < focusedIndex ? index : index - 1
                let distanceToFront = maxStackIndex - stackIndex
                let clampedDistanceToFront = min(distanceToFront, maxVisibleCardsInStack - 1)
                let frontCardY = max(0, maxHeight - frontCardVisibleHeight)
                let targetY = state.scrollOffset + frontCardY - CGFloat(clampedDistanceToFront) * stackOffset
                let targetScale = max(0.6, 0.95 - (CGFloat(clampedDistanceToFront) * 0.025))
                return CardState(y: targetY, scale: targetScale, elevation: 12, zIndex: Double(stackIndex), alpha: (!showStackWhileFocused || distanceToFront >= maxVisibleCardsInStack) ? 0.0 : 1.0)
            }
        } else {
            let targetY = isDragged ? state.dragCurrentY : (paddingTop + CGFloat(index) * listStep)
            return CardState(y: targetY, scale: isDragged ? 1.05 : 1.0, elevation: isDragged ? 24 : 12, zIndex: isDragged ? 100 : Double(index), alpha: 1.0)
        }
    }
}

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
    
    /// The identifier of the last focused card, used to preserve animations across navigation.
    public var lastFocusedCardIdentifier: String? = nil
    
    /// Whether to animate spatial transitions (like sliding cards) when entering this screen.
    /// This should typically be set to true only when navigating directly between two list states.
    public var animateListTransitions: Bool = true
    
    /// Whether the top content view should be shown when no card is focused.
    public var showTopContent: Bool = true
    
    /// Whether to show a dashed placeholder card when the card list is empty.
    public var showPlaceholderWhenEmpty: Bool = true
    
    /// The measured height of the top content view.
    public var topContentHeight: CGFloat = 0
    
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
                self.lastFocusedCardIdentifier = nil
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) {
                completion()
            }
        } else {
            completion()
        }
    }
}

private struct TopContentHeightPreferenceKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        let next = nextValue()
        if next > 0 {
            value = next
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
public struct VerticalCardList<TopContent: View, EmptyContent: View, SelectedContent: View>: View {
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
    
    /// An optional max height constraint for the cards.
    public var cardMaxHeight: CGFloat?
    
    /// The top padding for the card list. Defaults to 16.
    public var paddingTop: CGFloat
    
    /// The bottom padding for the card list. Defaults to 16.
    public var paddingBottom: CGFloat
    
    /// The state object used to control or observe the list's state.
    public var state: VerticalCardListState
    
    /// Whether the top content should be shown when no card is focused. When nil, uses state.showTopContent.
    public var showTopContent: Bool?
    
    /// Whether to show a dashed placeholder card when the card list is empty. When nil, uses state.showPlaceholderWhenEmpty.
    public var showPlaceholderWhenEmpty: Bool?
    
    /// Whether to animate transitions when entering or navigating between list states.
    public var animateListTransitions: Bool
    
    /// Slot displayed at the top of the list when no card is focused.
    @ViewBuilder public var topContent: () -> TopContent
    
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
    @State private var isInitialized: Bool = false
    
    /// Initializes a `VerticalCardList` view.
    ///
    /// - Parameters:
    ///   - cardInfos: The list of `CardInfo` objects to display.
    ///   - focusedCard: The currently focused card.
    ///   - unfocusedVisiblePercent: Percent of card visible when unfocused (0 to 100).
    ///   - allowCardReordering: Whether to allow dragging to reorder cards.
    ///   - showStackWhileFocused: Whether to show the collapsed 3D card stack at the bottom of the screen.
    ///   - cardMaxHeight: An optional max height constraint for the cards.
    ///   - paddingTop: The top padding for the card list.
    ///   - paddingBottom: The bottom padding for the card list.
    ///   - state: The list state tracker object.
    ///   - showTopContent: Whether the top content should be shown when no card is focused.
    ///   - showPlaceholderWhenEmpty: Whether to show a dashed placeholder card when the card list is empty.
    ///   - animateListTransitions: Whether to animate view list transitions.
    ///   - topContent: Closure rendering slot at the top of the list when no card is focused.
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
        cardMaxHeight: CGFloat? = nil,
        paddingTop: CGFloat = 16,
        paddingBottom: CGFloat = 16,
        state: VerticalCardListState = VerticalCardListState(),
        showTopContent: Bool? = nil,
        showPlaceholderWhenEmpty: Bool? = nil,
        animateListTransitions: Bool = true,
        @ViewBuilder topContent: @escaping () -> TopContent = { EmptyView() },
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
        self.cardMaxHeight = cardMaxHeight
        self.paddingTop = paddingTop
        self.paddingBottom = paddingBottom
        self.state = state
        self.showTopContent = showTopContent
        self.showPlaceholderWhenEmpty = showPlaceholderWhenEmpty
        self.animateListTransitions = animateListTransitions
        self.topContent = topContent
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
        if state.displayOrderIdentifiers.isEmpty && !cardInfos.isEmpty {
            state.displayOrderIdentifiers = cardInfos.map { $0.identifier }
        }
        
        if animateListTransitions {
            state.internalFocusedCardIdentifier = state.lastFocusedCardIdentifier
        } else {
            state.internalFocusedCardIdentifier = focusedCard?.identifier
            state.lastFocusedCardIdentifier = focusedCard?.identifier
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
    
    private struct CardListLayout {
        let maxWidth: CGFloat
        let maxHeight: CGFloat
        let cardWidth: CGFloat
        let cardHeight: CGFloat
        let cardXOffset: CGFloat
        let listTopOffset: CGFloat
        let listStep: CGFloat
        let totalHeight: CGFloat
        let maxStackIndex: Int
        let maxVisibleCardsInStack: Int
        let frontCardVisibleHeight: CGFloat
        let stackOffset: CGFloat
        let detailBottomPadding: CGFloat
        let isTopContentEffectivelyVisible: Bool
        let internalFocusedCard: CardInfo?
    }
    
    private func computeLayout(maxWidth: CGFloat, maxHeight: CGFloat) -> CardListLayout {
        let paddingHorizontal: CGFloat = 16
        let spacing: CGFloat = 16
        
        var cardWidth: CGFloat = max(0, maxWidth - 2 * paddingHorizontal)
        var cardHeight: CGFloat = max(0, cardWidth / 1.586)
        
        if let maxAllowedCardHeight = cardMaxHeight, cardHeight > maxAllowedCardHeight {
            cardHeight = maxAllowedCardHeight
            cardWidth = cardHeight * 1.586
        }
        
        let cardXOffset: CGFloat = (maxWidth - cardWidth) / 2
        
        let effectiveShowTopContent: Bool = showTopContent ?? state.showTopContent
        let isAnyFocused: Bool = state.internalFocusedCardIdentifier != nil
        let isTopContentEffectivelyVisible: Bool = effectiveShowTopContent && !isAnyFocused
        
        let topContentSpacing: CGFloat = state.topContentHeight > 0 ? spacing : 0
        let effectiveTopContentHeight: CGFloat = isTopContentEffectivelyVisible ? (state.topContentHeight + topContentSpacing) : 0
        let listTopOffset: CGFloat = paddingTop + effectiveTopContentHeight
        
        let listStep: CGFloat = max(0, unfocusedVisiblePercent == 100
            ? cardHeight + spacing
            : cardHeight * (CGFloat(unfocusedVisiblePercent) / 100.0))
        
        let totalHeight: CGFloat = max(0, listTopOffset + CGFloat(max(0, displayOrder.count - 1)) * listStep + cardHeight + paddingBottom)
        
        let maxStackIndex: Int = max(0, displayOrder.count - 2)
        let maxVisibleCardsInStack: Int = 5
        let maxVisibleStackOffsets: Int = min(maxStackIndex, maxVisibleCardsInStack - 1)
        
        let stackOffset: CGFloat = 14
        let frontCardVisibleHeight: CGFloat = cardHeight * 0.25
        
        let detailBottomPadding: CGFloat = max(0, showStackWhileFocused
            ? frontCardVisibleHeight + CGFloat(maxVisibleStackOffsets) * stackOffset + 16
            : 16)
        
        let internalFocusedCard: CardInfo? = cardInfos.first {
            $0.identifier == state.internalFocusedCardIdentifier
        }
        
        return CardListLayout(
            maxWidth: maxWidth,
            maxHeight: maxHeight,
            cardWidth: cardWidth,
            cardHeight: cardHeight,
            cardXOffset: cardXOffset,
            listTopOffset: listTopOffset,
            listStep: listStep,
            totalHeight: totalHeight,
            maxStackIndex: maxStackIndex,
            maxVisibleCardsInStack: maxVisibleCardsInStack,
            frontCardVisibleHeight: frontCardVisibleHeight,
            stackOffset: stackOffset,
            detailBottomPadding: detailBottomPadding,
            isTopContentEffectivelyVisible: isTopContentEffectivelyVisible,
            internalFocusedCard: internalFocusedCard
        )
    }

    @ViewBuilder
    private func emptyView(
        cardWidth: CGFloat,
        cardHeight: CGFloat,
        paddingTop: CGFloat,
        paddingHorizontal: CGFloat,
        isTopContentEffectivelyVisible: Bool
    ) -> some View {
        VStack(spacing: 16) {
            if isTopContentEffectivelyVisible {
                topContent()
                    .frame(width: cardWidth)
                    .transition(.opacity)
            }
            if showPlaceholderWhenEmpty ?? state.showPlaceholderWhenEmpty {
                ZStack {
                    RoundedRectangle(cornerRadius: 24)
                        .strokeBorder(Color.gray, style: StrokeStyle(lineWidth: 3, dash: [30, 30]))
                    emptyContent()
                }
                .frame(width: cardWidth, height: cardHeight)
            }
            Spacer()
        }
        .padding(.top, paddingTop)
        .padding(.horizontal, paddingHorizontal)
        .frame(maxWidth: .infinity, alignment: .top)
        .animation(isInitialized && animateListTransitions ? .spring(response: 0.4, dampingFraction: 0.8) : nil, value: isTopContentEffectivelyVisible)
    }

    @ViewBuilder
    private func topContentView(
        maxWidth: CGFloat,
        paddingHorizontal: CGFloat,
        paddingTop: CGFloat,
        isTopContentEffectivelyVisible: Bool
    ) -> some View {
        if state.topContentHeight > 0 || isTopContentEffectivelyVisible {
            topContent()
                .frame(width: maxWidth - 2 * paddingHorizontal)
                .background(
                    GeometryReader { topGeo in
                        Color.clear.preference(
                            key: TopContentHeightPreferenceKey.self,
                            value: topGeo.size.height
                        )
                    }
                )
                .offset(x: paddingHorizontal, y: isTopContentEffectivelyVisible ? paddingTop : (paddingTop - state.topContentHeight))
                .opacity(isTopContentEffectivelyVisible ? 1.0 : 0.0)
                .animation(isInitialized && animateListTransitions ? .spring(response: 0.4, dampingFraction: 0.8) : nil, value: isTopContentEffectivelyVisible)
                .zIndex(1)
        }
    }

    @ViewBuilder
    private func focusedDetailView(
        focused: CardInfo,
        maxWidth: CGFloat,
        maxHeight: CGFloat,
        paddingTop: CGFloat,
        cardHeight: CGFloat,
        detailBottomPadding: CGFloat
    ) -> some View {
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

    @ViewBuilder
    private func cardItemView(
        cardInfo: CardInfo,
        index: Int,
        layout: CardListLayout
    ) -> some View {
        let cardState = calculateCardState(
            index: index, cardInfo: cardInfo, maxHeight: layout.maxHeight, paddingTop: paddingTop,
            listTopOffset: layout.listTopOffset,
            listStep: layout.listStep, maxStackIndex: layout.maxStackIndex, maxVisibleCardsInStack: layout.maxVisibleCardsInStack,
            frontCardVisibleHeight: layout.frontCardVisibleHeight, stackOffset: layout.stackOffset
        )
        let isDragged = cardInfo.identifier == state.draggedCardIdentifier
        
        ZStack(alignment: .topTrailing) {
            Image(uiImage: cardInfo.cardArt)
                .resizable()
                .aspectRatio(contentMode: .fill)
                .frame(width: layout.cardWidth, height: layout.cardHeight)
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
                    if let focused = layout.internalFocusedCard {
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
                    startDragY = layout.listTopOffset + CGFloat(index) * layout.listStep
                    state.dragCurrentY = startDragY
                },
                onDragChanged: { translationY in
                    guard isDragging, state.draggedCardIdentifier == cardInfo.identifier else { return }
                    
                    state.dragCurrentY = startDragY + translationY
                    let newIndexRaw = Int(round((state.dragCurrentY - layout.listTopOffset) / layout.listStep))
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
        .offset(x: layout.cardXOffset, y: cardState.y)
        .zIndex(cardState.zIndex)
        .transition(.identity)
        .animation(isDragged ? .interactiveSpring() : (isInitialized && animateListTransitions ? .spring(response: 0.4, dampingFraction: 0.8) : nil), value: cardState.y)
        .animation(isInitialized && animateListTransitions ? .spring(response: 0.4, dampingFraction: 0.8) : nil, value: cardState.scale)
        .animation(isInitialized && animateListTransitions ? .spring(response: 0.4, dampingFraction: 0.8) : nil, value: cardState.elevation)
        .animation(isInitialized && animateListTransitions ? .spring(response: 0.4, dampingFraction: 0.8) : nil, value: cardState.alpha)
    }

    @ViewBuilder
    private func listView(layout: CardListLayout) -> some View {
        ZStack(alignment: .topLeading) {
            ScrollViewReader { scrollProxy in
                ScrollView {
                    ZStack(alignment: .topLeading) {
                        ScrollViewObserver(state: state)
                        
                        Color.clear
                            .contentShape(Rectangle())
                            .frame(maxWidth: .infinity)
                            .frame(height: layout.totalHeight)
                            .id("TopSpacer")
                        
                        topContentView(
                            maxWidth: layout.maxWidth,
                            paddingHorizontal: 16,
                            paddingTop: paddingTop,
                            isTopContentEffectivelyVisible: layout.isTopContentEffectivelyVisible
                        )
                        
                        if let focused = layout.internalFocusedCard {
                            focusedDetailView(
                                focused: focused,
                                maxWidth: layout.maxWidth,
                                maxHeight: layout.maxHeight,
                                paddingTop: paddingTop,
                                cardHeight: layout.cardHeight,
                                detailBottomPadding: layout.detailBottomPadding
                            )
                        }
                        
                        ForEach(Array(displayOrder.enumerated()), id: \.element.identifier) { index, cardInfo in
                            cardItemView(
                                cardInfo: cardInfo,
                                index: index,
                                layout: layout
                            )
                        }
                    }
                    .frame(width: layout.maxWidth, height: layout.totalHeight, alignment: .topLeading)
                }
                .coordinateSpace(name: "CardListSpace")
                .scrollDisabled(state.internalFocusedCardIdentifier != nil || isDragging)
            }
        }
    }
    
    public var body: some View {
        GeometryReader { proxy in
            if proxy.size.width <= 0 || proxy.size.height <= 0 {
                Color.clear
            } else {
                let layout = computeLayout(maxWidth: proxy.size.width, maxHeight: proxy.size.height)
                if displayOrder.isEmpty && cardInfos.isEmpty {
                    emptyView(
                        cardWidth: layout.cardWidth,
                        cardHeight: layout.cardHeight,
                        paddingTop: paddingTop,
                        paddingHorizontal: 16,
                        isTopContentEffectivelyVisible: layout.isTopContentEffectivelyVisible
                    )
                } else {
                    listView(layout: layout)
                }
            }
        }
        .onPreferenceChange(TopContentHeightPreferenceKey.self) { newHeight in
            if newHeight > 0 && abs(state.topContentHeight - newHeight) > 0.5 {
                var transaction = Transaction()
                transaction.disablesAnimations = true
                withTransaction(transaction) {
                    state.topContentHeight = newHeight
                }
            }
        }
        .onAppear {
            syncDisplayOrder()
            
            if animateListTransitions && state.lastFocusedCardIdentifier != focusedCard?.identifier {
                state.internalFocusedCardIdentifier = state.lastFocusedCardIdentifier
                DispatchQueue.main.async {
                    withAnimation(.spring(response: 0.4, dampingFraction: 0.8)) {
                        state.internalFocusedCardIdentifier = focusedCard?.identifier
                        state.lastFocusedCardIdentifier = focusedCard?.identifier
                    }
                    isInitialized = true
                }
            } else {
                state.internalFocusedCardIdentifier = focusedCard?.identifier
                state.lastFocusedCardIdentifier = focusedCard?.identifier
                DispatchQueue.main.async {
                    isInitialized = true
                }
            }
        }
        .onChange(of: cardInfos.map { $0.identifier }) { _, _ in
             if !isDragging {
                 syncDisplayOrder()
             }
        }
        .onChange(of: focusedCard?.identifier) { _, newId in
            if animateListTransitions && state.lastFocusedCardIdentifier != newId {
                DispatchQueue.main.async {
                    withAnimation(.spring(response: 0.4, dampingFraction: 0.8)) {
                        state.internalFocusedCardIdentifier = newId
                        state.lastFocusedCardIdentifier = newId
                    }
                }
            } else {
                state.internalFocusedCardIdentifier = newId
                state.lastFocusedCardIdentifier = newId
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
    
    private func calculateCardState(index: Int, cardInfo: CardInfo, maxHeight: CGFloat, paddingTop: CGFloat, listTopOffset: CGFloat, listStep: CGFloat, maxStackIndex: Int, maxVisibleCardsInStack: Int, frontCardVisibleHeight: CGFloat, stackOffset: CGFloat) -> CardState {
        let isFocused = cardInfo.identifier == state.internalFocusedCardIdentifier
        let isDragged = cardInfo.identifier == state.draggedCardIdentifier
        let isAnyFocused = state.internalFocusedCardIdentifier != nil
        let focusedIndex = displayOrder.firstIndex(where: { $0.identifier == state.internalFocusedCardIdentifier }) ?? 0
        
        if isAnyFocused {
            if isFocused {
                return CardState(y: state.scrollOffset + paddingTop, scale: 1.025, elevation: 24, zIndex: 100, alpha: 1.0)
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
            let targetY = isDragged ? state.dragCurrentY : (listTopOffset + CGFloat(index) * listStep)
            return CardState(y: targetY, scale: isDragged ? 1.05 : 1.0, elevation: isDragged ? 24 : 12, zIndex: isDragged ? 100 : Double(index), alpha: 1.0)
        }
    }
}

extension VerticalCardList where TopContent == EmptyView {
    public init(
        cardInfos: [CardInfo],
        focusedCard: CardInfo?,
        unfocusedVisiblePercent: Int = 25,
        allowCardReordering: Bool = true,
        showStackWhileFocused: Bool = true,
        cardMaxHeight: CGFloat? = nil,
        paddingTop: CGFloat = 16,
        paddingBottom: CGFloat = 16,
        state: VerticalCardListState = VerticalCardListState(),
        showTopContent: Bool? = nil,
        showPlaceholderWhenEmpty: Bool? = nil,
        animateListTransitions: Bool = true,
        @ViewBuilder showCardInfo: @escaping (CardInfo) -> SelectedContent = { _ in EmptyView() },
        @ViewBuilder emptyContent: @escaping () -> EmptyContent = { EmptyView() },
        onCardReordered: @escaping (CardInfo, Int) -> Void = { _, _ in },
        onCardFocused: @escaping (CardInfo) -> Void = { _ in },
        onCardFocusedTapped: @escaping (CardInfo) -> Void = { _ in },
        onCardFocusedStackTapped: @escaping (CardInfo) -> Void = { _ in }
    ) {
        self.init(
            cardInfos: cardInfos,
            focusedCard: focusedCard,
            unfocusedVisiblePercent: unfocusedVisiblePercent,
            allowCardReordering: allowCardReordering,
            showStackWhileFocused: showStackWhileFocused,
            cardMaxHeight: cardMaxHeight,
            paddingTop: paddingTop,
            paddingBottom: paddingBottom,
            state: state,
            showTopContent: showTopContent,
            showPlaceholderWhenEmpty: showPlaceholderWhenEmpty,
            animateListTransitions: animateListTransitions,
            topContent: { EmptyView() },
            showCardInfo: showCardInfo,
            emptyContent: emptyContent,
            onCardReordered: onCardReordered,
            onCardFocused: onCardFocused,
            onCardFocusedTapped: onCardFocusedTapped,
            onCardFocusedStackTapped: onCardFocusedStackTapped
        )
    }
}

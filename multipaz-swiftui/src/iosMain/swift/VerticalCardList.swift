import SwiftUI
import UIKit
import Combine
import Multipaz

/// State object for `VerticalCardList`.
///
/// Stores and synchronizes order, dragging, and scroll properties to enable smooth layout transitions.
@Observable
public class VerticalCardListState {
    /// The underlying toolkit-agnostic model.
    public let model: VerticalCardListModel

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

    public init(model: VerticalCardListModel = VerticalCardListModel()) {
        self.model = model
    }

    /// Unfocuses the currently focused card with animation, and calls the completion block when finished.
    public func unfocus(completion: @escaping () -> Void) {
        if internalFocusedCardIdentifier != nil {
            withAnimation(.easeInOut(duration: 0.38)) {
                self.internalFocusedCardIdentifier = nil
                self.lastFocusedCardIdentifier = nil
                self.model.lastFocusedCardIdentifier = nil
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.40) {
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

private struct ScrollViewObserver: UIViewRepresentable {
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
            observation = scrollView.observe(\.contentOffset, options: [.new]) { [weak self] scrollView, _ in
                guard let self = self else { return }
                guard !self.isUpdatingOffset else { return }
                let newY = scrollView.contentOffset.y
                let newRelativeOffset = newY - self.parent.state.initialContentOffset
                if abs(self.parent.state.scrollOffset - newRelativeOffset) > 0.5 {
                    self.parent.state.scrollOffset = newRelativeOffset
                    self.parent.state.model.scrollOffset = Double(newRelativeOffset)
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
public struct VerticalCardList<TopContent: View, EmptyContent: View, SelectedContent: View>: View {
    public var cardInfos: [CardInfo]
    public var focusedCard: CardInfo?
    public var unfocusedVisiblePercent: Int
    public var allowCardReordering: Bool
    public var showStackWhileFocused: Bool
    public var cardMaxHeight: CGFloat?
    public var paddingTop: CGFloat
    public var paddingBottom: CGFloat
    public var state: VerticalCardListState
    public var showTopContent: Bool?
    public var showPlaceholderWhenEmpty: Bool?
    public var animateListTransitions: Bool

    @ViewBuilder public var topContent: () -> TopContent
    @ViewBuilder public var showCardInfo: (CardInfo) -> SelectedContent
    @ViewBuilder public var emptyContent: () -> EmptyContent

    public var onCardReordered: (CardInfo, Int) -> Void
    public var onCardFocused: (CardInfo) -> Void
    public var onCardFocusedTapped: (CardInfo) -> Void
    public var onCardFocusedStackTapped: (CardInfo) -> Void

    @State private var displayOrder: [CardInfo]
    @State private var startDragY: CGFloat = 0
    @State private var isDragging: Bool = false
    @State private var lastDragEndTime: Date = .distantPast

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

        if state.displayOrderIdentifiers.isEmpty && !cardInfos.isEmpty {
            state.displayOrderIdentifiers = cardInfos.map { $0.identifier }
            state.model.displayOrderIdentifiers = state.displayOrderIdentifiers
        }

        self._displayOrder = State(initialValue: [])
    }

    private var effectiveDisplayOrder: [CardInfo] {
        if state.draggedCardIdentifier != nil {
            return displayOrder
        }
        let orderIds = state.displayOrderIdentifiers.isEmpty ? cardInfos.map { $0.identifier } : state.displayOrderIdentifiers
        var remainingCards = cardInfos
        var result: [CardInfo] = []
        for id in orderIds {
            if let idx = remainingCards.firstIndex(where: { $0.identifier == id }) {
                result.append(remainingCards.remove(at: idx))
            }
        }
        result.append(contentsOf: remainingCards)
        return result
    }

    private func createLayoutParameters(
        maxWidth: CGFloat,
        maxHeight: CGFloat,
        isTopContentEffectivelyVisible: Bool
    ) -> CardListLayoutParameters {
        let maxH: KotlinDouble? = cardMaxHeight != nil ? KotlinDouble(double: Double(cardMaxHeight!)) : nil
        return CardListLayoutParameters(
            viewportWidth: Double(maxWidth),
            viewportHeight: Double(maxHeight),
            cardMaxHeight: maxH,
            paddingTop: Double(paddingTop),
            paddingBottom: Double(paddingBottom),
            paddingHorizontal: 16.0,
            unfocusedVisiblePercent: Int32(unfocusedVisiblePercent),
            showStackWhileFocused: showStackWhileFocused,
            topContentHeight: Double(state.topContentHeight),
            isTopContentVisible: showTopContent ?? state.showTopContent,
            topContentProgress: isTopContentEffectivelyVisible ? 1.0 : 0.0,
            scrollOffset: Double(state.scrollOffset),
            stackOffset: 14.0,
            maxVisibleCardsInStack: 5,
            frontCardVisibleHeightFraction: 0.25,
            spacing: 16.0
        )
    }

    private func createCardLayoutItems(cards: [CardInfo]) -> [CardLayoutItem] {
        return cards.map { card in
            let size = card.cardArt.size
            let ratio = (size.height > 0) ? Double(size.width / size.height) : CardLayoutItem.Companion.shared.DEFAULT_ASPECT_RATIO
            return CardLayoutItem(identifier: card.identifier, aspectRatio: ratio)
        }
    }

    @ViewBuilder
    private func emptyView(
        cardWidth: CGFloat,
        cardHeight: CGFloat,
        paddingTop: CGFloat,
        paddingHorizontal: CGFloat,
        isTopContentEffectivelyVisible: Bool,
        canAnimate: Bool
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
        .animation(canAnimate ? .easeInOut(duration: 0.38) : nil, value: isTopContentEffectivelyVisible)
    }

    @ViewBuilder
    private func topContentView(
        maxWidth: CGFloat,
        paddingHorizontal: CGFloat,
        paddingTop: CGFloat,
        isTopContentEffectivelyVisible: Bool,
        canAnimate: Bool
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
                .animation(canAnimate ? .easeInOut(duration: 0.38) : nil, value: isTopContentEffectivelyVisible)
                .zIndex(1)
        }
    }

    @ViewBuilder
    private func focusedDetailView(
        focused: CardInfo,
        maxWidth: CGFloat,
        maxHeight: CGFloat,
        topOffset: CGFloat,
        detailBottomPadding: CGFloat
    ) -> some View {
        let detailHeight = max(0, maxHeight - detailBottomPadding)
        VStack {
            showCardInfo(focused)
        }
        .frame(maxWidth: .infinity, alignment: .top)
        .padding(.top, topOffset)
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
        layout: CardListLayout,
        layoutParams: CardListLayoutParameters,
        cardLayoutItems: [CardLayoutItem],
        isAnyFocused: Bool,
        internalFocusedCard: CardInfo?,
        canAnimate: Bool
    ) -> some View {
        let cardDim = layout.getDimensions(cardIdentifier: cardInfo.identifier)
        let effectiveFocusedId = isAnyFocused ? (internalFocusedCard?.identifier ?? (animateListTransitions ? state.internalFocusedCardIdentifier : focusedCard?.identifier)) : nil
        let visualState = CardListLayoutCalculator.shared.computeCardVisualState(
            cardIdentifier: cardInfo.identifier,
            index: Int32(index),
            cards: cardLayoutItems,
            layout: layout,
            params: layoutParams,
            focusedCardIdentifier: effectiveFocusedId,
            draggedCardIdentifier: state.draggedCardIdentifier,
            dragCurrentY: Double(state.dragCurrentY)
        )
        let isDragged = cardInfo.identifier == state.draggedCardIdentifier

        ZStack(alignment: .topTrailing) {
            Image(uiImage: cardInfo.cardArt)
                .resizable()
                .aspectRatio(contentMode: .fill)
                .frame(width: CGFloat(cardDim.width), height: CGFloat(cardDim.height))
                .clipShape(RoundedRectangle(cornerRadius: 24))

            CardBadgesView(badges: cardInfo.badges)
        }
        .contentShape(Rectangle())
        .shadow(color: Color.black.opacity(0.15), radius: CGFloat(visualState.elevation), x: 0, y: CGFloat(visualState.elevation) / 2)
        .scaleEffect(CGFloat(visualState.scale))
        .opacity(visualState.alpha)
        .overlay(
            CardInteractionView(
                allowReordering: state.internalFocusedCardIdentifier == nil && allowCardReordering,
                onTap: {
                    let action = state.model.handleTap(
                        cardIdentifier: cardInfo.identifier,
                        isAnyFocused: isAnyFocused,
                        focusedCardIdentifier: state.internalFocusedCardIdentifier
                    )
                    switch action {
                    case is CardTapActionIgnore:
                        break
                    case let focusAction as CardTapActionFocus:
                        if let card = cardInfos.first(where: { $0.identifier == focusAction.cardIdentifier }) {
                            onCardFocused(card)
                        }
                    case is CardTapActionFocusedCardTapped:
                        if let focused = internalFocusedCard {
                            onCardFocusedTapped(focused)
                        }
                    case is CardTapActionFocusedStackTapped:
                        if let focused = internalFocusedCard {
                            onCardFocusedStackTapped(focused)
                        }
                    default:
                        break
                    }
                },
                onLongPressStart: {
                    let generator = UIImpactFeedbackGenerator(style: .heavy)
                    generator.impactOccurred()
                    displayOrder = effectiveDisplayOrder
                    state.model.displayOrderIdentifiers = displayOrder.map { $0.identifier }
                    if state.model.startDrag(
                        cardIdentifier: cardInfo.identifier,
                        layout: layout,
                        cards: cardLayoutItems,
                        params: layoutParams
                    ) {
                        withAnimation(.snappy) {
                            isDragging = true
                            state.draggedCardIdentifier = cardInfo.identifier
                            state.dragCurrentY = CGFloat(state.model.dragCurrentY)
                        }
                        startDragY = CGFloat(state.model.dragCurrentY)
                    }
                },
                onDragChanged: { translationY in
                    guard isDragging, state.draggedCardIdentifier == cardInfo.identifier else { return }
                    let targetDragY = Double(startDragY + translationY)
                    let updateResult = state.model.updateDragPosition(
                        dragY: targetDragY,
                        layout: layout,
                        cards: cardLayoutItems,
                        params: layoutParams
                    )
                    state.dragCurrentY = CGFloat(state.model.dragCurrentY)

                    if updateResult.reordered {
                        withAnimation(.snappy) {
                            state.displayOrderIdentifiers = state.model.displayOrderIdentifiers
                            displayOrder = effectiveDisplayOrder
                        }
                        let generator = UIImpactFeedbackGenerator(style: .light)
                        generator.impactOccurred()
                    }
                },
                onDragEnded: {
                    guard isDragging, state.draggedCardIdentifier == cardInfo.identifier else { return }
                    let generator = UIImpactFeedbackGenerator(style: .medium)
                    generator.impactOccurred()
                    let endResult = state.model.endDrag()

                    state.displayOrderIdentifiers = state.model.displayOrderIdentifiers
                    state.dragJustEnded = true
                    state.draggedCardIdentifier = nil

                    withAnimation(.snappy) {
                        isDragging = false
                        lastDragEndTime = Date()
                    }

                    if let endResult = endResult, let finalCard = cardInfos.first(where: { $0.identifier == endResult.cardIdentifier }) {
                        onCardReordered(finalCard, Int(endResult.newIndex))
                    }
                }
            )
        )
        .offset(x: CGFloat(cardDim.xOffset), y: CGFloat(visualState.y))
        .zIndex(Double(visualState.zIndex))
        .transition(.identity)
        .animation(isDragged ? .interactiveSpring() : (canAnimate ? .easeInOut(duration: 0.38) : nil), value: visualState.y)
        .animation(canAnimate ? .easeInOut(duration: 0.38) : nil, value: visualState.scale)
        .animation(canAnimate ? .easeInOut(duration: 0.38) : nil, value: visualState.elevation)
        .animation(canAnimate ? .easeInOut(duration: 0.38) : nil, value: visualState.alpha)
    }

    @ViewBuilder
    private func listView(
        layout: CardListLayout,
        layoutParams: CardListLayoutParameters,
        cardLayoutItems: [CardLayoutItem],
        isAnyFocused: Bool,
        internalFocusedCard: CardInfo?,
        canAnimate: Bool
    ) -> some View {
        ZStack(alignment: .topLeading) {
            ScrollViewReader { _ in
                ScrollView {
                    ZStack(alignment: .topLeading) {
                        ScrollViewObserver(state: state)

                        Color.clear
                            .contentShape(Rectangle())
                            .frame(maxWidth: .infinity)
                            .frame(height: CGFloat(layout.totalHeight))
                            .id("TopSpacer")

                        topContentView(
                            maxWidth: CGFloat(layoutParams.viewportWidth),
                            paddingHorizontal: 16,
                            paddingTop: paddingTop,
                            isTopContentEffectivelyVisible: layout.isTopContentEffectivelyVisible,
                            canAnimate: canAnimate
                        )

                        if let focused = internalFocusedCard {
                            let focusedCardDim = layout.getDimensions(cardIdentifier: focused.identifier)
                            let topOffset = CardListLayoutCalculator.shared.computeDetailTopOffset(
                                paddingTop: Double(paddingTop),
                                focusedCardHeight: focusedCardDim.height,
                                spacing: 24.0
                            )
                            focusedDetailView(
                                focused: focused,
                                maxWidth: CGFloat(layoutParams.viewportWidth),
                                maxHeight: CGFloat(layoutParams.viewportHeight),
                                topOffset: CGFloat(topOffset),
                                detailBottomPadding: CGFloat(layout.detailBottomPadding)
                            )
                        }

                        ForEach(Array(effectiveDisplayOrder.enumerated()), id: \.element.identifier) { index, cardInfo in
                            cardItemView(
                                cardInfo: cardInfo,
                                index: index,
                                layout: layout,
                                layoutParams: layoutParams,
                                cardLayoutItems: cardLayoutItems,
                                isAnyFocused: isAnyFocused,
                                internalFocusedCard: internalFocusedCard,
                                canAnimate: canAnimate
                            )
                        }
                    }
                    .frame(width: CGFloat(layoutParams.viewportWidth), height: CGFloat(layout.totalHeight), alignment: .topLeading)
                }
                .coordinateSpace(name: "CardListSpace")
                .scrollDisabled(isAnyFocused || isDragging)
            }
        }
    }

    public var body: some View {
        GeometryReader { proxy in
            if proxy.size.width <= 0 || proxy.size.height <= 0 {
                Color.clear
            } else {
                let effectiveFocusedCardIdentifier = if animateListTransitions {
                    state.internalFocusedCardIdentifier
                } else {
                    focusedCard?.identifier
                }
                let isAnyFocused = focusedCard != nil && effectiveFocusedCardIdentifier != nil
                let internalFocusedCard: CardInfo? = (focusedCard == nil) ? nil : cardInfos.first {
                    $0.identifier == effectiveFocusedCardIdentifier
                }
                let effectiveShowTopContent = showTopContent ?? state.showTopContent
                let isTopContentEffectivelyVisible = effectiveShowTopContent && !isAnyFocused
                let canAnimate = animateListTransitions

                let currentDisplayOrder = effectiveDisplayOrder
                let cardLayoutItems = createCardLayoutItems(cards: currentDisplayOrder)
                let layoutParams = createLayoutParameters(
                    maxWidth: proxy.size.width,
                    maxHeight: proxy.size.height,
                    isTopContentEffectivelyVisible: isTopContentEffectivelyVisible
                )
                let layout = CardListLayoutCalculator.shared.computeLayout(
                    params: layoutParams,
                    cards: cardLayoutItems,
                    isAnyFocused: isAnyFocused
                )

                if currentDisplayOrder.isEmpty && cardInfos.isEmpty {
                    emptyView(
                        cardWidth: CGFloat(layout.defaultCardDimensions.width),
                        cardHeight: CGFloat(layout.defaultCardDimensions.height),
                        paddingTop: paddingTop,
                        paddingHorizontal: 16,
                        isTopContentEffectivelyVisible: isTopContentEffectivelyVisible,
                        canAnimate: canAnimate
                    )
                } else {
                    listView(
                        layout: layout,
                        layoutParams: layoutParams,
                        cardLayoutItems: cardLayoutItems,
                        isAnyFocused: isAnyFocused,
                        internalFocusedCard: internalFocusedCard,
                        canAnimate: canAnimate
                    )
                }
            }
        }
        .onPreferenceChange(TopContentHeightPreferenceKey.self) { newHeight in
            if newHeight > 0 && abs(state.topContentHeight - newHeight) > 0.5 {
                var transaction = Transaction()
                transaction.disablesAnimations = true
                withTransaction(transaction) {
                    state.topContentHeight = newHeight
                    state.model.topContentHeight = Double(newHeight)
                }
            }
        }
        .onAppear {
            if animateListTransitions {
                if state.lastFocusedCardIdentifier != focusedCard?.identifier {
                    state.internalFocusedCardIdentifier = state.lastFocusedCardIdentifier
                    DispatchQueue.main.async {
                        withAnimation(.easeInOut(duration: 0.38)) {
                            state.internalFocusedCardIdentifier = focusedCard?.identifier
                            state.lastFocusedCardIdentifier = focusedCard?.identifier
                            state.model.lastFocusedCardIdentifier = focusedCard?.identifier
                        }
                    }
                }
            } else {
                state.internalFocusedCardIdentifier = focusedCard?.identifier
                state.lastFocusedCardIdentifier = focusedCard?.identifier
                state.model.lastFocusedCardIdentifier = focusedCard?.identifier
            }
        }
        .onChange(of: focusedCard?.identifier) { _, newId in
            if animateListTransitions && state.lastFocusedCardIdentifier != newId {
                withAnimation(.easeInOut(duration: 0.38)) {
                    state.internalFocusedCardIdentifier = newId
                    state.lastFocusedCardIdentifier = newId
                    state.model.lastFocusedCardIdentifier = newId
                }
            } else {
                state.internalFocusedCardIdentifier = newId
                state.lastFocusedCardIdentifier = newId
                state.model.lastFocusedCardIdentifier = newId
            }
        }
        .onChange(of: state.dragJustEnded) { _, newValue in
            if newValue {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                    state.dragJustEnded = false
                    state.model.clearDragJustEnded()
                }
            }
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

import SwiftUI
import UIKit
import Combine

// MARK: - Internal Helper Models

private struct CarouselModel: Identifiable, Equatable {
    let id: String
    let name: String
    let image: UIImage
    
    static func == (lhs: CarouselModel, rhs: CarouselModel) -> Bool {
        return lhs.id == rhs.id
    }
}

// MARK: - DocumentCarousel

/// A horizontal carousel view that displays a collection of documents.
///
/// `DocumentCarousel` provides a highly interactive way to browse, select, and reorder documents.
/// It features a "cover flow" style layout where the center item is elevated, and side items are scaled down.
///
/// ## Features
/// - **Snap Scrolling**: Automatically snaps to the nearest card after dragging.
/// - **Reordering**: Long-press and drag to reorder items (optional).
/// - **Focus Reporting**: Reports which document is currently centered.
/// - **Custom Overlays**: Supports custom views for selected item information and empty states.
///
/// ## Usage Example
///
/// ```swift
/// DocumentCarousel(
///     documentModel: myDocumentModel,
///     initialDocumentId: "doc_123",
///     allowReordering: true,
///     onDocumentClicked: { docInfo in
///         print("Tapped on: \(docInfo.document.displayName ?? "Unknown")")
///     },
///     onDocumentReordered: { docInfo, oldIndex, newIndex in
///         print("Moved \(docInfo.document.identifier) from \(oldIndex) to \(newIndex)")
///     },
///     selectedDocumentInfo: { docInfo, index, total in
///         // A view that appears below the active card
///         VStack {
///             Text(docInfo?.document.displayName ?? "Loading...")
///                 .font(.headline)
///             Text("\(index + 1) of \(total)")
///                 .font(.caption)
///         }
///     },
///     emptyDocumentContent: {
///         Text("No Documents Found")
///     }
/// )
/// ```
public struct DocumentCarousel<EmptyContent: View, SelectedContent: View>: View {
    
    // MARK: - Properties
    
    /// The data source containing the list of documents to display.
    public var documentModel: DocumentModel
    
    /// The identifier of the document to focus on immediately upon loading.
    public var initialDocumentId: String?
    
    /// Controls whether the user can reorder cards via long-press and drag.
    /// Defaults to `true`.
    public var allowReordering: Bool
    
    /// Callback triggered when a document card is tapped.
    public var onDocumentClicked: (DocumentInfo) -> Void
    
    /// Callback triggered when a document settles in the center of the screen.
    public var onDocumentFocused: (DocumentInfo) -> Void
    
    /// Callback triggered when a document has been successfully moved to a new index.
    /// - Parameters:
    ///   - document: The document that was moved.
    ///   - oldIndex: The original index before the move.
    ///   - newIndex: The new index after the move.
    public var onDocumentReordered: (_ document: DocumentInfo, _ oldIndex: Int, _ newIndex: Int) -> Void
    
    /// A view builder that constructs the overlay information for the currently selected document.
    ///
    /// The closure provides:
    /// - `DocumentInfo?`: The currently focused document (nil during reordering).
    /// - `Int`: The current index.
    /// - `Int`: The total number of items.
    public var selectedDocumentInfo: (DocumentInfo?, Int, Int) -> SelectedContent
    
    /// A view builder that constructs the content to display when there are no documents.
    public var emptyDocumentContent: () -> EmptyContent
    
    // MARK: - State
    
    @State private var items: [CarouselModel] = []
    @State private var cardIndex: CGFloat = 0
    @State private var hasInitialized: Bool = false
    @State private var lastReportedFocusId: String? = nil
    @State private var isReordering: Bool = false
    
    // MARK: - Initializer
    
    /// Creates a new `DocumentCarousel` instance.
    ///
    /// - Parameters:
    ///   - documentModel: The data model containing document information.
    ///   - initialDocumentId: An optional ID to scroll to initially.
    ///   - allowReordering: If `true`, enables long-press to reorder cards. Defaults to `true`.
    ///   - onDocumentClicked: Action to perform when a card is tapped.
    ///   - onDocumentFocused: Action to perform when a card becomes the center focus.
    ///   - onDocumentReordered: Action to perform when the order changes.
    ///   - selectedDocumentInfo: Builder for the info view below the focused card.
    ///   - emptyDocumentContent: Builder for the empty state view.
    public init(
        documentModel: DocumentModel,
        initialDocumentId: String? = nil,
        allowReordering: Bool = true,
        onDocumentClicked: @escaping (DocumentInfo) -> Void = { _ in },
        onDocumentFocused: @escaping (DocumentInfo) -> Void = { _ in },
        onDocumentReordered: @escaping (DocumentInfo, Int, Int) -> Void = { _, _, _ in },
        @ViewBuilder selectedDocumentInfo: @escaping (DocumentInfo?, Int, Int) -> SelectedContent,
        @ViewBuilder emptyDocumentContent: @escaping () -> EmptyContent = { EmptyView() }
    ) {
        self.documentModel = documentModel
        self.initialDocumentId = initialDocumentId
        self.allowReordering = allowReordering
        self.onDocumentClicked = onDocumentClicked
        self.onDocumentFocused = onDocumentFocused
        self.onDocumentReordered = onDocumentReordered
        self.selectedDocumentInfo = selectedDocumentInfo
        self.emptyDocumentContent = emptyDocumentContent
    }
    
    // MARK: - Body
    
    public var body: some View {
        if items.isEmpty && documentModel.documentInfos.isEmpty {
            emptyStateView
        } else {
            VStack(spacing: -10) {
                CardCarousel(
                    items: $items,
                    cardIndex: $cardIndex,
                    isReordering: $isReordering,
                    allowReordering: allowReordering,
                    onCarouselItemClick: { item in
                        if let info = documentModel.documentInfos.first(where: { $0.document.identifier == item.id }) {
                            onDocumentClicked(info)
                        }
                    },
                    onReorder: { oldIndex, newIndex in
                        guard newIndex >= 0 && newIndex < items.count else { return }
                        let movedItem = items[newIndex]
                        
                        if let realDoc = documentModel.documentInfos.first(where: { $0.document.identifier == movedItem.id }) {
                            onDocumentReordered(realDoc, oldIndex, newIndex)
                        }
                    }
                )
                
                infoOverlayView
            }
            .onAppear {
                syncItemsFromModel()
                updateInitialIndex()
                DispatchQueue.main.async {
                    reportFocusChange()
                }
            }
            .onChange(of: documentModel.documentInfos) { _, _ in
                syncItemsFromModel()
                
                if !items.isEmpty {
                    let maxIndex = CGFloat(items.count - 1)
                    if cardIndex > maxIndex {
                        cardIndex = maxIndex
                    }
                }
                reportFocusChange()
            }
            .onChange(of: cardIndex) { _, _ in
                reportFocusChange()
            }
        }
    }
    
    // MARK: - Subviews
    
    private var emptyStateView: some View {
        GeometryReader { geometry in
            let width = geometry.size.width
            let cardWidth = width * 0.85
            let cardHeight = cardWidth / 1.586
            
            ZStack {
                RoundedRectangle(cornerRadius: 24)
                    .strokeBorder(
                        Color.secondary.opacity(0.3),
                        style: StrokeStyle(lineWidth: 4, dash: [10])
                    )
                    .frame(width: cardWidth, height: cardHeight)
                
                emptyDocumentContent()
            }
            .frame(width: width, height: geometry.size.height)
        }
        .aspectRatio(1.5, contentMode: .fit)
    }
    
    private var infoOverlayView: some View {
        ZStack {
            let totalCount = items.count
            
            ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                let dist = CGFloat(index) - cardIndex
                let absDist = abs(dist)
                
                if absDist < 0.5 {
                    let opacity = max(0, 1.0 - (absDist * 2.0))
                    let xOffset = dist * 30
                    
                    if isReordering {
                        selectedDocumentInfo(nil, index, totalCount)
                            .opacity(opacity)
                            .offset(x: xOffset)
                            .allowsHitTesting(false)
                    } else {
                        if let realDoc = documentModel.documentInfos.first(where: { $0.document.identifier == item.id }) {
                            selectedDocumentInfo(realDoc, index, totalCount)
                                .opacity(opacity)
                                .offset(x: xOffset)
                                .allowsHitTesting(false)
                        }
                    }
                }
            }
        }
        .zIndex(10)
    }
    
    // MARK: - Helpers
    
    private func syncItemsFromModel() {
        self.items = documentModel.documentInfos.map { info in
            CarouselModel(
                id: info.document.identifier,
                name: info.document.displayName ?? "",
                image: info.cardArt
            )
        }
    }
    
    private func updateInitialIndex() {
        guard !items.isEmpty else { return }
        
        if let targetId = initialDocumentId,
           let index = items.firstIndex(where: { $0.id == targetId }) {
            cardIndex = CGFloat(index)
        } else {
            if !hasInitialized {
                cardIndex = 0
            }
        }
        hasInitialized = true
    }
    
    private func reportFocusChange() {
        guard !items.isEmpty else { return }
        if isReordering { return }
        
        let index = Int(round(cardIndex))
        guard index >= 0 && index < items.count else { return }
        
        let focusedItem = items[index]
        
        if focusedItem.id != lastReportedFocusId {
            if let realDoc = documentModel.documentInfos.first(where: { $0.document.identifier == focusedItem.id }) {
                lastReportedFocusId = focusedItem.id
                onDocumentFocused(realDoc)
            }
        }
    }
}

// MARK: - Internal Implementation

private struct CardCarousel: View {
    @Binding var items: [CarouselModel]
    @Binding var cardIndex: CGFloat
    @Binding var isReordering: Bool
    
    let allowReordering: Bool
    let onCarouselItemClick: (CarouselModel) -> Void
    let onReorder: (Int, Int) -> Void
    
    // Scroll State
    @State private var dragStartIndex: CGFloat? = nil
    @State private var lastHapticIndex: Int = 0
    
    // Reorder State
    @State private var reorderVisualOffset: CGFloat = 0
    @State private var originalReorderIndex: Int? = nil
    @State private var baseDragTranslation: CGFloat = 0
    
    var body: some View {
        GeometryReader { geometry in
            let screenWidthPx = geometry.size.width
            let cardWidthPx = screenWidthPx * 0.85
            let maxCardHeightPx = cardWidthPx / 1.586
            
            let horizontalPeekPx = cardWidthPx * 0.16
            let verticalOffsetPx = maxCardHeightPx * 0.02
            
            let maxIndex = CGFloat(max(0, items.count - 1))
            let currentWhole = Int(round(cardIndex))
            let fractionalPart = cardIndex - CGFloat(Int(floor(cardIndex)))
            
            ZStack {
                ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                    let i = CGFloat(index)
                    let offset = i - cardIndex
                    let absOffset = abs(offset)
                    
                    let visibleFactor = min(1.0, max(0.0, 1.0 - max(0.0, absOffset - 1.0)))
                    
                    if visibleFactor > 0 || (isReordering && index == originalReorderIndex) {
                        let isCurrentCard = index == Int(round(cardIndex))
                        let isNextCard = index == Int(floor(cardIndex)) + 1
                        
                        let interpolation = min(1.0, absOffset)
                        let scaleBase = 1.0 - 0.08 * min(1.5, absOffset)
                        let targetRatio = 1.586 + (0.3 * interpolation)
                        let targetHeight = cardWidthPx / targetRatio
                        let translationY = verticalOffsetPx * interpolation
                        
                        let clampedOffset = min(1.0, max(-1.0, offset))
                        let baseTranslationX = horizontalPeekPx * clampedOffset
                        
                        let motionFactor = 2.0 * min(fractionalPart, 1.0 - fractionalPart)
                        let maxSlideDistance = (screenWidthPx - cardWidthPx) / 2.0 - 8.0
                        
                        let motionExtra: CGFloat = {
                            if isCurrentCard || isNextCard {
                                return (maxSlideDistance * 2.0 - horizontalPeekPx) * motionFactor * (offset > 0 ? 1 : -1)
                            }
                            return 0.0
                        }()
                        
                        let translationX = baseTranslationX + motionExtra
                        
                        // Reorder Overrides
                        let isBeingDragged = isReordering && index == Int(round(cardIndex))
                        let finalTranslationX = isBeingDragged ? reorderVisualOffset : translationX
                        
                        let finalScale = isBeingDragged ? 1.05 : scaleBase
                        let finalShadowRadius = isBeingDragged ? 24.0 : (isCurrentCard ? 16.0 : 5.0)
                        let finalZIndex: Double = {
                            if isBeingDragged { return 100.0 }
                            if isCurrentCard { return 2.0 }
                            if isNextCard { return 1.0 }
                            return -Double(absOffset)
                        }()
                        
                        CarouselItem(item: item, overlayAlpha: calculateOverlay(isCurrentCard: isCurrentCard, isNextCard: isNextCard, motionFactor: motionFactor, fraction: fractionalPart))
                            .frame(width: cardWidthPx, height: targetHeight)
                            .scaleEffect(finalScale)
                            .offset(x: finalTranslationX, y: translationY)
                            .zIndex(finalZIndex)
                            .shadow(
                                color: .black.opacity(isCurrentCard ? 0.45 : 0.15),
                                radius: finalShadowRadius,
                                x: 0, y: isCurrentCard ? 10 : 3
                            )
                            .onTapGesture {
                                if !isReordering && absOffset < 0.5 {
                                    onCarouselItemClick(item)
                                }
                            }
                            .simultaneousGesture(
                                LongPressGesture(minimumDuration: 0.5)
                                    .sequenced(before: DragGesture(minimumDistance: 0, coordinateSpace: .local))
                                    .onChanged { value in
                                        switch value {
                                        case .second(true, let drag):
                                            // Check allowReordering here to prevent starting the mode
                                            if allowReordering && !isReordering {
                                                if isCurrentCard {
                                                    startReorderMode(at: index)
                                                }
                                            }
                                            
                                            if let dragValue = drag, isReordering {
                                                handleReorderDrag(value: dragValue, slotWidth: horizontalPeekPx)
                                            }
                                        default:
                                            break
                                        }
                                    }
                                    .onEnded { value in
                                        switch value {
                                        case .second(true, _):
                                            if isReordering {
                                                endReorderMode()
                                            }
                                        default:
                                            break
                                        }
                                    }
                            )
                    }
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
            .contentShape(Rectangle())
            .gesture(
                DragGesture()
                    .onChanged { value in
                        if value.startLocation.x < 20 { return }
                        if isReordering { return }
                        handleScrollDrag(value: value, width: screenWidthPx, maxIndex: maxIndex)
                    }
                    .onEnded { value in
                        if value.startLocation.x < 20 { return }
                        if isReordering { return }
                        handleScrollEnd(value: value, maxIndex: maxIndex)
                    }
            )
        }
        .aspectRatio(1.5, contentMode: .fit)
    }
    
    // MARK: - Logic Helpers
    
    private func calculateOverlay(isCurrentCard: Bool, isNextCard: Bool, motionFactor: CGFloat, fraction: CGFloat) -> Double {
        if isReordering { return 0.0 }
        if isCurrentCard { return 0.35 * motionFactor }
        if isNextCard {
            return fraction >= 0.5 ? 0.0 : 0.35 * motionFactor
        }
        return 0.0
    }
    
    // MARK: Reorder Logic
    
    private func startReorderMode(at index: Int) {
        let generator = UIImpactFeedbackGenerator(style: .heavy)
        generator.impactOccurred()
        
        withAnimation(.interactiveSpring(response: 0.3, dampingFraction: 0.7)) {
            isReordering = true
            originalReorderIndex = index
            reorderVisualOffset = 0
            baseDragTranslation = 0
        }
    }
    
    private func handleReorderDrag(value: DragGesture.Value, slotWidth: CGFloat) {
        let totalDrag = value.translation.width
        let relativeDrag = totalDrag - baseDragTranslation
        
        let currentIndex = Int(round(cardIndex))
        
        reorderVisualOffset = relativeDrag
        
        let swapThreshold = slotWidth * 0.85
        
        if relativeDrag > swapThreshold {
            let targetIndex = currentIndex + 1
            if targetIndex < items.count {
                performSwap(from: currentIndex, to: targetIndex)
                baseDragTranslation = totalDrag
            }
        } else if relativeDrag < -swapThreshold {
            let targetIndex = currentIndex - 1
            if targetIndex >= 0 {
                performSwap(from: currentIndex, to: targetIndex)
                baseDragTranslation = totalDrag
            }
        }
    }
    
    private func performSwap(from currentIndex: Int, to targetIndex: Int) {
        let item = items.remove(at: currentIndex)
        items.insert(item, at: targetIndex)
        
        let generator = UIImpactFeedbackGenerator(style: .medium)
        generator.impactOccurred()
        
        withAnimation(.interactiveSpring(response: 0.3, dampingFraction: 0.7)) {
            cardIndex = CGFloat(targetIndex)
            reorderVisualOffset = 0
        }
    }
    
    private func endReorderMode() {
        withAnimation(.spring(response: 0.4, dampingFraction: 0.7)) {
            reorderVisualOffset = 0
            isReordering = false
        }
        
        let finalIndex = Int(round(cardIndex))
        
        if let original = originalReorderIndex, original != finalIndex {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                onReorder(original, finalIndex)
            }
        }
        
        originalReorderIndex = nil
        baseDragTranslation = 0
        let generator = UIImpactFeedbackGenerator(style: .medium)
        generator.impactOccurred()
    }
    
    // MARK: Scroll Logic
    
    private func handleScrollDrag(value: DragGesture.Value, width: CGFloat, maxIndex: CGFloat) {
        if dragStartIndex == nil {
            dragStartIndex = cardIndex
            lastHapticIndex = Int(round(cardIndex))
        }
        
        let totalDragPx = value.translation.width
        let dragSensitivity = width * 0.8
        let dragOffset = totalDragPx / dragSensitivity
        
        if let start = dragStartIndex {
            let minBound = max(0.0, start - 1.0)
            let maxBound = min(maxIndex, start + 1.0)
            let rawNewValue = start - dragOffset
            let newIndex = min(maxBound, max(minBound, rawNewValue))
            
            cardIndex = newIndex
            
            let currentSnapIndex = Int(round(newIndex))
            if currentSnapIndex != lastHapticIndex {
                let generator = UIImpactFeedbackGenerator(style: .medium)
                generator.impactOccurred()
                lastHapticIndex = currentSnapIndex
            }
        }
    }
    
    private func handleScrollEnd(value: DragGesture.Value, maxIndex: CGFloat) {
        let currentIndex = cardIndex
        let nearestIndex = round(currentIndex)
        let fraction = currentIndex - nearestIndex
        
        let isSwipeLeft = value.predictedEndLocation.x < value.location.x - 50
        let isSwipeRight = value.predictedEndLocation.x > value.location.x + 50
        
        let rawTarget: Int
        if isSwipeLeft {
            rawTarget = min(Int(maxIndex), Int(nearestIndex) + 1)
        } else if isSwipeRight {
            rawTarget = max(0, Int(nearestIndex) - 1)
        } else if fraction > 0.35 {
            rawTarget = min(Int(maxIndex), Int(nearestIndex) + 1)
        } else if fraction < -0.35 {
            rawTarget = max(0, Int(nearestIndex) - 1)
        } else {
            rawTarget = Int(nearestIndex).clamped(to: 0...Int(maxIndex))
        }
        
        let startIdx = dragStartIndex != nil ? Int(dragStartIndex!) : Int(nearestIndex)
        let finalTarget = rawTarget.clamped(
            to: max(0, startIdx - 1)...min(Int(maxIndex), startIdx + 1)
        )
        
        dragStartIndex = nil
        
        if finalTarget != lastHapticIndex {
            let generator = UIImpactFeedbackGenerator(style: .medium)
            generator.impactOccurred()
            lastHapticIndex = finalTarget
        }
        
        withAnimation(.spring(response: 0.5, dampingFraction: 0.7, blendDuration: 0)) {
            cardIndex = CGFloat(finalTarget)
        }
    }
}

// MARK: - Carousel Item View

private struct CarouselItem: View {
    let item: CarouselModel
    let overlayAlpha: Double
    
    var body: some View {
        ZStack {
            Image(uiImage: item.image)
                .resizable()
                .aspectRatio(contentMode: .fill)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .clipped()
            
            if overlayAlpha > 0 {
                Color.white.opacity(overlayAlpha)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 24))
        .drawingGroup()
    }
}

// MARK: - Utilities

private extension Comparable {
    func clamped(to limits: ClosedRange<Self>) -> Self {
        return min(max(self, limits.lowerBound), limits.upperBound)
    }
}

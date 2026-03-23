import SwiftUI

/// A bottom sheet that intelligently adjusts its height to its content and manages scroll state.
///
/// `SmartSheet` calculates the intrinsic height of its content and sets the sheet's detent
/// to match exactly. It also provides a "Terms of Service" style interaction pattern,
/// tracking whether the user has scrolled to the bottom of the content.
///
/// ## Features
/// - **Dynamic Sizing**: Shrinks to fit short content, expands for long content.
/// - **Scroll Tracking**: Detects when the bottom of the content is visible.
/// - **Smart Navigation**: Provides a "Page Down" capability via the footer closure.
///
/// ## Usage
/// ```swift
/// SmartSheet(maxHeight: 500) {
///     Text("Header")
/// } content: {
///     Text("Long legal text...")
/// } footer: { isAtBottom, scrollDown in
///     if isAtBottom {
///         Button("Accept") { }
///     } else {
///         Button("More") { scrollDown() }
///     }
/// }
/// ```
public struct SmartSheet<Header: View, Content: View, Footer: View>: View {
    
    /// The maximum height in points the sheet is allowed to grow to.
    ///
    /// If the content is taller than this value, the sheet stops growing and becomes scrollable.
    /// Pass `.infinity` to allow the sheet to grow to the maximum available screen height.
    public var maxHeight: CGFloat
    
    private let header: () -> Header
    private let content: () -> Content
    private let footer: (Bool, @escaping () -> Void) -> Footer
    
    // MARK: - Internal State
    
    /// The calculated height for the presentation detent.
    /// We store this in State (rather than a computed property) to prevent layout cycles
    /// when the height flickers during navigation transitions.
    @State private var presentationHeight: CGFloat = 100
    
    /// The height of the fixed header view.
    @State private var headerHeight: CGFloat = 0
    
    /// The height of the scrollable content view.
    @State private var contentHeight: CGFloat = 0
    
    /// The height of the fixed footer view.
    @State private var footerHeight: CGFloat = 0
    
    /// The height of the top safe area (e.g. Navigation Bar) imposed by the parent.
    @State private var topSafeAreaHeight: CGFloat = 0
    
    // MARK: - Scroll State
    
    /// The current vertical scroll offset.
    @State private var currentScrollY: CGFloat = 0
    
    /// The height of the visible scroll window.
    @State private var scrollContainerHeight: CGFloat = 0
    
    /// The total height of the scrollable content area.
    @State private var scrollContentHeight: CGFloat = 0
    
    /// Programmatic scroll control.
    @State private var scrollPosition = ScrollPosition()

    /// Creates a smart sizing sheet.
    ///
    /// - Parameters:
    ///   - maxHeight: The limit for the sheet's height. Defaults to 500.
    ///                Pass `.infinity` to let the sheet fill the screen if needed.
    ///   - header: A fixed view pinned to the top of the sheet.
    ///   - content: The scrollable content.
    ///   - footer: A fixed view pinned to the bottom. The closure receives:
    ///     - `isAtBottom`: A boolean indicating if the end of the content is visible.
    ///     - `scrollDown`: A function to scroll the view down by one page (90% of visible height).
    public init(
        maxHeight: CGFloat = 500,
        @ViewBuilder header: @escaping () -> Header,
        @ViewBuilder content: @escaping () -> Content,
        @ViewBuilder footer: @escaping (Bool, @escaping () -> Void) -> Footer
    ) {
        self.maxHeight = maxHeight
        self.header = header
        self.content = content
        self.footer = footer
    }
    
    /// Determines if the user has viewed all the content.
    private var isAtBottom: Bool {
        // 1. Static Fit Optimization
        // If we have a finite maxHeight and the content is smaller than it,
        // we know immediately that everything is visible without checking scroll geometry.
        if maxHeight < .infinity && presentationHeight > 0 && presentationHeight <= maxHeight {
            return true
        }
        
        // 2. Runtime Scroll Check
        // If the scroll view hasn't loaded (height is 0), safely assume false.
        if scrollContainerHeight == 0 { return false }
        
        // 3. Geometry Check
        // Is the current scroll position + the window height >= the total content height?
        // We subtract 1.0 to handle potential floating point rounding errors.
        return (currentScrollY + scrollContainerHeight) >= (scrollContentHeight - 1.0)
    }
    
    public var body: some View {
        VStack(spacing: 0) {
            // 1. Measure Header
            header()
                .measureSize { headerHeight = $0.height }
            
            // 2. Measure Content
            ScrollView {
                content()
                    .measureSize { contentHeight = $0.height }
            }
            // Prevent collapse to 0, which triggers layout warning
            .frame(minHeight: 1)
            .scrollBounceBehavior(.basedOnSize)
            .scrollPosition($scrollPosition)
            // Monitor scroll geometry to update state
            .onScrollGeometryChange(for: SmartSheetScrollMetrics.self) { geo in
                SmartSheetScrollMetrics(
                    offsetY: geo.contentOffset.y,
                    containerHeight: geo.containerSize.height,
                    contentHeight: geo.contentSize.height
                )
            } action: { _, newValue in
                currentScrollY = newValue.offsetY
                scrollContainerHeight = newValue.containerHeight
                scrollContentHeight = newValue.contentHeight
            }
            
            // 3. Measure Footer
            footer(isAtBottom) {
                // "Page Down" Logic
                let pageHeight = scrollContainerHeight
                // Target 90% of a page to keep some context
                let targetY = currentScrollY + (pageHeight * 0.9)
                let maxScroll = scrollContentHeight - scrollContainerHeight
                let finalY = min(targetY, maxScroll)
                
                withAnimation {
                    scrollPosition = .init(point: CGPoint(x: 0, y: finalY))
                }
            }
            .measureSize { footerHeight = $0.height }
        }
        // 4. Measure Safe Area (Navigation Bar)
        // We attach this to the background of the root VStack to see what the
        // parent container (NavigationStack) is imposing on us.
        .background(
            GeometryReader { proxy in
                Color.clear
                    .onAppear { topSafeAreaHeight = proxy.safeAreaInsets.top }
                    .onChange(of: proxy.safeAreaInsets) { topSafeAreaHeight = $0.top }
            }
        )
        // 5. Calculate Final Height
        // We do this in .onChange rather than a computed var to filter out "0" flickers
        .onChange(of: headerHeight + contentHeight + footerHeight + topSafeAreaHeight) {
             let newTotal = headerHeight + contentHeight + footerHeight + topSafeAreaHeight
             
             // FILTER: Ignore 0 or invalid heights to prevent the "fallback loop"
             guard newTotal > 10 else { return }

             let constrained = maxHeight == .infinity ? newTotal : min(newTotal, maxHeight)
             
             // DEBOUNCE: Only update if the change is significant (> 0.5pt)
             // This prevents infinite loops where the height shifts by 0.00000001
             if abs(constrained - presentationHeight) > 0.5 {
                 // Dispatch to next runloop to ensure we aren't updating State during View render
                 DispatchQueue.main.async {
                     presentationHeight = constrained
                 }
             }
        }
        // Apply the calculated height as a fixed detent
        .presentationDetents([.height(presentationHeight)])
        .presentationDragIndicator(.visible)
    }
}

// MARK: - Internal Helpers

/// Internal struct to transfer scroll data efficiently.
private struct SmartSheetScrollMetrics: Equatable {
    var offsetY: CGFloat
    var containerHeight: CGFloat
    var contentHeight: CGFloat
}

/// Private extension to read view size.
private extension View {
    func measureSize(onChange: @escaping (CGSize) -> Void) -> some View {
        background(
            GeometryReader { proxy in
                Color.clear
                    .preference(key: SmartSheetSizeKey.self, value: proxy.size)
            }
        )
        .onPreferenceChange(SmartSheetSizeKey.self) { size in
            DispatchQueue.main.async {
                onChange(size)
            }
        }
    }
}

/// A PreferenceKey to bubble up the content size.
private struct SmartSheetSizeKey: PreferenceKey {
    static let defaultValue: CGSize = .zero
    static func reduce(value: inout CGSize, nextValue: () -> CGSize) {
        let next = nextValue()
        value = CGSize(
            width: max(value.width, next.width),
            height: max(value.height, next.height)
        )
    }
}

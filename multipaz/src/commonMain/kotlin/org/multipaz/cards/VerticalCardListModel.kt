package org.multipaz.cards

/**
 * State model and interaction coordinator for a vertical card list.
 *
 * A **Vertical Card List** (often referred to as a "wallet card stack" or "pass deck" UI) displays
 * a collection of cards, credentials, or passes in a vertically overlapping, cascading presentation
 * reminiscent of a physical wallet or cardholder.
 *
 * ### Core Display Modes
 * The vertical card list operates in two distinct visual and operational modes:
 *
 * #### 1. List Mode (Unfocused / Overview)
 * - **Cascading Cards**: All cards in the list are rendered in an overlapping cascade, peeking out
 *   from beneath each other by a configurable percentage ([CardListLayoutParameters.unfocusedVisiblePercent],
 *   typically 25%–50%).
 * - **Scrollable Viewport**: When the total height of the cascaded cards exceeds the available viewport,
 *   the entire card list scrolls smoothly with standard physics.
 * - **Dynamic Top Content**: Optional header content (such as search fields, wallet balance, or action
 *   buttons) can be placed above the card cascade and scrolls naturally with the cards.
 * - **Interactive Reordering**: Users can long-press and drag cards to reorder them in real-time,
 *   with smooth displacement animations for affected cards.
 * - **Empty State Placeholder**: If the list contains no cards, an optional dashed placeholder card
 *   can be rendered to guide the user.
 *
 * #### 2. Focused Mode (Card Detail View)
 * - **Promoted Card**: When a card is selected, it transitions to the top of the viewport ([CardListLayoutParameters.paddingTop]),
 *   scaled up slightly (1.025) with prominent elevation shadow and high `zIndex`.
 * - **Inline Detail Content**: Card-specific information, attributes, verification status, or action
 *   buttons are rendered directly underneath the focused card in the remaining viewport space.
 * - **Top Content Collapse**: Any top content header smoothly fades and collapses out of view.
 * - **Bottom Card Stack ([CardListLayoutParameters.showStackWhileFocused])**: The remaining non-focused
 *   cards collapse into a compact, 3D overlapping deck pinned to the bottom of the viewport. This preserves
 *   spatial continuity, visually communicating that the other cards remain accessible. Tapping the bottom
 *   stack or the focused card initiates a return transition back to List Mode.
 *
 * ### Architecture & Responsibilities
 * - **[CardListLayoutCalculator]**: Pure mathematical engine that calculates card dimensions,
 *   header offsets, and the visual state (`y`, `scale`, `elevation`, `alpha`, `zIndex`) for every
 *   card in both list and focused modes.
 * - **[VerticalCardListModel]**: UI-agnostic coordinator managing card display order, drag-and-drop
 *   state machines, cooldown timers, and tap action resolution ([handleTap]).
 * - **Platform UI Layer** (Compose / SwiftUI): Binds the visual tree, renders card graphics/badges,
 *   captures gesture events, and executes smooth interpolations between computed layout states.
 *
 * ### UI Toolkit Implementation & Navigation Guidelines
 * When integrating this model into navigation frameworks (e.g. Jetpack Navigation, SwiftUI
 * NavigationStack), follow these design patterns to ensure smooth, glitch-free transitions:
 *
 * #### 1. Forward Navigation & Card Focusing
 * - When a card is tapped in list mode, [handleTap] returns [CardTapAction.Focus]. The UI should
 *   navigate to a focused destination carrying the document identifier and `animateListTransitions = true`.
 * - On initial render of the focused screen, card visual properties should start at their list
 *   positions (or the position of `lastFocusedCardIdentifier`) and animate to the focused layout
 *   over ~400ms.
 * - When navigating to a focused card directly from an external screen (e.g., Document Viewer),
 *   set `animateListTransitions = false`. The UI MUST initialize its visual properties directly to
 *   the focused state on Frame 0 synchronously to prevent unintended transition animations.
 *
 * #### 2. Back Navigation & In-Place Unfocusing
 * - **In-Place Reverse Animation**: Do not pop immediately and attempt to animate on the previous
 *   screen. Instead, trigger the unfocus animation in-place on the active (top) screen over ~400ms,
 *   and pop the screen silently (using `ExitTransition.None` / `popWithoutAnimation()`) once the
 *   animation finishes. This reveals the previous list screen already resting at its exact layout.
 * - **Unified Back Handling**: Route all back triggers (hardware back button, back gestures/swipes,
 *   toolbar back arrow, tapping the focused card, and tapping the background bottom card stack)
 *   to the same unified back handler.
 * - **Context-Aware Dismissal**: If the previous destination on the navigation stack is an instance
 *   of the card list, perform the 400ms in-place unfocus animation. If returning to an external
 *   destination (e.g., Document Viewer or Home), navigate back immediately.
 *
 * #### 3. Concurrency & State Scoping (Compose vs. SwiftUI)
 * - In **SwiftUI**, views naturally evaluate only when top-of-stack.
 * - In **Compose**, `NavHost` may compose both outgoing and incoming destinations during navigation
 *   transitions. To avoid race conditions where an underlying list destination overwrites or clears
 *   the active screen's focus state, keep per-screen focus properties locally scoped (e.g. via
 *   `remember`) and trigger unfocusing via distinct instance triggers rather than mutating shared
 *   model fields directly during composition passes.
 *
 * #### 4. Synchronous Header Measurement (Preventing Frame-0 Jumps)
 * - Any dynamic top content (such as search bars or header titles) MUST be measured synchronously
 *   during the initial layout pass (e.g., using `SubcomposeLayout` in Compose) before card Y positions
 *   are resolved. Deferring measurement to asynchronous effects causes a 1-frame layout shift (a visible
 *   "slide-down" jump) on cold app launches.
 */
class VerticalCardListModel(
    displayOrderIdentifiers: List<String> = emptyList(),
    draggedCardIdentifier: String? = null,
    dragCurrentY: Double = 0.0,
    dragJustEnded: Boolean = false,
    lastFocusedCardIdentifier: String? = null,
    animateListTransitions: Boolean = false,
    showTopContent: Boolean = true,
    showPlaceholderWhenEmpty: Boolean = true,
    topContentHeight: Double = 0.0,
    scrollOffset: Double = 0.0
) {
    /**
     * Secondary no-argument constructor for platform convenience.
     */
    constructor() : this(
        displayOrderIdentifiers = emptyList(),
        draggedCardIdentifier = null,
        dragCurrentY = 0.0,
        dragJustEnded = false,
        lastFocusedCardIdentifier = null,
        animateListTransitions = false,
        showTopContent = true,
        showPlaceholderWhenEmpty = true,
        topContentHeight = 0.0,
        scrollOffset = 0.0
    )

    /**
     * The current display order of the cards, tracked by identifier.
     */
    var displayOrderIdentifiers: List<String> = displayOrderIdentifiers

    /**
     * The identifier of the card currently being dragged, if any.
     */
    var draggedCardIdentifier: String? = draggedCardIdentifier

    /**
     * The current vertical position of the dragged card in coordinate space.
     */
    var dragCurrentY: Double = dragCurrentY

    /**
     * Whether a drag gesture just ended (used to ignore accidental tap events).
     */
    var dragJustEnded: Boolean = dragJustEnded

    /**
     * The identifier of the last focused card, used to preserve animations across navigation.
     */
    var lastFocusedCardIdentifier: String? = lastFocusedCardIdentifier

    /**
     * Whether to animate spatial transitions when entering this screen.
     */
    var animateListTransitions: Boolean = animateListTransitions

    /**
     * Whether the top content should be shown when no card is focused.
     */
    var showTopContent: Boolean = showTopContent

    /**
     * Whether to show a dashed placeholder card when the card list is empty.
     */
    var showPlaceholderWhenEmpty: Boolean = showPlaceholderWhenEmpty

    /**
     * The measured height of the top content in dp/points.
     */
    var topContentHeight: Double = topContentHeight

    /**
     * Current vertical scroll offset in dp/points.
     */
    var scrollOffset: Double = scrollOffset

    /**
     * Synchronizes [displayOrderIdentifiers] with the provided list of card identifiers.
     *
     * If a drag operation is currently active, synchronization is deferred to avoid disrupting gestures.
     * Existing ordering is preserved for existing items; new items are appended, and removed items are deleted.
     *
     * @param incomingIdentifiers the latest list of card identifiers from the data source.
     */
    fun syncCards(incomingIdentifiers: List<String>) {
        if (draggedCardIdentifier != null) {
            return
        }
        if (displayOrderIdentifiers.isEmpty()) {
            displayOrderIdentifiers = incomingIdentifiers
            return
        }
        if (displayOrderIdentifiers != incomingIdentifiers) {
            val incomingSet = incomingIdentifiers.toSet()
            val preserved = displayOrderIdentifiers.filter { incomingSet.contains(it) }
            val preservedSet = preserved.toSet()
            val newItems = incomingIdentifiers.filter { !preservedSet.contains(it) }
            displayOrderIdentifiers = preserved + newItems
        }
    }

    /**
     * Resolves the list of cards in current display order.
     *
     * @param availableCards the pool of available [CardLayoutItem] instances.
     * @return ordered list of [CardLayoutItem] matching [displayOrderIdentifiers].
     */
    fun resolveDisplayOrder(availableCards: List<CardLayoutItem>): List<CardLayoutItem> {
        val cardMap = availableCards.associateBy { it.identifier }
        val orderedList = mutableListOf<CardLayoutItem>()
        val seen = mutableSetOf<String>()

        for (id in displayOrderIdentifiers) {
            cardMap[id]?.let {
                orderedList.add(it)
                seen.add(id)
            }
        }
        for (card in availableCards) {
            if (!seen.contains(card.identifier)) {
                orderedList.add(card)
            }
        }
        return orderedList
    }

    /**
     * Initiates a drag gesture for the card with [cardIdentifier].
     *
     * @param cardIdentifier identifier of the card to drag.
     * @param layout computed [CardListLayout].
     * @param cards list of cards in current display order.
     * @param params layout configuration parameters.
     * @return true if the drag was successfully started, false otherwise.
     */
    fun startDrag(
        cardIdentifier: String,
        layout: CardListLayout,
        cards: List<CardLayoutItem>,
        params: CardListLayoutParameters
    ): Boolean {
        val index = displayOrderIdentifiers.indexOf(cardIdentifier)
        if (index == -1) return false

        draggedCardIdentifier = cardIdentifier
        dragJustEnded = false

        var yAcc = layout.listTopOffset
        for (i in 0 until index) {
            val h = layout.getDimensions(cards[i].identifier).height
            yAcc += CardListLayoutCalculator.computeCardStep(h, params.unfocusedVisiblePercent, params.spacing)
        }
        dragCurrentY = yAcc
        return true
    }

    /**
     * Updates the active drag gesture to an absolute Y position [dragY].
     *
     * @param dragY absolute vertical position in coordinate space.
     * @param layout computed [CardListLayout].
     * @param cards list of cards in current display order.
     * @param params layout configuration parameters.
     * @return [DragUpdateResult] indicating whether the card swapped index positions.
     */
    fun updateDragPosition(
        dragY: Double,
        layout: CardListLayout,
        cards: List<CardLayoutItem>,
        params: CardListLayoutParameters
    ): DragUpdateResult {
        val draggedId = draggedCardIdentifier ?: return DragUpdateResult(reordered = false)
        val currentIndex = displayOrderIdentifiers.indexOf(draggedId)
        if (currentIndex == -1) return DragUpdateResult(reordered = false)

        dragCurrentY = dragY
        val newIndex = CardListLayoutCalculator.findTargetIndexForDragY(dragCurrentY, layout, cards, params)

        if (newIndex != currentIndex) {
            val updated = displayOrderIdentifiers.toMutableList()
            val item = updated.removeAt(currentIndex)
            updated.add(newIndex, item)
            displayOrderIdentifiers = updated
            return DragUpdateResult(reordered = true, fromIndex = currentIndex, toIndex = newIndex)
        }
        return DragUpdateResult(reordered = false)
    }

    /**
     * Updates the active drag gesture by [deltaY].
     *
     * @param deltaY vertical translation delta.
     * @param layout computed [CardListLayout].
     * @param cards list of cards in current display order.
     * @param params layout configuration parameters.
     * @return [DragUpdateResult] indicating whether the card swapped index positions.
     */
    fun updateDrag(
        deltaY: Double,
        layout: CardListLayout,
        cards: List<CardLayoutItem>,
        params: CardListLayoutParameters
    ): DragUpdateResult {
        return updateDragPosition(dragCurrentY + deltaY, layout, cards, params)
    }

    /**
     * Completes the active drag gesture.
     *
     * Sets [dragJustEnded] to true and clears [draggedCardIdentifier].
     *
     * @return [DragEndResult] containing the card identifier and its final index, or null if no drag was active.
     */
    fun endDrag(): DragEndResult? {
        val draggedId = draggedCardIdentifier ?: return null
        val finalIndex = displayOrderIdentifiers.indexOf(draggedId)
        draggedCardIdentifier = null
        dragJustEnded = true
        return if (finalIndex != -1) DragEndResult(draggedId, finalIndex) else null
    }

    /**
     * Cancels the active drag gesture.
     */
    fun cancelDrag() {
        draggedCardIdentifier = null
        dragJustEnded = true
    }

    /**
     * Clears the [dragJustEnded] flag after the cooldown period expires.
     */
    fun clearDragJustEnded() {
        dragJustEnded = false
    }

    /**
     * Determines the appropriate tap action for a card given the current state.
     *
     * @param cardIdentifier identifier of the tapped card.
     * @param isAnyFocused whether a card is currently focused.
     * @param focusedCardIdentifier identifier of the currently focused card.
     * @return [CardTapAction] indicating what action should occur.
     */
    fun handleTap(
        cardIdentifier: String,
        isAnyFocused: Boolean,
        focusedCardIdentifier: String?
    ): CardTapAction {
        if (dragJustEnded || draggedCardIdentifier != null) {
            return CardTapAction.Ignore
        }
        return if (isAnyFocused) {
            if (cardIdentifier == focusedCardIdentifier) {
                CardTapAction.FocusedCardTapped(cardIdentifier)
            } else {
                CardTapAction.FocusedStackTapped(cardIdentifier)
            }
        } else {
            CardTapAction.Focus(cardIdentifier)
        }
    }
}

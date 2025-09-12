package org.multipaz.context

/**
 * Abstract base class for holding UI relevant data that may be accessed by a coroutine.
 *
 * If using multipaz-compose, the [rememberUiBoundCoroutineScope] can be used to bind an
 * activity context to a coroutine.
 */
abstract class UiContext
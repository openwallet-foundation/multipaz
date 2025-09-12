package org.multipaz.context

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * Extracts Activity from the current activity-scoped context.
 */
fun Context.getActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.getActivity()
    else -> null
}

/**
 * Android application context.
 *
 * Must be explicitly initialized as early as possible using [initializeApplication]
 */
val applicationContext: Context
    get() = _applicationContext!!

private var _applicationContext: Context? = null

/**
 * Initializes the application.
 *
 * @param applicationContext the application [Context].
 */
fun initializeApplication(
    applicationContext: Context,
) {
    if (_applicationContext == null) {
        check(applicationContext.getActivity() == null) { "Not an application context" }
        _applicationContext = applicationContext
    }
}
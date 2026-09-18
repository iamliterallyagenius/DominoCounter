package com.example.dominocounter.util

import android.view.View
import android.view.ViewGroup
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding

/**
 * Edge-to-edge means the app draws behind the status/nav bars and the keyboard; nothing
 * pads for them automatically. These add the relevant inset as extra padding, on top of
 * whatever padding the view already has in XML, captured once so repeated dispatches
 * (rotation, keyboard open/close) don't keep stacking padding on top of itself.
 */
private fun View.consumeInsets(
    apply: (view: View, systemBars: androidx.core.graphics.Insets, ime: androidx.core.graphics.Insets) -> Unit
) {
    val startLeft = paddingLeft
    val startTop = paddingTop
    val startRight = paddingRight
    val startBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(
        this,
        OnApplyWindowInsetsListener { v, insets ->
            v.setPadding(startLeft, startTop, startRight, startBottom)
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            apply(v, bars, ime)
            insets
        }
    )
}

/** Pushes the view down below the status bar, e.g. a toolbar in an edge-to-edge window. */
fun View.padForStatusBar() = consumeInsets { v, bars, _ ->
    v.updatePadding(top = v.paddingTop + bars.top)
}

/** Keeps the view clear of the navigation bar / gesture inset at the bottom of the screen. */
fun View.padForNavigationBar() = consumeInsets { v, bars, _ ->
    v.updatePadding(bottom = v.paddingBottom + bars.bottom)
}

/**
 * Keeps bottom-anchored content (buttons, bottom sheets) clear of whichever is taller: the
 * keyboard while it's open, or the navigation bar while it's closed.
 */
fun View.padForKeyboardOrNavigationBar() = consumeInsets { v, bars, ime ->
    v.updatePadding(bottom = v.paddingBottom + maxOf(bars.bottom, ime.bottom))
}

/**
 * Same idea as [padForNavigationBar] but for a view positioned with a layout margin rather
 * than padding — e.g. a button floating above the edge of a ConstraintLayout.
 */
fun View.marginForNavigationBar() {
    val start = (layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
    ViewCompat.setOnApplyWindowInsetsListener(
        this,
        OnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = start + bars.bottom }
            insets
        }
    )
}

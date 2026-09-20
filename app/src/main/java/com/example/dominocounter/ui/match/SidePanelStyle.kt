package com.example.dominocounter.ui.match

import android.content.Context
import android.util.TypedValue
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.example.dominocounter.databinding.ViewSidePanelBinding
import com.google.android.material.R

/**
 * Ties a side panel to its side's identity colour: a tonal fill (stronger while leading), a
 * full-width accent bar, and the score digits themselves in that colour — shared by the live
 * scoreboard and the read-only history detail view so both look like the same design system.
 */
fun ViewSidePanelBinding.applySideColor(context: Context, sideIndex: Int, isLeading: Boolean) {
    val color = ContextCompat.getColor(context, RoundLogAdapter.sideColor(sideIndex))
    val density = context.resources.displayMetrics.density

    val typedValue = TypedValue()
    context.theme.resolveAttribute(R.attr.colorSurface, typedValue, true)
    val surfaceColor = typedValue.data
    val ratio = if (isLeading) 0.33f else 0.20f
    val backgroundColor = ColorUtils.blendARGB(surfaceColor, color, ratio)

    sideStripe.setBackgroundColor(color)
    sideScore.setTextColor(color)
    sideCard.setCardBackgroundColor(backgroundColor)
    sideCard.strokeColor = color
    sideCard.strokeWidth = if (isLeading) (LEADING_STROKE_DP * density).toInt() else 0
}

private const val LEADING_STROKE_DP = 2.5f


package com.example.dominocounter.ui.setup

import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.example.dominocounter.R
import com.example.dominocounter.util.NameNormalizer
import com.google.android.material.card.MaterialCardView

/**
 * The name suggestions under a seat's field, drawn as a card in the screen's own layout instead
 * of a system popup window. A popup is placed by the platform (and on some devices ends up
 * running to the screen's edge); this one is placed by measuring the field's outline, so its
 * left edge and width are exactly the field's.
 *
 * A single card is shared by all seats and moved to whichever seat has focus. Everything that
 * can be tuned lives in res/values/dimens.xml (`suggestion_*`).
 *
 * @param overlay full-screen container the card floats in; it must sit above the scrolling form
 */
class SuggestionOverlay(
    private val overlay: FrameLayout,
    private val card: MaterialCardView,
    private val scroll: ScrollView,
    private val list: LinearLayout
) {
    private class Candidate(val name: String, val key: String)

    private val resources = overlay.resources
    private val maxRows = resources.getInteger(R.integer.suggestion_max_rows)
    private val rowHeight = resources.getDimensionPixelSize(R.dimen.suggestion_row_height)
    private val gap = resources.getDimensionPixelSize(R.dimen.suggestion_gap)
    private val widthAdjust = resources.getDimensionPixelSize(R.dimen.suggestion_popup_width_adjust)
    private val offsetAdjust = resources.getDimensionPixelSize(R.dimen.suggestion_popup_offset_adjust)

    /** Every known name, most recently played first, with its search key. */
    private var candidates = emptyList<Candidate>()

    private var input: EditText? = null
    private var box: View? = null
    private var onPick: (String) -> Unit = {}

    fun setNames(names: List<String>) {
        candidates = names.map { Candidate(it, NameNormalizer.normalize(it)) }
        refresh()
    }

    /** Points the card at [input]; [box] is the outlined field it must line up with. */
    fun attach(input: EditText, box: View, onPick: (String) -> Unit) {
        this.input = input
        this.box = box
        this.onPick = onPick
        refresh()
    }

    /** No-op unless [input] is the seat currently shown, so a stale focus-loss can't hide another seat's card. */
    fun detach(input: EditText) {
        if (this.input !== input) return
        this.input = null
        this.box = null
        card.isVisible = false
    }

    /** Re-filters for the field's current text and repositions. */
    fun refresh() {
        val input = input ?: return
        val query = NameNormalizer.normalize(input.text.toString())
        val matches = rank(query)

        // Offering back exactly what is already typed is just noise.
        if (matches.isEmpty() || (matches.size == 1 && matches.single().key == query)) {
            card.isVisible = false
            return
        }

        list.removeAllViews()
        val inflater = LayoutInflater.from(overlay.context)
        matches.forEach { candidate ->
            val row = inflater.inflate(R.layout.item_suggestion, list, false) as TextView
            row.text = candidate.name
            row.setOnClickListener { onPick(candidate.name) }
            list.addView(row)
        }

        // A whole number of rows, so the cap never cuts a name in half.
        scroll.layoutParams.height = minOf(matches.size, maxRows) * rowHeight
        scroll.scrollTo(0, 0)
        card.isVisible = true
        reposition()
    }

    /** Lines the card up under (or, if the keyboard leaves no room, above) the field. Cheap; call on scroll. */
    fun reposition() {
        val input = input ?: return
        val box = box ?: return
        if (!card.isVisible || box.width <= 0) return

        val overlayPos = IntArray(2).also(overlay::getLocationInWindow)
        val boxPos = IntArray(2).also(box::getLocationInWindow)
        val inputPos = IntArray(2).also(input::getLocationInWindow)

        card.layoutParams.width = box.width + widthAdjust
        card.translationX = (boxPos[0] - overlayPos[0] + offsetAdjust).toFloat()

        val fieldTop = inputPos[1] - overlayPos[1]
        val fieldBottom = fieldTop + input.height
        val cardHeight = scroll.layoutParams.height + card.strokeWidth * 2

        val insets = ViewCompat.getRootWindowInsets(overlay)
            ?.getInsets(WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.systemBars())
        val usableBottom = overlay.height - (insets?.bottom ?: 0)

        val below = fieldBottom + gap
        card.translationY =
            if (below + cardHeight <= usableBottom) below.toFloat() else (fieldTop - gap - cardHeight).toFloat()
        card.requestLayout()
    }

    /**
     * Names that start with what was typed, or that have a word starting with it ("Ahmed" for
     * "Bilal Ahmed") — never just containing it, so "a" offers Alice and Alex, not Bilal or Bob.
     * Names starting with the query come first; the given order is kept within each group.
     */
    private fun rank(query: String): List<Candidate> =
        if (query.isEmpty()) {
            candidates
        } else {
            candidates
                .mapNotNull { candidate ->
                    when {
                        candidate.key.startsWith(query) -> 0 to candidate
                        candidate.key.split(' ').any { it.startsWith(query) } -> 1 to candidate
                        else -> null
                    }
                }
                .sortedBy { it.first }
                .map { it.second }
        }
}

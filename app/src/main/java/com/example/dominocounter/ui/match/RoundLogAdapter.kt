package com.example.dominocounter.ui.match

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.dominocounter.R
import com.example.dominocounter.databinding.ItemRoundLogBinding
import com.example.dominocounter.domain.model.LoggedRound
import com.example.dominocounter.domain.model.ScoreSource

/**
 * The audit log. Every row is tappable while the match is live (R5); rows on a finished
 * match are inert.
 */
class RoundLogAdapter(
    private val onClick: (LoggedRound) -> Unit
) : ListAdapter<LoggedRound, RoundLogAdapter.ViewHolder>(Diff) {

    /** Set once per match, before the first submitList. */
    var sideLabels: List<String> = emptyList()

    class ViewHolder(val binding: ItemRoundLogBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemRoundLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val context = holder.binding.root.context
        val round = item.round

        holder.binding.rowSide.text = sideLabels.getOrElse(round.side) { "Side ${round.side + 1}" }
        holder.binding.rowPoints.text = context.getString(R.string.score_log_row, round.points)

        holder.binding.rowStripe.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, sideColor(round.side))
        )

        val meta = buildList {
            add(context.getString(sourceLabel(round.source)))
            add(context.getString(R.string.score_log_total, item.runningTotal))
            if (round.wasEdited) add(context.getString(R.string.score_edited))
        }
        holder.binding.rowMeta.text = meta.joinToString(" · ")

        // A round recorded after the match was decided contributes nothing, so it is shown
        // dimmed rather than silently hidden.
        holder.binding.root.alpha = if (item.isIgnored) 0.4f else 1f
        holder.binding.root.setOnClickListener { onClick(item) }
    }

    private fun sourceLabel(source: ScoreSource) = when (source) {
        ScoreSource.MANUAL -> R.string.source_manual
        ScoreSource.SCAN -> R.string.source_scan
        ScoreSource.FOUL -> R.string.source_foul
    }

    private object Diff : DiffUtil.ItemCallback<LoggedRound>() {
        override fun areItemsTheSame(old: LoggedRound, new: LoggedRound) =
            old.round.id == new.round.id

        // LoggedRound is a data class, so this covers points, running total and flags.
        override fun areContentsTheSame(old: LoggedRound, new: LoggedRound) = old == new
    }

    companion object {
        fun sideColor(side: Int) = when (side) {
            0 -> R.color.side_0
            1 -> R.color.side_1
            else -> R.color.side_2
        }
    }
}

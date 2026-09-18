package com.example.dominocounter.ui.history

import android.graphics.Typeface
import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.dominocounter.R
import com.example.dominocounter.databinding.ItemMatchHistoryBinding
import com.example.dominocounter.domain.model.MatchFormat
import com.example.dominocounter.domain.model.MatchState
import com.example.dominocounter.domain.model.MatchStatus
import java.util.Date

class MatchHistoryAdapter(
    private val onClick: (MatchState) -> Unit
) : ListAdapter<MatchState, MatchHistoryAdapter.ViewHolder>(Diff) {

    class ViewHolder(val binding: ItemMatchHistoryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemMatchHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val context = holder.binding.root.context

        holder.binding.historyFormat.text = context.getString(formatLabelRes(item.format))
        holder.binding.historyDate.text = DateFormat.getMediumDateFormat(context).format(Date(item.startedAt))

        holder.binding.historySidesContainer.removeAllViews()
        val wonOutright = item.status == MatchStatus.COMPLETED
        item.sides.forEach { side ->
            val isWinner = side.isLeading && wonOutright

            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, 2, 0, 2)

            val label = TextView(context)
            label.text = context.getString(R.string.history_row_score, side.label, side.score)
            if (isWinner) label.setTypeface(label.typeface, Typeface.BOLD)
            row.addView(label)

            if (isWinner) {
                val trophy = ImageView(context)
                trophy.setImageResource(R.drawable.ic_trophy)
                val size = (16 * context.resources.displayMetrics.density).toInt()
                trophy.layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = size / 2 }
                row.addView(trophy)
            }

            holder.binding.historySidesContainer.addView(row)
        }

        bindStatus(holder.binding, item.status)

        holder.binding.root.setOnClickListener { onClick(item) }
    }

    /** An unfinished match is outlined in brass like the menu's Resume card; abandoned ones are flagged. */
    private fun bindStatus(binding: ItemMatchHistoryBinding, status: MatchStatus) {
        val context = binding.root.context
        val inProgress = status == MatchStatus.IN_PROGRESS

        // Both branches set the stroke: rows are recycled, so a stale outline would carry over.
        binding.root.strokeColor = ContextCompat.getColor(context, R.color.brass)
        binding.root.strokeWidth = if (inProgress) (1 * context.resources.displayMetrics.density).toInt() else 0

        val (label, colorRes) = when (status) {
            MatchStatus.IN_PROGRESS -> R.string.history_in_progress to R.color.status_in_progress
            MatchStatus.ABANDONED -> R.string.history_abandoned to R.color.status_abandoned
            MatchStatus.COMPLETED -> null to null
        }
        binding.historyStatus.visibility = if (label == null) View.GONE else View.VISIBLE
        if (label != null && colorRes != null) {
            binding.historyStatus.setText(label)
            binding.historyStatus.setTextColor(ContextCompat.getColor(context, colorRes))
        }
    }

    private fun formatLabelRes(format: MatchFormat) = when (format) {
        MatchFormat.ONE_V_ONE -> R.string.format_one_v_one
        MatchFormat.TWO_V_TWO -> R.string.format_two_v_two
        MatchFormat.FREE_FOR_ALL -> R.string.format_free_for_all
    }

    private object Diff : DiffUtil.ItemCallback<MatchState>() {
        override fun areItemsTheSame(old: MatchState, new: MatchState) = old.matchId == new.matchId
        override fun areContentsTheSame(old: MatchState, new: MatchState) = old == new
    }
}

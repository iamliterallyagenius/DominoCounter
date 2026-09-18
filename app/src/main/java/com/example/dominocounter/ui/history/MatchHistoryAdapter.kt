package com.example.dominocounter.ui.history

import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
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
        item.sides.forEach { side ->
            val row = TextView(context)
            row.text = context.getString(R.string.history_row_score, side.label, side.score)
            row.setPadding(0, 2, 0, 2)
            if (side.isLeading && item.status == MatchStatus.COMPLETED) {
                row.setTypeface(row.typeface, android.graphics.Typeface.BOLD)
            }
            holder.binding.historySidesContainer.addView(row)
        }

        holder.binding.historyAbandoned.visibility =
            if (item.status == MatchStatus.ABANDONED) android.view.View.VISIBLE else android.view.View.GONE

        holder.binding.root.setOnClickListener { onClick(item) }
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

package com.example.dominocounter.ui.stats

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.dominocounter.R
import com.example.dominocounter.data.db.relation.PlayerPairing
import com.example.dominocounter.databinding.ItemStatRowBinding

/** Renders leaderboard rows, teammate synergy rows and head-to-head rows alike. */
class PlayerPairingAdapter(
    private val onClick: (PlayerPairing) -> Unit
) : ListAdapter<PlayerPairing, PlayerPairingAdapter.ViewHolder>(Diff) {

    class ViewHolder(val binding: ItemStatRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemStatRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val context = holder.binding.root.context
        holder.binding.statName.text = item.displayName
        holder.binding.statSummary.text = context.getString(
            R.string.stats_row_summary, item.won, item.played, (item.winRate * 100).toInt()
        )
        holder.binding.root.setOnClickListener { onClick(item) }
    }

    private object Diff : DiffUtil.ItemCallback<PlayerPairing>() {
        override fun areItemsTheSame(old: PlayerPairing, new: PlayerPairing) = old.playerId == new.playerId
        override fun areContentsTheSame(old: PlayerPairing, new: PlayerPairing) = old == new
    }
}

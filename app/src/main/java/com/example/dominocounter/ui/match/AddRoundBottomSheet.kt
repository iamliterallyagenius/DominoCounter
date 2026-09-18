package com.example.dominocounter.ui.match

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.hilt.navigation.fragment.hiltNavGraphViewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.dominocounter.R
import com.example.dominocounter.databinding.SheetAddRoundBinding
import com.example.dominocounter.domain.model.Rules
import com.example.dominocounter.util.padForKeyboardOrNavigationBar
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class AddRoundBottomSheet : BottomSheetDialogFragment() {

    private var binding: SheetAddRoundBinding? = null
    private val args: AddRoundBottomSheetArgs by navArgs()

    /** Same instance the scoreboard holds — no result passing needed. */
    private val viewModel: MatchViewModel by hiltNavGraphViewModels(R.id.match_graph)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = SheetAddRoundBinding.inflate(inflater, container, false)
        .also { binding = it }
        .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val views = binding ?: return
        views.root.padForKeyboardOrNavigationBar()

        val sideLabel = viewModel.state.value?.sides?.getOrNull(args.sideIndex)?.label.orEmpty()
        views.sheetTitle.text = getString(R.string.add_round_title, sideLabel)

        views.confirmButton.setOnClickListener { submit() }
        views.scanButton.setOnClickListener {
            findNavController().navigate(AddRoundBottomSheetDirections.toScanner(args.sideIndex))
        }
    }

    private fun submit() {
        val views = binding ?: return
        val points = views.pointsInput.text?.toString()?.trim()?.toIntOrNull()

        views.pointsLayout.error = when {
            points == null -> getString(R.string.add_round_points)
            points <= 0 || points > Rules.MAX_MANUAL_POINTS -> getString(R.string.add_round_points)
            else -> null
        }
        if (views.pointsLayout.error != null) return

        viewModel.addManual(args.sideIndex, checkNotNull(points))
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}

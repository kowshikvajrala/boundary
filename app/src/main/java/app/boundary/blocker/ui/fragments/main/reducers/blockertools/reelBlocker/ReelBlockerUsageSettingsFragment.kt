package app.boundary.blocker.ui.fragments.main.reducers.blockertools.reelBlocker

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import app.boundary.blocker.data.models.AppUsageConfig
import app.boundary.blocker.data.models.ReelUsageConfig
import app.boundary.blocker.databinding.FragmentReelBlockerUsageSettingsBinding
import app.boundary.blocker.ui.fragments.main.reducers.blockertools.shared.BaseUsageSettingsFragment

class ReelBlockerUsageSettingsFragment : BaseUsageSettingsFragment() {

    companion object {
        const val FRAGMENT_ID = "ReelBlockerUsageSettingsBottomSheet"
    }

    private val viewModel: ReelBlockerViewModel by activityViewModels()

    override fun inflateView(inflater: LayoutInflater, container: ViewGroup?): View =
        FragmentReelBlockerUsageSettingsBinding.inflate(inflater, container, false).root

    override fun loadUsageConfig(): AppUsageConfig {
        val c = viewModel.getReelUsageConfig()
        return AppUsageConfig(c.isDailyUniform, c.uniformLimit).also { c.dailyLimits.copyInto(it.dailyLimits) }
    }

    override fun saveUsageConfig(config: AppUsageConfig) {
        val reelConfig = ReelUsageConfig(config.isDailyUniform, config.uniformLimit)
        config.dailyLimits.copyInto(reelConfig.dailyLimits)
        viewModel.saveReelUsageConfig(reelConfig)
    }
}

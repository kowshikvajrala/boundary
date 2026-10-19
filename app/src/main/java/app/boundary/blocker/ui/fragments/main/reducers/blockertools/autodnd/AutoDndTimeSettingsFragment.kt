package app.boundary.blocker.ui.fragments.main.reducers.blockertools.autodnd

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import app.boundary.blocker.data.models.AppTimeConfig
import app.boundary.blocker.databinding.FragmentAutodndTimeSettingsBinding
import app.boundary.blocker.ui.fragments.main.reducers.blockertools.shared.BaseTimeSettingsFragment

class AutoDndTimeSettingsFragment : BaseTimeSettingsFragment() {

    companion object {
        const val FRAGMENT_ID = "autodnd_time_settings"
    }

    private val viewModel: AutoDndViewModel by activityViewModels()

    override val daysOfWeek = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    override fun inflateView(inflater: LayoutInflater, container: ViewGroup?): View =
        FragmentAutodndTimeSettingsBinding.inflate(inflater, container, false).root

    override fun getTimeConfig(): AppTimeConfig = viewModel.currentTimeConfig

    override fun saveTimeConfig(config: AppTimeConfig) {
        viewModel.currentTimeConfig = config
    }
}

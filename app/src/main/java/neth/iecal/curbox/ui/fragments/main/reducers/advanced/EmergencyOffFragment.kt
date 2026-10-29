package neth.iecal.curbox.ui.fragments.main.reducers.advanced

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.databinding.FragmentEmergencyOffBinding
import neth.iecal.curbox.utils.DataStoreManager
import java.text.DateFormat
import java.util.Date

/**
 * The one way out that needs neither waiting nor being away: switches every restriction off at
 * once, then cannot be used again for the number of days the owner chose.
 */
class EmergencyOffFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "emergency_off"
    }

    private var _binding: FragmentEmergencyOffBinding? = null
    private val binding get() = _binding!!
    private lateinit var dataStore: DataStoreManager
    private var latest: Settings? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentEmergencyOffBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dataStore = DataStoreManager(requireContext())
        binding.btnEmergencyOff.setOnClickListener { confirmEmergencyOff() }
        binding.btnSaveWait.setOnClickListener { saveWaitDays() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var filledWait = false
                dataStore.settings.collect { settings ->
                    latest = settings
                    if (!filledWait) {
                        binding.emergencyWaitInput.setText(settings.emergencyOffWaitDays.toString())
                        filledWait = true
                    }
                    render(settings)
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun render(settings: Settings) {
        val availableAt = dataStore.emergencyOffAvailableAt(settings)
        binding.btnEmergencyOff.isEnabled = availableAt == 0L
        binding.emergencyStatus.text = if (availableAt == 0L) {
            getString(R.string.emergency_off_available)
        } else {
            getString(R.string.emergency_off_waiting, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(availableAt)))
        }
    }

    private fun confirmEmergencyOff() {
        val days = latest?.emergencyOffWaitDays ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.emergency_off_confirm_title)
            .setMessage(resources.getQuantityString(R.plurals.emergency_off_confirm_message, days, days))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.emergency_off_button) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val message = if (dataStore.useEmergencyOff()) R.string.emergency_off_done else R.string.emergency_off_not_yet
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun saveWaitDays() {
        val days = binding.emergencyWaitInput.text?.toString()?.toIntOrNull()
        if (days == null || days < DataStoreManager.EMERGENCY_OFF_MIN_DAYS || days > DataStoreManager.EMERGENCY_OFF_MAX_DAYS) {
            binding.emergencyWaitLayout.error = getString(
                R.string.emergency_off_wait_range,
                DataStoreManager.EMERGENCY_OFF_MIN_DAYS,
                DataStoreManager.EMERGENCY_OFF_MAX_DAYS
            )
            return
        }
        binding.emergencyWaitLayout.error = null
        // Raising applies at once; lowering waits through the settings change delay.
        viewLifecycleOwner.lifecycleScope.launch { dataStore.updateEmergencyOffWaitDays(days) }
        Toast.makeText(requireContext(), R.string.emergency_off_saved, Toast.LENGTH_SHORT).show()
    }
}

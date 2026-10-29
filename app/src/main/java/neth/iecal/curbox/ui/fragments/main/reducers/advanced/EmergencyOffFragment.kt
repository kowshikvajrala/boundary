package neth.iecal.curbox.ui.fragments.main.reducers.advanced

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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
        private const val DEFAULT_WAIT_DAYS = 30
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

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                dataStore.settings.collect { settings ->
                    latest = settings
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

    /** Asks how many days until it can be used again, then turns everything off. */
    private fun confirmEmergencyOff() {
        val context = requireContext()
        val inputLayout = TextInputLayout(context).apply {
            hint = getString(R.string.emergency_off_wait_input)
            suffixText = getString(R.string.emergency_off_days)
            setPadding(48, 16, 48, 0)
        }
        val input = TextInputEditText(inputLayout.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(DEFAULT_WAIT_DAYS.toString())
            setSelectAllOnFocus(true)
        }
        inputLayout.addView(input)
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.emergency_off_confirm_title)
            .setMessage(R.string.emergency_off_ask_days)
            .setView(inputLayout)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.emergency_off_button, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val days = input.text?.toString()?.toIntOrNull()
            if (days == null || days < DataStoreManager.EMERGENCY_OFF_MIN_DAYS || days > DataStoreManager.EMERGENCY_OFF_MAX_DAYS) {
                inputLayout.error = getString(
                    R.string.emergency_off_wait_range,
                    DataStoreManager.EMERGENCY_OFF_MIN_DAYS,
                    DataStoreManager.EMERGENCY_OFF_MAX_DAYS
                )
                return@setOnClickListener
            }
            dialog.dismiss()
            viewLifecycleOwner.lifecycleScope.launch {
                val message = if (dataStore.useEmergencyOff(days)) R.string.emergency_off_done else R.string.emergency_off_not_yet
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }
}

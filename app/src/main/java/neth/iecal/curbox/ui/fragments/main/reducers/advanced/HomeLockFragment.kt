package neth.iecal.curbox.ui.fragments.main.reducers.advanced

import android.Manifest
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.CancellationSignal
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.HomeLocation
import neth.iecal.curbox.databinding.FragmentHomeLockBinding
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.HomeLocationChecker
import neth.iecal.curbox.utils.HomeLock
import neth.iecal.curbox.utils.PermissionUtils
import neth.iecal.curbox.utils.SettingsChangeDelayUtils

/**
 * Sets the owner's home and turns on Away mode after checking the owner is away from it.
 * While Away mode is on, locks after unlock can be loosened (see [HomeLock]).
 */
class HomeLockFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "home_lock"
        private const val MAX_HOME_ACCURACY_METERS = 100f
    }

    private var _binding: FragmentHomeLockBinding? = null
    private val binding get() = _binding!!
    private lateinit var dataStore: DataStoreManager
    private var locationRequest: CancellationSignal? = null
    private var actionAfterPermission: (() -> Unit)? = null

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            runCatching {
                startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.parse("package:" + requireContext().packageName))
                )
            }
        }
        lifecycleScope.launch { showAwayMode(dataStore.settings.first().isAwayModeActive) }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val action = actionAfterPermission
        actionAfterPermission = null
        if (grants.values.any { it }) action?.invoke() else showAwayStatus(getString(R.string.home_lock_no_permission))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeLockBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dataStore = DataStoreManager(requireContext())

        binding.btnSetHome.setOnClickListener { withLocation(::setHomeHere) }
        binding.btnCheckAway.setOnClickListener { confirmAwayMode() }
        binding.btnEndAwayMode.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch { dataStore.endAwayMode() }
        }
        binding.btnBackgroundLocation.setOnClickListener { requestBackgroundLocation() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                dataStore.settings.collect { settings ->
                    showHome(settings.homeLocation)
                    showAwayMode(settings.isAwayModeActive)
                }
            }
        }
    }

    override fun onDestroyView() {
        locationRequest?.cancel()
        locationRequest = null
        _binding = null
        super.onDestroyView()
    }

    private fun showHome(home: HomeLocation) {
        binding.homeStatus.text = if (home.isSet) {
            getString(R.string.home_lock_home_set, home.radiusMeters)
        } else {
            getString(R.string.home_lock_no_home)
        }
        binding.btnSetHome.setText(if (home.isSet) R.string.home_lock_move_home else R.string.home_lock_set_home)
    }

    /** Asks for permission if needed, then reads one location and hands it to [onFound]. */
    private fun withLocation(onFound: (Location) -> Unit) {
        val context = requireContext()
        if (!PermissionUtils.hasLocationPermission(context)) {
            actionAfterPermission = { withLocation(onFound) }
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
            return
        }
        locationRequest?.cancel()
        val request = CancellationSignal()
        locationRequest = request
        setBusy(true)
        HomeLocationChecker.currentLocation(context, request) { result ->
            if (_binding == null || request.isCanceled) return@currentLocation
            setBusy(false)
            when (result) {
                is HomeLocationChecker.Result.Found -> onFound(result.location)
                HomeLocationChecker.Result.NoPermission -> showAwayStatus(getString(R.string.home_lock_no_permission))
                HomeLocationChecker.Result.LocationOff -> showAwayStatus(getString(R.string.home_lock_location_off))
                HomeLocationChecker.Result.NotFound -> showAwayStatus(getString(R.string.home_lock_not_found))
                HomeLocationChecker.Result.Fake -> showAwayStatus(getString(R.string.home_lock_fake))
            }
        }
    }

    private fun setHomeHere(location: Location) {
        if (!location.hasAccuracy() || location.accuracy > MAX_HOME_ACCURACY_METERS) {
            showAwayStatus(getString(R.string.home_lock_not_precise, location.accuracy.toInt()))
            return
        }
        val home = HomeLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            isSet = true
        )
        viewLifecycleOwner.lifecycleScope.launch {
            val saved = dataStore.updateHomeLocation(home)
            val message = if (saved) R.string.home_lock_home_saved else R.string.home_lock_move_refused
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
    }

    private fun checkAway(location: Location) {
        viewLifecycleOwner.lifecycleScope.launch {
            val home = dataStore.settings.first().homeLocation
            when {
                !home.isSet -> showAwayStatus(getString(R.string.home_lock_needs_home))
                HomeLocationChecker.isAway(home, location) -> {
                    dataStore.startAwayMode()
                    showAwayStatus(getString(R.string.home_lock_all_off))
                    if (!PermissionUtils.hasBackgroundLocationPermission(requireContext())) requestBackgroundLocation()
                }
                else -> showAwayStatus(getString(R.string.home_lock_at_home))
            }
        }
    }

    /** Explains Away mode, then checks the location; Away mode starts only if the owner is away. */
    private fun confirmAwayMode() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.home_lock_turn_off_all_title)
            .setMessage(R.string.home_lock_turn_off_all_message)
            .setPositiveButton(R.string.home_lock_turn_off_all_confirm) { _, _ -> withLocation(::checkAway) }
            .setNegativeButton(R.string.home_lock_turn_off_all_cancel, null)
            .show()
    }

    private fun showAwayMode(isOn: Boolean) {
        val binding = _binding ?: return
        binding.cardAwayMode.isVisible = isOn
        val needsBackground = isOn && !PermissionUtils.hasBackgroundLocationPermission(requireContext())
        binding.awayModeBackgroundNote.isVisible = needsBackground
        binding.btnBackgroundLocation.isVisible = needsBackground
    }

    /**
     * Android only grants "Allow all the time" from its own settings page, after normal location
     * access. Asking opens that page on Android 11 and newer.
     */
    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!PermissionUtils.hasLocationPermission(requireContext())) {
            actionAfterPermission = { requestBackgroundLocation() }
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
            return
        }
        backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    private fun showAwayStatus(message: String) {
        val binding = _binding ?: return
        binding.awayStatus.text = message
        binding.awayStatus.isVisible = true
    }

    private fun setBusy(busy: Boolean) {
        binding.locationProgress.isVisible = busy
        binding.btnSetHome.isEnabled = !busy
        binding.btnCheckAway.isEnabled = !busy
    }
}

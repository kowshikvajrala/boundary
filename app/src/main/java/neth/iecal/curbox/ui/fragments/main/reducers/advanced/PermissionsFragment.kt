package neth.iecal.curbox.ui.fragments.main.reducers.advanced

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import neth.iecal.curbox.databinding.FragmentPermissionsBinding

/**
 * One place that shows every permission Boundary uses, whether it is on, and a button that
 * opens the right screen to fix it. Rechecked every time the screen comes back.
 */
class PermissionsFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "permissions_status"
    }

    private var _binding: FragmentPermissionsBinding? = null
    private val binding get() = _binding!!

    private var pendingEntry: PermissionRows.Entry? = null
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val entry = pendingEntry
        pendingEntry = null
        // Android stops asking after a refusal or two, so send the user to app info instead.
        if (!isGranted && entry != null && !shouldShowRequestPermissionRationale(entry.runtimePermission!!)) {
            PermissionRows.openFix(requireContext(), entry)
        }
        render()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPermissionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun render() {
        val binding = _binding ?: return
        PermissionRows.render(requireContext(), layoutInflater, binding.permissionsContainer, PermissionRows.all) { entry ->
            pendingEntry = entry
            permissionLauncher.launch(entry.runtimePermission)
        }
    }
}

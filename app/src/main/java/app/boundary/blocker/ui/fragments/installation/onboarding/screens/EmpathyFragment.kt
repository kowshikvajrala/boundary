package app.boundary.blocker.ui.fragments.installation.onboarding.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import app.boundary.blocker.databinding.FragmentEmpathyBinding
import app.boundary.blocker.ui.fragments.installation.onboarding.OnboardingFragment
import app.boundary.blocker.utils.LanguageUtils

class EmpathyFragment : Fragment() {

    private var _binding: FragmentEmpathyBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEmpathyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        LanguageUtils.bindLanguageSelector(binding.languageSelector, binding.languageSelector)
        binding.btnAction.setOnClickListener {
            (parentFragment as? OnboardingFragment)?.goToNextPage()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

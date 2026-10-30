package neth.iecal.curbox.ui.fragments.installation.onboarding

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class OnboardingViewModel : ViewModel() {

    private val _estimatedHours = MutableLiveData<Int>(4) // Default guess
    val estimatedHours: LiveData<Int> = _estimatedHours

    private val _targetAppPackage = MutableLiveData<String?>(null)
    val targetAppPackage: LiveData<String?> = _targetAppPackage


    fun setEstimatedHours(hours: Int) {
        _estimatedHours.value = hours
    }

    fun setTargetApp(packageName: String) {
        _targetAppPackage.value = packageName
    }

    fun skipTargetApp() {
        _targetAppPackage.value = null
    }
}

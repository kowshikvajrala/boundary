package neth.iecal.curbox.data.models

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PomodoroStateTest {

    @Test
    fun `four focus intervals alternate with breaks and then finish`() {
        var state = PomodoroState(isActive = true)

        assertEquals(PomodoroPhase.FOCUS, state.phase)
        assertEquals(1, state.currentFocusInterval)

        repeat(3) { completed ->
            state = state.nextPhase()
            assertTrue(state.isActive)
            assertEquals(PomodoroPhase.BREAK, state.phase)
            assertEquals(completed + 1, state.completedFocusIntervals)

            state = state.nextPhase()
            assertEquals(PomodoroPhase.FOCUS, state.phase)
            assertEquals(completed + 2, state.currentFocusInterval)
        }

        state = state.nextPhase()
        assertFalse(state.isActive)
    }

    @Test
    fun `invalid values are kept within safe limits`() {
        val normalized = PomodoroState(
            isActive = true,
            focusMinutes = 0,
            breakMinutes = Int.MAX_VALUE,
            totalFocusIntervals = 1,
            completedFocusIntervals = -3
        ).normalized()

        assertEquals(PomodoroState.MIN_MINUTES, normalized.focusMinutes)
        assertEquals(PomodoroState.MAX_BREAK_MINUTES, normalized.breakMinutes)
        assertEquals(PomodoroState.MIN_FOCUS_INTERVALS, normalized.totalFocusIntervals)
        assertEquals(0, normalized.completedFocusIntervals)
    }

    @Test
    fun `legacy settings json gets an inactive pomodoro state`() {
        val settings = Gson().fromJson("{}", Settings::class.java)

        assertFalse(settings.activePomodoroState.isActive)
        assertEquals(PomodoroPhase.FOCUS, settings.activePomodoroState.phase)
    }
}

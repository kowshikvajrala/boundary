package neth.iecal.curbox.utils

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import neth.iecal.curbox.CrashLogger

class FocusModeSoundPlayer(context: Context) {

    enum class Effect {
        FOCUS_COMPLETE,
        BREAK_STARTED,
        FOCUS_RESUMED,
        POMODORO_COMPLETE
    }

    private val crashLogger = CrashLogger(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val toneLock = Any()
    private var toneGenerator: ToneGenerator? = null
    private var soundJob: Job? = null

    fun play(effect: Effect) {
        soundJob?.cancel()
        soundJob = scope.launch {
            try {
                when (effect) {
                    Effect.FOCUS_COMPLETE -> playTone(ToneGenerator.TONE_PROP_ACK, 350)
                    Effect.BREAK_STARTED -> playTone(ToneGenerator.TONE_PROP_PROMPT, 300)
                    Effect.FOCUS_RESUMED -> playTone(ToneGenerator.TONE_PROP_BEEP2, 250)
                    Effect.POMODORO_COMPLETE -> {
                        playTone(ToneGenerator.TONE_PROP_ACK, 250)
                        delay(150)
                        playTone(ToneGenerator.TONE_PROP_ACK, 400)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to play focus mode sound", t)
                crashLogger.logNonFatalError(Exception(t))
            }
        }
    }

    fun release() {
        soundJob?.cancel()
        scope.cancel()
        synchronized(toneLock) {
            runCatching { toneGenerator?.release() }
            toneGenerator = null
        }
    }

    private fun playTone(tone: Int, durationMs: Int) {
        synchronized(toneLock) {
            val generator = toneGenerator ?: ToneGenerator(
                AudioManager.STREAM_NOTIFICATION,
                VOLUME_PERCENT
            ).also { toneGenerator = it }
            generator.startTone(tone, durationMs)
        }
    }

    companion object {
        private const val TAG = "FocusModeSound"
        private const val VOLUME_PERCENT = 80
    }
}

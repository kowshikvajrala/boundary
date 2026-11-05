package neth.iecal.curbox.ui.activity

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import neth.iecal.curbox.R

/**
 * The break screen after a long stretch of scrolling. It cannot be skipped: the blocker service
 * opens it again whenever another app comes to the front before [EXTRA_ENDS_AT].
 */
class ScrollBreakActivity : AppCompatActivity() {

    companion object {
        /** Wall clock time the break ends. */
        const val EXTRA_ENDS_AT = "ends_at"
        /** How long the user scrolled or watched before the break. */
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_WATCHING = "watching"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var endsAtMs = 0L
    private lateinit var countdown: TextView

    private val tick = object : Runnable {
        override fun run() {
            val leftMs = endsAtMs - System.currentTimeMillis()
            if (leftMs <= 0L) {
                finish()
                return
            }
            val totalSeconds = (leftMs + 999) / 1_000L
            countdown.text = "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scroll_break)
        countdown = findViewById(R.id.scroll_break_countdown)
        findViewById<MaterialButton>(R.id.scroll_break_home).setOnClickListener { goHome() }
        // Back would show the app underneath, so it goes home instead.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
        readIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(intent: Intent) {
        endsAtMs = intent.getLongExtra(EXTRA_ENDS_AT, 0L)
        val minutes = intent.getIntExtra(EXTRA_MINUTES, 0)
        val body = if (intent.getBooleanExtra(EXTRA_WATCHING, false)) {
            R.string.watch_break_screen_body
        } else {
            R.string.scroll_break_screen_body
        }
        findViewById<TextView>(R.id.scroll_break_body).text = getString(body, minutes)
        handler.removeCallbacks(tick)
        tick.run()
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }
}

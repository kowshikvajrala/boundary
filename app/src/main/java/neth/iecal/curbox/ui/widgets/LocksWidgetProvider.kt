package neth.iecal.curbox.ui.widgets

import neth.iecal.curbox.R

/** Groups that are locked after an unlock, with the time until they open again. */
class LocksWidgetProvider : TimersWidgetProvider() {
    override val titleRes = R.string.locks_widget_title
    override val emptyRes = R.string.locks_widget_empty
    override val prefsName = "locks_widget"
}

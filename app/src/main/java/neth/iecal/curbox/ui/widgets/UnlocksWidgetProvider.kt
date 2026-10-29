package neth.iecal.curbox.ui.widgets

import neth.iecal.curbox.R

/** Groups that are unlocked right now, with the time until they block again. */
class UnlocksWidgetProvider : TimersWidgetProvider() {
    override val titleRes = R.string.unlocks_widget_title
    override val emptyRes = R.string.unlocks_widget_empty
    override val prefsName = "unlocks_widget"
}

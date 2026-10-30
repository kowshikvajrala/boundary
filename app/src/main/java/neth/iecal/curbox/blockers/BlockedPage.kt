package neth.iecal.curbox.blockers

import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import neth.iecal.curbox.hardcoded.URL_BAR_ID_LIST
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.utils.AccessibilityHelper

/**
 * Moves a browser tab off a blocked page by typing [REPLACEMENT_PAGE] into its address bar.
 * Pressing Home alone leaves the tab on the page, so reopening the browser shows it again.
 * Walks the node tree, so call it off the accessibility event thread.
 */
object BlockedPage {

    const val REPLACEMENT_PAGE = "about:blank"

    /** True when the browser in front accepted the replacement page. */
    fun replace(service: BaseBlockingService, packageName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val urlBarId = URL_BAR_ID_LIST[packageName]?.displayUrlBarId ?: return false
        return runCatching {
            val root = service.rootInActiveWindow ?: return false
            if (root.packageName?.toString() != packageName) return false
            val urlBar = AccessibilityHelper.findElementById(root, urlBarId) ?: return false
            if (!urlBar.isEditable) return false
            urlBar.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            urlBar.refresh()
            val text = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    REPLACEMENT_PAGE
                )
            }
            urlBar.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text) &&
                urlBar.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }.getOrDefault(false)
    }
}

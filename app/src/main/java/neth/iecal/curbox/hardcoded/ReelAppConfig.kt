package neth.iecal.curbox.hardcoded

import android.view.accessibility.AccessibilityEvent
import neth.iecal.curbox.data.models.ReelAppData

class ReelAppConfig {
    companion object {
        const val INSTAGRAM_PACKAGE = "com.instagram.android"
        const val INSTAGRAM_DM_REEL_MARKER = "\u001Fcurbox:instagram_dm_inbox:\u001F"

        private fun isOnScreenFunction() = """
            fn isOnScreen(node) {
                if node == null or not node.visible { return false }
                return node.right > 0 and node.left < screen.width and
                    node.bottom > 0 and node.top < screen.height
            }
        """.trimIndent()

        private fun instagramScript(packageName: String) = """
            ${isOnScreenFunction()}

            viewer = find(id="$packageName:id/clips_viewer_view_pager")
            controls = find(id="$packageName:id/clips_ufi_component")
            if not isOnScreen(viewer) or not isOnScreen(controls) { return null }

            dmReplyBar = find(id="$packageName:id/reply_bar_container_scroll_view")
            openedFromDmInbox = isOnScreen(dmReplyBar)

            comparator = ""
            caption = find(id="$packageName:id/clips_captions_component")
            author = find(id="$packageName:id/clips_author_username")
            if caption != null { comparator = comparator + caption.subtreeText(32, 20000) }
            if author != null { comparator = comparator + author.subtreeText(32, 20000) }
            if openedFromDmInbox {
                return "$INSTAGRAM_DM_REEL_MARKER" + comparator
            }
            return comparator
        """.trimIndent()

        private fun youtubeScript(packageName: String) = """
            ${isOnScreenFunction()}

            viewer = find(id="$packageName:id/reel_recycler")
            if not isOnScreen(viewer) { return null }

            comparator = find(id="$packageName:id/reel_player_page_content")
            if comparator == null { return "" }
            return comparator.subtreeText(32, 20000)
        """.trimIndent()

        val reelData: Map<String, ReelAppData> = mapOf(
            INSTAGRAM_PACKAGE to ReelAppData(
                scriptSource = instagramScript(INSTAGRAM_PACKAGE)
            ),

            "com.myinsta.android" to ReelAppData(
                scriptSource = instagramScript("com.myinsta.android")
            ),

            "com.instagram.lite" to ReelAppData(
                scriptSource = """
                    ${isOnScreenFunction()}

                    reel = find(path="android.widget.FrameLayout[0]>androidx.recyclerview.widget.RecyclerView[0]>android.view.ViewGroup[0]>android.view.ViewGroup[0]")
                    if not isOnScreen(reel) or reel.h < screen.height * 0.8 { return null }

                    if event.fromIndex < 0 { return "" }
                    return "position:" + str(event.fromIndex)
                """.trimIndent(),
                eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                deduplicateComparators = false,
                initialComparator = "position:0"
            ),

            "com.google.android.youtube" to ReelAppData(
                scriptSource = youtubeScript("com.google.android.youtube"),
                comparisonResultCleanser = ::cleanYouTubeComparator,
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            ),
            "app.revanced.android.youtube" to ReelAppData(
                scriptSource = youtubeScript("app.revanced.android.youtube"),
                comparisonResultCleanser = ::cleanYouTubeComparator,
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            ),
            "app.morphe.android.youtube" to ReelAppData(
                scriptSource = youtubeScript("app.morphe.android.youtube"),
                comparisonResultCleanser = ::cleanYouTubeComparator,
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            ),

            "com.facebook.katana" to ReelAppData(
                scriptSource = """
                    ${isOnScreenFunction()}

                    reel = find(desc="Reels tab details")
                    if not isOnScreen(reel) { return null }

                    comparator = reel.parent()
                    if comparator == null { return "" }
                    return comparator.subtreeText(16, 4000)
                """.trimIndent()
            ),

            "com.snapchat.android" to ReelAppData(
                scriptSource = """
                    ${isOnScreenFunction()}

                    viewer = find(id="com.snapchat.android:id/spotlight_container")
                    if not isOnScreen(viewer) { return null }

                    comparator = find(id="com.snapchat.android:id/opera_viewer")
                    if comparator == null { return "" }
                    return comparator.subtreeText(32, 20000)
                """.trimIndent(),
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            ),

            "com.zhiliaoapp.musically" to ReelAppData(
                scriptSource = """
                    ${isOnScreenFunction()}

                    viewer = find(id="com.zhiliaoapp.musically:id/view_pager_layout_wrapper")
                    video = find(id="com.zhiliaoapp.musically:id/long_press_layout")
                    # TikTok reports the video node as hidden even while its full screen viewer is visible.
                    if not isOnScreen(viewer) or video == null { return null }
                    if viewer.w < screen.width * 0.9 or viewer.h < screen.height * 0.75 {
                        return null
                    }

                    commentEditor = find(class="android.widget.EditText")
                    if isOnScreen(commentEditor) and commentEditor.top > screen.height * 0.6 {
                        return null
                    }

                    comparator = ""
                    author = find(id="com.zhiliaoapp.musically:id/title")
                    if isOnScreen(author) {
                        comparator = comparator + author.subtreeText(2, 1000)
                    }

                    sound = find(id="com.zhiliaoapp.musically:id/p89")
                    if isOnScreen(sound) {
                        comparator = comparator + sound.subtreeText(4, 2000)
                    }
                    return comparator
                """.trimIndent(),
                eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            ),

            "com.twitter.android" to ReelAppData(
                scriptSource = """
                    ${isOnScreenFunction()}

                    fn isFullScreen(node) {
                        return node != null and node.top <= 0 and
                            node.w >= screen.width * 0.95 and node.h >= screen.height * 0.9
                    }

                    # X's full screen video viewer is a stack of Compose views that each cover the
                    # whole screen, with a scrollable pager inside. Walk down only through full
                    # screen views instead of a fixed path, so X adding or removing a layer does
                    # not break it, and feeds stop after a few levels.
                    fn findViewer(node, depth) {
                        if depth > 40 { return null }
                        if node.scrollable { return node }
                        for child in node.children() {
                            if isFullScreen(child) {
                                found = findViewer(child, depth + 1)
                                if found != null { return found }
                            }
                        }
                        return null
                    }

                    top = root()
                    if top == null { return null }
                    viewer = findViewer(top, 0)
                    if not isOnScreen(viewer) or not isFullScreen(viewer) { return null }

                    # Only videos, not photos. X has changed how a video page looks before, so
                    # any one of these signs is enough:
                    # 1. X tags the video screen "VideoTab" (photos are "MediaGallery").
                    isVideo = false
                    tagged = viewer.parent()
                    for i in 0..6 {
                        if tagged == null { break }
                        if tagged.id == "VideoTab" { isVideo = true }
                        tagged = tagged.parent()
                    }

                    page = viewer.child(0)
                    if not isVideo and isFullScreen(page) {
                        # 2. A full width seek bar in the player controls next to the pager.
                        controls = viewer.parent()
                        if controls != null {
                            for bar in controls.findAll(class="android.widget.SeekBar") {
                                if bar.w >= screen.width * 0.9 { isVideo = true }
                            }
                        }
                        # 3. An embedded player view on the page (older X versions).
                        for item in page.children() {
                            if item.class != null and containsIgnoreCase(item.class, "viewinterop") {
                                isVideo = true
                            }
                        }
                    }
                    if not isVideo { return null }

                    # Pages carry no text to tell videos apart, so X is blocked but not counted.
                    return ""
                """.trimIndent(),
                countsReels = false
            )
        )

        private fun cleanYouTubeComparator(value: String): String {
            val compact = value.replace("\n", "")
            if (compact.contains("PostPostPostlike") || compact.length <= 15) return ""
            return compact.replace("Video Progress", "")
                .replace("Tap to watch live", "")
                .replace("Go to channel", "")
                .replace("soundVideo ProgressSearchMoreHomeHomeShortsShortsCreateSubscriptions", "")
                .replace("soundSearchMoreHomeHomeShortsShortsCreateSubscriptions", "")
        }
    }
}

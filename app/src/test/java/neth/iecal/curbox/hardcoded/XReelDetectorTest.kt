package neth.iecal.curbox.hardcoded

import neth.iecal.curbox.blockers.uihider.script.Budget
import neth.iecal.curbox.blockers.uihider.script.Builtins
import neth.iecal.curbox.blockers.uihider.script.Interpreter
import neth.iecal.curbox.blockers.uihider.script.Parser
import neth.iecal.curbox.blockers.uihider.script.RuntimeApi
import neth.iecal.curbox.blockers.uihider.script.ScriptError
import neth.iecal.curbox.blockers.uihider.script.ScriptNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Runs the shipped X detector on trees shaped like uiautomator dumps of X 12.39.0. */
class XReelDetectorTest {

    private class Node(
        val cls: String = "android.view.View",
        val left: Int = 0,
        val top: Int = 0,
        val right: Int = W,
        val bottom: Int = H,
        val id: String? = null,
        val scrollable: Boolean = false,
        val children: MutableList<Node> = mutableListOf()
    ) : ScriptNode {
        var parent: Node? = null

        init { children.forEach { it.parent = this } }

        override fun prop(name: String): Any? = when (name) {
            "id" -> id
            "class" -> cls
            "left", "x" -> left.toDouble()
            "top", "y" -> top.toDouble()
            "right" -> right.toDouble()
            "bottom" -> bottom.toDouble()
            "w", "width" -> (right - left).toDouble()
            "h", "height" -> (bottom - top).toDouble()
            "scrollable" -> scrollable
            "visible" -> true
            "childCount" -> children.size.toDouble()
            else -> null
        }

        override fun call(name: String, args: List<Any?>, named: Map<String, Any?>): Any? = when (name) {
            "children" -> children.toList()
            "child" -> children.getOrNull((args[0] as Double).toInt())
            "parent" -> parent
            "findAll" -> descendants().filter { it.cls == named["class"] }
            else -> throw ScriptError("unknown method '$name'")
        }

        fun descendants(): List<Node> = children.flatMap { listOf(it) + it.descendants() }
    }

    private fun detect(root: Node): Any? {
        val budget = Budget()
        val api = object : RuntimeApi {
            val regexCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()
            override fun provideGlobals(): Map<String, Any?> = mapOf(
                "app" to "com.twitter.android",
                "screen" to mapOf("width" to W.toDouble(), "height" to H.toDouble())
            )

            override fun callFunction(name: String, args: List<Any?>, named: Map<String, Any?>): Any? =
                if (name == "root") root else {
                    val r = Builtins.tryCall(name, args, budget, regexCache)
                    if (r === Builtins.UNKNOWN) throw ScriptError("unknown function '$name'")
                    r
                }
        }
        val source = ReelAppConfig.reelData.getValue("com.twitter.android").scriptSource
        return Interpreter(api, budget).run(Parser.parse(source))
    }

    /** The views from the app window down to X's ComposeView, as in every dump. */
    private fun window(compose: Node) = Node(
        "android.widget.FrameLayout", children = mutableListOf(
            Node("android.widget.LinearLayout", children = mutableListOf(
                Node("android.widget.FrameLayout", children = mutableListOf(
                    Node("android.widget.LinearLayout", id = "com.twitter.android:id/action_bar_root", children = mutableListOf(
                        Node("android.widget.FrameLayout", id = "android:id/content", children = mutableListOf(compose))
                    ))
                ))
            ))
        )
    )

    private fun stack(levels: Int, inner: Node): Node =
        (0 until levels).fold(inner) { child, _ -> Node(children = mutableListOf(child)) }

    private fun videoScreen(tag: String? = "VideoTab", seekBar: Boolean = true, extraLayers: Int = 0): Node {
        val page = Node(children = mutableListOf(
            Node(bottom = 2257),
            Node(children = mutableListOf(Node(top = 1206), Node(top = 1863, bottom = 2238)))
        ))
        val pager = Node(scrollable = true, children = mutableListOf(page))
        val controls = Node(top = 2284, children = if (seekBar) {
            mutableListOf(Node("android.widget.SeekBar", top = 2257, bottom = 2312))
        } else mutableListOf())
        val withControls = Node(children = mutableListOf(stack(extraLayers, pager), controls, Node()))
        val tagged = Node(id = tag, children = mutableListOf(withControls))
        val top = Node(children = mutableListOf(stack(1, tagged), Node(left = 485, top = 94, right = 595, bottom = 204)))
        return window(Node("androidx.compose.ui.platform.ComposeView", children = mutableListOf(stack(3, top))))
    }

    @Test fun blocksVideoViewer() {
        assertEquals("", detect(videoScreen()))
    }

    @Test fun blocksVideoWhenOneSignIsMissing() {
        assertEquals("", detect(videoScreen(tag = null)))
        assertEquals("", detect(videoScreen(seekBar = false)))
    }

    @Test fun blocksVideoWhenXAddsLayers() {
        assertEquals("", detect(videoScreen(extraLayers = 3)))
    }

    @Test fun ignoresVideoWithNoVideoSigns() {
        assertNull(detect(videoScreen(tag = null, seekBar = false)))
    }

    @Test fun ignoresPhotoViewer() {
        val photo = stack(2, Node(children = mutableListOf(Node())))
        val gallery = Node(id = "MediaGallery", children = mutableListOf(photo))
        val top = Node(children = mutableListOf(gallery, Node(left = 485, top = 94, right = 595, bottom = 204)))
        assertNull(detect(window(Node("androidx.compose.ui.platform.ComposeView", children = mutableListOf(stack(3, top))))))
    }

    @Test fun ignoresFeed() {
        val rows = (0 until 6).map { Node(top = it * 400, bottom = it * 400 + 400) }.toMutableList()
        val feed = Node("androidx.recyclerview.widget.RecyclerView", top = 250, scrollable = true, children = rows)
        assertNull(detect(window(Node(children = mutableListOf(feed)))))
    }

    private companion object {
        const val W = 1080
        const val H = 2412
    }
}

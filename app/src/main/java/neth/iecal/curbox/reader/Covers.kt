package neth.iecal.curbox.reader

import android.content.Context
import java.io.File

/** Cover images of MangaDex linked series, downloaded once and kept on the phone. */
object Covers {
    fun file(context: Context, mangadexId: String): File = File(context.filesDir, "covers/$mangadexId.jpg")

    fun saveIfMissing(context: Context, mangadexId: String, coverFile: String) {
        val file = file(context, mangadexId)
        if (file.exists()) return
        val bytes = MangaDex.coverBytes(mangadexId, coverFile)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.part")
        temp.writeBytes(bytes)
        temp.renameTo(file)
    }
}

package io.github.quezka.tether

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import org.json.JSONObject

/** "Share → Tether": text goes to the computer's clipboard, files to its Downloads folder. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = LinkService.instance
        if (service?.links?.values?.any { it.connected } != true) {
            Toast.makeText(this, R.string.not_connected, Toast.LENGTH_LONG).show()
            finish(); return
        }
        val uris = buildList<Uri> {
            @Suppress("DEPRECATION")
            when (intent.action) {
                Intent.ACTION_SEND -> (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let(::add)
                Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(::addAll)
            }
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        Thread {
            if (uris.isEmpty() && text != null) LinkService.send(JSONObject().put("t", "clip").put("text", text))
            for (u in uris) {
                val name = contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "file"
                val bytes = contentResolver.openInputStream(u)?.use { it.readBytes() } ?: continue
                service.shareFile(name, bytes)
            }
        }.start()
        Toast.makeText(this, R.string.sent, Toast.LENGTH_SHORT).show()
        finish()
    }
}

package com.example.satchel.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

// Handing documents to other apps on the phone.

/** Hands a downloaded file to a viewer app. Returns an error message, or null. */
fun openExternally(context: Context, uri: Uri, name: String): String? {
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeTypeFor(name))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val noViewer = "No app on this phone can open ${FileKind.of(name).label} files. Install a viewer and try again."
    if (context.packageManager.queryIntentActivities(view, 0).isEmpty()) return noViewer
    return try {
        context.startActivity(view)
        null
    } catch (_: ActivityNotFoundException) {
        noViewer
    }
}

/** Opens the share sheet with the files. False if no app can take them. */
fun shareFiles(context: Context, uris: List<Uri>): Boolean {
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    send.type = "*/*"
    // Grant the receiving app read access to every file, not only the first.
    send.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (context.packageManager.queryIntentActivities(send, 0).isEmpty()) return false
    return try {
        context.startActivity(Intent.createChooser(send, "Share ${plural(uris.size, "document")}"))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

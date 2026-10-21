package org.librehu.disc

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import org.librehu.disc.drive.Cdrom
import org.librehu.disc.drive.DiscDrive
import java.io.File
import java.io.FileNotFoundException

/**
 * Files of the mounted disc for other apps (videos, pictures, documents: `content://org.librehu.disc.files/file/…`),
 * and the whole disc as an ISO image (`…/raw/disc.iso`), which is what VLC opens as a DVD with its menus.
 * Read-only, given to the opening app by a URI grant.
 */
class DiscFileProvider : ContentProvider() {
    override fun onCreate() = true

    private fun target(uri: Uri): File? {
        val seg = uri.pathSegments
        return when (seg.firstOrNull()) {
            "raw" -> DiscDrive.device?.let { File(it) }
            "file" -> {
                val root = File(DiscDrive.MOUNT_POINT).canonicalFile
                val f = File(root, seg.drop(1).joinToString("/")).canonicalFile
                // No way out of the mounted disc.
                if (f.path.startsWith(root.path + "/")) f else null
            }
            else -> null
        }
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        val f = target(uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String {
        if (uri.pathSegments.firstOrNull() == "raw") return ISO_MIME
        val ext = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val f = target(uri)
        val name = if (uri.pathSegments.firstOrNull() == "raw") "disc.iso" else f?.name ?: uri.lastPathSegment.orEmpty()
        val size =
            if (uri.pathSegments.firstOrNull() == "raw") {
                DiscDrive.fd.takeIf { it >= 0 }?.let { Cdrom.capacity(it) } ?: 0L
            } else {
                f?.length() ?: 0L
            }
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply {
            addRow(
                cols.map {
                    when (it) {
                        OpenableColumns.DISPLAY_NAME -> name
                        OpenableColumns.SIZE -> size
                        else -> null
                    }
                },
            )
        }
    }

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0

    companion object {
        const val AUTHORITY = "org.librehu.disc.files"
        const val ISO_MIME = "application/x-iso9660-image"

        fun fileUri(f: File): Uri {
            val rel = f.absolutePath.removePrefix(DiscDrive.MOUNT_POINT).trimStart('/')
            return Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("file").apply {
                rel.split('/').forEach { appendPath(it) }
            }.build()
        }

        val RAW: Uri = Uri.parse("content://$AUTHORITY/raw/disc.iso")
    }
}

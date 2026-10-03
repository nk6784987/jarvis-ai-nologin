package com.example.device

import android.app.DownloadManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import java.io.File

data class FileInfo(val uri: Uri, val name: String, val mime: String, val size: Long, val modifiedMs: Long, val path: String?)

/** Real file ops via MediaStore (scoped-storage safe) and DownloadManager. */
class FileManager(private val context: Context) {
  private val cr get() = context.contentResolver

  private fun collection(): Uri = if (Build.VERSION.SDK_INT >= 29) MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Files.getContentUri("external")

  /** Needs READ_MEDIA_* (images/video) or READ_EXTERNAL_STORAGE; PDFs/docs via MediaStore.Files need all-files on 33+, see [listDownloadsDir]. */
  fun find(nameContains: String? = null, mimePrefix: String? = null, mimeExact: String? = null, newestFirst: Boolean = true, limit: Int = 20): List<FileInfo> {
    val sel = mutableListOf("${MediaStore.Files.FileColumns.SIZE} > 0"); val args = mutableListOf<String>()
    nameContains?.let { sel += "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"; args += "%$it%" }
    mimePrefix?.let { sel += "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ?"; args += "$it%" }
    mimeExact?.let { sel += "${MediaStore.Files.FileColumns.MIME_TYPE} = ?"; args += it }
    val proj = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.MIME_TYPE,
      MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.DATE_MODIFIED, MediaStore.Files.FileColumns.DATA)
    val out = mutableListOf<FileInfo>()
    runCatching {
      cr.query(collection(), proj, sel.joinToString(" AND "), args.toTypedArray(),
        "${MediaStore.Files.FileColumns.DATE_MODIFIED} ${if (newestFirst) "DESC" else "ASC"}")?.use { c ->
        while (c.moveToNext() && out.size < limit) {
          val id = c.getLong(0)
          out += FileInfo(ContentUris.withAppendedId(collection(), id), c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3), c.getLong(4) * 1000, c.getString(5))
        }
      }
    }
    return out
  }

  /** Direct listing of the public Downloads folder (works for any type when storage access is granted). */
  fun listDownloadsDir(extension: String? = null, limit: Int = 50): List<FileInfo> {
    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val files = dir.listFiles()?.filter { it.isFile && (extension == null || it.extension.equals(extension, true)) }
      ?.sortedByDescending { it.lastModified() }?.take(limit) ?: return emptyList()
    return files.map { f -> FileInfo(Uri.fromFile(f), f.name, mimeOf(f.name), f.length(), f.lastModified(), f.absolutePath) }
  }

  fun findLatestPdf(): FileInfo? = find(mimeExact = "application/pdf", limit = 1).firstOrNull() ?: listDownloadsDir("pdf", 1).firstOrNull()

  fun mimeOf(name: String): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

  fun open(f: FileInfo): DeviceResult<Unit> = try {
    val uri = shareableUri(f)
    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, f.mime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    DeviceResult.ok(Unit)
  } catch (e: Exception) { DeviceResult.fail(ResultCode.ACTION_FAILED, "Open fail: ${e.message}") }

  fun shareableUri(f: FileInfo): Uri = if (f.uri.scheme == "file") FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(f.uri.path!!)) else f.uri

  fun rename(f: FileInfo, newName: String): DeviceResult<Unit> = try {
    val v = android.content.ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
    if (f.uri.scheme == "file") { val ok = File(f.uri.path!!).renameTo(File(File(f.uri.path!!).parentFile, newName)); if (ok) DeviceResult.ok(Unit) else DeviceResult.fail(ResultCode.ACTION_FAILED, "rename fail") }
    else if (cr.update(f.uri, v, null, null) > 0) DeviceResult.ok(Unit) else DeviceResult.fail(ResultCode.ACTION_FAILED, "rename: 0 rows updated")
  } catch (e: Exception) { DeviceResult.fail(ResultCode.ACTION_FAILED, e.message ?: "rename error") }

  /** Caller MUST have obtained user confirmation first (SafetyGate). */
  fun delete(f: FileInfo): DeviceResult<Unit> = try {
    val ok = if (f.uri.scheme == "file") File(f.uri.path!!).delete() else cr.delete(f.uri, null, null) > 0
    if (ok) DeviceResult.ok(Unit) else DeviceResult.fail(ResultCode.ACTION_FAILED, "Delete nahi hua (permission/scoped storage)")
  } catch (e: SecurityException) { DeviceResult.fail(ResultCode.ACTION_FAILED, "Delete ke liye user consent chahiye: ${e.message}") }

  fun copyToDownloads(f: FileInfo, newName: String? = null): DeviceResult<File> = try {
    val dest = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), newName ?: f.name)
    cr.openInputStream(f.uri)!!.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
    if (dest.exists() && dest.length() == f.size) DeviceResult.ok(dest) else DeviceResult.fail(ResultCode.VERIFICATION_FAILED, "Copy size mismatch")
  } catch (e: Exception) { DeviceResult.fail(ResultCode.ACTION_FAILED, e.message ?: "copy fail") }

  // ---------------- downloads ----------------

  data class DownloadOutcome(val file: FileInfo, val mimeVerified: Boolean, val bytes: Long)

  /** START -> DOWNLOAD -> WAIT -> DETECT FILE -> VERIFY SIZE/TYPE -> VERIFY COMPLETION. */
  suspend fun download(url: String, fileName: String, expectMimePrefix: String? = null, timeoutMs: Long = 120_000): DeviceResult<DownloadOutcome> {
    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val req = DownloadManager.Request(Uri.parse(url)).setTitle(fileName).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
      .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName).addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
    val id = try { dm.enqueue(req) } catch (e: Exception) { return DeviceResult.fail(ResultCode.ACTION_FAILED, "Download start fail: ${e.message}") }
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
      dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
        if (c != null && c.moveToFirst()) {
          when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
            DownloadManager.STATUS_SUCCESSFUL -> return verifyDownload(dm, id, c, expectMimePrefix)
            DownloadManager.STATUS_FAILED -> return DeviceResult.fail(ResultCode.ACTION_FAILED, "Download fail (reason ${c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))})")
          }
        } else return DeviceResult.fail(ResultCode.ACTION_FAILED, "Download record gayab (cancel hua?)")
      }
      delay(700)
    }
    dm.remove(id)
    return DeviceResult.fail(ResultCode.TIMEOUT, "Download ${timeoutMs / 1000}s mein complete nahi hua")
  }

  private fun verifyDownload(dm: DownloadManager, id: Long, c: android.database.Cursor, mimePrefix: String?): DeviceResult<DownloadOutcome> {
    val uriStr = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
    val reported = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
    val uri = Uri.parse(uriStr); val f = File(uri.path ?: return DeviceResult.fail(ResultCode.VERIFICATION_FAILED, "Local path nahi mila"))
    if (!f.exists()) return DeviceResult.fail(ResultCode.VERIFICATION_FAILED, "File disk par nahi mili")
    if (f.length() <= 0L) return DeviceResult.fail(ResultCode.VERIFICATION_FAILED, "File empty hai")
    if (reported > 0 && f.length() != reported) return DeviceResult.fail(ResultCode.VERIFICATION_FAILED, "Size mismatch: ${f.length()} vs $reported")
    val head = ByteArray(16).also { b -> f.inputStream().use { it.read(b) } }
    val sniffed = sniffMime(head) ?: dm.getMimeTypeForDownloadedFile(id) ?: mimeOf(f.name)
    val mimeOk = mimePrefix == null || sniffed.startsWith(mimePrefix)
    if (!mimeOk) return DeviceResult.fail(ResultCode.VERIFICATION_FAILED, "File type $sniffed mila, $mimePrefix chahiye tha (HTML error page ho sakta hai)")
    return DeviceResult.ok(DownloadOutcome(FileInfo(Uri.fromFile(f), f.name, sniffed, f.length(), f.lastModified(), f.absolutePath), mimeOk, f.length()))
  }

  private fun sniffMime(h: ByteArray): String? = when {
    h.size > 3 && h[0] == 0xFF.toByte() && h[1] == 0xD8.toByte() -> "image/jpeg"
    h.size > 7 && h[0] == 0x89.toByte() && h[1] == 'P'.code.toByte() -> "image/png"
    h.size > 11 && String(h, 0, 4) == "RIFF" && String(h, 8, 4) == "WEBP" -> "image/webp"
    h.size > 3 && String(h, 0, 4) == "%PDF" -> "application/pdf"
    h.size > 3 && String(h, 0, 3) == "GIF" -> "image/gif"
    h.size > 4 && String(h, 0, 5).lowercase().let { it.startsWith("<!doc") || it.startsWith("<html") } -> "text/html"
    else -> null
  }
}

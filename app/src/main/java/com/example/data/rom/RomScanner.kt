package com.example.data.rom

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.example.data.local.RomEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class RomScanner(private val context: Context) {

    companion object {
        private val SUPPORTED_EXTENSIONS = setOf("gb", "gbc", "gba")

        fun formatTreeFolderLabel(treeUri: Uri): String {
            val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            if (docId.isNullOrBlank()) return treeUri.lastPathSegment ?: "Pasta de ROMs"
            val parts = docId.split(':', limit = 2)
            return if (parts.size == 2) {
                val root = if (parts[0].equals("primary", ignoreCase = true)) "Memória Interna" else parts[0]
                val sub = parts[1].trim('/')
                if (sub.isBlank()) root else "$root/$sub"
            } else {
                docId
            }
        }
    }

    suspend fun scanFolder(treeUri: Uri): List<RomEntity> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val results = mutableListOf<RomEntity>()
        val queue = ArrayDeque<String>()
        queue.add(rootDocId)

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        while (queue.isNotEmpty()) {
            val currentParentId = queue.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, currentParentId)

            runCatching {
                resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                    while (cursor.moveToNext()) {
                        val docId = if (idIdx >= 0) cursor.getString(idIdx) ?: continue else continue
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx) ?: continue else continue
                        val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) ?: "" else ""
                        val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L
                        val lastMod = if (modIdx >= 0 && !cursor.isNull(modIdx)) cursor.getLong(modIdx) else 0L

                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            queue.add(docId)
                            continue
                        }

                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (ext !in SUPPORTED_EXTENSIONS) continue

                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        val headerBytes = readHeaderSample(docUri, maxBytes = if (ext == "gba") 256 else 512)
                        val info = RomHeaderParser.parse(name, headerBytes)

                        results.add(
                            RomEntity(
                                uriString = docUri.toString(),
                                fileName = name,
                                displayTitle = info.displayTitle,
                                system = info.system,
                                gameCode = info.gameCode,
                                mapperInfo = info.mapperInfo,
                                fileSizeBytes = size,
                                lastModified = lastMod,
                                isImported = false
                            )
                        )
                    }
                }
            }
        }
        results.sortedBy { it.displayTitle.lowercase() }
    }

    suspend fun importSingleRom(uri: Uri): RomEntity? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var displayName = "jogo.gb"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) displayName = cursor.getString(idx) ?: displayName
            }
        }
        val ext = displayName.substringAfterLast('.', "").lowercase()
        if (ext !in SUPPORTED_EXTENSIONS) return@withContext null

        val importDir = File(context.filesDir, "imported_roms").apply { mkdirs() }
        val safeName = displayName.replace(Regex("[^a-zA-Z0-9._ -]"), "_")
        val destFile = File(importDir, safeName)

        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        } ?: return@withContext null

        val bytes = destFile.readBytes()
        val info = RomHeaderParser.parse(displayName, bytes)
        RomEntity(
            uriString = Uri.fromFile(destFile).toString(),
            fileName = displayName,
            displayTitle = info.displayTitle,
            system = info.system,
            gameCode = info.gameCode,
            mapperInfo = info.mapperInfo,
            fileSizeBytes = destFile.length(),
            lastModified = destFile.lastModified(),
            isImported = true
        )
    }

    suspend fun loadRomBytes(uriString: String): ByteArray? = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") {
            val file = uri.path?.let { File(it) }
            if (file != null && file.exists()) return@withContext file.readBytes()
        }
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }

    private fun readHeaderSample(uri: Uri, maxBytes: Int): ByteArray {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(maxBytes)
                var readTotal = 0
                while (readTotal < maxBytes) {
                    val n = input.read(buf, readTotal, maxBytes - readTotal)
                    if (n <= 0) break
                    readTotal += n
                }
                buf.copyOf(readTotal)
            } ?: ByteArray(0)
        }.getOrDefault(ByteArray(0))
    }
}

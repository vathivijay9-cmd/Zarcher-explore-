package com.example.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File

object SafUtils {
    private const val TAG = "SafUtils"
    private const val PREFS_NAME = "SafPrefs"
    private const val KEY_SAF_URI = "saf_tree_uri"

    /**
     * Target Android/data folder path
     */
    val androidDataPath: String = "${android.os.Environment.getExternalStorageDirectory().absolutePath}/Android/data"

    /**
     * Parse package name from path
     */
    fun getPackageNameFromPath(systemPath: String): String? {
        val normalized = systemPath.replace("/storage/emulated/0", "")
            .replace("/sdcard", "")
        if (!normalized.contains("/Android/data/")) return null
        val afterData = normalized.substringAfter("/Android/data/")
        val pkg = afterData.substringBefore("/")
        return if (pkg.isNotEmpty()) pkg else null
    }

    /**
     * Save the authorized tree URI to persistent storage.
     */
    fun saveSafUri(context: Context, uri: Uri) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val treeId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (e: Exception) {
            null
        }

        if (treeId != null) {
            if (treeId.contains("Android/data/")) {
                val pkgName = treeId.substringAfter("Android/data/").substringBefore("/")
                if (pkgName.isNotEmpty()) {
                    prefs.edit()
                        .putString("saf_uri_$pkgName", uri.toString())
                        .apply()
                }
            } else {
                prefs.edit()
                    .putString(KEY_SAF_URI, uri.toString())
                    .apply()
            }
        } else {
            prefs.edit()
                .putString(KEY_SAF_URI, uri.toString())
                .apply()
        }
    }

    /**
     * Retrieve persistent tree URI from preferences.
     */
    fun getSafUriString(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SAF_URI, null)
    }

    /**
     * Check if we hold a valid, active persistent permission to browse Android/data or specific subfolders.
     */
    fun hasSafPermission(context: Context, systemPath: String? = null): Boolean {
        val persisted = context.contentResolver.persistedUriPermissions
        
        // 1. Check global permission first
        val globalUriStr = getSafUriString(context)
        if (globalUriStr != null) {
            try {
                val globalUri = Uri.parse(globalUriStr)
                for (p in persisted) {
                    if (p.uri == globalUri && p.isReadPermission && p.isWritePermission) {
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error matching global permission", e)
            }
        }
        
        // 2. Check path-specific permission if path is provided
        if (systemPath != null) {
            val pkg = getPackageNameFromPath(systemPath)
            if (pkg != null) {
                val pkgUriStr = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString("saf_uri_$pkg", null)
                if (pkgUriStr != null) {
                    try {
                        val pkgUri = Uri.parse(pkgUriStr)
                        for (p in persisted) {
                            if (p.uri == pkgUri && p.isReadPermission && p.isWritePermission) {
                                return true
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error matching package permission for $pkg", e)
                    }
                }
            }
        }
        
        return false
    }

    /**
     * Triggers SAF Folder requesting permission to Android/data or specific application subfolder.
     */
    fun getSafRequestIntent(subfolder: String? = null): Intent {
        val pathSegment = if (subfolder.isNullOrEmpty()) {
            "primary%3AAndroid%2Fdata"
        } else {
            "primary%3AAndroid%2Fdata%2F$subfolder"
        }
        val primaryUriStr = "content://com.android.externalstorage.documents/tree/$pathSegment"
        val initialUri = Uri.parse(primaryUriStr)
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }
    }

    /**
     * Direct conversion from a system path under Android/data to the SAF Document URI.
     * Maps `/storage/emulated/0/Android/data/com.example/files` to matched Document IDs.
     */
    fun getDocumentUriForPath(context: Context, systemPath: String): Uri? {
        val pkg = getPackageNameFromPath(systemPath)
        val hasPackageUri = pkg != null && context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).contains("saf_uri_$pkg")
        
        val rootUriStr = if (hasPackageUri && pkg != null) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("saf_uri_$pkg", null)
        } else {
            getSafUriString(context)
        } ?: return null
        
        val rootUri = Uri.parse(rootUriStr)
        val rootDocId = DocumentsContract.getTreeDocumentId(rootUri) // usually "primary:Android/data"

        // Find relative suffix starting after /Android/data/
        val normalizedPath = systemPath.replace("/storage/emulated/0", "")
            .replace("/sdcard", "")
        
        var relativePart = normalizedPath.substringAfter("/Android/data", "")
        if (relativePart.startsWith("/")) {
            relativePart = relativePart.substring(1)
        }

        val targetDocId = if (rootDocId.contains("Android/data/")) {
            val rootPkg = rootDocId.substringAfter("Android/data/").substringBefore("/")
            if (relativePart.startsWith(rootPkg)) {
                val subPart = relativePart.substringAfter(rootPkg).removePrefix("/")
                if (subPart.isEmpty()) rootDocId else "$rootDocId/$subPart"
            } else {
                if (relativePart.isEmpty()) rootDocId else "$rootDocId/$relativePart"
            }
        } else {
            if (relativePart.isEmpty()) {
                rootDocId
            } else {
                "$rootDocId/$relativePart"
            }
        }

        return DocumentsContract.buildDocumentUriUsingTree(rootUri, targetDocId)
    }

    /**
     * List all items inside a directory using SAF.
     */
    fun listSafDirectory(context: Context, systemPath: String): List<FileItem> {
        val list = mutableListOf<FileItem>()
        val parentUri = getDocumentUriForPath(context, systemPath) ?: return emptyList()
        val resolver = context.contentResolver

        val childrenUri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(
                parentUri,
                DocumentsContract.getDocumentId(parentUri)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build children URI for $systemPath", e)
            return emptyList()
        }

        var cursor: android.database.Cursor? = null
        try {
            cursor = resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                ),
                null, null, null
            )

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0)
                    val displayName = cursor.getString(1)
                    val mimeType = cursor.getString(2)
                    val size = cursor.getLong(3)
                    val lastModified = cursor.getLong(4)

                    val isDir = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                    val isZip = displayName.lowercase().endsWith(".zip")

                    // Assemble the exact virtual local path for back/forth navigation
                    val subPath = if (systemPath.endsWith("/")) {
                        "$systemPath$displayName"
                    } else {
                        "$systemPath/$displayName"
                    }

                    list.add(
                        FileItem(
                            name = displayName,
                            path = subPath,
                            isDirectory = isDir,
                            size = if (isDir) 0L else size,
                            lastModified = lastModified,
                            permissions = "saf-rwx",
                            owner = "app",
                            group = "saf",
                            isZipArchive = isZip
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing query for SAF: ${e.message}", e)
        } finally {
            cursor?.close()
        }

        return list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    /**
     * Create a directory inside SAF hierarchy.
     */
    fun createSafDirectory(context: Context, parentSystemPath: String, name: String): Boolean {
        return try {
            val parentUri = getDocumentUriForPath(context, parentSystemPath) ?: return false
            val provider = DocumentsContract.buildDocumentUriUsingTree(
                parentUri,
                DocumentsContract.getDocumentId(parentUri)
            )
            val newDir = DocumentsContract.createDocument(
                context.contentResolver,
                provider,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name
            )
            newDir != null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SAF directory: ${e.message}", e)
            false
        }
    }

    /**
     * Create a file inside SAF hierarchy.
     */
    fun createSafFile(context: Context, parentSystemPath: String, name: String, content: String): Boolean {
        try {
            val parentUri = getDocumentUriForPath(context, parentSystemPath) ?: return false
            val provider = DocumentsContract.buildDocumentUriUsingTree(
                parentUri,
                DocumentsContract.getDocumentId(parentUri)
            )
            
            val mimeType = when {
                name.endsWith(".txt") -> "text/plain"
                name.endsWith(".html") -> "text/html"
                name.endsWith(".json") -> "application/json"
                else -> "application/octet-stream"
            }

            val newFileUri = DocumentsContract.createDocument(
                context.contentResolver,
                provider,
                mimeType,
                name
            ) ?: return false

            if (content.isNotEmpty()) {
                context.contentResolver.openOutputStream(newFileUri)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SAF file: ${e.message}", e)
            return false
        }
    }

    /**
     * Deletes an item inside SAF hierarchy.
     */
    fun deleteSafItem(context: Context, systemPath: String): Boolean {
        return try {
            val targetUri = getDocumentUriForPath(context, systemPath) ?: return false
            DocumentsContract.deleteDocument(context.contentResolver, targetUri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed deleting SAF item: ${e.message}", e)
            false
        }
    }

    /**
     * Renames an item inside SAF hierarchy.
     */
    fun renameSafItem(context: Context, systemPath: String, newName: String): Boolean {
        return try {
            val targetUri = getDocumentUriForPath(context, systemPath) ?: return false
            val newUri = DocumentsContract.renameDocument(context.contentResolver, targetUri, newName)
            newUri != null
        } catch (e: Exception) {
            Log.e(TAG, "Failed rename SAF item: ${e.message}", e)
            false
        }
    }
}

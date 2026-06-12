package com.example.utils

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ZipEntryInfo(
    val name: String,
    val size: Long,
    val compressedSize: Long,
    val isDirectory: Boolean
) {
    val formattedSize: String
        get() = if (isDirectory) "--" else String.format("%,d bytes", size)
}

object ZipUtils {
    private const val TAG = "ZipUtils"

    /**
     * Lists all entries inside a ZIP file without extracting.
     */
    fun listZipEntries(zipFilePath: String): List<ZipEntryInfo> {
        val entriesList = mutableListOf<ZipEntryInfo>()
        val file = File(zipFilePath)
        if (!file.exists()) return entriesList

        var zipFile: ZipFile? = null
        try {
            zipFile = ZipFile(file)
            val entries = zipFile.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                entriesList.add(
                    ZipEntryInfo(
                        name = entry.name,
                        size = entry.size,
                        compressedSize = entry.compressedSize,
                        isDirectory = entry.isDirectory
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read zip contents: ${e.message}", e)
        } finally {
            try { zipFile?.close() } catch (ignored: Exception) {}
        }
        return entriesList
    }

    /**
     * Compresses a file or folder into a ZIP archive.
     */
    fun compress(sourcePath: String, zipFilePath: String, onProgress: (Float, String) -> Unit): Boolean {
        val sourceFile = File(sourcePath)
        val zipFile = File(zipFilePath)

        // Make parent directory for zip if it doesn't exist
        zipFile.parentFile?.mkdirs()

        // Count total files to compute progress
        val totalFiles = countFiles(sourceFile)
        var compressedCount = 0

        var zipOutputStream: ZipOutputStream? = null
        try {
            zipOutputStream = ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile)))
            
            fun zipEntry(file: File, relativePath: String) {
                if (file.isDirectory) {
                    val dirPath = if (relativePath.endsWith("/")) relativePath else "$relativePath/"
                    zipOutputStream.putNextEntry(ZipEntry(dirPath))
                    zipOutputStream.closeEntry()
                    
                    val children = file.listFiles()
                    if (children != null) {
                        for (child in children) {
                            zipEntry(child, dirPath + child.name)
                        }
                    }
                } else {
                    onProgress(compressedCount.toFloat() / totalFiles.coerceAtLeast(1), file.name)
                    val origin = BufferedInputStream(FileInputStream(file))
                    try {
                        val entry = ZipEntry(relativePath)
                        zipOutputStream.putNextEntry(entry)
                        
                        val buffer = ByteArray(2048)
                        var bytesRead: Int
                        while (origin.read(buffer).also { bytesRead = it } != -1) {
                            zipOutputStream.write(buffer, 0, bytesRead)
                        }
                        zipOutputStream.closeEntry()
                        compressedCount++
                    } finally {
                        origin.close()
                    }
                }
            }

            if (sourceFile.isDirectory) {
                val children = sourceFile.listFiles()
                if (children != null) {
                    for (child in children) {
                        zipEntry(child, child.name)
                    }
                }
            } else {
                zipEntry(sourceFile, sourceFile.name)
            }
            onProgress(1.0f, "Completed")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Compression failed of $sourcePath: ${e.message}", e)
            return false
        } finally {
            try { zipOutputStream?.close() } catch (ignored: Exception) {}
        }
    }

    /**
     * Extracts a ZIP archive folder into a directory.
     */
    fun extract(zipFilePath: String, destDirPath: String, onProgress: (Float, String) -> Unit): Boolean {
        val destDir = File(destDirPath)
        if (!destDir.exists()) {
            destDir.mkdirs()
        }

        var totalEntries = 1
        var extractedEntries = 0

        // Get total entries first to evaluate progress
        try {
            val zf = ZipFile(zipFilePath)
            totalEntries = zf.size().coerceAtLeast(1)
            zf.close()
        } catch (ignored: Exception) {}

        var zipInputStream: ZipInputStream? = null
        try {
            zipInputStream = ZipInputStream(BufferedInputStream(FileInputStream(zipFilePath)))
            var entry: ZipEntry? = zipInputStream.nextEntry
            val buffer = ByteArray(4096)

            while (entry != null) {
                val file = File(destDir, entry.name)
                
                // Security check against Zip Slip exploit
                val canonicalDestDir = destDir.canonicalPath
                val canonicalFile = file.canonicalPath
                if (!canonicalFile.startsWith(canonicalDestDir)) {
                    throw SecurityException("Illegal ZIP entry tried traversing parent levels: " + entry.name)
                }

                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    onProgress(extractedEntries.toFloat() / totalEntries, entry.name)
                    
                    val outputStream = FileOutputStream(file)
                    try {
                        var count: Int
                        while (zipInputStream.read(buffer).also { count = it } != -1) {
                            outputStream.write(buffer, 0, count)
                        }
                    } finally {
                        outputStream.close()
                    }
                }
                zipInputStream.closeEntry()
                extractedEntries++
                entry = zipInputStream.nextEntry
            }
            onProgress(1.0f, "Completed")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Unzipping failed for $zipFilePath: ${e.message}", e)
            return false
        } finally {
            try { zipInputStream?.close() } catch (ignored: Exception) {}
        }
    }

    private fun countFiles(file: File): Int {
        if (!file.exists()) return 0
        if (!file.isDirectory) return 1
        var count = 0
        val children = file.listFiles()
        if (children != null) {
            for (child in children) {
                count += countFiles(child)
            }
        }
        return count
    }
}

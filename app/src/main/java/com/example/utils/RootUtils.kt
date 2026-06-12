package com.example.utils

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

data class FileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val permissions: String = "",
    val owner: String = "",
    val group: String = "",
    val isZipArchive: Boolean = false
) {
    val formattedSize: String
        get() {
            if (isDirectory) return "--"
            val kb = size / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
                mb >= 1.0 -> String.format(Locale.US, "%.2f MB", mb)
                kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
                else -> "$size bytes"
            }
        }

    val formattedDate: String
        get() {
            if (lastModified <= 0) return "--"
            return try {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                sdf.format(Date(lastModified))
            } catch (e: Exception) {
                "--"
            }
        }
}

object RootUtils {
    private const val TAG = "RootUtils"

    private val SU_PATHS = arrayOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/local/su",
        "/su/bin/su"
    )

    /**
     * Checks if the su binary is present on the system.
     */
    fun isDeviceRooted(): Boolean {
        for (path in SU_PATHS) {
            if (File(path).exists()) return true
        }
        // Fallback: Check 'which su' via a standard process
        val result = executeCommandSync("which su", runAsRoot = false)
        return result.exitCode == 0 && result.stdout.isNotBlank()
    }

    /**
     * Executes a terminal command synchronously.
     */
    fun executeCommandSync(command: String, runAsRoot: Boolean): CommandResult {
        var process: Process? = null
        var os: java.io.DataOutputStream? = null
        var stdoutReader: BufferedReader? = null
        var stderrReader: BufferedReader? = null
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        try {
            val shell = if (runAsRoot) "su" else "sh"
            process = ProcessBuilder(shell).start()
            
            os = java.io.DataOutputStream(process.outputStream)
            os.writeBytes(command + "\n")
            os.writeBytes("exit\n")
            os.flush()

            stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (stdoutReader.readLine().also { line = it } != null) {
                stdout.append(line).append("\n")
            }

            stderrReader = BufferedReader(InputStreamReader(process.errorStream))
            while (stderrReader.readLine().also { line = it } != null) {
                stderr.append(line).append("\n")
            }

            process.waitFor()
            val exitValue = process.exitValue()

            return CommandResult(
                exitCode = exitValue,
                stdout = stdout.toString().trim(),
                stderr = stderr.toString().trim()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error running command '$command': ${e.message}", e)
            return CommandResult(
                exitCode = -1,
                stdout = "",
                stderr = e.localizedMessage ?: "Unknown process failure"
            )
        } finally {
            try { os?.close() } catch (ignored: Exception) {}
            try { stdoutReader?.close() } catch (ignored: Exception) {}
            try { stderrReader?.close() } catch (ignored: Exception) {}
            process?.destroy()
        }
    }

    /**
     * Lists files inside a directory. Uses root 'ls' if directed, otherwise standard Java Files.
     */
    fun listDirectory(path: String, runAsRoot: Boolean): List<FileItem> {
        val list = mutableListOf<FileItem>()
        val baseFolder = File(path)

        if (runAsRoot) {
            // execute ls command with detailed permissions, owner, group and size
            val cmd = "ls -lA \"$path\""
            val result = executeCommandSync(cmd, runAsRoot = true)
            if (result.exitCode == 0 && result.stdout.isNotBlank()) {
                val lines = result.stdout.split("\n")
                for (line in lines) {
                    if (line.trim().startsWith("total")) continue
                    val fileItem = parseLsLine(line, path)
                    if (fileItem != null) {
                        list.add(fileItem)
                    }
                }
                // Sort folders first, then alphabetically
                return list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            }
        }

        // Standard java.io.File fallback or non-root default
        try {
            val files = baseFolder.listFiles()
            if (files != null) {
                for (file in files) {
                    val isZip = file.name.lowercase().endsWith(".zip")
                    list.add(
                        FileItem(
                            name = file.name,
                            path = file.absolutePath,
                            isDirectory = file.isDirectory,
                            size = if (file.isDirectory) 0 else file.length(),
                            lastModified = file.lastModified(),
                            permissions = getSimplePermissions(file),
                            owner = "me",
                            group = "staff",
                            isZipArchive = isZip
                        )
                    )
                }
            } else {
                // If standard files returns null but path exists, try simple 'su' ls listing if they have su
                if (isDeviceRooted()) {
                    return listDirectory(path, runAsRoot = true)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading normal files at $path: ${e.message}")
        }

        return list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    private fun getSimplePermissions(file: File): String {
        val r = if (file.canRead()) "r" else "-"
        val w = if (file.canWrite()) "w" else "-"
        val x = if (file.canExecute()) "x" else "-"
        return r + w + x
    }

    /**
     * Parses standard output of ls -lA. Example:
     * drwxrwx--- 2 system cache 4096 1970-01-01 00:00 backup
     * -rw------- 1 root   root   1024 2026-06-11 12:45 config.xml
     */
    fun parseLsLine(line: String, parentPath: String): FileItem? {
        val parts = line.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (parts.size < 7) return null

        try {
            val permissions = parts[0]
            val isDirectory = permissions.startsWith("d") || permissions.startsWith("l")
            val owner = parts[2]
            val group = parts[3]

            // Size could be at column 4
            val size = parts[4].toLongOrNull() ?: 0L

            // Date processing can vary, let's assemble name (usually the last column or columns)
            // Ls output format might be: [perms] [count] [owner] [group] [size] [date] [time] [name]
            // Let's count from the standard ls format
            val nameIndex = line.indexOf(parts.last())
            val name = line.substring(nameIndex).trim()

            if (name == "." || name == "..") return null

            val isZip = name.lowercase().endsWith(".zip")
            val systemPath = if (parentPath.endsWith("/")) "$parentPath$name" else "$parentPath/$name"

            return FileItem(
                name = name,
                path = systemPath,
                isDirectory = isDirectory,
                size = if (isDirectory) 0L else size,
                lastModified = System.currentTimeMillis(), // fallback, parsing dynamic ls dates is volatile
                permissions = permissions,
                owner = owner,
                group = group,
                isZipArchive = isZip
            )
        } catch (e: Exception) {
            // Quick parsing fallback if column structure is different
            val lastName = parts.lastOrNull() ?: return null
            if (lastName == "." || lastName == "..") return null
            val isDir = line.startsWith("d")
            return FileItem(
                name = lastName,
                path = if (parentPath.endsWith("/")) "$parentPath$lastName" else "$parentPath/$lastName",
                isDirectory = isDir,
                size = 0,
                lastModified = System.currentTimeMillis(),
                permissions = if (isDir) "drwx" else "-rwx",
                owner = "root",
                group = "root",
                isZipArchive = lastName.lowercase().endsWith(".zip")
            )
        }
    }
}

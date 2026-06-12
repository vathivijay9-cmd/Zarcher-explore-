package com.example.viewmodel

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.utils.CommandResult
import com.example.utils.FileItem
import com.example.utils.RootUtils
import com.example.utils.SafUtils
import com.example.utils.ZipEntryInfo
import com.example.utils.ZipUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FileExplorerViewModel : ViewModel() {
    private val TAG = "FileExplorerViewModel"

    // Initial Path selection
    private val defaultSdcard: String = try {
        Environment.getExternalStorageDirectory().absolutePath
    } catch (e: Exception) {
        "/sdcard"
    }

    private val _currentPath = MutableStateFlow(defaultSdcard)
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private val _filesList = MutableStateFlow<List<FileItem>>(emptyList())
    val filesList: StateFlow<List<FileItem>> = _filesList.asStateFlow()

    private val _isRootAvailable = MutableStateFlow(false)
    val isRootAvailable: StateFlow<Boolean> = _isRootAvailable.asStateFlow()

    private val _isRootActive = MutableStateFlow(false)
    val isRootActive: StateFlow<Boolean> = _isRootActive.asStateFlow()

    private val _isSafAuthorized = MutableStateFlow(false)
    val isSafAuthorized: StateFlow<Boolean> = _isSafAuthorized.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isExecuting = MutableStateFlow(false)
    val isExecuting: StateFlow<Boolean> = _isExecuting.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _terminalLogs = MutableStateFlow<List<String>>(
        listOf("Shell Initialized.", "Root status: Checking su binary presence...")
    )
    val terminalLogs: StateFlow<List<String>> = _terminalLogs.asStateFlow()

    private val _zipProgress = MutableStateFlow<Pair<Float, String>?>(null)
    val zipProgress: StateFlow<Pair<Float, String>?> = _zipProgress.asStateFlow()

    private val _inspectingZipEntries = MutableStateFlow<List<ZipEntryInfo>?>(null)
    val inspectingZipEntries: StateFlow<List<ZipEntryInfo>?> = _inspectingZipEntries.asStateFlow()

    private val _activeZipItem = MutableStateFlow<FileItem?>(null)
    val activeZipItem: StateFlow<FileItem?> = _activeZipItem.asStateFlow()

    // Combined filtered list
    private val _filteredFiles = MutableStateFlow<List<FileItem>>(emptyList())
    val filteredFiles: StateFlow<List<FileItem>> = _filteredFiles.asStateFlow()

    init {
        checkRootPresence()
        loadDirectory(_currentPath.value, null)

        // Combine filter logic
        viewModelScope.launch {
            combine(_filesList, _searchQuery) { list, query ->
                if (query.isBlank()) {
                    list
                } else {
                    list.filter { it.name.contains(query, ignoreCase = true) }
                }
            }.collect { filtered ->
                _filteredFiles.value = filtered
            }
        }
    }

    private fun checkRootPresence() {
        viewModelScope.launch(Dispatchers.IO) {
            val hasRoot = RootUtils.isDeviceRooted()
            _isRootAvailable.value = hasRoot
            val suStatus = if (hasRoot) "FOUND (su is active on device)" else "NOT FOUND (su missing)"
            addTerminalLog("[INFO] su bin check: $suStatus. Mode: Normal device storage explorer active.")
        }
    }

    fun refreshSafStatus(context: Context, path: String? = null) {
        _isSafAuthorized.value = SafUtils.hasSafPermission(context, path ?: _currentPath.value)
    }

    fun getVirtualDataPackages(context: Context): List<FileItem> {
        val list = mutableListOf<FileItem>()
        try {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in apps) {
                if (app.packageName == context.packageName) continue
                val appLabel = pm.getApplicationLabel(app).toString()
                val pkgName = app.packageName
                
                val pathStr = "${SafUtils.androidDataPath}/$pkgName"
                
                list.add(
                    FileItem(
                        name = pkgName,
                        path = pathStr,
                        isDirectory = true,
                        size = 0L,
                        lastModified = 0L,
                        permissions = "virtual",
                        owner = appLabel,
                        group = "installed",
                        isZipArchive = false
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("FileExplorerViewModel", "Error fetching installed apps", e)
        }
        
        val popular = listOf(
            "com.mojang.minecraftpe" to "Minecraft",
            "com.tencent.ig" to "PUBG Mobile",
            "com.dts.freefireth" to "Free Fire",
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "com.mobile.legends" to "Mobile Legends",
            "com.instagram.android" to "Instagram",
            "com.facebook.katana" to "Facebook"
        )
        val existingPkgs = list.map { it.name }.toSet()
        for ((pkg, label) in popular) {
            if (!existingPkgs.contains(pkg)) {
                list.add(
                    FileItem(
                        name = pkg,
                        path = "${SafUtils.androidDataPath}/$pkg",
                        isDirectory = true,
                        size = 0L,
                        lastModified = 0L,
                        permissions = "virtual",
                        owner = label,
                        group = "popular",
                        isZipArchive = false
                    )
                )
            }
        }
        
        return list.sortedBy { it.owner.lowercase() }
    }

    fun isAndroidDataPath(path: String): Boolean {
        val normalized = path.replace("//", "/")
        return normalized == SafUtils.androidDataPath || normalized.startsWith(SafUtils.androidDataPath + "/")
    }

    fun setRootActive(active: Boolean) {
        _isRootActive.value = active
        addTerminalLog("[MODE] Root Access Mode toggled: ${if (active) "ENABLED" else "DISABLED"}")
        loadDirectory(_currentPath.value, null)
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    private fun addTerminalLog(log: String) {
        val current = _terminalLogs.value.toMutableList()
        current.add(log)
        if (current.size > 200) current.removeAt(0)
        _terminalLogs.value = current
    }

    fun loadDirectory(path: String, context: Context? = null) {
        _currentPath.value = path
        _isExecuting.value = true
        _inspectingZipEntries.value = null
        _activeZipItem.value = null
        if (context != null) {
            refreshSafStatus(context, path)
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Determine whether to use SAF or RootUtils listing
                val list = if (!_isRootActive.value && context != null && isAndroidDataPath(path)) {
                    if (SafUtils.hasSafPermission(context, path)) {
                        SafUtils.listSafDirectory(context, path)
                    } else if (path.replace("//", "/").removeSuffix("/") == SafUtils.androidDataPath.removeSuffix("/")) {
                        getVirtualDataPackages(context)
                    } else {
                        emptyList()
                    }
                } else {
                    RootUtils.listDirectory(path, _isRootActive.value)
                }
                _filesList.value = list
                addTerminalLog("[CMD] Listed dir: $path (items: ${list.size})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed listing dir $path", e)
                _errorMessage.value = "Access Denied: Cannot view directory $path"
                _filesList.value = emptyList()
            } finally {
                _isExecuting.value = false
            }
        }
    }

    fun navigateUp(context: Context? = null) {
        val current = File(_currentPath.value)
        val parent = current.parentFile
        if (parent != null) {
            loadDirectory(parent.absolutePath, context)
        } else if (_currentPath.value != "/") {
            loadDirectory("/", context)
        }
    }

    fun createDirectory(name: String, context: Context? = null) {
        if (name.isBlank()) return
        _isExecuting.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val parentPath = _currentPath.value
            
            var success = false
            if (!_isRootActive.value && context != null && isAndroidDataPath(parentPath)) {
                success = SafUtils.createSafDirectory(context, parentPath, name)
            } else {
                val targetFolder = File(parentPath, name)
                if (_isRootActive.value) {
                    val escapedPath = targetFolder.absolutePath.replace("\"", "\\\"")
                    val cmd = "mkdir -p \"$escapedPath\""
                    val result = RootUtils.executeCommandSync(cmd, runAsRoot = true)
                    success = result.exitCode == 0
                    addTerminalLog("[ROOT] mkdir status: ${result.exitCode}. Log: ${result.stdout} ${result.stderr}")
                } else {
                    success = targetFolder.mkdirs()
                }
            }

            if (success) {
                addTerminalLog("[SUCCESS] Created directory: $name")
                loadDirectory(parentPath, context)
            } else {
                _errorMessage.value = "Failed creating folder '$name'. Check root permission or write-access."
                _isExecuting.value = false
            }
        }
    }

    fun createFile(name: String, content: String = "", context: Context? = null) {
        if (name.isBlank()) return
        _isExecuting.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val parentPath = _currentPath.value
            
            var success = false
            if (!_isRootActive.value && context != null && isAndroidDataPath(parentPath)) {
                success = SafUtils.createSafFile(context, parentPath, name, content)
            } else {
                val targetFile = File(parentPath, name)
                if (_isRootActive.value) {
                    val escapedPath = targetFile.absolutePath.replace("\"", "\\\"")
                    val cmd = "echo \"$content\" > \"$escapedPath\""
                    val result = RootUtils.executeCommandSync(cmd, runAsRoot = true)
                    success = result.exitCode == 0
                    addTerminalLog("[ROOT] create_file status: ${result.exitCode}")
                } else {
                    try {
                        targetFile.parentFile?.mkdirs()
                        targetFile.createNewFile()
                        if (content.isNotEmpty()) {
                            targetFile.writeText(content)
                        }
                        success = true
                    } catch (e: Exception) {
                        Log.e(TAG, "File creation error", e)
                    }
                }
            }

            if (success) {
                addTerminalLog("[SUCCESS] Created file: $name")
                loadDirectory(parentPath, context)
            } else {
                _errorMessage.value = "Failed creating file '$name'. Check root permission."
                _isExecuting.value = false
            }
        }
    }

    fun deleteItem(item: FileItem, context: Context? = null) {
        _isExecuting.value = true
        viewModelScope.launch(Dispatchers.IO) {
            var success = false
            if (!_isRootActive.value && context != null && isAndroidDataPath(item.path)) {
                success = SafUtils.deleteSafItem(context, item.path)
            } else {
                if (_isRootActive.value) {
                    val cmd = "rm -rf \"${item.path}\""
                    val result = RootUtils.executeCommandSync(cmd, runAsRoot = true)
                    success = result.exitCode == 0
                    addTerminalLog("[ROOT] rm -rf status: ${result.exitCode}")
                } else {
                    val target = File(item.path)
                    success = if (target.isDirectory) {
                        target.deleteRecursively()
                    } else {
                        target.delete()
                    }
                }
            }

            if (success) {
                addTerminalLog("[SUCCESS] Deleted file/folder: ${item.name}")
                loadDirectory(_currentPath.value, context)
            } else {
                _errorMessage.value = "Deleting '${item.name}' failed. Try enabling Root Access Mode."
                _isExecuting.value = false
            }
        }
    }

    fun renameItem(item: FileItem, newName: String, context: Context? = null) {
        if (newName.isBlank() || newName == item.name) return
        _isExecuting.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val parentPath = _currentPath.value
            
            var success = false
            if (!_isRootActive.value && context != null && isAndroidDataPath(item.path)) {
                success = SafUtils.renameSafItem(context, item.path, newName)
            } else {
                val source = File(item.path)
                val destination = File(source.parentFile, newName)
                if (_isRootActive.value) {
                    val cmd = "mv \"${source.absolutePath}\" \"${destination.absolutePath}\""
                    val result = RootUtils.executeCommandSync(cmd, runAsRoot = true)
                    success = result.exitCode == 0
                    addTerminalLog("[ROOT] mv status: ${result.exitCode}")
                } else {
                    success = source.renameTo(destination)
                }
            }

            if (success) {
                addTerminalLog("[SUCCESS] Renamed ${item.name} to $newName")
                loadDirectory(parentPath, context)
            } else {
                _errorMessage.value = "Failed renaming file to '$newName'."
                _isExecuting.value = false
            }
        }
    }

    fun executeTerminalCommand(commandString: String, executeAsSU: Boolean) {
        if (commandString.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            addTerminalLog("[SHELL] Executing: $commandString ${if (executeAsSU) "(as root)" else ""}")
            val result = RootUtils.executeCommandSync(commandString, runAsRoot = executeAsSU)
            addTerminalLog("[SHELL_RES] Exit code: ${result.exitCode}")
            if (result.stdout.isNotBlank()) {
                result.stdout.split("\n").forEach { addTerminalLog("  > $it") }
            }
            if (result.stderr.isNotBlank()) {
                result.stderr.split("\n").forEach { addTerminalLog("[ERR] > $it") }
            }
        }
    }

    // ZIP ARCHIVE MANAGEMENT (ZArchiver Core Feature)

    fun inspectZipFile(item: FileItem) {
        _activeZipItem.value = item
        _isExecuting.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entries = ZipUtils.listZipEntries(item.path)
                _inspectingZipEntries.value = entries
                addTerminalLog("[ZIP] Inspected entry count: ${entries.size} inside ${item.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Error listing zip: ${e.message}")
                _errorMessage.value = "Failed to inspect ZIP. Archive might be corrupt or encrypted."
            } finally {
                _isExecuting.value = false
            }
        }
    }

    fun closeZipInspection() {
        _inspectingZipEntries.value = null
        _activeZipItem.value = null
    }

    fun compressToZip(item: FileItem) {
        _isExecuting.value = true
        val outputZipPath = if (item.path.endsWith("/")) {
            "${item.path.substring(0, item.path.length - 1)}.zip"
        } else {
            "${item.path}.zip"
        }

        viewModelScope.launch(Dispatchers.Default) {
            _zipProgress.value = Pair(0.0f, "Starting ZIP compression...")
            val success = ZipUtils.compress(item.path, outputZipPath) { progress, file ->
                _zipProgress.value = Pair(progress, "Zipping: $file")
            }
            withContext(Dispatchers.IO) {
                _zipProgress.value = null
                if (success) {
                    addTerminalLog("[SUCCESS] Compressed '${item.name}' into ZIP.")
                    loadDirectory(_currentPath.value)
                } else {
                    _errorMessage.value = "Failed compressing '${item.name}'."
                    _isExecuting.value = false
                }
            }
        }
    }

    fun extractZipFile(item: FileItem, targetDirectory: String? = null) {
        _isExecuting.value = true
        val targetPath = targetDirectory ?: _currentPath.value
        viewModelScope.launch(Dispatchers.Default) {
            _zipProgress.value = Pair(0.0f, "Extracting files...")
            val success = ZipUtils.extract(item.path, targetPath) { progress, entry ->
                _zipProgress.value = Pair(progress, "Extracting: $entry")
            }
            withContext(Dispatchers.IO) {
                _zipProgress.value = null
                if (success) {
                    addTerminalLog("[SUCCESS] Extracted '${item.name}' to $targetPath")
                    loadDirectory(_currentPath.value)
                } else {
                    _errorMessage.value = "Failed extracting archive '${item.name}'."
                    _isExecuting.value = false
                }
            }
        }
    }
}

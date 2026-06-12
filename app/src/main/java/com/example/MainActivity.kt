package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.utils.FileItem
import com.example.utils.ZipEntryInfo
import com.example.viewmodel.FileExplorerViewModel
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainAppHost()
            }
        }
    }
}

@Composable
fun MainAppHost() {
    val context = LocalContext.current
    var hasLegacyPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    var hasAllFilesPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true
            }
        )
    }

    // Traditional Permission Launcher
    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val writeGranted = permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] ?: false
        val readGranted = permissions[Manifest.permission.READ_EXTERNAL_STORAGE] ?: false
        hasLegacyPermission = writeGranted || readGranted
    }

    // Android 11 Storage Manager Launcher
    val allFilesPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            hasAllFilesPermission = Environment.isExternalStorageManager()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasLegacyPermission && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            legacyPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("app_main_scaffold"),
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (!hasLegacyPermission || !hasAllFilesPermission) {
                // High Quality Permission Request State
                PermissionBannerSection(
                    hasLegacy = hasLegacyPermission,
                    hasAllFiles = hasAllFilesPermission,
                    onRequestLegacy = {
                        legacyPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            )
                        )
                    },
                    onRequestAllFiles = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            try {
                                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                allFilesPermissionLauncher.launch(intent)
                            } catch (e: Exception) {
                                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                allFilesPermissionLauncher.launch(intent)
                            }
                        }
                    },
                    onSkip = {
                        hasLegacyPermission = true
                        hasAllFilesPermission = true
                    }
                )
            } else {
                RootZExplorerApp()
            }
        }
    }
}

@Composable
fun PermissionBannerSection(
    hasLegacy: Boolean,
    hasAllFiles: Boolean,
    onRequestLegacy: () -> Unit,
    onRequestAllFiles: () -> Unit,
    onSkip: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.Shield,
            contentDescription = "Shield Access Icon",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(80.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Storage Privileges Required",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "To search directories, compress archives (ZArchiver mode), and modify files, RootZ Explorer needs file-access permissions. Zero setup is required.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFiles) {
            Button(
                onClick = onRequestAllFiles,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("request_all_files_btn")
            ) {
                Text(
                    text = "Grant All Files Access",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        } else if (!hasLegacy) {
            Button(
                onClick = onRequestLegacy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("request_legacy_btn")
            ) {
                Text(
                    text = "Grant Storage Permission",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = onSkip,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("skip_permission_btn"),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.secondary)
        ) {
            Text(text = "Proceed Anyway (Limited Mode)", fontWeight = FontWeight.SemiBold)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RootZExplorerApp(viewModel: FileExplorerViewModel = viewModel()) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // Observe state from ViewModel
    val currentPath by viewModel.currentPath.collectAsStateWithLifecycle()
    val filteredFiles by viewModel.filteredFiles.collectAsStateWithLifecycle()
    val isRootAvailable by viewModel.isRootAvailable.collectAsStateWithLifecycle()
    val isRootActive by viewModel.isRootActive.collectAsStateWithLifecycle()
    val isSafAuthorized by viewModel.isSafAuthorized.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val isExecuting by viewModel.isExecuting.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val terminalLogs by viewModel.terminalLogs.collectAsStateWithLifecycle()
    val zipProgress by viewModel.zipProgress.collectAsStateWithLifecycle()
    val zipEntries by viewModel.inspectingZipEntries.collectAsStateWithLifecycle()
    val activeZipItem by viewModel.activeZipItem.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableStateOf(0) } // 0 = Explorer, 1 = Archive Viewer, 2 = Root Terminal
    var isSearching by remember { mutableStateOf(false) }

    // Dialog triggering states
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showCreateFileDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<FileItem?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf<FileItem?>(null) }
    var showItemActionSheet by remember { mutableStateOf<FileItem?>(null) }

    // Periodically update SAF status
    LaunchedEffect(Unit) {
        viewModel.refreshSafStatus(context)
    }

    val safDirectoryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val treeUri = result.data?.data
            if (treeUri != null) {
                try {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
                    com.example.utils.SafUtils.saveSafUri(context, treeUri)
                    viewModel.refreshSafStatus(context)
                    viewModel.loadDirectory(currentPath, context)
                } catch (e: Exception) {
                    Toast.makeText(context, "Authorization failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Handle toast error notification
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearErrorMessage()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // --- HIGH QUALITY HEADER ---
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Storage,
                                contentDescription = "Logo",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "RootZ Explorer",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = "Standard & root-level offline utility",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Root privilege toggle badge
                    if (isRootAvailable) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isRootActive) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (isRootActive) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { viewModel.setRootActive(!isRootActive) }
                                .testTag("root_power_toggle")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isRootActive) Icons.Filled.Shield else Icons.Filled.Lock,
                                    contentDescription = "Lock",
                                    tint = if (isRootActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isRootActive) "SU ROOT ON" else "SU ROOT OFF",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = if (isRootActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        // Helpful Root Missing Notice
                        AssistChip(
                            onClick = {
                                Toast.makeText(
                                    context,
                                    "No local 'su' found. Operating in standard offline file mode.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            label = { Text("Standard Mode") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                labelColor = MaterialTheme.colorScheme.secondary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Navigation Tabs
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        modifier = Modifier.testTag("tab_explorer")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 12.dp)
                        ) {
                            Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Explorer", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        modifier = Modifier.testTag("tab_archives")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 12.dp)
                        ) {
                            Icon(Icons.Filled.Inventory, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Archives", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        modifier = Modifier.testTag("tab_terminal")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 12.dp)
                        ) {
                            Icon(Icons.Filled.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Terminal", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // --- CORE TAB PANELS ---
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (selectedTab) {
                0 -> ExplorerTabContent(
                    viewModel = viewModel,
                    currentPath = currentPath,
                    filteredFiles = filteredFiles,
                    searchQuery = searchQuery,
                    isSearching = isSearching,
                    onSearchToggle = { isSearching = !isSearching },
                    onItemClick = { fileItem ->
                        if (fileItem.isDirectory) {
                            viewModel.loadDirectory(fileItem.path, context)
                        } else {
                            showItemActionSheet = fileItem
                        }
                    },
                    onItemLongClick = { fileItem ->
                        showItemActionSheet = fileItem
                    },
                    onCreateFolderClick = { showCreateFolderDialog = true },
                    onCreateFileClick = { showCreateFileDialog = true },
                    isExecuting = isExecuting,
                    zipProgress = zipProgress,
                    onRequestSafAuthorize = { subfolder ->
                        try {
                            safDirectoryLauncher.launch(com.example.utils.SafUtils.getSafRequestIntent(subfolder))
                        } catch (e: Exception) {
                            Toast.makeText(context, "An error occurred launching permission window. Make sure you haven't disabled the system Files app.", Toast.LENGTH_LONG).show()
                        }
                    }
                )
                1 -> ArchiveTabContent(
                    viewModel = viewModel,
                    filteredFiles = filteredFiles,
                    zipEntries = zipEntries,
                    activeZipItem = activeZipItem,
                    isExecuting = isExecuting,
                    zipProgress = zipProgress,
                    onInspectRequest = { fileItem ->
                        viewModel.inspectZipFile(fileItem)
                    }
                )
                2 -> ConsoleTerminalTabContent(
                    viewModel = viewModel,
                    logs = terminalLogs,
                    isSuEnabled = isRootActive
                )
            }
        }
    }

    // --- QUICK SHORTCUT ACTION SHEET DIALOG ---
    if (showItemActionSheet != null) {
        val selectedItem = showItemActionSheet!!
        AlertDialog(
            onDismissRequest = { showItemActionSheet = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when {
                            selectedItem.isDirectory -> Icons.Filled.Folder
                            selectedItem.isZipArchive -> Icons.Filled.Inventory
                            else -> Icons.Filled.Description
                        },
                        contentDescription = null,
                        tint = if (selectedItem.isZipArchive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = selectedItem.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Path: ${selectedItem.path}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Size: ${selectedItem.formattedSize}  |  Perms: ${selectedItem.permissions}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    HorizontalDivider()

                    // Browse item
                    if (selectedItem.isDirectory) {
                        ListItem(
                            headlineContent = { Text("Open Directory", fontWeight = FontWeight.SemiBold) },
                            leadingContent = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                            modifier = Modifier
                                .clickable {
                                    showItemActionSheet = null
                                    viewModel.loadDirectory(selectedItem.path, context)
                                }
                                .testTag("action_open_dir")
                        )
                    }

                    // Zip Inspect (For archives)
                    if (selectedItem.isZipArchive) {
                        ListItem(
                            headlineContent = { Text("Inspect ZIP Contents", fontWeight = FontWeight.SemiBold) },
                            leadingContent = { Icon(Icons.Filled.Visibility, contentDescription = null) },
                            modifier = Modifier
                                .clickable {
                                    showItemActionSheet = null
                                    selectedTab = 1
                                    viewModel.inspectZipFile(selectedItem)
                                }
                                .testTag("action_inspect_zip")
                        )

                        ListItem(
                            headlineContent = { Text("Extract / Unzip Here", fontWeight = FontWeight.SemiBold) },
                            leadingContent = { Icon(Icons.Filled.Unarchive, contentDescription = null) },
                            modifier = Modifier
                                .clickable {
                                    showItemActionSheet = null
                                    viewModel.extractZipFile(selectedItem)
                                }
                                .testTag("action_extract_zip")
                        )
                    } else {
                        // Compress to zip
                        ListItem(
                            headlineContent = { Text("Compress to ZIP", fontWeight = FontWeight.SemiBold) },
                            leadingContent = { Icon(Icons.Filled.Archive, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier
                                .clickable {
                                    showItemActionSheet = null
                                    viewModel.compressToZip(selectedItem)
                                }
                                .testTag("action_compress_zip")
                        )
                    }

                    ListItem(
                        headlineContent = { Text("Rename", fontWeight = FontWeight.SemiBold) },
                        leadingContent = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        modifier = Modifier
                            .clickable {
                                showItemActionSheet = null
                                showRenameDialog = selectedItem
                            }
                            .testTag("action_rename_item")
                    )

                    ListItem(
                        headlineContent = { Text("Delete Permanently", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) },
                        leadingContent = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        modifier = Modifier
                            .clickable {
                                showItemActionSheet = null
                                showDeleteConfirmDialog = selectedItem
                            }
                            .testTag("action_delete_item")
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showItemActionSheet = null }) {
                    Text("Close")
                }
            }
        )
    }

    // --- CREATE DIRECTORY DIALOG ---
    if (showCreateFolderDialog) {
        var dirName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("Create New Directory") },
            text = {
                OutlinedTextField(
                    value = dirName,
                    onValueChange = { dirName = it },
                    label = { Text("Directory Name") },
                    placeholder = { Text("e.g. Backups") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("folder_name_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (dirName.isNotBlank()) {
                            viewModel.createDirectory(dirName, context)
                            showCreateFolderDialog = false
                        }
                    },
                    modifier = Modifier.testTag("folder_create_confirm_btn")
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- CREATE FILE DIALOG ---
    if (showCreateFileDialog) {
        var fileName by remember { mutableStateOf("") }
        var fileContent by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFileDialog = false },
            title = { Text("Create Blank File") },
            text = {
                Column {
                    OutlinedTextField(
                        value = fileName,
                        onValueChange = { fileName = it },
                        label = { Text("File Name") },
                        placeholder = { Text("e.g. notes.txt") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("file_name_input")
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = fileContent,
                        onValueChange = { fileContent = it },
                        label = { Text("Initial Content (Optional)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .testTag("file_content_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (fileName.isNotBlank()) {
                            viewModel.createFile(fileName, fileContent, context)
                            showCreateFileDialog = false
                        }
                    },
                    modifier = Modifier.testTag("file_create_confirm_btn")
                ) {
                    Text("Save File")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFileDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- RENAME DIALOG ---
    if (showRenameDialog != null) {
        val target = showRenameDialog!!
        var editName by remember { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text("Rename Item") },
            text = {
                OutlinedTextField(
                    value = editName,
                    onValueChange = { editName = it },
                    label = { Text("New Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rename_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editName.isNotBlank() && editName != target.name) {
                            viewModel.renameItem(target, editName, context)
                            showRenameDialog = null
                        }
                    },
                    modifier = Modifier.testTag("rename_confirm_btn")
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- DELETE CONFIRMATION DIALOG ---
    if (showDeleteConfirmDialog != null) {
        val target = showDeleteConfirmDialog!!
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = null },
            title = { Text("Confirm Deletion", color = MaterialTheme.colorScheme.error) },
            text = {
                Text("Are you sure you want to permanently delete '${target.name}'? This action can NEVER be undone, and will recursively wipe all directories inside.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteItem(target, context)
                        showDeleteConfirmDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("delete_confirm_btn")
                ) {
                    Text("Delete Permanently", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = null }) {
                    Text("Keep File")
                }
            }
        )
    }
}

// ============================================
// EXPLORER VIEW COMPONENT
// ============================================
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExplorerTabContent(
    viewModel: FileExplorerViewModel,
    currentPath: String,
    filteredFiles: List<FileItem>,
    searchQuery: String,
    isSearching: Boolean,
    onSearchToggle: () -> Unit,
    onItemClick: (FileItem) -> Unit,
    onItemLongClick: (FileItem) -> Unit,
    onCreateFolderClick: () -> Unit,
    onCreateFileClick: () -> Unit,
    isExecuting: Boolean,
    zipProgress: Pair<Float, String>?,
    onRequestSafAuthorize: (String?) -> Unit
) {
    val listState = rememberLazyListState()
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        // Search & Filter header bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSearching) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.updateSearchQuery(it) },
                    placeholder = { Text("Filter current files...") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                )
                IconButton(onClick = {
                    viewModel.updateSearchQuery("")
                    onSearchToggle()
                }) {
                    Icon(Icons.Filled.Close, contentDescription = "Close search")
                }
            } else {
                // Directory Breadcrumb Title
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.loadDirectory(Environment.getExternalStorageDirectory().absolutePath, context) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Filled.Home, contentDescription = "Home", modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = currentPath,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                IconButton(onClick = onSearchToggle) {
                    Icon(Icons.Filled.Search, contentDescription = "Search folder")
                }
            }
        }

        // Bookmark row
        BookmarkBar(
            onBookmarkSelect = { path ->
                viewModel.loadDirectory(path, context)
            }
        )

        // Loading or progress bar
        ProgressAndStatusSection(isExecuting = isExecuting, zipProgress = zipProgress)

        // UP Navigation row
        if (currentPath != "/") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable { viewModel.navigateUp(context) }
                    .testTag("nav_up_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowUpward,
                        contentDescription = "Up Icon",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = ".. [Parent Directory]",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }

        // Main Files List
        if (filteredFiles.isEmpty() && !isExecuting) {
            val isDataPath = viewModel.isAndroidDataPath(currentPath)
            val isSafAuth by viewModel.isSafAuthorized.collectAsStateWithLifecycle()
            val isRootActive by viewModel.isRootActive.collectAsStateWithLifecycle()

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isDataPath && !isSafAuth && !isRootActive) {
                    val pkgName = com.example.utils.SafUtils.getPackageNameFromPath(currentPath)
                    var showGlobalGuide by remember { mutableStateOf(false) }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(20.dp)
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Security,
                                contentDescription = "Security Alert",
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            if (pkgName != null) {
                                Text(
                                    text = "Authorize $pkgName Folder",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "To bypass Android 14/15 secure restrictions on the root folder, you can authorize just this selective app folder: 'Android/data/$pkgName'!",
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                Button(
                                    onClick = { onRequestSafAuthorize(pkgName) },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().testTag("auth_single_pkg_btn")
                                ) {
                                    Icon(Icons.Filled.FolderOpen, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Grant Access to This App Folder")
                                }
                            } else {
                                Text(
                                    text = "Access Android/data Securely",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Android 11+ protects this directory from traditional apps. With RootZ Explorer, you can browse, edit, and manage files in this directory instantly either by using Root Privileges or via standard offline SAF approval — absolutely no Wi-Fi, Hotspot, ADB, or debug connections needed!",
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                Button(
                                    onClick = { onRequestSafAuthorize(null) },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().testTag("auth_global_btn")
                                ) {
                                    Icon(Icons.Filled.FolderOpen, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Grant Files Offline Authorization")
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            // Expandable Android 14/15 global bypass troubleshooting guide
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showGlobalGuide = !showGlobalGuide }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (showGlobalGuide) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Android 14 / 15 \"Can't use this folder\" Bypass Guide",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            
                            if (showGlobalGuide) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = "On Android 14 & 15, the system blocks root /Android/data selection, showing \"Can't use this folder\" in black. To bypass this restriction globally:",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = "1. Click the button below to open the system \"Files\" App settings.\n2. Tap the 3 dots in the top-right corner and choose \"Uninstall updates\" (confirm yes).\n3. This rolls back the files app, disabling the block.\n4. Return here, click \"Grant Files Offline Authorization\" above, choose Android/data, and tap \"Use this folder\".\n5. This provides permanent access! You can safely update your Files app from Play Store afterward.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                            lineHeight = 16.sp
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Button(
                                            onClick = {
                                                val packages = listOf("com.google.android.documentsui", "com.android.documentsui")
                                                var launched = false
                                                for (pkg in packages) {
                                                    try {
                                                        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                            data = Uri.parse("package:$pkg")
                                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                        }
                                                        context.startActivity(intent)
                                                        launched = true
                                                        break
                                                    } catch (e: Exception) {
                                                        // retry
                                                    }
                                                }
                                                if (!launched) {
                                                    try {
                                                        val intent = Intent(android.provider.Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS).apply {
                                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                        }
                                                        context.startActivity(intent)
                                                    } catch (e: Exception) {
                                                        Toast.makeText(context, "Please go to Settings > Apps > Files App manually.", Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                            modifier = Modifier.fillMaxWidth().testTag("uninstall_files_updates_btn")
                                        ) {
                                            Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Open Files App Settings", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Directory is empty",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(filteredFiles, key = { it.path }) { item ->
                    FileListItemLayout(
                        item = item,
                        onClick = { onItemClick(item) },
                        onLongClick = { onItemLongClick(item) }
                    )
                }
            }
        }
    }

    // Floating action action with custom items
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.BottomEnd
    ) {
        Column(horizontalAlignment = Alignment.End) {
            SmallFloatingActionButton(
                onClick = onCreateFileClick,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .testTag("fab_create_file")
            ) {
                Icon(Icons.Filled.NoteAdd, contentDescription = "Add File", modifier = Modifier.size(18.dp))
            }
            ExtendedFloatingActionButton(
                onClick = onCreateFolderClick,
                icon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                text = { Text("New Folder") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("fab_create_folder")
            )
        }
    }
}

// ============================================
// ARCHIVE CONTROL CONTENT
// ============================================
@Composable
fun ArchiveTabContent(
    viewModel: FileExplorerViewModel,
    filteredFiles: List<FileItem>,
    zipEntries: List<ZipEntryInfo>?,
    activeZipItem: FileItem?,
    isExecuting: Boolean,
    zipProgress: Pair<Float, String>?,
    onInspectRequest: (FileItem) -> Unit
) {
    val zipFiles = remember(filteredFiles) {
        filteredFiles.filter { it.isZipArchive }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ProgressAndStatusSection(isExecuting = isExecuting, zipProgress = zipProgress)

        if (zipEntries != null && activeZipItem != null) {
            // ZIP ARCHIVE INNER VIEWER PANEL
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Inventory,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = activeZipItem.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.closeZipInspection() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close zip view")
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Compressed items: ${zipEntries.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Size: ${activeZipItem.formattedSize}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { viewModel.extractZipFile(activeZipItem) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Unarchive, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Extract Archive Here", fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Contents:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                            .padding(8.dp)
                    ) {
                        items(zipEntries) { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                                        contentDescription = null,
                                        tint = if (entry.isDirectory) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = entry.name,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = entry.formattedSize,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }

        // Standard ZIP management screen list
        Text(
            text = "Zip Archives found in Current folder:",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp)
        )

        if (zipFiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Icon(
                        imageVector = Icons.Filled.FolderZip,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No ZIP files found here.",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Navigate to standard folders ('Download') or create a zip using the Explorer tab options.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(zipFiles) { zipItem ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .border(1.dp, Color.Gray.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Inventory,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = zipItem.name,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Size: ${zipItem.formattedSize} | Perms: ${zipItem.permissions}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = { onInspectRequest(zipItem) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text("Inspect", color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================
// CONSOLE TERMINAL WORKSPACE
// ============================================
@Composable
fun ConsoleTerminalTabContent(
    viewModel: FileExplorerViewModel,
    logs: List<String>,
    isSuEnabled: Boolean
) {
    var rawCommand by remember { mutableStateOf("") }
    var executeAsSu by remember { mutableStateOf(isSuEnabled) }
    val logsListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val controller = LocalSoftwareKeyboardController.current

    // Auto scroll console log list when logs grow
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            logsListState.animateScrollToItem(logs.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Log console view container
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                .background(Color(0xFF02060C))
                .padding(8.dp)
        ) {
            LazyColumn(
                state = logsListState,
                modifier = Modifier.fillMaxSize()
            ) {
                items(logs) { log ->
                    Text(
                        text = log,
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            log.startsWith("[SUCCESS]") -> Color(0xFF10B981)
                            log.startsWith("[ERR]") || log.contains("fail", ignoreCase = true) -> Color(0xFFEF4444)
                            log.startsWith("[SHELL]") -> Color(0xFF00E5FF)
                            log.startsWith("[ROOT]") -> Color(0xFFFBBF24)
                            else -> Color(0xFF00FF66)
                        },
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Preset command quick actions
        Text(
            text = "Console presets:",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val presets = listOf("id", "whoami", "ls -la /", "mount")
            presets.forEach { label ->
                SuggestionChip(
                    onClick = { viewModel.executeTerminalCommand(label, executeAsSu) },
                    label = { Text(label, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // TextInput for Custom Command shell execution
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = rawCommand,
                onValueChange = { rawCommand = it },
                label = { Text("Enter command") },
                placeholder = { Text("e.g. ls -la /system") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    if (rawCommand.isNotBlank()) {
                        viewModel.executeTerminalCommand(rawCommand, executeAsSu)
                        rawCommand = ""
                        controller?.hide()
                    }
                }),
                modifier = Modifier
                    .weight(1f)
                    .testTag("terminal_cmd_input")
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = {
                    if (rawCommand.isNotBlank()) {
                        viewModel.executeTerminalCommand(rawCommand, executeAsSu)
                        rawCommand = ""
                        controller?.hide()
                    }
                },
                modifier = Modifier
                    .size(52.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                    .testTag("terminal_run_btn")
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Execute cmd",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        // SU permission flag
        if (isSuEnabled) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Checkbox(
                    checked = executeAsSu,
                    onCheckedChange = { executeAsSu = it }
                )
                Text(
                    text = "Request su (Root Privileges)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ============================================
// HELPER COMPONENT CHIPS
// ============================================
@Composable
fun BookmarkBar(onBookmarkSelect: (String) -> Unit) {
    val bookmarks = remember {
        listOf(
            Pair("Root (/) ", "/"),
            Pair("Storage (/sdcard)", Environment.getExternalStorageDirectory().absolutePath),
            Pair("Android Data", Environment.getExternalStorageDirectory().absolutePath + "/Android/data"),
            Pair("Downloads", Environment.getExternalStorageDirectory().absolutePath + "/Download"),
            Pair("System (/system)", "/system"),
            Pair("Cache (/cache)", "/cache")
        )
    }

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(bookmarks) { item ->
            InputChip(
                selected = false,
                onClick = { onBookmarkSelect(item.second) },
                label = { Text(item.first, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                leadingIcon = {
                    Icon(
                        imageVector = when (item.first) {
                            "Root (/) " -> Icons.Filled.Lock
                            "Android Data" -> Icons.Filled.Android
                            else -> Icons.Filled.Folder
                        },
                        contentDescription = null,
                        modifier = Modifier.size(12.dp)
                    )
                }
            )
        }
    }
}

@Composable
fun ProgressAndStatusSection(isExecuting: Boolean, zipProgress: Pair<Float, String>?) {
    if (zipProgress != null) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = zipProgress.second,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${(zipProgress.first * 100).toInt()}%",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { zipProgress.first },
                    modifier = Modifier.fillMaxWidth(),
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }
        }
    } else if (isExecuting) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListItemLayout(
    item: FileItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when {
                    item.isDirectory -> Icons.Filled.Folder
                    item.isZipArchive -> Icons.Filled.Inventory
                    else -> Icons.Filled.Description
                },
                contentDescription = null,
                tint = when {
                    item.isDirectory -> MaterialTheme.colorScheme.secondary
                    item.isZipArchive -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                },
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (item.isDirectory) "Folder" else item.formattedSize,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Perms: ${item.permissions}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

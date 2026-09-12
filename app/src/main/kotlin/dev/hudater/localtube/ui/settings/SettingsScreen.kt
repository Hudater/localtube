package dev.hudater.localtube.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.hudater.localtube.core.model.Source
import dev.hudater.localtube.core.model.SourceType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val sources by viewModel.sources.collectAsState()
    var showAddMenu by remember { mutableStateOf(false) }
    var showSmbDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val pickLocalFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            // Persist BOTH read and write so deleting video files later
            // doesn't hit SecurityException on a read-only grant.
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            viewModel.addLocalSource(uri.toString(), label = uri.lastPathSegment ?: "Local folder")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sources") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddMenu = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add source")
            }
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            items(sources, key = { it.id.value }) { source ->
                SourceRow(
                    source = source,
                    onHiddenToggle = { hidden -> viewModel.setHidden(source, hidden) },
                    onRemove = { viewModel.removeSource(source) },
                )
            }
        }
    }

    if (showAddMenu) {
        AlertDialog(
            onDismissRequest = { showAddMenu = false },
            title = { Text("Add source") },
            text = { Text("Choose a source type") },
            confirmButton = {
                TextButton(onClick = {
                    showAddMenu = false
                    pickLocalFolder.launch(null)
                }) { Text("Local folder") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddMenu = false
                    showSmbDialog = true
                }) { Text("SMB share") }
            },
        )
    }

    if (showSmbDialog) {
        AddSmbSourceDialog(
            onDismiss = { showSmbDialog = false },
            onConfirm = { label, host, share, path, username, password ->
                viewModel.addSmbSource(label, host, share, path, username, password)
                showSmbDialog = false
            },
        )
    }
}

@Composable
private fun SourceRow(
    source: Source,
    onHiddenToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(source.label) },
        supportingContent = {
            Text(if (source.type == SourceType.SMB) "SMB — ${source.smbConnectionInfo?.host}" else "Local folder")
        },
        trailingContent = {
            Row {
                Switch(checked = !source.hidden, onCheckedChange = { visible -> onHiddenToggle(!visible) })
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove source")
                }
            }
        },
    )
}

@Composable
private fun AddSmbSourceDialog(
    onDismiss: () -> Unit,
    onConfirm: (label: String, host: String, share: String, path: String, username: String?, password: String?) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var share by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add SMB share") },
        text = {
            Column {
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Label") })
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host") })
                OutlinedTextField(value = share, onValueChange = { share = it }, label = { Text("Share name") })
                OutlinedTextField(value = path, onValueChange = { path = it }, label = { Text("Path (optional)") })
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username (optional)") })
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password (optional)") })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    label.ifBlank { host },
                    host,
                    share,
                    path,
                    username.ifBlank { null },
                    password.ifBlank { null },
                )
            }) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

package dev.dbexplorer.ui.connections

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dbexplorer.domain.model.ConnectionColor
import dev.dbexplorer.domain.model.SslMode
import dev.dbexplorer.ui.components.ErrorCard
import dev.dbexplorer.ui.components.LoadingBox
import dev.dbexplorer.ui.theme.CodeTextStyle
import dev.dbexplorer.ui.theme.swatch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionEditScreen(onDone: () -> Unit, viewModel: ConnectionEditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.saved.collect { onDone() } }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "New connection" else "Edit connection") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    TextButton(onClick = viewModel::save, enabled = !state.loading && !state.saving) { Text("Save") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        val form = state.form
        val errors = state.errors
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.isNew) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    state.supportedKinds.forEachIndexed { index, kind ->
                        SegmentedButton(
                            selected = form.kind == kind,
                            onClick = { viewModel.setKind(kind) },
                            shape = SegmentedButtonDefaults.itemShape(index, state.supportedKinds.size),
                        ) { Text(kind.displayName) }
                    }
                }
            } else {
                Text(form.kind.displayName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }

            FormField("Name", form.name, { v -> viewModel.update { it.copy(name = v) } }, errors[ConnectionForm.Field.NAME])

            if (form.kind.usesNetwork) {
                NetworkFields(form, errors, viewModel)
            } else {
                SqliteFileField(form, errors[ConnectionForm.Field.FILE], viewModel)
            }

            SectionTitle("Safety")
            ToggleRow("Read-only", "Reject writes at the connection level", form.readOnly) { v ->
                viewModel.update { it.copy(readOnly = v) }
            }
            ToggleRow("Production", "Red banner on every screen of this connection", form.production) { v ->
                viewModel.update {
                    it.copy(
                        production = v,
                        color = if (v &&
                            it.color == ConnectionColor.NONE
                        ) {
                            ConnectionColor.RED
                        } else {
                            it.color
                        },
                    )
                }
            }
            ToggleRow("Block screenshots", "Hide data from screenshots and the recents screen", form.secureScreen) { v ->
                viewModel.update { it.copy(secureScreen = v) }
            }

            SectionTitle("Organisation")
            ColorPicker(form.color) { c -> viewModel.update { it.copy(color = c) } }
            FormField("Folder (optional)", form.folder, { v -> viewModel.update { it.copy(folder = v) } })

            HorizontalDivider()
            TestSection(state.test, onTest = viewModel::testConnection)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkFields(form: ConnectionForm, errors: Map<ConnectionForm.Field, String>, viewModel: ConnectionEditViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FormField(
            "Host",
            form.host,
            { v -> viewModel.update { it.copy(host = v) } },
            errors[ConnectionForm.Field.HOST],
            modifier = Modifier.weight(1f),
            keyboardType = KeyboardType.Uri,
        )
        FormField(
            "Port",
            form.port,
            { v -> viewModel.update { it.copy(port = v.filter(Char::isDigit).take(5)) } },
            errors[ConnectionForm.Field.PORT],
            modifier = Modifier.weight(0.45f),
            keyboardType = KeyboardType.Number,
        )
    }
    FormField("Database", form.database, { v -> viewModel.update { it.copy(database = v) } })
    FormField("Username", form.username, { v -> viewModel.update { it.copy(username = v) } })
    OutlinedTextField(
        value = form.password,
        onValueChange = { v -> viewModel.update { it.copy(password = v, passwordEdited = true) } },
        label = { Text("Password") },
        placeholder = { if (form.hasStoredPassword && !form.passwordEdited) Text("Saved — leave untouched to keep") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
        modifier = Modifier.fillMaxWidth(),
    )
    ToggleRow("Save password", "Encrypted with a hardware-backed Android Keystore key", form.savePassword) { v ->
        viewModel.update { it.copy(savePassword = v) }
    }

    SectionTitle("TLS")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        val modes = SslMode.entries
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = form.sslMode == mode,
                onClick = { viewModel.update { it.copy(sslMode = mode) } },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
            ) {
                Text(
                    when (mode) {
                        SslMode.DISABLE -> "Off"
                        SslMode.REQUIRE -> "Require"
                        SslMode.VERIFY_FULL -> "Verify"
                    },
                )
            }
        }
    }
    Text(form.sslMode.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SqliteFileField(form: ConnectionForm, error: String?, viewModel: ConnectionEditViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importSqliteFile)
    }
    var askName by rememberSaveable { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Database file", style = MaterialTheme.typography.labelLarge)
            Text(
                form.filePath.ifEmpty { "None selected" },
                style = CodeTextStyle,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Opened files are copied into app storage; changes apply to the copy.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Open file…") }
                OutlinedButton(onClick = { askName = true }) { Text("New database") }
            }
        }
    }

    if (askName) {
        var name by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askName = false },
            title = { Text("New SQLite database") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("File name") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    viewModel.createSqliteFile(name.trim())
                    askName = false
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { askName = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TestSection(test: TestState, onTest: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 24.dp)) {
        FilledTonalButton(onClick = onTest, enabled = test != TestState.Running, modifier = Modifier.fillMaxWidth()) {
            if (test == TestState.Running) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text("Test connection")
            }
        }
        when (test) {
            is TestState.Success -> Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null)
                    Column {
                        Text("Connected", style = MaterialTheme.typography.titleSmall)
                        Text(test.serverVersion, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            is TestState.Failure -> ErrorCard(test.message, test.sqlState)
            else -> Unit
        }
    }
}

@Composable
private fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    error: String? = null,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next, autoCorrectEnabled = false),
        modifier = modifier,
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
private fun ColorPicker(selected: ConnectionColor, onSelect: (ConnectionColor) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        ConnectionColor.entries.forEach { color ->
            val swatch = color.swatch()
            val isSelected = color == selected
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(swatch ?: MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        BorderStroke(
                            if (isSelected) 3.dp else 1.dp,
                            if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                        ),
                        CircleShape,
                    )
                    .clickable(role = Role.RadioButton, onClickLabel = color.name.lowercase()) { onSelect(color) },
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = color.name.lowercase(),
                        tint = if (swatch ==
                            null
                        ) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            Color.White
                        },
                    )
                }
            }
        }
    }
}

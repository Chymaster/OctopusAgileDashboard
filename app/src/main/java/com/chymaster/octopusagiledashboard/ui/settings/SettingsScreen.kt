package com.chymaster.octopusagiledashboard.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.chymaster.octopusagiledashboard.core.util.Constants

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    showBackButton: Boolean = true,
    onNavigateToAdvancedSettings: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var passwordVisible by remember { mutableStateOf(false) }
    var gspExpanded by remember { mutableStateOf(false) }
    var serialDropdownExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            snackbarHostState.showSnackbar("Settings saved")
            viewModel.clearSaveSuccess()
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Octopus Energy Credentials",
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = "Optional — needed only for consumption data on the Dashboard",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "Your credentials will never leave your phone other than to authenticate with Octopus Energy, and your data will never leave your phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // API Key
            OutlinedTextField(
                value = uiState.apiKey,
                onValueChange = viewModel::onApiKeyChange,
                label = { Text("API Key") },
                placeholder = { Text("sk_live_...") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (passwordVisible) "Hide" else "Show"
                        )
                    }
                },
                singleLine = true
            )

            // MPAN
            OutlinedTextField(
                value = uiState.mpan,
                onValueChange = viewModel::onMpanChange,
                label = { Text("MPAN (Meter Point Administration Number)") },
                placeholder = { Text("13-digit number") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            // Serial Number — auto-fetched or manual
            when {
                // Loading: API key + MPAN saved, fetching serials from API
                uiState.isFetchingSerials -> {
                    OutlinedTextField(
                        value = "Fetching meter serial number...",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Meter Serial Number") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = false,
                        trailingIcon = {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp).height(16.dp)
                            )
                        },
                        singleLine = true
                    )
                }
                // Multiple serials returned — user picks via dropdown
                uiState.serialNumbers.size > 1 -> {
                    ExposedDropdownMenuBox(
                        expanded = serialDropdownExpanded,
                        onExpandedChange = { serialDropdownExpanded = !serialDropdownExpanded }
                    ) {
                        OutlinedTextField(
                            value = uiState.serialNumber.ifBlank { "Select a meter" },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Meter Serial Number") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = serialDropdownExpanded)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                            singleLine = true
                        )
                        ExposedDropdownMenu(
                            expanded = serialDropdownExpanded,
                            onDismissRequest = { serialDropdownExpanded = false }
                        ) {
                            uiState.serialNumbers.forEach { serial ->
                                DropdownMenuItem(
                                    text = { Text(serial) },
                                    onClick = {
                                        viewModel.onSerialNumberSelected(serial)
                                        serialDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                // Single serial auto-selected — show read-only
                uiState.serialNumbers.size == 1 -> {
                    OutlinedTextField(
                        value = uiState.serialNumber,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Meter Serial Number") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = {
                            Text("Auto-populated from your MPAN")
                        }
                    )
                }
                // Error fetching serials — allow manual entry with error hint
                uiState.serialFetchError != null -> {
                    OutlinedTextField(
                        value = uiState.serialNumber,
                        onValueChange = viewModel::onSerialNumberChange,
                        label = { Text("Meter Serial Number") },
                        modifier = Modifier.fillMaxWidth(),
                        isError = true,
                        supportingText = {
                            Text(uiState.serialFetchError ?: "")
                        },
                        singleLine = true
                    )
                }
                // No API key/MPAN saved yet, no existing serial — disabled
                uiState.serialNumber.isBlank() -> {
                    OutlinedTextField(
                        value = "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Meter Serial Number") },
                        placeholder = { Text("Auto-fetched after save") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = false,
                        supportingText = {
                            Text("Save your API key and MPAN to auto-populate")
                        },
                        singleLine = true
                    )
                }
                // Existing serial number from previous session — editable
                else -> {
                    OutlinedTextField(
                        value = uiState.serialNumber,
                        onValueChange = viewModel::onSerialNumberChange,
                        label = { Text("Meter Serial Number") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }

            // Region (GSP) dropdown
            ExposedDropdownMenuBox(
                expanded = gspExpanded,
                onExpandedChange = { gspExpanded = it }
            ) {
                OutlinedTextField(
                    value = if (uiState.gsp.isNotBlank()) uiState.gsp else "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Region (GSP Group)") },
                    placeholder = { Text("Select region") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = gspExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    expanded = gspExpanded,
                    onDismissRequest = { gspExpanded = false }
                ) {
                    Constants.GSP_GROUPS.forEach { gsp ->
                        DropdownMenuItem(
                            text = { Text(gsp) },
                            onClick = {
                                viewModel.onGspChange(gsp)
                                gspExpanded = false
                            }
                        )
                    }
                }
            }

            // Product Code
            OutlinedTextField(
                value = uiState.productCode,
                onValueChange = viewModel::onProductCodeChange,
                label = { Text("Product Code") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Tariff Code (derived, read-only)
            if (uiState.tariffCode.isNotBlank()) {
                OutlinedTextField(
                    value = uiState.tariffCode,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Tariff Code (derived)") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = false
                )
            }

            // Flexible Product Code
            OutlinedTextField(
                value = uiState.flexibleProductCode,
                onValueChange = viewModel::onFlexibleProductCodeChange,
                label = { Text("Flexible Product Code") },
                placeholder = { Text("OCTOPUS-FLEXIBLE-22-11-01") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = viewModel::testConnection,
                    modifier = Modifier.weight(1f),
                    enabled = uiState.connectionTestState !is ConnectionTestState.Testing
                ) {
                    if (uiState.connectionTestState is ConnectionTestState.Testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp).height(16.dp)
                        )
                    }
                    Text(
                        when (uiState.connectionTestState) {
                            ConnectionTestState.Testing -> "Testing..."
                            ConnectionTestState.Success -> "✓ Connected"
                            is ConnectionTestState.Error -> "✗ Failed"
                            ConnectionTestState.Idle -> "Test Connection"
                        }
                    )
                }

                Button(
                    onClick = viewModel::save,
                    modifier = Modifier.weight(1f),
                    enabled = !uiState.isSaving
                ) {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp).height(16.dp)
                        )
                    }
                    Text("Save")
                }
            }

            // Connection test error
            val testState = uiState.connectionTestState
            if (testState is ConnectionTestState.Error) {
                Text(
                    text = testState.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Divider
            androidx.compose.material3.HorizontalDivider()

            // Advanced Settings
            androidx.compose.material3.ListItem(
                headlineContent = { Text("Advanced Settings") },
                supportingContent = {
                    Text(
                        "Price colour thresholds, display options",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToAdvancedSettings() }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

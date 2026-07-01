package com.example.octopusdashboard.ui.settings

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
import com.example.octopusdashboard.core.util.Constants

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    showBackButton: Boolean = true,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var passwordVisible by remember { mutableStateOf(false) }
    var gspExpanded by remember { mutableStateOf(false) }

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

            // Serial Number
            OutlinedTextField(
                value = uiState.serialNumber,
                onValueChange = viewModel::onSerialNumberChange,
                label = { Text("Meter Serial Number") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

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

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

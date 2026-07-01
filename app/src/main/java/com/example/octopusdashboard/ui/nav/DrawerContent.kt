package com.example.octopusdashboard.ui.nav

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun DrawerContent(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    onCloseDrawer: () -> Unit,
    modifier: Modifier = Modifier
) {
    ModalDrawerSheet(modifier = modifier) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Octopus Dashboard",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()

        Spacer(modifier = Modifier.height(8.dp))

        NavigationDrawerItem(
            label = { Text("Home") },
            icon = { Icon(Icons.Default.Home, contentDescription = null) },
            selected = currentRoute == Routes.HOME,
            onClick = {
                onNavigate(Routes.HOME)
                onCloseDrawer()
            }
        )

        NavigationDrawerItem(
            label = { Text("Dashboard") },
            icon = { Icon(Icons.Default.BarChart, contentDescription = null) },
            selected = currentRoute == Routes.DASHBOARD,
            onClick = {
                onNavigate(Routes.DASHBOARD)
                onCloseDrawer()
            }
        )

        NavigationDrawerItem(
            label = { Text("Future Prices") },
            icon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
            selected = currentRoute == Routes.FUTURE_PRICES,
            onClick = {
                onNavigate(Routes.FUTURE_PRICES)
                onCloseDrawer()
            }
        )

        NavigationDrawerItem(
            label = { Text("Settings") },
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            selected = currentRoute == Routes.SETTINGS,
            onClick = {
                onNavigate(Routes.SETTINGS)
                onCloseDrawer()
            }
        )
    }
}

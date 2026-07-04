package com.chymaster.octopusagiledashboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.chymaster.octopusagiledashboard.data.prefs.UserPreferencesRepository
import com.chymaster.octopusagiledashboard.ui.nav.AppNavGraph
import com.chymaster.octopusagiledashboard.ui.theme.OctopusDashboardTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var preferencesRepository: UserPreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OctopusDashboardTheme {
                val navController = rememberNavController()
                AppNavGraph(
                    preferencesRepository = preferencesRepository,
                    navController = navController
                )
            }
        }
    }
}

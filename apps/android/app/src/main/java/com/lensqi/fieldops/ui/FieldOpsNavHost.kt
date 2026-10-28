package com.lensqi.fieldops.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lensqi.fieldops.ui.connect.ConnectScreen

@Composable
fun FieldOpsNavHost(modifier: Modifier = Modifier) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "connect", modifier = modifier) {
        composable("connect") { ConnectScreen(onAssetChosen = { _, _ -> }) }
    }
}

package com.lensqi.fieldops.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lensqi.fieldops.ui.camera.CameraScreen
import com.lensqi.fieldops.ui.connect.ConnectScreen
import com.lensqi.fieldops.ui.inspection.InspectionScreen
import com.lensqi.fieldops.ui.inspection.InspectionViewModel
import com.lensqi.fieldops.ui.receipt.ReceiptScreen

@Composable
fun FieldOpsNavHost(modifier: Modifier = Modifier) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "connect", modifier = modifier) {
        composable("connect") {
            ConnectScreen(onInspectionCreated = { id -> nav.navigate("inspection/$id") })
        }
        composable("inspection/{inspectionId}", arguments = listOf(navArgument("inspectionId") { type = NavType.StringType })) {
            InspectionScreen(
                onCapture = { nav.navigate("camera") },
                onOpenReceipt = { url -> nav.navigate("receipt?url=${Uri.encode(url)}") },
            )
        }
        composable("camera") { entry ->
            // The capture belongs to the inspection screen underneath. Pin that entry once: after popBackStack the
            // previous entry becomes the connect screen, which has no inspection id.
            val parent = remember(entry) { nav.previousBackStackEntry } ?: return@composable
            val vm: InspectionViewModel = hiltViewModel(parent)
            CameraScreen(onCaptured = { file ->
                vm.queuePhoto(file, origin = "Phone")
                nav.popBackStack()
            })
        }
        composable("receipt?url={url}", arguments = listOf(navArgument("url") { type = NavType.StringType })) { entry ->
            ReceiptScreen(requireNotNull(entry.arguments?.getString("url")))
        }
    }
}

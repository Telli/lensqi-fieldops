package com.lensqi.fieldops.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lensqi.fieldops.ui.camera.CameraScreen
import com.lensqi.fieldops.ui.home.AssetsScreen
import com.lensqi.fieldops.ui.home.HomeScreen
import com.lensqi.fieldops.ui.home.HomeViewModel
import com.lensqi.fieldops.ui.home.InspectionsScreen
import com.lensqi.fieldops.ui.home.ProfileScreen
import com.lensqi.fieldops.ui.home.StartScreen
import com.lensqi.fieldops.ui.inspection.InspectionScreen
import com.lensqi.fieldops.ui.inspection.InspectionViewModel
import com.lensqi.fieldops.ui.inspection.ReviewScreen
import com.lensqi.fieldops.ui.receipt.ReceiptScreen
import com.lensqi.fieldops.ui.theme.Fo

private data class Tab(val route: String, val label: String, val icon: ImageVector)
private val tabs = listOf(
    Tab("home", "Home", Icons.Outlined.Home),
    Tab("inspections", "Inspections", Icons.Outlined.Assignment),
    Tab("start", "Scan", Icons.Outlined.QrCodeScanner),
    Tab("assets", "Assets", Icons.Outlined.Inventory2),
    Tab("profile", "Profile", Icons.Outlined.Person),
)

@Composable
fun FieldOpsNavHost(modifier: Modifier = Modifier) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val home: HomeViewModel = hiltViewModel()
    val openInspection: (String) -> Unit = { id -> nav.navigate("inspection/$id") }
    val goTab: (String) -> Unit = { r ->
        if (r == "home" || r == "inspections") home.refresh()
        nav.navigate(r) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true }
    }
    Column(modifier) {
        Box(Modifier.weight(1f)) {
            NavHost(nav, startDestination = "home") {
                composable("home") { HomeScreen(home, onStart = { goTab("start") }, onOpen = openInspection, onAll = { goTab("inspections") }) }
                composable("inspections") { InspectionsScreen(home, openInspection) }
                composable("start") { StartScreen(home) { id -> nav.navigate("inspection/$id") { popUpTo("home") } ; nav.navigate("camera") } }
                composable("assets") { AssetsScreen(home) { f, a -> home.create(f, a) { id -> nav.navigate("inspection/$id") } } }
                composable("profile") { ProfileScreen(home, openInspection) }
                composable("inspection/{inspectionId}", arguments = listOf(navArgument("inspectionId") { type = NavType.StringType })) {
                    InspectionScreen(
                        onBack = { home.refresh(); nav.popBackStack() },
                        onCapture = { nav.navigate("camera") },
                        onReview = { nav.navigate("review") },
                        onOpenReceipt = { url -> nav.navigate("receipt?url=${Uri.encode(url)}") },
                    )
                }
                composable("camera") { e ->
                    // The capture belongs to the inspection underneath. Pin that entry once: after popBackStack the
                    // previous entry changes and has no inspection id.
                    val parent = remember(e) { nav.previousBackStackEntry } ?: return@composable
                    val vm: InspectionViewModel = hiltViewModel(parent)
                    CameraScreen(
                        prompt = vm.cameraPrompt(),
                        onClose = { nav.popBackStack() },
                        onCaptured = { file ->
                            vm.captureForReview(file, origin = "Phone")
                            nav.navigate("review") { popUpTo("camera") { inclusive = true } }
                        },
                    )
                }
                composable("review") { e ->
                    val parent = remember(e) { nav.previousBackStackEntry } ?: return@composable
                    val vm: InspectionViewModel = hiltViewModel(parent)
                    ReviewScreen(vm, onContinue = { nav.popBackStack() }, onRetake = { nav.navigate("camera") { popUpTo("review") { inclusive = true } } })
                }
                composable("receipt?url={url}", arguments = listOf(navArgument("url") { type = NavType.StringType })) { e ->
                    ReceiptScreen(requireNotNull(e.arguments?.getString("url")), onBack = { nav.popBackStack() })
                }
            }
        }
        if (route in tabs.map { it.route }) BottomBar(route) { goTab(it) }
    }
}

@Composable
private fun BottomBar(current: String?, onSelect: (String) -> Unit) {
    Column(Modifier.background(Fo.Background.copy(alpha = 0.95f)).navigationBarsPadding()) {
        HorizontalDivider(color = Fo.Border)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
            tabs.forEach { t ->
                val active = t.route == current
                Column(Modifier.weight(1f).clickable { onSelect(t.route) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (t.route == "start") {
                        Box(
                            Modifier.offset(y = (-18).dp).size(56.dp).border(4.dp, Fo.Background, RoundedCornerShape(18.dp)).background(Fo.Primary, RoundedCornerShape(18.dp)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(t.icon, "Start an inspection", tint = Fo.OnPrimary, modifier = Modifier.size(26.dp)) }
                        Text(t.label, color = Fo.Primary, fontSize = 10.sp, fontWeight = FontWeight.Medium, modifier = Modifier.offset(y = (-14).dp))
                    } else {
                        Icon(t.icon, t.label, tint = if (active) Fo.Foreground else Fo.Muted, modifier = Modifier.size(22.dp))
                        Text(t.label, color = if (active) Fo.Foreground else Fo.Muted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

package com.lensqi.fieldops.ui

import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lensqi.fieldops.ui.camera.CameraScreen
import com.lensqi.fieldops.ui.common.stageLabel
import com.lensqi.fieldops.ui.glasses.GlassesScreen
import com.lensqi.fieldops.ui.home.AssetDetailScreen
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
import com.lensqi.fieldops.data.api.TaskMode
import com.lensqi.fieldops.data.api.TaskPhase
import com.lensqi.fieldops.ui.task.FreeformScreen
import com.lensqi.fieldops.ui.task.NewTaskScreen
import com.lensqi.fieldops.ui.task.PlanScreen
import com.lensqi.fieldops.ui.task.StepScreen
import com.lensqi.fieldops.ui.task.TaskDoneScreen
import com.lensqi.fieldops.ui.task.TaskHomeScreen
import com.lensqi.fieldops.ui.task.TaskListViewModel
import com.lensqi.fieldops.ui.task.TaskViewModel
import com.lensqi.fieldops.wearables.LiveSession
import com.lensqi.fieldops.wearables.LiveSessionKind
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Build
import com.lensqi.fieldops.ui.theme.Fo

private data class Tab(val route: String, val label: String, val icon: ImageVector)
// Three destinations: home (DIY and recent tasks), a new task, technician work. Assets and inspections open from Technician.
private val tabs = listOf(
    Tab("taskHome", "Home", Icons.Outlined.Home),
    Tab("newTask", "New task", Icons.Outlined.Add),
    Tab("home", "Technician", Icons.Outlined.Build),
)
private const val NEW_TASK = "newTask?mode={mode}&goal={goal}"
private val withBar = setOf("taskHome", NEW_TASK, "home", "start", "assets", "inspections", "profile")

@Composable
fun FieldOpsNavHost(modifier: Modifier = Modifier) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val home: HomeViewModel = hiltViewModel()
    val taskList: TaskListViewModel = hiltViewModel()
    val openInspection: (String) -> Unit = { id -> nav.navigate("inspection/$id") }
    val goTab: (String) -> Unit = { r ->
        if (r == "home" || r == "inspections") home.refresh()
        if (r == "taskHome") taskList.refresh()
        // The start tab is the root: return to it. Restoring its saved stack would reopen whatever was pushed on it (Profile).
        if (r == "taskHome") nav.popBackStack("taskHome", inclusive = false)
        else nav.navigate(r) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true }
    }
    Column(modifier) {
        Box(Modifier.weight(1f)) {
            NavHost(nav, startDestination = "taskHome") {
                composable("taskHome") {
                    TaskHomeScreen(taskList,
                        // One step from goal to plan; details and manual photos stay optional on the full form.
                        onPlan = { goal -> taskList.create(TaskMode.Diy, goal, null, null, null, emptyList()) { id -> nav.navigate("task/$id") } },
                        onDetails = { goal -> nav.navigate("newTask?mode=Diy&goal=${Uri.encode(goal)}") },
                        onJustAsk = { taskList.justAsk { id -> nav.navigate("task/$id/live") } },
                        onTechnician = { goTab("home") },
                        onOpen = { t -> nav.navigate(com.lensqi.fieldops.ui.task.taskRoute(t)) },
                        onProfile = { nav.navigate("profile") }, onTeaching = { nav.navigate("teaching") })
                }
                composable("teaching?taskId={taskId}", arguments = listOf(navArgument("taskId") { type = NavType.StringType; defaultValue = "" })) {
                    com.lensqi.fieldops.teaching.TeachingScreen(hiltViewModel(), onBack = { nav.popBackStack() })
                }
                composable(NEW_TASK, arguments = listOf(
                    navArgument("mode") { type = NavType.StringType; defaultValue = "Diy" },
                    navArgument("goal") { type = NavType.StringType; defaultValue = "" },
                )) { e ->
                    val fixtures = home.state.collectAsStateWithLifecycle().value.fixtures
                    NewTaskScreen(taskList, fixtures, if (e.arguments?.getString("mode") == "Technician") TaskMode.Technician else TaskMode.Diy, e.arguments?.getString("goal").orEmpty(),
                        onBack = { nav.popBackStack() }, onCreated = { id -> nav.navigate("task/$id") { popUpTo("taskHome") } })
                }
                composable("task/{taskId}", arguments = listOf(navArgument("taskId") { type = NavType.StringType })) { e ->
                    val id = requireNotNull(e.arguments?.getString("taskId"))
                    PlanScreen(hiltViewModel(), onBack = { taskList.refresh(); nav.popBackStack() }, onStep = { nav.navigate("task/$id/step") }, onDone = { nav.navigate("task/$id/done") }, onTeaching = { nav.navigate("teaching?taskId=$id") })
                }
                composable("task/{taskId}/step", arguments = listOf(navArgument("taskId") { type = NavType.StringType })) { e ->
                    val id = requireNotNull(e.arguments?.getString("taskId"))
                    // Close goes to the plan (as on the web), where the task can be stopped, also when the list opened the step directly.
                    StepScreen(hiltViewModel(), onGlasses = { nav.navigate("task/$id/glasses") }, onClose = {
                        if (nav.previousBackStackEntry?.destination?.route == "task/{taskId}") nav.popBackStack()
                        else nav.navigate("task/$id") { popUpTo("task/{taskId}/step") { inclusive = true } }
                    }, onDone = { nav.navigate("task/$id/done") { popUpTo("task/{taskId}/step") { inclusive = true } } })
                }
                composable("task/{taskId}/glasses", arguments = listOf(navArgument("taskId") { type = NavType.StringType })) { e ->
                    // GT-R12.2: glasses serve the task's current step. Pinned to the step screen's view model, like "camera";
                    // a glasses photo becomes that step's check (a suggestion, never evidence).
                    val parent = remember(e) { nav.previousBackStackEntry } ?: return@composable
                    val vm: TaskViewModel = hiltViewModel(parent)
                    val t = vm.state.collectAsStateWithLifecycle().value.task
                    val step = t?.currentStep ?: return@composable
                    val n = t.plan?.steps?.indexOfFirst { it.id == step.id } ?: 0
                    GlassesScreen(
                        session = LiveSession(vm.taskId, LiveSessionKind.Task, step.lookFor.firstOrNull()),
                        prompt = step.instruction,
                        status = "Step ${n + 1} of ${t.plan?.steps?.size ?: 1} · ${step.title}",
                        onClose = { nav.popBackStack() },
                        onCaptured = { file, _ -> vm.check(step.id, file); nav.popBackStack() },
                    )
                }
                composable("task/{taskId}/live", arguments = listOf(navArgument("taskId") { type = NavType.StringType })) {
                    FreeformScreen(hiltViewModel(), onClose = { taskList.refresh(); nav.popBackStack() },
                        onPlan = { goal -> nav.navigate("newTask?mode=Diy&goal=${Uri.encode(goal)}") })
                }
                composable("task/{taskId}/done", arguments = listOf(navArgument("taskId") { type = NavType.StringType })) {
                    TaskDoneScreen(hiltViewModel(), onHome = { goTab("taskHome") }, onInspection = openInspection)
                }
                composable("home") { HomeScreen(home, onStart = { goTab("start") }, onAssets = { nav.navigate("assets") }, onOpen = openInspection, onAll = { nav.navigate("inspections") }, onProfile = { nav.navigate("profile") }) }
                composable("inspections") { InspectionsScreen(home, openInspection) }
                composable("start") { StartScreen(home) { id -> nav.navigate("inspection/$id") { popUpTo("start") { inclusive = true } } ; nav.navigate("camera") } }
                composable("assets") { AssetsScreen(home) { assetId -> nav.navigate("asset/$assetId") } }
                composable("asset/{assetId}", arguments = listOf(navArgument("assetId") { type = NavType.StringType })) { e ->
                    AssetDetailScreen(
                        home, requireNotNull(e.arguments?.getString("assetId")), onBack = { nav.popBackStack() },
                        onStart = { f, a -> home.create(f, a) { id -> nav.navigate("inspection/$id") } }, onOpen = openInspection,
                    )
                }
                composable("profile") { ProfileScreen(home, openInspection) }
                composable("inspection/{inspectionId}", arguments = listOf(navArgument("inspectionId") { type = NavType.StringType })) {
                    InspectionScreen(
                        onBack = { home.refresh(); nav.popBackStack() },
                        onCapture = { nav.navigate("camera") },
                        onReview = { nav.navigate("review") },
                        onGlasses = { nav.navigate("glasses") },
                        onOpenReceipt = { url -> nav.navigate("receipt?url=${Uri.encode(url)}") },
                    )
                }
                composable("camera") { e ->
                    // The capture belongs to the inspection underneath. Pin that entry once: after popBackStack the
                    // previous entry changes and has no inspection id.
                    val parent = remember(e) { nav.previousBackStackEntry } ?: return@composable
                    val vm: InspectionViewModel = hiltViewModel(parent)
                    val ui = vm.state.collectAsStateWithLifecycle().value
                    // Capture help for the post-repair photo comes only on the server's CapturePhoto step: the part it
                    // compares and its before photo as a framing aid. Before a repair, the part the AI asked to see.
                    val guide = com.lensqi.fieldops.domain.captureGuide(ui.view)
                    val reference by produceState<android.graphics.Bitmap?>(null, guide?.referencePhotoId) { value = guide?.referencePhotoId?.let { vm.photoForDisplay(it) } }
                    CameraScreen(
                        prompt = vm.cameraPrompt(),
                        analyzeLive = vm::analyzeLiveFrame,
                        // The confirmed part is remembered across captures and retakes.
                        targetConcept = guide?.concept ?: com.lensqi.fieldops.ui.inspection.targetConcept(ui.fixture, ui.latestAssessment),
                        capturePart = guide?.concept,
                        reference = reference,
                        registration = remember(ui.fixture, ui.view?.assetId) { com.lensqi.fieldops.ui.inspection.registrationSubject(ui.fixture, ui.view?.assetId) },
                        scopeKey = vm.inspectionId,
                        targetMemory = vm.spatialTarget,
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
                composable("glasses") { e ->
                    // Same pinning as "camera": the photo belongs to the inspection underneath.
                    val parent = remember(e) { nav.previousBackStackEntry } ?: return@composable
                    val vm: InspectionViewModel = hiltViewModel(parent)
                    val stage = vm.state.collectAsStateWithLifecycle().value.view?.stage
                    GlassesScreen(
                        session = LiveSession(vm.inspectionId, LiveSessionKind.Inspection),
                        prompt = vm.cameraPrompt(),
                        status = stage?.let { stageLabel(it) },
                        onClose = { nav.popBackStack() },
                        // origin is "Glasses" for real glasses and "MockWearables" for the Mock Device Kit, never "Phone".
                        onCaptured = { file, origin ->
                            vm.captureForReview(file, origin = origin)
                            nav.navigate("review") { popUpTo("glasses") { inclusive = true } }
                        },
                    )
                }
                composable("receipt?url={url}", arguments = listOf(navArgument("url") { type = NavType.StringType })) { e ->
                    ReceiptScreen(requireNotNull(e.arguments?.getString("url")), onBack = { nav.popBackStack() })
                }
            }
        }
        if (route in withBar) BottomBar(route) { goTab(it) }
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
                    if (t.route == "newTask") {
                        Box(
                            Modifier.offset(y = (-20).dp).size(64.dp).border(6.dp, Fo.Background, CircleShape).padding(6.dp).background(Fo.Primary, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Icon(t.icon, "Start a new task", tint = Fo.OnPrimary, modifier = Modifier.size(26.dp)) }
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

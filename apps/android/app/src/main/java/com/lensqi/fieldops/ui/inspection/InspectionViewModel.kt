package com.lensqi.fieldops.ui.inspection

import com.lensqi.fieldops.ui.theme.Fo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lensqi.fieldops.data.InspectionCache
import com.lensqi.fieldops.data.SnapshotStore
import com.lensqi.fieldops.data.api.ApiOutcome
import com.lensqi.fieldops.data.api.ChecklistRequest
import com.lensqi.fieldops.data.api.ChecklistStatus
import com.lensqi.fieldops.data.api.DiagnosticSource
import com.lensqi.fieldops.data.api.FieldOpsApi
import com.lensqi.fieldops.data.api.Fixture
import com.lensqi.fieldops.data.api.InspectionView
import com.lensqi.fieldops.data.api.InvestigateResponse
import com.lensqi.fieldops.data.api.KnowledgeResponse
import com.lensqi.fieldops.data.api.ReceiptVerification
import com.lensqi.fieldops.data.api.RepairClaimRequest
import com.lensqi.fieldops.data.api.SegmentRequest
import com.lensqi.fieldops.data.api.Segmentation
import com.lensqi.fieldops.data.api.SimulationRequest
import com.lensqi.fieldops.data.api.WorkOrderRequest
import com.lensqi.fieldops.data.api.apiCall
import com.lensqi.fieldops.data.api.ProposedNextAction
import com.lensqi.fieldops.data.api.VerificationResult
import com.lensqi.fieldops.data.api.VisualAssessment
import com.lensqi.fieldops.data.api.PhotoEvidence
import com.lensqi.fieldops.ui.common.AssetMatch
import com.lensqi.fieldops.ui.common.assetMatch
import com.lensqi.fieldops.ui.common.sentence
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.lensqi.fieldops.data.queue.CommandQueue
import com.lensqi.fieldops.data.queue.PendingCommand
import com.lensqi.fieldops.data.settings.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject

/** Everything the inspection screen draws. Stage, decision and labels come from the server; nothing is derived locally. */
enum class StepState { Todo, Doing, Done, Skipped }
data class ReviewStep(val label: String, val state: StepState)

/** The post-capture review: the frozen frame, what the server is doing with it, and what came back. */
data class Review(
    val file: File,
    val origin: String,
    val afterRepair: Boolean,
    val steps: List<ReviewStep>,
    val photoId: String? = null,
    val awaitingUpload: Boolean = false,
    val assetSeen: String? = null,
    /** Whether LensQi saw the inspection's asset; null until it has looked. */
    val assetMatch: AssetMatch? = null,
    val done: Boolean = false,
)

data class InspectionUi(
    val view: InspectionView? = null,
    val fixture: Fixture? = null,
    val diagnosticsMode: DiagnosticSource? = null,
    val pending: List<PendingCommand> = emptyList(),
    val artifacts: List<String> = emptyList(),
    val assessment: InvestigateResponse? = null,
    val segmentation: Segmentation? = null,
    val segmentationImage: Bitmap? = null,
    val knowledge: KnowledgeResponse? = null,
    /** Set when the knowledge shown is the on-device copy because the API is unreachable. */
    val knowledgeCachedAt: String? = null,
    /** Set when the inspection view shown is the on-device copy because the API is unreachable. */
    val viewCachedAt: String? = null,
    val receiptCheck: ReceiptVerification? = null,
    /** Null until the technician edits it; the screen then falls back to Muse's or the fixture's suggestion. */
    val concept: String? = null,
    val workOrderText: String = "Reseat the yellow cable at port 2",
    val claimText: String = "Reseated the yellow cable at port 2",
    /** Latest AI assessment, read from the event trail so it survives restarts. */
    val latestAssessment: VisualAssessment? = null,
    /** Every verification decision so far, oldest first. */
    val attempts: List<VerificationResult> = emptyList(),
    /** Demo control state of the simulated endpoint (Simulated mode only). */
    val simUp: Boolean? = null,
    /** Newest matched outline per part for the latest photo, and that photo's pixels. */
    val outlines: List<Segmentation> = emptyList(),
    val outlinePhotoId: String? = null,
    val outlineImage: Bitmap? = null,
    val review: Review? = null,
    val busy: String? = null,
    val error: String? = null,
)

@HiltViewModel
class InspectionViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val api: FieldOpsApi,
    private val cache: InspectionCache,
    private val queue: CommandQueue,
    private val settings: SettingsStore,
    private val snapshots: SnapshotStore,
    private val json: Json,
) : ViewModel() {
    val inspectionId: String = checkNotNull(savedState["inspectionId"])
    suspend fun analyzeLiveFrame(kind: String, bytes: ByteArray, frameId: String): com.lensqi.fieldops.domain.live.LiveResponse {
        return api.liveFrame(inspectionId, kind,
            okhttp3.MultipartBody.Part.createFormData("image", "preview.jpg", bytes.toRequestBody("image/jpeg".toMediaType())),
            frameId.toRequestBody("text/plain".toMediaType()))
    }
    private val local = MutableStateFlow(InspectionUi())
    private var running: Job? = null

    val state: StateFlow<InspectionUi> = combine(local, cache.observe(inspectionId), queue.observe(inspectionId)) { ui, view, pending ->
        ui.copy(view = view ?: ui.view, pending = pending)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InspectionUi())

    init {
        viewModelScope.launch {
            (apiCall { api.health() } as? ApiOutcome.Ok)?.let { h -> local.update { it.copy(diagnosticsMode = h.value.diagnosticsMode) } }
            val view = (apiCall { api.inspection(inspectionId) } as? ApiOutcome.Ok)?.value?.also(cache::put)
            refresh()
            (apiCall { api.fixtures() } as? ApiOutcome.Ok)?.value?.firstOrNull { it.fixtureId == view?.fixtureId }?.let { f -> local.update { it.copy(fixture = f) } }
            loadKnowledge()
        }
        // A capture made while offline drains as soon as the screen opens with a connection.
        queue.requestSync()
        // Every server view is kept on the device so the screen still shows the last known state offline.
        viewModelScope.launch {
            cache.observe(inspectionId).collect { v -> if (v != null) snapshots.save(viewKey, InspectionView.serializer(), v) }
        }
        // New photos (from the queue or another client) need their artifact names for thumbnails.
        viewModelScope.launch {
            cache.observe(inspectionId).map { v -> v?.photos?.map { it.evidenceId }.orEmpty() }.distinctUntilChanged().collect { ids ->
                if (ids.any { id -> local.value.artifacts.none { it.startsWith("$id-") } })
                    (apiCall { api.artifacts(inspectionId) } as? ApiOutcome.Ok)?.let { a -> local.update { it.copy(artifacts = a.value) } }
            }
        }
    }

    fun artifactUrl(name: String): String = settings.currentBase().resolve("v1/inspections/$inspectionId/artifacts/$name").toString()
    /** The designed receipt page served by the API (embed mode: the app draws its own top bar). */
    fun receiptUrl(): String = settings.currentBase().resolve("m/i/$inspectionId/receipt?embed=1&theme=${if (Fo.isDark) "dark" else "light"}").toString()
    fun receiptJsonUrl(): String = settings.currentBase().resolve("v1/inspections/$inspectionId/receipt").toString()
    fun artifactFor(evidenceId: String): String? = state.value.artifacts.firstOrNull { it.startsWith("$evidenceId-") }

    fun refresh() = viewModelScope.launch {
        when (val r = apiCall { api.inspection(inspectionId) }) {
            is ApiOutcome.Ok -> {
                cache.put(r.value)
                local.update { it.copy(viewCachedAt = null) }
                if (local.value.knowledgeCachedAt != null) loadKnowledge()
            }
            is ApiOutcome.Unreachable -> {
                report(r)
                if (state.value.view == null) snapshots.load(viewKey, InspectionView.serializer())?.let { snap ->
                    local.update { it.copy(view = snap.value, viewCachedAt = snap.fetchedAt) }
                }
            }
            else -> report(r)
        }
        (apiCall { api.artifacts(inspectionId) } as? ApiOutcome.Ok)?.let { a -> local.update { it.copy(artifacts = a.value) } }
        loadTrail()
    }

    /** Assessment and verification history from the events, the stored outline of the latest photo, and the demo switch. */
    private suspend fun loadTrail() {
        val events = (apiCall { api.events(inspectionId) } as? ApiOutcome.Ok)?.value ?: return
        val assessment = events.lastOrNull { it["kind"]?.jsonPrimitive?.content == "AssessmentRecorded" }?.get("assessment")
            ?.let { runCatching { json.decodeFromJsonElement(VisualAssessment.serializer(), it) }.getOrNull() }
        val attempts = events.filter { it["kind"]?.jsonPrimitive?.content == "VerificationEvaluated" }
            .mapNotNull { e -> e["decision"]?.jsonObject?.get("result")?.jsonPrimitive?.content?.let { r -> runCatching { VerificationResult.valueOf(r) }.getOrNull() } }
        local.update { it.copy(latestAssessment = assessment ?: it.latestAssessment, attempts = attempts) }
        val view = state.value.view ?: return
        val photo = view.photos.lastOrNull() ?: return
        val latest = view.segmentations.filter { it.sourcePhotoEvidenceId == photo.evidenceId && it.matched }.associateBy { it.concept }.values.toList()
        val ui = local.value
        if (ui.outlinePhotoId != photo.evidenceId || ui.outlines.map { it.segmentationId }.toSet() != latest.map { it.evidenceId }.toSet()) {
            val geometries = latest.mapNotNull { seg ->
                (apiCall { api.artifact(inspectionId, "${seg.evidenceId}.json") } as? ApiOutcome.Ok)?.value
                    ?.let { body -> runCatching { json.decodeFromString(Segmentation.serializer(), body.string()) }.getOrNull() }
            }
            val image = ui.outlineImage.takeIf { ui.outlinePhotoId == photo.evidenceId } ?: loadArtifactBitmap(photo.evidenceId)
            local.update { it.copy(outlines = geometries, outlinePhotoId = photo.evidenceId, outlineImage = image) }
        }
        if (local.value.diagnosticsMode == DiagnosticSource.Simulated) {
            val sim = (apiCall { api.simulation() } as? ApiOutcome.Ok)?.value
            val id = local.value.fixture?.diagnostics?.firstOrNull()?.diagnosticId
            local.update { it.copy(simUp = sim?.get("outcomes")?.jsonObject?.get(id ?: "")?.jsonPrimitive?.booleanOrNull) }
        }
    }

    /** What the camera asks for: the AI's requested view, or a post-repair photo once a repair is claimed. */
    fun cameraPrompt(): String {
        val v = state.value.view
        val asked = v?.evidenceRequests?.firstOrNull { it.answeredByPhotoId == null }
        return when {
            asked != null -> "Requested: ${asked.note}"
            v?.currentClaimId != null -> "Take a new photo of the repaired connection"
            state.value.latestAssessment?.nextAction == ProposedNextAction.RequestView -> sentence(state.value.latestAssessment?.requestedView)
            else -> "Point at the equipment and capture a wide view"
        }
    }

    /** Muse looks at the latest photo, then SAM outlines each part the site expects, repair target last. */
    fun assessAndOutline() = act("LensQi is looking at the photo…") {
        val assessment = when (val r = apiCall { api.investigate(inspectionId, key()) }) {
            is ApiOutcome.Ok -> { cache.put(r.value.state); local.update { it.copy(assessment = r.value, latestAssessment = r.value.assessment) }; r.value.assessment }
            else -> { report(r); null }
        }
        for (concept in outlineOrder(local.value.fixture, assessment ?: local.value.latestAssessment))
            apiCall { api.segment(inspectionId, key(), SegmentRequest(concept = concept)) }.onOk { cache.put(it.state) }
    }

    /**
     * After the shutter: queue the original photo, wait for the server to have it, then let Muse look and SAM outline
     * (before a repair) or show the server's change check (after one). Offline, the photo stays queued and says so.
     */
    fun captureForReview(file: File, origin: String) {
        val view = state.value.view
        val afterRepair = view?.currentClaimId != null
        val known = view?.photos?.map { it.evidenceId }?.toSet().orEmpty()
        val labels = if (afterRepair) listOf("Saving photo", "Comparing with before") else listOf("Saving photo", "Observing", "Matching asset", "Outlining parts")
        local.update { it.copy(error = null, review = Review(file, origin, afterRepair, labels.mapIndexed { i, l -> ReviewStep(l, if (i == 0) StepState.Doing else StepState.Todo) })) }
        running?.cancel()
        running = viewModelScope.launch {
            runCatching { queue.enqueuePhoto(inspectionId, file, origin) }.onFailure { e -> local.update { it.copy(error = e.message) } }
            var photo: PhotoEvidence? = null
            withTimeoutOrNull(45_000) {
                while (photo == null) {
                    queue.drain()
                    (apiCall { api.inspection(inspectionId) } as? ApiOutcome.Ok)?.value?.let { v -> cache.put(v); photo = v.photos.firstOrNull { it.evidenceId !in known } }
                    if (photo == null) delay(1_500)
                }
            }
            val uploaded = photo ?: run { reviewUpdate { it.copy(awaitingUpload = true) }; return@launch }
            reviewStep(0, StepState.Done)
            reviewUpdate { it.copy(photoId = uploaded.evidenceId) }
            if (afterRepair) {
                reviewStep(1, StepState.Done)
            } else {
                reviewStep(1, StepState.Doing)
                val assessment = when (val r = apiCall { api.investigate(inspectionId, key()) }) {
                    is ApiOutcome.Ok -> { cache.put(r.value.state); local.update { it.copy(assessment = r.value, latestAssessment = r.value.assessment) }; r.value.assessment }
                    else -> { report(r); null }
                }
                reviewStep(1, if (assessment != null) StepState.Done else StepState.Skipped)
                reviewUpdate { it.copy(assetSeen = assessment?.assetId, assetMatch = assetMatch(assessment, state.value.view?.assetId)) }
                reviewStep(2, if (assessment != null) StepState.Done else StepState.Skipped)
                reviewStep(3, StepState.Doing)
                var failed = false
                for (concept in outlineOrder(local.value.fixture, assessment ?: local.value.latestAssessment)) {
                    when (val r = apiCall { api.segment(inspectionId, key(), SegmentRequest(photoEvidenceId = uploaded.evidenceId, concept = concept)) }) {
                        is ApiOutcome.Ok -> cache.put(r.value.state)
                        else -> { failed = true; report(r) }
                    }
                }
                reviewStep(3, if (failed) StepState.Skipped else StepState.Done)
            }
            refresh().join()
            reviewUpdate { it.copy(done = true) }
        }
    }

    fun closeReview() = local.update { it.copy(review = null) }

    private fun reviewUpdate(change: (Review) -> Review) = local.update { ui -> ui.copy(review = ui.review?.let(change)) }
    private fun reviewStep(index: Int, to: StepState) = reviewUpdate { r -> r.copy(steps = r.steps.mapIndexed { i, st -> if (i == index) st.copy(state = to) else st }) }

    fun onConceptChanged(value: String) = local.update { it.copy(concept = value) }
    fun onWorkOrderChanged(value: String) = local.update { it.copy(workOrderText = value) }
    fun onClaimChanged(value: String) = local.update { it.copy(claimText = value) }

    fun queuePhoto(file: File, origin: String) = viewModelScope.launch {
        runCatching { queue.enqueuePhoto(inspectionId, file, origin) }.onFailure { e -> local.update { it.copy(error = e.message) } }
    }

    fun syncNow() = viewModelScope.launch { queue.drain(); refresh() }

    fun cancel() {
        running?.cancel()
        local.update { it.copy(busy = null, error = "Cancelled on this device; the server records the result only if the model already answered.") }
    }

    fun investigate() = act("Asking Muse Spark…") {
        apiCall { api.investigate(inspectionId, key()) }.onOk { r ->
            cache.put(r.state)
            local.update { it.copy(assessment = r) }
        }
    }

    fun segment(concept: String) = act("Asking SAM 3.1…") {
        apiCall { api.segment(inspectionId, key(), SegmentRequest(concept = concept.ifBlank { null })) }.onOk { r ->
            cache.put(r.state)
            val bitmap = loadArtifactBitmap(r.segmentation.photoEvidenceId)
            local.update { it.copy(segmentation = r.segmentation, segmentationImage = bitmap) }
        }
    }

    fun runDiagnostic(diagnosticId: String) = act("Running $diagnosticId…") {
        apiCall { api.runDiagnostic(inspectionId, diagnosticId, key()) }.onOk { cache.put(it.state) }
    }

    fun setSimulated(diagnosticId: String, passed: Boolean) = act("Setting simulated endpoint…") {
        apiCall { api.setSimulatedOutcome(diagnosticId, SimulationRequest(passed)) }.onOk { local.update { it.copy(simUp = passed) } }
    }

    fun workOrder(summary: String) = act("Drafting work order…") { apiCall { api.workOrder(inspectionId, key(), WorkOrderRequest(summary)) }.onOk(cache::put) }

    fun claim(description: String) = act("Recording repair claim…") {
        apiCall { api.repairClaim(inspectionId, key(), RepairClaimRequest(description)) }.onOk { cache.put(it.state) }
    }

    fun review(requirementId: String, status: ChecklistStatus, photoEvidenceId: String) = act("Recording checklist…") {
        apiCall { api.checklist(inspectionId, key(), ChecklistRequest(requirementId, status, photoEvidenceId)) }.onOk(cache::put)
    }

    fun acknowledge() = act("Recording acknowledgement…") { apiCall { api.acknowledge(inspectionId, key()) }.onOk(cache::put) }

    fun verify() = act("Server is verifying…") {
        apiCall { api.verify(inspectionId, key()) }.onOk { cache.put(it.state) }
    }

    fun checkReceipt() = act("Re-hashing the receipt bundle…") {
        apiCall { api.verifyReceipt(inspectionId) }.onOk { v -> local.update { it.copy(receiptCheck = v) } }
    }

    private suspend fun loadKnowledge() {
        when (val k = apiCall { api.knowledge(inspectionId) }) {
            is ApiOutcome.Ok -> {
                snapshots.save(knowledgeKey, KnowledgeResponse.serializer(), k.value)
                local.update { it.copy(knowledge = k.value, knowledgeCachedAt = null) }
            }
            is ApiOutcome.Unreachable -> snapshots.load(knowledgeKey, KnowledgeResponse.serializer())?.let { snap ->
                local.update { it.copy(knowledge = snap.value, knowledgeCachedAt = snap.fetchedAt) }
            }
            is ApiOutcome.Rejected -> report(k)
        }
    }

    private val viewKey get() = "inspection-"
    private val knowledgeKey get() = "knowledge-"

    private suspend fun loadArtifactBitmap(evidenceId: String): Bitmap? {
        val names = (apiCall { api.artifacts(inspectionId) } as? ApiOutcome.Ok)?.value ?: return null
        local.update { it.copy(artifacts = names) }
        val name = names.firstOrNull { it.startsWith("$evidenceId-") } ?: return null
        val body = (apiCall { api.artifact(inspectionId, name) } as? ApiOutcome.Ok)?.value ?: return null
        return withContext(Dispatchers.IO) { body.byteStream().use { BitmapFactory.decodeStream(it) } }
    }

    private fun act(label: String, block: suspend () -> Unit) {
        if (local.value.busy != null) return
        running = viewModelScope.launch {
            local.update { it.copy(busy = label, error = null) }
            try { block() } finally { local.update { it.copy(busy = null) } }
            refresh()
        }
    }

    private inline fun <T> ApiOutcome<T>.onOk(handle: (T) -> Unit) {
        if (this is ApiOutcome.Ok) handle(value) else report(this)
    }

    private fun report(outcome: ApiOutcome<*>) = local.update {
        it.copy(error = when (outcome) {
            is ApiOutcome.Rejected -> "${outcome.code}: ${outcome.message}"
            is ApiOutcome.Unreachable -> "API unreachable: ${outcome.message}"
            is ApiOutcome.Ok -> null
        })
    }

    /** One fresh Idempotency-Key per user action. */
    private fun key() = UUID.randomUUID().toString()
}

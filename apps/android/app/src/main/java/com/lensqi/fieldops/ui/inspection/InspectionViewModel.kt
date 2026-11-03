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
import com.lensqi.fieldops.data.api.retryable
import com.lensqi.fieldops.data.api.ProposedNextAction
import com.lensqi.fieldops.data.api.VerificationResult
import com.lensqi.fieldops.data.api.VisualAssessment
import com.lensqi.fieldops.data.api.PhotoEvidence
import com.lensqi.fieldops.domain.Attempt
import com.lensqi.fieldops.domain.currentAttempt
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject

/** Everything the inspection screen draws. Stage, decision and labels come from the server; nothing is derived locally. */
enum class StepState { Todo, Doing, Done, Skipped }
data class ReviewStep(val label: String, val state: StepState)

/** The post-capture review: the frozen frame, what the server is doing with it, and what came back. */
data class Review(
    val commandId: String,
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
    /** The server's refusal of the upload, in the technician's words; the photo stays saved on the phone. */
    val refused: String? = null,
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
    /** Each attempt as the record keeps it: decision, what was measured, what was confirmed. */
    val history: List<Attempt> = emptyList(),
    /** Something saved on the phone or sent later; cleared by the next action. Not an error. */
    val notice: String? = null,
    /** The decision for the evidence on screen now, read from the events; null once a new claim, photo, review or confirmation follows it. */
    val attempt: Attempt? = null,
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
    private val serverBase = settings.currentBase().toString()
    private fun cacheView(view: InspectionView) = cache.put(view, serverBase)
    /** The part confirmed on the camera, kept across captures and retakes for this inspection. */
    val spatialTarget = com.lensqi.fieldops.ui.camera.TargetMemory()
    suspend fun analyzeLiveFrame(kind: String, bytes: ByteArray, frameId: String): com.lensqi.fieldops.domain.live.LiveResponse {
        return api.liveFrame(inspectionId, kind,
            okhttp3.MultipartBody.Part.createFormData("image", "preview.jpg", bytes.toRequestBody("image/jpeg".toMediaType())),
            frameId.toRequestBody("text/plain".toMediaType()))
    }
    private val local = MutableStateFlow(InspectionUi())
    private var running: Job? = null
    // Main thread only. The review on screen follows one photo; each photo's analysis runs to the end even after its
    // review is closed or a newer photo is taken, one at a time in capture order.
    private var reviewCommandId: String? = null
    private val analyses = mutableMapOf<String, Job>()
    private var lastAnalysis: Job? = null

    val state: StateFlow<InspectionUi> = combine(local, cache.observe(inspectionId, serverBase), queue.observe(inspectionId, serverBase)) { ui, view, pending ->
        ui.copy(view = view ?: ui.view, pending = pending)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InspectionUi())

    init {
        viewModelScope.launch {
            (apiCall { api.health() } as? ApiOutcome.Ok)?.let { h -> local.update { it.copy(diagnosticsMode = h.value.diagnosticsMode) } }
            val view = (apiCall { api.inspection(inspectionId) } as? ApiOutcome.Ok)?.value?.also(::cacheView)
            refresh()
            (apiCall { api.fixtures() } as? ApiOutcome.Ok)?.value?.firstOrNull { it.fixtureId == view?.fixtureId }?.let { f -> local.update { it.copy(fixture = f) } }
            loadKnowledge()
        }
        // A capture made while offline drains as soon as the screen opens with a connection.
        queue.requestSync()
        viewModelScope.launch {
            queue.observe(inspectionId, serverBase).collect { commands ->
                val photos = commands.filter { it.kind == com.lensqi.fieldops.data.queue.CommandKind.UploadPhoto }
                if (reviewCommandId == null) photos.lastOrNull { it.state != com.lensqi.fieldops.data.queue.CommandState.Rejected }?.let(::resumeReview)
                // Photos uploaded while no screen followed them are on the server (digest checked) and only the newest is
                // analysed, so their saved copies are removed instead of piling up.
                photos.filter { it.state == com.lensqi.fieldops.data.queue.CommandState.Uploaded && it.id !in analyses }.forEach { queue.dismiss(it) }
            }
        }
        // Every server view is kept on the device so the screen still shows the last known state offline.
        viewModelScope.launch {
            cache.observe(inspectionId, serverBase).collect { v -> if (v != null) snapshots.save(viewKey, InspectionView.serializer(), v) }
        }
        // New photos (from the queue or another client) need their artifact names for thumbnails.
        viewModelScope.launch {
            cache.observe(inspectionId, serverBase).map { v -> v?.photos?.map { it.evidenceId }.orEmpty() }.distinctUntilChanged().collect { ids ->
                if (ids.any { id -> local.value.artifacts.none { it.startsWith("$id-") } })
                    (apiCall { api.artifacts(inspectionId) } as? ApiOutcome.Ok)?.let { a -> local.update { it.copy(artifacts = a.value) } }
            }
        }
    }

    fun artifactUrl(name: String): String = serverBase.toHttpUrl().resolve("v1/inspections/$inspectionId/artifacts/$name").toString()
    /** The designed receipt page served by the API (embed mode: the app draws its own top bar). */
    fun receiptUrl(): String = serverBase.toHttpUrl().resolve("m/i/$inspectionId/receipt?embed=1&theme=${if (Fo.isDark) "dark" else "light"}").toString()
    fun receiptJsonUrl(): String = serverBase.toHttpUrl().resolve("v1/inspections/$inspectionId/receipt").toString()
    fun artifactFor(evidenceId: String): String? = state.value.artifacts.firstOrNull { it.startsWith("$evidenceId-") }

    fun refresh() = viewModelScope.launch {
        when (val r = apiCall { api.inspection(inspectionId) }) {
            is ApiOutcome.Ok -> {
                cacheView(r.value)
                local.update { it.copy(viewCachedAt = null) }
                if (local.value.knowledgeCachedAt != null) loadKnowledge()
            }
            is ApiOutcome.Unreachable -> {
                report(r)
                if (state.value.view == null) snapshots.load(viewKey, InspectionView.serializer())?.takeIf { it.value.inspectionId == inspectionId }?.let { snap ->
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
        local.update { it.copy(latestAssessment = assessment ?: it.latestAssessment, attempts = attempts, attempt = currentAttempt(events, json), history = com.lensqi.fieldops.domain.attemptHistory(events, json)) }
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
        val photo = latestPhotoId()
        val assessment = when (val r = keys.command(action("investigate:$photo")) { k -> api.investigate(inspectionId, k, photo) }) {
            is ApiOutcome.Ok -> { cacheView(r.value.state); local.update { it.copy(assessment = r.value, latestAssessment = r.value.assessment) }; r.value.assessment }
            else -> { report(r); null }
        }
        for (concept in outlineOrder(local.value.fixture, assessment ?: local.value.latestAssessment))
            keys.command(action("segment:$photo:$concept")) { k -> api.segment(inspectionId, k, SegmentRequest(photoEvidenceId = photo, concept = concept)) }.onOk { cacheView(it.state) }
    }

    /**
     * The name a command's retry key is kept under: the command, the inputs it depends on (the photo, the review answers,
     * the claim, the confirmation) but never its own effect. A key kept after a lost answer is then replayed only while the
     * request would be the same request: a new photo, a changed review answer or a new claim makes a new name, so an old
     * assessment, measurement, confirmation or decision is never handed to new evidence. The server's event sequence is
     * deliberately not part of the name: a command the server applied before its answer was lost advances the sequence,
     * and its retry must still replay the recorded outcome, not run again.
     */
    private fun action(name: String) = "$name@claim=${state.value.view?.currentClaimId}"
    private fun latestPhotoId() = state.value.view?.photos?.lastOrNull()?.evidenceId
    /** The review answers as they stand (requirement, answer, photo): a command that confirms or measures them is a new request once one changes. */
    private fun reviewState() = state.value.view?.checklist?.sortedBy { it.requirementId }?.joinToString(";") { "${it.requirementId}=${it.status}:${it.reviewedByEvidenceId}" }.orEmpty()

    /**
     * After the shutter: queue the original photo, wait for the server to have it, then let Muse look and SAM outline
     * (before a repair) or show the server's change check (after one). Offline, the photo stays queued and says so.
     */

    fun captureForReview(file: File, origin: String, measured: String? = null) {
        viewModelScope.launch {
            val afterRepair = state.value.view?.currentClaimId != null
            val shotAt = file.lastModified().takeIf { it > 0 }?.let(java.time.Instant::ofEpochMilli) ?: java.time.Instant.now()
            try {
                val command = queue.enqueuePhoto(inspectionId, file, origin, serverBase, shotAt, afterRepair, measured)
                resumeReview(command)
            } catch (e: java.io.IOException) {
                local.update { it.copy(error = "The photo couldn't be saved. Free some space and try again.") }
            } catch (e: com.lensqi.fieldops.data.queue.UnsupportedImageException) {
                local.update { it.copy(error = "Only JPEG or PNG photos can be used as evidence. Choose another photo.") }
            }
        }
    }

    private fun resumeReview(command: PendingCommand) {
        if (reviewCommandId == command.id) return
        reviewCommandId = command.id
        val labels = if (command.afterRepair) listOf("Saving photo", "Comparing with before") else listOf("Saving photo", "Observing", "Matching asset", "Outlining parts")
        local.update { it.copy(error = null, review = Review(command.id, File(requireNotNull(command.filePath)), command.source ?: "Phone", command.afterRepair,
            labels.map { label -> ReviewStep(label, StepState.Todo) }, awaitingUpload = true)) }
        queue.requestSync()
        analyze(command)
    }

    /** Waits for the server to have the photo, then lets Muse look and SAM outline; the review shows it only while it is open. */
    private fun analyze(command: PendingCommand) {
        if (command.id in analyses) return
        val id = command.id
        val previous = lastAnalysis
        lastAnalysis = viewModelScope.launch {
            previous?.join()
            val uploaded = queue.observe(inspectionId, serverBase).map { list -> list.firstOrNull { it.id == id } }
                .first { it == null || it.state != com.lensqi.fieldops.data.queue.CommandState.Pending }
            if (uploaded == null || uploaded.state == com.lensqi.fieldops.data.queue.CommandState.Rejected) {
                // Removed or refused: a retry starts a new analysis.
                analyses.remove(id)
                val refused = uploaded?.let { com.lensqi.fieldops.ui.common.friendly(it.errorCode ?: "unknown", it.errorMessage.orEmpty()) }
                if (refused != null) local.update { it.copy(error = refused) }
                reviewUpdate(id) { it.copy(awaitingUpload = false, done = true, refused = refused) }
                return@launch
            }
            val photoId = requireNotNull(uploaded.resultEvidenceId)
            reviewStep(id, 0, StepState.Done)
            reviewUpdate(id) { it.copy(photoId = photoId, awaitingUpload = false) }
            if (uploaded.afterRepair) reviewStep(id, 1, StepState.Done)
            else {
                reviewStep(id, 1, StepState.Doing)
                val assessment = when (val r = apiCall { api.investigate(inspectionId, "review-$id", photoId) }) {
                    is ApiOutcome.Ok -> { cacheView(r.value.state); local.update { it.copy(assessment = r.value, latestAssessment = r.value.assessment) }; r.value.assessment }
                    else -> { report(r); null }
                }
                reviewStep(id, 1, if (assessment != null) StepState.Done else StepState.Skipped)
                reviewUpdate(id) { it.copy(assetSeen = assessment?.assetId, assetMatch = assetMatch(assessment, state.value.view?.assetId)) }
                reviewStep(id, 2, if (assessment != null) StepState.Done else StepState.Skipped)
                var failed = assessment == null
                if (assessment != null) for (concept in outlineOrder(local.value.fixture, assessment)) {
                    when (val r = apiCall { api.segment(inspectionId, "review-$id-$concept", SegmentRequest(photoEvidenceId = photoId, concept = concept)) }) {
                        is ApiOutcome.Ok -> cacheView(r.value.state)
                        else -> { failed = true; report(r) }
                    }
                }
                reviewStep(id, 3, if (failed) StepState.Skipped else StepState.Done)
            }
            refresh().join()
            reviewUpdate(id) { it.copy(done = true) }
            // Its review was closed while it ran: nothing shows the saved copy any more.
            if (local.value.review?.commandId != id) queue.dismiss(uploaded)
        }.also { analyses[id] = it }
    }

    fun retryUpload(command: PendingCommand) = viewModelScope.launch { reviewCommandId = null; queue.retry(command, serverBase) }
    fun removeUpload(command: PendingCommand) = viewModelScope.launch { queue.dismiss(command) }
    fun closeReview() {
        val closing = local.value.review?.commandId ?: return
        local.update { it.copy(review = null) }
        // Keep reviewCommandId until another capture so the closed review is not reopened. An unfinished analysis keeps
        // running and removes its own saved copy when it ends.
        if (analyses[closing]?.isActive == true) return
        viewModelScope.launch {
            queue.observe(inspectionId, serverBase).first().firstOrNull { it.id == closing && it.state == com.lensqi.fieldops.data.queue.CommandState.Uploaded }?.let { queue.dismiss(it) }
        }
    }

    private fun reviewUpdate(id: String, change: (Review) -> Review) = local.update { ui -> ui.copy(review = ui.review?.let { if (it.commandId == id) change(it) else it }) }
    private fun reviewStep(id: String, index: Int, to: StepState) = reviewUpdate(id) { r -> r.copy(steps = r.steps.mapIndexed { i, st -> if (i == index) st.copy(state = to) else st }) }

    fun onConceptChanged(value: String) = local.update { it.copy(concept = value) }
    fun onWorkOrderChanged(value: String) = local.update { it.copy(workOrderText = value) }
    fun onClaimChanged(value: String) = local.update { it.copy(claimText = value) }

    fun queuePhoto(file: File, origin: String) = viewModelScope.launch {
        runCatching { queue.enqueuePhoto(inspectionId, file, origin, serverBase) }.onFailure { e -> local.update { it.copy(error = com.lensqi.fieldops.ui.common.friendly("local_save_failed", "")) } }
    }

    fun syncNow() = viewModelScope.launch { queue.drain(); refresh() }

    fun cancel() {
        running?.cancel()
        local.update { it.copy(busy = null, error = "Cancelled on this device; the server records the result only if the model already answered.") }
    }

    fun investigate() = act("Asking Muse Spark…") {
        val photo = latestPhotoId()
        keys.command(action("investigate:$photo")) { k -> api.investigate(inspectionId, k, photo) }.onOk { r ->
            cacheView(r.state)
            local.update { it.copy(assessment = r) }
        }
    }

    fun segment(concept: String) = act("Asking SAM 3.1…") {
        val photo = latestPhotoId()
        keys.command(action("segment:$photo:${concept.ifBlank { "auto" }}")) { k -> api.segment(inspectionId, k, SegmentRequest(photoEvidenceId = photo, concept = concept.ifBlank { null })) }.onOk { r ->
            cacheView(r.state)
            val bitmap = loadArtifactBitmap(r.segmentation.photoEvidenceId)
            local.update { it.copy(segmentation = r.segmentation, segmentationImage = bitmap) }
        }
    }

    fun runDiagnostic(diagnosticId: String) = act("Running $diagnosticId…") {
        // A check is a new request once new evidence exists (a photo or a review answer), never a replay of an older measurement.
        keys.command(action("diagnostic:$diagnosticId:${latestPhotoId()}:${reviewState()}")) { k -> api.runDiagnostic(inspectionId, diagnosticId, k) }.onOk { cacheView(it.state) }
    }

    fun setSimulated(diagnosticId: String, passed: Boolean) = act("Setting simulated endpoint…") {
        // The demo outcome may have changed (answered, lost, or cancelled in flight): a check whose answer was lost before
        // must not replay the old measurement now. Only a refusal leaves the pending check as it was.
        val outcome = try { apiCall { api.setSimulatedOutcome(diagnosticId, SimulationRequest(passed)) } }
            catch (e: kotlinx.coroutines.CancellationException) { keys.dropAll("diagnostic:$diagnosticId:"); throw e }
        if (outcome !is ApiOutcome.Rejected || outcome.retryable) keys.dropAll("diagnostic:$diagnosticId:")
        outcome.onOk { local.update { it.copy(simUp = passed) } }
    }

    fun workOrder(summary: String) = act("Drafting work order…") {
        val body = WorkOrderRequest(summary)
        val action = action("workOrder:$summary")
        keys.command(action) { k -> api.workOrder(inspectionId, k, body) }
            .queuedWhenOffline(action, com.lensqi.fieldops.data.queue.CommandKind.WorkOrder, json.encodeToString(WorkOrderRequest.serializer(), body))
            ?.onOk(::cacheView)
    }

    fun claim(description: String) = act("Recording repair claim…") {
        val body = RepairClaimRequest(description)
        val action = action("claim:$description")
        keys.command(action) { k -> api.repairClaim(inspectionId, k, body) }
            .queuedWhenOffline(action, com.lensqi.fieldops.data.queue.CommandKind.RepairClaim, json.encodeToString(RepairClaimRequest.serializer(), body))
            ?.onOk { cacheView(it.state) }
    }

    fun review(requirementId: String, status: ChecklistStatus, photoEvidenceId: String) = act("Recording checklist…") {
        val body = ChecklistRequest(requirementId, status, photoEvidenceId)
        val action = action("review:$requirementId:$status:$photoEvidenceId")
        // Any review the server may have applied, or that is saved to be sent, clears the confirmation there, even one that
        // repeats an answer: a confirmation whose answer was lost before is a new request now. A review cancelled in flight may
        // have reached the server, so it counts as applied too (its own key stays for the retry). A refused review changed nothing.
        val outcome = try { keys.command(action) { k -> api.checklist(inspectionId, k, body) } }
            catch (e: kotlinx.coroutines.CancellationException) { keys.dropAll("acknowledge:"); throw e }
        if (outcome !is ApiOutcome.Rejected || outcome.retryable) keys.dropAll("acknowledge:")
        outcome.queuedWhenOffline(action, com.lensqi.fieldops.data.queue.CommandKind.Checklist, json.encodeToString(ChecklistRequest.serializer(), body))
            ?.onOk(::cacheView)
    }

    fun acknowledge() = act("Recording acknowledgement…") {
        // The confirmation is of the accepted photo and the review as they stand; a revised answer needs a new confirmation.
        val action = action("acknowledge:${state.value.view?.acceptedPostRepairPhotoId}:${reviewState()}")
        keys.command(action) { k -> api.acknowledge(inspectionId, k) }
            .queuedWhenOffline(action, com.lensqi.fieldops.data.queue.CommandKind.Acknowledge, null)
            ?.onOk(::cacheView)
    }

    /**
     * A command the server did not answer (unreachable, timed out, 5xx, asked to slow down) is saved on the phone under the
     * key it was just sent with and sent later in capture order, with the same body (verify is never saved: it measures the
     * equipment). A refusal is final and is shown instead. Null means it was saved, so the caller has nothing more to show;
     * the server's answer or refusal still comes back through the queue.
     */
    private suspend fun <T> ApiOutcome<T>.queuedWhenOffline(action: String, kind: com.lensqi.fieldops.data.queue.CommandKind, payload: String?): ApiOutcome<T>? {
        if (!retryable) return this
        return try {
            queue.enqueueCommand(inspectionId, kind, keys.keyFor(action), payload, serverBase)
            keys.drop(action)
            local.update { it.copy(notice = SAVED_OFFLINE) }
            null
        } catch (e: java.io.IOException) { this }
    }

    /**
     * A verify that times out on the phone and is tapped again replays the server's decision instead of recording a second
     * attempt. The key is for this claim, this accepted photo, these review answers and this confirmation: after any of
     * them changes it is a new request.
     */
    fun verify() = act("Server is verifying…") {
        val v = state.value.view
        keys.command(action("verify:${v?.acceptedPostRepairPhotoId}:${reviewState()}:ack=${v?.technicianAcknowledged}")) { k -> api.verify(inspectionId, k) }.onOk { cacheView(it.state) }
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
            is ApiOutcome.Unreachable -> snapshots.load(knowledgeKey, KnowledgeResponse.serializer())?.takeIf { it.value.inspectionId == inspectionId }?.let { snap ->
                local.update { it.copy(knowledge = snap.value, knowledgeCachedAt = snap.fetchedAt) }
            }
            is ApiOutcome.Rejected -> report(k)
        }
    }

    private val viewKey get() = SnapshotStore.key(serverBase, "inspection", inspectionId)
    private val knowledgeKey get() = SnapshotStore.key(serverBase, "knowledge", inspectionId)

    /** A stored photo for display on the camera (the before photo as a framing aid), upright and downscaled; null offline. */
    suspend fun photoForDisplay(evidenceId: String): Bitmap? {
        val name = artifactFor(evidenceId) ?: (apiCall { api.artifacts(inspectionId) } as? ApiOutcome.Ok)?.value
            ?.also { names -> local.update { it.copy(artifacts = names) } }?.firstOrNull { it.startsWith("$evidenceId-") } ?: return null
        val body = (apiCall { api.artifact(inspectionId, name) } as? ApiOutcome.Ok)?.value ?: return null
        return withContext(Dispatchers.IO) { body.byteStream().use { com.lensqi.fieldops.ui.camera.uprightBitmap(it.readBytes(), 1600) } }
    }

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
            local.update { it.copy(busy = label, error = null, notice = null) }
            try { block() } finally { local.update { it.copy(busy = null) } }
            refresh()
        }
    }

    private inline fun <T> ApiOutcome<T>.onOk(handle: (T) -> Unit) {
        if (this is ApiOutcome.Ok) handle(value) else report(this)
    }

    private fun report(outcome: ApiOutcome<*>) = local.update {
        it.copy(error = when (outcome) {
            is ApiOutcome.Rejected -> com.lensqi.fieldops.ui.common.friendly(outcome.code, outcome.message)
            is ApiOutcome.Unreachable -> com.lensqi.fieldops.ui.common.friendly("offline", outcome.message)
            is ApiOutcome.Ok -> null
        })
    }

    /**
     * One Idempotency-Key per pending command, kept while the server has not answered (unreachable, timed out, 5xx or a
     * failed model call) so a retry replays rather than repeats; an answer drops it and the next tap is a new request.
     */
    private val keys = com.lensqi.fieldops.data.api.RetryKeys(savedState)

    companion object {
        const val SAVED_OFFLINE = "Saved on this phone. It is sent in order when the connection returns; the server's answer replaces this view then."
    }
}

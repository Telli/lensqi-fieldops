package com.lensqi.fieldops.data.api

import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

/**
 * The inspection API (PRD §9). Every POST carries a client-generated Idempotency-Key so a retried command,
 * including one replayed from the offline queue, is applied once by the server.
 */
interface FieldOpsApi {
    @Multipart
    @POST("v1/inspections/{id}/live/{kind}")
    suspend fun liveFrame(@Path("id") id: String, @Path("kind") kind: String, @Part image: MultipartBody.Part,
        @Part("frameId") frameId: RequestBody): com.lensqi.fieldops.domain.live.LiveResponse

    @GET("health")
    suspend fun health(): Health

    @GET("v1/fixtures")
    suspend fun fixtures(): List<Fixture>

    @POST("v1/inspections")
    suspend fun createInspection(@Header(IDEMPOTENCY_KEY) key: String, @Body body: CreateInspectionRequest): InspectionView

    @GET("v1/inspections")
    suspend fun inspections(): List<InspectionSummary>

    @GET("v1/inspections/{id}")
    suspend fun inspection(@Path("id") id: String): InspectionView

    @Multipart
    @POST("v1/inspections/{id}/observations")
    suspend fun uploadObservation(
        @Path("id") id: String,
        @Header(IDEMPOTENCY_KEY) key: String,
        @Part image: MultipartBody.Part,
        @Part("source") source: RequestBody,
        @Part("observedAt") observedAt: RequestBody,
    ): ObservationResponse

    @POST("v1/inspections/{id}/investigate")
    suspend fun investigate(@Path("id") id: String, @Header(IDEMPOTENCY_KEY) key: String): InvestigateResponse

    @POST("v1/inspections/{id}/segment")
    suspend fun segment(@Path("id") id: String, @Header(IDEMPOTENCY_KEY) key: String, @Body body: SegmentRequest): SegmentResponse

    @GET("v1/inspections/{id}/knowledge")
    suspend fun knowledge(@Path("id") id: String): KnowledgeResponse

    @POST("v1/inspections/{id}/diagnostics/{diagnosticId}:run")
    suspend fun runDiagnostic(@Path("id") id: String, @Path("diagnosticId") diagnosticId: String, @Header(IDEMPOTENCY_KEY) key: String): DiagnosticRunResponse

    @POST("v1/inspections/{id}/work-order")
    suspend fun workOrder(@Path("id") id: String, @Header(IDEMPOTENCY_KEY) key: String, @Body body: WorkOrderRequest): InspectionView

    @POST("v1/inspections/{id}/repair-claims")
    suspend fun repairClaim(@Path("id") id: String, @Header(IDEMPOTENCY_KEY) key: String, @Body body: RepairClaimRequest): RepairClaimResponse

    @POST("v1/inspections/{id}/checklist")
    suspend fun checklist(@Path("id") id: String, @Header(IDEMPOTENCY_KEY) key: String, @Body body: ChecklistRequest): InspectionView

    @POST("v1/inspections/{id}/acknowledge")
    suspend fun acknowledge(@Path("id") id: String, @Header(IDEMPOTENCY_KEY) key: String): InspectionView

    @POST("v1/inspections/{id}/verify")
    suspend fun verify(@Path("id") id: String, @Header(IDEMPOTENCY_KEY) key: String): VerifyResponse

    @GET("v1/inspections/{id}/receipt")
    suspend fun receipt(@Path("id") id: String): JsonObject

    @GET("v1/inspections/{id}/receipt/verify")
    suspend fun verifyReceipt(@Path("id") id: String): ReceiptVerification

    @GET("v1/inspections/{id}/events")
    suspend fun events(@Path("id") id: String): List<JsonObject>

    @GET("v1/simulation/diagnostics")
    suspend fun simulation(): JsonObject

    @GET("v1/inspections/{id}/artifacts")
    suspend fun artifacts(@Path("id") id: String): List<String>

    @GET("v1/inspections/{id}/artifacts/{name}")
    suspend fun artifact(@Path("id") id: String, @Path("name") name: String): ResponseBody

    /** Demo-only control, refused by the server unless diagnostics run in Simulated mode. */
    @POST("v1/simulation/diagnostics/{diagnosticId}")
    suspend fun setSimulatedOutcome(@Path("diagnosticId") diagnosticId: String, @Body body: SimulationRequest): SimulationResponse

    // ---- guided tasks (DIY and technician) ----

    @GET("v1/tasks")
    suspend fun tasks(): List<TaskSummary>

    @GET("v1/tasks/{id}")
    suspend fun task(@Path("id") id: String): TaskView

    @Multipart
    @POST("v1/tasks")
    suspend fun createTask(@Header(IDEMPOTENCY_KEY) key: String, @Part("mode") mode: RequestBody, @Part("goal") goal: RequestBody,
        @Part("details") details: RequestBody?, @Part("fixtureId") fixtureId: RequestBody?, @Part("assetId") assetId: RequestBody?,
        @Part manual: List<MultipartBody.Part>): CreateTaskResponse

    @POST("v1/tasks/{id}/plan")
    suspend fun replan(@Path("id") id: String, @Header(IDEMPOTENCY_KEY) key: String): TaskView

    @POST("v1/tasks/{id}/accept")
    suspend fun acceptPlan(@Path("id") id: String, @Header(IDEMPOTENCY_KEY) key: String, @Body body: AcceptRequest): TaskView

    @POST("v1/tasks/{id}/steps/{stepId}:{action}")
    suspend fun stepAction(@Path("id") id: String, @Path("stepId") stepId: String, @Path("action") action: String, @Header(IDEMPOTENCY_KEY) key: String): TaskView

    @Multipart
    @POST("v1/tasks/{id}/steps/{stepId}/check")
    suspend fun checkStep(@Path("id") id: String, @Path("stepId") stepId: String, @Header(IDEMPOTENCY_KEY) key: String, @Part image: MultipartBody.Part): StepCheckResponse

    @Multipart
    @POST("v1/tasks/{id}/ask")
    suspend fun ask(@Path("id") id: String, @Header(IDEMPOTENCY_KEY) key: String, @Part("question") question: RequestBody, @Part image: MultipartBody.Part?): AskResponse

    @POST("v1/tasks/{id}/link-inspection")
    suspend fun linkInspection(@Path("id") id: String, @Header(IDEMPOTENCY_KEY) key: String): LinkInspectionResponse

    @Multipart
    @POST("v1/tasks/{id}/live/{kind}")
    suspend fun taskLiveFrame(@Path("id") id: String, @Path("kind") kind: String, @Part image: MultipartBody.Part,
        @Part("frameId") frameId: RequestBody): com.lensqi.fieldops.domain.live.LiveResponse

    companion object {
        const val IDEMPOTENCY_KEY = "Idempotency-Key"
    }
}

using System.Text.Json;
using LensQi.FieldOps.Api.Infrastructure.Hardening;
using LensQi.FieldOps.Api.Infrastructure.Inspections;
using LensQi.FieldOps.Api.Shared;
using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Meta.Voice;

namespace LensQi.FieldOps.Api.Features.Voice;

public static class VoiceEndpoints
{
    public static IEndpointRouteBuilder MapVoice(this IEndpointRouteBuilder app)
    {
        // Deliberately records no event and saves no audio. Only repair-claims can turn confirmed text into a claim.
        app.MapPost("/v1/inspections/{id}/voice/transcribe", async (string id, HttpRequest http, InspectionStore store, IVoiceTranscriber voice, ILoggerFactory logs, CancellationToken ct) =>
        {
            if (store.Find(id) is not { } session) return ApiResults.NotFound($"Inspection '{id}' not found");
            // A closed inspection takes no new repair claim, so it does not pay for a transcription of one.
            if (session.State.Stage is InspectionStage.VerifiedComplete or InspectionStage.Inconclusive)
                return ApiResults.Conflict(InspectionErrorCodes.InvalidState, "This inspection is closed; it takes no new repair claim");
            return await Transcribe(http, voice, logs, ct);
        }).DisableAntiforgery().AsModelCall().WithMetadata(new Microsoft.AspNetCore.Mvc.RequestSizeLimitAttribute(ClaimAudio.MaxBytes + 65_536));
        app.MapPost("/v1/voice/transcribe", Transcribe).DisableAntiforgery().AsModelCall()
            .WithMetadata(new Microsoft.AspNetCore.Mvc.RequestSizeLimitAttribute(ClaimAudio.MaxBytes + 65_536));
        return app;
    }
    private static async Task<IResult> Transcribe(HttpRequest http, IVoiceTranscriber voice, ILoggerFactory logs, CancellationToken ct)
    {
            if (!http.HasFormContentType) return ApiResults.BadRequest("audio_invalid", "multipart/form-data with an 'audio' WAV file is required");
            IFormCollection form;
            try { form = await http.ReadFormAsync(ct); }
            catch (Exception ex) when (ex is InvalidDataException or BadHttpRequestException) { return ApiResults.BadRequest("audio_invalid", "Invalid multipart audio upload"); }
            var file = form.Files.GetFile("audio");
            if (file is null || file.Length == 0 || file.Length > ClaimAudio.MaxBytes)
                return ApiResults.BadRequest("audio_invalid", "Record up to 60 seconds of mono PCM WAV audio");
            using var buffer = new MemoryStream();
            await file.CopyToAsync(buffer, ct);
            var bytes = buffer.ToArray();
            if (!ClaimAudio.IsSupported(bytes)) return ApiResults.BadRequest("audio_invalid", "Audio must be mono 16-bit PCM WAV at 16 or 24 kHz, at most 60 seconds");
            try { return Results.Ok(await voice.TranscribeAsync(bytes, ct)); }
            catch (NoSpeechException) { return ApiResults.Conflict("no_speech", "No speech was recognized. Record again or type instead."); }
            catch (Exception ex) when ((ex is HttpRequestException or OperationCanceledException or InvalidOperationException or JsonException) && !ct.IsCancellationRequested)
            {
                // The message holds a status at most: the transcriber never reads a provider error body into it.
                logs.CreateLogger("VoiceTranscribe").LogWarning("Transcription failed: {Reason}", ex.GetType().Name);
                return ApiResults.Conflict("provider_unavailable", "Transcription failed. Record again or type instead.");
            }
    }

}

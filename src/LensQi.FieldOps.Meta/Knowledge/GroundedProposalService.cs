using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using LensQi.FieldOps.Core.Diagnostics;
using LensQi.FieldOps.Core.Knowledge;
using Microsoft.Extensions.AI;

namespace LensQi.FieldOps.Meta.Knowledge;

public sealed record GroundedProposalResult(
    VendorGroundedProposal? Proposal,
    ProposalValidationResult Validation,
    string RawResponse,
    string ModelId,
    string PromptDigest,
    string ResponseDigest,
    UsageDetails? Usage,
    string? ParseError,
    VendorGroundedProposal? Parsed = null)
{
    /// <summary>The action the model asked for, valid or not; for the rejection record only, never acted on.</summary>
    public string? AttemptedAction => Parsed?.NextAction.ToString();

    public bool Accepted => Proposal is not null && Validation.IsValid;
}

/// <summary>
/// One bounded model call: compose → call → parse strictly → validate against resolved facts, fixture allowlist and
/// probe registry. The model's text is never acted on before <see cref="KnowledgeActionValidator"/> accepts it.
/// </summary>
public sealed class GroundedProposalService
{
    private readonly IChatClient _chat;
    private readonly IDiagnosticRegistry _registry;

    public GroundedProposalService(IChatClient chat, IDiagnosticRegistry registry)
    {
        _chat = chat;
        _registry = registry;
    }

    public async Task<GroundedProposalResult> ProposeAsync(
        FixtureKnowledgeBinding binding,
        AssetIdentity asset,
        KnowledgeResolution resolution,
        Observation observation,
        CancellationToken cancellationToken = default)
    {
        var request = KnowledgeGroundingComposer.Compose(binding, asset, resolution, _registry, observation);
        var response = await _chat.GetResponseAsync(request.Messages, request.Options, cancellationToken).ConfigureAwait(false);
        var raw = response.Text ?? string.Empty;
        var responseDigest = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(raw)));
        var modelId = response.ModelId ?? "unknown";

        VendorGroundedProposal? proposal;
        try
        {
            proposal = JsonSerializer.Deserialize<VendorGroundedProposal>(raw, KnowledgeJson.Options);
        }
        catch (JsonException ex)
        {
            return new GroundedProposalResult(null,
                new ProposalValidationResult(false, [new ProposalRejection(ProposalErrorCodes.ActionNotAllowed, $"Model output is not a valid proposal: {ex.Message}", [])]),
                raw, modelId, request.PromptDigest, responseDigest, response.Usage, ex.Message);
        }

        if (proposal is null)
            return new GroundedProposalResult(null,
                new ProposalValidationResult(false, [new ProposalRejection(ProposalErrorCodes.ActionNotAllowed, "Model output was empty.", [])]),
                raw, modelId, request.PromptDigest, responseDigest, response.Usage, "empty");

        var validation = KnowledgeActionValidator.Validate(proposal, resolution, binding, _registry);
        return new GroundedProposalResult(validation.IsValid ? proposal : null, validation, raw, modelId, request.PromptDigest, responseDigest, response.Usage, null, proposal);
    }
}

namespace LensQi.FieldOps.Core.Tasks;

/// <summary>
/// DIY: an everyday technical user with a goal and a plan. Technician: installs and repairs, optionally on a site record.
/// Freeform: spontaneous help with whatever the camera shows, no plan; it can be turned into a planned task later.
/// </summary>
public enum TaskMode { Diy, Technician, Freeform }

public enum TaskPhase { Planned, InProgress, Completed, Abandoned }

public enum RiskLevel { Low, Moderate, High }

public enum CautionKind { Hot, Electrical, Lifting, Chemical, Height, Sharp, Pressure, Other }

/// <summary>Work that is professionals-only. A plan in one of these gets prepare-and-observe steps and a referral, never hands-on steps.</summary>
public enum RestrictedCategory { None, GasLinesOrAppliances, MainsElectrical, EvHighVoltage, AirbagsSrs, Refrigerant, Structural }

/// <summary>What the photo check suggests. The user decides whether a step is done; the AI never does.</summary>
public enum StepCheckVerdict { LooksDone, NotYet, CantTell }

public enum TaskPhotoPurpose { StepCheck, Manual, Question }

public sealed record Caution(CautionKind Kind, string Text);

public sealed record StepPhotoCheck(bool Required, string WhatDoneLooksLike);

/// <summary>
/// One step of a plan. <see cref="LookFor"/> are short noun phrases SAM outlines in the live view. A hands-on step asks the
/// user to touch, loosen, fit or remove something; prepare and observe steps do not. <see cref="AccessFor"/> is set only on
/// a step whose purpose is getting up to a part (a ladder or step stool): the part it reaches, so the phone can measure
/// whether the person can reach it without the step.
/// </summary>
public sealed record TaskStep(
    string Id, string Title, string Instruction, bool HandsOn,
    IReadOnlyList<string> LookFor, IReadOnlyList<Caution> Cautions, IReadOnlyList<string> StopIf, StepPhotoCheck? Check,
    string? AccessFor = null, IReadOnlyList<LensQi.FieldOps.Core.Knowledge.ApplicableFact>? SourceFacts = null);

/// <summary>What the person told LensQi about themselves, so plans and coaching fit them: their height and the tools they have.</summary>
public sealed record PersonContext(int? HeightCm, IReadOnlyList<string> Tools)
{
    public const int MinHeightCm = 90, MaxHeightCm = 250, MaxTools = 8;

    /// <summary>Overhead reach standing on the floor, estimated as 1.33 × height. An estimate, never a measurement.</summary>
    public int? StandingReachCm => HeightCm is { } h ? (int)Math.Round(h * 1.33) : null;
}

/// <summary>Why a step was skipped, when the person said: their words, and the measurement behind them when the phone took one.</summary>
public sealed record SkipReason(string Text, int? PartHeightCm = null, int? ReachCm = null);

/// <summary>A part the phone's ARCore measured: camera distance and height above the floor plane. Measured, not inferred.</summary>
public sealed record MeasuredPart(string Part, int? DistanceCm, int? HeightAboveFloorCm);

/// <summary>An AI-written plan. <see cref="SourceNote"/> is set by the server, never by the model.</summary>
public sealed record TaskPlan(
    string Title, string Summary, RiskLevel Risk, int EstimatedMinutes,
    RestrictedCategory Restricted, bool ProfessionalRequired, string? ProfessionalReason,
    IReadOnlyList<string> Tools, IReadOnlyList<string> Parts, IReadOnlyList<TaskStep> Steps, string SourceNote);

/// <summary>A technician task's equipment on a site record; it enables the approved check and FieldProof verification.</summary>
public sealed record SiteLink(string FixtureId, string AssetId);

public static class TaskErrorCodes
{
    public const string InvalidState = "invalid_state";
    public const string UnknownStep = "unknown_step";
    public const string UnsafePlan = "unsafe_plan";
    public const string InvalidPlan = "invalid_plan";
    public const string SafetyNotAcknowledged = "safety_not_acknowledged";
    public const string DuplicateEvidence = "duplicate_evidence";
    public const string InvalidInput = "invalid_input";
}

public sealed class TaskConflictException(string code, string message) : Exception(message)
{
    public string Code { get; } = code;
}

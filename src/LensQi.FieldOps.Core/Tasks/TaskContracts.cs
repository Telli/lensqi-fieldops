namespace LensQi.FieldOps.Core.Tasks;

/// <summary>DIY: an everyday technical user with a goal. Technician: installs and repairs, optionally on a site record.</summary>
public enum TaskMode { Diy, Technician }

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
/// user to touch, loosen, fit or remove something; prepare and observe steps do not.
/// </summary>
public sealed record TaskStep(
    string Id, string Title, string Instruction, bool HandsOn,
    IReadOnlyList<string> LookFor, IReadOnlyList<Caution> Cautions, IReadOnlyList<string> StopIf, StepPhotoCheck? Check);

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

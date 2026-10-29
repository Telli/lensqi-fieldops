using System.Text.RegularExpressions;

namespace LensQi.FieldOps.Core.Tasks;

/// <summary>
/// Professionals-only topics, recognised in the user's own words. This does not depend on the model: a goal that names one
/// of these makes the plan restricted even if the model said otherwise.
/// </summary>
public static class RestrictedTopics
{
    private static readonly (RestrictedCategory Category, Regex Pattern)[] Rules =
    [
        (RestrictedCategory.GasLinesOrAppliances, Words("gas line", "gas lines", "gas pipe", "gas pipes", "gas valve", "gas meter", "gas leak", "gas stove", "gas range", "gas furnace", "gas water heater", "gas dryer", "propane line", "natural gas")),
        (RestrictedCategory.MainsElectrical, Words("breaker panel", "electrical panel", "main panel", "service panel", "fuse box", "subpanel", "sub-panel", "panel upgrade", "service entrance", "meter base", "live wire", "live wires", "while live", "240v", "240 volt")),
        (RestrictedCategory.EvHighVoltage, Words("high voltage battery", "high-voltage battery", "hv battery", "traction battery", "hybrid battery", "ev battery", "orange cable", "orange cables")),
        (RestrictedCategory.AirbagsSrs, Words("airbag", "airbags", "air bag", "srs", "seatbelt pretensioner", "clock spring")),
        (RestrictedCategory.Refrigerant, Words("refrigerant", "freon", "r134a", "r-134a", "r1234yf", "r-1234yf", "ac recharge", "a/c recharge", "recharge ac", "recharge the ac")),
        (RestrictedCategory.Structural, Words("load-bearing", "load bearing", "structural beam", "joist", "joists", "foundation crack", "remove a wall", "remove the wall")),
    ];

    public static RestrictedCategory Classify(string text) =>
        Rules.FirstOrDefault(r => r.Pattern.IsMatch(text)).Category;

    private static Regex Words(params string[] phrases) =>
        new(@"(?<![a-z0-9])(" + string.Join("|", phrases.Select(Regex.Escape)) + @")(?![a-z0-9])", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
}

/// <summary>
/// Checks an AI-written plan before it is recorded. Model output is untrusted: a plan that breaks a rule is rejected, never
/// repaired. Safety rules are enforced here, not only asked for in the prompt.
/// </summary>
public static class TaskPlanValidator
{
    public const int MaxSteps = 25;
    private static readonly Regex Concept = new("^[A-Za-z][A-Za-z0-9 -]{0,38}[A-Za-z0-9]$", RegexOptions.CultureInvariant);

    public static bool IsConcept(string phrase) =>
        Concept.IsMatch(phrase) && phrase.Split(' ', StringSplitOptions.RemoveEmptyEntries).Length <= 4;

    /// <summary>Throws <see cref="TaskConflictException"/> (unsafe_plan or invalid_plan) listing every violation.</summary>
    public static void Validate(TaskPlan plan, string goal, string? details)
    {
        var unsafeRules = new List<string>();
        var invalid = new List<string>();

        void Text(string? value, string field, int max, bool required = true)
        {
            if (string.IsNullOrWhiteSpace(value)) { if (required) invalid.Add($"{field} is empty"); return; }
            if (value.Length > max) invalid.Add($"{field} is longer than {max} characters");
            if (value.Contains("://", StringComparison.Ordinal)) invalid.Add($"{field} contains a link");
        }

        Text(plan.Title, "title", 120);
        Text(plan.Summary, "summary", 600);
        if (plan.EstimatedMinutes is < 1 or > 24 * 60) invalid.Add("estimatedMinutes is outside 1..1440");
        if (plan.Tools.Count > 20 || plan.Parts.Count > 20) invalid.Add("more than 20 tools or parts");
        foreach (var t in plan.Tools.Concat(plan.Parts)) Text(t, "tool or part", 80);
        if (plan.Steps.Count is < 1 or > MaxSteps) invalid.Add($"a plan needs 1..{MaxSteps} steps");
        if (plan.Steps.Select(s => s.Id).Distinct(StringComparer.Ordinal).Count() != plan.Steps.Count) invalid.Add("step ids are not unique");

        foreach (var s in plan.Steps)
        {
            var at = $"step {s.Id}";
            Text(s.Title, at + " title", 120);
            Text(s.Instruction, at + " instruction", 800);
            if (s.LookFor.Count > 3) invalid.Add($"{at} names more than 3 parts to outline");
            foreach (var c in s.LookFor) if (!IsConcept(c)) invalid.Add($"{at} part '{Short(c)}' is not a 1-4 word noun phrase");
            if (s.Cautions.Count > 5) invalid.Add($"{at} has more than 5 cautions");
            foreach (var c in s.Cautions) Text(c.Text, at + " caution", 300);
            if (s.StopIf.Count > 4) invalid.Add($"{at} has more than 4 stop conditions");
            foreach (var c in s.StopIf) Text(c, at + " stop condition", 300);
            if (s.Check is { } check) Text(check.WhatDoneLooksLike, at + " check", 300);
            if (plan.Risk == RiskLevel.High && s.HandsOn && s.Cautions.Count == 0) unsafeRules.Add($"{at} is hands-on in a high-risk plan but has no caution");
        }

        // Restricted work: the user's words decide as much as the model's label.
        var fromWords = RestrictedTopics.Classify(goal + " " + details);
        var restricted = plan.Restricted != RestrictedCategory.None ? plan.Restricted : fromWords;
        if (fromWords != RestrictedCategory.None && plan.Restricted == RestrictedCategory.None)
            unsafeRules.Add($"the goal concerns {fromWords} work but the plan is not marked restricted");
        if (restricted != RestrictedCategory.None)
        {
            if (!plan.ProfessionalRequired) unsafeRules.Add($"{restricted} work must require a professional");
            foreach (var s in plan.Steps.Where(s => s.HandsOn)) unsafeRules.Add($"step {s.Id} is hands-on in {restricted} work");
        }
        if (plan.ProfessionalRequired) Text(plan.ProfessionalReason, "professionalReason", 400);

        if (unsafeRules.Count > 0) throw new TaskConflictException(TaskErrorCodes.UnsafePlan, string.Join("; ", unsafeRules.Concat(invalid)));
        if (invalid.Count > 0) throw new TaskConflictException(TaskErrorCodes.InvalidPlan, string.Join("; ", invalid));
    }

    private static string Short(string s) => s.Length > 40 ? s[..40] + "…" : s;
}

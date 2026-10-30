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
        (RestrictedCategory.GasLinesOrAppliances, Words("gas line", "gas lines", "gas pipe", "gas pipes", "gas valve", "gas meter", "gas leak", "gas stove", "gas range", "gas furnace", "gas water heater", "gas dryer", "gas fireplace", "gas heater", "gas grill line", "propane line", "propane tank valve", "natural gas")),
        (RestrictedCategory.MainsElectrical, Words("breaker panel", "electrical panel", "main panel", "service panel", "fuse box", "subpanel", "sub-panel", "panel upgrade", "service entrance", "meter base", "circuit breaker", "breaker box", "live wire", "live wires", "while live", "240v", "240 volt")),
        (RestrictedCategory.EvHighVoltage, Words("high voltage battery", "high-voltage battery", "hv battery", "traction battery", "hybrid battery", "ev battery", "orange cable", "orange cables")),
        (RestrictedCategory.AirbagsSrs, Words("airbag", "airbags", "air bag", "srs", "seatbelt pretensioner", "clock spring")),
        (RestrictedCategory.Refrigerant, Words("refrigerant", "freon", "r134a", "r-134a", "r1234yf", "r-1234yf", "ac recharge", "a/c recharge", "recharge ac", "recharge the ac", "recharge my ac", "recharge my car's ac", "recharge the a/c", "car ac recharge", "ac refrigerant")),
        (RestrictedCategory.Structural, Words("load-bearing", "load bearing", "structural beam", "joist", "joists", "foundation crack", "remove a wall", "remove the wall")),
    ];

    public static RestrictedCategory Classify(string? text) =>
        string.IsNullOrEmpty(text) ? RestrictedCategory.None : Rules.FirstOrDefault(r => r.Pattern.IsMatch(text.Replace('’', '\''))).Category;

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
    private static readonly Regex Concept = new("^[A-Za-z][A-Za-z0-9 '-]{0,38}[A-Za-z0-9]$", RegexOptions.CultureInvariant);
    private static readonly Regex Link = new(@"://|(?<![A-Za-z0-9])www\.[A-Za-z0-9-]", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

    /// <summary>A URL or a bare web address ("www.example.com"). Model text that the user reads or hears never carries one.</summary>
    public static bool HasLink(string? text) => text is not null && Link.IsMatch(text);

    public static bool IsConcept(string? phrase) => phrase is not null &&
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
            if (HasLink(value)) invalid.Add($"{field} contains a link");
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
        if (restricted != RestrictedCategory.None && !plan.ProfessionalRequired) unsafeRules.Add($"{restricted} work must require a professional");
        // A plan that needs a professional never guides hands-on work, whatever the model labelled each step: the step's
        // own words are checked too.
        if (restricted != RestrictedCategory.None || plan.ProfessionalRequired)
        {
            var why = restricted != RestrictedCategory.None ? restricted.ToString() : "professional-only";
            foreach (var s in plan.Steps.Where(s => s.HandsOn)) unsafeRules.Add($"step {s.Id} is hands-on in {why} work");
            foreach (var s in plan.Steps.Where(s => !s.HandsOn && (TaskSafety.InstructsHandsOn(s.Instruction) || TaskSafety.InstructsHandsOn(s.Title))))
                unsafeRules.Add($"step {s.Id} instructs hands-on work in {why} work");
        }
        if (plan.ProfessionalRequired) Text(plan.ProfessionalReason, "professionalReason", 400);

        if (unsafeRules.Count > 0) throw new TaskConflictException(TaskErrorCodes.UnsafePlan, string.Join("; ", unsafeRules.Concat(invalid)));
        if (invalid.Count > 0) throw new TaskConflictException(TaskErrorCodes.InvalidPlan, string.Join("; ", invalid));
    }

    private static string Short(string s) => s.Length > 40 ? s[..40] + "…" : s;
}

/// <summary>
/// Safety checks on free text the model writes (step instructions, answers, live guidance) for professionals-only work.
/// The model's own labels are not trusted alone: a sentence that tells the person to act on the hazard counts as hands-on,
/// unless it is negated ("do not open the panel") or delegated ("have a licensed electrician replace it").
/// </summary>
public static class TaskSafety
{
    public const string Referral = "This is work for a qualified professional; LensQi can help you look and document, not do it.";

    // An action counts when its object, within two words, is the hazard itself ("remove the panel cover", "disconnect the gas
    // line", "replace the old breaker"); clearing access or unplugging appliances does not. A few verbs are hazardous on their own.
    private const string Hazard = @"(panel|panels|cover|covers|breaker|breakers|fuse|fuses|wire|wires|wiring|conductor|conductors|terminal|terminals|bus|busbar|meter|gas|line|lines|pipe|pipes|valve|valves|fitting|fittings|connector|connectors|airbag|airbags|inflator|module|battery|batteries|cable|cables|refrigerant|freon|compressor|wall|beam|joist|joists|stud|studs|support|supports)";
    private static readonly Regex Action = new($@"(?<![a-z])((disconnect|reconnect|unscrew|unbolt|loosen|tighten|pry|cut|strip|remove|replace|install|detach|reattach|unplug|open|close|turn|take off|take out|take apart|wire)\s+(\S+\s+){{0,2}}{Hazard}(?![a-z])|(splice|solder|crimp|rewire|bleed|discharge|depressuri[sz]e|purge|recharge)(?![a-z]))",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private const string Pro = @"(licensed |qualified |certified |a |an |the |your )*(electrician|plumber|professional|pro|technician|installer|contractor|engineer|dealer|mechanic|gas company|utility)";
    // "Do not open the panel", "never disconnect…": a negation within three words before the action.
    private static readonly Regex NegatedBefore = new(@"(do not|don't|dont|never|avoid|no need to)\s+(\S+\s+){0,2}$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    // "Have a licensed electrician replace…", "a plumber should disconnect…": the action belongs to someone else, but only
    // when the delegation comes before it in the same clause. "Remove the cover, and call an electrician" and "call an
    // electrician, then remove the cover" still tell the person to act.
    private static readonly Regex Delegated = new($@"((have|let|ask|call|get|hire|book)\s+{Pro}|{Pro}\s+(should|will|can|must|needs to|has to))", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

    private static readonly Regex ClauseBreak = new(@"(?<![a-z])then(?![a-z])", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

    /// <summary>True when any sentence tells the person to do hands-on work themselves.</summary>
    public static bool InstructsHandsOn(string? text) =>
        !string.IsNullOrWhiteSpace(text) && Regex.Split(text.Replace('’', '\''), @"(?<=[.!?;:])\s+|\n+").Any(sentence =>
            Action.Matches(sentence).Any(m => !NegatedBefore.IsMatch(sentence[..m.Index]) && !DelegatedBefore(sentence, m.Index)));

    private static bool DelegatedBefore(string sentence, int actionAt)
    {
        var clauseStart = ClauseBreak.Matches(sentence[..actionAt]).LastOrDefault() is { } brk ? brk.Index + brk.Length : 0;
        return Delegated.Matches(sentence[clauseStart..actionAt]).Count > 0;
    }

    /// <summary>Whether this task, or the words of a question about it, concerns professionals-only work.</summary>
    public static bool IsRestricted(TaskState task, string? text = null) =>
        task.Plan is { } plan && (plan.Restricted != RestrictedCategory.None || plan.ProfessionalRequired)
        || RestrictedTopics.Classify(task.Goal + " " + task.Details) != RestrictedCategory.None
        || RestrictedTopics.Classify(text) != RestrictedCategory.None;

    /// <summary>
    /// What may be shown for model text in restricted work: hands-on instructions are replaced by the referral, anything
    /// else keeps the referral attached. Outside restricted work the text is returned as is.
    /// </summary>
    public static string Guard(TaskState task, string modelText, string? question = null) =>
        !IsRestricted(task, question) ? modelText
        : InstructsHandsOn(modelText) ? Referral
        : modelText.Contains(Referral, StringComparison.Ordinal) ? modelText : modelText + " " + Referral;
}

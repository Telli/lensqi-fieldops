namespace LensQi.FieldOps.Api.Shared;

/// <summary>Canonical error shape with stable codes (design.md §8 Errors).</summary>
public sealed record ApiError(string Code, string Message, IReadOnlyList<string>? Details = null)
{
    public static ApiError NotFound(string message) => new("not_found", message);
    public static ApiError InvalidPack(IReadOnlyList<string> details) => new("invalid_pack", "Knowledge pack failed validation", details);
    public static ApiError BindingInvalid(IReadOnlyList<string> details) => new("binding_invalid", "Fixture binding failed validation", details);
}

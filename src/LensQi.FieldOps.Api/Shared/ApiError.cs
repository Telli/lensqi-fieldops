namespace LensQi.FieldOps.Api.Shared;

/// <summary>Canonical error shape with stable codes (design.md §8 Errors).</summary>
public sealed record ApiError(string Code, string Message, IReadOnlyList<string>? Details = null)
{
    public static ApiError NotFound(string message) => new("not_found", message);
    public static ApiError BadRequest(string message) => new("bad_request", message);
    public static ApiError InvalidPack(IReadOnlyList<string> details) => new("invalid_pack", "Knowledge pack failed validation", details);
    public static ApiError BindingInvalid(IReadOnlyList<string> details) => new("binding_invalid", "Fixture binding failed validation", details);
}

public static class ApiResults
{
    public static IResult Conflict(string code, string message) => Results.Json(new ApiError(code, message), statusCode: StatusCodes.Status409Conflict);
    public static IResult BadRequest(string message) => Results.Json(ApiError.BadRequest(message), statusCode: StatusCodes.Status400BadRequest);
    public static IResult NotFound(string message) => Results.Json(ApiError.NotFound(message), statusCode: StatusCodes.Status404NotFound);
}

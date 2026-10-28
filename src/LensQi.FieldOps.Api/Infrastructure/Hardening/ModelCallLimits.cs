using System.Threading.RateLimiting;
using LensQi.FieldOps.Api.Shared;
using Microsoft.AspNetCore.Http.Timeouts;
using Microsoft.AspNetCore.RateLimiting;

namespace LensQi.FieldOps.Api.Infrastructure.Hardening;

/// <summary>T6.1 limits. Defaults live in appsettings.json; every value can be overridden per deployment.</summary>
public sealed class HardeningOptions
{
    public const string SectionName = "Hardening";
    /// <summary>Server-side wall clock for one model endpoint request, above the provider client's own timeout.</summary>
    public int ModelRequestTimeoutSeconds { get; set; } = 90;
    /// <summary>Model-backed requests one inspection may make within <see cref="InspectionWindowHours"/>.</summary>
    public int ModelCallsPerInspection { get; set; } = 30;
    public int InspectionWindowHours { get; set; } = 24;
    public int ModelCallsPerHourPerIp { get; set; } = 120;
    public int MinImageShortSide { get; set; } = 240;
    public int MinImageLongSide { get; set; } = 320;
    public long MaxImagePixels { get; set; } = 50_000_000;
}

/// <summary>Marks an endpoint that spends Meta Model API calls (Muse or SAM).</summary>
public sealed class ModelCallEndpoint;

/// <summary>
/// Request timeouts and rate limits for model-backed endpoints (T6.1). Limits are in-memory and reset on restart:
/// they protect the API credit and the provider from runaway clients, they are not an audit control.
/// </summary>
public static class ModelCallLimits
{
    public const string TimeoutPolicy = "model-call";

    public static IServiceCollection AddModelCallLimits(this IServiceCollection services, IConfiguration configuration)
    {
        var o = new HardeningOptions();
        configuration.GetSection(HardeningOptions.SectionName).Bind(o);
        services.AddSingleton(o);

        services.AddRequestTimeouts(t => t.AddPolicy(TimeoutPolicy, new RequestTimeoutPolicy
        {
            Timeout = TimeSpan.FromSeconds(o.ModelRequestTimeoutSeconds),
            TimeoutStatusCode = StatusCodes.Status504GatewayTimeout,
            WriteTimeoutResponse = ctx => ctx.Response.WriteAsJsonAsync(new ApiError("model_timeout", $"Model request exceeded {o.ModelRequestTimeoutSeconds}s and was cancelled")),
        }));

        services.AddRateLimiter(r =>
        {
            r.RejectionStatusCode = StatusCodes.Status429TooManyRequests;
            r.OnRejected = async (ctx, ct) =>
            {
                if (ctx.Lease.TryGetMetadata(MetadataName.RetryAfter, out var retry))
                    ctx.HttpContext.Response.Headers.RetryAfter = ((int)Math.Ceiling(retry.TotalSeconds)).ToString();
                await ctx.HttpContext.Response.WriteAsJsonAsync(new ApiError("rate_limited", "Model call limit reached for this inspection or client; try again later"), ct);
            };
            r.GlobalLimiter = PartitionedRateLimiter.CreateChained(
                PartitionedRateLimiter.Create<HttpContext, string>(ctx => IsModelCall(ctx)
                    ? RateLimitPartition.GetFixedWindowLimiter("ip:" + (ctx.Connection.RemoteIpAddress?.ToString() ?? "unknown"), _ => Window(o.ModelCallsPerHourPerIp, TimeSpan.FromHours(1)))
                    : RateLimitPartition.GetNoLimiter("other")),
                PartitionedRateLimiter.Create<HttpContext, string>(ctx => IsModelCall(ctx) && ctx.Request.RouteValues["id"] is string id
                    ? RateLimitPartition.GetFixedWindowLimiter("inspection:" + id, _ => Window(o.ModelCallsPerInspection, TimeSpan.FromHours(o.InspectionWindowHours)))
                    : RateLimitPartition.GetNoLimiter("other")));
        });
        return services;
    }

    public static WebApplication UseModelCallLimits(this WebApplication app)
    {
        // Routing first so the limiter can read endpoint metadata and the inspection id route value.
        app.UseRouting();
        app.UseRateLimiter();
        app.UseRequestTimeouts();
        return app;
    }

    /// <summary>Applies the model-call rate limits and the request timeout to one endpoint.</summary>
    public static TBuilder AsModelCall<TBuilder>(this TBuilder endpoint) where TBuilder : IEndpointConventionBuilder
    {
        endpoint.WithMetadata(new ModelCallEndpoint());
        endpoint.WithRequestTimeout(TimeoutPolicy);
        return endpoint;
    }

    private static bool IsModelCall(HttpContext ctx) => ctx.GetEndpoint()?.Metadata.GetMetadata<ModelCallEndpoint>() is not null;

    private static FixedWindowRateLimiterOptions Window(int permits, TimeSpan window) =>
        new() { PermitLimit = permits, Window = window, QueueLimit = 0, AutoReplenishment = true };
}

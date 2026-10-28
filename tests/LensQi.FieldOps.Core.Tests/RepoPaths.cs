namespace LensQi.FieldOps.Tests;

/// <summary>Locates the repository's fixtures directory from the test binary location.</summary>
public static class RepoPaths
{
    public static string FixturesDir { get; } = Find();

    public static string Fixture(string name) => Path.Combine(FixturesDir, name);

    private static string Find()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        for (var i = 0; dir is not null && i < 10; i++, dir = dir.Parent)
        {
            var candidate = Path.Combine(dir.FullName, "fixtures", "vendor-knowledge");
            if (Directory.Exists(candidate)) return candidate;
        }
        throw new DirectoryNotFoundException("fixtures/vendor-knowledge not found above the test binary.");
    }
}

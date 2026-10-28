using Microsoft.Extensions.AI;

namespace LensQi.FieldOps.Tests;

/// <summary>Records prompts and images, returns a canned response. Lets tests assert what the model was and was not told.</summary>
public sealed class FakeChatClient : IChatClient
{
    public List<string> Prompts { get; } = new();
    public List<DataContent> Images { get; } = new();
    public ChatOptions? LastOptions { get; private set; }
    public string CannedResponse { get; set; } = "{}";

    public Task<ChatResponse> GetResponseAsync(IEnumerable<ChatMessage> messages, ChatOptions? options = null, CancellationToken cancellationToken = default)
    {
        var list = messages.ToList();
        Prompts.Add(string.Join("\n\n", list.SelectMany(m => m.Contents.OfType<TextContent>().Select(t => t.Text))));
        Images.AddRange(list.SelectMany(m => m.Contents.OfType<DataContent>()));
        LastOptions = options;
        return Task.FromResult(new ChatResponse(new ChatMessage(ChatRole.Assistant, CannedResponse)) { ModelId = "fake-muse" });
    }

    public async IAsyncEnumerable<ChatResponseUpdate> GetStreamingResponseAsync(IEnumerable<ChatMessage> messages, ChatOptions? options = null,
        [System.Runtime.CompilerServices.EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        var r = await GetResponseAsync(messages, options, cancellationToken);
        foreach (var u in r.ToChatResponseUpdates()) yield return u;
    }

    public object? GetService(Type serviceType, object? serviceKey = null) => null;
    public void Dispose() { }
}

using System.Security.Cryptography;
using Conversor.Core;

var root = Path.Combine(Path.GetTempPath(), "ConversorLocal-test-" + Guid.NewGuid().ToString("N"));
Directory.CreateDirectory(root);
int passed = 0;
void Check(bool condition, string message) { if (!condition) throw new Exception(message); passed++; Console.WriteLine("PASS " + message); }
try
{
    var source = Path.Combine(root, "Artigo científico & revisão.pdf");
    var valid = Path.Combine(root, "verified.bin");
    File.WriteAllText(source, "original-preserved"); File.WriteAllText(valid, "validated-content");
    var originalHash = SHA256.HashData(File.ReadAllBytes(source));
    var first = AtomicPublisher.Publish(valid, source, "docx");
    Check(first == Path.Combine(root, "Artigo científico & revisão.docx"), "same directory and exact base name");
    var second = AtomicPublisher.Publish(valid, source, "docx");
    Check(second == Path.Combine(root, "Artigo científico & revisão (1).docx"), "existing target receives numeric suffix");
    var concurrent = await Task.WhenAll(Enumerable.Range(0, 16).Select(_ => Task.Run(() => AtomicPublisher.Publish(valid, source, "docx"))));
    Check(concurrent.Distinct(StringComparer.OrdinalIgnoreCase).Count() == 16, "16 simultaneous publishers reserve unique names atomically");
    Check(concurrent.All(p => File.ReadAllText(p) == "validated-content"), "concurrent outputs preserve complete bytes");
    Check(SHA256.HashData(File.ReadAllBytes(source)).SequenceEqual(originalHash), "original bytes unchanged");
    using var cts = new CancellationTokenSource(); cts.Cancel();
    var before = Directory.GetFiles(root).Length;
    try { AtomicPublisher.Publish(valid, source, "pptx", cts.Token); throw new Exception("expected cancellation"); }
    catch (OperationCanceledException) { }
    Check(Directory.GetFiles(root).Length == before, "cancel before commit leaves no output or partial");
    Check(!Directory.GetFiles(root).Any(p => p.EndsWith(".partial")), "no incomplete files remain");
    Directory.CreateDirectory(Path.Combine(root, "Artigo científico & revisão.pptx"));
    Check(AtomicPublisher.Publish(valid, source, "pptx").EndsWith(" (1).pptx"), "directory at target name is never replaced");
    Check(Formats.IsValid("file.DOCX", "pdf") && Formats.IsValid("file.PPTX", "pdf") && Formats.IsValid("file.PDF", "docx") && Formats.IsValid("file.pdf", "pptx"), "all four and only valid routes");
    Check(!Formats.IsValid("file.pdf", "pdf") && !Formats.IsValid("file.txt", "docx"), "invalid routes rejected");
    Console.WriteLine($"{passed} assertions passed.");
    return 0;
}
finally { Directory.Delete(root, true); }

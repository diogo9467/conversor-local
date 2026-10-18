using System.Diagnostics;
using System.Text;
using System.Text.Json;
using System.Runtime.InteropServices;

namespace Conversor.Core;

public sealed record ConversionRequest(string Source, string Target, bool Ocr = false);
public sealed record ConversionProgress(string Stage, int Percent = 0, string? Detail = null);
public sealed record ConversionResult(string Source, string? Output, bool Success, string? Error,
    IReadOnlyList<string> Warnings, int Pages = 0, bool Cancelled = false);

public static class Formats
{
    public static bool IsValid(string source, string target) =>
        (Path.GetExtension(source).ToLowerInvariant(), target.ToLowerInvariant()) is
        (".docx", "pdf") or (".pptx", "pdf") or (".pdf", "docx") or (".pdf", "pptx");
}

public static class AtomicPublisher
{
    // Copy to a temporary FILE beside the source, then rename atomically without replacing.
    // No output directory is invented. The move is also the name-collision arbitration.
    public static string Publish(string validatedFile, string source, string extension, CancellationToken cancellation = default)
    {
        source = Path.GetFullPath(source);
        var directory = Path.GetDirectoryName(source) ?? throw new IOException("Pasta de origem inválida.");
        var temporary = Path.Combine(directory, $".conversor-{Guid.NewGuid():N}.partial");
        try
        {
            cancellation.ThrowIfCancellationRequested();
            using (var input = File.OpenRead(validatedFile))
            using (var output = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None,
                1024 * 128, FileOptions.WriteThrough))
            {
                var buffer = new byte[1024 * 128];
                int n;
                while ((n = input.Read(buffer)) != 0)
                {
                    cancellation.ThrowIfCancellationRequested();
                    output.Write(buffer, 0, n);
                }
                output.Flush(flushToDisk: true);
            }
            for (var suffix = 0; suffix < 10000; suffix++)
            {
                cancellation.ThrowIfCancellationRequested();
                var name = Path.GetFileNameWithoutExtension(source) + (suffix == 0 ? "" : $" ({suffix})") + "." + extension;
                var candidate = Path.Combine(directory, name);
                if (File.Exists(candidate) || Directory.Exists(candidate)) continue;
                try
                {
                    File.Move(temporary, candidate, overwrite: false);
                    return candidate;
                }
                catch (IOException) when (File.Exists(candidate) || Directory.Exists(candidate)) { }
            }
            throw new IOException("Não foi possível encontrar um nome de destino livre na pasta de origem.");
        }
        catch (UnauthorizedAccessException ex)
        {
            throw new IOException("Sem permissão de escrita na pasta de origem. Nenhum arquivo foi salvo em outra pasta.", ex);
        }
        finally
        {
            try { if (File.Exists(temporary)) File.Delete(temporary); } catch (IOException) { }
        }
    }

    public static void CheckWritable(string source)
    {
        var directory = Path.GetDirectoryName(Path.GetFullPath(source))!;
        var path = Path.Combine(directory, $".conversor-{Guid.NewGuid():N}.probe");
        try
        {
            using var probe = new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.None, 1, FileOptions.DeleteOnClose);
        }
        catch (UnauthorizedAccessException ex)
        {
            throw new IOException("Sem permissão de escrita na pasta de origem. Escolha uma pasta com permissão e tente novamente.", ex);
        }
        catch (IOException ex)
        {
            throw new IOException("Não foi possível gravar na pasta de origem (permissão, espaço ou unidade indisponível).", ex);
        }
    }
}

public sealed class ConversionRunner(string applicationDirectory)
{
    public static string DataDirectory => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ConversorLocal");

    public async Task<ConversionResult> ConvertAsync(ConversionRequest request, IProgress<ConversionProgress>? progress,
        CancellationToken cancellation)
    {
        var warnings = new List<string>();
        var source = Path.GetFullPath(request.Source);
        var job = Path.Combine(DataDirectory, "jobs", Guid.NewGuid().ToString("N"));
        try
        {
            if (!Formats.IsValid(source, request.Target)) throw new IOException("Conversão não suportada para este tipo de arquivo.");
            if (!File.Exists(source)) throw new IOException("O arquivo de origem não existe ou está indisponível.");
            AtomicPublisher.CheckWritable(source);
            Directory.CreateDirectory(job);
            var input = Path.Combine(job, "input" + Path.GetExtension(source).ToLowerInvariant());
            // Hold the original read-only while taking a coherent snapshot; no engine receives its path.
            using (var original = new FileStream(source, FileMode.Open, FileAccess.Read, FileShare.Read, 128 * 1024, true))
            await using (var snapshot = new FileStream(input, FileMode.CreateNew, FileAccess.Write, FileShare.None, 128 * 1024, true))
                await original.CopyToAsync(snapshot, cancellation);

            var output = Path.Combine(job, "output." + request.Target);
            var spec = new { source = input, output, target = request.Target, ocr = request.Ocr,
                runtime = Path.GetFullPath(applicationDirectory), job };
            var requestPath = Path.Combine(job, "request.json");
            await File.WriteAllTextAsync(requestPath, JsonSerializer.Serialize(spec), Encoding.UTF8, cancellation);
            var engine = Path.Combine(applicationDirectory, "engine", "Conversor.Engine.exe");
            if (!File.Exists(engine)) throw new IOException("Motor de conversão não encontrado. Reinstale o Conversor Local.");
            var start = new ProcessStartInfo(engine) { UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardOutput = true, RedirectStandardError = true, StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8, WorkingDirectory = job };
            start.ArgumentList.Add("--request");
            start.ArgumentList.Add(requestPath);
            start.Environment["PYTHONUTF8"] = "1";
            using var process = Process.Start(start) ?? throw new IOException("Não foi possível iniciar o motor de conversão.");
            using var jobObject = OperatingSystem.IsWindows() ? ProcessJob.Attach(process) : null;
            using var timeout = new CancellationTokenSource(TimeSpan.FromMinutes(30));
            using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellation, timeout.Token);
            using var registration = linked.Token.Register(() => { try { process.Kill(entireProcessTree: true); } catch (InvalidOperationException) { } });
            var stderr = process.StandardError.ReadToEndAsync();
            var success = false;
            var pages = 0;
            string? engineError = null;
            try
            {
                while (await process.StandardOutput.ReadLineAsync(linked.Token) is { } line)
                {
                    try
                    {
                        using var json = JsonDocument.Parse(line);
                        var item = json.RootElement;
                        var type = item.GetProperty("type").GetString();
                        if (type == "progress") progress?.Report(new(item.GetProperty("message").GetString()!, item.GetProperty("percent").GetInt32()));
                        if (type == "warning") warnings.Add(item.GetProperty("message").GetString()!);
                        if (type == "error") engineError = item.GetProperty("message").GetString();
                        if (type == "result") { success = item.GetProperty("success").GetBoolean(); pages = item.GetProperty("pages").GetInt32(); }
                    }
                    catch (JsonException) { /* Third-party library log; not a success signal. */ }
                    catch (KeyNotFoundException) { }
                }
                await process.WaitForExitAsync(linked.Token);
            }
            catch (OperationCanceledException) when (timeout.IsCancellationRequested && !cancellation.IsCancellationRequested)
            {
                throw new IOException("A conversão excedeu 30 minutos e foi interrompida. O original foi preservado.");
            }
            var diagnostic = await stderr;
            cancellation.ThrowIfCancellationRequested();
            if (process.ExitCode != 0 || !success || !File.Exists(output) || new FileInfo(output).Length == 0)
            {
                Log("engine", source, diagnostic);
                throw new IOException(engineError ?? "Não foi possível converter ou validar o documento. Consulte o registro local.");
            }
            progress?.Report(new("Salvando na pasta de origem", 98));
            var final = AtomicPublisher.Publish(output, source, request.Target, cancellation);
            // Publication is the commit point: a later cancellation must not mislabel a valid result.
            ShellNotifications.Created(final);
            Log("success", source, final);
            return new(source, final, true, null, warnings, pages);
        }
        catch (OperationCanceledException)
        {
            return new(source, null, false, "Conversão cancelada. O original foi preservado.", warnings, Cancelled: true);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException or System.ComponentModel.Win32Exception)
        {
            Log("failure", source, ex.ToString());
            return new(source, null, false, ex.Message, warnings);
        }
        finally
        {
            // Delete only this job's directory. Never traverse a source directory for cleanup.
            try { if (Directory.Exists(job)) Directory.Delete(job, recursive: true); }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { Log("cleanup", "", ex.Message); }
        }
    }

    public static void Log(string type, string source, string message)
    {
        try
        {
            var path = Path.Combine(DataDirectory, "logs");
            Directory.CreateDirectory(path);
            var log = Path.Combine(path, $"{DateTime.UtcNow:yyyy-MM-dd}.jsonl");
            using var stream = new FileStream(log, FileMode.Append, FileAccess.Write, FileShare.ReadWrite);
            using var writer = new StreamWriter(stream, new UTF8Encoding(false));
            writer.WriteLine(JsonSerializer.Serialize(new { at = DateTimeOffset.UtcNow, type, source, message }));
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { }
    }
}

internal static partial class ShellNotifications
{
    [LibraryImport("shell32.dll", EntryPoint = "SHChangeNotify", StringMarshalling = StringMarshalling.Utf16)]
    private static partial void Notify(uint eventId, uint flags, string item1, nint item2);
    public static void Created(string file)
    {
        if (!OperatingSystem.IsWindows()) return;
        Notify(0x00000002, 0x1005, file, 0); // SHCNE_CREATE | SHCNF_PATHW | SHCNF_FLUSHNOWAIT
        Notify(0x00001000, 0x1005, Path.GetDirectoryName(file)!, 0); // SHCNE_UPDATEDIR
    }
}

// KILL_ON_JOB_CLOSE prevents an orphan LibreOffice/PDF/OCR process if the UI is closed.
internal sealed class ProcessJob : IDisposable
{
    private nint handle;
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] private static extern nint CreateJobObject(nint attributes, string? name);
    [DllImport("kernel32.dll")] private static extern bool SetInformationJobObject(nint job, int type, nint info, uint length);
    [DllImport("kernel32.dll")] private static extern bool AssignProcessToJobObject(nint job, nint process);
    [DllImport("kernel32.dll")] private static extern bool CloseHandle(nint handle);
    private ProcessJob(nint h) { handle = h; }
    public static ProcessJob Attach(Process process)
    {
        var h = CreateJobObject(0, null);
        if (h == 0) return new(0);
        var memory = Marshal.AllocHGlobal(144);
        try
        {
            Marshal.Copy(new byte[144], 0, memory, 144);
            Marshal.WriteInt32(memory, 16, 0x2000);
            if (!SetInformationJobObject(h, 9, memory, 144) || !AssignProcessToJobObject(h, process.Handle)) { CloseHandle(h); h = 0; }
        }
        finally { Marshal.FreeHGlobal(memory); }
        return new(h);
    }
    public void Dispose() { if (handle != 0) { CloseHandle(handle); handle = 0; } }
}

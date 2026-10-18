using System.Text.Json;
using System.Diagnostics;
using Conversor.Core;

namespace Conversor.App;

internal sealed record Options(string Target, string[] Files, bool NoUi, string? Report, bool Ocr);
internal sealed record Preferences(bool Ocr = true)
{
    private static string FilePath => Path.Combine(ConversionRunner.DataDirectory, "settings.json");
    public static Preferences Load()
    {
        try { return JsonSerializer.Deserialize<Preferences>(File.ReadAllText(FilePath)) ?? new(); }
        catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException) { return new(); }
    }
    public void Save()
    {
        Directory.CreateDirectory(ConversionRunner.DataDirectory);
        File.WriteAllText(FilePath, JsonSerializer.Serialize(this));
    }
}

internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        ApplicationConfiguration.Initialize();
        try
        {
            if (args.Contains("--register-modern")) return ModernPackage(register: true);
            if (args.Contains("--unregister-modern")) return ModernPackage(register: false);
            if (args.Length == 0 || args.Contains("--settings"))
            {
                Application.Run(new SettingsForm());
                return 0;
            }
            var options = Parse(args);
            if (options.Files.Length == 0) throw new ArgumentException("Nenhum arquivo foi selecionado.");
            if (options.NoUi)
            {
                using var cancel = new CancellationTokenSource();
                Console.CancelKeyPress += (_, e) => { e.Cancel = true; cancel.Cancel(); };
                var results = BatchAsync(options, null, cancel.Token).GetAwaiter().GetResult();
                WriteReport(options, results);
                return results.All(r => r.Success) ? 0 : 1;
            }
            using var form = new ProgressForm(options);
            Application.Run(form);
            return form.ExitCode;
        }
        catch (Exception ex)
        {
            ConversionRunner.Log("app", "", ex.ToString());
            if (!args.Contains("--no-ui")) MessageBox.Show(ex.Message, "Conversor Local", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 2;
        }
    }

    private static int ModernPackage(bool register)
    {
        var package = Path.Combine(AppContext.BaseDirectory, "modern", "ConversorLocal-Shell.msix");
        if (register && !File.Exists(package)) return 0; // Unsigned releases use the classic menu.
        var start = new ProcessStartInfo(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), "WindowsPowerShell", "v1.0", "powershell.exe"))
        { UseShellExecute = false, CreateNoWindow = true, RedirectStandardError = true, RedirectStandardOutput = true };
        foreach (var argument in new[] { "-NoLogo", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command",
            register ? "$ErrorActionPreference='Stop'; Add-AppxPackage -Path (Join-Path $env:CONVERSOR_APP_DIRECTORY 'modern\\ConversorLocal-Shell.msix') -ExternalLocation $env:CONVERSOR_APP_DIRECTORY" :
            "$ErrorActionPreference='Stop'; Get-AppxPackage -Name ConversorLocal.Shell | Remove-AppxPackage" }) start.ArgumentList.Add(argument);
        start.Environment["CONVERSOR_APP_DIRECTORY"] = AppContext.BaseDirectory;
        using var process = Process.Start(start)!;
        var error = process.StandardError.ReadToEndAsync();
        var output = process.StandardOutput.ReadToEndAsync();
        if (!process.WaitForExit(120000)) { process.Kill(true); return 1; }
        if (process.ExitCode != 0) ConversionRunner.Log("modern-package", "", error.GetAwaiter().GetResult());
        return process.ExitCode;
    }

    private static Options Parse(string[] args)
    {
        var files = new List<string>();
        var target = "";
        var noUi = false;
        var ocr = Preferences.Load().Ocr;
        string? report = null;
        var positional = false;
        for (var i = 0; i < args.Length; i++)
        {
            var arg = args[i];
            if (positional) { files.Add(arg); continue; }
            switch (arg)
            {
                case "--": positional = true; break;
                case "--to": target = args[++i].ToLowerInvariant(); break;
                case "--no-ui": noUi = true; break;
                case "--ocr": ocr = true; break;
                case "--no-ocr": ocr = false; break;
                case "--report": report = args[++i]; break;
                case "--batch":
                    var batchPath = Path.GetFullPath(args[++i]);
                    using (var json = JsonDocument.Parse(File.ReadAllText(batchPath)))
                    {
                        target = json.RootElement.GetProperty("target").GetString()!;
                        files.AddRange(json.RootElement.GetProperty("files").EnumerateArray().Select(f => f.GetString()!));
                    }
                    var queue = Path.Combine(ConversionRunner.DataDirectory, "queue") + Path.DirectorySeparatorChar;
                    if (batchPath.StartsWith(queue, StringComparison.OrdinalIgnoreCase) && Path.GetExtension(batchPath) == ".json")
                        File.Delete(batchPath);
                    break;
                default:
                    if (arg.StartsWith("--")) throw new ArgumentException($"Opção desconhecida: {arg}");
                    files.Add(arg);
                    break;
            }
        }
        if (!Formats.TargetIds.Contains(target)) throw new ArgumentException("Formato de destino não suportado. Use uma das opções do menu Converter arquivo.");
        return new(target, files.Select(Path.GetFullPath).Distinct(StringComparer.OrdinalIgnoreCase).ToArray(), noUi, report, ocr);
    }

    internal static async Task<List<ConversionResult>> BatchAsync(Options options, Action<string, ConversionProgress>? update, CancellationToken cancellation)
    {
        var runner = new ConversionRunner(AppContext.BaseDirectory);
        var results = new List<ConversionResult>();
        // A batch is serial to bound memory use. Separate invocations are fully isolated.
        for (var i = 0; i < options.Files.Length; i++)
        {
            var file = options.Files[i];
            if (cancellation.IsCancellationRequested)
            {
                results.Add(new(file, null, false, "Não convertido: lote cancelado.", [], Cancelled: true));
                continue;
            }
            var label = $"{i + 1} de {options.Files.Length} · {Path.GetFileName(file)}";
            var progress = new Progress<ConversionProgress>(p => update?.Invoke(label, p));
            var result = await runner.ConvertAsync(new(file, options.Target, options.Ocr), progress, cancellation);
            results.Add(result);
        }
        return results;
    }

    internal static void WriteReport(Options options, List<ConversionResult> results)
    {
        if (options.Report is null) return;
        File.WriteAllText(Path.GetFullPath(options.Report), JsonSerializer.Serialize(results, new JsonSerializerOptions { WriteIndented = true }));
    }
}

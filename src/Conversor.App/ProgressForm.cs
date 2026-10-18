using Conversor.Core;

namespace Conversor.App;

internal sealed class ProgressForm : Form
{
    private readonly Options options;
    private readonly CancellationTokenSource cancellation = new();
    private readonly Label heading = new() { AutoSize = false, Text = "Preparando a conversão", Font = new Font("Segoe UI", 14, FontStyle.Bold), Dock = DockStyle.Top, Height = 42 };
    private readonly Label filename = new() { Dock = DockStyle.Top, Height = 42, AutoEllipsis = true };
    private readonly Label status = new() { Dock = DockStyle.Top, Height = 30, Text = "O resultado será salvo junto do original." };
    private readonly ProgressBar bar = new() { Dock = DockStyle.Top, Height = 10, Maximum = 100 };
    private readonly TextBox details = new() { Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical, Dock = DockStyle.Fill, Visible = false, BorderStyle = BorderStyle.None, BackColor = Color.White };
    private readonly Button action = new() { Text = "Cancelar", Width = 110, Height = 34, Anchor = AnchorStyles.Right | AnchorStyles.Bottom };
    private bool finished;
    public int ExitCode { get; private set; } = 1;

    public ProgressForm(Options options)
    {
        this.options = options;
        Text = "Conversor Local";
        Font = new Font("Segoe UI", 10);
        BackColor = Color.White;
        ClientSize = new Size(550, 230);
        Padding = new Padding(24);
        StartPosition = FormStartPosition.CenterScreen;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        var bottom = new Panel { Dock = DockStyle.Bottom, Height = 50 };
        bottom.Controls.Add(action);
        bottom.Resize += (_, _) => action.Location = new Point(bottom.ClientSize.Width - action.Width, 12);
        Controls.Add(details); Controls.Add(bar); Controls.Add(status); Controls.Add(filename); Controls.Add(heading); Controls.Add(bottom);
        action.Click += (_, _) => { if (finished) Close(); else Cancel(); };
        FormClosing += (_, e) => { if (!finished) { e.Cancel = true; Cancel(); } };
        Shown += async (_, _) => await RunAsync();
    }

    private void Cancel()
    {
        cancellation.Cancel();
        action.Enabled = false;
        status.Text = "Cancelando e removendo temporários…";
    }

    private async Task RunAsync()
    {
        try
        {
            var results = await Program.BatchAsync(options, (name, p) =>
            {
                if (IsDisposed) return;
                filename.Text = name; status.Text = p.Stage; bar.Value = Math.Clamp(p.Percent, 0, 100);
            }, cancellation.Token);
            Program.WriteReport(options, results);
            var ok = results.Count(r => r.Success);
            ExitCode = ok == results.Count ? 0 : 1;
            finished = true;
            bar.Value = 100;
            heading.Text = ok == results.Count ? "Conversão concluída" : $"{ok} de {results.Count} arquivo(s) convertido(s)";
            status.Text = "Os arquivos convertidos estão na pasta de origem.";
            action.Text = "Fechar"; action.Enabled = true;
            var messages = results.SelectMany(r => new[] { r.Success ? $"✓ {Path.GetFileName(r.Output)}" : $"✗ {Path.GetFileName(r.Source)}: {r.Error}" }
                .Concat(r.Warnings.Distinct().Select(w => "  " + w))).ToArray();
            if (results.Any(r => !r.Success || r.Warnings.Count != 0))
            {
                ClientSize = new Size(650, 430);
                details.Text = string.Join(Environment.NewLine + Environment.NewLine, messages);
                details.Visible = true;
                filename.Text = "Originais preservados · processamento 100% local";
                if (ok == 0) status.Text = results.All(r => r.Cancelled) ? "Lote cancelado." : "Nenhum resultado foi publicado.";
            }
            else
            {
                filename.Text = Path.GetFileName(results[0].Output);
                await Task.Delay(1400);
                Close();
            }
        }
        catch (Exception ex)
        {
            finished = true;
            heading.Text = "Não foi possível concluir";
            status.Text = ex.Message;
            action.Text = "Fechar"; action.Enabled = true;
            ConversionRunner.Log("ui", "", ex.ToString());
        }
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) cancellation.Dispose();
        base.Dispose(disposing);
    }
}

internal sealed class SettingsForm : Form
{
    public SettingsForm()
    {
        Text = "Conversor Local · Configurações";
        Font = new Font("Segoe UI", 10);
        BackColor = Color.White;
        ClientSize = new Size(570, 340);
        StartPosition = FormStartPosition.CenterScreen;
        FormBorderStyle = FormBorderStyle.FixedDialog; MaximizeBox = false;
        var intro = new Label { Text = "Converta pelo botão direito", Font = new Font("Segoe UI", 16, FontStyle.Bold), AutoSize = true, Location = new(24, 24) };
        var description = new Label { Text = "PDF → DOCX / PPTX     ·     DOCX / PPTX → PDF\n\nO resultado fica junto do original, sem sobrescrever arquivos.\nNo Windows 11, use ‘Mostrar mais opções’ no menu clássico.", Location = new(24, 70), Size = new(520, 100) };
        var ocr = new CheckBox { Text = "Usar OCR local em PDFs digitalizados (português e inglês)", Checked = Preferences.Load().Ocr, AutoSize = true, Location = new(24, 184) };
        var note = new Label { Text = "OCR pode ser mais demorado. Revise o texto reconhecido.\nPDF → PPTX gera imagens; PDF → DOCX pode alterar o layout.", Location = new(24, 218), Size = new(520, 48) };
        var save = new Button { Text = "Salvar", Location = new(430, 282), Size = new(110, 34) };
        save.Click += (_, _) => { try { new Preferences(ocr.Checked).Save(); Close(); } catch (Exception ex) { MessageBox.Show(ex.Message, Text); } };
        Controls.AddRange([intro, description, ocr, note, save]);
    }
}

using Conversor.Core;

namespace Conversor.App;

internal sealed class ProgressForm : Form
{
    private readonly Options options;
    private readonly CancellationTokenSource cancellation = new();
    private readonly Label heading = AppTheme.Label("Preparando a conversão", 20, FontStyle.Bold);
    private readonly Label filename = AppTheme.Label("", 11, FontStyle.Bold);
    private readonly Label fileCaption = AppTheme.Label("", 8.5f, FontStyle.Bold, AppTheme.Muted);
    private readonly Label status = AppTheme.Label("O resultado será salvo junto do original.", 10, color: AppTheme.Muted);
    private readonly Label percent = AppTheme.Label("0%", 10, FontStyle.Bold);
    private readonly ProgressTrack bar = new() { Dock = DockStyle.Fill, Margin = Padding.Empty };
    private readonly TextBox details = new()
    {
        Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical,
        Dock = DockStyle.Fill, BorderStyle = BorderStyle.None,
        BackColor = AppTheme.Surface, ForeColor = AppTheme.Ink, Font = AppTheme.Font(9.5f), AccessibleName = "Detalhes da conversão"
    };
    private readonly SurfacePanel detailsCard = new() { Dock = DockStyle.Fill, Visible = false, Margin = new(0, 0, 0, 10), Padding = new(18) };
    private readonly ModernButton action = new() { Text = "Cancelar", Anchor = AnchorStyles.Right, Margin = Padding.Empty };
    private bool finished;
    public int ExitCode { get; private set; } = 1;

    public ProgressForm(Options options)
    {
        this.options = options;
        AppTheme.Configure(this, new(680, 410));
        DoubleBuffered = true;
        var layout = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new(28), ColumnCount = 1, RowCount = 6 };
        layout.ColumnStyles.Add(new(SizeType.Percent, 100));
        foreach (var height in new[] { 64, 58, 104, 70 }) layout.RowStyles.Add(new(SizeType.Absolute, height));
        layout.RowStyles.Add(new(SizeType.Percent, 100));
        layout.RowStyles.Add(new(SizeType.Absolute, 58));
        layout.Controls.Add(AppTheme.Header("Conversão de documentos"), 0, 0);
        heading.Margin = new(0, 0, 0, 12);
        layout.Controls.Add(heading, 0, 1);

        var fileCard = new SurfacePanel { Dock = DockStyle.Fill, Margin = new(0, 0, 0, 12), Padding = new(18, 14, 18, 14) };
        var fileLayout = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, RowCount = 2, Margin = Padding.Empty, BackColor = AppTheme.Surface };
        fileLayout.ColumnStyles.Add(new(SizeType.Absolute, 60)); fileLayout.ColumnStyles.Add(new(SizeType.Percent, 100));
        fileLayout.RowStyles.Add(new(SizeType.Absolute, 22)); fileLayout.RowStyles.Add(new(SizeType.Percent, 100));
        var format = AppTheme.Label(options.Target.ToUpperInvariant(), 9, FontStyle.Bold, AppTheme.Accent);
        format.BackColor = AppTheme.AccentSoft; format.TextAlign = ContentAlignment.MiddleCenter; format.Margin = new(0, 4, 14, 4);
        fileLayout.Controls.Add(format, 0, 0); fileLayout.SetRowSpan(format, 2);
        fileCaption.Text = $"SAÍDA EM {options.Target.ToUpperInvariant()}";
        fileLayout.Controls.Add(fileCaption, 1, 0);
        filename.Text = Path.GetFileName(options.Files.FirstOrDefault()) ?? "Preparando arquivos…";
        filename.AutoEllipsis = true;
        fileLayout.Controls.Add(filename, 1, 1);
        fileCard.Controls.Add(fileLayout);
        layout.Controls.Add(fileCard, 0, 2);

        var progress = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, RowCount = 3, Margin = Padding.Empty };
        progress.ColumnStyles.Add(new(SizeType.Percent, 100)); progress.ColumnStyles.Add(new(SizeType.Absolute, 54));
        progress.RowStyles.Add(new(SizeType.Absolute, 42)); progress.RowStyles.Add(new(SizeType.Absolute, 8)); progress.RowStyles.Add(new(SizeType.Percent, 100));
        status.AutoEllipsis = true; status.Margin = new(0, 0, 12, 4);
        percent.TextAlign = ContentAlignment.MiddleRight; percent.Margin = new(0, 0, 0, 4);
        progress.Controls.Add(status, 0, 0); progress.Controls.Add(percent, 1, 0);
        progress.Controls.Add(bar, 0, 1); progress.SetColumnSpan(bar, 2);
        layout.Controls.Add(progress, 0, 3);

        var detailsHeading = AppTheme.Label("DETALHES DA CONVERSÃO", 8.5f, FontStyle.Bold, AppTheme.Muted);
        detailsHeading.Dock = DockStyle.Top; detailsHeading.Height = 28;
        detailsCard.Controls.Add(details); detailsCard.Controls.Add(detailsHeading);
        layout.Controls.Add(detailsCard, 0, 4);
        var footer = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, Margin = Padding.Empty };
        footer.ColumnStyles.Add(new(SizeType.Percent, 100)); footer.ColumnStyles.Add(new(SizeType.Absolute, 120));
        footer.Controls.Add(AppTheme.Label("Na mesma pasta do original.\nSeus arquivos são preservados.", 9, color: AppTheme.Muted), 0, 0);
        footer.Controls.Add(action, 1, 0);
        layout.Controls.Add(footer, 0, 5);
        Controls.Add(layout);

        action.Click += (_, _) => { if (finished) Close(); else Cancel(); };
        FormClosing += (_, e) => { if (!finished) { e.Cancel = true; Cancel(); } };
        Shown += async (_, _) => await RunAsync();
    }

    private void SetProgress(int value)
    {
        bar.Value = Math.Clamp(value, 0, 100);
        percent.Text = $"{bar.Value}%";
    }

    private void ShowDetails(string text)
    {
        var area = Screen.FromControl(this).WorkingArea;
        var frameHeight = Height - ClientSize.Height;
        ClientSize = new Size((int)Math.Round(680 * DeviceDpi / 96d), Math.Min((int)Math.Round(602 * DeviceDpi / 96d), area.Height - frameHeight));
        Location = new Point(Math.Clamp(Left, area.Left, Math.Max(area.Left, area.Right - Width)), Math.Clamp(Top, area.Top, Math.Max(area.Top, area.Bottom - Height)));
        details.Text = text; detailsCard.Visible = true;
    }

    private void Cancel()
    {
        cancellation.Cancel(); action.Enabled = false;
        status.Text = "Cancelando e removendo temporários…"; bar.FillColor = AppTheme.Warning;
    }

    private async Task RunAsync()
    {
        try
        {
            var results = await Program.BatchAsync(options, (name, p) =>
            {
                if (IsDisposed || finished) return;
                filename.Text = name; status.Text = p.Stage; SetProgress(p.Percent);
                if (!cancellation.IsCancellationRequested) heading.Text = "Conversão em andamento";
            }, cancellation.Token);
            Program.WriteReport(options, results);
            var ok = results.Count(r => r.Success);
            ExitCode = ok == results.Count ? 0 : 1;
            finished = true; SetProgress(100);
            bar.FillColor = ok == results.Count ? AppTheme.Success : results.All(r => r.Cancelled) ? AppTheme.Warning : ok == 0 ? AppTheme.Danger : AppTheme.Warning;
            heading.Text = ok == results.Count ? "Conversão concluída" : results.All(r => r.Cancelled) ? "Conversão cancelada" : ok == 0 ? "Conversão não concluída" : $"{ok} de {results.Count} arquivos convertidos";
            status.Text = "Os arquivos convertidos estão na pasta de origem.";
            action.Text = "Fechar"; action.Enabled = true; action.Primary = true;
            var messages = results.SelectMany(r => new[] { r.Success ? $"✓ {Path.GetFileName(r.Output)}" : $"✗ {Path.GetFileName(r.Source)}: {r.Error}" }
                .Concat(r.Warnings.Distinct().Select(w => "  " + w))).ToArray();
            if (results.Any(r => !r.Success || r.Warnings.Count != 0))
            {
                fileCaption.Text = "RESUMO DO LOTE";
                ShowDetails(string.Join(Environment.NewLine + Environment.NewLine, messages));
                filename.Text = "Originais preservados · processamento 100% local";
                if (ok == 0) status.Text = results.All(r => r.Cancelled) ? "Lote cancelado." : "Nenhum resultado foi publicado.";
            }
            else
            {
                filename.Text = Path.GetFileName(results[0].Output);
                await Task.Delay(1400); Close();
            }
        }
        catch (Exception ex)
        {
            finished = true; heading.Text = "Não foi possível concluir";
            status.Text = "Veja os detalhes abaixo."; bar.FillColor = AppTheme.Danger;
            ShowDetails(ex.Message);
            action.Text = "Fechar"; action.Enabled = true; action.Primary = true;
            ConversionRunner.Log("ui", "", ex.ToString());
        }
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) cancellation.Dispose();
        base.Dispose(disposing);
    }
}

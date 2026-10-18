using Conversor.Core;

namespace Conversor.App;

internal sealed class SettingsForm : Form
{
    public SettingsForm()
    {
        AppTheme.Configure(this, new(720, 640));
        Text = "Conversor Local · Configurações";
        DoubleBuffered = true;
        var layout = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new(28), ColumnCount = 1, RowCount = 6 };
        layout.ColumnStyles.Add(new(SizeType.Percent, 100));
        foreach (var height in new[] { 64, 78, 164, 138 }) layout.RowStyles.Add(new(SizeType.Absolute, height));
        layout.RowStyles.Add(new(SizeType.Percent, 100)); layout.RowStyles.Add(new(SizeType.Absolute, 58));
        layout.Controls.Add(AppTheme.Header("Configurações"), 0, 0);

        var introduction = new TableLayoutPanel { Dock = DockStyle.Fill, RowCount = 2, Margin = new(0, 0, 0, 14) };
        introduction.RowStyles.Add(new(SizeType.Absolute, 38)); introduction.RowStyles.Add(new(SizeType.Percent, 100));
        introduction.Controls.Add(AppTheme.Label("Pronto para converter", 20, FontStyle.Bold), 0, 0);
        introduction.Controls.Add(AppTheme.Label("Selecione arquivos abaixo ou use o menu do botão direito.", 10, color: AppTheme.Muted), 0, 1);
        layout.Controls.Add(introduction, 0, 1);

        var formatsCard = new SurfacePanel { Dock = DockStyle.Fill, Margin = new(0, 0, 0, 12), Padding = new(20, 12, 20, 12) };
        var formats = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, RowCount = 3, Margin = Padding.Empty, BackColor = AppTheme.Surface };
        formats.ColumnStyles.Add(new(SizeType.Percent, 50)); formats.ColumnStyles.Add(new(SizeType.Percent, 50));
        formats.RowStyles.Add(new(SizeType.Absolute, 24)); formats.RowStyles.Add(new(SizeType.Percent, 50)); formats.RowStyles.Add(new(SizeType.Percent, 50));
        var formatsTitle = AppTheme.Label("FORMATOS DISPONÍVEIS", 8.5f, FontStyle.Bold, AppTheme.Muted);
        formats.Controls.Add(formatsTitle, 0, 0); formats.SetColumnSpan(formatsTitle, 2);
        for (var i = 0; i < Formats.Groups.Count; i++)
        {
            var group = Formats.Groups[i];
            var cell = new TableLayoutPanel { Dock = DockStyle.Fill, RowCount = 2, Margin = Padding.Empty, BackColor = AppTheme.Surface };
            cell.RowStyles.Add(new(SizeType.Absolute, 23)); cell.RowStyles.Add(new(SizeType.Percent, 100));
            cell.Controls.Add(AppTheme.Label(group.Name, 10, FontStyle.Bold), 0, 0);
            cell.Controls.Add(AppTheme.Label(group.Formats, 9, color: AppTheme.Muted), 0, 1);
            formats.Controls.Add(cell, i % 2, 1 + i / 2);
        }
        formatsCard.Controls.Add(formats); layout.Controls.Add(formatsCard, 0, 2);

        var ocrCard = new SurfacePanel { Dock = DockStyle.Fill, Margin = new(0, 0, 0, 12), Padding = new(20, 12, 20, 12) };
        var ocrLayout = new TableLayoutPanel { Dock = DockStyle.Fill, RowCount = 3, Margin = Padding.Empty, BackColor = AppTheme.Surface };
        ocrLayout.RowStyles.Add(new(SizeType.Absolute, 23)); ocrLayout.RowStyles.Add(new(SizeType.Absolute, 32)); ocrLayout.RowStyles.Add(new(SizeType.Percent, 100));
        ocrLayout.Controls.Add(AppTheme.Label("RECONHECIMENTO DE TEXTO", 8.5f, FontStyle.Bold, AppTheme.Muted), 0, 0);
        var ocr = new CheckBox
        {
            Text = "Usar OCR local em PDFs digitalizados", Checked = Preferences.Load().Ocr,
            Dock = DockStyle.Fill, Margin = Padding.Empty, BackColor = AppTheme.Surface,
            ForeColor = AppTheme.Ink, Font = AppTheme.Font(10.5f, FontStyle.Bold), TabIndex = 0
        };
        ocrLayout.Controls.Add(ocr, 0, 1);
        ocrLayout.Controls.Add(AppTheme.Label("Português e inglês. O processo pode demorar mais;\nrevise o texto reconhecido ao concluir.", 9.5f, color: AppTheme.Muted), 0, 2);
        ocrCard.Controls.Add(ocrLayout); layout.Controls.Add(ocrCard, 0, 3);

        var note = AppTheme.Label("O resultado fica na pasta do original, sem substituir arquivos.\nNo Windows 11, use “Mostrar mais opções”.\nPDF → PPTX gera imagens; PDF → DOCX pode alterar o layout.", 9, color: AppTheme.Muted);
        note.TextAlign = ContentAlignment.TopLeft; note.Padding = new(2, 6, 0, 0);
        layout.Controls.Add(note, 0, 4);
        var footer = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 3, Margin = Padding.Empty };
        footer.ColumnStyles.Add(new(SizeType.Percent, 100)); footer.ColumnStyles.Add(new(SizeType.Absolute, 178)); footer.ColumnStyles.Add(new(SizeType.Absolute, 120));
        footer.Controls.Add(AppTheme.Label("Tudo neste computador.", 9, color: AppTheme.Muted), 0, 0);
        var convert = new ModernButton { Text = "Converter arquivos", Width = 164, Anchor = AnchorStyles.Right, Margin = new(0, 0, 14, 0), TabIndex = 1 };
        convert.Click += (_, _) => SelectAndConvert(ocr.Checked);
        footer.Controls.Add(convert, 1, 0);
        var save = new ModernButton { Text = "Salvar", Primary = true, Anchor = AnchorStyles.Right, Margin = Padding.Empty, TabIndex = 1 };
        save.Click += (_, _) => { try { new Preferences(ocr.Checked).Save(); Close(); } catch (Exception ex) { MessageBox.Show(ex.Message, Text); } };
        save.TabIndex = 2;
        footer.Controls.Add(save, 2, 0); layout.Controls.Add(footer, 0, 5);
        Controls.Add(layout);
    }

    private void SelectAndConvert(bool ocr)
    {
        using var files = new OpenFileDialog
        {
            Title = "Selecione os arquivos para converter", Multiselect = true, CheckFileExists = true,
            RestoreDirectory = true, Filter = "Arquivos compatíveis|" + string.Join(";", Formats.SourceExtensions.Select(ext => "*" + ext))
        };
        if (files.ShowDialog(this) != DialogResult.OK) return;
        var selected = files.FileNames.Distinct(StringComparer.OrdinalIgnoreCase).ToArray();
        var targets = Formats.TargetIds.Where(target => selected.All(file => Formats.IsValid(file, target))).ToArray();
        if (targets.Length == 0)
        {
            MessageBox.Show(this, "Os arquivos selecionados não têm um destino em comum. Selecione um grupo compatível.", "Conversor Local", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        using var destination = new TargetForm(targets, selected.Length);
        if (destination.ShowDialog(this) != DialogResult.OK) return;
        using var progress = new ProgressForm(new(destination.Target, selected, false, null, ocr));
        progress.ShowDialog(this);
    }
}

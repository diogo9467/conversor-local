namespace Conversor.App;

internal sealed class SettingsForm : Form
{
    public SettingsForm()
    {
        AppTheme.Configure(this, new(680, 576));
        Text = "Conversor Local · Configurações";
        DoubleBuffered = true;
        var layout = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new(28), ColumnCount = 1, RowCount = 6 };
        layout.ColumnStyles.Add(new(SizeType.Percent, 100));
        foreach (var height in new[] { 64, 78, 100, 138 }) layout.RowStyles.Add(new(SizeType.Absolute, height));
        layout.RowStyles.Add(new(SizeType.Percent, 100)); layout.RowStyles.Add(new(SizeType.Absolute, 58));
        layout.Controls.Add(AppTheme.Header("Configurações"), 0, 0);

        var introduction = new TableLayoutPanel { Dock = DockStyle.Fill, RowCount = 2, Margin = new(0, 0, 0, 14) };
        introduction.RowStyles.Add(new(SizeType.Absolute, 38)); introduction.RowStyles.Add(new(SizeType.Percent, 100));
        introduction.Controls.Add(AppTheme.Label("Pronto para converter", 20, FontStyle.Bold), 0, 0);
        introduction.Controls.Add(AppTheme.Label("Use “Converter arquivo” no menu do botão direito.", 10, color: AppTheme.Muted), 0, 1);
        layout.Controls.Add(introduction, 0, 1);

        var formatsCard = new SurfacePanel { Dock = DockStyle.Fill, Margin = new(0, 0, 0, 12), Padding = new(20, 12, 20, 12) };
        var formats = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, RowCount = 2, Margin = Padding.Empty, BackColor = AppTheme.Surface };
        formats.ColumnStyles.Add(new(SizeType.Percent, 50)); formats.ColumnStyles.Add(new(SizeType.Percent, 50));
        formats.RowStyles.Add(new(SizeType.Absolute, 24)); formats.RowStyles.Add(new(SizeType.Percent, 100));
        var formatsTitle = AppTheme.Label("FORMATOS DISPONÍVEIS", 8.5f, FontStyle.Bold, AppTheme.Muted);
        formats.Controls.Add(formatsTitle, 0, 0); formats.SetColumnSpan(formatsTitle, 2);
        formats.Controls.Add(AppTheme.Label("DOCX / PPTX  →  PDF", 11, FontStyle.Bold), 0, 1);
        formats.Controls.Add(AppTheme.Label("PDF  →  DOCX / PPTX", 11, FontStyle.Bold, AppTheme.Accent), 1, 1);
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
        var footer = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, Margin = Padding.Empty };
        footer.ColumnStyles.Add(new(SizeType.Percent, 100)); footer.ColumnStyles.Add(new(SizeType.Absolute, 120));
        footer.Controls.Add(AppTheme.Label("Suas preferências ficam neste computador.", 9, color: AppTheme.Muted), 0, 0);
        var save = new ModernButton { Text = "Salvar", Primary = true, Anchor = AnchorStyles.Right, Margin = Padding.Empty, TabIndex = 1 };
        save.Click += (_, _) => { try { new Preferences(ocr.Checked).Save(); Close(); } catch (Exception ex) { MessageBox.Show(ex.Message, Text); } };
        footer.Controls.Add(save, 1, 0); layout.Controls.Add(footer, 0, 5);
        Controls.Add(layout);
    }
}

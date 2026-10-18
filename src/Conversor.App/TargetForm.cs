namespace Conversor.App;

internal sealed class TargetForm : Form
{
    private readonly string[] targets;
    private readonly ComboBox destination;
    public string Target => targets[destination.SelectedIndex];

    public TargetForm(string[] targets, int count)
    {
        this.targets = targets;
        AppTheme.Configure(this, new(500, 320));
        Text = "Conversor Local · Nova conversão";
        StartPosition = FormStartPosition.CenterParent;
        var layout = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new(24), RowCount = 6, ColumnCount = 1 };
        layout.ColumnStyles.Add(new(SizeType.Percent, 100));
        foreach (var height in new[] { 62, 40, 26, 40 }) layout.RowStyles.Add(new(SizeType.Absolute, height));
        layout.RowStyles.Add(new(SizeType.Percent, 100)); layout.RowStyles.Add(new(SizeType.Absolute, 48));
        layout.Controls.Add(AppTheme.Header("Nova conversão"), 0, 0);
        layout.Controls.Add(AppTheme.Label($"{count} arquivo(s) selecionado(s)", 14, FontStyle.Bold), 0, 1);
        layout.Controls.Add(AppTheme.Label("FORMATO DE DESTINO", 8.5f, FontStyle.Bold, AppTheme.Muted), 0, 2);
        destination = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Dock = DockStyle.Top, Font = AppTheme.Font(11), AccessibleName = "Formato de destino", TabIndex = 0 };
        destination.Items.AddRange(targets.Select(target => target.ToUpperInvariant()).Cast<object>().ToArray());
        destination.SelectedIndex = 0;
        layout.Controls.Add(destination, 0, 3);
        layout.Controls.Add(AppTheme.Label("O resultado será salvo na pasta do original.\nArquivos existentes serão preservados.", 9, color: AppTheme.Muted), 0, 4);
        var buttons = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.RightToLeft, WrapContents = false, Margin = Padding.Empty, Padding = new(0, 4, 0, 0) };
        var convert = new ModernButton { Text = "Converter", Primary = true, DialogResult = DialogResult.OK, Margin = Padding.Empty, TabIndex = 1 };
        var cancel = new ModernButton { Text = "Cancelar", DialogResult = DialogResult.Cancel, Margin = new(0, 0, 12, 0), TabIndex = 2 };
        buttons.Controls.Add(convert); buttons.Controls.Add(cancel);
        layout.Controls.Add(buttons, 0, 5); Controls.Add(layout);
        AcceptButton = convert; CancelButton = cancel;
        Shown += (_, _) => destination.Focus();
    }
}

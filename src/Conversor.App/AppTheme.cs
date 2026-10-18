using System.ComponentModel;
using System.Drawing.Drawing2D;

namespace Conversor.App;

internal static class AppTheme
{
    public static Color Canvas => SystemInformation.HighContrast ? SystemColors.Control : Color.FromArgb(245, 247, 251);
    public static Color Surface => SystemInformation.HighContrast ? SystemColors.Window : Color.White;
    public static Color Ink => SystemInformation.HighContrast ? SystemColors.WindowText : Color.FromArgb(25, 39, 61);
    public static Color Muted => SystemInformation.HighContrast ? SystemColors.WindowText : Color.FromArgb(94, 109, 131);
    public static Color Border => SystemInformation.HighContrast ? SystemColors.WindowText : Color.FromArgb(223, 230, 239);
    public static Color Accent => SystemInformation.HighContrast ? SystemColors.Highlight : Color.FromArgb(37, 99, 235);
    public static Color AccentSoft => SystemInformation.HighContrast ? SystemColors.Control : Color.FromArgb(235, 242, 255);
    public static Color Success => SystemInformation.HighContrast ? SystemColors.WindowText : Color.FromArgb(4, 120, 87);
    public static Color SuccessSoft => SystemInformation.HighContrast ? SystemColors.Window : Color.FromArgb(229, 246, 238);
    public static Color Danger => SystemInformation.HighContrast ? SystemColors.WindowText : Color.FromArgb(180, 35, 24);
    public static Color Warning => SystemInformation.HighContrast ? SystemColors.WindowText : Color.FromArgb(162, 86, 12);

    public static Font Font(float size = 10, FontStyle style = FontStyle.Regular) => new("Segoe UI", size, style);

    public static void Configure(Form form, Size size)
    {
        form.Text = "Conversor Local";
        form.Font = Font();
        form.BackColor = Canvas;
        form.ForeColor = Ink;
        form.AutoScaleMode = AutoScaleMode.Dpi;
        form.ClientSize = size;
        form.StartPosition = FormStartPosition.CenterScreen;
        form.FormBorderStyle = FormBorderStyle.FixedDialog;
        form.MaximizeBox = false;
    }

    public static Label Label(string text, float size = 10, FontStyle style = FontStyle.Regular, Color? color = null) => new()
    {
        Text = text, Font = Font(size, style), ForeColor = color ?? Ink,
        BackColor = Color.Transparent, Dock = DockStyle.Fill, Margin = Padding.Empty,
        TextAlign = ContentAlignment.MiddleLeft, UseMnemonic = false
    };

    public static TableLayoutPanel Header(string subtitle)
    {
        var header = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 3, RowCount = 1, Margin = Padding.Empty };
        header.ColumnStyles.Add(new(SizeType.Absolute, 54));
        header.ColumnStyles.Add(new(SizeType.Percent, 100));
        header.ColumnStyles.Add(new(SizeType.Absolute, 112));
        header.Controls.Add(new BrandMark { Size = new(40, 40), Margin = new(0, 4, 0, 0) }, 0, 0);
        var name = new TableLayoutPanel { Dock = DockStyle.Top, Height = 48, RowCount = 2, Margin = Padding.Empty };
        name.RowStyles.Add(new(SizeType.Absolute, 27));
        name.RowStyles.Add(new(SizeType.Absolute, 21));
        name.Controls.Add(Label("Conversor Local", 12, FontStyle.Bold), 0, 0);
        name.Controls.Add(Label(subtitle, 9, color: Muted), 0, 1);
        header.Controls.Add(name, 1, 0);
        header.Controls.Add(new LocalBadge { Text = "100% local", Size = new(110, 28), Margin = new(0, 10, 0, 0) }, 2, 0);
        return header;
    }

    public static GraphicsPath Rounded(RectangleF bounds, float radius)
    {
        var path = new GraphicsPath();
        var diameter = Math.Min(radius * 2, Math.Min(bounds.Width, bounds.Height));
        if (diameter <= 0) { path.AddRectangle(bounds); return path; }
        path.AddArc(bounds.Left, bounds.Top, diameter, diameter, 180, 90);
        path.AddArc(bounds.Right - diameter, bounds.Top, diameter, diameter, 270, 90);
        path.AddArc(bounds.Right - diameter, bounds.Bottom - diameter, diameter, diameter, 0, 90);
        path.AddArc(bounds.Left, bounds.Bottom - diameter, diameter, diameter, 90, 90);
        path.CloseFigure();
        return path;
    }
}

internal sealed class SurfacePanel : Panel
{
    public SurfacePanel()
    {
        DoubleBuffered = true; BackColor = AppTheme.Canvas; Padding = new(20);
        SetStyle(ControlStyles.ResizeRedraw, true);
    }
    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        using var shape = AppTheme.Rounded(new RectangleF(.5f, .5f, Width - 1, Height - 1), 12 * DeviceDpi / 96f);
        using var fill = new SolidBrush(AppTheme.Surface);
        using var border = new Pen(AppTheme.Border);
        e.Graphics.FillPath(fill, shape); e.Graphics.DrawPath(border, shape);
        base.OnPaint(e);
    }
}

internal sealed class BrandMark : Control
{
    public BrandMark() { DoubleBuffered = true; TabStop = false; AccessibleRole = AccessibleRole.Graphic; AccessibleName = "Conversor Local"; }
    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        e.Graphics.ScaleTransform(Width / 40f, Height / 40f);
        using var background = AppTheme.Rounded(new RectangleF(0, 0, 39, 39), 10);
        using var fill = new SolidBrush(AppTheme.Accent);
        e.Graphics.FillPath(fill, background);
        using var pen = new Pen(SystemInformation.HighContrast ? SystemColors.HighlightText : Color.White, 1.6f) { LineJoin = LineJoin.Round, StartCap = LineCap.Round, EndCap = LineCap.Round };
        e.Graphics.DrawLines(pen, [new(13, 9), new(22, 9), new(28, 15), new(28, 30), new(13, 30), new(13, 9)]);
        e.Graphics.DrawLines(pen, [new(22, 9), new(22, 15), new(28, 15)]);
        e.Graphics.DrawLine(pen, 17, 20, 24, 20); e.Graphics.DrawLine(pen, 17, 25, 22, 25);
    }
}

internal sealed class LocalBadge : Control
{
    public LocalBadge() { DoubleBuffered = true; Font = AppTheme.Font(9, FontStyle.Bold); TabStop = false; AccessibleRole = AccessibleRole.StaticText; }
    protected override void OnPaint(PaintEventArgs e)
    {
        var scale = DeviceDpi / 96f;
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        using var shape = AppTheme.Rounded(new RectangleF(0, 0, Width - 1, Height - 1), 14 * scale);
        using var fill = new SolidBrush(AppTheme.SuccessSoft);
        using var dot = new SolidBrush(AppTheme.Success);
        e.Graphics.FillPath(fill, shape);
        e.Graphics.FillEllipse(dot, 12 * scale, Height / 2f - 3 * scale, 6 * scale, 6 * scale);
        var bounds = new Rectangle((int)(25 * scale), 0, Width - (int)(28 * scale), Height);
        TextRenderer.DrawText(e.Graphics, Text, Font, bounds, AppTheme.Success, TextFormatFlags.VerticalCenter | TextFormatFlags.Left | TextFormatFlags.NoPrefix);
    }
}

internal sealed class ModernButton : Button
{
    private bool primary;
    private bool hovered;
    private bool pressed;
    [DesignerSerializationVisibility(DesignerSerializationVisibility.Hidden)]
    public bool Primary { get => primary; set { primary = value; Invalidate(); } }
    public ModernButton()
    {
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
        FlatStyle = FlatStyle.Flat; FlatAppearance.BorderSize = 0; UseVisualStyleBackColor = false;
        Font = AppTheme.Font(10, FontStyle.Bold); Cursor = Cursors.Hand; Size = new(120, 40);
    }
    protected override void OnMouseEnter(EventArgs e) { hovered = true; Invalidate(); base.OnMouseEnter(e); }
    protected override void OnMouseLeave(EventArgs e) { hovered = pressed = false; Invalidate(); base.OnMouseLeave(e); }
    protected override void OnMouseDown(MouseEventArgs e) { if (e.Button == MouseButtons.Left) pressed = true; Invalidate(); base.OnMouseDown(e); }
    protected override void OnMouseUp(MouseEventArgs e) { pressed = false; Invalidate(); base.OnMouseUp(e); }
    protected override void OnKeyDown(KeyEventArgs e) { if (e.KeyCode == Keys.Space) { pressed = true; Invalidate(); } base.OnKeyDown(e); }
    protected override void OnKeyUp(KeyEventArgs e) { pressed = false; Invalidate(); base.OnKeyUp(e); }
    protected override void OnGotFocus(EventArgs e) { Invalidate(); base.OnGotFocus(e); }
    protected override void OnLostFocus(EventArgs e) { pressed = false; Invalidate(); base.OnLostFocus(e); }
    protected override void OnEnabledChanged(EventArgs e) { Invalidate(); base.OnEnabledChanged(e); }
    protected override void OnSizeChanged(EventArgs e)
    {
        if (Width > 0 && Height > 0)
        {
            using var shape = AppTheme.Rounded(new RectangleF(0, 0, Width, Height), 8 * DeviceDpi / 96f);
            var previous = Region;
            Region = new Region(shape);
            previous?.Dispose();
        }
        base.OnSizeChanged(e);
    }
    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.Clear(Parent?.BackColor ?? AppTheme.Canvas);
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        var background = !Enabled ? AppTheme.Border : Primary ? AppTheme.Accent : AppTheme.Surface;
        if (Enabled && (hovered || pressed)) background = Primary ? ControlPaint.Dark(AppTheme.Accent, pressed ? .17f : .07f) : AppTheme.AccentSoft;
        var foreground = !Enabled ? AppTheme.Muted : Primary ? (SystemInformation.HighContrast ? SystemColors.HighlightText : Color.White) : AppTheme.Ink;
        using var shape = AppTheme.Rounded(new RectangleF(.5f, .5f, Width - 1, Height - 1), 8 * DeviceDpi / 96f);
        using var fill = new SolidBrush(background);
        using var border = new Pen(Primary ? background : AppTheme.Border);
        e.Graphics.FillPath(fill, shape); e.Graphics.DrawPath(border, shape);
        TextRenderer.DrawText(e.Graphics, Text, Font, ClientRectangle, foreground, TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPrefix);
        if (Focused && ShowFocusCues) ControlPaint.DrawFocusRectangle(e.Graphics, Rectangle.Inflate(ClientRectangle, -5, -5), foreground, background);
    }
}

internal sealed class ProgressTrack : Control
{
    private int value;
    private Color fillColor = AppTheme.Accent;
    [DesignerSerializationVisibility(DesignerSerializationVisibility.Hidden)]
    public int Value
    {
        get => value;
        set { this.value = Math.Clamp(value, 0, 100); Invalidate(); if (IsHandleCreated) AccessibilityNotifyClients(AccessibleEvents.ValueChange, -1); }
    }
    [DesignerSerializationVisibility(DesignerSerializationVisibility.Hidden)]
    public Color FillColor { get => fillColor; set { fillColor = value; Invalidate(); } }
    public ProgressTrack()
    {
        DoubleBuffered = true; TabStop = false;
        AccessibleRole = AccessibleRole.ProgressBar; AccessibleName = "Progresso da conversão";
        SetStyle(ControlStyles.ResizeRedraw, true);
    }
    protected override AccessibleObject CreateAccessibilityInstance() => new TrackAccessibility(this);
    private sealed class TrackAccessibility(ProgressTrack owner) : ControlAccessibleObject(owner)
    {
        public override string? Value { get => $"{owner.Value}%"; set { } }
        public override AccessibleStates State => base.State | AccessibleStates.ReadOnly;
    }
    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        using var track = AppTheme.Rounded(new RectangleF(0, 0, Width, Height), Height / 2f);
        using var background = new SolidBrush(AppTheme.Border);
        e.Graphics.FillPath(background, track);
        if (Value == 0) return;
        using var fill = new SolidBrush(FillColor);
        e.Graphics.SetClip(track); e.Graphics.FillRectangle(fill, 0, 0, Width * Value / 100f, Height);
    }
}

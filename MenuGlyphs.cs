using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace NASMount;

/// <summary>Shared 24-unit line icons for settings and profile context menus.</summary>
internal static class MenuGlyphs
{
    internal static Bitmap Create(string kind, int size, Color color)
    {
        // Normalize the ink, not just the square canvas: the icon outlines
        // have different natural top/bottom padding.
        int resolution = Math.Max(48, size * 2);
        using var raw = new Bitmap(resolution, resolution,
            System.Drawing.Imaging.PixelFormat.Format32bppArgb);
        using (var g = Graphics.FromImage(raw))
            DrawRaw(g, kind, new RectangleF(0, 0, resolution, resolution), color);
        int left = resolution, top = resolution, right = -1, bottom = -1;
        for (int y = 0; y < resolution; y++)
        for (int x = 0; x < resolution; x++)
        {
            if (raw.GetPixel(x, y).A < 24) continue;
            left = Math.Min(left, x); right = Math.Max(right, x);
            top = Math.Min(top, y); bottom = Math.Max(bottom, y);
        }
        var image = new Bitmap(size, size, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
        if (right < left || bottom < top) return image;
        var source = Rectangle.FromLTRB(Math.Max(0, left - 1), Math.Max(0, top - 1),
            Math.Min(resolution, right + 2), Math.Min(resolution, bottom + 2));
        float scale = size * .84f / Math.Max(source.Width, source.Height);
        float w = source.Width * scale, h = source.Height * scale;
        using var canvas = Graphics.FromImage(image);
        canvas.InterpolationMode = InterpolationMode.HighQualityBicubic;
        canvas.PixelOffsetMode = PixelOffsetMode.HighQuality;
        canvas.DrawImage(raw, new RectangleF((size - w) / 2f, (size - h) / 2f, w, h),
            source, GraphicsUnit.Pixel);
        return image;
    }

    internal static void Draw(Graphics g, string kind, RectangleF rect, Color color)
    {
        using var image = Create(kind, Math.Max(1, (int)Math.Round(rect.Width)), color);
        g.DrawImage(image, rect);
    }

    // Center the visible font ink on the same row axis as the icon.
    private static readonly System.Collections.Concurrent.ConcurrentDictionary<string, int>
        LabelOffsets = new();

    internal static void DrawAlignedLabel(Graphics g, string text, Font font,
        Rectangle bounds, Color color)
    {
        if (bounds.Width <= 0 || bounds.Height <= 0 || text.Length == 0) return;
        const TextFormatFlags flags = TextFormatFlags.Left | TextFormatFlags.VerticalCenter |
            TextFormatFlags.NoPadding | TextFormatFlags.EndEllipsis |
            TextFormatFlags.SingleLine | TextFormatFlags.NoPrefix;
        string key = string.Join("|", text, font.Name, font.Size, font.Style,
            g.DpiX, g.DpiY, bounds.Width, bounds.Height);
        int offset = LabelOffsets.GetOrAdd(key, _ =>
        {
            using var probe = new Bitmap(bounds.Width, bounds.Height);
            probe.SetResolution(g.DpiX, g.DpiY);
            using (var pg = Graphics.FromImage(probe))
            {
                pg.Clear(Color.Black);
                TextRenderer.DrawText(pg, text, font,
                    new Rectangle(Point.Empty, probe.Size), Color.White, Color.Black, flags);
            }
            int top = probe.Height, bottom = -1;
            for (int y = 0; y < probe.Height; y++)
            for (int x = 0; x < probe.Width; x++)
            {
                Color pixel = probe.GetPixel(x, y);
                if (Math.Max(pixel.R, Math.Max(pixel.G, pixel.B)) < 64) continue;
                top = Math.Min(top, y); bottom = Math.Max(bottom, y);
            }
            return bottom < top ? 0 : (int)Math.Round(
                (probe.Height - 1 - top - bottom) / 2f);
        });
        bounds.Offset(0, offset);
        TextRenderer.DrawText(g, text, font, bounds, color, flags);
    }

    private static void DrawRaw(Graphics g, string kind, RectangleF rect, Color color)
    {
        var state = g.Save();
        try
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.TranslateTransform(rect.X, rect.Y);
            g.ScaleTransform(rect.Width / 24f, rect.Height / 24f);
            using var pen = new Pen(color, 1.7f)
            {
                StartCap = LineCap.Round, EndCap = LineCap.Round,
                LineJoin = LineJoin.Round
            };
            using var brush = new SolidBrush(color);
            void Line(params PointF[] points) => g.DrawLines(pen, points);
            void Rect(float x, float y, float w, float h) => g.DrawRectangle(pen, x, y, w, h);
            void Circle(float x, float y, float d) => g.DrawEllipse(pen, x, y, d, d);
            void Dot(float x, float y) => g.FillEllipse(brush, x - 0.9f, y - 0.9f, 1.8f, 1.8f);
            void Folder()
            {
                Line(new(3, 19), new(3, 6), new(9, 6), new(11, 9),
                    new(21, 9), new(21, 19), new(3, 19));
            }
            void Arrow(bool up)
            {
                float a = up ? 5 : 14, b = up ? 10 : 9;
                Line(new(12, a), new(12, up ? 15 : 4));
                Line(new(8, b), new(12, a), new(16, b));
            }
            switch (kind)
            {
                case "export":
                case "import":
                    Line(new(4, 13), new(4, 20), new(20, 20), new(20, 13));
                    Arrow(kind == "export");
                    break;
                case "folder":
                case "open":
                    Folder();
                    if (kind == "open") Line(new(3, 19), new(7, 12), new(23, 12), new(21, 19));
                    break;
                case "driver":
                    Rect(6, 6, 12, 12);
                    Rect(9, 9, 6, 6);
                    for (int i = 8; i <= 16; i += 4)
                    {
                        Line(new(i, 3), new(i, 6));
                        Line(new(i, 18), new(i, 21));
                        Line(new(3, i), new(6, i));
                        Line(new(18, i), new(21, i));
                    }
                    break;
                case "update":
                    g.DrawArc(pen, 4, 4, 16, 16, -150, 265);
                    Line(new(16, 4), new(20, 4), new(20, 9));
                    break;
                case "info":
                    Circle(3, 3, 18);
                    Dot(12, 7);
                    Line(new(12, 10), new(12, 17));
                    break;
                case "settings":
                    Circle(8, 8, 8);
                    for (int i = 0; i < 8; i++)
                    {
                        double a = i * Math.PI / 4;
                        Line(new((float)(12 + 7 * Math.Cos(a)), (float)(12 + 7 * Math.Sin(a))),
                            new((float)(12 + 10 * Math.Cos(a)), (float)(12 + 10 * Math.Sin(a))));
                    }
                    Circle(5, 5, 14);
                    break;
                case "share":
                    Circle(3, 9, 5); Circle(16, 3, 5); Circle(16, 16, 5);
                    Line(new(8, 10), new(16, 6));
                    Line(new(8, 13), new(16, 18));
                    break;
                case "lock":
                    Rect(5, 10, 14, 11);
                    g.DrawArc(pen, 8, 3, 8, 14, 180, 180);
                    Line(new(8, 10), new(8, 9));
                    Line(new(16, 10), new(16, 9));
                    Dot(12, 15);
                    Line(new(12, 15), new(12, 18));
                    break;
                case "account":
                    Circle(8, 3, 8);
                    g.DrawArc(pen, 4, 13, 16, 14, 180, 180);
                    break;
                case "upload":
                    Line(new(4, 15), new(4, 20), new(20, 20), new(20, 15));
                    Arrow(true);
                    break;
                case "delete":
                    Line(new(4, 6), new(20, 6));
                    Line(new(9, 6), new(9, 3), new(15, 3), new(15, 6));
                    Line(new(6, 6), new(7, 21), new(17, 21), new(18, 6));
                    Line(new(10, 10), new(10, 17));
                    Line(new(14, 10), new(14, 17));
                    break;
                default:
                    throw new ArgumentException("Unknown menu icon: " + kind);
            }
        }
        finally { g.Restore(state); }
    }
}

internal sealed class ProfileMenuRenderer : ToolStripProfessionalRenderer
{
    internal ProfileMenuRenderer() : base(new ProfileMenuColors())
    {
        RoundedEdges = false;
    }

    protected override void OnRenderMenuItemBackground(ToolStripItemRenderEventArgs e)
    {
        var rect = new Rectangle(2, 1, e.Item.Width - 4, e.Item.Height - 2);
        using var brush = new SolidBrush(e.Item.Selected || e.Item.Pressed
            ? Color.FromArgb(55, 70, 90) : ModernTheme.Surface);
        e.Graphics.FillRectangle(brush, rect);
    }

    protected override void OnRenderSeparator(ToolStripSeparatorRenderEventArgs e)
    {
        using var pen = new Pen(ModernTheme.Border);
        e.Graphics.DrawLine(pen, 9, e.Item.Height / 2,
            e.Item.Width - 8, e.Item.Height / 2);
    }

    protected override void OnRenderItemImage(ToolStripItemImageRenderEventArgs e)
    {
        if (e.Item is not ToolStripMenuItem || e.Image is null)
        {
            base.OnRenderItemImage(e);
            return;
        }
        var rect = e.ImageRectangle;
        rect.Y = (e.Item.Height - rect.Height) / 2;
        e.Graphics.DrawImage(e.Image, rect);
    }

    protected override void OnRenderItemText(ToolStripItemTextRenderEventArgs e)
    {
        if (e.Item is not ToolStripMenuItem)
        {
            base.OnRenderItemText(e);
            return;
        }
        var rect = new Rectangle(e.TextRectangle.X, 0,
            e.TextRectangle.Width, e.Item.Height);
        MenuGlyphs.DrawAlignedLabel(e.Graphics, e.Text, e.TextFont, rect,
            e.Item.Enabled ? e.TextColor : ModernTheme.Muted);
    }

    private sealed class ProfileMenuColors : ProfessionalColorTable
    {
        public override Color ToolStripDropDownBackground => ModernTheme.Surface;
        public override Color ImageMarginGradientBegin => ModernTheme.Surface;
        public override Color ImageMarginGradientMiddle => ModernTheme.Surface;
        public override Color ImageMarginGradientEnd => ModernTheme.Surface;
        public override Color MenuBorder => ModernTheme.Border;
        public override Color MenuItemBorder => ModernTheme.Border;
        public override Color SeparatorDark => ModernTheme.Border;
        public override Color SeparatorLight => ModernTheme.Border;
    }
}

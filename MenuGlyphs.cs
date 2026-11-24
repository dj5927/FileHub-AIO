using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace NASMount;

/// <summary>Shared 24-unit line icons for settings and profile context menus.</summary>
internal static class MenuGlyphs
{
    internal static Bitmap Create(string kind, int size, Color color)
    {
        var image = new Bitmap(size, size, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
        using var g = Graphics.FromImage(image);
        Draw(g, kind, new RectangleF(0, 0, size, size), color);
        return image;
    }

    internal static void Draw(Graphics g, string kind, RectangleF rect, Color color)
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

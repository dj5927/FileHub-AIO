using System.Drawing;
using System.Drawing.Drawing2D;

namespace NASMount;

/// <summary>
/// The left-hand card graphic is a PROTOCOL glyph, not a drive letter.
/// Brand glyphs ship locally, so cards render without network access.
/// </summary>
internal static class ProtocolIconFactory
{
    internal static Image Create(string protocol)
    {
        const int size = 56;
        var canvas = new Bitmap(size, size, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
        using var g = Graphics.FromImage(canvas);
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.InterpolationMode = InterpolationMode.HighQualityBicubic;
        g.Clear(Color.Transparent);
        if (protocol == "GoogleDrive" &&
            DrawImage(g, Path.Combine(AppContext.BaseDirectory, "tools", "GoogleDriveMark.png")))
            return canvas;
        if (protocol == "TeraBox" &&
            DrawImage(g, Path.Combine(AppContext.BaseDirectory, "tools", "TeraBoxMark.png")))
            return canvas;
        if (protocol == "OneDrive")
        {
            DrawOneDrive(g);
            return canvas;
        }
        if (protocol == "MyBox")
        {
            if (DrawImage(g, Path.Combine(AppContext.BaseDirectory,
                    "tools", "MyBoxMark.png"))) return canvas;
            DrawMyBox(g);
            return canvas;
        }
        switch (protocol)
        {
            case "SMB": DrawFolder(g); break;
            case "FTP": DrawTransferArrows(g); break;
            case "SFTP": DrawPadlock(g); break;
            default: DrawGlobe(g); break;
        }
        return canvas;
    }

    private static bool DrawImage(Graphics g, string path)
    {
        if (!File.Exists(path)) return false;
        try
        {
            using var image = Image.FromFile(path);
            g.DrawImage(image, new Rectangle(4, 4, 48, 48));
            return true;
        }
        catch { return false; }
    }

    private static void DrawOneDrive(Graphics g)
    {
        // A crisp cloud in OneDrive blues; drawn locally, no Shell extension.
        using var dark = new SolidBrush(Color.FromArgb(16, 92, 188));
        using var light = new SolidBrush(Color.FromArgb(0, 153, 236));
        using var medium = new SolidBrush(Color.FromArgb(0, 111, 204));
        g.FillEllipse(dark, 9, 18, 30, 28);
        g.FillEllipse(dark, 22, 11, 26, 30);
        g.FillRectangle(dark, 13, 28, 35, 16);
        g.FillEllipse(light, 4, 30, 23, 17);
        g.FillEllipse(light, 17, 25, 20, 20);
        g.FillEllipse(medium, 29, 28, 20, 19);
        g.FillRectangle(light, 14, 34, 31, 13);
    }

    private static void DrawMyBox(Graphics g)
    {
        // MYBOX connection mark with genuinely transparent outer pixels.
        // Logo geometry follows the user's green N-on-roundel reference.
        using var brush = new LinearGradientBrush(new Rectangle(4, 4, 48, 48),
            Color.FromArgb(0, 211, 77), Color.FromArgb(0, 169, 62),
            LinearGradientMode.Vertical);
        g.FillEllipse(brush, 4, 4, 48, 48);
        using var face = new Font("Arial", 31, FontStyle.Bold, GraphicsUnit.Pixel);
        using var white = new SolidBrush(Color.White);
        using var centered = new StringFormat {
            Alignment = StringAlignment.Center,
            LineAlignment = StringAlignment.Center
        };
        g.DrawString("N", face, white, new RectangleF(3, 3, 50, 50), centered);
    }

    private static void DrawGlobe(Graphics g)
    {
        // WebDAV only: neutral globe as in the reference UI.
        using var pen = new Pen(Color.FromArgb(206, 215, 218), 2.8f);
        g.DrawEllipse(pen, 7, 7, 42, 42);
        g.DrawEllipse(pen, 18, 7, 20, 42);
        g.DrawLine(pen, 7, 28, 49, 28);
        g.DrawArc(pen, 8, 16, 40, 24, 18, 144);
        g.DrawArc(pen, 8, 16, 40, 24, 198, 144);
    }

    private static void DrawTransferArrows(Graphics g)
    {
        // FTP: clearly separated upload (up) and download (down) arrows.
        // Solid neutral glyph stays legible in both the 40px protocol list
        // and the smaller 50px main drive-card icon area.
        using var fill = new SolidBrush(Color.FromArgb(221, 229, 233));
        g.FillRectangle(fill, 14, 21, 7, 27);
        g.FillPolygon(fill, [new Point(17, 7), new Point(5, 24),
            new Point(11, 24), new Point(11, 29), new Point(23, 29),
            new Point(23, 24), new Point(29, 24)]);
        g.FillRectangle(fill, 35, 8, 7, 27);
        g.FillPolygon(fill, [new Point(38, 49), new Point(26, 32),
            new Point(32, 32), new Point(32, 27), new Point(44, 27),
            new Point(44, 32), new Point(50, 32)]);
    }

    private static void DrawPadlock(Graphics g)
    {
        // SFTP: secure transfer = unmistakable closed padlock.
        using var stroke = new Pen(Color.FromArgb(221, 229, 233), 5.8f)
        { StartCap = LineCap.Round, EndCap = LineCap.Round };
        g.DrawArc(stroke, 14, 6, 28, 33, 180, 180);
        using var fill = new SolidBrush(Color.FromArgb(221, 229, 233));
        using var body = new GraphicsPath();
        body.AddArc(7, 23, 10, 10, 180, 90);
        body.AddArc(39, 23, 10, 10, 270, 90);
        body.AddArc(39, 42, 10, 10, 0, 90);
        body.AddArc(7, 42, 10, 10, 90, 90);
        body.CloseFigure();
        g.FillPath(fill, body);
        using var hole = new SolidBrush(Color.FromArgb(48, 54, 62));
        g.FillEllipse(hole, 25, 32, 6, 6);
        g.FillRectangle(hole, 27, 35, 2, 8);
    }

    private static void DrawFolder(Graphics g)
    {
        // Cream file-folder silhouette, no hard-drive or cloud shape.
        using var shadow = new SolidBrush(Color.FromArgb(89, 96, 101));
        using var folder = new SolidBrush(Color.FromArgb(236, 225, 202));
        using var tab = new SolidBrush(Color.FromArgb(252, 242, 218));
        using var path = new GraphicsPath();
        path.AddLine(6, 17, 19, 17);
        path.AddLine(19, 17, 25, 23);
        path.AddLine(25, 23, 48, 23);
        path.AddArc(44, 23, 7, 7, 270, 90);
        path.AddLine(51, 27, 51, 43);
        path.AddArc(44, 40, 7, 7, 0, 90);
        path.AddLine(47, 47, 9, 47);
        path.AddArc(5, 40, 7, 7, 90, 90);
        path.AddLine(5, 43, 5, 21);
        path.AddArc(5, 17, 7, 7, 180, 90);
        path.CloseFigure();
        g.FillPath(shadow, path);
        g.FillRectangle(tab, new Rectangle(8, 15, 16, 13));
        g.FillPath(folder, path);
    }

    internal static void VerifyProtocolDistinctnessForUiTest()
    {
        using var webdav = (Bitmap)Create("WebDAV");
        using var ftp = (Bitmap)Create("FTP");
        using var sftp = (Bitmap)Create("SFTP");
        static bool Same(Bitmap a, Bitmap b)
        {
            for (int y = 0; y < a.Height; y++)
                for (int x = 0; x < a.Width; x++)
                    if (a.GetPixel(x, y) != b.GetPixel(x, y)) return false;
            return true;
        }
        if (Same(webdav, ftp) || Same(webdav, sftp) || Same(ftp, sftp))
            throw new InvalidOperationException(
                "WebDAV globe, FTP arrows and SFTP lock must have distinct icons.");
        Console.WriteLine("PROTOCOL_ICONS_GLOBE_ARROWS_PADLOCK_DISTINCT_PASS");
    }
}

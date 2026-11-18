using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace NASMount;

internal static class ModernTheme
{
    [DllImport("dwmapi.dll", PreserveSig = true)]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute,
        ref int value, int size);

    internal static void EnableDarkTitleBar(Form form)
    {
        form.HandleCreated += (_, _) =>
        {
            try
            {
                int enabled = 1;
                _ = DwmSetWindowAttribute(form.Handle, 20, ref enabled, sizeof(int));
            }
            catch { /* Graceful fallback on older Windows versions. */ }
        };
    }

    // Neutral graphite with restrained blue, close to the dense visual
    // hierarchy of familiar drive managers rather than blue-tinted cards.
    internal static readonly Color Background = Color.FromArgb(34, 35, 37);
    internal static readonly Color Surface = Color.FromArgb(43, 44, 46);
    internal static readonly Color Elevated = Color.FromArgb(57, 59, 62);
    internal static readonly Color Field = Color.FromArgb(52, 53, 55);
    internal static readonly Color Accent = Color.FromArgb(33, 132, 215);
    internal static readonly Color Text = Color.FromArgb(245, 245, 246);
    internal static readonly Color Muted = Color.FromArgb(187, 190, 195);
    internal static readonly Color Border = Color.FromArgb(79, 81, 84);
    internal static readonly Color Green = Color.FromArgb(69, 207, 155);

    internal static Label Label(string value, float size = 10f, bool bold = false,
        Color? color = null) => new()
    {
        Text = value, ForeColor = color ?? Text,
        Font = new Font("Malgun Gothic", size, bold ? FontStyle.Bold : FontStyle.Regular),
        AutoEllipsis = true, TextAlign = ContentAlignment.MiddleLeft
    };

    internal static Button Button(string value, int width, Action clicked,
        bool accent = false, int height = 38)
    {
        // A real Region-based rounded Button clips at some DPI values and
        // produces broken blue borders on the first/last pixel. Paint the
        // corners instead; keep the full rectangular hit-test/OS region.
        var button = new ModernButton(accent)
        {
            Text = value, Width = width, Height = height,
            Font = new Font("Malgun Gothic", 9f, accent ? FontStyle.Bold : FontStyle.Regular),
            ForeColor = Text, BackColor = accent ? Accent : Elevated,
            FlatStyle = FlatStyle.Flat, Cursor = Cursors.Hand,
            Margin = new Padding(4, 0, 0, 0), TabStop = true
        };
        button.FlatAppearance.BorderSize = 0;
        button.FlatAppearance.MouseOverBackColor = accent
            ? Color.FromArgb(64, 154, 246) : Color.FromArgb(55, 70, 90);
        button.FlatAppearance.MouseDownBackColor = Color.FromArgb(22, 97, 184);
        button.Click += (_, _) => clicked();
        return button;
    }
}

/// <summary>
/// Owner-drawn WinForms button with anti-aliased rounded fill, no clipping
/// Region and a normal accessible WinForms Button click/focus surface.
/// </summary>
internal sealed class ModernButton : Button
{
    private readonly bool accent;
    private bool hovered;
    private bool pressed;

    internal ModernButton(bool accent)
    {
        this.accent = accent;
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint |
            ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
    }

    protected override void OnMouseEnter(EventArgs e)
    { hovered = true; Invalidate(); base.OnMouseEnter(e); }
    protected override void OnMouseLeave(EventArgs e)
    { hovered = false; pressed = false; Invalidate(); base.OnMouseLeave(e); }
    protected override void OnMouseDown(MouseEventArgs e)
    { pressed = true; Invalidate(); base.OnMouseDown(e); }
    protected override void OnMouseUp(MouseEventArgs e)
    { pressed = false; Invalidate(); base.OnMouseUp(e); }
    protected override void OnGotFocus(EventArgs e) { Invalidate(); base.OnGotFocus(e); }
    protected override void OnLostFocus(EventArgs e) { Invalidate(); base.OnLostFocus(e); }

    protected override void OnPaint(PaintEventArgs e)
    {
        if (Width < 8 || Height < 8) return;
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        e.Graphics.Clear(Parent?.BackColor ?? ModernTheme.Surface);
        float radius = Math.Min(7f, Math.Min(Width, Height) / 4f);
        var bounds = new RectangleF(1f, 1f, Width - 2.5f, Height - 2.5f);
        using var path = new GraphicsPath();
        float diameter = radius * 2f;
        path.AddArc(bounds.Left, bounds.Top, diameter, diameter, 180, 90);
        path.AddArc(bounds.Right - diameter, bounds.Top, diameter, diameter, 270, 90);
        path.AddArc(bounds.Right - diameter, bounds.Bottom - diameter, diameter, diameter, 0, 90);
        path.AddArc(bounds.Left, bounds.Bottom - diameter, diameter, diameter, 90, 90);
        path.CloseFigure();
        var fill = !Enabled ? ModernTheme.Field : pressed ?
            Color.FromArgb(23, 95, 177) : hovered ?
            (accent ? Color.FromArgb(63, 153, 245) : Color.FromArgb(55, 70, 90)) :
            (accent ? ModernTheme.Accent : ModernTheme.Elevated);
        using (var brush = new SolidBrush(fill)) e.Graphics.FillPath(brush, path);
        TextRenderer.DrawText(e.Graphics, Text, Font, Rectangle.Inflate(ClientRectangle, -7, -2),
            Enabled ? ForeColor : ModernTheme.Muted,
            TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter |
            TextFormatFlags.NoPadding | TextFormatFlags.EndEllipsis | TextFormatFlags.SingleLine);
        if (Focused && ShowFocusCues)
        {
            using var pen = new Pen(Color.FromArgb(190, 226, 255), 1f)
                { DashStyle = DashStyle.Dot, Alignment = PenAlignment.Inset };
            e.Graphics.DrawPath(pen, path);
        }
    }
}

/// <summary>Compact owner-drawn file-manager action.</summary>
internal sealed class DriveActionButton : Button
{
    private string kind;
    internal string Kind => kind;
    private bool hovered;
    private bool pressed;
    internal DriveActionButton(string kind, string help, Action action)
    {
        this.kind = kind;
        AccessibleName = help;
        Text = help;
        Size = new Size(36, 36);
        Margin = new Padding(3, 0, 3, 0);
        TabStop = true;
        Cursor = Cursors.Hand;
        FlatStyle = FlatStyle.Flat;
        FlatAppearance.BorderSize = 0;
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint |
            ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
        Click += (_, _) => action();
    }
    internal void SetActionState(bool disconnect)
    {
        string next = disconnect ? "disconnect" : "connect";
        if (kind == next) return;
        kind = next;
        AccessibleName = Text = disconnect ? "연결 해제" : "연결";
        Invalidate(); // only this 36x36 icon, not the whole card/list
    }
    protected override void OnMouseEnter(EventArgs e) { hovered = true; Invalidate(); base.OnMouseEnter(e); }
    protected override void OnMouseLeave(EventArgs e) { hovered = pressed = false; Invalidate(); base.OnMouseLeave(e); }
    protected override void OnMouseDown(MouseEventArgs e) { pressed = true; Invalidate(); base.OnMouseDown(e); }
    protected override void OnMouseUp(MouseEventArgs e) { pressed = false; Invalidate(); base.OnMouseUp(e); }
    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.Clear(Parent?.BackColor ?? ModernTheme.Surface);
        using var fill = new SolidBrush(pressed ? Color.FromArgb(74, 87, 94) :
            hovered ? Color.FromArgb(69, 75, 81) : ModernTheme.Elevated);
        using var outline = new Pen(Color.FromArgb(125, 133, 141), 1.2f);
        using var stroke = new Pen(Color.FromArgb(232, 235, 236), 2f)
            { StartCap = LineCap.Round, EndCap = LineCap.Round, LineJoin = LineJoin.Round };
        g.FillEllipse(fill, 2, 2, Width - 5, Height - 5);
        if (hovered || Focused) g.DrawEllipse(outline, 2, 2, Width - 5, Height - 5);
        float cx = Width / 2f, cy = Height / 2f;
        switch (kind)
        {
            case "connect":
                using (var path = new GraphicsPath())
                {
                    path.AddPolygon([new PointF(cx - 4, cy - 7),
                        new PointF(cx - 4, cy + 7), new PointF(cx + 7, cy)]);
                    using var brush = new SolidBrush(ModernTheme.Text);
                    g.FillPath(brush, path);
                }
                break;
            case "disconnect":
                using (var brush = new SolidBrush(ModernTheme.Text))
                    g.FillRectangle(brush, cx - 6, cy - 6, 12, 12);
                break;
            case "folder":
                using (var path = new GraphicsPath())
                {
                    path.AddPolygon([new PointF(cx - 9, cy - 4),new PointF(cx - 3, cy - 4),
                        new PointF(cx, cy - 1),new PointF(cx + 9,cy - 1),
                        new PointF(cx + 8,cy + 7),new PointF(cx - 9,cy + 7)]);
                    g.DrawPath(stroke, path);
                }
                break;
            case "settings":
                g.DrawEllipse(stroke, cx - 6, cy - 6, 12, 12);
                g.DrawEllipse(stroke, cx - 2, cy - 2, 4, 4);
                for (int n=0;n<8;n++)
                {
                    double a=Math.PI*n/4;
                    g.DrawLine(stroke,
                        cx+(float)(7*Math.Cos(a)), cy+(float)(7*Math.Sin(a)),
                        cx+(float)(9*Math.Cos(a)), cy+(float)(9*Math.Sin(a)));
                }
                break;
            case "more":
                using (var brush = new SolidBrush(ModernTheme.Text))
                    for (int n=-1;n<=1;n++)
                        g.FillEllipse(brush, cx-2+n*6,cy-2,4,4);
                break;
        }
    }
}

/// <summary>
/// Solid-blue, vector-drawn toolbar button. No hover, pressed, or focus
/// decoration: the icon and underlying titlebar pixels are always identical.
/// The 23px glyph viewport matches the 23px brand icon at the left.
/// </summary>
internal sealed class TopBarIconButton : Button
{
    internal string Kind { get; }
    internal const int IconViewport = 23;
    internal static readonly Color TitleBlue = Color.FromArgb(8, 108, 185);

    internal TopBarIconButton(string kind, string accessibleName, int width, Action action)
    {
        Kind = kind;
        Text = "";
        AccessibleName = accessibleName;
        Width = width;
        Height = 33;
        Margin = Padding.Empty;
        Padding = Padding.Empty;
        TextAlign = ContentAlignment.MiddleCenter;
        BackColor = TitleBlue;
        ForeColor = Color.White;
        FlatStyle = FlatStyle.Flat;
        FlatAppearance.BorderSize = 0;
        FlatAppearance.MouseOverBackColor = TitleBlue;
        FlatAppearance.MouseDownBackColor = TitleBlue;
        Cursor = Cursors.Hand;
        TabStop = true;
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint |
            ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
        Click += (_, _) => action();
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.Clear(TitleBlue);
        g.SmoothingMode = SmoothingMode.AntiAlias;
        float cx = Width / 2f, cy = Height / 2f;
        using var stroke = new Pen(Color.White, 2.1f)
        {
            StartCap = LineCap.Round, EndCap = LineCap.Round,
            LineJoin = LineJoin.Round
        };
        switch (Kind)
        {
            case "add":
                g.DrawLine(stroke, cx - 8.5f, cy, cx + 8.5f, cy);
                g.DrawLine(stroke, cx, cy - 8.5f, cx, cy + 8.5f);
                break;
            case "settings":
                g.DrawEllipse(stroke, cx - 6.8f, cy - 6.8f, 13.6f, 13.6f);
                g.DrawEllipse(stroke, cx - 2.4f, cy - 2.4f, 4.8f, 4.8f);
                for (int n = 0; n < 8; n++)
                {
                    float a = (float)(Math.PI * n / 4);
                    g.DrawLine(stroke,
                        cx + 7.5f * MathF.Cos(a), cy + 7.5f * MathF.Sin(a),
                        cx + 10.1f * MathF.Cos(a), cy + 10.1f * MathF.Sin(a));
                }
                break;
            case "refresh":
                g.DrawArc(stroke, cx - 8, cy - 8, 16, 16, -47, 297);
                using (var brush = new SolidBrush(Color.White))
                    g.FillPolygon(brush, [
                        new PointF(cx + 7.7f, cy - 9.7f),
                        new PointF(cx + 10.1f, cy - 2.2f),
                        new PointF(cx + 2.9f, cy - 5.2f)]);
                break;
            case "close":
                g.DrawLine(stroke, cx - 6.2f, cy - 6.2f, cx + 6.2f, cy + 6.2f);
                g.DrawLine(stroke, cx + 6.2f, cy - 6.2f, cx - 6.2f, cy + 6.2f);
                break;
        }
        // Intentionally no base.OnPaint(): WinForms flat buttons otherwise
        // add a dotted focus rectangle, white border, and hover tint.
    }

    internal Bitmap RenderForTest()
    {
        var bitmap = new Bitmap(Width, Height);
        DrawToBitmap(bitmap, new Rectangle(Point.Empty, bitmap.Size));
        return bitmap;
    }

    internal void RaiseHoverForTest() => OnMouseEnter(EventArgs.Empty);
    internal void RaiseLeaveForTest() => OnMouseLeave(EventArgs.Empty);
}

/// <summary>Double-buffered parent/card: no white erase between text updates.</summary>
internal sealed class SmoothCardPanel : Panel
{
    internal SmoothCardPanel()
    {
        SetStyle(ControlStyles.AllPaintingInWmPaint |
            ControlStyles.OptimizedDoubleBuffer |
            ControlStyles.ResizeRedraw, true);
        UpdateStyles();
    }
}

internal sealed partial class MainForm
{
    private readonly Panel cards = new SmoothCardPanel
    {
        Dock = DockStyle.Fill,
        AutoScroll = true,
        BackColor = ModernTheme.Background,
        Padding = new Padding(0, 4, 2, 4)
    };
    private readonly Label emptyHint = ModernTheme.Label("", 12, false, ModernTheme.Muted);
    private Guid? selectedId;
    private sealed class ModernCardView
    {
        internal required NasProfile Profile;
        internal required string VisualKey;
        internal required Panel Card;
        internal required Label Badge;
        internal required DriveActionButton ConnectButton;
        internal required ToolTip Hints;
        internal bool Online;
        internal bool Running;
    }
    private readonly Dictionary<Guid, ModernCardView> modernCardViews = new();
    private static string CardVisualKey(NasProfile p) =>
        string.Join("\u001f", p.Protocol, p.DisplayName,
            p.Letter, p.Host, p.Share, p.RemoteDisplay);

    private void InitializeModernUi(bool minimized)
    {
        Text = "FileHub AIO v1.3.101  |  PC 드라이브";
        FormBorderStyle = FormBorderStyle.None;
        ControlBox = false;
        MaximizeBox = false;
        MinimizeBox = false;
        SizeGripStyle = SizeGripStyle.Hide;
        ClientSize = new Size(770, 566);
        StartPosition = FormStartPosition.CenterScreen;
        AutoScaleMode = AutoScaleMode.Dpi;
        Font = new Font("Malgun Gothic", 8.8f);
        BackColor = ModernTheme.Background;
        ForeColor = ModernTheme.Text;
        try { Icon = new Icon(ExplorerDriveIcon.IconPath); }
        catch { /* Preserve application UI if a custom icon is missing. */ }

        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill, RowCount = 2, ColumnCount = 1,
            BackColor = ModernTheme.Background,
            Padding = Padding.Empty
        };
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 33));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        Controls.Add(root);

        // Fill the top edge, left to right, without any internal titlebar margin.
        var header = new Panel {
            Dock = DockStyle.Fill, Margin = Padding.Empty,
            Padding = Padding.Empty, BackColor = Color.FromArgb(8, 108, 185)
        };
        var headerIcon = new PictureBox {
            Bounds = new Rectangle(7, 5, 23, 23),
            SizeMode = PictureBoxSizeMode.Zoom,
            BackColor = Color.Transparent
        };
        try {
            using var badge = new Icon(ExplorerDriveIcon.IconPath, 48, 48);
            headerIcon.Image = badge.ToBitmap();
        } catch { /* A missing icon must never block connection controls. */ }
        header.Controls.Add(headerIcon);
        var brand = ModernTheme.Label("FileHub AIO  v1.3.101", 9.3f, true);
        brand.SetBounds(35, 1, 215, 31);
        header.Controls.Add(brand);
        var actions = new FlowLayoutPanel
        {
            Dock = DockStyle.Right, Width = 152, Height = 33,
            Margin = Padding.Empty, FlowDirection = FlowDirection.LeftToRight,
            WrapContents = false, Padding = Padding.Empty, AutoScroll = false,
            BackColor = Color.Transparent
        };
        TopBarIconButton ActionIcon(string kind, string hint, int width, Action handler)
        {
            // No tooltip or border on hover. AccessibleName still describes
            // each icon to screen readers and automation.
            return new TopBarIconButton(kind, hint, width, handler);
        }
        actions.Controls.Add(ActionIcon("add", "새 연결 추가", 34, Add));
        actions.Controls.Add(ActionIcon("settings", "환경 설정", 34, ShowSettings));
        actions.Controls.Add(ActionIcon("refresh", "연결 목록 새로고침", 34, RefreshList));
        actions.Controls.Add(new Panel {
            Width = 1, Height = 20,
            Margin = new Padding(6, 7, 6, 6),
            BackColor = Color.FromArgb(150, 211, 243)
        });
        actions.Controls.Add(ActionIcon("close", "창 숨기기 · 종료는 트레이 메뉴", 37,
            () => Close()));
        header.Controls.Add(actions);
        EnableHeaderDrag(header);
        EnableHeaderDrag(headerIcon);
        EnableHeaderDrag(brand);
        root.Controls.Add(header, 0, 0);

        var body = new Panel { Dock = DockStyle.Fill, Padding = new Padding(4, 2, 4, 0),
            BackColor = ModernTheme.Background };
        root.Controls.Add(body, 0, 1);
        // Two independent updates, two lines: WinFsp driver and PC OTA.
        var bottom = new Panel { Dock = DockStyle.Bottom, Height = 44,
            BackColor = ModernTheme.Background };
        footerNotices = new Panel {
            Dock = DockStyle.Right, Width = 0, Height = 44,
            BackColor = ModernTheme.Background, Visible = false
        };
        winFspNotice = new LinkLabel {
            Text = "", Dock = DockStyle.Top, Height = 21,
            AutoSize = false, Visible = false,
            TextAlign = ContentAlignment.MiddleRight,
            Padding = new Padding(2, 0, 10, 0),
            LinkColor = Color.FromArgb(255, 159, 98),
            ActiveLinkColor = Color.White,
            VisitedLinkColor = Color.FromArgb(255, 159, 98),
            BackColor = ModernTheme.Background,
            Font = new Font("Malgun Gothic", 8f, FontStyle.Bold),
            LinkBehavior = LinkBehavior.HoverUnderline,
            Cursor = Cursors.Hand
        };
        winFspNotice.LinkClicked += (_, _) => ShowSettings();
        var driverTip = new ToolTip();
        driverTip.SetToolTip(winFspNotice,
            "PC 앱 OTA는 공유 시스템 드라이버를 자동 교체하지 않습니다.\n" +
            "WinFsp 버전만으로 연결 실패를 판단하지 않습니다.\n" +
            "RaiDrive와 FileHub가 정상이라면 드라이버 교체는 불필요합니다.\n" +
            "환경 설정 → WinFsp 확인 / 설치에서 실제 버전을 확인하세요.");
        updateNotice = new LinkLabel {
            Text = "",
            Dock = DockStyle.Bottom,
            Height = 23,
            AutoSize = false,
            Visible = false,
            TextAlign = ContentAlignment.MiddleRight,
            Padding = new Padding(3, 0, 10, 0),
            LinkColor = Color.FromArgb(255, 198, 75),
            ActiveLinkColor = Color.White,
            VisitedLinkColor = Color.FromArgb(255, 198, 75),
            BackColor = ModernTheme.Background,
            Font = new Font("Malgun Gothic", 8.1f, FontStyle.Bold),
            LinkBehavior = LinkBehavior.HoverUnderline,
            Cursor = Cursors.Hand
        };
        updateNotice.LinkClicked += async (_, _) =>
            await CheckForPcUpdateAsync(preferCached: true, startDownloadNow: true);
        status.Dock = DockStyle.Fill;
        status.Font = new Font("Malgun Gothic", 8f);
        status.ForeColor = ModernTheme.Muted;
        status.TextAlign = ContentAlignment.MiddleLeft;
        status.Padding = new Padding(7, 0, 0, 0);
        bottom.Controls.Add(status);
        footerNotices.Controls.Add(updateNotice);
        footerNotices.Controls.Add(winFspNotice);
        bottom.Controls.Add(footerNotices);
        body.Controls.Add(bottom);
        cards.SizeChanged += (_, _) => ResizeModernCards();
        body.Controls.Add(cards);
        cards.BringToFront();
        InstallSlideHost(body);

        tray = new NotifyIcon
        {
            Icon = Icon ?? SystemIcons.Application, Text = "FileHub AIO v1.3.101",
            Visible = true, ContextMenuStrip = new ContextMenuStrip()
        };
        tray.ContextMenuStrip.Items.Add("FileHub AIO v1.3.101 열기", null, (_, _) => ShowWindow());
        tray.ContextMenuStrip.Items.Add("모두 연결", null, async (_, _) => await ConnectAll(true));
        tray.ContextMenuStrip.Items.Add("프로그램 종료", null, async (_, _) => await ExitProgramFast());
        tray.BalloonTipClicked += async (_, _) =>
            await CheckForPcUpdateAsync(preferCached: true, startDownloadNow: true);
        tray.DoubleClick += (_, _) => ShowWindow();
        FormClosed += (_, _) => tray.Dispose();
        FormClosing += (_, e) =>
        {
            if (Environment.GetEnvironmentVariable("NASMOUNT_UI_SMOKE") == "1")
                return; // The isolated QA form must dispose normally.
            if (ShouldCloseToTray(e.CloseReason, exiting))
            {
                e.Cancel = true;
                // X hides the window to the notification area. Only the
                // tray's "프로그램 종료" command performs a real shutdown.
                Hide();
                ShowInTaskbar = false;
            }
        };
        Resize += (_, _) =>
        {
            if (WindowState == FormWindowState.Minimized)
            { Hide(); ShowInTaskbar = false; }
        };
        timer.Tick += async (_, _) =>
        {
            // Auto connect cannot race local engine initialization. Failed
            // preflight is retried on timer, without touching remote drives.
            if (!startupDependenciesReady) await PrepareStartupEnginesAsync();
            if (startupDependenciesReady) await ConnectAll(false);
            // Do NOT poll Explorer's virtual "This PC" folder for Google
            // during every timer tick. Shell COM may ask WinFsp for remote
            // volume data and stall the entire Explorer refresh. A user can
            // still run the explicit "Google 탐색기 표시 재확인" action.
        };
        // If rclone's local RC call lasts longer than five seconds, skip
        // that timer tick rather than queueing redundant requests forever.
        // Explicit card selection can still request one follow-up check.
        uploadStatusTimer.Tick += async (_, _) =>
        {
            if (!transferStatusRefreshing)
                await UpdateTransferIndicator();
        };
        RefreshList();
        RefreshWinFspNotice();
        Activated += (_, _) => RefreshWinFspNotice();
        Shown += async (_, _) =>
        {
            if (Environment.GetEnvironmentVariable("NASMOUNT_UI_SMOKE") == "1") return;
            if (minimized) { Hide(); ShowInTaskbar = false; }
            // Start the GitHub PC version check immediately; slow NAS mounts
            // must not delay the bottom-right update notification.
            _ = CheckStartupPcUpdateAsync();
            // On the first real startup, offer the bundled signed WinFsp MSI
            // if an auto-connected NAS needs it. Never install silently.
            if (!RemoteMount.HasWinFsp && cfg.Profiles.Any(p =>
                    p.AutoConnect && p.Protocol != "SMB"))
            {
                if (MessageBox.Show(this,
                    "NAS 드라이브 연결에 필요한 WinFsp가 설치되지 않았습니다.\n" +
                    "포함된 공식 설치 파일을 실행할까요? (관리자 승인 필요)",
                    "필수 드라이버 설치", MessageBoxButtons.YesNo,
                    MessageBoxIcon.Question) == DialogResult.Yes)
                    await InstallWinFsp();
            }
            await CheckStartupRemnants();
            try
            {
                if (await PrepareStartupEnginesAsync())
                    await ConnectAll(false);
            }
            finally
            {
                if (!shutdownRequested && !IsDisposed)
                {
                    timer.Start();
                    uploadStatusTimer.Start();
                    _ = UpdateTransferIndicator();
                }
            }
        };
    }

    private void ResizeModernCards()
    {
        // Manual vertical card layout avoids a WinForms FlowLayoutPanel bug
        // that produces a horizontal scrollbar after resizing smaller.
        int width = Math.Max(0, cards.ClientSize.Width -
            (cards.VerticalScroll.Visible ? SystemInformation.VerticalScrollBarWidth : 0) - 3);
        int y = 4;
        cards.SuspendLayout();
        cards.AutoScrollMinSize = Size.Empty;
        foreach (Control control in cards.Controls)
        {
            if (control is not Panel) continue;
            control.SetBounds(0, y, width, control.Height);
            y += control.Height + 3;
        }
        cards.AutoScrollMinSize = new Size(0, y + 4);
        cards.ResumeLayout(true);
    }

    private void RefreshModernCards()
    {
        // Connection state changes must NOT destroy/recreate every child
        // WinForms handle. Previously this caused a visible whole-list
        // blink on each successful concurrent connect/disconnect.
        bool sameStructure = cfg.Profiles.Count == modernCardViews.Count &&
            (cfg.Profiles.Count > 0 || cards.Controls.Count > 0) &&
            cfg.Profiles.All(p =>
                modernCardViews.TryGetValue(p.Id, out var view) &&
                !view.Card.IsDisposed && ReferenceEquals(view.Profile, p) &&
                view.VisualKey == CardVisualKey(p));
        if (sameStructure)
        {
            foreach (var p in cfg.Profiles)
            {
                bool online = IsConnected(p);
                bool running = !online && p.Protocol != "SMB" &&
                    RemoteMount.HasManagedRecord(p);
                UpdateModernCardView(modernCardViews[p.Id], online, running);
            }
            string footer = $"{(RemoteMount.HasWinFsp ? "● WinFsp 정상" : "● WinFsp 설치 필요")}    ·    " +
                "NAS 연결 및 파일 잠금 상태";
            if (status.Text != footer && status.Text != lastTransferWarning)
                status.Text = footer;
            return;
        }
        cards.SuspendLayout();
        try
        {
            var previous = cards.Controls.Cast<Control>().ToArray();
            foreach (var old in modernCardViews.Values)
                old.Hints.Dispose();
            cards.Controls.Clear();
            foreach (var item in previous) item.Dispose();
            modernCardViews.Clear();
            int connected = 0;
            foreach (var p in cfg.Profiles)
            {
                bool online = IsConnected(p);
                bool runningNotBrowsable = !online && p.Protocol != "SMB" &&
                    RemoteMount.HasManagedRecord(p);
                if (online) connected++;
                cards.Controls.Add(CreateModernCard(p, online, runningNotBrowsable));
            }
            if (cfg.Profiles.Count == 0)
            {
                var placeholder = new Panel { Height = 135, BackColor = ModernTheme.Surface,
                    Margin = new Padding(0, 0, 0, 10) };
                var message = ModernTheme.Label("연결된 드라이브가 없습니다. 상단 [추가] 버튼으로 등록하세요.",
                    12, false, ModernTheme.Muted);
                message.Dock = DockStyle.Fill;
                message.TextAlign = ContentAlignment.MiddleCenter;
                placeholder.Controls.Add(message);
                cards.Controls.Add(placeholder);
            }
            if (status.Text != lastTransferWarning)
                status.Text = NormalTransferFooter();
        }
        finally
        {
            cards.ResumeLayout();
            ResizeModernCards();
        }
    }

    private void UpdateModernCardView(ModernCardView view, bool online, bool running)
    {
        if (view.Card.IsDisposed) return;
        string nextText = LockStatusPresentation.CardStatus(
            view.Profile, online, running,
            manualReconnectPause.IsSuspended(view.Profile.Id),
            view.Profile.Protocol != "SMB" &&
                RemoteMount.DriveLetterExists(view.Profile.Letter));
        var nextColor = online ? ModernTheme.Green :
            running ? Color.FromArgb(122, 199, 247) : ModernTheme.Muted;
        if (view.Badge.Text != nextText) view.Badge.Text = nextText;
        if (view.Badge.ForeColor != nextColor) view.Badge.ForeColor = nextColor;
        bool nowMountedOrRunning = online || running;
        bool wasMountedOrRunning = view.Online || view.Running;
        if (nowMountedOrRunning != wasMountedOrRunning)
        {
            view.ConnectButton.SetActionState(nowMountedOrRunning);
            view.Hints.SetToolTip(view.ConnectButton,
                nowMountedOrRunning ? "연결 해제" : "연결");
        }
        bool borderChanged = view.Online != online;
        view.Online = online;
        view.Running = running;
        if (borderChanged) view.Card.Invalidate();
    }

    private Panel CreateModernCard(NasProfile p, bool online, bool runningNotBrowsable)
    {
        var card = new SmoothCardPanel { Height = 56, BackColor = ModernTheme.Surface,
            Margin = new Padding(0, 0, 0, 3), Cursor = Cursors.Hand, Padding = new Padding(6, 2, 6, 2) };
        // Do not set a rounded Region on the card: a WinForms Region clips
        // the outer border and leaves broken segments at DPI scaling.
        card.Paint += (_, e) =>
        {
            bool selected = selectedId == p.Id;
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            using var border = new Pen(selected ? ModernTheme.Accent : ModernTheme.Border,
                selected ? 2f : 1f) { Alignment = PenAlignment.Inset };
            var r = card.ClientRectangle;
            e.Graphics.DrawRectangle(border, r.X + 1, r.Y + 1, r.Width - 3, r.Height - 3);
            bool active = (card.Tag as ModernCardView)?.Online ?? online;
            using var pen = new Pen(active ? ModernTheme.Green : ModernTheme.Muted, 2f);
            e.Graphics.DrawLine(pen, 3, 7, 3, r.Height - 8);
        };

        var right = new FlowLayoutPanel
        {
            Dock = DockStyle.Right, Width = 188,
            FlowDirection = FlowDirection.LeftToRight,
            WrapContents = false, Padding = new Padding(3, 8, 0, 0)
        };
        void FocusCard()
        {
            selectedId = p.Id;
            card.Invalidate();
            RefreshSelectedTransferStatus();
        }
        bool mountedOrRunning = online || runningNotBrowsable;
        var hints = new ToolTip { AutoPopDelay = 6000, InitialDelay = 320, ReshowDelay = 150 };
        DriveActionButton Action(string kind, string label, Action click)
        {
            var button = new DriveActionButton(kind, label, click);
            hints.SetToolTip(button, label);
            right.Controls.Add(button);
            return button;
        }
        var toggle = Action(mountedOrRunning ? "disconnect" : "connect",
            mountedOrRunning ? "연결 해제" : "연결",
            () =>
            {
                FocusCard();
                bool busy = IsConnected(p) ||
                    (p.Protocol != "SMB" && RemoteMount.HasManagedRecord(p));
                _ = SelectedOperation(!busy);
            });
        Action("settings", "연결 설정", () => { FocusCard(); Edit(); });
        Action("folder", "드라이브 폴더 열기", () => { FocusCard(); OpenDrive(); });
        Action("more", "추가 메뉴", () => { FocusCard(); ShowCardMenu(card, p); });
        var content = new TableLayoutPanel
        {
            Dock = DockStyle.Left, ColumnCount = 2, RowCount = 3,
            Cursor = Cursors.Hand
        };
        // Explicitly reserve the right-hand action area. Dock.Fill can
        // overlap the buttons at certain DPI and during bitmap rendering.
        void FitContent() => content.Width = Math.Max(230,
            card.ClientSize.Width - right.Width - card.Padding.Horizontal);
        card.SizeChanged += (_, _) => FitContent();
        content.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 45));
        content.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        content.RowStyles.Add(new RowStyle(SizeType.Absolute, 19));
        content.RowStyles.Add(new RowStyle(SizeType.Absolute, 16));
        content.RowStyles.Add(new RowStyle(SizeType.Absolute, 16));
        var protocolIcon = new PictureBox {
            Image = ProtocolIconFactory.Create(p.Protocol),
            SizeMode = PictureBoxSizeMode.Zoom, Dock = DockStyle.Fill,
            Padding = new Padding(4, 6, 5, 6),
            BackColor = Color.Transparent, Cursor = Cursors.Hand,
            AccessibleName = p.Protocol + " 연결 아이콘"
        };
        content.Controls.Add(protocolIcon, 0, 0);
        content.SetRowSpan(protocolIcon, 3);
        var nameRow = new FlowLayoutPanel {
            Dock = DockStyle.Fill, FlowDirection = FlowDirection.LeftToRight,
            WrapContents = false, AutoScroll = false,
            Margin = Padding.Empty, Padding = Padding.Empty,
            BackColor = Color.Transparent, Cursor = Cursors.Hand
        };
        var name = ModernTheme.Label(p.DisplayName, 8.6f, true);
        name.AutoEllipsis = true;
        name.AutoSize = false;
        name.Width = Math.Clamp(TextRenderer.MeasureText(p.DisplayName, name.Font).Width + 4, 28, 285);
        name.Height = 19;
        name.Margin = new Padding(0, 0, 3, 0);
        var letter = ModernTheme.Label("(" + p.Letter.ToUpperInvariant() + ":)", 7.5f, false,
            online ? ModernTheme.Muted : ModernTheme.Muted);
        letter.AutoSize = false;
        letter.Width = 40;
        letter.Height = 19;
        letter.Margin = Padding.Empty;
        nameRow.Controls.Add(name);
        nameRow.Controls.Add(letter);
        content.Controls.Add(nameRow, 1, 0);
        string address = p.Protocol == "TeraBox" ? "테라박스 계정 연결" :
            p.Protocol == "GoogleDrive" ? "Google 계정 연결" :
            p.Protocol == "OneDrive" ? "Microsoft 계정 연결" :
            p.Protocol == "MyBox" ? "네이버 MYBOX Open API · 계정 연결" :
            p.Protocol == "SMB" ? p.Unc : p.Host +
            (p.Share.Length > 0 ? "/" + p.Share.TrimStart('/') : "");
        var info = ModernTheme.Label($"{p.Protocol}   ·   {address}", 7.5f,
            color: ModernTheme.Muted);
        info.Dock = DockStyle.Fill;
        content.Controls.Add(info, 1, 1);
        var badge = ModernTheme.Label(LockStatusPresentation.CardStatus(p, online,
                runningNotBrowsable, manualReconnectPause.IsSuspended(p.Id),
                p.Protocol != "SMB" && RemoteMount.DriveLetterExists(p.Letter)),
            7.1f, false, online ? ModernTheme.Green :
                runningNotBrowsable ? Color.FromArgb(122, 199, 247) : ModernTheme.Muted);
        badge.Dock = DockStyle.Fill;
        content.Controls.Add(badge, 1, 2);
        card.Controls.Add(content);
        // WinForms docking runs in z-order: right actions must dock first,
        // otherwise the fill panel covers every card action button.
        card.Controls.Add(right);
        right.BringToFront();
        FitContent();
        void SelectCard(object? sender, EventArgs e)
        {
            selectedId = p.Id;
            foreach (Control c in cards.Controls) c.Invalidate();
            RefreshSelectedTransferStatus();
        }
        card.Click += SelectCard;
        content.Click += SelectCard;
        protocolIcon.Click += SelectCard;
        nameRow.Click += SelectCard;
        name.Click += SelectCard;
        info.Click += SelectCard;
        badge.Click += SelectCard;
        letter.Click += SelectCard;
        card.DoubleClick += (_, _) => {
            selectedId = p.Id;
            RefreshSelectedTransferStatus();
            OpenDrive();
        };
        card.ContextMenuStrip = MakeCardMenu(p);
        var view = new ModernCardView
        {
            Profile = p, VisualKey = CardVisualKey(p), Card = card,
            Badge = badge, ConnectButton = toggle, Hints = hints,
            Online = online, Running = runningNotBrowsable
        };
        card.Tag = view;
        modernCardViews[p.Id] = view;
        return card;
    }

    internal void VerifyNoFlickerCardRefreshForUiTest()
    {
        if (Environment.GetEnvironmentVariable("NASMOUNT_UI_SMOKE") != "1")
            throw new InvalidOperationException("Visual no-flicker test is test-only");
        if (cfg.Profiles.Count == 0) return;
        // The old implementation deleted every handle on a single state
        // change. The new one must preserve all cards and other buttons.
        var before = cfg.Profiles.ToDictionary(p => p.Id,
            p => modernCardViews[p.Id].Card);
        RefreshModernCards();
        foreach (var p in cfg.Profiles)
            if (!ReferenceEquals(before[p.Id], modernCardViews[p.Id].Card))
                throw new InvalidOperationException(
                    "Unchanged connection caused complete card rebuild");
        var view = modernCardViews[cfg.Profiles[0].Id];
        bool oldOnline = view.Online;
        bool oldRunning = view.Running;
        try
        {
            // Synthetic visual updates only; no real mount or user data.
            UpdateModernCardView(view, true, false);
            if (view.ConnectButton.Kind != "disconnect" ||
                view.Badge.ForeColor != ModernTheme.Green ||
                !ReferenceEquals(before[view.Profile.Id], view.Card))
                throw new InvalidOperationException("Connected state update was not in place");
            UpdateModernCardView(view, false, false);
            if (view.ConnectButton.Kind != "connect" ||
                view.Badge.ForeColor != ModernTheme.Muted)
                throw new InvalidOperationException("Disconnected state update was not in place");
        }
        finally { UpdateModernCardView(view, oldOnline, oldRunning); }
        RefreshModernCards();
        foreach (var p in cfg.Profiles)
            if (!ReferenceEquals(before[p.Id], modernCardViews[p.Id].Card))
                throw new InvalidOperationException("State-only change rebuilt card");
        Console.WriteLine("NO_FLICKER_CARD_STABLE_HANDLE_STATUS_ONLY_PASS");
    }

    internal void VerifyProtocolIconsForUiTest()
    {
        if (Icon is null)
            throw new InvalidOperationException("Window title icon missing");
        var header = Controls.OfType<TableLayoutPanel>().FirstOrDefault()?
            .Controls.OfType<Panel>().FirstOrDefault();
        if (header?.Controls.OfType<PictureBox>().FirstOrDefault(x => x.Image is not null)
            is not { } icon || icon.Width < 20 || icon.Height < 20)
            throw new InvalidOperationException("NAS MOUNT header logo missing or undersized");
        if (tray.Icon is null)
            throw new InvalidOperationException("Tray icon missing");
        foreach (var p in cfg.Profiles)
        {
            var card = cards.Controls.OfType<Panel>().FirstOrDefault(c =>
                c.Controls.OfType<TableLayoutPanel>().Any(t =>
                    t.Controls.OfType<FlowLayoutPanel>().Any(row =>
                        row.Controls.OfType<Label>().Any(n => n.Text == p.DisplayName))));
            if (card is null) throw new InvalidOperationException("Card missing: " + p.DisplayName);
            var grid = card.Controls.OfType<TableLayoutPanel>().Single();
            if (p.Protocol != "SMB" &&
                card.ContextMenuStrip?.Items.Cast<ToolStripItem>()
                    .All(x => x.Text != "끊어진 네트워크 위치 정리") != false)
                throw new InvalidOperationException(
                    "Manual disconnected network-location recovery menu missing");
            if (p.Protocol == "WebDAV")
            {
                var menu = card.ContextMenuStrip ??
                    throw new InvalidOperationException("WebDAV diagnostic menu missing");
                string[] captions = menu.Items.Cast<ToolStripItem>().Select(x => x.Text).ToArray();
                if (!captions.Contains("파일잠금 상태 확인") ||
                    !captions.Contains("서버 잠금 충돌 검사"))
                    throw new InvalidOperationException("WebDAV lock inspection actions not exposed");
                if (p.UseLockBridge &&
                    !LockStatusPresentation.CardStatus(p, true, false).Contains("파일잠금 브리지"))
                    throw new InvalidOperationException("WebDAV protected mode is not shown");
            }
            if (card.Height > 74 || card.Width > ClientSize.Width ||
                card.Controls.OfType<FlowLayoutPanel>().Single().Controls.OfType<DriveActionButton>().Count() != 4)
                throw new InvalidOperationException("Compact card row or action layout invalid: " + p.Protocol);
            var glyph = grid.Controls.OfType<PictureBox>().SingleOrDefault();
            if (glyph?.Image is null || glyph.AccessibleName != p.Protocol + " 연결 아이콘")
                throw new InvalidOperationException("Protocol icon missing: " + p.Protocol);
            using (var sample = new Bitmap(glyph.Image))
            {
                if (!Enumerable.Range(0, sample.Width).Any(x =>
                    Enumerable.Range(0, sample.Height).Any(y => sample.GetPixel(x, y).A > 0)))
                    throw new InvalidOperationException("Protocol icon is transparent: " + p.Protocol);
            }
            var nameRow = grid.Controls.OfType<FlowLayoutPanel>().Single();
            var labels = nameRow.Controls.OfType<Label>().ToArray();
            if (labels.Length != 2 || labels[0].Text != p.DisplayName ||
                labels[1].Text != "(" + p.Letter.ToUpperInvariant() + ":)" ||
                labels[1].Font.Size >= labels[0].Font.Size)
                throw new InvalidOperationException("Letter is not small beside connection name: " + p.Protocol);
        }
        var toolbar = header?.Controls.OfType<FlowLayoutPanel>().FirstOrDefault();
        if (toolbar is null)
            throw new InvalidOperationException("Borderless titlebar missing");
        var titleButtons = toolbar.Controls.OfType<TopBarIconButton>().ToArray();
        var separator = toolbar.Controls.OfType<Panel>().SingleOrDefault(p => p.Width == 1);
        var kindOrder = new[] { "add", "settings", "refresh", "close" };
        if (header is null || header.Left != 0 || header.Top != 0 ||
            header.Right != ClientSize.Width || header.Height != 33 ||
            toolbar.Width != 152 || titleButtons.Length != 4 ||
            !titleButtons.Select(b => b.Kind).SequenceEqual(kindOrder) ||
            titleButtons.Any(b => b.Height != 33 || b.Text.Length != 0 ||
                b.FlatAppearance.MouseOverBackColor != TopBarIconButton.TitleBlue ||
                b.FlatAppearance.MouseDownBackColor != TopBarIconButton.TitleBlue ||
                b.FlatAppearance.BorderSize != 0 ||
                string.IsNullOrWhiteSpace(b.AccessibleName)) ||
            titleButtons.Take(3).Any(b => b.Width != 34) ||
            TopBarIconButton.IconViewport != 23 ||
            titleButtons[^1].Right != toolbar.ClientSize.Width ||
            separator is null || separator.Left <= titleButtons[2].Right ||
            separator.Right >= titleButtons[3].Left)
            throw new InvalidOperationException("Compact plain-icon titlebar alignment invalid");
        foreach (var button in titleButtons)
        {
            using var before = button.RenderForTest();
            button.RaiseHoverForTest();
            using var hovered = button.RenderForTest();
            button.RaiseLeaveForTest();
            using var left = button.RenderForTest();
            int visiblePixels = 0;
            for (int y = 0; y < button.Height; y++)
                for (int x = 0; x < button.Width; x++)
                {
                    if (before.GetPixel(x, y) != hovered.GetPixel(x, y) ||
                        before.GetPixel(x, y) != left.GetPixel(x, y))
                        throw new InvalidOperationException(
                            "Hover tinted or bordered titlebar icon: " + button.Kind);
                    if (before.GetPixel(x, y).ToArgb() != TopBarIconButton.TitleBlue.ToArgb())
                    {
                        visiblePixels++;
                        if (Math.Abs(x - (button.Width - 1) / 2f) > 12 ||
                            Math.Abs(y - (button.Height - 1) / 2f) > 12)
                            throw new InvalidOperationException(
                                "Titlebar glyph outside 23px icon area: " + button.Kind);
                    }
                }
            if (visiblePixels < 16)
                throw new InvalidOperationException("Titlebar icon not drawn: " + button.Kind);
        }
        if (FormBorderStyle != FormBorderStyle.None || ControlBox ||
            MaximizeBox || MinimizeBox || SizeGripStyle != SizeGripStyle.Hide)
            throw new InvalidOperationException("Window is not fixed/borderless with only custom close button");
        if (Controls.OfType<TableLayoutPanel>().Single().RowCount != 2 ||
            Controls.OfType<TableLayoutPanel>().Single().Controls.OfType<Panel>().Any(
                p => p.Controls.OfType<Label>().Any(l =>
                    l.Text.Contains("연결 목록") || l.Text.Contains("전체 "))))
            throw new InvalidOperationException("Unwanted connection list/count header visible");
    }

    private ContextMenuStrip MakeCardMenu(NasProfile p)
    {
        var menu = new ContextMenuStrip { BackColor = ModernTheme.Surface,
            ForeColor = ModernTheme.Text, ShowImageMargin = false };
        void Item(string caption, Action action)
        {
            menu.Items.Add(caption, null, (_, _) => {
                selectedId = p.Id;
                RefreshSelectedTransferStatus();
                action();
            });
        }
        Item("드라이브 열기", OpenDrive);
        Item("연결 설정", Edit);
        if (SynologySharing.IsNas(p))
            Item("Synology 공유 설정", () => {
                // Open after the card menu closes to avoid WinForms reentry
                // into ContextMenuStrip keyboard processing.
                BeginInvoke((Action)(() => {
                    if (IsDisposed || Disposing) return;
                    using var form = new SynologySettingsForm(cfg, preferredProfileId: p.Id);
                    form.ShowDialog(this);
                }));
            });
        if (p.Protocol == "WebDAV")
        {
            Item("파일잠금 상태 확인", () => _ = ShowWebDavLockStatus());
            Item("서버 잠금 충돌 검사", () => _ = RemoteLockCheck());
        }
        Item("연결 진단", () => _ = DiagnoseSelected());
        if (p.Protocol == "GoogleDrive")
            Item("Google 탐색기 표시 재확인", () =>
                _ = CheckGoogleExplorerVisibilityAsync(p, manual: true));
        if (p.Protocol == "GoogleDrive")
            Item("Google 계정 재인증", () => _ = ReauthorizeGoogleDrive());
        if (p.Protocol == "OneDrive")
            Item("Microsoft 계정 재인증", () => _ = ReauthorizeOneDrive());
        Item("업로드 상태", () => _ = CheckUploadStatus());
        if (p.Protocol != "SMB")
            Item("끊어진 네트워크 위치 정리", () => _ = CleanupOldNetworkLocation());
        Item("로그 폴더", OpenLogs);
        menu.Items.Add(new ToolStripSeparator());
        Item("연결 삭제", Delete);
        return menu;
    }

    private void ShowCardMenu(Control control, NasProfile p)
    {
        // Reuse the menu owned by the card. Disposing on Closed caused a
        // WinForms re-entrant ProcessDialogKey to touch a disposed Handle.
        // The card lifecycle owns disposal; Closed must NOT dispose it.
        var menu = control.ContextMenuStrip ??= MakeCardMenu(p);
        selectedId = p.Id;
        RefreshSelectedTransferStatus();
        menu.Show(control, new Point(Math.Max(0, control.Width - 200), control.Height - 7));
    }

    internal void VerifyCardMenuLifetimeForUiTest()
    {
        if (cards.Controls.OfType<Panel>().FirstOrDefault() is not { } card ||
            cfg.Profiles.Count == 0)
            throw new InvalidOperationException("Card unavailable for UI menu test");
        var originalMenu = card.ContextMenuStrip ??
            throw new InvalidOperationException("ContextMenuStrip not attached to card");
        for (int n = 0; n < 2; n++)
        {
            ShowCardMenu(card, cfg.Profiles[0]);
            Application.DoEvents();
            originalMenu.Close();
            Application.DoEvents();
            if (originalMenu.IsDisposed || !ReferenceEquals(card.ContextMenuStrip, originalMenu))
                throw new InvalidOperationException("ContextMenuStrip disposed after close");
        }
    }

    internal static bool ShouldCloseToTray(CloseReason reason, bool isExiting) =>
        !isExiting && reason == CloseReason.UserClosing;

    internal void VerifyCloseToTrayPolicyForUiTest()
    {
        if (!ShouldCloseToTray(CloseReason.UserClosing, false) ||
            ShouldCloseToTray(CloseReason.WindowsShutDown, false) ||
            ShouldCloseToTray(CloseReason.UserClosing, true) ||
            tray.ContextMenuStrip is null ||
            tray.ContextMenuStrip.Items.Cast<ToolStripItem>()
                .Count(item => item.Text == "프로그램 종료") != 1)
            throw new InvalidOperationException(
                "X must hide to tray; only tray Exit may terminate application");
        Console.WriteLine("CLOSE_BUTTON_TO_TRAY_AND_TRAY_EXIT_SEPARATE_PASS");
    }

    private bool EnsureAutomaticDriveLetter(NasProfile p, bool showErrors)
    {
        // Never reallocate a letter while a live (even unbrowsable) backend
        // may still hold open Office documents and pending VFS uploads.
        if (IsConnected(p) || (p.Protocol != "SMB" && RemoteMount.IsRunning(p)))
            return true;
        // Preserve the assigned letter if still free; moving an existing
        // profile at every connection breaks its shortcuts and Office paths.
        string old = p.Letter.ToUpperInvariant();
        bool reservedByOther = cfg.Profiles.Any(q => q.Id != p.Id &&
            string.Equals(q.Letter, old, StringComparison.OrdinalIgnoreCase));
        bool occupiedInWindows = Environment.GetLogicalDrives().Any(d =>
            d.StartsWith(old + ":", StringComparison.OrdinalIgnoreCase));
        if (old.Length == 1 && old[0] is >= 'D' and <= 'Z' &&
            !reservedByOther && !occupiedInWindows) return true;
        if (DriveLetterAllocator.GoogleLetterMustRemainStable(
                p, reservedByOther, occupiedInWindows))
        {
            // Auto-moving a Google profile O: -> Z: -> M: changes user
            // shortcuts and does NOT repair WinFsp error 0x80070050.
            retry[p.Id] = DateTime.UtcNow.AddMinutes(15);
            AppStore.Log("GOOGLE_DRIVE_LETTER_COLLISION_NO_REASSIGN id=" +
                p.Id + " letter=" + old +
                " occupied=" + occupiedInWindows +
                " reserved=" + reservedByOther);
            if (showErrors)
                MessageBox.Show(this,
                    "Google Drive의 " + old + ": 드라이브 문자를 Windows 또는 " +
                    "다른 FileHub 연결이 이미 사용하고 있습니다.\n" +
                    "드라이브 문자를 임의로 바꾸지 않았습니다.\n\n" +
                    "열린 탐색기와 이전 FileHub 연결이 남아 있는지 확인하고,\n" +
                    "필요하면 연결 설정에서 사용 가능한 문자를 직접 지정하세요.",
                    "Google Drive 문자 충돌", MessageBoxButtons.OK,
                    MessageBoxIcon.Warning);
            return false;
        }
        var next = DriveLetterAllocator.HighestFree(cfg.Profiles, p.Id);
        if (next is null)
        {
            if (showErrors) MessageBox.Show("사용 가능한 드라이브 문자가 없습니다. D:~Z: 범위를 확인하세요.",
                "FileHub AIO", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return false;
        }
        if (string.Equals(p.Letter, next, StringComparison.OrdinalIgnoreCase)) return true;
        var previous = p.Letter;
        p.Letter = next;
        try
        {
            AppStore.Save(cfg);
            AppStore.Log("AUTO_LETTER " + p.Id + " " + previous + " -> " + next);
            RefreshModernCards();
            return true;
        }
        catch (Exception ex)
        {
            p.Letter = previous;
            if (showErrors) MessageBox.Show("드라이브 문자 자동 배정 저장 실패: " + ex.Message);
            return false;
        }
    }

    private void ShowModernSettings()
    {
        using var dialog = new Form
        {
            Text = "FileHub AIO · 환경 설정", ClientSize = new Size(610, 560),
            MinimumSize = new Size(560, 500), StartPosition = FormStartPosition.CenterParent,
            BackColor = ModernTheme.Background, ForeColor = ModernTheme.Text,
            Font = new Font("Malgun Gothic", 10), AutoScaleMode = AutoScaleMode.Dpi
        };
        ModernTheme.EnableDarkTitleBar(dialog);
        var body = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(23),
            ColumnCount = 1, RowCount = 3 };
        body.RowStyles.Add(new RowStyle(SizeType.Absolute, 70));
        body.RowStyles.Add(new RowStyle(SizeType.Absolute, 75));
        body.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        dialog.Controls.Add(body);
        var settingsTitle = ModernTheme.Label("환경 설정", 18, true);
        settingsTitle.Dock = DockStyle.Fill;
        body.Controls.Add(settingsTitle, 0, 0);
        var autoStart = new CheckBox
        {
            Text = "Windows 로그인 시 FileHub AIO 자동 실행", Checked = AppStore.IsAutoStartEnabled(),
            ForeColor = ModernTheme.Text, BackColor = ModernTheme.Background,
            Dock = DockStyle.Fill, FlatStyle = FlatStyle.Flat
        };
        autoStart.CheckedChanged += (_, _) =>
        {
            try { AppStore.SetAutoStart(autoStart.Checked); cfg.StartWithWindows = autoStart.Checked; AppStore.Save(cfg); }
            catch (Exception ex) { MessageBox.Show(ex.Message); }
        };
        body.Controls.Add(autoStart, 0, 1);
        var group = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown,
            WrapContents = false, AutoScroll = true, BackColor = ModernTheme.Background
        };
        body.Controls.Add(group, 0, 2);
        var diagnosticsTitle = ModernTheme.Label("연결 관리 및 진단", 11, true);
        diagnosticsTitle.Size = new Size(500, 36);
        group.Controls.Add(diagnosticsTitle);
        void Tool(string label, Action action)
        {
            var button = ModernTheme.Button(label, 350, action, height: 35);
            button.Margin = new Padding(0, 4, 0, 4);
            group.Controls.Add(button);
        }
        Tool("연결 진단", () => _ = DiagnoseSelected());
        Tool("업로드 상태 확인", () => _ = CheckUploadStatus());
        Tool("WebDAV 저장 테스트", () => _ = CheckWebDavSave());
        Tool("Excel 읽기 진단", () => _ = CompareExcelRead());
        Tool("파일 잠금 검사", () => _ = LockCheck());
        Tool("서버 잠금 검사", () => _ = RemoteLockCheck());
        Tool("WebDAV 표준 잠금 검사", () => _ = CheckWebDavStandardLock());
        Tool("연결 상태 유지 확인", () => _ = CheckPersistentMount());
        Tool("루트 목록 갱신", () => _ = RefreshRootMetadata());
        Tool("전체 연결 재시도", () => _ = ConnectAll(true));
        Tool("WinFsp 설치 / 확인", () => _ = InstallWinFsp());
        Tool("로그 폴더 열기", OpenLogs);
        dialog.ShowDialog(this);
    }
}

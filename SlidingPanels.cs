using System.Drawing;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace NASMount;

/// <summary>
/// Borderless main window, draggable custom titlebar and animated in-window
/// editor pages. Forms are hosted as child controls, not modal pop-up windows.
/// </summary>
internal sealed partial class MainForm
{
    [DllImport("user32.dll")]
    private static extern bool ReleaseCapture();
    [DllImport("user32.dll", CharSet = CharSet.Auto)]
    private static extern IntPtr SendMessage(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam);

    private Panel? slideHost;
    private Panel? slidePanel;
    private Form? slideView;
    private System.Windows.Forms.Timer? slideAnimation;
    private bool slideOut;
    private bool forceAnimationForTest;

    private void EnableHeaderDrag(Control control)
    {
        control.MouseDown += (_, e) =>
        {
            if (e.Button != MouseButtons.Left || !IsHandleCreated) return;
            ReleaseCapture();
            SendMessage(Handle, 0x00A1 /* WM_NCLBUTTONDOWN */,
                (IntPtr)2 /* HTCAPTION */, IntPtr.Zero);
        };
    }

    private void InstallSlideHost(Panel body)
    {
        slideHost = body;
        body.Resize += (_, _) =>
        {
            if (slidePanel is null) return;
            int left = slideOut ? slidePanel.Left : 0;
            slidePanel.SetBounds(left, 0, body.ClientSize.Width, body.ClientSize.Height);
            if (slideView is not null && !slideView.IsDisposed)
                LayoutSlideView(slideView);
        };
        KeyPreview = true;
        KeyDown += (_, e) =>
        {
            if (e.KeyCode == Keys.Escape && slidePanel is not null)
            {
                CloseSlidingView();
                e.Handled = true;
            }
        };
    }

    private void LayoutSlideView(Form view)
    {
        if (slidePanel is null || view.IsDisposed) return;
        int width = view.Width;
        int x = Math.Max(0, (slidePanel.ClientSize.Width - width) / 2);
        // Main FileHub AIO titlebar is already visible above the slide host.
        // No duplicate blue section header or its old 32px empty offset.
        view.Location = new Point(x, 0);
    }

    private void OpenSlidingView(Form view, string heading, Action<Form>? onSaved = null)
    {
        if (slideHost is null) { view.Dispose(); return; }
        if (slidePanel is not null) CloseSlideImmediately();
        slidePanel = new Panel
        {
            BackColor = ModernTheme.Background,
            Size = slideHost.ClientSize,
            Location = new Point(slideHost.ClientSize.Width, 0),
            TabStop = true
        };
        slideHost.Controls.Add(slidePanel);
        slidePanel.BringToFront(); // covers footer/status bar as well as list
        // Keep the section name for accessibility but no visible duplicate
        // section bar. The profile's service header and settings heading
        // already identify the displayed page.
        slidePanel.AccessibleName = heading;

        view.TopLevel = false;
        view.FormBorderStyle = FormBorderStyle.None;
        view.ShowInTaskbar = false;
        view.FormClosed += (_, _) =>
        {
            if (ReferenceEquals(slideView, view) && view.DialogResult == DialogResult.OK)
                onSaved?.Invoke(view);
            if (ReferenceEquals(slideView, view))
            {
                slideView = null;
                AnimateSlide(closing: true);
            }
        };
        slideView = view;
        slidePanel.Controls.Add(view);
        LayoutSlideView(view);
        view.Show();
        view.BringToFront();
        AnimateSlide(closing: false);
    }

    private void CloseSlidingView()
    {
        if (slideView is { IsDisposed: false } view)
        {
            view.DialogResult = DialogResult.Cancel;
            view.Close();
        }
        else if (slidePanel is not null)
            AnimateSlide(closing: true);
    }

    private void AnimateSlide(bool closing)
    {
        if (slidePanel is null || slideHost is null) return;
        slideAnimation?.Stop();
        slideAnimation?.Dispose();
        slideAnimation = null;
        slideOut = closing;
        if (Environment.GetEnvironmentVariable("NASMOUNT_UI_SMOKE") == "1" && !forceAnimationForTest)
        {
            if (closing) CloseSlideImmediately();
            else slidePanel.Left = 0;
            return;
        }
        var timer = new System.Windows.Forms.Timer { Interval = 14 };
        slideAnimation = timer;
        timer.Tick += (_, _) =>
        {
            if (slidePanel is null || slideHost is null)
            {
                timer.Stop(); timer.Dispose();
                if (ReferenceEquals(slideAnimation, timer)) slideAnimation = null;
                return;
            }
            int target = closing ? slideHost.ClientSize.Width : 0;
            int distance = Math.Max(1, (slideHost.ClientSize.Width + 5) / 9);
            slidePanel.Left += closing ? distance : -distance;
            if (closing ? slidePanel.Left >= target : slidePanel.Left <= target)
            {
                slidePanel.Left = target;
                timer.Stop(); timer.Dispose();
                if (ReferenceEquals(slideAnimation, timer)) slideAnimation = null;
                if (closing) CloseSlideImmediately();
            }
        };
        timer.Start();
    }

    private void CloseSlideImmediately()
    {
        slideAnimation?.Stop();
        slideAnimation?.Dispose();
        slideAnimation = null;
        if (slideView is { IsDisposed: false } view)
        {
            slideView = null;
            view.DialogResult = DialogResult.Cancel;
            view.Close();
        }
        slideView = null;
        var panel = slidePanel;
        slidePanel = null;
        panel?.Dispose();
        slideOut = false;
    }

    internal bool SlidingPanelOpenForTest => slidePanel is not null && slidePanel.Left == 0;
    internal void VerifySlideChromeForTest(bool profile)
    {
        if (slidePanel is null || slideView is null)
            throw new InvalidOperationException("Slide is not open");
        if (slidePanel.Controls.Count != 1 || slidePanel.Controls[0] != slideView ||
            slidePanel.Controls.OfType<Panel>().Any() ||
            slideView.Top != 0 ||
            slidePanel.AccessibleName?.Contains(profile ? "연결 설정" : "환경 설정") != true)
            throw new InvalidOperationException(
                "Slide must have no duplicate blue header or 32px top gap");
        if (profile)
        {
            var labels = slideView.Controls.OfType<Button>().Select(x => x.Text).ToArray();
            if (!labels.Contains("취소") || !labels.Contains("닫기") || !labels.Contains("저장"))
                throw new InvalidOperationException("Profile editor cancel/close/save buttons missing");
        }
    }
    internal bool SlidingPanelHidesStatusForTest =>
        slidePanel is not null && slideHost is not null &&
        slidePanel.Height == slideHost.ClientSize.Height &&
        slidePanel.Parent == slideHost &&
        slideHost.Controls.GetChildIndex(slidePanel) == 0 &&
        slideView is { Visible: true, TopLevel: false } &&
        slideView.Parent == slidePanel;
    internal void CaptureSlidePanelForTest(string destination)
    {
        if (slidePanel is null || slideView is null || !SlidingPanelHidesStatusForTest)
            throw new InvalidOperationException("Sliding panel not ready for visual QA");
        using var image = new Bitmap(slidePanel.Width, slidePanel.Height);
        slidePanel.DrawToBitmap(image, new Rectangle(Point.Empty, image.Size));
        // DrawToBitmap on WinForms does not reliably capture an embedded
        // non-top-level Form; render the editor explicitly for QA only.
        using var child = new Bitmap(slideView.Width, slideView.Height);
        slideView.DrawToBitmap(child, new Rectangle(Point.Empty, child.Size));
        using (var graphics = Graphics.FromImage(image))
            graphics.DrawImageUnscaled(child, slideView.Location);
        image.Save(destination);
    }
    internal void OpenSettingsSlideForTest() => ShowCompactSettings();
    internal void OpenProfileSlideForTest(NasProfile profile) =>
        OpenSlidingView(new ModernProfileDialog(profile.Clone(), true), "연결 설정");
    internal void PressFirstCardGearForTest()
    {
        var card = cards.Controls.OfType<Panel>().First();
        var gear = card.Controls.OfType<FlowLayoutPanel>().Single()
            .Controls.OfType<DriveActionButton>()
            .First(b => b.AccessibleName == "연결 설정");
        gear.PerformClick();
    }
    internal void CloseSlideForTest() => CloseSlidingView();
    internal void VerifySlideAnimationForTest()
    {
        if (slidePanel is not null) throw new InvalidOperationException("Slide is already open");
        forceAnimationForTest = true;
        try
        {
            OpenSlidingView(CreateCompactSettingsForm(), "환경 설정");
            if (slidePanel?.Left != slideHost?.ClientSize.Width)
                throw new InvalidOperationException("Slide did not begin off the right edge");
            for (int i = 0; i < 35 && !SlidingPanelOpenForTest; i++)
            {
                System.Threading.Thread.Sleep(18);
                Application.DoEvents();
            }
            if (!SlidingPanelOpenForTest || !SlidingPanelHidesStatusForTest)
                throw new InvalidOperationException("Right-to-left sliding animation did not complete");
            CloseSlidingView();
            for (int i = 0; i < 35 && slidePanel is not null; i++)
            {
                System.Threading.Thread.Sleep(18);
                Application.DoEvents();
            }
            if (slidePanel is not null)
                throw new InvalidOperationException("Left-to-right closing animation did not complete");
        }
        finally
        {
            forceAnimationForTest = false;
            CloseSlideImmediately();
        }
    }
}


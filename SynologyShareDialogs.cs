using System.Drawing;
using System.Windows.Forms;

namespace NASMount;

internal sealed class SynologyShareSelectorDialog : Form
{
    private readonly CheckedListBox checklist = new() {
        CheckOnClick = true, BorderStyle = BorderStyle.FixedSingle,
        IntegralHeight = false
    };
    private readonly List<SynologyFileEntry> shares;
    private readonly Label selectionCount = new();

    internal List<string> SelectedRoots =>
        checklist.CheckedIndices.Cast<int>()
            .Select(index => shares[index].Path).ToList();

    internal SynologyShareSelectorDialog(List<SynologyFileEntry> available,
        IEnumerable<string> selected)
    {
        shares = available;
        var old = new HashSet<string>(selected, StringComparer.Ordinal);
        Text = "Synology 공유 폴더 다중 선택";
        ClientSize = new Size(520, 525);
        StartPosition = FormStartPosition.CenterParent;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        AutoScaleMode = AutoScaleMode.Dpi;
        BackColor = ModernTheme.Background;
        ForeColor = ModernTheme.Text;
        Font = new Font("Malgun Gothic", 9f);
        ModernTheme.EnableDarkTitleBar(this);

        var title = new Label { Text = "공유 폴더 선택",
            Font = new Font("Malgun Gothic", 14f, FontStyle.Bold),
            Location = new Point(20, 15), Size = new Size(480, 31),
            ForeColor = ModernTheme.Text };
        Controls.Add(title);
        var hint = new Label {
            Text = "DSM 계정에서 접근할 수 있는 폴더입니다. 여러 개 체크할 수 있습니다.",
            Location = new Point(20, 55), Size = new Size(480, 26),
            ForeColor = ModernTheme.Muted
        };
        Controls.Add(hint);
        checklist.SetBounds(20, 86, 480, 340);
        checklist.BackColor = ModernTheme.Surface;
        checklist.ForeColor = ModernTheme.Text;
        for (int i = 0; i < shares.Count; i++)
        {
            var share = shares[i];
            checklist.Items.Add(share.Name == share.Path[1..] ?
                share.Path : $"{share.Name}   ({share.Path})",
                old.Contains(share.Path));
        }
        Controls.Add(checklist);
        selectionCount.SetBounds(20, 436, 470, 23);
        selectionCount.ForeColor = ModernTheme.Muted;
        selectionCount.Text = $"총 {shares.Count}개 폴더";
        Controls.Add(selectionCount);
        checklist.ItemCheck += (_, _) => BeginInvoke((Action)(() =>
            selectionCount.Text = $"선택 {checklist.CheckedItems.Count}개 / 전체 {shares.Count}개"));

        var accept = ModernTheme.Button("선택 완료", 154, () => {
            if (SelectedRoots.Count == 0) {
                MessageBox.Show(this, "공유 폴더를 하나 이상 선택하세요.",
                    "Synology 공유 폴더", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            DialogResult = DialogResult.OK;
            Close();
        }, accent: true);
        accept.SetBounds(186, 472, 154, 34);
        Controls.Add(accept);
        var cancel = ModernTheme.Button("취소", 154, () => {
            DialogResult = DialogResult.Cancel; Close();
        });
        cancel.SetBounds(346, 472, 154, 34);
        Controls.Add(cancel);
        AcceptButton = accept;
        CancelButton = cancel;
    }
}

internal sealed class SynologyFileBrowserDialog : Form
{
    private readonly IReadOnlyList<string> roots;
    private readonly Func<string, Task<List<SynologyFileEntry>>> listFolder;
    private readonly ComboBox rootCombo = new() { DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly ListView files = new() {
        View = View.Details, FullRowSelect = true,
        HideSelection = false, MultiSelect = false
    };
    private readonly Label currentPath = new() { AutoEllipsis = true };
    private readonly Label message = new() { AutoEllipsis = true };
    private readonly Button choose;
    private string folder = "";
    private bool loading;
    internal string? SelectedPath { get; private set; }

    internal SynologyFileBrowserDialog(IReadOnlyList<string> allowedRoots,
        Func<string, Task<List<SynologyFileEntry>>> listFolder)
    {
        roots = allowedRoots;
        this.listFolder = listFolder;
        Text = "DSM 파일 / 폴더 탐색";
        ClientSize = new Size(720, 562);
        StartPosition = FormStartPosition.CenterParent;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false; MinimizeBox = false;
        AutoScaleMode = AutoScaleMode.Dpi;
        BackColor = ModernTheme.Background; ForeColor = ModernTheme.Text;
        Font = new Font("Malgun Gothic", 9f);
        ModernTheme.EnableDarkTitleBar(this);

        var heading = new Label { Text = "선택한 DSM 공유 폴더 탐색",
            Font = new Font("Malgun Gothic", 13f, FontStyle.Bold),
            Location = new Point(18, 12), Size = new Size(670, 35),
            ForeColor = ModernTheme.Text };
        Controls.Add(heading);
        rootCombo.SetBounds(18, 57, 490, 30);
        rootCombo.Items.AddRange(roots.Cast<object>().ToArray());
        Controls.Add(rootCombo);
        var up = ModernTheme.Button("상위 폴더", 100, () => _ = UpAsync());
        up.SetBounds(521, 57, 181, 30);
        Controls.Add(up);
        currentPath.SetBounds(18, 94, 684, 24);
        currentPath.ForeColor = ModernTheme.Muted;
        Controls.Add(currentPath);
        files.SetBounds(18, 125, 684, 350);
        files.BackColor = ModernTheme.Surface;
        files.ForeColor = ModernTheme.Text;
        files.Columns.Add("이름", 420);
        files.Columns.Add("종류", 105);
        files.Columns.Add("DSM 경로", 500);
        Controls.Add(files);
        files.DoubleClick += (_, _) => _ = OpenOrChooseAsync();
        files.SelectedIndexChanged += (_, _) =>
            choose.Enabled = !loading && !string.IsNullOrEmpty(Selection());
        message.SetBounds(18, 483, 680, 22);
        message.ForeColor = ModernTheme.Muted;
        message.Text = "파일은 선택하여 링크 생성, 폴더는 더블클릭해 이동할 수 있습니다.";
        Controls.Add(message);
        choose = ModernTheme.Button("이 파일 / 폴더 선택", 210, Confirm, accent: true);
        choose.SetBounds(278, 516, 210, 33);
        choose.Enabled = false;
        Controls.Add(choose);
        var cancel = ModernTheme.Button("취소", 205, () => {
            DialogResult = DialogResult.Cancel; Close();
        });
        cancel.SetBounds(497, 516, 205, 33);
        Controls.Add(cancel);
        CancelButton = cancel;
        rootCombo.SelectedIndexChanged += (_, _) => {
            if (!loading && rootCombo.SelectedIndex >= 0)
                _ = NavigateAsync(roots[rootCombo.SelectedIndex]);
        };
    }

    internal async Task<string?> ChooseAsync(IWin32Window owner)
    {
        if (roots.Count == 0)
            throw new ArgumentException("선택한 공유 폴더가 없습니다.");
        rootCombo.SelectedIndex = 0;
        await NavigateAsync(roots[0]);
        return ShowDialog(owner) == DialogResult.OK ? SelectedPath : null;
    }

    private string? Selection() =>
        files.SelectedItems.Count > 0 &&
        files.SelectedItems[0].Tag is SynologyFileEntry entry ?
        entry.Path : null;

    private async Task NavigateAsync(string requested)
    {
        if (loading) return;
        if (!roots.Any(root => SynologySharing.InRoot(root, requested)))
            throw new ArgumentException("허용되지 않은 DSM 경로입니다.");
        loading = true; choose.Enabled = false;
        UseWaitCursor = true;
        try
        {
            var items = await listFolder(requested);
            if (IsDisposed) return;
            folder = requested;
            currentPath.Text = folder;
            files.BeginUpdate();
            try
            {
                files.Items.Clear();
                foreach (var item in items)
                {
                    var row = new ListViewItem(item.Name) { Tag = item };
                    row.SubItems.Add(item.IsDirectory ? "폴더" : "파일");
                    row.SubItems.Add(item.Path);
                    files.Items.Add(row);
                }
            }
            finally { files.EndUpdate(); }
            message.Text = $"현재 {folder} · {items.Count}개 항목";
        }
        catch (Exception ex)
        {
            message.Text = "탐색 실패: " + ex.Message;
            MessageBox.Show(this, ex.Message, "DSM 폴더 탐색",
                MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
        finally { loading = false; UseWaitCursor = false; }
    }

    private async Task UpAsync()
    {
        if (loading || string.IsNullOrWhiteSpace(folder)) return;
        string? root = roots.FirstOrDefault(r => SynologySharing.InRoot(r, folder));
        if (root is null || folder == root) return;
        string parent = folder[..folder.LastIndexOf('/')];
        if (SynologySharing.InRoot(root, parent)) await NavigateAsync(parent);
    }

    private async Task OpenOrChooseAsync()
    {
        if (loading || files.SelectedItems.Count != 1 ||
            files.SelectedItems[0].Tag is not SynologyFileEntry entry) return;
        if (entry.IsDirectory) await NavigateAsync(entry.Path);
        else Confirm();
    }

    private void Confirm()
    {
        if (loading) return;
        // No arbitrary free-form path: only File Station-returned entries.
        var selected = Selection();
        if (selected is null) {
            MessageBox.Show(this, "공유할 파일 또는 폴더를 선택하세요.");
            return;
        }
        SelectedPath = selected;
        DialogResult = DialogResult.OK;
        Close();
    }
}

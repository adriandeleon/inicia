# inicia's window. inicia.exe starts this, writes events to its stdin, one JSON object per line,
# and reads its answers from stdout, one per line: "ready", "install <ids>", "cancel", "retry", "quit".
#   {"t":"pick", "apps":[{"id","name","category","description","version","size","installed","note","suite"}], "suites":[{"name","ids"}]}
#   {"t":"plan", "title", "apps":[{"id","name","status","state"}]}   the progress view
#   {"t":"app", "id", "status", "state":"waiting|working|ok|skip|fail"}
#   {"t":"overall", "value":0-1000, "text"}
#   {"t":"log", "text"}
#   {"t":"done", "title", "text", "ok", "logs"}
#   {"t":"retry", "title", "ids":[...]}   the failed apps run again
$ErrorActionPreference = 'Stop'
[Console]::InputEncoding = [Text.UTF8Encoding]::new($false)
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

# stdin is read on a runspace of its own, so the window never waits on it
$queue = [System.Collections.Concurrent.ConcurrentQueue[string]]::new()
$runspace = [runspacefactory]::CreateRunspace()
$runspace.Open()
$runspace.SessionStateProxy.SetVariable('queue', $queue)
$reader = [powershell]::Create()
$reader.Runspace = $runspace
[void]$reader.AddScript({
  while ($null -ne ($line = [Console]::In.ReadLine())) { $queue.Enqueue($line) }
  $queue.Enqueue('{"t":"eof"}')
})
[void]$reader.BeginInvoke()

function Send([string]$text) { [Console]::Out.WriteLine($text); [Console]::Out.Flush() }

$accent = [System.Drawing.Color]::FromArgb(11, 110, 79)
$muted = [System.Drawing.Color]::FromArgb(98, 94, 87)
$failColor = [System.Drawing.Color]::FromArgb(176, 32, 32)
$noteColor = [System.Drawing.Color]::FromArgb(138, 90, 0)
$font = New-Object System.Drawing.Font('Segoe UI', 9)
$headerFont = New-Object System.Drawing.Font('Segoe UI Semibold', 13)

$form = New-Object System.Windows.Forms.Form
$form.Text = 'inicia'
$form.Font = $font
$form.AutoScaleMode = 'Dpi'
$form.ClientSize = New-Object System.Drawing.Size(700, 500)
$form.MinimumSize = New-Object System.Drawing.Size(560, 420)
$form.StartPosition = 'CenterScreen'
$form.BackColor = [System.Drawing.Color]::White
$scale = $form.DeviceDpi / 96.0

# --- icons, drawn here so the window needs no files: the title bar's, and one per row state ---
function New-Canvas([int]$size) {
  $bmp = New-Object System.Drawing.Bitmap $size, $size
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.SmoothingMode = 'AntiAlias'
  $g.Clear([System.Drawing.Color]::Transparent)
  return @($bmp, $g)
}

$bmp, $g = New-Canvas 32
$g.FillEllipse((New-Object System.Drawing.SolidBrush $accent), 2, 2, 28, 28)
$g.DrawString('i', (New-Object System.Drawing.Font('Segoe UI', 15, [System.Drawing.FontStyle]::Bold)), [System.Drawing.Brushes]::White, 9, 1)
$g.Dispose()
$form.Icon = [System.Drawing.Icon]::FromHandle($bmp.GetHicon())

$iconSize = [int][Math]::Round(16 * $scale)
$images = New-Object System.Windows.Forms.ImageList
$images.ColorDepth = 'Depth32Bit'
$images.ImageSize = New-Object System.Drawing.Size($iconSize, $iconSize)
function Add-Icon([string]$key, [scriptblock]$draw) {
  $bmp, $g = New-Canvas $iconSize
  $s = $iconSize / 16.0
  $g.ScaleTransform($s, $s)
  & $draw $g
  $g.Dispose()
  $images.Images.Add($key, $bmp)
}
$pen = { param($color, $width) $p = New-Object System.Drawing.Pen($color, $width); $p.StartCap = 'Round'; $p.EndCap = 'Round'; $p }
Add-Icon 'waiting' { param($g) $g.DrawEllipse((& $pen ([System.Drawing.Color]::FromArgb(170, 166, 158)) 1.4), 3, 3, 10, 10) }
Add-Icon 'working' { param($g)
  $g.DrawEllipse((& $pen ([System.Drawing.Color]::FromArgb(210, 230, 222)) 2), 2.5, 2.5, 11, 11)
  $g.DrawArc((& $pen $accent 2), 2.5, 2.5, 11, 11, -90, 250) }
Add-Icon 'ok' { param($g)
  $g.FillEllipse((New-Object System.Drawing.SolidBrush $accent), 1.5, 1.5, 13, 13)
  $g.DrawLines((& $pen ([System.Drawing.Color]::White) 1.8), [System.Drawing.PointF[]]@(
    (New-Object System.Drawing.PointF(4.8, 8.2)), (New-Object System.Drawing.PointF(7, 10.4)), (New-Object System.Drawing.PointF(11.2, 5.8)))) }
Add-Icon 'skip' { param($g)
  $c = [System.Drawing.Color]::FromArgb(150, 146, 138)
  $g.DrawEllipse((& $pen $c 1.4), 2, 2, 12, 12)
  $g.DrawLines((& $pen $c 1.6), [System.Drawing.PointF[]]@(
    (New-Object System.Drawing.PointF(5, 8.2)), (New-Object System.Drawing.PointF(7.1, 10.2)), (New-Object System.Drawing.PointF(11, 6)))) }
Add-Icon 'fail' { param($g)
  $g.FillEllipse((New-Object System.Drawing.SolidBrush $failColor), 1.5, 1.5, 13, 13)
  $p = & $pen ([System.Drawing.Color]::White) 1.8
  $g.DrawLine($p, 5.5, 5.5, 10.5, 10.5); $g.DrawLine($p, 10.5, 5.5, 5.5, 10.5) }
Add-Icon 'note' { param($g)
  $g.FillPolygon((New-Object System.Drawing.SolidBrush $noteColor), [System.Drawing.PointF[]]@(
    (New-Object System.Drawing.PointF(8, 1.5)), (New-Object System.Drawing.PointF(15, 14.5)), (New-Object System.Drawing.PointF(1, 14.5))))
  $g.FillRectangle([System.Drawing.Brushes]::White, 7.2, 5.5, 1.6, 5)
  $g.FillRectangle([System.Drawing.Brushes]::White, 7.2, 11.6, 1.6, 1.6) }
Add-Icon 'none' { param($g) }

# --- layout ---
$layout = New-Object System.Windows.Forms.TableLayoutPanel
$layout.Dock = 'Fill'
$layout.Padding = New-Object System.Windows.Forms.Padding(18, 14, 18, 14)
$layout.ColumnCount = 1
$layout.RowCount = 7
[void]$layout.ColumnStyles.Add((New-Object System.Windows.Forms.ColumnStyle([System.Windows.Forms.SizeType]::Percent, 100)))
foreach ($i in 0..6) { [void]$layout.RowStyles.Add((New-Object System.Windows.Forms.RowStyle([System.Windows.Forms.SizeType]::AutoSize))) }
$layout.RowStyles[4] = New-Object System.Windows.Forms.RowStyle([System.Windows.Forms.SizeType]::Percent, 100)
$form.Controls.Add($layout)

$header = New-Object System.Windows.Forms.Label
$header.Font = $headerFont
$header.AutoSize = $true
$header.Margin = New-Object System.Windows.Forms.Padding(0, 0, 0, 2)
$layout.Controls.Add($header, 0, 0)

$status = New-Object System.Windows.Forms.Label
$status.ForeColor = $muted
$status.AutoSize = $true
$status.MaximumSize = New-Object System.Drawing.Size([int](660 * $scale), 0)
$status.Margin = New-Object System.Windows.Forms.Padding(0, 0, 0, 8)
$layout.Controls.Add($status, 0, 1)

# the picker's search: .NET Framework's TextBox has no placeholder text, so a label says what it is
$searchRow = New-Object System.Windows.Forms.TableLayoutPanel
$searchRow.Dock = 'Fill'
$searchRow.AutoSize = $true
$searchRow.ColumnCount = 2
$searchRow.Visible = $false
$searchRow.Margin = New-Object System.Windows.Forms.Padding(0, 0, 0, 8)
[void]$searchRow.ColumnStyles.Add((New-Object System.Windows.Forms.ColumnStyle([System.Windows.Forms.SizeType]::AutoSize)))
[void]$searchRow.ColumnStyles.Add((New-Object System.Windows.Forms.ColumnStyle([System.Windows.Forms.SizeType]::Percent, 100)))
$searchLabel = New-Object System.Windows.Forms.Label
$searchLabel.Text = 'Search:'
$searchLabel.AutoSize = $true
$searchLabel.Margin = New-Object System.Windows.Forms.Padding(0, 4, 6, 0)
$search = New-Object System.Windows.Forms.TextBox
$search.Dock = 'Fill'
$search.Margin = New-Object System.Windows.Forms.Padding(0)
$searchRow.Controls.Add($searchLabel, 0, 0)
$searchRow.Controls.Add($search, 1, 0)
$layout.Controls.Add($searchRow, 0, 2)

$bar = New-Object System.Windows.Forms.ProgressBar
$bar.Maximum = 1000
$bar.Dock = 'Fill'
$bar.Height = 18
$bar.Margin = New-Object System.Windows.Forms.Padding(0, 0, 0, 10)
$layout.Controls.Add($bar, 0, 3)

$list = New-Object System.Windows.Forms.ListView
$list.View = 'Details'
$list.FullRowSelect = $true
$list.HeaderStyle = 'Nonclickable'
$list.Dock = 'Fill'
$list.ShowItemToolTips = $true
$list.SmallImageList = $images
$list.Margin = New-Object System.Windows.Forms.Padding(0)
$layout.Controls.Add($list, 0, 4)

$details = New-Object System.Windows.Forms.TextBox
$details.Multiline = $true
$details.ReadOnly = $true
$details.ScrollBars = 'Vertical'
$details.Dock = 'Fill'
$details.Height = [int](140 * $scale)
$details.Font = New-Object System.Drawing.Font('Consolas', 8.5)
$details.Visible = $false
$details.Margin = New-Object System.Windows.Forms.Padding(0, 8, 0, 0)
$layout.Controls.Add($details, 0, 5)

$bottom = New-Object System.Windows.Forms.TableLayoutPanel
$bottom.Dock = 'Fill'
$bottom.AutoSize = $true
$bottom.ColumnCount = 2
$bottom.Margin = New-Object System.Windows.Forms.Padding(0, 12, 0, 0)
[void]$bottom.ColumnStyles.Add((New-Object System.Windows.Forms.ColumnStyle([System.Windows.Forms.SizeType]::Percent, 100)))
[void]$bottom.ColumnStyles.Add((New-Object System.Windows.Forms.ColumnStyle([System.Windows.Forms.SizeType]::AutoSize)))
$layout.Controls.Add($bottom, 0, 6)

$left = New-Object System.Windows.Forms.FlowLayoutPanel
$left.AutoSize = $true
$left.WrapContents = $false
$left.Margin = New-Object System.Windows.Forms.Padding(0)
$bottom.Controls.Add($left, 0, 0)

$right = New-Object System.Windows.Forms.FlowLayoutPanel
$right.AutoSize = $true
$right.WrapContents = $false
$right.Margin = New-Object System.Windows.Forms.Padding(0)
$bottom.Controls.Add($right, 1, 0)

function New-Button([string]$text) {
  $b = New-Object System.Windows.Forms.Button
  $b.Text = $text
  $b.AutoSize = $true
  $b.MinimumSize = New-Object System.Drawing.Size(92, 30)
  $b.Padding = New-Object System.Windows.Forms.Padding(8, 0, 8, 0)
  $b.Margin = New-Object System.Windows.Forms.Padding(6, 0, 0, 0)
  return $b
}

function New-Link([string]$text) {
  $l = New-Object System.Windows.Forms.LinkLabel
  $l.Text = $text
  $l.AutoSize = $true
  $l.LinkColor = $accent
  $l.ActiveLinkColor = $accent
  $l.Margin = New-Object System.Windows.Forms.Padding(0, 7, 14, 0)
  return $l
}

$retry = New-Button 'Retry failed'
$retry.Visible = $false
$cancel = New-Button 'Cancel'
$primary = New-Button 'Install'
$primary.BackColor = $accent
$primary.ForeColor = [System.Drawing.Color]::White
$primary.FlatStyle = 'Flat'
$primary.FlatAppearance.BorderColor = $accent
$right.Controls.Add($retry)
$right.Controls.Add($cancel)
$right.Controls.Add($primary)

$script:mode = 'starting'
$script:finished = $false
$script:answered = $false
$script:items = @{}
$script:notes = @{}
$script:sizes = @{}
$script:checked = @{}
$script:allItems = @()
$script:filtering = $false
$script:logs = $null

function Format-Size([double]$bytes) {
  $mb = $bytes / 1MB
  if ($mb -lt 10) { return ('{0:0.0} MB' -f $mb) }
  if ($mb -lt 1000) { return ('{0:0} MB' -f $mb) }
  return ('{0:0.0} GB' -f ($mb / 1024))
}

function Resize-Columns {
  if ($list.Columns.Count -eq 0) { return }
  $w = $list.ClientSize.Width - 4
  if ($script:mode -eq 'pick') {
    $widths = 0.27, 0.11, 0.10, 0.15
    for ($i = 0; $i -lt 4; $i++) { $list.Columns[$i].Width = [int]($w * $widths[$i]) }
    $list.Columns[4].Width = $w - ($list.Columns[0].Width + $list.Columns[1].Width + $list.Columns[2].Width + $list.Columns[3].Width)
  } else {
    $list.Columns[0].Width = [int]($w * 0.42); $list.Columns[1].Width = $w - $list.Columns[0].Width
  }
}
$list.Add_Resize({ Resize-Columns })

# --- taskbar: progress on inicia's button, and a flash when it needs looking at -----------------
$script:taskbar = $false
function Start-Taskbar {
  try {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
[ComImport, Guid("ea1afb91-9e28-4b86-90e9-9e9f8a5eefaf"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface ITaskbarList3 {
  void HrInit(); void AddTab(IntPtr h); void DeleteTab(IntPtr h); void ActivateTab(IntPtr h); void SetActiveAlt(IntPtr h);
  void MarkFullscreenWindow(IntPtr h, [MarshalAs(UnmanagedType.Bool)] bool full);
  void SetProgressValue(IntPtr h, ulong done, ulong total);
  void SetProgressState(IntPtr h, int state);
}
[ComImport, Guid("56fdf344-fd6d-11d0-958a-006097c9a090"), ClassInterface(ClassInterfaceType.None)]
class CTaskbarList { }
public static class IniciaTaskbar {
  static ITaskbarList3 list;
  [StructLayout(LayoutKind.Sequential)]
  struct FLASHWINFO { public uint cbSize; public IntPtr hwnd; public uint dwFlags; public uint uCount; public uint dwTimeout; }
  [DllImport("user32.dll")] static extern bool FlashWindowEx(ref FLASHWINFO info);
  public static void Init() { list = (ITaskbarList3)new CTaskbarList(); list.HrInit(); }
  // states: 0 none, 2 normal (green), 4 error (red)
  public static void Progress(IntPtr h, int state, int value) {
    if (list == null) return;
    list.SetProgressState(h, state);
    if (state == 2 || state == 4) list.SetProgressValue(h, (ulong)value, 1000);
  }
  // flashes until the window comes to the front
  public static void Flash(IntPtr h) {
    var f = new FLASHWINFO(); f.cbSize = (uint)Marshal.SizeOf(f); f.hwnd = h; f.dwFlags = 3 | 12; f.uCount = 3;
    FlashWindowEx(ref f);
  }
}
'@
    [IniciaTaskbar]::Init()
    $script:taskbar = $true
  } catch { }
}
function Set-Taskbar([int]$state, [int]$value) {
  if ($script:taskbar) { try { [IniciaTaskbar]::Progress($form.Handle, $state, $value) } catch { } }
}
function Get-Attention {
  if ($script:taskbar -and [System.Windows.Forms.Form]::ActiveForm -ne $form) { try { [IniciaTaskbar]::Flash($form.Handle) } catch { } }
}

# --- the picker ---------------------------------------------------------------

function Update-Picker {
  $ids = @($script:checked.Keys)
  $n = $ids.Count
  $total = 0
  foreach ($id in $ids) { if ($script:sizes[$id]) { $total += $script:sizes[$id] } }
  $primary.Enabled = $n -gt 0
  $what = if ($n -eq 1) { '1 app' } else { "$n apps" }
  $primary.Text = if ($n -eq 0) { 'Install' } elseif ($total -gt 0) { "Install $what · $(Format-Size $total)" } else { "Install $what" }
  $notes = @($ids | Where-Object { $script:notes[$_] } | ForEach-Object { $script:notes[$_] })
  $status.Text = if ($notes.Count) { $notes -join ' ' } else { 'Everything installs silently, each download checked against its publisher''s hash or signature first. Apps marked with a warning sign have something to know: select one to read it.' }
}

function Filter-Picker {
  $text = $search.Text.Trim()
  $script:filtering = $true
  $list.BeginUpdate()
  $list.Items.Clear()
  foreach ($item in $script:allItems) {
    if (-not $text -or $item.Text -like "*$text*" -or $item.SubItems[4].Text -like "*$text*") {
      $item.Checked = [bool]$script:checked[$item.Name]
      [void]$list.Items.Add($item)
      # an item put back in the list has lost its group: its category is in its Tag
      $item.Group = $script:groups[[string]$item.Tag]
    }
  }
  $list.EndUpdate()
  $script:filtering = $false
}

function Show-Picker($m) {
  $script:mode = 'pick'
  $header.Text = 'Pick the apps to install'
  $bar.Visible = $false
  $searchRow.Visible = $true
  $list.CheckBoxes = $true
  $list.ShowGroups = $true
  foreach ($c in 'App', 'Version', 'Size', 'On this PC', 'About') { [void]$list.Columns.Add($c) }
  $groups = @{}
  foreach ($cat in ($m.apps | ForEach-Object { $_.category } | Sort-Object -Unique)) {
    $groups[$cat] = New-Object System.Windows.Forms.ListViewGroup($cat, $cat)
    [void]$list.Groups.Add($groups[$cat])
  }
  $script:groups = $groups
  foreach ($a in ($m.apps | Sort-Object name)) {
    $item = New-Object System.Windows.Forms.ListViewItem($a.name)
    $item.Name = $a.id
    $item.Tag = [string]$a.category
    $item.Group = $groups[$a.category]
    [void]$item.SubItems.Add($(if ($a.version) { $a.version } else { 'latest' }))
    [void]$item.SubItems.Add($(if ($a.size) { Format-Size $a.size } else { '' }))
    [void]$item.SubItems.Add([string]$a.installed)
    $about = $a.description
    if ($a.suite) { $about = "$about · Part of the $($a.suite) Suite" }
    [void]$item.SubItems.Add($about)
    $item.ToolTipText = $about
    if ($a.size) { $script:sizes[$a.id] = [double]$a.size }
    if ($a.note) {
      $script:notes[$a.id] = "$($a.name): $($a.note)"
      $item.ImageKey = 'note'
      $item.ToolTipText = "$($a.note)`n$about"
    } else { $item.ImageKey = 'none' }
    if ($a.installed -eq 'Installed') { $item.ForeColor = $muted }
    $script:allItems += $item
    [void]$list.Items.Add($item)
  }
  $all = New-Link 'Select all'
  $all.Add_LinkClicked({ foreach ($i in $list.Items) { $i.Checked = $true } })
  $none = New-Link 'Clear'
  $none.Add_LinkClicked({ $script:checked.Clear(); foreach ($i in $list.Items) { $i.Checked = $false }; Update-Picker })
  $left.Controls.Add($all); $left.Controls.Add($none)
  foreach ($s in @($m.suites)) {
    if (-not $s) { continue }
    $link = New-Link "All $([string]$s.name) apps"
    $link.Tag = @($s.ids)
    $link.Add_LinkClicked({ param($sender) foreach ($i in $list.Items) { if ($sender.Tag -contains $i.Name) { $i.Checked = $true } } })
    $left.Controls.Add($link)
  }
  $list.Add_ItemChecked({ param($sender, $e)
    if ($script:filtering) { return }
    if ($e.Item.Checked) { $script:checked[$e.Item.Name] = $true } else { $script:checked.Remove($e.Item.Name) }
    Update-Picker })
  $search.Add_TextChanged({ Filter-Picker })
  Resize-Columns
  Update-Picker
  $search.Focus()
}

# --- the progress view ----------------------------------------------------------

function Show-Plan($m) {
  $script:mode = 'progress'
  $header.Text = $m.title
  $status.Text = 'Getting ready…'
  $searchRow.Visible = $false
  $bar.Visible = $true
  $list.Items.Clear(); $list.Columns.Clear(); $list.Groups.Clear()
  $list.CheckBoxes = $false
  $list.ShowGroups = $false
  [void]$list.Columns.Add('App'); [void]$list.Columns.Add('Status')
  $left.Controls.Clear()
  foreach ($a in $m.apps) {
    $item = New-Object System.Windows.Forms.ListViewItem($a.name)
    $item.Name = $a.id
    [void]$item.SubItems.Add($a.status)
    $script:items[$a.id] = $item
    Set-RowState $item ([string]$a.state)
    [void]$list.Items.Add($item)
  }
  $toggle = New-Link 'Show details'
  $toggle.Add_LinkClicked({ param($sender)
    $details.Visible = -not $details.Visible
    $sender.Text = if ($details.Visible) { 'Hide details' } else { 'Show details' } })
  $copy = New-Link 'Copy details'
  $copy.Add_LinkClicked({ if ($details.Text) { [System.Windows.Forms.Clipboard]::SetText($details.Text) } })
  $script:logLink = New-Link 'Open the log folder'
  $script:logLink.Visible = $false
  $script:logLink.Add_LinkClicked({ if ($script:logs) { Start-Process explorer.exe -ArgumentList "`"$script:logs`"" } })
  $left.Controls.Add($toggle); $left.Controls.Add($copy); $left.Controls.Add($script:logLink)
  $primary.Visible = $false
  $retry.Visible = $false
  $cancel.Visible = $true
  $cancel.Text = 'Cancel'
  $cancel.Enabled = $true
  Set-Taskbar 2 0
  Resize-Columns
}

function Set-RowState($item, [string]$state) {
  switch ($state) {
    'waiting' { $item.ImageKey = 'waiting'; $item.ForeColor = $muted }
    'working' { $item.ImageKey = 'working'; $item.ForeColor = [System.Drawing.Color]::Black; $item.EnsureVisible() }
    'ok' { $item.ImageKey = 'ok'; $item.ForeColor = $accent }
    'skip' { $item.ImageKey = 'skip'; $item.ForeColor = $muted }
    'fail' { $item.ImageKey = 'fail'; $item.ForeColor = $failColor }
  }
}

function Set-AppState($m) {
  $item = $script:items[$m.id]
  if (-not $item) { return }
  $item.SubItems[1].Text = $m.status
  $item.ToolTipText = $m.status
  Set-RowState $item ([string]$m.state)
}

function Finish($m) {
  $script:finished = $true
  $header.Text = $m.title
  $status.Text = $m.text
  $bar.Value = 1000
  $cancel.Visible = $false
  $primary.Text = 'Close'
  $primary.Visible = $true
  $primary.Enabled = $true
  $retry.Visible = -not $m.ok
  $script:logs = $m.logs
  if ($script:logLink) { $script:logLink.Visible = [bool]$m.logs }
  if ($m.ok) { Set-TaskBar 0 0 } else { Set-Taskbar 4 1000 }
  $primary.Focus()
  Get-Attention
}

function Start-Retry($m) {
  $script:finished = $false
  $header.Text = $m.title
  $status.Text = 'Getting ready…'
  $bar.Value = 0
  foreach ($id in $m.ids) {
    $item = $script:items[$id]
    if ($item) { $item.SubItems[1].Text = 'Waiting'; Set-RowState $item 'waiting' }
  }
  $retry.Visible = $false
  $primary.Visible = $false
  $cancel.Visible = $true
  $cancel.Enabled = $true
  Set-Taskbar 2 0
}

function Handle($m) {
  switch ($m.t) {
    'pick' { Show-Picker $m }
    'plan' { Show-Plan $m }
    'app' { Set-AppState $m }
    'overall' {
      $v = [Math]::Max(0, [Math]::Min(1000, [int]$m.value))
      $bar.Value = $v
      $status.Text = $m.text
      Set-Taskbar 2 $v
    }
    'log' { $details.AppendText('[' + (Get-Date -Format 'HH:mm:ss') + '] ' + $m.text + "`r`n") }
    'done' { Finish $m }
    'retry' { Start-Retry $m }
    'eof' {
      if ($script:mode -eq 'progress' -and -not $script:finished) {
        Finish ([pscustomobject]@{ title = 'inicia stopped'; text = 'It ended before finishing. Show details for what it did.'; ok = $true; logs = $null })
      } elseif ($script:mode -ne 'progress') { $form.Close() }
    }
  }
}

$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 80
$timer.Add_Tick({
  $line = $null
  while ($queue.TryDequeue([ref]$line)) {
    try { Handle ($line | ConvertFrom-Json) } catch { $details.AppendText("(window: $_)`r`n") }
  }
})

$primary.Add_Click({
  if ($script:mode -eq 'pick') {
    $script:answered = $true
    Send ('install ' + (@($script:checked.Keys) -join ' '))
    $form.Close()
  } else {
    $form.Close()
  }
})

$retry.Add_Click({ $retry.Visible = $false; Send 'retry' })

function Ask-Cancel {
  $answer = [System.Windows.Forms.MessageBox]::Show($form,
    'Stop after the app being installed now? Apps already installed stay installed.',
    'inicia', 'YesNo', 'Question')
  if ($answer -eq 'Yes') {
    Send 'cancel'
    $cancel.Enabled = $false
    $status.Text = 'Stopping after the current app…'
  }
}

$cancel.Add_Click({
  if ($script:mode -eq 'pick') { $script:answered = $true; Send 'quit'; $form.Close() }
  elseif (-not $script:finished) { Ask-Cancel }
})

$form.Add_FormClosing({ param($sender, $e)
  if ($script:mode -eq 'progress' -and -not $script:finished) {
    $e.Cancel = $true
    if ($cancel.Enabled) { Ask-Cancel }
  } elseif ($script:mode -eq 'pick' -and -not $script:answered) {
    Send 'quit'
  }
})

# inicia runs the window from a process started in the background, which Windows doesn't let take
# the focus: being topmost for a moment brings it to the front anyway. The taskbar shim compiles
# after the window shows, so it never delays it.
$form.Add_Shown({
  $form.TopMost = $true; $form.Activate(); $form.TopMost = $false
  $form.BeginInvoke([Action]{ Start-Taskbar; if ($script:mode -eq 'progress' -and -not $script:finished) { Set-Taskbar 2 $bar.Value } }) | Out-Null
})
$timer.Start()
Send 'ready'
[System.Windows.Forms.Application]::Run($form)

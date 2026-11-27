# inicia's window. inicia.exe starts this, writes events to its stdin, one JSON object per line,
# and reads its answers from stdout, one per line: "ready", "install <ids>", "cancel", "quit".
#   {"t":"pick", "apps":[...], "suites":[...]}   the picker
#   {"t":"plan", "title":..., "apps":[{"id","name","status"}]}   the progress view
#   {"t":"app", "id", "status", "state":"working|ok|skip|fail"}
#   {"t":"overall", "value":0-1000, "text"}
#   {"t":"log", "text"}
#   {"t":"done", "title", "text", "ok"}
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
$font = New-Object System.Drawing.Font('Segoe UI', 9)
$headerFont = New-Object System.Drawing.Font('Segoe UI Semibold', 13)

# the window's icon: the site's wordmark dot on a white tile
$bmp = New-Object System.Drawing.Bitmap 32, 32
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = 'AntiAlias'
$g.Clear([System.Drawing.Color]::Transparent)
$g.FillEllipse((New-Object System.Drawing.SolidBrush $accent), 2, 2, 28, 28)
$g.DrawString('i', (New-Object System.Drawing.Font('Segoe UI', 15, [System.Drawing.FontStyle]::Bold)), [System.Drawing.Brushes]::White, 9, 1)
$g.Dispose()

$form = New-Object System.Windows.Forms.Form
$form.Text = 'inicia'
$form.Icon = [System.Drawing.Icon]::FromHandle($bmp.GetHicon())
$form.Font = $font
$form.AutoScaleMode = 'Dpi'
$form.ClientSize = New-Object System.Drawing.Size(640, 480)
$form.MinimumSize = New-Object System.Drawing.Size(520, 400)
$form.StartPosition = 'CenterScreen'
$form.BackColor = [System.Drawing.Color]::White

$layout = New-Object System.Windows.Forms.TableLayoutPanel
$layout.Dock = 'Fill'
$layout.Padding = New-Object System.Windows.Forms.Padding(18, 14, 18, 14)
$layout.ColumnCount = 1
$layout.RowCount = 6
[void]$layout.ColumnStyles.Add((New-Object System.Windows.Forms.ColumnStyle([System.Windows.Forms.SizeType]::Percent, 100)))
foreach ($i in 0..5) { [void]$layout.RowStyles.Add((New-Object System.Windows.Forms.RowStyle([System.Windows.Forms.SizeType]::AutoSize))) }
$layout.RowStyles[3] = New-Object System.Windows.Forms.RowStyle([System.Windows.Forms.SizeType]::Percent, 100)
$form.Controls.Add($layout)

$header = New-Object System.Windows.Forms.Label
$header.Font = $headerFont
$header.AutoSize = $true
$header.Margin = New-Object System.Windows.Forms.Padding(0, 0, 0, 2)
$layout.Controls.Add($header, 0, 0)

$status = New-Object System.Windows.Forms.Label
$status.ForeColor = $muted
$status.AutoSize = $true
$status.MaximumSize = New-Object System.Drawing.Size(600, 0)
$status.Margin = New-Object System.Windows.Forms.Padding(0, 0, 0, 8)
$layout.Controls.Add($status, 0, 1)

$bar = New-Object System.Windows.Forms.ProgressBar
$bar.Maximum = 1000
$bar.Dock = 'Fill'
$bar.Height = 18
$bar.Margin = New-Object System.Windows.Forms.Padding(0, 0, 0, 10)
$layout.Controls.Add($bar, 0, 2)

$list = New-Object System.Windows.Forms.ListView
$list.View = 'Details'
$list.FullRowSelect = $true
$list.HeaderStyle = 'Nonclickable'
$list.Dock = 'Fill'
$list.ShowItemToolTips = $true
$list.Margin = New-Object System.Windows.Forms.Padding(0)
$layout.Controls.Add($list, 0, 3)

$details = New-Object System.Windows.Forms.TextBox
$details.Multiline = $true
$details.ReadOnly = $true
$details.ScrollBars = 'Vertical'
$details.Dock = 'Fill'
$details.Height = 130
$details.Font = New-Object System.Drawing.Font('Consolas', 8.5)
$details.Visible = $false
$details.Margin = New-Object System.Windows.Forms.Padding(0, 8, 0, 0)
$layout.Controls.Add($details, 0, 4)

$bottom = New-Object System.Windows.Forms.TableLayoutPanel
$bottom.Dock = 'Fill'
$bottom.AutoSize = $true
$bottom.ColumnCount = 2
$bottom.Margin = New-Object System.Windows.Forms.Padding(0, 12, 0, 0)
[void]$bottom.ColumnStyles.Add((New-Object System.Windows.Forms.ColumnStyle([System.Windows.Forms.SizeType]::Percent, 100)))
[void]$bottom.ColumnStyles.Add((New-Object System.Windows.Forms.ColumnStyle([System.Windows.Forms.SizeType]::AutoSize)))
$layout.Controls.Add($bottom, 0, 5)

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

$cancel = New-Button 'Cancel'
$primary = New-Button 'Install'
$primary.BackColor = $accent
$primary.ForeColor = [System.Drawing.Color]::White
$primary.FlatStyle = 'Flat'
$primary.FlatAppearance.BorderColor = $accent
$right.Controls.Add($cancel)
$right.Controls.Add($primary)

$script:mode = 'starting'
$script:finished = $false
$script:answered = $false
$script:items = @{}
$script:notes = @{}

function Resize-Columns {
  if ($list.Columns.Count -eq 0) { return }
  $w = $list.ClientSize.Width - 4
  if ($script:mode -eq 'pick') {
    $list.Columns[0].Width = [int]($w * 0.34); $list.Columns[1].Width = [int]($w * 0.14)
    $list.Columns[2].Width = $w - $list.Columns[0].Width - $list.Columns[1].Width
  } else {
    $list.Columns[0].Width = [int]($w * 0.42); $list.Columns[1].Width = $w - $list.Columns[0].Width
  }
}
$list.Add_Resize({ Resize-Columns })

# --- the picker ---------------------------------------------------------------

function Update-Picker {
  $checked = @($list.CheckedItems | ForEach-Object { $_.Name })
  $n = $checked.Count
  $primary.Enabled = $n -gt 0
  $primary.Text = if ($n -eq 0) { 'Install' } elseif ($n -eq 1) { 'Install 1 app' } else { "Install $n apps" }
  $notes = @($checked | Where-Object { $script:notes[$_] } | ForEach-Object { $script:notes[$_] })
  $status.Text = if ($notes.Count) { $notes -join ' ' } else { 'Everything installs silently, each download checked against its publisher''s hash or signature first.' }
}

function Show-Picker($m) {
  $script:mode = 'pick'
  $header.Text = 'Pick the apps to install'
  $bar.Visible = $false
  $list.CheckBoxes = $true
  $list.ShowGroups = $true
  [void]$list.Columns.Add('App'); [void]$list.Columns.Add('Version'); [void]$list.Columns.Add('About')
  $groups = @{}
  foreach ($cat in ($m.apps | ForEach-Object { $_.category } | Sort-Object -Unique)) {
    $groups[$cat] = New-Object System.Windows.Forms.ListViewGroup($cat, $cat)
    [void]$list.Groups.Add($groups[$cat])
  }
  foreach ($a in ($m.apps | Sort-Object name)) {
    $item = New-Object System.Windows.Forms.ListViewItem($a.name)
    $item.Name = $a.id
    $item.Group = $groups[$a.category]
    [void]$item.SubItems.Add($(if ($a.version) { $a.version } else { 'latest' }))
    $about = $a.description
    if ($a.suite) { $about = "$about · Part of the $($a.suite) Suite" }
    [void]$item.SubItems.Add($about)
    $item.ToolTipText = $about
    if ($a.note) { $script:notes[$a.id] = "$($a.name): $($a.note)"; $item.ForeColor = [System.Drawing.Color]::FromArgb(138, 90, 0) }
    [void]$list.Items.Add($item)
  }
  $all = New-Link 'Select all'
  $all.Add_LinkClicked({ foreach ($i in $list.Items) { $i.Checked = $true } })
  $none = New-Link 'Clear'
  $none.Add_LinkClicked({ foreach ($i in $list.Items) { $i.Checked = $false } })
  $left.Controls.Add($all); $left.Controls.Add($none)
  foreach ($s in @($m.suites)) {
    if (-not $s) { continue }
    $suiteName = [string]$s.name
    $ids = @($s.ids)
    $link = New-Link "All $suiteName apps"
    $link.Tag = $ids
    $link.Add_LinkClicked({ param($sender) foreach ($i in $list.Items) { if ($sender.Tag -contains $i.Name) { $i.Checked = $true } } })
    $left.Controls.Add($link)
  }
  $list.Add_ItemChecked({ Update-Picker })
  Resize-Columns
  Update-Picker
}

# --- the progress view ----------------------------------------------------------

function Show-Plan($m) {
  $script:mode = 'progress'
  $header.Text = $m.title
  $status.Text = 'Getting ready…'
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
    $item.ForeColor = $muted
    $script:items[$a.id] = $item
    [void]$list.Items.Add($item)
  }
  $toggle = New-Link 'Show details'
  $toggle.Add_LinkClicked({ param($sender)
    $details.Visible = -not $details.Visible
    $sender.Text = if ($details.Visible) { 'Hide details' } else { 'Show details' } })
  $left.Controls.Add($toggle)
  $primary.Visible = $false
  $cancel.Text = 'Cancel'
  $cancel.Enabled = $true
  Resize-Columns
}

function Set-AppState($m) {
  $item = $script:items[$m.id]
  if (-not $item) { return }
  $item.SubItems[1].Text = $m.status
  $item.ToolTipText = $m.status
  switch ($m.state) {
    'working' { $item.ForeColor = [System.Drawing.Color]::Black; $item.EnsureVisible() }
    'ok' { $item.ForeColor = $accent }
    'skip' { $item.ForeColor = $muted }
    'fail' { $item.ForeColor = $failColor }
  }
}

function Finish($title, $text) {
  $script:finished = $true
  $header.Text = $title
  $status.Text = $text
  $bar.Value = 1000
  $cancel.Visible = $false
  $primary.Text = 'Close'
  $primary.Visible = $true
  $primary.Enabled = $true
  $primary.Focus()
}

function Handle($m) {
  switch ($m.t) {
    'pick' { Show-Picker $m }
    'plan' { Show-Plan $m }
    'app' { Set-AppState $m }
    'overall' { $bar.Value = [Math]::Max(0, [Math]::Min(1000, [int]$m.value)); $status.Text = $m.text }
    'log' { $details.AppendText($m.text + "`r`n") }
    'done' { Finish $m.title $m.text }
    'eof' {
      if ($script:mode -eq 'progress' -and -not $script:finished) {
        Finish 'inicia stopped' 'It ended before finishing. Show details for what it did.'
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
    $ids = @($list.CheckedItems | ForEach-Object { $_.Name })
    $script:answered = $true
    Send ('install ' + ($ids -join ' '))
    $form.Close()
  } else {
    $form.Close()
  }
})

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
# the focus: being topmost for a moment brings it to the front anyway
$form.Add_Shown({ $form.TopMost = $true; $form.Activate(); $form.TopMost = $false })
$timer.Start()
Send 'ready'
[System.Windows.Forms.Application]::Run($form)

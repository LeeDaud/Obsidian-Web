param(
    [ValidateSet('Initialize', 'Build')][string]$Action = 'Initialize',
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'This helper requires Windows DPAPI.' }
if (-not $JavaHome) { throw 'Set JAVA_HOME to the existing JDK 21 directory.' }
$keytool = Join-Path $JavaHome 'bin/keytool.exe'
if (-not (Test-Path -LiteralPath $keytool -PathType Leaf)) { throw 'JAVA_HOME does not contain keytool.exe.' }
$directory = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Echo/signing'
$store = Join-Path $directory 'echo-release.p12'
$passwordFile = Join-Path $directory 'password.dpapi'
$certificate = Join-Path $directory 'certificate.cer'
$alias = 'echo-release'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if ([IO.Path]::GetFullPath($directory).StartsWith($repository + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The signing directory must be outside the source repository.'
}
$ancestor = [IO.DirectoryInfo]::new($directory)
while ($null -ne $ancestor) {
    if ($ancestor.Exists -and ($ancestor.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Signing paths must not use links or junctions.' }
    $ancestor = $ancestor.Parent
}

$names = @('ECHO_RELEASE_STORE_FILE', 'ECHO_RELEASE_STORE_PASSWORD', 'ECHO_RELEASE_KEY_ALIAS', 'ECHO_RELEASE_KEY_PASSWORD')
$oldValues = @{}
foreach ($name in $names) { $oldValues[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$password = $null
try {
    if ($Action -eq 'Initialize') {
        if ((Test-Path -LiteralPath $store) -or (Test-Path -LiteralPath $passwordFile) -or (Test-Path -LiteralPath $certificate)) {
            throw 'Signing files already exist. They will not be overwritten or rotated.'
        }
        if ((Test-Path -LiteralPath $directory) -and [IO.Directory]::GetFileSystemEntries($directory).Length -gt 0) {
            throw 'The signing directory is not empty. Existing files and permissions will not be changed.'
        }
        if (-not [Environment]::UserInteractive) { throw 'Open this helper in your interactive Windows session.' }
        Add-Type -AssemblyName System.Windows.Forms
        Add-Type -AssemblyName System.Drawing
        $form = New-Object Windows.Forms.Form
        $form.Text = 'Echo Release Signing'
        $form.ClientSize = New-Object Drawing.Size(520, 285)
        $form.StartPosition = 'CenterScreen'
        $form.FormBorderStyle = 'FixedDialog'
        $form.MaximizeBox = $false
        $form.MinimizeBox = $false
        $form.Font = New-Object Drawing.Font('Segoe UI', 10)
        $description = New-Object Windows.Forms.Label
        $description.SetBounds(16, 12, 488, 60)
        $description.Text = "Create a new release key outside the repository.`r`nBack up the keystore AND password separately.`r`nThis key cannot replace the installed debug-signed app."
        $form.Controls.Add($description)
        $label = New-Object Windows.Forms.Label
        $label.SetBounds(16, 85, 125, 25); $label.Text = 'Password (16+)'
        $form.Controls.Add($label)
        $passwordBox = New-Object Windows.Forms.TextBox
        $passwordBox.SetBounds(145, 82, 355, 28); $passwordBox.UseSystemPasswordChar = $true; $passwordBox.MaxLength = 128
        $form.Controls.Add($passwordBox)
        $confirmLabel = New-Object Windows.Forms.Label
        $confirmLabel.SetBounds(16, 124, 125, 25); $confirmLabel.Text = 'Confirm password'
        $form.Controls.Add($confirmLabel)
        $confirmation = New-Object Windows.Forms.TextBox
        $confirmation.SetBounds(145, 121, 355, 28); $confirmation.UseSystemPasswordChar = $true; $confirmation.MaxLength = 128
        $form.Controls.Add($confirmation)
        $backup = New-Object Windows.Forms.CheckBox
        $backup.SetBounds(16, 162, 488, 30)
        $backup.Text = 'I will keep an independent keystore and password backup.'
        $form.Controls.Add($backup)
        $create = New-Object Windows.Forms.Button
        $create.SetBounds(275, 220, 108, 34); $create.Text = 'Create key'
        $create.Add_Click({
            if ($passwordBox.Text.Length -lt 16 -or [string]::IsNullOrWhiteSpace($passwordBox.Text) -or $passwordBox.Text -match '[\x00-\x1f]' -or $passwordBox.Text -cne $confirmation.Text -or -not $backup.Checked) {
                [void][Windows.Forms.MessageBox]::Show('Use matching passwords of at least 16 characters and confirm the backup plan.', 'Echo')
                return
            }
            $form.DialogResult = [Windows.Forms.DialogResult]::OK
        })
        $form.Controls.Add($create)
        $cancel = New-Object Windows.Forms.Button
        $cancel.SetBounds(393, 220, 108, 34); $cancel.Text = 'Cancel'
        $cancel.DialogResult = [Windows.Forms.DialogResult]::Cancel
        $form.Controls.Add($cancel)
        $form.AcceptButton = $create; $form.CancelButton = $cancel
        try {
            if ($form.ShowDialog() -ne [Windows.Forms.DialogResult]::OK) { Write-Output 'Cancelled. No signing files created.'; return }
            $password = $passwordBox.Text
        } finally { $passwordBox.Clear(); $confirmation.Clear(); $form.Dispose() }
        foreach ($path in @($store, $passwordFile, $certificate)) { if (Test-Path -LiteralPath $path) { throw 'Signing files appeared during setup. No overwrite is allowed.' } }
        if ((Test-Path -LiteralPath $directory) -and [IO.Directory]::GetFileSystemEntries($directory).Length -gt 0) { throw 'Signing directory changed during setup. Existing files are retained.' }
        $protected = ConvertFrom-SecureString (ConvertTo-SecureString $password -AsPlainText -Force)
        [void][IO.Directory]::CreateDirectory($directory)
        $acl = New-Object Security.AccessControl.DirectorySecurity
        $acl.SetAccessRuleProtection($true, $false)
        $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
        $rule = New-Object Security.AccessControl.FileSystemAccessRule($sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
        $acl.AddAccessRule($rule)
        Set-Acl -LiteralPath $directory -AclObject $acl
        $env:ECHO_RELEASE_STORE_PASSWORD = $password
        $env:ECHO_RELEASE_KEY_PASSWORD = $password
        $pendingStore = Join-Path $directory (([guid]::NewGuid().ToString()) + '.pending.p12')
        & $keytool -genkeypair -alias $alias -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 10000 -dname 'CN=Echo Android,O=Echo,C=CN' -storetype PKCS12 -keystore $pendingStore -storepass:env ECHO_RELEASE_STORE_PASSWORD -keypass:env ECHO_RELEASE_KEY_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw 'Key creation failed. Existing files are retained for inspection; do not blindly retry.' }
        [IO.File]::Move($pendingStore, $store)
        $output = [IO.File]::Open($passwordFile, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        try { $bytes = [Text.Encoding]::UTF8.GetBytes($protected); $output.Write($bytes, 0, $bytes.Length); $output.Flush($true) }
        finally { $output.Dispose() }
        $pendingCertificate = Join-Path $directory (([guid]::NewGuid().ToString()) + '.pending.cer')
        & $keytool -exportcert -alias $alias -keystore $store -storetype PKCS12 -storepass:env ECHO_RELEASE_STORE_PASSWORD -file $pendingCertificate
        if ($LASTEXITCODE -ne 0) { throw 'Certificate export failed. Signing files are retained.' }
        [IO.File]::Move($pendingCertificate, $certificate)
        $fingerprint = (Get-FileHash -LiteralPath $certificate -Algorithm SHA256).Hash
        Write-Output "Signing key created: $store"
        Write-Output "Public certificate SHA256: $fingerprint"
        Write-Output 'Independent backup is still required. DPAPI is tied to this Windows account.'
    } else {
        foreach ($path in @($store, $passwordFile, $certificate)) { if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'Signing setup is incomplete. Initialize it first.' } }
        $secure = ConvertTo-SecureString ([IO.File]::ReadAllText($passwordFile))
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        try { $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $secure.Dispose() }
        $env:ECHO_RELEASE_STORE_FILE = $store
        $env:ECHO_RELEASE_STORE_PASSWORD = $password
        $env:ECHO_RELEASE_KEY_ALIAS = $alias
        $env:ECHO_RELEASE_KEY_PASSWORD = $password
        Push-Location $PSScriptRoot
        try {
            & ./gradlew.bat --offline --no-daemon --no-configuration-cache --no-build-cache testReleaseUnitTest lintRelease assembleRelease
            if ($LASTEXITCODE -ne 0) { throw 'Signed release validation failed. No publication performed.' }
        } finally { Pop-Location }
        Write-Output 'Local signed candidate built. Version and UI mode follow build.gradle; no publication, installation or data migration performed.'
    }
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $oldValues[$name], 'Process') }
    $password = $null
}

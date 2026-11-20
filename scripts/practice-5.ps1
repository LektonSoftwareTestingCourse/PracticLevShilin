param(
    [switch]$SkipStack,
    [switch]$Faults,
    [switch]$Defects,
    [ValidateRange(1, 3)][int]$Runs = 1,
    [string]$ProjectName = 'practiclevshilin2',
    [string]$Maven = ''
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
    if (!$Maven) {
        $mavenCommand = Get-Command mvn -ErrorAction SilentlyContinue
        if ($mavenCommand) { $Maven = $mavenCommand.Source }
        else {
            $ideaMaven = Get-ChildItem 'C:/Program Files/JetBrains' -Filter mvn.cmd -Recurse -ErrorAction SilentlyContinue |
                Where-Object FullName -Match 'maven3[/\\]bin' | Select-Object -First 1
            if ($ideaMaven) { $Maven = $ideaMaven.FullName }
        }
    }
    if (!$Maven) { throw 'Maven not found. Install Maven or pass -Maven <path to mvn.cmd>.' }
    if (Test-Path 'C:/Program Files/Java/jdk-21') { $env:JAVA_HOME = 'C:/Program Files/Java/jdk-21' }
    $envFile = if (Test-Path '.env') { '.env' } else { '.env.example' }
    $compose = @('compose', '--env-file', $envFile, '-p', $ProjectName, '-f', 'docker-compose.yaml')
    if (!$SkipStack) {
        $ErrorActionPreference = 'Continue'
        & docker @compose up -d --build --wait --wait-timeout 300
        $ErrorActionPreference = 'Stop'
        if ($LASTEXITCODE -ne 0) { throw 'Docker Compose startup failed.' }
    }
    if ($Faults) {
        $ErrorActionPreference = 'Continue'
        & docker @compose -f docker-compose.practice5.yaml --profile practice5 up -d --no-deps `
            practice5-switch-fault practice5-gateway-fault practice5-gateway-unavailable `
            practice5-cms-stub practice5-auth-fault practice5-auth-readonly practice5-logger-readonly
        $ErrorActionPreference = 'Stop'
        if ($LASTEXITCODE -ne 0) { throw 'Fault fixture startup failed.' }
    }
    $arguments = @('-B', '-f', 'services/pom.xml', '-Papi-tests', 'verify')
    if ($Defects) { $arguments += @('-Dgroups=defect', '-DexcludedGroups=fault') }
    elseif ($Faults) { $arguments += '-DexcludedGroups=defect' }
    $evidence = Join-Path $root ('services/api-tests/target/evidence/' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $evidence -Force | Out-Null
    for ($run = 1; $run -le $Runs; $run++) {
        $destination = Join-Path $evidence ('run-' + $run)
        $ErrorActionPreference = 'Continue'
        & $Maven @arguments "-Dpractice5.reportsDirectory=$destination"
        $result = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        if ($result -ne 0) { throw "Run $run failed. Reports: $destination" }
    }
    Write-Host "Completed $Runs run(s). Reports: $evidence"
} finally {
    Pop-Location
}

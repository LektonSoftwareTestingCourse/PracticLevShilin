param(
    [string]$Maven = 'mvn',
    [string]$ProjectName = 'practiclevshilin2'
)
# Work only on generated copies, never mutate the student's source or the running main Logger.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$work = Join-Path $root ('services/api-tests/target/mutations/' + [guid]::NewGuid().ToString('N'))
$utf8 = New-Object System.Text.UTF8Encoding($false)
function Run-Native([string]$File, [string[]]$Arguments, [string]$Log) {
    $ErrorActionPreference = 'Continue'
    & $File @Arguments > $Log 2>&1
    return $LASTEXITCODE
}
function Replace-Once([string]$Path, [string]$Before, [string]$After) {
    $content = [IO.File]::ReadAllText($Path)
    if (!$content.Contains($Before)) { throw "Mutation anchor missing: $Path" }
    [IO.File]::WriteAllText($Path, $content.Replace($Before, $After), $utf8)
}
function Assert-Killed([int]$Code, [string]$Report, [string]$Name) {
    if ($Code -eq 0) { throw "Mutation survived: $Name" }
    if (!(Test-Path $Report)) { throw "Missing JUnit result for $Name; build failure is not a killed mutant." }
    [xml]$xml = Get-Content -Raw $Report
    if ([int]$xml.testsuite.failures -lt 1 -or [int]$xml.testsuite.errors -ne 0) {
        throw "Expected assertion failure with no infrastructure errors: $Name"
    }
    Write-Host "KILLED: $Name ($($xml.testsuite.failures) assertion failures)"
}
Push-Location $root
try {
    if (Test-Path 'C:/Program Files/Java/jdk-21') { $env:JAVA_HOME = 'C:/Program Files/Java/jdk-21' }
    New-Item -ItemType Directory -Force -Path "$work/services/api-tests", "$work/services/common", `
        "$work/services/transaction-logger" | Out-Null
    Copy-Item services/pom.xml, services/checkstyle.xml "$work/services" -Force
    Copy-Item services/api-tests/pom.xml "$work/services/api-tests" -Force
    Copy-Item services/api-tests/src "$work/services/api-tests" -Recurse -Force
    Copy-Item services/common/pom.xml "$work/services/common" -Force
    Copy-Item services/common/src "$work/services/common" -Recurse -Force
    Copy-Item services/transaction-logger/pom.xml, services/transaction-logger/Dockerfile `
        "$work/services/transaction-logger" -Force
    Copy-Item services/transaction-logger/src "$work/services/transaction-logger" -Recurse -Force
    $base = @('-B', '-f', "$work/services/pom.xml", '-Papi-tests', 'test')
    $tests = "$work/services/api-tests/src/test/java/com/processing/api"
    Replace-Once "$tests/AuthorizationTest.java" 'equalTo("00")' 'equalTo("99")'
    $code = Run-Native $Maven ($base + '-Dtest=AuthorizationTest#approvalAndBalance') "$work/wrong-code.log"
    Assert-Killed $code "$work/services/api-tests/target/surefire-reports/TEST-com.processing.api.AuthorizationTest.xml" 'wrong-code'
    Copy-Item services/api-tests/src/test/java/com/processing/api/AuthorizationTest.java $tests -Force
    Replace-Once "$tests/GatewayValidationTest.java" 'equalTo("VALIDATION_ERROR")' 'equalTo("WRONG_ERROR")'
    $code = Run-Native $Maven ($base + '-Dtest=GatewayValidationTest#rejectedField') "$work/wrong-error.log"
    Assert-Killed $code "$work/services/api-tests/target/surefire-reports/TEST-com.processing.api.GatewayValidationTest.xml" 'wrong-error'
    Copy-Item services/api-tests/src/test/java/com/processing/api/GatewayValidationTest.java $tests -Force

    $logger = "$work/services/transaction-logger/src/main/java/com/processing/transactionlogger/service/TransactionService.java"
    Replace-Once $logger 'if (existingTransaction.isPresent()) {' 'if (false) {'
    $code = Run-Native docker @('build', '-t', 'smp-practice5-logger-mutant', '-f',
        "$work/services/transaction-logger/Dockerfile", $work) "$work/build-mutant.log"
    if ($code -ne 0) { throw "Mutant build failed: $work/build-mutant.log" }
    $override = "$work/mutant.yaml"
    [IO.File]::WriteAllText($override, "services:`n  transaction-logger:`n    image: smp-practice5-logger-mutant`n", $utf8)
    $envFile = if (Test-Path '.env') { '.env' } else { '.env.example' }
    $compose = @('compose', '--env-file', $envFile, '-p', $ProjectName, '-f', "$root/docker-compose.yaml", '-f', $override)
    $code = Run-Native docker ($compose + @('run', '--no-deps', '-d', '--name', 'practice5-logger-mutant',
        '-p', '18188:8080', 'transaction-logger')) "$work/start-mutant.log"
    if ($code -ne 0) { throw 'Failed to start isolated mutant.' }
    try {
        $deadline = [DateTime]::UtcNow.AddSeconds(120)
        do {
            try { $health = Invoke-RestMethod 'http://localhost:18188/health' -TimeoutSec 3 } catch { $health = $null }
            if ($health.status -eq 'ok') { break }
            if ([DateTime]::UtcNow -gt $deadline) { throw 'Mutant readiness timeout.' }
            Start-Sleep -Milliseconds 500
        } while ($true)
        $code = Run-Native $Maven ($base + @('-Dtest=LoggerTest#duplicateDeliveryIsIdempotent',
            '-Dapi.logger=http://localhost:18188')) "$work/no-deduplication.log"
        Assert-Killed $code "$work/services/api-tests/target/surefire-reports/TEST-com.processing.api.LoggerTest.xml" 'no-deduplication'
    } finally {
        & docker rm -f practice5-logger-mutant
    }
    Write-Host "All three mutations detected. Evidence: $work"
} finally {
    Pop-Location
}

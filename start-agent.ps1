$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$RuntimeArgs = @($args)

if ($RuntimeArgs.Count -eq 2 -and $RuntimeArgs[0] -eq "--config" -and -not [System.IO.Path]::IsPathRooted($RuntimeArgs[1])) {
    $RuntimeArgs[1] = Join-Path $Root $RuntimeArgs[1]
}

Push-Location "$Root/agent-runtime"
try {
    if (-not (Test-Path "node_modules")) {
        throw "Runtime dependencies are missing. Run: npm ci --omit=dev"
    }
    npm start -- @RuntimeArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Runtime failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path

Push-Location "$Root/agent-runtime"
try {
    if (-not (Test-Path "node_modules")) {
        throw "Runtime dependencies are missing. Run: npm ci --omit=dev"
    }
    npm start
    if ($LASTEXITCODE -ne 0) {
        throw "Runtime failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

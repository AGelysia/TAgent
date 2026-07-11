$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

Push-Location "$Root/agent-runtime"
try {
    npm ci --prefer-offline
    if ($LASTEXITCODE -ne 0) { throw "npm ci failed with exit code $LASTEXITCODE" }
    npm run format:check
    if ($LASTEXITCODE -ne 0) { throw "npm run format:check failed with exit code $LASTEXITCODE" }
    npm run lint
    if ($LASTEXITCODE -ne 0) { throw "npm run lint failed with exit code $LASTEXITCODE" }
    npm test
    if ($LASTEXITCODE -ne 0) { throw "npm test failed with exit code $LASTEXITCODE" }
    npm run build
    if ($LASTEXITCODE -ne 0) { throw "npm run build failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}

Push-Location $Root
try {
    & ./gradlew.bat --no-daemon --max-workers=1 :paper-plugin:build
    if ($LASTEXITCODE -ne 0) { throw "Paper build failed with exit code $LASTEXITCODE" }
    & ./gradlew.bat --no-daemon --max-workers=1 :client-mod:build
    if ($LASTEXITCODE -ne 0) { throw "Client build failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$Dist = "$Root/dist"
$Release = "$Root/release"
$Gradle = if ($env:OS -eq "Windows_NT") { "$Root/gradlew.bat" } else { "$Root/gradlew" }
$RuntimePackage = Get-Content "$Root/agent-runtime/package.json" -Raw | ConvertFrom-Json
$Version = $RuntimePackage.version
$SkipTests = if ($env:MINECRAFT_AGENT_PACKAGE_SKIP_TESTS) { $env:MINECRAFT_AGENT_PACKAGE_SKIP_TESTS } else { "0" }

if ($Version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$') {
    throw "Runtime package version is not release-safe: $Version"
}
if ($Version.ToUpperInvariant().Contains("SNAPSHOT")) {
    throw "SNAPSHOT versions cannot be packaged"
}
if ($SkipTests -notin @("0", "1")) {
    throw "MINECRAFT_AGENT_PACKAGE_SKIP_TESTS must be 0 or 1"
}

Remove-Item "$Root/agent-runtime/dist" -Recurse -Force -ErrorAction SilentlyContinue
& $Gradle --project-dir $Root --no-daemon --max-workers=1 --no-build-cache clean
if ($LASTEXITCODE -ne 0) { throw "Gradle clean failed with exit code $LASTEXITCODE" }

if ($SkipTests -eq "1") {
    Push-Location "$Root/agent-runtime"
    try {
        npm ci --prefer-offline
        if ($LASTEXITCODE -ne 0) { throw "npm ci failed with exit code $LASTEXITCODE" }
        npm run build
        if ($LASTEXITCODE -ne 0) { throw "Runtime build failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
    & $Gradle --project-dir $Root --no-daemon --max-workers=1 --no-build-cache :paper-plugin:assemble
    if ($LASTEXITCODE -ne 0) { throw "Paper build failed with exit code $LASTEXITCODE" }
    & $Gradle --project-dir $Root --no-daemon --max-workers=1 --no-build-cache :client-mod:assemble
    if ($LASTEXITCODE -ne 0) { throw "Client build failed with exit code $LASTEXITCODE" }
} else {
    $PreviousNoBuildCache = $env:MINECRAFT_AGENT_NO_BUILD_CACHE
    try {
        $env:MINECRAFT_AGENT_NO_BUILD_CACHE = "1"
        & "$Root/scripts/test.ps1"
        if ($LASTEXITCODE -ne 0) {
            throw "Verification failed with exit code $LASTEXITCODE"
        }
    } finally {
        $env:MINECRAFT_AGENT_NO_BUILD_CACHE = $PreviousNoBuildCache
    }
}
Remove-Item $Dist -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item $Release -Recurse -Force -ErrorAction SilentlyContinue
New-Item "$Dist/agent-runtime" -ItemType Directory -Force | Out-Null
New-Item "$Dist/agent-runtime/scripts" -ItemType Directory -Force | Out-Null
New-Item "$Dist/default-capability-packs" -ItemType Directory -Force | Out-Null
New-Item "$Dist/deploy" -ItemType Directory -Force | Out-Null
New-Item "$Dist/docs" -ItemType Directory -Force | Out-Null
New-Item "$Dist/protocol" -ItemType Directory -Force | Out-Null

$PaperJar = Get-Item "$Root/paper-plugin/build/libs/minecraft-agent-paper-$Version.jar"
$ClientJar = Get-Item "$Root/client-mod/build/libs/minecraft-agent-client-$Version.jar"
Copy-Item $PaperJar.FullName "$Dist/MinecraftAgent-Paper.jar"
Copy-Item $ClientJar.FullName "$Dist/MinecraftAgent-Client-Fabric.jar"
Copy-Item "$Root/agent-runtime/dist" "$Dist/agent-runtime/dist" -Recurse
Copy-Item "$Root/agent-runtime/scripts/version.mjs" "$Dist/agent-runtime/scripts"
Copy-Item "$Root/agent-runtime/package.json", "$Root/agent-runtime/package-lock.json" "$Dist/agent-runtime"
Copy-Item "$Root/agent-runtime/config.example.yml" "$Dist/agent-runtime"
Copy-Item "$Root/agent-runtime/config.example.yml" "$Dist/config.example.yml"
Copy-Item "$Root/capability-packs/*" "$Dist/default-capability-packs" -Recurse
Copy-Item "$Root/deploy/*" "$Dist/deploy" -Recurse
Copy-Item "$Root/protocol/schemas" "$Dist/protocol/schemas" -Recurse
Copy-Item "$Root/protocol/README.md" "$Dist/protocol"
Copy-Item "$Root/.env.example" $Dist
Copy-Item "$Root/docs/operations.md", "$Root/docs/phase13-manual-test.md", "$Root/docs/phase14-cloud-validation.md" "$Dist/docs"
Copy-Item "$Root/README.md", "$Root/LICENSE", "$Root/SECURITY.md", "$Root/CLIENT-COMPATIBILITY.md", "$Root/start-agent.sh", "$Root/start-agent.ps1" $Dist

$ChecksumLines = Get-ChildItem $Dist -File -Recurse |
    Where-Object { $_.Name -ne "SHA256SUMS" } |
    Sort-Object FullName |
    ForEach-Object {
        $Relative = [System.IO.Path]::GetRelativePath($Dist, $_.FullName).Replace("\", "/")
        $Hash = (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$Hash  ./$Relative"
    }
[System.IO.File]::WriteAllLines("$Dist/SHA256SUMS", $ChecksumLines, [System.Text.UTF8Encoding]::new($false))

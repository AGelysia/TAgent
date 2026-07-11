$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$Dist = "$Root/dist"

& "$Root/scripts/test.ps1"
if ($LASTEXITCODE -ne 0) {
    throw "Verification failed with exit code $LASTEXITCODE"
}
Remove-Item $Dist -Recurse -Force -ErrorAction SilentlyContinue
New-Item "$Dist/agent-runtime" -ItemType Directory -Force | Out-Null
New-Item "$Dist/agent-runtime/scripts" -ItemType Directory -Force | Out-Null
New-Item "$Dist/default-capability-packs" -ItemType Directory -Force | Out-Null
New-Item "$Dist/protocol" -ItemType Directory -Force | Out-Null

$PaperJar = Get-ChildItem "$Root/paper-plugin/build/libs/minecraft-agent-paper-*.jar" | Where-Object { $_.Name -notlike "*-sources.jar" } | Select-Object -First 1
$ClientJar = Get-ChildItem "$Root/client-mod/build/libs/minecraft-agent-client-*.jar" | Where-Object { $_.Name -notlike "*-sources.jar" } | Select-Object -First 1
Copy-Item $PaperJar.FullName "$Dist/MinecraftAgent-Paper.jar"
Copy-Item $ClientJar.FullName "$Dist/MinecraftAgent-Client-Fabric.jar"
Copy-Item "$Root/agent-runtime/dist" "$Dist/agent-runtime/dist" -Recurse
Copy-Item "$Root/agent-runtime/scripts/version.mjs" "$Dist/agent-runtime/scripts"
Copy-Item "$Root/agent-runtime/package.json", "$Root/agent-runtime/package-lock.json" "$Dist/agent-runtime"
Copy-Item "$Root/capability-packs/*" "$Dist/default-capability-packs" -Recurse
Copy-Item "$Root/protocol/schemas" "$Dist/protocol/schemas" -Recurse
Copy-Item "$Root/protocol/README.md" "$Dist/protocol"
Copy-Item "$Root/.env.example" $Dist
Copy-Item "$Root/README.md", "$Root/LICENSE", "$Root/start-agent.sh", "$Root/start-agent.ps1" $Dist

# 在本地跑一遍和 CI 上一模一样的检查：先把最新的主干（master）合进来，再跑全部测试。
#
# 用法（在仓库任意目录下，PowerShell）：
#   powershell -ExecutionPolicy Bypass -File scripts\ci-local.ps1
#
# 它不会动你现在的工作目录 —— 合并是在一个临时目录里做的，跑完就删掉。
# 想让依赖（node_modules / target）留着下次接着用，指定一个固定目录：
#   $env:CI_LOCAL_DIR = "..\fastpay-ci-local"; powershell -ExecutionPolicy Bypass -File scripts\ci-local.ps1

$ErrorActionPreference = 'Stop'

function Invoke-Step([string]$Label, [scriptblock]$Body) {
    Write-Host "==> $Label"
    & $Body
    if ($LASTEXITCODE -ne 0) { throw "$Label 失败（退出码 $LASTEXITCODE）" }
}

$base = if ($env:BASE_BRANCH) { $env:BASE_BRANCH } else { 'master' }
$repoRoot = (git rev-parse --show-toplevel).Trim()
Set-Location $repoRoot

Invoke-Step "抓一下最新的主干（$base）" { git fetch --no-tags origin $base }
$masterSha = (git rev-parse "origin/$base").Trim()
$headSha = (git rev-parse HEAD).Trim()
Write-Host "    主干最新版本号 : $masterSha"
Write-Host "    你当前分支     : $headSha"

if ($env:CI_LOCAL_DIR) {
    $workdir = $env:CI_LOCAL_DIR
    $keep = $true
} else {
    $workdir = Join-Path ([System.IO.Path]::GetTempPath()) ("fastpay-ci-local-" + [System.Guid]::NewGuid().ToString('N').Substring(0, 8))
    $keep = $false
}

try {
    Write-Host "==> 在临时目录里把「你的分支 + 主干最新」合出来（不动你现在的工作目录）"
    git worktree remove --force $workdir 2>$null | Out-Null
    if (Test-Path $workdir) { Remove-Item -Recurse -Force $workdir }
    git worktree add --detach $workdir $headSha | Out-Null
    Set-Location $workdir

    git -c user.name=ci-local -c user.email=ci-local@example.com merge --no-ff --no-edit $masterSha
    if ($LASTEXITCODE -ne 0) {
        Write-Host ""
        Write-Host "❌ 跟主干冲突了，下面这些文件打架："
        git diff --name-only --diff-filter=U | ForEach-Object { Write-Host "     - $_" }
        Write-Host ""
        Write-Host "   怎么办：回到你自己的分支，执行"
        Write-Host "     git fetch origin $base; git merge origin/$base"
        Write-Host "   把冲突解掉、提交，再跑一次本脚本。"
        exit 1
    }
    Write-Host "    ✅ 主干代码合进来了"

    Invoke-Step "后端测试 fastpay-server（含 MySQL + PostgreSQL 两套端到端）" {
        Set-Location (Join-Path $workdir 'fastpay-server'); mvn -B test
    }

    foreach ($app in @('fastpay-admin', 'fastpay-merchant')) {
        Invoke-Step "前端 $app`：装依赖" { Set-Location (Join-Path $workdir $app); npm ci }
        Invoke-Step "前端 $app`：跑测试"  { Set-Location (Join-Path $workdir $app); npm test }
        Invoke-Step "前端 $app`：打包"    { Set-Location (Join-Path $workdir $app); npm run build }
    }

    Write-Host ""
    Write-Host "✅ 全部通过 —— 基于主干版本 $masterSha"
} finally {
    Set-Location $repoRoot
    if (-not $keep) {
        git worktree remove --force $workdir 2>$null | Out-Null
        if (Test-Path $workdir) { Remove-Item -Recurse -Force $workdir -ErrorAction SilentlyContinue }
    }
}

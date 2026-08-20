# 提交代码后的自动检查

## 这东西是干嘛的

以前经常出现这种返工：开发同学在**自己拉分支那天的主干**上把测试跑绿了，交上来验收的时候才发现，
合到**今天最新的主干**上其实是红的。每发现一次就白白多转一轮，而且只有人工才发现得了。

现在只要往 PR（代码提交页面）上推代码，机器就会自动做三件事：

1. 把当天最新的主干（`master`）合进你的分支
2. 合不上（有冲突）就直接在 PR 上报「跟主干冲突了」，并列出打架的文件
3. 合得上就跑一遍全部测试，绿还是红直接标在 PR 上

**本期只报告，不拦截。** 这个检查红了**不会**挡住合并 —— 仓库上没有配置任何「必须绿才能合并」的规则，
分支保护也没动。它只负责让你早点看见问题。

## 配置文件在哪

| 文件 | 作用 |
| --- | --- |
| `.github/workflows/pr-check.yml` | 主配置：什么时候触发、跑哪几项检查 |
| `.github/actions/merge-master/action.yml` | 「合最新主干」这一步的实现，几个检查共用同一份 |
| `scripts/ci-local.sh` / `scripts/ci-local.ps1` | 本地跑同样一套检查的脚本 |

## 怎么触发

- **自动**：任何一个 PR 新开、或者往已有 PR 上推了新代码，就自动跑一次。不用做任何事
- **手动**：GitHub 仓库页 → 上方 **Actions** 标签 → 左侧选 **PR 检查（先合最新主干再跑测试）** →
  右侧 **Run workflow** → 选一个分支 → 跑。（手动触发的按钮要等这份配置合进 `master` 之后才会出现）

同一个 PR 连着推好几次，只保留最后一次的结果，前面的会自动取消。

## 从哪看结果

打开那个 PR，拉到最下面的检查列表，一共四项：

| 检查项 | 跑的是什么 |
| --- | --- |
| `1·先合最新主干` | 把主干最新代码合进来。**冲突就在这一项红掉**，后面几项直接跳过 |
| `2·后端测试 fastpay-server（MySQL + PostgreSQL）` | `mvn -B test`，含 MySQL 和 PostgreSQL 两套端到端测试 |
| `3·前端测试 (fastpay-admin)` / `(fastpay-merchant)` | `npm test` + `npm run build` |
| `4·一句话结论` | 一句人话总结这次是绿是红 |

点任意一项右边的 **Details** 就能进去看日志。几个要点：

- **这次基于哪个主干版本跑的**，写在每一项的日志开头，也写在运行页面顶部的摘要（Summary）里，
  长这样：`主干（master）版本号 : d1097527a3fe...`，可以直接点开看那笔提交
- **红了怎么定位**：看是哪一项红的就点哪一项。后端测试红了还可以在运行页面底部下载
  `后端测试报告-surefire` 附件，里面是每条测试的详细报错
- 前端那两项就算测试挂了也会照样打一次包，好让你一次看清「是测试挂了，还是连编译都过不去」

## 本地怎么跑同样的测试

一条命令（在仓库根目录）：

```bash
# Linux / macOS / Windows 的 Git Bash
bash scripts/ci-local.sh
```

```powershell
# Windows PowerShell
powershell -ExecutionPolicy Bypass -File scripts\ci-local.ps1
```

脚本做的事和 CI 完全一样：抓最新主干 → 合进来 → 跑后端 `mvn -B test` → 跑两个前端的
`npm ci && npm test && npm run build`。

**它不会动你现在的工作目录** —— 合并是在一个临时目录里做的，跑完自动删掉。
每次重新装依赖有点慢，想让依赖留着下次接着用，就指定一个固定目录：

```bash
CI_LOCAL_DIR=../fastpay-ci-local bash scripts/ci-local.sh
```

```powershell
$env:CI_LOCAL_DIR = "..\fastpay-ci-local"
powershell -ExecutionPolicy Bypass -File scripts\ci-local.ps1
```

不想用脚本、只想手动跑其中一块的话：

```bash
cd fastpay-server   && mvn -B test
cd fastpay-admin    && npm ci && npm test && npm run build
cd fastpay-merchant && npm ci && npm test && npm run build
```

## 环境说明

- 检查跑在 GitHub 提供的 Ubuntu 机器上，用 JDK 17（temurin）和 Node 20
- 两套端到端测试**不需要 Docker、也不需要机器上装数据库**：测试自己会临时拉起一个真的 MariaDB
  （MySQL 协议兼容，靠 MariaDB4j）和一个真的 PostgreSQL（靠 zonky embedded-postgres），跑完自动销毁
- **机器时区设成了东八区（`TZ: Asia/Shanghai`）**，跟开发同学的机器、跟配置里写死的
  `Asia/Shanghai` 保持一致，见下面一节
- 这个检查只读代码、只跑测试，**不碰服务器、不碰任何数据库、不发布任何东西**

### 为什么要专门把 CI 机器的时区调成东八区

GitHub 的机器默认是 UTC（零时区）。不调的话，`MySqlFullFlowTest` 里「订单超时自动关单」那两条
测试会挂：测试往数据库写过期时间用的是机器的本地时间，而 MySQL 连接串里写死了
`serverTimezone=Asia/Shanghai`，两边差 8 小时，本该过期的订单就没过期。PostgreSQL 那条路径的连接串
没有这个参数，所以不受影响 —— 这也是为什么只有 MySQL 那一半会挂。

把 CI 机器调成东八区，是为了让「本地绿 = CI 绿」，**不是绕开测试**。

⚠️ 但这件事本身是个隐患：**真把服务部署到一台 UTC 时区的服务器上，同样的问题会在生产上重现**
（订单该超时的不超时）。这个已经单独提出来给后端跟进，不在本期 CI 的范围内。上线前请确认服务器
时区是东八区：`timedatectl` 看一眼，不对就 `sudo timedatectl set-timezone Asia/Shanghai`。

## 后面还要做什么

等紧急 bug MTM-170 那两个 PR 收口以后，再另开一个 issue 把这个检查收紧成「必须绿才能合并」。
现在先不设，是怕把正在赶的修复堵在门外。

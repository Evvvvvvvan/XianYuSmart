# Star History Actions Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 保留 `main` Pull Request 保护，并让每日星标趋势任务自动创建、合并 PR 后成功结束。

**Architecture:** 现有 Python 生成脚本保持不变，工作流把图片提交推送到固定的 `automation/star-history` 分支，再用仓库内置 `GITHUB_TOKEN` 创建或复用 PR 并 squash 合并。仓库 Actions 权限只增加创建和处理 PR 的能力，不新增 PAT 或第三方 Action。

**Tech Stack:** GitHub Actions、GitHub CLI、Bash、Python 3.12、Git branch protection

---

### Task 1: 建立工作流配置的失败检查

**Files:**
- Create temporarily: `scripts/validate_star_history_workflow.py`
- Read: `.github/workflows/star-history.yml`

- [ ] **Step 1: 写入临时失败检查**

```python
from pathlib import Path

workflow = Path(".github/workflows/star-history.yml").read_text(encoding="utf-8")

required_fragments = (
    "pull-requests: write",
    "automation/star-history",
    "gh pr create",
    "gh pr merge",
)

missing = [fragment for fragment in required_fragments if fragment not in workflow]
if missing:
    raise SystemExit(f"missing workflow fragments: {', '.join(missing)}")
```

- [ ] **Step 2: 运行检查并确认 RED**

Run:

```powershell
python scripts/validate_star_history_workflow.py
```

Expected: 非零退出，提示缺少 `pull-requests: write`、自动化分支和 PR 命令。

- [ ] **Step 3: 回读现有线上失败证据**

Run:

```powershell
gh run view 30790441840 --log-failed
```

Expected: 图片生成成功，`git push` 因 `main` 必须通过 Pull Request 被拒绝。

### Task 2: 改造星标趋势工作流

**Files:**
- Modify: `.github/workflows/star-history.yml`
- Test temporarily: `scripts/validate_star_history_workflow.py`

- [ ] **Step 1: 增加最小 PR 权限**

将权限配置改为：

```yaml
permissions:
  contents: write
  pull-requests: write
```

- [ ] **Step 2: 用自动 PR 替换直接推送**

将最后一步替换为：

```yaml
      - name: Commit and merge updated charts
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          git config user.name "github-actions[bot]"
          git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
          git add docs/assets/star-history-*.png
          git diff --cached --quiet && exit 0
          BRANCH="automation/star-history"
          git commit -m "docs: update star history chart [skip ci]"
          git push --force origin "HEAD:${BRANCH}"
          PR_NUMBER="$(gh pr list --base main --head "${BRANCH}" --state open --json number --jq '.[0].number')"
          if [ -z "${PR_NUMBER}" ]; then
            PR_URL="$(gh pr create --base main --head "${BRANCH}" --title "docs: update star history chart" --body "Automated daily star history chart update.")"
            PR_NUMBER="${PR_URL##*/}"
          fi
          gh pr merge "${PR_NUMBER}" --squash --delete-branch
```

固定分支配合现有 `concurrency`，失败重跑时复用未关闭 PR。

- [ ] **Step 3: 运行临时检查并确认 GREEN**

Run:

```powershell
python scripts/validate_star_history_workflow.py
```

Expected: 退出码为 0。

- [ ] **Step 4: 检查 YAML 差异与改动范围**

Run:

```powershell
git diff --check -- .github/workflows/star-history.yml
git diff -- .github/workflows/star-history.yml
git status --short
```

Expected: 只出现工作流、临时检查和任务开始前已有的 `README.md`、`DISCLAIMER.md` 改动。

### Task 3: 清理临时检查并完成本地验证

**Files:**
- Delete: `scripts/validate_star_history_workflow.py`
- Verify: `.github/workflows/star-history.yml`

- [ ] **Step 1: 删除临时检查**

使用 `apply_patch` 删除 `scripts/validate_star_history_workflow.py`。

- [ ] **Step 2: 确认临时产物已删除**

Run:

```powershell
if (Test-Path scripts\validate_star_history_workflow.py) { throw "temporary test still exists" }
```

Expected: 退出码为 0。

- [ ] **Step 3: 使用内存检查重新验证正式工作流**

Run:

```powershell
@'
from pathlib import Path
workflow = Path(".github/workflows/star-history.yml").read_text(encoding="utf-8")
for fragment in ("pull-requests: write", "automation/star-history", "gh pr create", "gh pr merge"):
    assert fragment in workflow, fragment
'@ | python -
```

Expected: 退出码为 0，不产生文件。

- [ ] **Step 4: 确认容器工作流未改变**

Run:

```powershell
git diff --exit-code HEAD -- .github/workflows/publish-container.yml
```

Expected: 无输出，退出码为 0。

### Task 4: 提交、设置仓库权限并触发线上闭环

**Files:**
- Commit: `.github/workflows/star-history.yml`
- Commit: `docs/superpowers/plans/2026-08-03-star-history-actions.md`
- Repository setting: Actions workflow permissions

- [ ] **Step 1: 提交工作流和计划**

Run:

```powershell
git add -- .github/workflows/star-history.yml docs/superpowers/plans/2026-08-03-star-history-actions.md
git diff --cached --check
git commit -m "fix: update star history through pull request"
```

Expected: 提交只包含工作流与计划文档。

- [ ] **Step 2: 开启 Actions 创建 PR 权限**

Run:

```powershell
gh api --method PUT repos/Evvvvvvvan/XianYuSmart/actions/permissions/workflow -f default_workflow_permissions=read -F can_approve_pull_request_reviews=true
gh api repos/Evvvvvvvan/XianYuSmart/actions/permissions/workflow
```

Expected: `default_workflow_permissions` 为 `read`，`can_approve_pull_request_reviews` 为 `true`。

- [ ] **Step 3: 推送主分支**

Run:

```powershell
git push origin main
```

Expected: 远端 `main` 包含工作流修复，原有 `README.md`、`DISCLAIMER.md` 未提交改动不进入提交。

- [ ] **Step 4: 手动触发并等待新任务**

Run:

```powershell
$headSha = git rev-parse HEAD
gh workflow run star-history.yml --ref main
do {
    Start-Sleep -Seconds 2
    $run = gh run list --workflow star-history.yml --event workflow_dispatch --limit 1 --json databaseId,headSha,status,conclusion,url | ConvertFrom-Json
} while (-not $run -or $run.headSha -ne $headSha)
gh run watch $run.databaseId --exit-status
```

Expected: 新任务运行完成并返回成功。

- [ ] **Step 5: 回读线上结果和保护设置**

Run:

```powershell
gh run view $run.databaseId --json conclusion,status,url,headSha,jobs
gh pr list --base main --head automation/star-history --state all --limit 1 --json number,state,mergedAt,url
gh api repos/Evvvvvvvan/XianYuSmart/branches/main/protection
gh api repos/Evvvvvvvan/XianYuSmart/git/ref/heads/automation/star-history
```

Expected:

- Actions 结论为 `success`。
- 自动 PR 状态为已合并。
- `main` 仍要求 Pull Request 和会话解决。
- 自动化临时分支查询返回 404，表示已删除。

- [ ] **Step 6: 同步自动合并提交并核对工作区**

Run:

```powershell
git pull --ff-only origin main
git status --short
git log -4 --oneline
```

Expected: 本地主分支同步自动图片提交，工作区仍只保留任务开始前已有的 `README.md`、`DISCLAIMER.md` 改动。

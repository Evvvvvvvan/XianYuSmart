# Star History Actions 自动合并设计

## 目标

保留 `main` 必须通过 Pull Request 的分支保护，让每日星标趋势任务在生成图片后自动创建并合并 PR，避免直接推送被拒绝。

## 根因

`.github/workflows/star-history.yml` 已成功安装依赖、读取 stargazers 并生成两张图片，失败固定发生在 `git push`。当前 `main` 启用了 Pull Request 保护，而 `github-actions[bot]` 不能在个人仓库中加入保护规则绕过名单。

## 方案

只调整现有星标趋势工作流：

1. 保留 `contents: write`，新增 `pull-requests: write`。
2. 图片没有变化时直接成功结束，不创建空 PR。
3. 图片变化时提交到固定分支 `automation/star-history`。
4. 使用仓库内置 `GITHUB_TOKEN` 查找或创建指向 `main` 的 PR。
5. 使用仓库已开启的 squash merge 自动合并，并删除临时分支。
6. 开启仓库的 `can_approve_pull_request_reviews` Actions 权限，使内置令牌可以创建 PR；工作流不执行审批操作。

固定分支配合现有 `concurrency` 使用，失败后再次运行会更新同一个分支和未关闭 PR，不产生每日重复 PR。

## 权限与安全

- 不新增 PAT、长期密钥或第三方 Action。
- `GITHUB_TOKEN` 仅授予内容写入和 PR 写入权限。
- `main` 分支保护保持不变，所有图片更新仍通过 PR 合并。
- 工作流只暂存 `docs/assets/star-history-*.png`，不会提交其他运行时文件。

## 异常处理

- 无图片变化：任务成功退出。
- 已存在自动化 PR：复用现有 PR，不重复创建。
- 推送、创建 PR 或合并失败：命令返回非零，任务保留失败状态和 PR，便于重跑与定位。
- 主分支在运行期间更新：GitHub 在 PR 合并时按当前分支保护重新校验，不绕过保护规则。

## 验证

1. 静态检查工作流 YAML 和权限配置。
2. 本地运行星标图生成脚本，确认只影响两张图片及缓存文件。
3. 推送工作流后使用 `workflow_dispatch` 手动触发。
4. 回读 Actions 结论、自动 PR 状态、合并提交和临时分支状态。
5. 确认 `main` 分支保护仍要求 Pull Request，容器发布工作流未修改。

## 影响范围

- 修改：`.github/workflows/star-history.yml`
- 仓库设置：允许 GitHub Actions 创建和处理 Pull Request
- 不修改：容器发布、应用代码、依赖、数据库、README 和 DISCLAIMER

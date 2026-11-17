# 在 GitHub 开发，向 Gitee 提交

## 仓库与导入基线

- 开发仓库：<https://github.com/YsxbTshe/Kylin_Security_Operations_agent-CSIETPP-dachuang>，默认分支 `main`。
- Gitee 项目：<https://gitee.com/missXIEROU/qilinyunweiagentdachuang>，默认分支 `master`。
- 本次导入基于 Gitee 提交 `b40a9aec5cf5c33a0edda9bd892bae957080aacf`。
- 通过合并保留两边的提交历史。项目 README、源码、依赖清单、锁文件及许可证保持 Gitee 原样；GitHub 原 README 可在原始提交 `662a37c051c2583bc2cafcf8c5da5fdff60c57b7` 中查看。

## 首次克隆后的设置

```bash
git clone https://github.com/YsxbTshe/Kylin_Security_Operations_agent-CSIETPP-dachuang.git
cd Kylin_Security_Operations_agent-CSIETPP-dachuang
git remote add gitee https://gitee.com/missXIEROU/qilinyunweiagentdachuang.git
git fetch gitee
```

当前云环境已经配置了 `gitee`，不必重复添加。使用现有检出目录即可；每个云任务已经隔离，无需额外创建 Git worktree。

Gitee 的读取和推送均使用具有对应仓库权限的账号。HTTPS 在本机终端提示时输入用户名和密码；云任务可通过环境设置中的 `GITEE_USERNAME`、`GITEE_PASSWORD` 配合临时 `GIT_ASKPASS` 读取凭据。凭据不写入远程 URL、仓库文件或提交记录。网页登录状态不能替代 Git 认证。

## 在 GitHub 上修改

```bash
git switch main
git pull --ff-only origin main
git switch -c feature/your-change

# 修改并运行项目已有的相关测试，然后提交具体修改过的文件。
git add <修改的文件路径>
git commit -m "Describe the change"
git push -u origin feature/your-change
```

在 GitHub 创建 Pull Request，检查通过后合入 `main`。组件启动和测试命令见项目原有的 [README](README.md)。

## 将修改提交给 Gitee

从已经合入 GitHub 的版本创建提交分支，并合并 Gitee 的最新变更：

```bash
git switch main
git pull --ff-only origin main
git fetch gitee
git switch -c submit/your-change
git merge gitee/master

# 如有冲突，解决并重新运行相关测试。
git push gitee HEAD:refs/heads/submit/your-change
```

然后在 Gitee 创建 Pull Request：`submit/your-change` → `master`。推送需要该账号对仓库有写入权限；没有写入权限时，先推送到自己在 Gitee 的 fork，再向原项目提 Pull Request。

此流程不执行强制推送，也不配置自动双向镜像。Gitee 上出现新提交时，先合并并验证后再提交修改。

---
name: ai-review-loop
description: Use when pushing a feature branch, creating a PR, monitoring AI Review, or deciding whether to auto-fix review findings. Follows the collaboration rule: severity >= 4 auto-fix and re-monitor; severity < 4 stop, analyze, and let the human decide.
---

# AI Review 协作循环

> 适用场景：每次功能分支 push 后，由 AI Review 自动审查 PR。
> 目标：严重问题自动修复，轻微问题不擅自改，交由人来决策。

## 工作流

```text
1. push feature 分支
2. 创建 PR 到 main
3. 监听 AI Review 结果
4. 分析严重级别：
   - severity >= 4：直接修复 → push → 回到第 3 步监听
   - severity < 4：停下，不擅自修改
5. 对 severity < 4 的问题：
   - 分析是否真的需要修复
   - 给出结论与建议
   - 等人决策后再动手
```

## 严重级别定义

| 级别 | 含义 | 自动处理 |
|---|---|---|
| 5 | 致命 | 是 |
| 4 | 严重 | 是 |
| 3 | 必修 | 否，等人决策 |
| 2 | 轻微 | 否，等人决策 |
| 1 | 建议 | 否，等人决策 |

## 自动修复触发条件

只有同时满足以下条件才自动修复：

```text
- AI Review 标记 severity >= 4
- 修复方案明确、低风险
- 不会引入新的行为变化
```

自动修复后：

```text
git add <files>
git commit -m "fix: <原因>"
git push origin <branch>
```

然后继续监听下一轮 Review。

## 非自动修复处理方式

对 severity < 4 的问题：

```text
1. 判断是否为真实问题
2. 判断影响范围
3. 判断是否值得在本次 PR 修复
4. 输出结论：
   - 建议修复 / 可以忽略 / 后续跟进
5. 等人拍板
```

不要因为“AI 说有问题”就擅自改。

## 边界

- 如果 AI Review 卡住或超时，主动查询 check-run 状态。
- 如果 Review 结论与代码事实不符，以代码事实为准，并说明原因。
- 如果问题跨多个 PR 或涉及架构决策，即使 severity >= 4 也可能需要先咨询人，不盲目自动改。

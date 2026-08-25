# Requirements Document — AI Terminal Autopilot

## Introduction

一款 Android 应用，内置完整的终端模拟器环境与用户自配的 AI 大模型接入能力。AI 以终端为唯一执行通道，通过"规划 → 命令生成 → 执行 → 观察 → 修正"的自动驾驶循环，把用户的自然语言任务自动推进到完成状态。

产品独特性定位：主流 AI 编程产品均运行于桌面端并以对话补全为主；本产品运行在 Android 设备上，AI 直接操控一个连接 Android 系统的真实终端，实现移动端全自动化编程闭环。

## Glossary

- **终端会话 (Terminal Session)**: 应用内基于伪终端 (PTY) 运行的交互式 shell 进程及其界面
- **Linux 用户态环境 (Bootstrap)**: 应用私有目录内预置的命令行工具集（shell、coreutils、语言运行时等），使终端具备接近完整 Linux 的命令能力
- **Agent 循环 (Agent Loop)**: AI 的 规划→生成命令→执行→回读输出→修正 闭环自动化流程
- **任务 (Task)**: 用户提交的一段自然语言目标，可附验收标准
- **模型配置 (Model Config)**: 用户提供的 API Base URL、API Key、模型名称、协议类型
- **工作区 (Workspace)**: 应用私有存储中供 AI 读写文件与执行命令的根目录
- **高危命令 (High-Risk Command)**: 可能造成不可逆数据或系统损害的命令模式

## Requirements

### R1 终端模拟器

**User Story:** AS 用户, I want 一个功能完整的真实终端, so that 我能直接查看和手动干预设备上的系统操作

#### Acceptance Criteria

1. THE 系统 SHALL 提供基于伪终端 (PTY) 的交互式 shell 会话，并正确渲染 ANSI 转义序列
2. WHEN 用户在终端输入框输入命令并确认, THE 系统 SHALL 在当前会话执行该命令并实时回显输出
3. THE 系统 SHALL 支持创建、切换和关闭多个命名终端会话
4. THE 系统 SHALL 在应用私有目录内提供预置 Linux 用户态环境，包含 shell、coreutils、git、clang 编译工具链、Python 与 Node.js 运行时
5. WHEN 终端会话因进程退出而结束, THE 系统 SHALL 显示退出码并提供重建会话入口

### R2 AI 模型接入

**User Story:** AS 用户, I want 自主选择并配置任意 OpenAI 兼容的大模型服务, so that 我可以使用自己信任的模型驱动 Agent

#### Acceptance Criteria

1. THE 系统 SHALL 提供模型配置界面，包含 API 地址、API 密钥、模型名称三个必填项
2. THE 系统 SHALL 通过 OpenAI 兼容 Chat Completions 协议与所配置服务通信
3. THE 系统 SHALL 将 API 密钥保存于应用私有加密存储
4. IF 模型请求失败, THE 系统 SHALL 向用户展示失败阶段与具体错误信息
5. WHEN 用户修改模型配置, THE 系统 SHALL 在下一次请求生效且中断当前 Agent 循环

### R3 Agent 自动驾驶循环

**User Story:** AS 用户, I want 只提交一句目标就能让 AI 自动完成任务, so that 全程无需逐步指挥与确认

#### Acceptance Criteria

1. WHEN 用户提交任务, THE 系统 SHALL 调用模型生成结构化执行计划并向用户展示
2. THE 系统 SHALL 按计划逐条向终端注入命令，捕获退出码与输出并反馈给模型
3. WHEN 某条命令执行失败, THE 系统 SHALL 将失败信息交由模型生成修正方案后继续循环
4. WHILE Agent 循环处于自动执行状态, THE 系统 SHALL 在界面上实时显示当前步骤、正在执行的命令与其输出
5. WHEN 单任务自动迭代轮数达到上限（默认 50 轮）, THE 系统 SHALL 暂停循环并等待用户选择继续或终止
6. WHEN 任务判定为完成, THE 系统 SHALL 输出包含变更文件清单、关键日志摘要与总耗时的完成报告

### R4 安全边界与审计

**User Story:** AS 用户, I want 对 AI 的破坏性操作有拦截和追溯手段, so that 自动化过程可控可信

#### Acceptance Criteria

1. THE 系统 SHALL 在首次启动时展示自动化风险提示并获得用户确认
2. IF 待执行命令匹配高危命令模式, THE 系统 SHALL 暂停循环并向用户弹出人工确认对话框
3. THE 系统 SHALL 将每条 AI 执行的命令、时间戳与退出码写入本地审计日志
4. THE 系统 SHALL 支持将审计日志导出为文本文件
5. WHEN 用户点击停止按钮, THE 系统 SHALL 在当前命令结束后终止 Agent 循环

### R5 分层权限增强（普通设备接近 Root 能力）

**User Story:** AS 用户, I want 在无 root 的普通设备上获得尽量高的系统操作权限, so that AI 的自动化能力覆盖更大范围

#### Acceptance Criteria

1. THE 系统 SHALL 实现三级权限模型：沙箱级（应用私有目录）、存储级（所有文件访问）、Shell 级（Shizuku 授权）
2. THE 系统 SHALL 提供引导页协助用户开启所有文件访问权限
3. WHEN 用户完成 Shizuku 无线调试授权, THE 系统 SHALL 提供 Shell 级命令执行通道并用于系统级操作
4. THE 系统 SHALL 在任务提交界面展示当前生效的权限级别与各通道可用状态
5. IF 高权限通道不可用, THE 系统 SHALL 自动回退到低一级通道继续任务并在报告中注明降级情况

### R6 工作区管理

**User Story:** AS 用户, I want 管理自己的代码工作区, so that 项目成果可以备份迁移

#### Acceptance Criteria

1. THE 系统 SHALL 在首次启动时于应用私有目录创建 workspace 根目录
2. THE 系统 SHALL 支持将工作区导出为 zip 文件到公共下载目录
3. THE 系统 SHALL 支持从 zip 文件导入工作区
4. THE 系统 SHALL 展示工作区文件树并允许用户以只读方式查看文件内容

## Out of Scope（本期不做）

- 依赖设备 Root 直接提权的场景
- 多设备协同、云端同步
- 非终端通道的原生 GUI 操作自动化

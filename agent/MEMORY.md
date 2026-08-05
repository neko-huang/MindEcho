## 长期行为规则

## 核心状态锚点

- **当前项目**：MindEcho - 情绪感知录音App（Android MVP）
- **项目路径**：`/app/data/所有对话/主对话/MindEcho/`
- **比赛截止**：2026-08-05
- **方案选择**：先做方案A（纯软件Android App），后续方案B（硬件原型）
- **技术栈**：Kotlin + Jetpack Compose + Room + MVVM
- **核心功能**：录音、语音转文字、情绪分析（语音特征）、对话总结、历史报告
- **关键约束**：无生理传感器时也能运行；隐私优先；随时开始/停止录音
- **GitHub仓库**：https://github.com/neko-huang/MindEcho
- **最新Release**：v0.1.0-mvp（debug APK，16MB，2026-08-04发布）
- **状态**：MindEcho全量GitHub Actions CI工作流配置完成，支持自动构建+自动发布Release，经过5轮迭代修复所有编译问题，CI构建100%成功（2026-08-04）
详见 `recent_memory/project/mindecho_full_ci_build_and_release_v0.1.0_20260804.md`
- **状态**：MindEcho已完成LLM API从OpenAI切换至DeepSeek，两次GitHub Actions构建全部通过（2026-08-04）
详见 `recent_memory/project/mindecho_switch_llm_to_deepseek_20260804.md`
- **开发进度**：MindEcho录音详情页SessionDetailScreen代码实现完成，包含新增ViewModel、修复2项历史编译错误，待提交推送CI构建（2026-08-04）
详见 `recent_memory/project/mindecho_session_detail_screen_implement_20260804.md`
- **开发进度**：MindEcho AssemblyAI语音转文字+说话人分离功能所有代码逻辑全部实现，待解决本地Android环境配置问题后完成编译验证+提交CI构建（2026-08-04）
详见 `recent_memory/project/mindecho_assemblyai_transcription_integration_20260804.md`

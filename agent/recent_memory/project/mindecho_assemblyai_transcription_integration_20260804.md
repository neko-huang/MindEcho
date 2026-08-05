# MindEcho AssemblyAI 语音转文字 + 说话人分离集成进展（2026-08-04）
## 已完成代码改动清单
1. 修改 Constants.kt：新增 ASSEMBLYAI_BASE_URL、轮询间隔3s、5分钟超时常量
2. 修改 PreferenceManager.kt：新增 assemblyAiApiKey 持久化Flow和save方法，使用DataStore存储
3. 修改 ApiClient.kt：完整新增 AssemblyAI Retrofit 接口，包含上传音频、创建转录任务、查询转录状态三个接口，配套5个相关数据类
4. 修改 SettingsScreen.kt：新增 AssemblyAI API Key 配置项，同时修复原有的 DeepSeek API Key 持久化Bug
5. 修改 RecordingService.kt：录音停止后自动触发上传音频、创建转录、轮询状态、保存带说话人标记的转写结果到数据库，未配置Key时走原有本地兜底逻辑
6. 修改 RecordingScreen.kt：新增转录过程多阶段进度UI提示
7. 修改 strings.xml：新增全部12条相关英文字符串资源
## 当前进度
所有代码改动全部完成，在本地Android环境尝试构建时遇到Android SDK配置相关问题：Java 17丢失、platform-34的android.jar缺失，尚未完成本地编译校验、commit+push和触发GitHub Actions Release步骤。
## 待办
后续优先补全Android本地环境依赖，执行 ./gradlew assembleDebug 验证编译，提交对应commit推送至远程仓库触发CI构建。
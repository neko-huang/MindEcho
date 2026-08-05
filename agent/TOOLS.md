# 工具使用经验
这里沉淀在使用各类工具过程中积累的技巧与注意事项，方便日后复用。
- **Android CI构建通用坑点**：Kotlin+Jetpack Compose+AGP8.2+Gradle8.4项目在GitHub Actions上的8个典型编译问题与修复方案。详见 基础设定/experience/android_ci_common_issues.md
- **DeepSeek v4-flash最佳配置**：model名使用deepseek-v4-flash，reasoning_effort设为max，temperature=1.0，top_p=0.95，max_tokens≥5000，输出质量最高
- **Kotlin Companion Object限制**：同一个类中只允许定义一个companion object，定义第二个独立的companion object会直接触发编译错误，需合并所有静态方法/常量到同一个companion块中
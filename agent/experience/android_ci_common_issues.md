## Android GitHub Actions 构建通用坑点集合
适用于 Kotlin + Jetpack Compose + AGP 8.2.x + Kotlin 1.9.2x + Gradle 8.4 项目。

### 1. gradlew脚本截断
- 现象：执行gradlew报语法错误，正常Gradle8.4 gradlew约8KB，小于该值大概率截断
- 修复：从官方Gradle分发镜像下载完整脚本替换

### 2. 缺失gradle-wrapper.jar
- 现象：CI找不到wrapper类
- 修复：下载对应版本gradle-wrapper.jar推送到gradle/wrapper目录，不要用.gitignore排除

### 3. Room用annotationProcessor生成代码失败
- 现象：Room DAO的_Impl实现类找不到
- 修复：根build.gradle.kts添加KSP插件，app模块用ksp替代annotationProcessor处理Room注解

### 4. 带构造参数的Enum无法被Room映射
- 现象：SQL聚合查询返回枚举映射失败
- 修复：DAO层返回类型改为String，Repository层手动用valueOf()转枚举

### 5. Compose Material3实验API报错
- 现象：使用Card(onClick)等API编译报错
- 修复：对应函数/类加@OptIn(ExperimentalMaterial3Api::class)注解

### 6. Navigation Compose 2.7.6+废弃API报错
- 现象：NavGraph.Companion.findNode找不到
- 修复：直接删除该无效import即可

### 7. Compose自定义扩展函数与标准API冲突
- 现象：Modifier同名方法参数类型冲突
- 修复：删除自定义冲突扩展，用官方标准API

### 8. CI自动发布优化
- 痛点：手动下载Artifact再上传Release易超时
- 优化：Workflow直接用upload-release-asset action在CI环境中生成Release上传APK，无需本地绕路

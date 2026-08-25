# User Instruction Memory

## Entries

[Project Knowledge Summary]
- Date: 2026-08-25
- Context: Agent 执行 AI Terminal Autopilot 项目构建验证时发现
- Category: Build Methods
- Instructions:
  - 本项目为 Android 应用：JDK 17 + Android SDK 34 + NDK 26.1.10909125（已装于 /opt/android-sdk）
  - 构建命令：`export ANDROID_HOME=/opt/android-sdk && ./gradlew :app:assembleDebug --no-daemon`
  - 单元测试：`./gradlew :app:testDebugUnitTest --no-daemon`，结果 XML 在 app/build/test-results/
  - Gradle 8.7 发行版需手动下载到 ~/.gradle/wrapper/dists/gradle-8.7-bin/bhs2wmbdwecv87pi65oeuq5iu/（wrapper 自动下载曾因网络损坏，curl 断点续传后校验通过）
  - 构建必须用 background_terminal_create 执行并限内存 30%（环境总内存仅 ~8GB 且常驻占用高）
  - gradle 输出管道接 grep|head 会因 SIGPIPE 提前杀死构建进程，应全量重定向到日志文件再检索

[Project Knowledge Summary]
- Date: 2026-08-25
- Context: 产品定位由用户多轮澄清确认
- Category: Workflow & Collaboration
- Instructions:
  - 产品形态：Android 应用内嵌完整终端，AI 操控终端实现全自动编程，用户自配 OpenAI 兼容模型
  - 无 root 设备能力边界：应用沙箱 PTY（免权限）+ MANAGE_EXTERNAL_STORAGE + Shizuku shell 级通道，三层递进
  - rikka.shizuku.Shizuku.newProcess 在 13.x 为非公开签名，采用反射多签名探测 + 失败降级处理

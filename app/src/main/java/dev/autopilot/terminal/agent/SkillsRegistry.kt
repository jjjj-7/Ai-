package dev.autopilot.terminal.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SkillDef(val label: String, val prompt: String)

object SkillsRegistry {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val builtin: List<SkillDef> = listOf(
        SkillDef(
            "联网搜索",
            "联网搜索今天的科技热点: 用 curl 带最新 Chrome 浏览器 UA 和 Accept-Language 头访问 Bing 搜索 (关键词自选, URL 编码), --compressed -L 抓回结果页 HTML 存到工作区, 再用 python3 正则提取每条结果的标题、来源和摘要, 给我整理成要点列表"
        ),
        SkillDef(
            "抓取网页",
            "我要抓一个网页的正文。先问我要 URL, 然后用 curl 带浏览器伪装头抓取 (处理 gzip 与重定向), 保存后用 python3 提出去掉脚本和样式的纯文本正文, 输出核心内容摘要"
        ),
        SkillDef(
            "批量下载",
            "帮我做批量下载: 先问我要文件链接清单 (或者给我一个列表页 URL 由你解析出下载链接), 然后写一个 python 脚本用 curl 逐个下载到 ~/storage/downloads, 每个间隔 1 秒, 校验文件大小, 最后汇报成功失败清单"
        ),
        SkillDef(
            "站点监控",
            "对几个常用网站做健康检查: 向我要域名列表 (没有就用 github.com / baidu.com / zhihu.com), 写脚本用 curl 测每个站点的 HTTP 状态码、DNS 解析时间和总响应耗时, 输出对比表格"
        ),
        SkillDef(
            "设备体检",
            "查看设备状态: uname -a 内核信息、df -h 磁盘占用、free 内存、uptime 运行时长, 汇总成简报"
        ),
        SkillDef(
            "创造技能",
            "我想给你添加一个新技能。请先问我想要什么功能 (一句话即可), 然后把它设计成一条可复用的完整指令, 写入 ~/tools/user_skills.json —— 读取现有内容 (没有就空数组), 追加新元素 {\"label\": \"技能按钮名\", \"prompt\": \"完整指令\"}, 保持 JSON 数组合法, 写回后告诉用户稍等片刻就能在技能栏看到"
        ),
        SkillDef(
            "应用调控",
            "帮我调度手机应用: 先问我想做什么 (打开某个 App / 看看装了什么 / 直达某个设置页), 然后 pm list packages -3 列出应用或用 am start 打开目标, 需要时用 dumpsys package 查详情, 完成后汇报打开了什么"
        ),
        SkillDef(
            "创造指令",
            "我想造一个环境里还没有的命令。先问我想要这个命令做什么、怎么调用 (参数设计), 然后把可执行脚本写进 ~/bin/<命令名> 并 chmod +x, 实际运行验证可用后, 告诉我命令名和用法示例"
        )
    )

    const val USER_SKILLS_FILE = "tools/user_skills.json"

    fun userSkillsFile(homeDir: File): File = File(homeDir, USER_SKILLS_FILE)

    @Serializable
    private data class Wrapper(val skills: List<SkillDef> = emptyList())

    fun loadUserSkills(homeDir: File): List<SkillDef> = runCatching {
        val f = userSkillsFile(homeDir)
        if (!f.isFile) return emptyList()
        val text = f.readText().trim()
        if (text.isEmpty()) return emptyList()
        val list = if (text.startsWith("[")) {
            json.decodeFromString<List<SkillDef>>(text)
        } else {
            json.decodeFromString(Wrapper.serializer(), text).skills
        }
        list.filter { it.label.isNotBlank() && it.prompt.isNotBlank() }.take(20)
    }.getOrDefault(emptyList())

    fun all(homeDir: File): List<SkillDef> = builtin + loadUserSkills(homeDir)

    fun describe(homeDir: File): String {
        val list = all(homeDir)
        if (list.isEmpty()) return ""
        return buildString {
            appendLine("当前技能清单 (任务与之相关时你应主动直接调用对应工具与方法, 无需等待用户点名):")
            list.forEach { s -> appendLine("- ${s.label}: ${s.prompt.take(72)}") }
            appendLine("用户自定义技能完整定义存于 ~/tools/$USER_SKILLS_FILE, 需要时可自行读取全文; 你也可以往该文件追加新技能。")
        }
    }
}

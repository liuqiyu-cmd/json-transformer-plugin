# JSON Transformer — IntelliJ IDEA 插件

把 [tableconvert.com](https://tableconvert.com/zh-cn/json-to-json) 的表格工具搬进 IDE，
完整覆盖其三大块：**数据源 → 在线表格编辑器 → 表格生成器**，无需打开浏览器。

> 开发方式参考 [JetBrains Marketplace](https://plugins.jetbrains.com/) 与官方
> [IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/getting-started.html)，
> 使用官方推荐的 **Gradle + IntelliJ Platform Gradle Plugin (2.x) + Kotlin** 方案。

---

## 功能（对应 tableconvert 三大块）

### ① 数据源（输入格式，支持自动识别）

| 数据源 | 说明 |
| --- | --- |
| 自动识别 | 按内容特征自动判断以下所有格式 |
| JSON 数组 | 四种布局：对象数组 / 二维数组 / 列数组 / 键值数组 |
| Excel | 从 Excel 复制粘贴的 TSV 文本 |
| CSV | 支持引号、转义引号、单元格内换行 |
| Markdown 表格 | 支持 `\|` 转义与对齐分隔行 |
| HTML 表格 | `<table>`，支持 thead/tbody、实体解码 |
| 插入 SQL | `INSERT INTO t (cols) VALUES (...), (...)`，支持 `''` 转义与 NULL |
| LaTeX 表格 | `tabular` 环境 |
| XML | `<root><row><field>v</field></row></root>` 结构 |
| MySQL 查询结果 | mysql 客户端 `+---+` 边框输出（及 `-e` 的 TSV） |
| MediaWiki 表格 | `{\| class="wikitable" ... \|}` |
| **上传文件** | 工具栏「上传文件」按钮，支持 `.xlsx` / `.csv` / `.tsv` / `.json` / `.md` / `.html` / `.xml` / `.sql` 等；`.xlsx` 用零依赖读取器解析（sharedStrings、类型化数字/布尔、实体转义、AA+ 列名，已用 openpyxl 生成的文件实测） |

### ② 在线表格编辑器

网格直接编辑单元格，另提供：转置 / 去重 / 删除空行 / **删除选中行 / 删除选中列** /
**在上方/下方插入行 / 在左侧/右侧插入列** /
转大写 / 转小写 / 首字母大写 / 查找替换（纯文本或正则）/ 按选中列排序 ↑↓ /
撤销重做 / 重置表格。

- **右键菜单**（与 tableconvert 一致）：复制 / 剪切 / 粘贴（TSV，粘贴时自动扩表）、
  删除选中行、在上方/下方插入行、删除选中列、在左侧/右侧插入列、升序（A to Z）/ 降序（Z to A）排序。
- **底部统计条**：随选中列实时显示 `COUNT / SUM / MIN / MAX / AVG`；未选中列时汇总整表。
- 表格支持**整行选择 + 整列选择**（点行头/列头可多选），删除行列同样受撤销/重做保护。
- 编辑器底部有 **复制结果 / 导出文件** 按钮，把编辑后的表格按当前生成格式导出。
- **所有编辑操作只修改工作表格，绝不改动输入区里的原始数据**；点「重置表格」即可从输入重新解析。

### ③ 表格生成器（输出格式）

| 生成格式 | 说明 |
| --- | --- |
| JSON | 对象数组输出，受缩进 / 压缩 / 根对象名控制 |
| Excel | TSV 文本（可粘贴进 Excel）；另有 **「导出 Excel (.xlsx)」按钮** 生成真正的 xlsx 文件（零依赖写入：数字/布尔类型化单元格、中文表名支持、特殊字符转义；已用 openpyxl 实测可解析） |
| Magic | 文本表格（ASCII 网格）* |
| SQL | `CREATE TABLE IF NOT EXISTS 表名 (…)`（列类型按内容自动推断：INT / DOUBLE / BOOLEAN / VARCHAR(255) / TEXT）+ `INSERT INTO`，「根对象名称/表名」即 SQL 表名 |
| LaTeX | `tabular`，特殊字符自动转义 |
| HTML | 缩进良好的 `<table>`，`&<>"` 自动转义 |
| CSV | 按需加引号 |
| Markdown | 管道表格，`\|` 转义 |
| XML | `<root><row>…`，「根对象名称/表名」即根元素名，非法标签名自动清洗 |
| PDF | 文本表格（ASCII 网格）* |
| ASCII | MySQL 风格对齐网格 |
| MediaWiki | `{| class="wikitable" … \|}` |

> \* Magic 与 PDF 在 tableconvert 上分别是交互编辑器和二进制 PDF；
> 在插件的文本输出区里以可打印的文本表格呈现（可打印/再粘贴利用）。

### JSON→JSON 专用选项

缩进大小（2/4/8 空格、Tab）、压缩输出、根对象名称（对任意输出格式生效）、
智能解析（单元格内字符串化 JSON 还原为真实 JSON 值）。
所有选项**即时生效**：一改就自动重新转换，输入区编辑防抖实时转换。

---

## 项目结构

```
json-transformer-plugin/
├── build.gradle.kts                 # 官方 IntelliJ Platform Gradle Plugin 配置
├── settings.gradle.kts
├── gradle/wrapper/gradle-wrapper.properties
├── src/main/kotlin/com/example/jsontransformer/
│   ├── JsonModel.kt                 # 零依赖 JSON 模型 + 解析器 + 渲染器
│   ├── JsonTransformer.kt           # 核心：JSON 表格解析/转换/渲染引擎
│   ├── TableIO.kt                   # 多格式数据源解析 + 表格生成器
│   ├── JsonTransformerPanel.kt      # 主 UI（JSON 转换 + 表格编辑器 + 表格生成器）
│   ├── JsonTransformerToolWindowFactory.kt
│   ├── JsonTransformerProjectService.kt
│   ├── OpenJsonTransformerAction.kt
│   └── ConvertEditorSelectionAction.kt
├── src/main/resources/
│   ├── META-INF/plugin.xml          # 插件描述符
│   └── icons/pluginIcon.svg
└── src/test/kotlin/.../JsonTransformerTest.kt   # JUnit 测试
```

> 核心引擎 `JsonModel.kt` / `JsonTransformer.kt` / `TableIO.kt` **不依赖任何
> IntelliJ API**，因此可以脱离 SDK 单独编译和单元测试。

---

## 环境要求

- **JDK 17**（IntelliJ Platform Gradle Plugin 2.x 要求）
- IntelliJ IDEA 2024.1+（插件兼容范围 build 241 – 243.*）
- Gradle 9.0.0（仓库已通过 `gradle-wrapper.properties` 指定版本；在 IDEA 中导入会自动处理）

---

## 构建与运行

### 方式一：在 IntelliJ IDEA 中（推荐）

1. `File → Open` 选择本目录（`json-transformer-plugin`）。
2. IDEA 会自动按 `gradle-wrapper.properties` 配置 Gradle 与 JDK 17。
3. 打开 Gradle 工具窗口，运行 `Tasks → intellijPlatform → runIde`。
   这会启动一个安装了本插件的沙箱 IDE，在右侧看到 **JSON Transformer** 工具窗口。

### 方式二：命令行

```bash
# 若本地没有 Gradle，先生成 wrapper（需已装 Gradle）：
gradle wrapper --gradle-version 9.0.0

# 启动沙箱 IDE
./gradlew runIde

# 运行单元测试
./gradlew test

# 打包为可发布的 zip（产物在 build/distributions/）
./gradlew buildPlugin

# 发布到 JetBrains Marketplace（需先配置 HUB_TOKEN）
./gradlew publishPlugin
```

---

## 使用插件

1. 右侧工具窗口 **JSON Transformer** 默认带一个示例。
2. 在「数据源」选输入格式（一般用**自动识别**），粘贴任意来源的表格数据；输出自动实时更新。
3. 「JSON 转换」页调整布局/缩进/压缩/根对象名/智能解析，即时刷新。
4. 「表格编辑器」页用转置 / 去重 / 删除空行 / 大小写 / 查找替换 / 排序等整理数据。
5. 「表格生成器」页选择 JSON / Excel / SQL / LaTeX / HTML / CSV / Markdown / XML / ASCII / MediaWiki 等输出。
6. `复制到剪贴板` 复制，`下载文件` 导出（按格式可选 json/csv/sql/xml/html/tex 等）。
7. 在任意编辑器里选中一段表格文本 → 右键 **Convert JSON Selection**，一键送进工具窗口并转换。

---

## 验证

核心引擎用 **55 个用例**独立验证，全部通过：四种 JSON 布局互转、缩进、压缩、
根对象名、智能解析、表格编辑操作，以及全部 9 种数据源解析 + 12 种生成格式
（含 CSV 引号/换行、SQL `''` 转义与 NULL、LaTeX 转义、XML 标签清洗、
MySQL 网格对齐、MediaWiki 表头、Markdown `\|` 转义、多组端到端往返）。
`src/test` 下的 **19 个 JUnit 测试**同样全部通过（`./gradlew test`）。

独立验证脚本位于仓库外的 `../verify/`（使用 Kotlin 编译器直接编译引擎并运行，
不依赖 IntelliJ SDK）。正式的 JUnit 测试在 `src/test`。

---

## 发布到市场

1. 在 [JetBrains Marketplace](https://plugins.jetbrains.com/) 创建一个 plugin。
2. 在 `gradle.properties` 或环境变量设置 `ORG_GRADLE_PROJECT_intellijPlatformPublishingToken`
   （即 Marketplace 的 HUB token）。
3. `./gradlew publishPlugin`。

详见官方文档：<https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html>

---

## 许可证（License）

本项目采用 **[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)**，
完整文本见仓库根目录的 [`LICENSE`](LICENSE) 文件。

选择 Apache 2.0 的理由：宽松（允许自由使用/修改/商用）且附带明确的**专利授权条款**，
是 JetBrains 平台本身与官方插件模板采用的标准许可证。

### 上架 Marketplace 时如何完成许可证

按 JetBrains 官方要求（[Uploading a new plugin](https://plugins.jetbrains.com/docs/marketplace/uploading-a-new-plugin.html)）：

1. **License 字段必填**：上传表单中 License 选择 **Apache 2.0**，
   License URL 填 <https://www.apache.org/licenses/LICENSE-2.0.txt>。
2. **开源许可证必须提供源码链接**：Source Code URL 填本插件的代码仓库地址
   （`https://github.com/liuqiyu-cmd/json-transformer-plugin`）。
   这是开源分发的法律要求，缺失会被 Marketplace 下架。
3. 把 `LICENSE` 文件与代码一起提交到开源仓库（本项目已包含）。
4. 发布前把 `build.gradle.kts` 中 `vendor { … }` 与 `plugin.xml` 的
   `<vendor>` 改成你自己的名字/邮箱/仓库地址。

> 依据：自 2018-05 起所有 Marketplace 插件必须提供有效 License URL；
> 选开源许可证的插件必须填写有效的 Source Code 链接，否则会被移出市场。
> 参考：[Vendor obligation to provide source code for OSS software](https://blog.jetbrains.com/platform/2023/04/vendor-obligation-to-provide-source-code-for-oss-software/)

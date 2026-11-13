# 番茄书库 TomatoLib

一个把自己番茄免费小说（`com.dragon.read`）**离线下载缓存**读出来、索引、解密并导出的 Android 小工具。

不是阅读器，是「下载 + 解密」工具：扫书、看章节、逐章解密、一键解密全部、导出成四种格式。正文阅读只作预览，正式阅读用自己的阅读器打开导出文件。

- 包名：`com.tomato.lib`
- 体积：约 330 KB（单个 APK）
- 形态：**单 Activity + WebView + 本地单文件 WebUI**，纯脚本构建，**无 Gradle、无 AndroidX、无第三方库**
- 权限：需要 **root**（直接读 `/data/data/com.dragon.read` 与 `/data/user/0/com.dragon.read` 下的缓存与密钥文件）
- 联网：完全不联网

## 功能

| 分类 | 内容 |
| --- | --- |
| 扫描 | 自动发现账号目录 → 书 → 章节三级索引，多账号书架，支持搜索、筛选、收藏 |
| 缓存信息 | 每本书的原文件位置、占用大小、章节数、最后缓存时间、账号 ID |
| 解密 | 逐章 AES 解密（密钥与版本来自番茄账号级 MMKV），结果**落盘持久缓存**，大退重进不重解；支持"一键解密全部"串行队列、单章超时兜底 |
| 阅读预览 | 上下滚动 / 左右翻章两种模式，字号、行距、字体（黑体/宋体/等宽/导入字体）、亮度、六套配色，章节标题左对齐加粗、正文自动剥离重复章名 |
| 导出 | TXT / Markdown / HTML / JSON；HTML 自带可点击目录并复用阅读器同款排版，写到 `Download/番茄书库/导出/` |
| 界面 | 五套皮肤、深浅色、外观与阅读器同一套颜色令牌，状态栏与页面同色 |

## 构建

依赖：`aapt2`、`d8`、`zipalign`、`apksigner`（Android SDK build-tools 34.0.0）、`javac`（JDK 17）、`android.jar`（API 34）。

```bash
export BT=$HOME/android-sdk/build-tools/34.0.0
export AJ=$HOME/android-sdk/platforms/android-34/android.jar
export JAVAC=$HOME/opt/jdk-17/bin/javac

# 签名需要自己的 keystore（仓库内不附带）
keytool -genkeypair -keystore keystore.jks -alias tomato \
  -storepass tomato123 -keypass tomato123 -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=TomatoPure"

bash build.sh          # 产出 TomatoLib-v<VER>.apk
```

`build.sh` 里 `VER` 与 `src/main/AndroidManifest.xml` 的 `versionCode` / `versionName`、以及 `assets/webui/index.html` 里的版本徽章需要一起改。

## 目录结构

```
build.sh                     aapt2 → javac → d8 → zipalign → apksigner
assets/webui/index.html      整个 WebUI（HTML+CSS+JS 单文件）
src/main/AndroidManifest.xml
src/main/java/com/tomato/lib/
  MainActivity.java          单 Activity，WebView 与 Lib 桥（scan/chapters/decrypt/cache*/export/saveDoc…）
  Scanner.java               账号/书/章节扫描
  Decrypt.java               MMKV 密钥解析 + AES 解密 + gzip
  Root.java                  su 调用封装
  ShareProvider.java         content:// 文件 Provider
src/main/res/                图标与基础资源
docs-design-system.txt       UI 设计令牌
README-提示词.md             AI 接手须知（业务背景与技术约束）
CHANGELOG.md                 历次变更
```

## 解密原理（简述）

1. 章节文件是 `[二进制头][JSON]`，只有 `content` 字段被加密；
2. `content` = `base64(IV[16] || 密文)`，AES 解密后若 `compress_status == 1` 再 gzip 解压，得到带章节标记的 HTML；
3. 密钥存在账号级 MMKV：`files/mmkv/prefix_public_crypt_key_kv_<uid>`，版本表在 `prefix_private_<uid>_chapter_version_<bookid>`；
4. 解密结果写入 `getFilesDir()/decache/<书目录名哈希>/<章节文件名>.txt`，重进 App 直接命中缓存。

## 版本

当前发行版 **v1.6.8**（versionCode 27）。历史变更见 [CHANGELOG.md](CHANGELOG.md)。

## 开源协议与"开源等级"

本仓库采用 **Apache License 2.0**，属于**宽松型（Permissive）开源协议**中的"署名保留型"：

| 维度 | 本项目的规则 |
| --- | --- |
| 能否商用 | 可以 |
| 能否修改后再闭源发布 | 可以（修改过的文件需显著说明改了什么） |
| 是否要求开源衍生代码 | **不要求**（不是 GPL/AGPL 那种传染式 copyleft） |
| 是否要求署名 | **要求**：使用或参考了本项目的代码/实现，必须在 README、关于页或源文件头标注来源与仓库链接 |
| 是否提供专利授权 | 提供（Apache-2.0 自带专利许可条款，比 MIT 更稳） |

一句话：**宽松型 + 强制署名**。你可以拿去改、拿去发布（包括商用、闭源），但不能装作是自己从零写的——引用或参考了就得写清出处。

为什么不选另外几种常见协议：
- **MIT**：同样宽松，但只要求保留版权声明，对"参考了思路"约束很弱；
- **GPL-3.0 / AGPL-3.0**：强 copyleft，会要求你的衍生作品也开源，和"别人自由使用"冲突；
- **CC BY 4.0**：偏内容/文档许可，用在软件代码上会带来专利与分发条款的空白。

如果你确实要"连参考思路都必须声明"，Apache-2.0 的署名条款只管衍生作品；对"只参考未复制"的情况，靠的是上面 NOTICE 里的社区约定（README 标注出处），法律上无法强制。

## 说明

本项目只读取本机已有缓存，不联网、不修改番茄的任何数据。仅供个人备份与阅读已下载内容，请遵守各内容平台的服务条款。

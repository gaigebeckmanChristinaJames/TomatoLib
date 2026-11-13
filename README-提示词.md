

## 0. 你的角色与目标

你要接手的是一个 **已经能跑通的 Android 原生壳 + WebUI 应用**，包名 `com.tomato.lib`，中文名「番茄书库」。
它用 root 直接读取番茄免费小说（`com.dragon.read`）的**离线下载缓存目录**，把「账号 → 书 → 章节」三级结构索引出来，
并提供书架、搜索、筛选、收藏、阅读进度、章节浏览、导出等能力。

本轮任务优先级：
1. **保住「能扫到书」这条命脉**（历史上最容易回归的就是这里）；
2. UI 严格沿用用户给定的设计系统，不做风格再创作；
3. 新增功能只在既有架构上加，不推倒重来（不要换 Compose、不要换 Gradle、不要引入第三方 UI 库）。

技术栈是刻意的：**纯脚本构建（aapt2 + javac + d8 + zipalign + apksigner），无 Gradle、无 AndroidX、单 Activity + WebView + 本地 HTML**。
体积 290KB 左右、冷启动快、可离线、不依赖网络。除非用户明确要求，不要改变这个形态。

---

## 1. 业务背景（必须理解，否则会做错功能）

### 1.1 番茄缓存真实结构（已在真机核实）

```
/data/data/com.dragon.read/files/                 ← 扫描根（root 才能读）
├── <账号UID>/                                     ← 例如 101327284007，一个账号一个目录
│   ├── prefix_public_<book_id>/                   ← 一本书一个目录，里面是 N 个章节缓存文件
│   │   ├── 1794498936164-86400000                 ← 单章文件：无扩展名，名字是 "<时间戳>-86400000"
│   │   └── ...
│   ├── prefix_public_text_para_cache_<book_id>/   ← 非书：正文段落缓存，必须排除
│   ├── prefix_public_audio_info_data/             ← 非书：听书缓存
│   ├── prefix_public__polaris_/ prefix_public__reading_/ ... ← 非书：各类业务缓存
│   ├── reader_*.db / reading_bookshelf.db         ← 书级元数据库
│   └── ...
├── mmkv/  keva/  databases/  cache/  logs/ ...    ← 非账号目录，必须跳过
```

单章文件内部结构（**这是全项目最关键的一手事实**）：

```
[二进制头 ~若干字节][一段 JSON]
JSON 字段（全部明文）：
  book_id / chapter_id / name("第82章 失控") / paragraphs_num /
  content_md5 / chapter_version / compress_status(1=压缩) / content
其中 content 是 base64(IV[16] || 密文)，compress_status:1 表示明文侧是 gzip；
正文由 App 用账号级 MMKV 里的书密钥解密后渲染（见 §5.6）。
```

**因此本 App 的产品定位是「库目录索引器 + 阅读器外壳」**：
书名、章节名、章ID、大小、时间、进度全部可做；正文解密后可直接阅读（v1.4.x 起，见 §5.6）。

### 1.2 必须支持的场景

- 一台设备上有**多个账号**都下载过书，要全部列出来，不局限于某一个 UID；
- 换号/新增账号后，重扫即可看到；
- 番茄改目录前缀、加中间层、或某些书平铺在 `files/` 下，都不能漏（所以**不能按前缀名认书**）。

---

## 2. 环境与构建

### 2.1 依赖

| 组件 | 要求 |
|---|---|
| JDK | 17（`javac` 用 `-source 8 -target 8` 产出 Java 8 字节码） |
| Android SDK | `platforms/android-34/android.jar` + `build-tools/34.0.0`（aapt2 / d8 / zipalign / apksigner） |
| 系统 | Linux / macOS 可直接跑；Windows 需把 build.sh 改写为 PowerShell 或 WSL |
| 签名 | 自带 `keystore.jks`（alias `tomato`，storepass/keypass `tomato123`）——**换签名会导致无法覆盖安装**，保持原样 |

### 2.2 build.sh（照抄即可，只需改三个绝对路径）

```bash
#!/bin/bash
set -e
BT=<SDK>/build-tools/34.0.0
AJ=<SDK>/platforms/android-34/android.jar
JAVAC=<JDK17>/bin/javac
OUT=build
VER=1.2.0
APK=TomatoLib-v$VER.apk

rm -rf $OUT && mkdir -p $OUT/res $OUT/classes $OUT/dex

$BT/aapt2 compile --dir src/main/res -o $OUT/res/res.zip
$BT/aapt2 link -o $OUT/base.apk -I $AJ \
  --manifest src/main/AndroidManifest.xml \
  -A assets \
  --min-sdk-version 26 --target-sdk-version 34 \
  --auto-add-overlay $OUT/res/res.zip

$JAVAC -source 8 -target 8 -Xlint:-options -nowarn -encoding UTF-8 \
  -d $OUT/classes -cp "$AJ" $(find src/main/java -name '*.java')

$BT/d8 --min-api 26 --lib $AJ --output $OUT/dex $(find $OUT/classes -name '*.class')

cp $OUT/base.apk $OUT/unsigned.apk
(cd $OUT/dex && zip -q -X ../unsigned.apk classes.dex)

$BT/zipalign -f -p 4 $OUT/unsigned.apk $OUT/aligned.apk
$BT/apksigner sign --ks keystore.jks --ks-pass pass:tomato123 --key-pass pass:tomato123 \
  --out $APK $OUT/aligned.apk
$BT/apksigner verify --print-certs $APK | head -3
ls -l $APK
```

### 2.3 构建期的坑（都踩过，别再踩）

1. `aapt2 link` 必须带 `-A assets`，否则 `assets/webui/index.html` 不进包，App 白屏；
2. `d8` 必须带 `--lib $AJ`，否则 `android.util.Base64`、`Context` 等报缺类；
3. `classes.dex` 必须在 `zipalign` **之前**塞进 APK，且用 `zip -X`（不加额外字段）；
4. `zipalign -p 4` 的 `-p` 是页对齐，别省；
5. `javac` 不能只写 `javac` 依赖 PATH —— 很多机器上只有 `java` 没有 `javac`，必须用 JDK 里绝对路径；
6. 版本号有三处要同步：`AndroidManifest.xml` 的 `versionCode/versionName`、`build.sh` 的 `VER`、`index.html` 顶栏徽章文案。

---

## 3. 目录结构

```
TomatoLib/
├── build.sh
├── keystore.jks
├── src/main/AndroidManifest.xml
├── src/main/java/com/tomato/lib/
│   ├── MainActivity.java     # 单 Activity + WebView + JS 桥（5.4KB）
│   ├── Root.java             # su 多策略探测与执行（9.4KB）
│   └── Scanner.java          # 扫描 / 章节 / 导出（18.5KB）
├── src/main/res/
│   ├── values/{strings.xml, colors.xml}          # ic_bg = #FDF6EC
│   ├── drawable-nodpi/ic_fg.png                  # 自适应图标前景（432px，徽标占 70%）
│   ├── mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher(_round).png
│   └── mipmap-anydpi-v26/ic_launcher(_round).xml # adaptive icon
└── assets/webui/index.html   # 单文件 WebUI（约 55KB，五页 + 五皮肤 + 抽屉 + Sheet + Toast）
```

Manifest 关键项：`minSdk 26 / targetSdk 34`，`android:icon="@mipmap/ic_launcher"`、`android:roundIcon`，
`configChanges` 含 `uiMode`（切换深浅色不重建 Activity），单 `Activity`，无任何权限声明（读 `/data/data` 靠 root，不靠系统权限）。

---

## 4. 架构与契约（改动必须遵守）

```
WebUI(index.html)  --Lib.scan()----------->  MainActivity.startScan()  --> Scanner.scanAll()
                   <-window.onScan(json)--                                    |
                   --Lib.chapters(dir)---->  startChapters()          --> Scanner.chapters(dir)
                   <-onChapters(json,dir)--                                   |
                   --Lib.export(...)------>  startExport()            --> Scanner.export(...)
                   <-onExport(msg)--------                                    |
                   --Lib.getStr/setStr---->  SharedPreferences（跨重启持久化）
```

### 4.1 JS 桥（`@JavascriptInterface`，对象名固定为 `Lib`）

| 方法 | 参数 | 返回 | 说明 |
|---|---|---|---|
| `scan()` | — | 无（异步回调） | 触发全量扫描 |
| `chapters(dir)` | 书目录绝对路径 | 无（异步） | 读章节清单 |
| `export(dir,bid,name,chapters,listB64)` | 五参 | 无（异步） | 导出到 `/storage/emulated/0/Download/番茄书库/<bid>/` |
| `copy(text)` | 文本 | 无 | 复制到剪贴板（诊断回执用） |
| `getStr(k,def)` / `setStr(k,v)` | key | String / 无 | 落盘配置，JS 侧封装成 localStorage 风格 |
| `info()` | — | JSON 字符串 | `{pkg,dataRoot,ver}` |

**回调必须是 `window.onScan / onChapters / onExport` 三个全局函数，且参数是 JSON 字符串**（不是对象）。
所有 Java→JS 调用统一走 `post()` → `runOnUiThread` → `evaluateJavascript`，绝不能在子线程碰 WebView。

### 4.2 `scanAll()` 返回的 JSON（字段级契约）

```json
{
  "ok": true, "su": true, "rootUid": true, "uid": 0,
  "suPath": "/system/bin/su",          // 找到的 su 绝对路径，可为 ""
  "mode": "uid=0 ctx=u:r:... 模式=su -c 根=/data/data/com.dragon.read/files",
  "noDir": false,                      // 目标目录不存在
  "noBook": false,                     // 目录在但没识别出书
  "total": 16, "totalKb": 38000, "totalCh": 6288, "accCount": 2,
  "diag": ["[0] su -c → uid=0 ctx=... 目录可见", "扫描 exit=0", "识别到账号 2 个…"],
  "accounts": [
    { "acc": "101327284007",
      "books": [
        { "bid": "7666965658051021886", "name": "都玩轮椅号了…",
          "chapters": 393, "sizeKb": 3400, "mtime": 1763000000000,
          "dir": "/data/data/com.dragon.read/files/101327284007/prefix_public_7666965658051021886" }
      ]
    }
  ]
}
```

`diag` 数组是**排障命脉**：真机上出问题，用户只需要贴这一段就能定位。
`mode` 必须显示当前选用的 su 策略与扫描根，别只显示「成功/失败」。

### 4.3 `chapters(dir)` 返回

```json
{"ok":true,"dir":"…","total":393,
 "list":[{"f":"1794498936164-86400000","name":"第82章 失控","cid":"7666…","size":6214,"mtime":1763000000000}]}
```

排序规则：有章号的按章号升序，没章号的按 mtime 升序。
**必须过滤**只解析出大小、既无 `name` 又无 `cid` 的文件（`-shm`/`-wal`/临时件），否则列表会出现空白行。

---

## 5. 关键实现（照做，别简化）

### 5.1 Root：多策略探测 + 自动回退（**本项目的核心难题**）

真机故障史：v1.0/v1.1 在用户设备上始终 0 本，诊断显示 `id -u = 0`（root 拿到了）但
`[ -d /data/data/com.dragon.read/files ]` 为假、`ls` 看不到。
这是 **mount namespace / SELinux 视野差异**：`su -c` 可能跑在 App 自己的命名空间里，
看不到别人家的 `/data/data/<包名>`；而用户在 MT 终端里 `su` 后 `ls` 能看见（终端是全局命名空间）。

所以 `Root` 必须实现：

1. **7 种执行策略**，逐个试：
   ```
   [0] su -c
   [1] su --mount-master -c
   [2] su -M -c
   [3] su 0 sh -c
   [4] su -c "nsenter -t 1 -m -p -- /system/bin/sh -c '<script>'"
   [5] su -c "nsenter -t 1 -m    -- /system/bin/sh -c '<script>'"
   [6] su -mm -c
   ```
   nsenter 那条要用单引号包裹内层脚本，并把内层里的 `'` 转义为 `'\''`（`quote()` 函数）。
2. **3 个候选扫描根**，每个策略都要试一遍：
   ```
   /data/data/com.dragon.read/files
   /data/user/0/com.dragon.read/files
   /data/user_de/0/com.dragon.read/files
   ```
3. 探测脚本只有 4 行，逐条带前缀回传，Java 侧解析：
   ```
   echo "S|$(id -u 2>/dev/null)"                       → uid
   echo "C|$(cat /proc/self/attr/current 2>/dev/null)" → SELinux 域
   echo "L|$(ls -ld <root> 2>&1 | head -2 | tr '\n' ' ')"  → 含 "drwx" 视为可见
   echo "N|$(ls -1 <root> 2>/dev/null | head -3 | tr '\n' ',')"
   ```
4. 命中条件 = `uid==0 且 L 含 drwx`；**取第一个命中组合并缓存**，后续扫描/章节/导出全用它；
5. 全部不命中时退回「root 可用者」，`mode` 标注「目标目录在 root 下不可见」；
6. 每策略超时 12s，进程读流必须开独立线程抽干 stdout/stderr，`waitFor(timeout)` 后 `destroy()`，否则会挂死。

安全约束：`safePath()` 只放行 `/data/data/com.dragon.read/files/`、`/data/user/`、`/data/user_de/` 下、
且含有 `/com.dragon.read/files/`、仅由 `[A-Za-z0-9/_.-]` 组成、不含 `..` 的路径。所有进 shell 的路径都必须过这一关。

### 5.2 扫描脚本（写在 Java 里，用 `Root.run(script)` 执行）

```sh
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
D=<扫描根>
echo "UID|$(id -u 2>/dev/null || echo -)"
[ -d "$D" ] || { echo "NODIR|$D"; exit 0; }
echo "TOPLIST|$(ls -1 "$D" 2>&1 | tr '\n' ',')"

is_book() {                       # ← 核心：按内容认书，不认目录名
  b="$1"; [ -d "$b" ] || return 1
  c=$(ls -1 "$b" 2>/dev/null | wc -l); [ "$c" -ge 2 ] || return 1
  f=$(ls -1 "$b" 2>/dev/null | head -n 1); [ -n "$f" ] || return 1
  head -c 600 "$b/$f" 2>/dev/null | grep -q 'book_name' || return 1
  return 0
}
emit() {                          # 一行一本书，末段是文件头 base64
  acc="$1"; d="$2"
  c=$(ls -1 "$d" 2>/dev/null | wc -l)
  kb=$(du -sk "$d" 2>/dev/null | awk '{print $1}')
  mt=$(stat -c %Y "$d" 2>/dev/null)
  f=$(ls -1 "$d" 2>/dev/null | head -n 1)
  h=$(head -c 600 "$d/$f" 2>/dev/null | base64 | tr -d '\n')
  echo "BK|$acc|$d|$c|${kb:-0}|${mt:-0}|$h"
}

for a in "$D"/*; do               # ① 账号层
  [ -d "$a" ] || continue
  case "${a##*/}" in prefix_*|mmkv|keva|databases|cache|files|logs|plugins|shared_prefs) continue ;; esac
  echo "ACC|${a##*/}"
  for b in "$a"/*; do is_book "$b" && emit "${a##*/}" "$b"; done
done
for b in "$D"/*; do               # ② 平铺层兜底，账号记为 _flat
  [ -d "$b" ] || continue
  case "${b##*/}" in prefix_private_*_chapter_version_*) continue ;; esac
  is_book "$b" && emit "_flat" "$b"
done
echo "END"
```

`book_id` 提取：从目录名剥掉 `prefix_public_` / `prefix_private_` 后，再 `replaceAll("_[0-9]+$","")`
（这样 `prefix_public_text_para_cache_123` 之类的尾巴不会污染纯数字 ID）。

### 5.3 章节解析（grep 的坑最多）

```sh
cd '<dir>' || { echo @@NOCD; exit 0; }
echo @@S; stat -c '%n|%s|%Y' ./* 2>/dev/null
echo @@N; grep -aoH '"name":"[^"]*"' ./* 2>/dev/null
echo @@C; grep -aEoH '"chapter_id":"?[0-9]+' ./* 2>/dev/null
echo @@E
```

必须注意：
- 用 `./*` 而不是 `*`：章节文件名可能以 `-` 开头，会被当成命令选项；
- `chapter_id` 必须用 **`-E`（ERE）**，`"?` 才是量词；用 BRE 时 `?` 是字面量，会导致 cid 恒为 0（踩过）；
- 名字与 cid 按 `文件名` 关联成一行一条，避免大目录爆内存；
- 排序用**单一 sortKey**（有章号时 `"1"+6位补零`，无章号时 `"2"+mtime补零`），
  不要写多重比较器 —— 会抛 `Comparison method violates its general contract`（踩过）。

### 5.4 导出

`cp -a <dir>/. → /storage/emulated/0/Download/番茄书库/<bid>/`，同时写两个文本：

- `meta.txt`：书ID、书名、章节数、源目录、说明（正文为 App 私有加密缓存，不包含正文）；
- `chapters.txt`：章节清单（由 JS 生成后 base64 传入，Java 侧解码写盘，避免中文被 shell 吃掉）。

最后 `chown media_rw:media_rw`、`chmod 644`，否则文件在文件管理器里看不到。

### 5.5 已知修复清单（这些 bug 都已经修过，重构时别回退）

1. `sum()` 少传参数导致「占用」恒为 `—`；
2. Sheet 里目录长串横向溢出 → `overflow-wrap:anywhere`；
3. 概览「最近在读」不显示 → 进度存在 `PROG` 里但渲染函数读了别的 key；
4. 假 su 回放时策略 1/2/3 会报 `-c: not found`，属测试环境预期，真机不复现。

---

### 5.6 正文解密（`Decrypt.java`，已用真机样本验证）

**文件与密文**

- 章节文件 = `[二进制头][JSON]`，二进制头通常 22~30 字节；有的文件还夹一段 Java 序列化残渣
  （`aced0005 t… {{"book_id":…`），所以解析时要逐个 `{` 试，不能只取第一个；
- 章节文件名 = `javaHash(chapter_id)`（有符号十进制，可能是负数），不是时间戳；
- `content` = `base64(IV[16] || 密文)`，`compress_status:1` 表示明文侧是 gzip（`1f 8b 08`），解压后是 HTML。

**加密方式（实测结论）**

- `AES-128-CBC/PKCS5`，key = 下方密钥表里 `key_<id>` 值解码出的 16 字节，IV = 密文前 16 字节；
- `content_md5` 与「压缩明文」「解压后正文」的 md5 都不相等（推测是对原始密文或整包算的），
  所以判定用 **gzip 结构校验 + 正文文本评分**，命中即可返回，不必扫完全部候选。

**密钥表（MMKV）**

- 落盘布局：`[uint32 数据区长度][0xFFFFFF07 标记][varint keyLen][key][varint valueLen][value]…`，
  条目从 offset 8 开始；
- 值都是「长度前缀 + 字符串」：`key_<id>` = `0x20`(32) + 32 位大写 hex → 解开即 16 字节 AES key；
  `key_v2_<id>_1001` = `0x40`(64) + 64 位 base64 → 48 字节材料（备用/新版本）；
- 实测一本 394 章的样本：391 章用同一把 key 全部解开，另外 3 个文件不是章节（Java 序列化缓存）。

**其它**

- 读取时跳过 `*.crc`（CRC 旁路文件，4096 B，无数据）；
- 若按 book_id 线索找不到对应条目，就整表取值（表里条目不多），逐个试；
- 日志会打出每个条目的键名、值长度、值 hex，以及命中用的 key/mode/score。

## 6. UI 规范（**严格照用户给的《通用模块WebUI_复刻提示词.txt》，不许自由发挥**）

### 6.1 硬性数值

| 项 | 值 |
|---|---|
| 顶栏高 | 58px；Logo 34×34 / 圆角 9；标题 20/700 |
| 副标题 | 12.5px，次要色 |
| 顶栏徽章 | 21px 高，圆角胶囊 |
| 图标按钮 | 34×34 |
| 抽屉 | 286px（小屏 84vw）；一级项 44px；二级项 33px；末端胶囊 29px |
| 开关 | 44×26，滑块直径 20 |
| 卡片 | 1px 边框 + 16px 内距 + 12px 下距 |
| Sheet | 82vh，顶部圆角 18，遮罩 `rgba(0,0,0,.42)` |
| Toast | 3.2s 自动消失 |
| 动效 | 抽屉 440ms / Sheet 460ms / 开关与按钮 320ms，统一 `cubic-bezier(.2,.7,.2,1)` |
| 页面数 | ≥4（本项目 5 页：概览 / 书架 / 账号 / 诊断 / 外观） |

### 6.2 视觉纪律

- 五套皮肤只换**设计令牌与圆角**，不改布局：A 石墨（深色默认）/ B 钴蓝 / C 纸墨（浅色）/ D 霓虹 / E 柔光；
- **禁止**玻璃拟态、`backdrop-filter`、`color-mix()`（旧 WebView 不支持，会整片失效）；
- 除皮肤外所有颜色走 CSS 变量，禁止硬编码色值散落；
- 无插画、无 emoji、无渐变装饰条；
- 深浅色跟随系统（`prefers-color-scheme` + Manifest 的 `uiMode`），不是自己造一个开关去改全部颜色。

### 6.3 五页结构

1. **概览**：扫描结果三宫格（账号 / 书籍 / 占用）+ 主按钮「重新扫描」+ 「最近在读」（带进度条，点击继续）+「访问方式」三行（扫描根 / 识别规则 / 权限来源）；
2. **书架**：搜索框（书名 / 书ID / 账号）+ 三段排序（最近下载 / 章节数 / 书名）+ 账号筛选 chips + 双列封面网格（封面按书名 hash 取 hsl 渐变、顶左「账号 xxxx」、右上收藏星、底部章数与大小）；
3. **账号**：按账号分组的书数/章数/占用汇总，点击进入该账号书架；
4. **诊断**：运行环境（su 路径 / 访问模式 / uid / 目录存在性）、扫描回执（等宽文本 + 复制按钮）、目录说明；
5. **外观**：皮肤选择器（五卡）、深浅色段、字号档位、开关行（44×26）。

### 6.4 交互

- 点书 → 详情 Sheet（书名 / 书ID / 章数 / 大小 / 时间 / 账号 / 目录 / 进度条 / 继续读 + 收藏 / 章节列表懒加载 / 导出）；
- 点章节 → 阅读 Sheet（章名 / 第 i 总 n / 文件名 / 章节ID / 大小 / 缓存时间 / 解密后的正文 / 上一章 下一章 / 标记读到这一章 / 返回书籍）；
- 收藏与进度写 `Lib.setStr`（`tl_prog`、`tl_fav`、`tl_sort`、`tl_skin`、`tl_theme`），重启保留；
- 返回键行为：先关 Sheet → 再关抽屉 → 再回概览 → 都没有才 `finish()`（由 `uiBack()` 返回 true/false 决定）。

---



## 7. 验收清单（交付前逐条自测）

- [ ] `./build.sh` 一次通过，产出 `TomatoLib-v1.2.0.apk`，`apksigner verify` 打印证书；
- [ ] 装机后首屏能自动扫描；真机至少要能扫出 ≥1 个账号、≥1 本书（扫不到就先看诊断页 `[0]`–`[6]` 哪条 `目录可见`）；
- [ ] 断网状态下所有页面正常（无任何网络依赖）；
- [ ] 五个页面在 390×844 下无横向溢出；Sheet 内容超高时可滚动；
- [ ] 书架搜索、排序、筛选、收藏、进度五条链路都能落盘并在重启后恢复；
- [ ] 章节列表 393 章不卡顿、无空行、章号顺序正确；
- [ ] 导出后 `/sdcard/Download/番茄书库/<bid>/` 下有原缓存文件 + `meta.txt` + `chapters.txt`，文件管理器可见；
- [ ] 返回键三级退出逻辑正确；
- [ ] 五套皮肤切换后无布局跳动、无对比度不足的文字。

---

## 9. 交付物

1. 可安装 APK（覆盖安装、签名不变）；
2. 源码（保持上述目录结构）；
3. 一段简短说明：本次改了什么、有什么已知限制。

改动前先读代码再动手；任何与本文冲突的"优化建议"，以本文为准，先问再改。

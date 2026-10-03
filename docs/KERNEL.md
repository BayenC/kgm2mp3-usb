# KGM 内核来源与更新

核对日期：**2026-10-02**。日期按 Asia/Shanghai 表示；统计依据是上游官方 GitHub API 和发布记录。

## 提取范围及支持边界

本项目从 [OpenConverter](https://github.com/nowa277/OpenConverter) 的 Kotlin 解码实现提取 KGM 查表数据、逐字节变换逻辑和必要的格式识别参考，封装成独立的 `kgm-core` 模块并编译进本应用。没有嵌入上游整套应用或 APK。

来源固定在提交 [`e037007cc07b619434dc3b00e53b67a09842f849`](https://github.com/nowa277/OpenConverter/commit/e037007cc07b619434dc3b00e53b67a09842f849)，主要参考该版本的 [KgmDecoder.kt](https://github.com/nowa277/OpenConverter/blob/e037007cc07b619434dc3b00e53b67a09842f849/android/app/src/main/kotlin/com/openconverter/app/decoders/KgmDecoder.kt) 和 [FormatSniffer.kt](https://github.com/nowa277/OpenConverter/blob/e037007cc07b619434dc3b00e53b67a09842f849/android/app/src/main/kotlin/com/openconverter/app/decoders/FormatSniffer.kt)。Apache-2.0 授权、原始源文件、来源摘要及改动说明保存在 `third_party/OpenConverter/`；授权文本也包含在 APK 的 Java 资源中。

当前明确支持 **KGM/KGMA 魔数、版本字段 3、类型字段 1**。这是本地真实样本和独立参考算法已验证的组合；不能仅凭 `.kgm` 后缀认定所有加密版本可用。其他版本、KGG 或未知解密结果会报错，不会默认当作 MP3。

本项目增加了流式读取、取消检查、Long 偏移、檔头长度上限和严格格式识别。受支持的旧格式只需歌曲自身檔头及内置查表数据，不需要 Root 或酷狗私有数据库。解密、音讯转码和 USB 复制是分开的阶段。

本地样本的桌面验证得到 228 秒、44.1 kHz、双声道、16-bit FLAC，完整 PCM MD5 与 STREAMINFO 一致。其音讯结束后另有 30 字节，严格解码会报错；仅在完整 PCM 摘要匹配后，使用无损重新编码清理，再核对摘要并严格解码。源码和发布包不包含该样本。

## 上游是否频繁更新

官方仓库创建于 2026-06-15，最近一次提交为 2026-09-22。[仓库信息 API](https://api.github.com/repos/nowa277/OpenConverter)、[最新提交 API](https://api.github.com/repos/nowa277/OpenConverter/commits?per_page=1)

以下统计正式 Release 中**包含 Android APK**的发布次数；同一次发布包含桌面包时仍只计一次：

| 月份 | Android 发布次数 |
|---|---:|
| 2026-06 | 3 |
| 2026-07 | 2 |
| 2026-08 | 0 |
| 2026-09 | 5 |
| 2026-10，截至 10-02 | 0 |

9 月五个 Android 版本依次为 9/14 的 1.4.0、9/19 的 1.4.1、9/20 的 1.4.2、9/21 的 1.4.3、9/22 的 1.4.4。最新 Android 版本为 **1.4.4，发布于 2026-09-22**。[官方发布记录](https://github.com/nowa277/OpenConverter/releases)、[完整 Release API](https://api.github.com/repos/nowa277/OpenConverter/releases?per_page=100)

**近期应用版本发布密集，但 KGM 解码算法并非同样频繁更新。** 官方 KgmDecoder.kt 历史目前只有三次提交：6/19 的初始 Kotlin 移植、9/19 的大文件流式处理、同日的编译修复。9 月后续版本还涉及其他音乐格式、元数据及界面，因此不能把五次 APK 发布理解为五次新的 KGM 加密适配，也无法承诺上游未来会持续跟进所有酷狗变化。[KGM 文件提交历史](https://api.github.com/repos/nowa277/OpenConverter/commits?path=android%2Fapp%2Fsrc%2Fmain%2Fkotlin%2Fcom%2Fopenconverter%2Fapp%2Fdecoders%2FKgmDecoder.kt&per_page=100)、[流式处理提交](https://github.com/nowa277/OpenConverter/commit/7687a3ed412ca62df5d35720e109298d2807babf)、[编译修复提交](https://github.com/nowa277/OpenConverter/commit/d1b6df7ecfda168f752005e4dd5b7faa50bd341b)

## 本软件怎样更新内核

1.1 的“检查更新”固定检查 [BayenC/kgm2mp3-usb](https://github.com/BayenC/kgm2mp3-usb) 的正式 Android Release。上游出现相关修正后，由维护者检查差异、合并到本项目、用合成与真实样本验证，再发布新版本。

内核更新随**同一签名的新版 APK**覆盖安装。应用验证下载摘要、包名、版本号及签名，并由 Android 系统完成安装确认；不直接加载从网络下载的解码代码，也不安装 OpenConverter 的 APK。更新后的应用继续使用已有设置。未知加密版本需要经过实现和验证后才能声明支持。

1.1 改用 `com.kgm2mp3_usb.app` 与独立新签名，首次作为新 App 手动安装，重新设置和授权；上述保留设置的覆盖更新指 1.1 起后续使用相同新身份的版本。1.0 的包名和签名校验会拒绝 1.1，不能直接在旧 App 中覆盖升级。

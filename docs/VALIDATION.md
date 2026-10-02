# 验收记录

日期：2026-10-02（Asia/Shanghai）。记录实际执行结果，未以计划代替通过。

## 自动验证已通过

| 检查 | 结果 |
|---|---|
| KGM JVM 核心测试 | 16 项通过，含独立编码向量、分块、损坏/未知输入、取消、超 2 GiB 偏移及 FLAC STREAMINFO |
| Android app JVM 测试 | 14 项通过，含文件名规则、覆盖失败回滚、恢复与更新信任策略 |
| Android 13 / ARM64 仪器测试 | 13 项通过，21.672 秒；实际调用 APK 中原生 FFmpegKit |
| Python 构建辅助测试 | 9 项通过，含代理、官方 archive 选择；Shell/Python 语法通过 |
| Debug / 正式 Release APK | 构建成功，Release Lint 0 errors |
| 最终正式 APK | v2 签名验证通过；ZIP 16 KB 对齐检查通过；全部 arm64-v8a / x86_64 ELF PT_LOAD 对齐为 16 KB |
| 发布内容 | 无歌曲、签名私钥或本机配置；第三方授权原文已包含 |

Lint 的剩余警告主要是已固定依赖存在更新、target SDK 35、侧载文件管理权限及中文专用界面的资源/KTX 建议；没有通过关闭检查或基线隐藏错误。16 KB 检查是二进制与打包对齐，未声称在 16 KB 内存页设备上运行过。

## 用户真实 KGM

使用本地样本，不把歌曲放进测试 APK、源码或发布包。样本为旧版 KGM v3 type 1。

- 解密音频：44.1 kHz、立体声、16-bit FLAC，228 秒。
- 桌面校验：2,455 帧 CRC 全部正确；10,054,800 个样本；完整 PCM MD5 `3a98f6980c1423c19c3ba2c1daa52269` 与 STREAMINFO 一致。
- 音频后有 30 字节非音频尾部。先核验完整 PCM，再无损重编码整理，再核验样本数、音讯摘要及严格解码；损坏/截断样本明确拒绝。
- Android APK 成功生成四种格式，均严格完整解码通过；FLAC 再次验证完整 PCM MD5，源 KGM SHA-256 保持不变。

| 输出 | 编码 | 时长 | 文件大小 |
|---|---|---:|---:|
| MP3 | MP3 / 192 kbps | 228 秒 | 5,473,929 bytes |
| FLAC | FLAC / 无损 | 228 秒 | 26,860,253 bytes |
| WAV | PCM 16-bit | 228 秒 | 40,219,362 bytes |
| M4A | AAC / 192 kbps | 228 秒 | 5,625,432 bytes |

已用 Android 运行诊断确认 FFmpegKit 8.1.7 的 thread-local `exit_on_error` 未清理问题；校验/限定恢复显式 `-noxerror`，普通转码和最终完整解码保留 `-xerror`。诊断证明原样本默认 hash 失败，而重置后 hash 完整相符；恢复结果仍经严格解码及相同 PCM 摘要验证。

## Android 集成

- 原生库冷启动、普通音频转 MP3、真实 KGM 四格式、损坏输入、读取/原生转码取消、源文件变化拒绝。
- 在模拟器实际挂载的虚拟外接磁盘根目录执行生产 TransferEngine/UsbWriter：转换、读回 SHA-256、同名覆盖；时长由 2 秒正确替换为 4 秒；源文件保留；复制取消保留旧歌并清理暂存/日志。
- 实际 Android PackageManager 验证安装 APK、`.part` 后缀暂存包、错误包名和损坏安装包；拒绝同版本包。
- 更新策略测试拒绝非可信 HTTPS 主机、异常端口、错误/歧义 checksum、降级与不同/缺失签名。尚未建立在线 Release 仓库，不能把在线升级下载视为已验证。

## 正式 APK 手动流程

使用最终 `dist/music-transfer-1.0.0-universal.apk` 验证：首次文件授权跳转并开启正确系统开关；返回自动读取歌曲；设置四种格式排版正常；选定模拟外接根目录；点右侧圆圈后显示「已选 1 首」；点「转换并转移」并允许系统通知；前台服务完整转换和写入，主界面显示「成功 1 首」。根目录 MP3 为 228 秒、44.1 kHz、立体声、5,473,929 bytes。手机 KGM 仍在列表。

切换「USB 歌曲」后自动显示刚转移的 `validation-sample.mp3`，读到 1 首。界面截图见 [主界面](screenshots/home.png)、[设置](screenshots/settings.png)、[完成结果](screenshots/completed.png)、[USB 歌曲](screenshots/usb.png)。截图中的 Virtual SD card 是模拟器挂载磁盘，不是实体 U 盘。

正式 APK SHA-256：`ec43ea8fab3e53fec7319bade8709ffbfbee56df9a379e2d677ddf049293e4a0`。

## 需要实体设备最终确认

- 小米 10S / HyperOS 的 OTG 挂载和 USB 根目录读写。
- 手机上的下载目录实时变化及熄屏长批次行为。
- USB 拔插过程中硬件与文件系统的实际恢复行为。
- 车机的 MP3 播放、中文歌曲名、USB 文件系统兼容性。
- Android 8–12 及 14 以上的实际运行；最低 SDK 26 已在编译/API 检查验证。

模拟器与 JVM 测试可以覆盖软件逻辑，但不能证明真实 USB 硬件或车机已经通过验收。

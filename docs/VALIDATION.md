# 1.1.0 验收记录

日期：2026-10-03（Asia/Shanghai）。状态：本地可安装 APK 完成，最终界面回归通过；SAF 和实体设备项仍待补，尚未发布 GitHub。

## 应用与构建身份

| 项目 | 本次结果 |
|---|---|
| 套件名称 | `com.kgm2mp3_usb.app` |
| 版本 | `1.1.0`，`versionCode = 2` |
| 最低 / 目标 Android API | 26 / 35 |
| 正式包架构 | 仅 `arm64-v8a` |
| 新签名证书 SHA-256 | `74aed6fe1c22a17bde561ae47c7ea2d63390921e86d2fc4725a65f67346e06d0` |
| 最终正式 APK 大小 | 23,484,946 bytes（23.48 MB） |
| 最终交付 APK SHA-256 | `6a62e3c3cbf9bb61567e9992078d3d057c9467e61aab02d97c7406f380ff9c41` |

最终正式包较 1.0 的 48,219,532 bytes 缩小约 **51%**。缩小主要来自移除正式手机包里的 x86_64 音讯库；四种输出格式保留。此大小和 SHA-256 对应圆润音符及位置微调后重新构建的 APK。

1.1 新身份已在 Android 13 ARM64 模拟器独立安装，与 1.0 共存。新安装默认 MP3，实际从 `/storage/emulated/0/kgmusic/download/kgmusic` 扫描到 KGM 样本；首次授权不继承 1.0。

## 已完成的自动化验证

| 检查 | 结果 |
|---|---|
| `kgm-core` JVM | 16 项通过；0 失败、0 错误、0 跳过 |
| App JVM | 38 项通过；0 失败、0 错误、0 跳过 |
| JVM 合计 | **54 项通过** |
| Python 构建辅助 | **9 项通过** |
| 最终 Release 构建 | 图标位置微调后重新构建通过 |
| 最终 Release Lint | **0 Error，55 Warning**；并非无警告 |
| Android 13 ARM64 instrumented tests | 默认 compatibility 配置下 **22 项通过**，23.895 秒 |
| 真实 SAF 根授权测试 | **1 项因模拟器授权环境阻塞，未通过** |

JVM 计数亲读 `kgm-core/build/test-results/test/TEST-*.xml` 与 `app/build/test-results/testDebugUnitTest/TEST-*.xml`。图示修订前功能验证基线包的 Android 复验日志为本机 `/private/tmp/kgm2mp3-android-tests-1.1-final.log`，结果 `OK (22 tests)`；23 项完整测试首次运行中，22 项通过，SAF 1 项失败于初始授权步骤。

`connectedReleaseAndroidTest` 在离线构建环境缺少 UTP 依赖，改为通过 adb 执行已构建的同一 Release 测试 APK。该运行方式实际执行下面 22 项，并非以跳过测试代替；SAF 项另行保留阻塞记录。

### Android 22 项通过范围

- **原生转换与恢复 7 项**：实际载入音讯原生库，真实 KGM 转 MP3 / FLAC / WAV / M4A，源文件保留，损坏输入拒绝，选歌后文件变化拒绝，取消不进入编码，严格验证和无损恢复状态复位。
- **签名更新 fixture 2 项**：同新套件、较高 versionCode、同新签名生成系统安装 Intent；较高版本但旧签名明确拒绝。此项验证更新安装包校验与 Intent，不等同于已执行未来正式版本的覆盖安装。
- **真实 APK archive 5 项**：当前版本拒绝作为升级，`.part` 文件仍按真实签名元数据检查，其他 App 和损坏 APK 拒绝，实际原生库 ABI 与模拟器相符。
- **GitHub 在线检查 1 项**：通过正式 `UpdateManager.check(callback)` 真正访问固定 `BayenC/kgm2mp3-usb`，当前公开 `v1.0.0` 不被误报为 1.1 更新；显式启用 `runLiveUpdateCheck=true`，网络失败会使测试失败。网络仅为测试模拟器设置主机代理，产品未加入代理配置。
- **USB 删除 5 项**：仅删除已选根目录歌曲，保护手机原歌、未选歌曲和子目录；变化快照拒绝；取消保留未删歌曲；监听器重接不重复执行；转移与删除互斥；中断不自动续删；真实卸载虚拟移动卷后停止删除，重挂后原文件保留。
- **USB 转移 2 项**：真实转码、复制、同名事务覆盖；复制中取消保持原有目标歌曲并清理临时文件。

这里的移动存储由模拟器公开虚拟卷提供，卸载确实发生在 Android 存储系统；它不替代实体 USB OTG 拔插测试。

### SAF 阻塞与未验证范围

`UsbDeletionIntegrationTest.realSafGrantEnumeratesExactRootUriAndProtectsNestedFiles` 要求系统文件选择器授予真实、持久化的 USB 根目录读写权限。Android 13 把本次虚拟 SD 根目录视作可靠存储，文件选择器显示 “Can't use this folder”，根目录的 “USE THIS FOLDER” 按钮禁用，无法取得初始 grant。

单独重试初始授权仍失败；临时 compatibility flag 调整也未解除限制，随后已恢复系统默认配置。产品仍保持 target API 35、原有权限与根目录限制；测试没有用子目录、伪造 grant 或假 provider 冒充成功。

因此 **SAF 根目录删除链路尚未通过实际运行验证**。通过的直接路径测试、JVM 边界策略和代码审查不能代替它，需在允许授予根目录的真实 USB / 手机环境补测。

### 最终 Lint 警告

亲读 `app/build/reports/lint-results-release.xml`：总计 55 条 Warning，没有 Error。

| 类别 | 数量 |
|---|---:|
| `UseKtx` | 35 |
| `SetTextI18n` | 6 |
| `OldTargetApi` / `AndroidGradlePluginVersion` / `GradleDependency` / `NewerVersionAvailable` | 8 |
| `ScopedStorage` | 1 |
| `ChromeOsAbiSupport` | 1 |
| `DataExtractionRules` | 1 |
| `ObsoleteSdkInt` | 1 |
| `MonochromeLauncherIcon` | 2 |

警告涉及 KTX 写法、中文界面资源、依赖更新提示、广泛文件权限、正式包只含 ARM64、备份规则和图标资源。这里只记录检查结果，没有把警告计为通过或据此改变当前依赖、权限和发布目标。

## 最终 APK 签名与对齐

最终正式 APK 的 `aapt2` 包身份检查为 `com.kgm2mp3_usb.app`、versionCode 2、versionName 1.1.0、min API 26、target API 35。签名证书 SHA-256 与上表一致，RSA 3072，APK v2 签名验证通过。

`zipalign -c -P 16` 检查通过。仅包含 10 个 ARM64 原生库，均为未压缩 ELF64；全部 `PT_LOAD` 的对齐为 16384，且 `(offset - vaddr) % 16384 = 0`。这些检查验证 APK / ELF 对齐，不等同于已在 16 KB 内存页实体手机运行。

## 最终正式包界面与操作验证

以下操作测试在图示修订前的功能验证基线 APK（SHA-256 `1addb2fcbf8794486fd5508b5f7cbed86b88d345ef058c10282e81f85fbd9913`）完成。54 项 JVM、9 项 Python 与 22 项 Android 测试也属于该基线的记录。本轮只调整图标资源，已比对当前包与基线的全部 DEX 和 10 个 ARM64 原生库，字节内容相同；没有把原功能测试标成在新图示包上重跑。

- **小屏与大字体**：320 dp × 569 dp、fontScale 1.3 的紧凑界面中，四个输出格式完整可见，歌曲标题和选择圆圈可见且可逐首选择，底部「转移」可点击。任务运行时取消入口可见；歌曲格式／大小文字的下缘可能需要滚动，没有把每行全高都标为始终可见。
- **选择独立与后台返回**：进入后台再返回，手机已选 1 首、USB 已选 0 首保持；两个分页面的勾选互不影响。USB 页没有全选入口。
- **删除确认与取消**：界面逐首勾选 `ui-selected` 一首，确认框显示 1 首及不可撤销说明；取消后文件保持完整。再次确认后只有所选文件删除，未选的 `ui-keep` 和 `validation-sample` 保留，手机 KGM 的 SHA-256 前后相同。
- **小屏实际转移**：在同一小屏界面选择 MP3 并点「转移」，最后显示成功 1 首。输出为 5,473,929 bytes；`ffprobe` 验证 MP3、44,100 Hz、双声道、228 秒；手机 KGM 的 SHA-256 前后相同。
- **格式保存**：改为 FLAC，重新启动 App 和同签名 `install -r` 重新安装后仍保存 FLAC，随后恢复 MP3。
- **主页与设置**：主页不再显示 USB 状态提示条或底部格式／保留／覆盖提示；设置没有输出格式或高级设置。首次 MP3、新路径扫描样本、新旧 App 共存均已验证。
- **按压反馈**：按钮按住时背景由 RGB `(232, 239, 236)` 变为 `(203, 221, 213)`。

操作截图来自上述功能验证基线包；桌面图标截图已更新为本轮安装后的实际画面。保存目录为 `docs/screenshots/1.1/`：

| 画面 | 截图 |
|---|---|
| 手机歌曲主页 | [home.png](screenshots/1.1/home.png) |
| USB 逐首选择，无全选 | [usb.png](screenshots/1.1/usb.png) |
| 删除数量与不可撤销确认 | [delete-confirm.png](screenshots/1.1/delete-confirm.png) |
| 简化设置 | [settings.png](screenshots/1.1/settings.png) |
| 320 dp / fontScale 1.3 | [small-font.png](screenshots/1.1/small-font.png) |
| 小屏任务运行与取消入口 | [small-busy.png](screenshots/1.1/small-busy.png) |
| 小屏成功转移结果 | [small-result.png](screenshots/1.1/small-result.png) |
| 仅所选歌曲删除后的结果 | [delete-result.png](screenshots/1.1/delete-result.png) |
| 桌面图标 | [launcher.png](screenshots/1.1/launcher.png) |

本轮按用户参考图改成连续圆润双音符，保留原青绿 `#16776B` 与白色配色；再向左微移 1.25 个 viewport 单位（580 像素预览约 10 像素），大小不变。预览四角 alpha 为 0，不包含参考图的白色外角。正式 APK 已重新构建，Lint 0 Error / 55 Warning、同新签名 v2 验证、ZIP 16 KB 对齐及同身份 `install -r` 安装通过；新图标已在 Android 13 Launcher 实际显示并可视核对，圆形遮罩无切角。SVG / PNG 方形预览也已查看。

预览：[logo-1.1.png](assets/logo-1.1.png)；矢量原图：[logo-1.1.svg](assets/logo-1.1.svg)。

## 实体设备仍需确认

- 小米 10S / HyperOS 真实 OTG 拔插、直接路径和 SAF 根目录读写、删除。
- 熄屏长批次、实际车机播放、中文歌名和 USB 文件系统兼容性。
- Android 8–12、14 以上及 16 KB 内存页设备的实际运行。

上述未验证项保留为限制，没有标成通过。1.0 历史验证见 [VALIDATION-1.0.md](VALIDATION-1.0.md)。

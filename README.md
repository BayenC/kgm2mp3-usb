# 音乐转移（kgx2mp3）

一个面向家庭使用的 Android 音乐转换与 USB 复制工具。默认扫描酷狗概念版下载目录，勾选歌曲后转换为 MP3 并复制到 USB 根目录，保留手机原文件。

## 使用

1. 安装 `dist/` 中的已签名 APK。
2. 首次打开按提示授予文件访问权限；也可以在设置中选择歌曲文件夹。
3. 插入 USB，确认界面显示正确的目标。首次使用或系统不支持直接读写时，在设置中选择 **USB 根目录**。
4. 选择歌曲或点全选，然后点 **转换并转移**。

默认手机目录是 `/storage/emulated/0/kgmusic/download`，可以修改。设置中可选择 MP3、FLAC、WAV、M4A。解密后的音频已经符合目标格式时不重新编码。同名输出默认覆盖，失败时保留可重试状态，手机源文件始终保留。

## 格式与兼容性

- 最低 Android 8（API 26），当前转码库包含 `arm64-v8a` 与 `x86_64` 原生库；小米 10S 使用 `arm64-v8a`。
- KGM 核心按文件内容识别旧版加密，KGMA 和 `.kgm.flac` / `.kgma.flac` 文件名共用同一解码流程。
- KGG 与未知加密版本不属于本版支持范围。
- 普通 MP3、FLAC、WAV、M4A 等音频可按目标格式转换或直接复制。
- Android 各版本的文件授权方式不同；移动存储的可见性、可写性和目录授权也取决于手机系统、OTG 与文件系统。
- Android 的公开存储接口把 USB 和可移除 SD 卡都列为移动存储，不能可靠地把 USB 设备身份对应到卷 UUID。软件只在检测到 USB 大容量存储设备且仅有一个移动存储卷时自动选择目标；多个卷时由用户选择，已明确选择的目标会被记住。仅有 SD 卡时不会自动选择，首次设置应确认目标确实是需要放歌的 USB。

## 构建

工程使用 JDK 17、Gradle 8.13、Android SDK 35 / Build Tools 35.0.0。Android Studio 可直接打开项目。

标准环境：

```sh
./gradlew :kgm-core:test :app:testDebugUnitTest :app:assembleDebug
```

本机隔离构建（当前下载脚本面向 Apple Silicon Mac，无需在系统安装 Java/Android Studio）：

```sh
python3 tools/bootstrap_build.py /private/tmp/kgx2mp3-toolchain
python3 tools/prepare_android.py /private/tmp/kgx2mp3-toolchain
python3 tools/create_signing_key.py
bash scripts/gradle_local.sh :kgm-core:test :app:testDebugUnitTest :app:assembleRelease
```

`local.properties` 指向本机 SDK，不能提交。Gradle wrapper 与普通 Android Studio 构建不依赖上述临时目录。

上述脚本继承已有代理设置：优先使用 `HTTPS_PROXY`，没有时将 `HTTP_PROXY` 或 `ALL_PROXY` 同时用于 HTTPS 下载，并把相应代理设置明确传给 Gradle 和 sdkmanager。可使用大小写形式；脚本不打印代理用户名或密码。例如本机代理为 `127.0.0.1:1082` 时，可以在执行构建前设置 `HTTP_PROXY=http://127.0.0.1:1082`。Gradle 8.13 的下载 SHA-256 同时固定在 bootstrap 脚本及 wrapper 配置中。

APK 构建只安装平台、Build Tools 和 platform-tools。模拟器及体积较大的系统镜像是独立的可选步骤，下载失败不会撤销已经完成的 SDK 构建配置：

```sh
python3 tools/prepare_emulator.py /private/tmp/kgx2mp3-toolchain
```

也可给 `prepare_android.py` 加 `--emulator`，它会先完成构建配置，再开始可选下载。模拟器脚本按主机 CPU 选择官方稳定版归档，并校验下载摘要及二进制架构；Apple Silicon 使用 `aarch64` 模拟器和 `arm64-v8a` 系统镜像。

构建脚本的离线检查：`python3 -m unittest discover -s tools/tests`。

## 发布和更新

第一版通过相同签名的 APK 覆盖更新。高级设置可填写公开 GitHub 仓库 `owner/repo`，更新检查读取该仓库的最新正式 Release。

Release 应提供一个 APK 和同名 `.sha256` 文件。安装前验证 SHA-256、包名、较高的版本号及与当前软件相同的签名证书。用户仍通过 Android 系统确认安装。

没有配置发布仓库时会显示需要设置更新地址，不会访问虚构的地址。私有仓库的源代码可用于版本管理，但手机匿名更新需要公开可读取的 Release 来源；不要在 APK 内嵌 GitHub 令牌。

**务必妥善备份 `signing/`。** 它包含本项目独立生成的签名密钥和密码，仅保存在本机并被 `.gitignore` 排除。丢失后无法以同一身份覆盖更新。签名材料、真实音乐样本、转出的音乐和本机配置都不能上传到源代码仓库。

## 源码与第三方组件

- `kgm-core/`：从 OpenConverter 的 Kotlin KGM 实作抽取并适配的流式解码模块，保留上游授权及来源记录。
- `app/`：Android 界面、存储授权与扫描、转码、USB 复制和更新。
- 音讯转换使用固定版本的社区维护 FFmpegKit 音频构建，见 `third_party/` 和 `docs/THIRD_PARTY.md`。

真实歌曲只用于本地验证，不包含在源码或发行版内。验收记录和未验证项目见 [docs/VALIDATION.md](docs/VALIDATION.md)。内核提取范围、上游更新频率与升级方式见 [docs/KERNEL.md](docs/KERNEL.md)。

# 第三方组件

## KGM 解码模块

来源：<https://github.com/nowa277/OpenConverter>，`android/app/src/main/kotlin/com/openconverter/app/decoders/`。模块来源记录与 Apache-2.0 授权见 `third_party/OpenConverter/`。

仅抽取 KGM 相关解码、格式识别与必要接口；本项目增加流式取消、严格输入/输出校验及针对文件损坏的保护。KGM 算法参考项目链见模块来源记录。

## FFmpegKit 音频构建

固定依赖：`dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.7`。显式依赖 `com.arthenica:smart-exception-java:0.2.1`，供 FFmpegKit 的原生初始化和异常报告使用。

发行 AAR SHA-256：`9b277de25934594635a495e4fda164b902309219a2f538827f5ecc7ed77229d6`。APK 中保留本地开源许可查看页及相关授权全文。

维护仓库：<https://github.com/ffmpegkit-maintained/ffmpeg>。音频版包含 MP3 LAME 编码器及音频相关依赖，不引入视频编码的 GPL 扩展。具体版权、授权和源码地址以发行 AAR 内的第三方通知及维护项目对应版本为准；发行 APK 时保留这些通知。

FFmpegKit 原项目已退役，本项目不使用已撤回的官方 Maven 二进制。维护仓库的发布与源码仍需在升级依赖时重新核对。

## Smart Exception

FFmpegKit 初始化和本地异常报告使用 `com.arthenica:smart-exception-java:0.2.1`，及其传递依赖 `com.arthenica:smart-exception-common:0.2.1`。两者的发布 POM 标注 BSD 3-Clause License。

授权原文与版权声明取自官方 <https://github.com/tanersener/smart-exception> 的精确 `v0.2.1` 标签，固定提交为 `e77d0004790d39e5e57888ae8e7292f85711d12f`。未经修改的 `LICENSE`、来源记录及两份 POM 保存在 `third_party/SmartException/`，并将授权原文随 APK 放入 `assets/licenses/SmartException-BSD-3-Clause.txt`，可在「设置 → 关于 → 开源许可」离线查看。

## AndroidX / Kotlin

AndroidX Core、DocumentFile 以及 Kotlin 标准库按各自发行包附带的授权使用，固定版本由 Gradle 配置记录。

## 本项目

自有实现采用 Apache License 2.0。第三方组件保留各自授权。本项目不附带歌曲文件、酷狗客户端或酷狗私有数据库。

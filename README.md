# CarDeck-BMW

CarDeck-BMW 基于开源项目 [CarDeck](https://github.com/shaonialife/cardeck)，针对宝马 iDrive 7 车机的无线 CarPlay 连接与使用体验进行优化。项目运行在 Android 手机上，通过 CarPlay 将手机应用界面投送至车机。

CarDeck-BMW is an experimental Android project based on [CarDeck](https://github.com/shaonialife/cardeck), optimized for BMW iDrive 7 head units. It builds on the upstream project's wireless CarPlay sender and adds BMW-specific compatibility and interface work.

## 项目状态

当前源码对应 **V33 实验性诊断版**，仍在开发和实车验证中。宝马车型、车机版本和手机系统存在差异，连接、声音、网络、导航和按键功能的表现可能不同。请在停车时完成配置和操作，并保留原车导航等备用方式。完整说明见 [免责声明](DISCLAIMER.md)。

## 主要功能与优化

- 针对宝马车机的无线连接、会话和音频兼容性进行调整。
- 优化横屏桌面、地图和媒体界面的显示；支持车机地图与手机导航切换，并提供百度地图巡航入口。
- 增加旋钮、方向盘及车机快捷键相关的输入处理、诊断和校准功能；具体可用按键取决于车机上报的信号。
- 支持媒体信息及歌词显示，可选择使用在线歌词查询。
- 提供网络连接、声音和输入等诊断信息，便于排查兼容问题。

## 使用条件

- 推荐 Android 14 或更新版本的手机。项目的最低构建版本为 Android 12（API 31），部分功能依赖较新系统。
- 安装并启动 [Shizuku](https://shizuku.rikka.app/)，按其说明授权 CarDeck-BMW；虚拟显示、输入注入等功能依赖 Shizuku。
- 支持无线 CarPlay 的宝马 iDrive 7 车机；实际可用性取决于手机、车机和系统配置。

在线歌词是可选功能。开启后，在需要查询时会向 LRCLIB、网易云音乐公开曲库发送歌名和歌手；专辑与时长用于本地匹配。网络重试选项可在应用中设置。

## 从源码构建

需要 JDK 17、Android SDK 及 API 34 平台。项目提供 Gradle Wrapper（8.10.2），首次构建需要下载 Gradle 和依赖。

```bash
# macOS / Linux
./gradlew :app:assembleDebug

# Windows
gradlew.bat :app:assembleDebug
```

构建完成后，debug APK 位于 `app/build/outputs/apk/debug/`。此构建的应用 ID 为 `io.github.shaonialife.cardeck.bmwprobe`，与上游正式版的应用 ID 分开。

项目保留上游的 release 构建配置：未配置签名文件时会使用 debug 签名，且 release 应用 ID 为 `io.github.shaonialife.cardeck`。如需正式发布，请先配置自己的签名、检查应用 ID 和升级关系；不要提交密钥或签名密码。可参考 [keystore.properties.example](keystore.properties.example)。

## 致谢与许可

本项目基于 [shaonialife/cardeck](https://github.com/shaonialife/cardeck) 开发，源码基线为上游提交 [`09eebb0`](https://github.com/shaonialife/cardeck/commit/09eebb0f2a51c961c101e8fb49134ca7769b2d94)，感谢原作者及贡献者。上游版权声明为 **Copyright (c) 2026 lizhihua**，项目依照 [MIT License](LICENSE) 开源。

本项目是独立的非官方实验项目，与宝马、Apple 及相关品牌无关联或认证。CarPlay、AirPlay 等名称及商标归各自权利人所有。

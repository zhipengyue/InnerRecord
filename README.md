# InnerRecord

纯 Android Java 录音项目，不再依赖 Godot。当前安装包：[InnerRecord-Android-v1.1.0-debug.apk](releases/InnerRecord-Android-v1.1.0-debug.apk)。原 Godot 工程仅在本机的 `legacy_godot/` 存档，不参与构建或版本提交。

## 功能

- 首页录制按钮；录制时可暂停、继续、停止，停止后进入录音列表。
- 设置中选择系统内录（默认）或通话／麦克风模式，选择 WAV（默认）或 MP3。
- 列表直接播放，长按可重命名、查看保存位置、另存为或删除；右上角返回首页。
- Android 10+ 内录采用 MediaProjection 与 AudioPlaybackCapture，并在前台服务中录制。另存为使用 Android 系统文件选择器；原文件保留。
- 设置中可开启悬浮按钮，授予“显示在其他应用上层”权限后，可从其他应用快速开始、暂停、继续或停止录制。开启期间会有常驻通知；关闭开关即移除悬浮控件。首次开始内录时仍需通过 Android 系统授权。

录音保存在应用私有目录 `files/recordings`。内录仅能采集允许被录制的应用音频；通话模式使用麦克风，远端声音受系统和设备限制。Android 不可靠地提供具体音频来源 App，因此文件名标注“系统播放”或“麦克风”。

## 构建

需要 Android SDK（含 API 35）、JDK 17 和网络访问。运行 `./tools/build-apk.sh`，输出位于 `releases/`。安装包是调试签名，适合直接安装测试；发布到商店前应使用自己的发布密钥重新签名。

MP3 编码使用 [TAndroidLame](https://github.com/naman14/TAndroidLame) 的本地 AAR 与原生 LAME 库。为避免该旧 AAR 的清单引入无关权限，项目中仅调整了其 AndroidManifest，编码实现保持原样。该库采用 GPL-3.0-or-later 许可证，分发时须遵守其许可条款。

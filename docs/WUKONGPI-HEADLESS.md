# WuKong Pi 无界面桥接：实施与更新

适用现有 H3 / 512 MiB / Android 13 userdebug 系统。DiPlay 接收 iPhone，CarProjection 输出 CarLife；两份 APK 必须成对更新。当前保留 XR819 和 RTL8761BTV UART2，未引入外部路由器。

## 已实现的五部分

| 部分 | 实施内容 |
| --- | --- |
| 专用精简构建 | 独立 board 模块，不编入普通 DiPlay Activity、Compose 页面、组件与 BYD 界面；只保留 ARM32 库。两端 R8 与资源裁剪，签名保持现有测试密钥 |
| 媒体优化 | 默认只转发 H.264 与 PCM；不创建本地视频解码器和 AudioTrack，不使用录屏、无障碍或悬浮窗。协议 TRACE 在构造字符串前关闭；音频队列限制 64 包、1 MiB，日志内存 256 KiB、磁盘 2 MiB × 3，写盘独立且有界 |
| 后台运行 | 两端前台服务拥有会话，开机恢复、USB 重连、有限退避、旧连接关闭完成后重建；预览 Activity 关闭仅释放预览。保持 Android 框架和默认逻辑显示 |
| 免点击配置 | vendor init 启动窄接口 root 助手，按固定包/UID 授予运行时、USB Host、CarLife Accessory 与 VPN 权限；停用 MTP。限时蓝牙配对及物理显示电源控制；iPhone 首次允许仍在手机上操作 |
| Web 管理 | 状态、开始/停止/分端重连、无线与音频配置、已配对手机、120 秒配对窗口、移除蓝牙配对、有限日志导出、显示输出开关、设备重启。令牌鉴权、来源校验、2 个请求工作线程及 8 个排队请求；配置校验、版本冲突检测、60 秒未确认回滚、进程重启恢复旧配置 |

暂停 Web 页面轮询或关闭浏览器不会关闭投屏。Web 不传视频与 PCM，也不提供任意 shell、ADB 命令、文件访问或应用安装接口。

自动重连与配置恢复属于已编写并检查的功能；真实 UART、USB、Wi-Fi 和车机可靠性仍须在板上验收。不得把通过编译当作无线 CarPlay 已跑通。

## 下载

- 本仓库 `integration/wukongpi-headless` 的 **WukongPi headless bridge** 成功工作流：`DiPlay-wukongpi-apk`，解压取得 `board-debug.apk`。
- `Aki894/CarProjection` 的 `wukongpi-headless` 成功工作流：`CarProjection-wukongpi-apk`，取得 `app-wukongpi.apk`。
- `Aki894/glodroid_manifest` 的 `wukongpi-bringup`：设备补丁包含 root 助手启动文件。现有成功启动的内核、DTB、蓝牙固件保持使用。

## 先构建 vendor 更新

root 助手需要 vendor init 文件，新 APK 不能凭空建立持久 root 启动服务。在学校服务器运行：

```bash
source /data/ccc/wukong-build/env.sh
cd /data/ccc/wukong-build/port
git pull --ff-only
set -o pipefail
bash scripts/build.sh /data/ccc/wukong-build/aosp 4 \
  2>&1 | tee /data/ccc/wukong-build/logs/build-headless.log
```

下载新 `super.img`，进入已经验证可用的 **userspace fastboot**，确认 `getvar is-userspace` 为 `yes` 后，仅刷 `super.img` 并重启。此次功能不要求换当前正常的 `boot.img`；不格式化 userdata 或 metadata。

```powershell
& $Fastboot getvar is-userspace
# 只有输出 yes 后执行；保留此前检查退出码的 fb 函数。
fb flash super super.img
fb reboot
```

两端应用数据、系统已有蓝牙配对和 iPhone 记录应保留。普通 ADB 连接与车机 AOA 属于不同 USB 状态，桌面更新期间使用当前可用的 ADB 链路。

## Windows 安装

下载本仓库 `scripts/install-wukongpi.ps1` 与两个 APK。系统启动完成后：

```powershell
.\install-wukongpi.ps1 -Adb $Adb -DiPlayApk "C:\你的目录\board-debug.apk" -CarProjectionApk "C:\你的目录\app-wukongpi.apk"
```

安装器先导出两份旧 APK，然后 `install -r -d`，不卸载、不清除应用数据；确认 root 助手文件存在，再启动后台与 USB Web 转发。助手初始化权限期间，后台会等待并重试。

```powershell
& $Adb shell cat /data/user/0/com.shihab.diplay.hudtest/no_backup/web-token
Start-Process "http://127.0.0.1:8765"
```

将令牌输入网页；不要把令牌放入网址或共享日志。默认 localhost 经 USB ADB 访问。允许局域网访问需要显式确认配置并重启管理服务；后续在 CarPlay 自建 AP 的地址上验收，不需要车库路由器。

## 可选预览与屏幕

正常运行不打开预览，不常驻 scrcpy 虚拟屏。诊断需要时才启动：

```powershell
& $Adb shell am start -n com.shihab.diplay.hudtest/com.shilapi.xcertplay.board.BoardPreviewActivity
```

退出预览释放视频解码器；查看 Web 中 `videoDecoders` 应回到 0。Web 的显示输出关闭走 Android 13 SurfaceControl 物理显示接口，保留 SurfaceFlinger/SystemUI/逻辑显示和会话唤醒锁。HWC 是否归还所有缓冲，以及灭屏无线是否稳定，必须实测。

默认无线关闭，先恢复已成功的有线基线，再测试无线。当前 XR819 不支持 5 GHz；`LOCAL_ONLY_HOTSPOT` 和 `WIFI_P2P` 的真实适配留给板上测试，`MANUAL` 只使用已经存在的 AP，不能自动创建热点。

媒体音频默认 USB 路径；保留 TTS 兼容开关与现有采样率选项，不擅自改车机已验证参数。无界面版不在桥接失败时回退开发板扬声器。麦克风上行默认关闭，需确认最终硬件链路后开启。

## 回退

安装器显示备份目录。需要回退时使用 `scripts/rollback-wukongpi.ps1`：

```powershell
.\rollback-wukongpi.ps1 -Adb $Adb -BackupDirectory "C:\你的备份目录"
```

这会关闭持久 root 助手并装回两份旧 APK，保留应用数据。再次使用 board APK 时，安装器自动重新启用助手。普通 APK 不包含 board root 入口，不要单独混装。

## 验收顺序（实施与 CI 检查完成后）

1. 成对更新，确认 boot_completed、RUNNING_UNLOCKED 与 Web 能访问；关闭浏览器后后台仍在。
2. 有线 iPhone：不用点击 Android 权限框，重复插拔后能够恢复，`videoDecoders=0`，比较 PSS、堆、帧计数与日志丢弃数。
3. 开启无线：明确进入 120 秒配对窗口，手机首次确认；记录热点地址、频段与会话状态；断电/重启/手机离开再回来后恢复。
4. 测试配置拒绝、未确认 60 秒回退、服务重启回退、关闭预览及显示输出后媒体继续；停止后资源释放。
5. 最后进车验证 CarLife 视频、USB 媒体、TTS、RemoteTouch 旋转/滑动/机械确认/Back/拖动及长时间稳定性。真实车机测试前不重写已有输入协议。

当前方案针对既有 permissive userdebug 开发系统；生产 enforcing SELinux 与独立权限签名不是本轮构建完成的项目。

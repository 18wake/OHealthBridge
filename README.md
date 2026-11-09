# OHealthBridge

**让 OPPO / 一加 / realme 手表在非 OPPO 手机上，也能与手机同步勿扰状态和闹钟。**

OPPO 健康把手表联动能力绑在 ColorOS 私有接口上。换到小米 HyperOS、荣耀 MagicOS、三星 One UI 或原生 Android 后，手表收不到手机的勿扰变化，手机闹钟也不会在手表上响。本模块通过 LSPosed 在运行时补齐这些能力——不修改 OPPO 健康，不重打包，不侵入系统。

| | |
|---|---|
| 运行方式 | [LSPosed](https://github.com/LSPosed/LSPosed) 或兼容的现代 libxposed 框架，作用域已内置 |
| 目标应用 | OPPO 健康 `com.heytap.health` 6.7.x – 6.9.x |
| 系统要求 | Android 8.0+，已 Root |
| 下载 | [Releases](../../releases) |

## 功能

- **勿扰同步（双向）**
  - 手机开启勿扰 → 手表数秒内跟随
  - 手表开启勿扰 → 手机跟随（需授权勿扰访问或开启 Root 兜底）
- **闹钟同步**
  - 手机闹钟响铃 → 手表同步响铃
  - 手表上关闭 / 贪睡 → 手机闹钟同步停止 / 贪睡
  - 默认按 `notification.category == alarm` 识别，兼容所有闹钟应用；也可切换为包名白名单
- **品牌自适应**：OPPO 系手机自动闲置（原生即可用），其他品牌自动启用

## 安装

1. 安装 APK，在 LSPosed 中启用模块（作用域已内置 OPPO 健康）
2. 重启手机，或强停后重新打开 OPPO 健康
3. 打开 OHealthBridge → **勿扰权限 → 授权勿扰访问**（需 Root，仅需一次）
4. 系统设置 → 特殊应用权限 → **通知使用权** → 授予 **OPPO 健康**
5. 断开重连一次手表蓝牙，使联动能力重新上报
6. 建议将 OPPO 健康设为自启动、电池无限制，避免后台被杀

> OPPO 健康内不会出现“勿扰同步 / 闹钟联动”开关，功能为静默生效，直接测试行为即可。

## 测试

- **勿扰**：手机下拉开勿扰 → 看手表是否跟随；手表上开勿扰 → 看手机是否跟随
- **闹钟**：手机定一个 1 分钟后的闹钟 → 响铃时看手表是否同步；在手表上点关闭或贪睡验证回控

## 排查

打开 OHealthBridge → **状态 → 运行状态**，可查看模块加载、各功能挂载与最近事件。

- 无任何记录 → 检查 LSPosed 作用域是否包含 OPPO 健康，重启 OPPO 健康后再试
- 勿扰授权失败 → 保持 **Root 兜底** 开关开启即可
- 手表不响铃 → 检查 OPPO 健康通知使用权、手表连接状态

日志：

```
adb logcat -s OHealthBridge
```

## 构建

免 Gradle，Windows + JDK 17 + Android SDK（platform-34 / build-tools 34）：

```bash
cd OHealthBridge && bash build.sh
```

产物为自签名 APK，仅供自用；正式分发请自行更换签名证书。

## 已知限制

- “手表 → 手机”方向的勿扰写入依赖勿扰访问授权（Root 授予）或 Root 兜底；“手机 → 手表”方向无需任何权限
- 个别 ROM 可能拒绝 `cmd notification allow_dnd`，此时使用 Root 兜底
- 锁屏步数小部件不在实现范围内

## 免责声明

本模块仅供个人学习与研究，通过运行时 Hook 增强自有设备的互联互通能力，不修改、不分发 OPPO 健康应用本身。使用产生的一切后果由使用者自行承担。

## 许可

[MIT](LICENSE)

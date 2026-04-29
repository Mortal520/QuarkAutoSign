# 夸克扫描王自动签到 - Xposed模块方案

## 方案对比

| 方案 | 难度 | 稳定性 | 安全性 | 推荐度 |
|------|------|--------|--------|--------|
| MT管理器改APK | 中 | 中 | 需重签名 | ⭐⭐⭐ |
| Xposed模块(NPatch) | 低 | 中 | 不改APK | ⭐⭐⭐⭐ |
| **Xposed + 荣耀智慧空间** | **低** | **高** | **不改APK** | **⭐⭐⭐⭐⭐** |

## 推荐方案：Xposed + 荣耀智慧空间

**全自动、息屏执行、无需手动打开APP**

```
每天 8:00（息屏状态）
    ↓
荣耀智慧空间定时触发 → 打开夸克扫描王APP
    ↓
Xposed模块检测到APP启动 → 自动执行签到
    ↓
签到完成后 8秒 → 自动返回桌面
```

**详见**：《荣耀智慧空间配置教程.md》

---

## 基础方案原理

通过 NPatch/FPA 免Root Xposed框架，Hook 夸克扫描王APP的 `Application.onCreate`：
1. APP启动时自动触发签到
2. 记录签到时间，每天只签一次
3. 签到完成后自动返回桌面（不占用前台）

**无需修改APK、保留原版签名、随时开关**

---

## 前置条件

1. **NPatch** 或 **FPA** 已安装（免Root Xposed框架）
2. **夸克扫描王APP** 已安装（原版，无需修改）
3. 已登录夸克账号

---

## 安装步骤

### 步骤1：安装Xposed模块APK

**方式A：下载预编译版（如果有）**
直接安装 `QuarkAutoSign-v1.0.apk`

**方式B：自行编译**
```bash
# 1. 创建Android Studio项目
# 2. 导入 QuarkAutoSign.java
# 3. 将 xposed_init 放入 assets 目录
# 4. 使用提供的 AndroidManifest.xml
# 5. 编译生成 APK
```

### 步骤2：在NPatch/FPA中激活模块

#### NPatch操作：
```
1. 打开 NPatch 管理器
2. 点击底部「模块」标签
3. 找到「夸克扫描王自动签到」
4. 勾选启用开关
5. 返回首页，点击「安装/更新」注入框架
```

#### FPA操作：
```
1. 打开 FPA 管理器
2. 进入「模块管理」
3. 找到「夸克扫描王自动签到」
4. 点击「启用」
5. 返回首页，重新注入目标APP
```

### 步骤3：对夸克扫描王进行注入

```
1. 在 NPatch/FPA 首页
2. 点击「添加应用」或「选择应用」
3. 选择「夸克扫描王」
4. 点击「注入」或「修补」
5. 等待处理完成（会生成一个修补后的APK）
6. 卸载原版夸克扫描王，安装修补版
```

### 步骤4：验证功能

```bash
# 连接电脑执行：
adb logcat -s QuarkAutoSign:D *:S
```

**预期输出**：
```
QuarkAutoSign: QuarkAutoSign module loaded for: com.quark.scank
QuarkAutoSign: Application onCreate hooked, scheduling sign-in
QuarkAutoSign: Signing in now...
QuarkAutoSign: Sign-in triggered successfully
QuarkAutoSign: Next sign-in scheduled after 23h
```

---

## 工作原理详解

### 触发流程
```
APP启动 → Application.onCreate → Hook触发
    → 检查今天是否已签到
    → 未签到 → 调用 CameraCheckInManager.m63476q()
    → 记录签到时间到 SharedPreferences
    → 设置23小时后AlarmManager定时器
    → 定时器触发 → 再次执行签到流程
```

### Hook点
- **目标类**：`com.ucpro.feature.study.userop.CameraCheckInManager`
- **方法**：`m63467i(AbsWindow)` 获取单例
- **方法**：`m63476q(ValueCallback, String)` 执行签到
- **Hook位置**：`Application.onCreate`（确保APP初始化完成）

### 防重复机制
```java
// 通过SharedPreferences记录最后一次签到日期
// 每天0点重置，确保一天只签一次
Calendar.lastSign.day != Calendar.now.day → 允许签到
```

---

## 常见问题

### Q1: NPatch提示"模块未启用"？
- 确保在NPatch模块列表中勾选了本模块
- 点击NPatch首页的「安装/更新」重新注入框架
- 重启手机

### Q2: 日志显示"CameraCheckInManager is null"？
- 可能是APP版本更新，类名或方法名变更
- 需要更新Hook目标
- 联系开发者适配新版本

### Q3: 签到成功但没奖励？
- 确保APP内已登录账号
- 确认当天未手动签到过（通常每天限一次）
- 检查网络连接

### Q4: 定时器没有触发？
- Android系统可能限制了后台Alarm
- MagicOS设置：电池优化 → 夸克扫描王 → 不优化
- 或使用「NPatch应用保活」功能

### Q5: 如何关闭自动签到？
- NPatch模块列表中取消勾选本模块
- 或卸载本模块APK
- 重新注入夸克扫描王（不含模块）

---

## 优势总结

| 优势 | 说明 |
|------|------|
| 不改APK | 保留原版签名，不影响APP更新 |
| 随时开关 | NPatch中一键启用/禁用 |
| 无Root | NPatch/FPA免Root方案 |
| 自动调度 | 23小时间隔，无需手动操作 |
| 防重复 | 每天只签一次，避免风控 |
| 日志完整 | 便于排查问题 |

---

## 更新记录

- 2024-04-29: 创建Xposed模块方案，适配NPatch/FPA

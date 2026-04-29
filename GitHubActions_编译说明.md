# GitHub Actions 自动编译

## 方案说明

没有本地 Android 编译环境？使用 **GitHub Actions** 云端自动编译，**免费**、**无需本地配置**。

## 步骤

### 步骤1：创建 GitHub 仓库

```
1. 访问 https://github.com/new
2. 仓库名称：QuarkAutoSign
3. 选择「Public」（或Private，Actions免费额度相同）
4. 点击「Create repository」
```

### 步骤2：上传代码到 GitHub

```bash
# 在你的电脑（当前目录就是 Xposed_自动签到模块 文件夹）

# 初始化Git
git init

# 添加所有文件
git add .

# 提交
git commit -m "Initial commit"

# 添加远程仓库（替换 yourname 为你的GitHub用户名）
git remote add origin https://github.com/yourname/QuarkAutoSign.git

# 推送
git push -u origin main
```

**或使用 GitHub Desktop / VSCode Git 插件上传**

### 步骤3：触发自动编译

```
代码推送后，GitHub Actions 会自动开始编译
1. 访问仓库页面
2. 点击顶部「Actions」标签
3. 看到 "Build Xposed Module" 工作流正在运行
4. 等待2-3分钟
```

### 步骤4：下载编译好的APK

```
编译完成后：
1. 点击「Actions」标签
2. 点击最新的成功构建
3. 滚动到 Artifacts 区域
4. 下载 "QuarkAutoSign-Debug"
5. 解压zip得到 APK 文件
```

**或等待自动Release**：
- 构建成功后会自动创建 Release
- 点击仓库右侧「Releases」
- 下载最新版本的 APK

---

## 常见问题

### Q1: 没有git命令？
**解决**：下载 [GitHub Desktop](https://desktop.github.com/)，打开项目文件夹，点击Publish repository。

### Q2: git push 失败？
**解决**：
```bash
# 先设置用户名和邮箱（首次使用git）
git config --global user.name "Your Name"
git config --global user.email "your@email.com"

# 重新推送
git push -u origin main
```

### Q3: GitHub Actions编译失败？
**解决**：
- 检查 `.github/workflows/build.yml` 是否正确上传
- 检查 `build.gradle` 和 `src/` 目录结构是否正确
- 查看Actions日志中的具体错误信息

### Q4: 不想用GitHub？
**替代方案**：
- [GitLab CI](https://docs.gitlab.com/ee/ci/)（类似配置）
- [Gitee Actions](https://gitee.com)（国内速度快）

---

## 文件结构要求

推送到GitHub时，确保包含以下文件：

```
QuarkAutoSign/
├── .github/
│   └── workflows/
│       └── build.yml          ← 编译工作流
├── build.gradle               ← Gradle配置
├── src/
│   └── main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   └── xposed_init
│       └── java/
│           └── com/
│               └── qurk/
│                   └── autosign/
│                       └── QuarkAutoSign.java
└── README.md                  ← 可选
```

---

## 首次编译时间线

```
Push代码 ──→ Actions触发（立即）
                │
                ↓ 30秒
            设置环境（Ubuntu + JDK 17 + Android SDK）
                │
                ↓ 1分钟
            Gradle编译（下载依赖 + 编译Java + 打包APK）
                │
                ↓ 30秒
            上传Artifacts
                │
                ↓
            完成！下载APK
```

**总耗时约3-5分钟**

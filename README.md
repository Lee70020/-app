# HuaYuanTimetable

一个面向华侨大学教务系统的本地课表工具，支持课程信息获取、课表展示、自动同步以及 Android 端使用。

项目目前主要用于个人学习、课表管理与 Android 应用开发实践。

---

## 功能

- 获取华侨大学教务系统课表
- 支持统一身份认证登录流程
- 自动读取当前学期课程信息
- 将课表数据转换为本地可用格式
- Android 端课表展示
- 支持课表自动同步
- 支持本地缓存课程信息
- 提供桌面端辅助抓取工具
- 包含基础测试与同步逻辑验证

---

## 项目结构

```text
hqu-timetable/
├── app/
│   ├── src/main/
│   │   ├── assets/
│   │   │   ├── auto_login.js
│   │   │   ├── home.html
│   │   │   ├── home.js
│   │   │   └── timetable.js
│   │   │
│   │   ├── java/cn/hqu/timetable/local/
│   │   │   ├── AutoSync.java
│   │   │   ├── LoginFlow.java
│   │   │   ├── LoginNavigation.java
│   │   │   ├── MainActivity.java
│   │   │   ├── ScheduleJobService.java
│   │   │   ├── ScheduleTiming.java
│   │   │   ├── SyncEngine.java
│   │   │   ├── SyncNotifications.java
│   │   │   ├── SyncReceiver.java
│   │   │   ├── WdkbClient.java
│   │   │   └── WdkbTransport.java
│   │   │
│   │   └── python/
│   │       └── schedule.py
│   │
│   └── build.gradle
│
├── desktop/
│   ├── autologin.py
│   ├── hqu_crawl.py
│   └── requirements.txt
│
├── examples/
│   └── example-schedule.json
│
├── tests/
│   ├── java/
│   ├── test_live.cjs
│   ├── test_login.cjs
│   ├── test_schedule.py
│   ├── test_ui.cjs
│   └── test_updates.py
│
├── docs/
├── gradle/
├── crawl.py
├── build.gradle
├── settings.gradle
├── gradle.properties
├── gradlew
├── gradlew.bat
└── VALIDATION.md
```

---

## 运行环境

### Android

建议环境：

- Android Studio
- JDK 17 或兼容版本
- Android SDK
- Gradle

项目已经包含 Gradle Wrapper，因此通常无需单独安装 Gradle。

Windows：

```bash
gradlew.bat assembleDebug
```

Linux / macOS：

```bash
./gradlew assembleDebug
```

编译完成后，APK 通常位于：

```text
app/build/outputs/apk/
```

具体路径取决于当前构建配置。

---

## 桌面端工具

部分教务系统请求与登录流程可以通过 Python 工具进行调试。

进入：

```text
desktop/
```

安装依赖：

```bash
pip install -r requirements.txt
```

随后根据需要运行对应脚本：

```bash
python hqu_crawl.py
```

---

## 数据格式

项目支持将课程信息转换为 JSON 格式。

示例：

```text
examples/example-schedule.json
```

课程数据通常包括：

```json
{
  "courseName": "课程名称",
  "teacher": "教师",
  "location": "上课地点",
  "weekday": 1,
  "startSection": 1,
  "endSection": 2,
  "startWeek": 1,
  "endWeek": 16
}
```

实际字段请以当前版本代码为准。

---

## 登录与隐私

本项目不会要求开发者将华侨大学统一身份认证账号或密码提交至 Git 仓库。

请勿在源码中直接写入：

```text
学号
密码
Cookie
JSESSIONID
Token
CAS Ticket
其他认证凭据
```

如果需要本地配置账号信息，建议使用环境变量、本地配置文件或系统安全存储，并确保相关文件已经加入 `.gitignore`。

例如：

```gitignore
.env
*.keystore
*.jks
local.properties
```

不要将真实账号密码提交到 GitHub。

如果敏感凭据曾经被提交过，即使之后删除，也可能仍然存在于 Git 历史中，应及时修改对应密码并清理 Git 历史。

---

## 当前状态

项目目前仍处于开发和验证阶段。

部分功能可能会受到以下因素影响：

- 华侨大学教务系统页面改版
- CAS 登录流程变化
- Cookie / Session 策略变化
- 学期参数变化
- Android WebView 行为差异
- 教务系统接口调整

相关测试与开发记录可参考：

```text
VALIDATION.md
```

---

## 下载

如果提供正式 Android 安装包，建议通过 GitHub Releases 发布。

源码仓库主要用于保存：

- 源代码
- 测试代码
- 构建文件
- 文档
- 示例数据

APK 文件原则上不直接提交到 Git 仓库。

---

## 开发计划

后续可能继续完善：

- [ ] 优化登录稳定性
- [ ] 优化课表自动同步
- [ ] 改进课程展示界面
- [ ] 增强异常状态提示
- [ ] 增加学期切换功能
- [ ] 优化离线课表体验
- [ ] 增加更完整的测试
- [ ] 提供稳定版本 APK
- [ ] 优化项目目录与构建流程

---

## 注意事项

本项目与华侨大学官方无关。

项目仅用于个人学习、技术研究与个人课表管理。

使用者应自行遵守学校教务系统、统一身份认证系统及相关网络服务的使用规定。

请勿利用本项目进行：

- 批量访问
- 高频请求
- 未授权数据采集
- 获取他人账号信息
- 绕过身份认证
- 影响教务系统正常运行的行为

由使用本项目产生的账号、数据及其他风险由使用者自行承担。

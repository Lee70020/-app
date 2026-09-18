# 华园课表 0.3.1

包名 `cn.hqu.timetable.local`，versionCode 12。0.2.8 的课表同步已获用户实机确认成功；0.3.0 新增实时首页、后台检查与变化提醒，后台执行与通知到达仍待手机复验。

## 0.3.1 登录修复

提交后的慢跳转不再在十秒时误判失败；同一尝试不重复提交；终态后的新尝试打开新登录页。原生等待提交结果最多两分钟，到达教务后等待会话建立再抓取。诊断新增版本标记和跳转协议/端口。

本地新增 8 项登录浏览器测试、28 项登录状态与导航检查通过。设备登录仍需复验，历史 0.2.8 成功不能代替新版验收。

## 手机使用

1. 覆盖安装新版，保留已有课表与账号，不需要卸载。
2. 首次打开允许通知，并点右上角“同步”一次，读取学期开始日期；首页即可按手机系统时间（北京时间）显示当前周、正在上的课、课间和下一节。
3. 周课表支持切换周次、回到本周、网格/列表和课程详情。浏览其他周不改变实时课程卡片。
4. “调整”保留最近 30 批课表变化；教室、教师、星期、周次、节次变化会记录原安排和现安排。首次同步只建立基线，不把全表当作调课。
5. 默认每周一 00:15，以及每天 08:00、14:00、20:00 计划检查（均为北京时间）。发现变化才发课表通知；登录过期另提醒一次。设置中可以关闭自动检查或白天检查。
6. 在手机应用设置中允许通知和后台活动。关闭应用页面后可由系统执行；强制停止、关机、离线和省电限制可能推迟或阻止执行。任务不是精确闹钟，不能保证整点执行。
7. 设置内可导出/导入 JSON、管理教务账号、查看上课时间和同步诊断。进口文件只有包含有效学期日期，才能直接计算实时课程，否则需同步一次。

## 时间与数据边界

- 节次起止时间取自[教务处作息表](https://jwc.hqu.edu.cn/info/1001/1013.htm)（2022-08-22），并与[当前官网教学日历](https://jwc.hqu.edu.cn/)核对。第 10 节官网标注不排课；若接口有该节记录，仍按公开时段显示。
- 首页时钟不访问学校服务器，每秒读取系统时间；上课状态按分钟更新。请开启手机自动日期与时间。
- 后台复用 WebView 会话；会话过期需要打开应用重新登录，不在后台自动提交密码或解验证码。
- 普通课程与 `KBLB` 非普通记录分开保存。未确认语义的特殊安排标为“待核对”，不臆测考试、取消或调整的含义，不自动覆盖普通课表。特殊安排存在时首页有提示。
- 通知只覆盖教务接口已发布且可比较的变化，无法监听老师私下通知；白天检查间隔约六小时，不是校方即时推送。
- 数据不完整、为空或解析失败时保留旧课表，避免错误报告取消课程。

## 架构

- `MainActivity`：独立 CAS WebView（原有自动登录与滑块流程）及离线主页。
- `WdkbClient` / `WdkbTransport`：Java HTTP 直接 POST 学期与课表接口，逐 URL 读取 Cookie、接收更新、限定同源 HTTPS 重定向。沿用已成功的 0.2.8 流程。
- `SyncEngine`：串行化手动/后台同步和导入；Python `schedule.sync_wdkb` 转换、比较并将课表与变化记录一起原子保存。
- `ScheduleJobService` / `AutoSync`：系统 JobScheduler 持久化计划，网络约束、停止处理与退避重试；不需要常驻服务或服务器。
- `SyncNotifications`：Android 通知权限、频道、去重及点击打开变化记录。
- `home.html` / `home.js` / `timetable.js`：离线界面和时间模型。主页禁止网络加载、文件访问和导航；仅其自身含枚举动作桥接。CAS WebView 不注册该桥接；课表文本通过 textContent 渲染。

## 构建和验证

JDK 17、Gradle 8.13、Android SDK 35 / Build Tools 35.0.0、AGP 8.9.2、Chaquopy 16.1.0 / Python 3.12；minSdk 24，arm64-v8a + x86_64。

```text
./gradlew :app:assembleDebug
python -m unittest discover -s tests -v
javac -encoding UTF-8 -d build/transport-tests app/src/main/java/cn/hqu/timetable/local/WdkbTransport.java app/src/main/java/cn/hqu/timetable/local/ScheduleTiming.java tests/java/WdkbTransportTest.java tests/java/ScheduleTimingTest.java
java -cp build/transport-tests cn.hqu.timetable.local.WdkbTransportTest
java -cp build/transport-tests cn.hqu.timetable.local.ScheduleTimingTest
node tests/test_live.cjs
```

Windows 使用 gradlew.bat，Python 测试设置 `PYTHONUTF8=1`。浏览器界面测试 `tests/test_ui.cjs` 需要 Playwright 和 Edge；可通过 `PLAYWRIGHT_MODULE` 指定包路径、`PLAYWRIGHT_CHANNEL` 选择已安装浏览器。测试截图仅使用示例数据。

本轮：24 项 Python 测试、10 项 HTTP 回归、8 项调度边界、16 项实时课程断言通过；同一 HTML 资产浏览器交互与 320/390/768 宽度检查通过；APK 构建与签名通过。Android lint 因 lint-gradle 依赖下载 TLS 失败未完成，不能称静态检查全部通过。详细记录见 VALIDATION.md。

## 数据与隐私

课表与变化记录保存在本机私有目录，允许通过系统文件选择器导入导出。源码和安装包不含用户账号或密码。桌面爬虫只从 HQU_USER/HQU_PASS 环境变量读取凭据；Android 继承旧版账号设置行为，将用户输入保存于应用私有 credentials.json，可在账号页清除。后台检查使用已有 Cookie，不新增账号存储或向第三方发送数据。卸载会删除应用私有课表和登录状态。APK 仍为原 debug 证书签名的测试版，非学校官方应用。

登录浏览器回归：设置 PLAYWRIGHT_MODULE 后运行 node tests/test_login.cjs。Java 登录策略回归：编译 LoginFlow.java、LoginNavigation.java 与 tests/java/LoginFlowTest.java，再运行 cn.hqu.timetable.local.LoginFlowTest。

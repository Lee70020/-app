# 华园课表 0.3.0 Implementation Plan

Goal: 已成功同步的应用增加计划更新、变化通知和可用的周课表界面。
Architecture: JobScheduler -> shared SyncEngine -> WdkbClient -> existing WdkbTransport; Python atomic save/diff; local HTML UI with restricted action bridge.
Tech Stack: Android Java API24+, Python stdlib/Chaquopy, local HTML/CSS/JS. No new dependencies.
Spec: ../specs/2026-09-17-auto-sync-ui-design.md

1. 写变化检测和时段边界测试并运行；实现 Python 语义比较/历史/特殊安排与 ScheduleTiming。
2. 提取 WdkbClient 和 Cookie 适配器；实现串行 SyncEngine、JobService、通知与持久化调度；接入 manifest 和 Activity。
3. 实现离线 UI、桥接、自动本周及权限设置；用同一资产浏览器验证手机尺寸和交互。
4. 运行回归、构建与 lint，检查版本签名和打包内容；记录限制并交付 APK/源码。

开发按以上顺序本地执行；无 Git 仓库，不创建提交；不向他人发送文件。

## 完成记录
四步已实施。新增实时课程模型与校方作息表。构建、签名、24 Python + 18 Java + 16 实时断言及浏览器资产检查通过；Android lint 依赖网络不可用，实机新功能待复验，详见 VALIDATION.md。

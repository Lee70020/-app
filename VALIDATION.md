# 验证记录

## 0.3.1（2026-09-18，登录慢跳转与重试状态修复）

### 新实机证据

- 12:16:07 报 submitted，12:16:18 即报 still_on_login/no server message；直到 12:17:03 才出现带 ticket 的 jwapp 页面。仅凭十秒仍在原登录 DOM 不能判定密码错误。
- 多次点击重试后 1–2 秒直接读取相同 STOPPED。旧 JS 的 __LOGIN_RUNNING 在终态错误后未释放，脚本提前 return，__LOGIN_RESULT 未清空；Java 又复用旧登录页。浏览器夹具复现了这个问题。
- jwapp 页面回调出现时 Cookie 仍为空。旧逻辑将“到达域名”等同于会话可用，立即抓取并失败。
- block non-univ 日志中显示 id.hqu.edu.cn，但旧摘要隐去了 scheme/port，不能确定是 HTTP、端口、用户信息还是其他 URL 问题。此次不把 HTTP 推测当作已确认根因。

### 修复

- auto_login.js v5 使用 native attempt token；旧异步工作不能再提交或覆盖新结果。终态释放运行标记，同一尝试重复注入不重复提交。
- 10 秒仍停留原表单改为 waiting_redirect；只有可见的明确错误提示才报告 server_rejected。验证码/准备阶段最长 180 秒，提交后最多等待 120 秒，使用单调时钟且重复轮询不延长截止时间。
- 同一登录尝试仍进行时只继续等待；明确失败或超时后的新尝试加载新的固定 HTTPS CAS 入口，获取新表单与 salt，不重用旧结果。
- 到达 jwapp 时检查数据接口路径下的会话 Cookie；仅有语言 Cookie 不算已就绪。无会话时最多等待 30 秒，建立后再调用原 Java 抓取；超时明确说明尚未建立会话，不误称登录成功。
- 移除旧的 jwapp→CAS 阻断逻辑，允许正常 HTTPS 认证往返。导航诊断保留协议、端口、主机与路径，省略整个查询串；启动日志带 0.3.1 / login-flow v5。
- 若校方返回标准 HTTP CAS 登录入口的 GET，最多一次回到本应用固定 HTTPS 入口，不发送原 HTTP 请求；表单同类目标也先升为 HTTPS，再填入凭据。其他域名、异常端口、URL 用户信息和明文 POST 仍拦截。此项为安全恢复分支，设备是否命中须看新版日志。
- 版本 code12 / 0.3.1，保留 0.3.0 实时课程、UI、定时更新与变化提醒。

### 本地验证

- 旧登录脚本：首批六项浏览器测试 2 通过、4 失败，覆盖慢跳转被误判、失败运行标记残留、旧异步尝试、可见服务端错误。新增两项表单目标测试先失败，再修复。
- 新登录脚本 8/8 浏览器夹具测试通过（不访问学校、不使用真实凭据）；LoginFlow/导航/Cookie 等待边界 28 项检查通过。
- 原 HTTP 10/10、调度 8/8、实时判断 16 项断言、Python 24/24 以及主页浏览器交互检查均通过。
- 最终 :app:assembleDebug 成功；aapt 确认 code12 / 0.3.1，apksigner 验签通过且证书与 0.3.0 相同。APK 内登录与主页资产逐字节匹配当前源码，DEX 含 v5 标记和新的 LoginFlow / LoginNavigation。手机完整登录与 Cookie 建立仍待复验，不能用本地模拟等同实机成功。

### 复验

覆盖安装后点同步一次，保持登录页等待，提交后的慢跳转可以等待两分钟。预期诊断出现 submitted → waiting_redirect（若慢）→ jwapp reached → session ready 或直接 java fetch → terms/kb status=200。若失败，请提供从 app 0.3.1 / login-flow v5 开始的诊断；新版能够显示被拦截目标的协议/端口，且不会记录 ticket 参数值。

## 0.3.0（2026-09-18，实时课表、后台检查、变化提醒与界面）

### 用户确认与要求

- 用户确认 0.2.8 实机同步成功。
- 新要求：周一凌晨自动更新、及时提示临时调课、优化界面；用户选定“周一凌晨 + 白天每六小时检查”，确认厦门校区，并补充“同步现实时间，主界面显示实时课程”。

### 实现

- 计划时段：北京时间周一 00:15，以及每天 08:00/14:00/20:00；使用 JobScheduler 持久化单次任务、网络约束与失败退避，完成后安排下一时段。重启、覆盖安装和手动改时间有处理；关闭开关取消任务。
- 前后台复用同一 WdkbClient/Transport 和串行 SyncEngine，沿用 0.2.8 成功的直接 terms→kb 查询、URL Cookie 匹配及响应更新。会话过期提示重新登录；不在后台操作登录页。
- Python 语义比较课程名称、教师、教室、星期、节次、周次，忽略顺序/重复/ID/排版；初次或导入文件建立基线，学期切换单独提示。变化历史最多 30 批，与课表一起原子提交。解析不完整时不覆盖旧课表。
- 非普通 KBLB 记录保留为特殊安排，比较变化、主页提示并在调整页展示，未验证语义时不放入正常网格。
- 新增 term_start；首页用校方作息 + 当前北京时间计算本周、当前课程、课间和后续课程；手动切周不会改变实时卡片；导入导出保留有效学期日期。
- 离线 UI：蓝白主页、实时课程卡、周网格/列表、原文详情、调整记录、更新与通知设置。只在本地页面注册动作桥，CAS 页面无桥接；阻止主页网络与导航，所有外来课程文本使用 textContent。

### 本地证据

- 新功能测试先失败，再实现：初始变化测试 7/8 未通过；实时模型初始缺失；时段模型初始返回错误时间；导入日期回归初始失败。实现后 Python 总计 24/24、HTTP 10/10、调度 8/8、实时课程 16 项断言全部通过。
- Playwright 运行 APK 同源 HTML/CSS/JS 资产，使用标记的示例数据：当前课程、查看其他周/回到本周、详情弹层、列表、三页导航、设置动作、320/390/768 屏宽无页面横向溢出、恶意课程文本不执行、跨周、空状态；无页面 JS 错误。此验证是浏览器资产检查，不是 Android WebView 真机测试。
- :app:assembleDebug 成功；包名 cn.hqu.timetable.local / code 11 / 0.3.0，minSdk24，arm64-v8a + x86_64。
- apksigner verify 成功，证书 SHA-256 45492790575973f7ea064022568ca5f5093ba8f1ed8e1607aa57560366b55063，与 0.2.8 一致，可覆盖安装。
- APK ZIP 完整性通过；打包的 home.html、home.js、timetable.js 与源码逐字节一致，Python app.imy 含当前 schedule.py；DEX 含新后台服务、调度器、客户端与通知类。Manifest 包含后台服务的 BIND_JOB_SERVICE、开机接收器、通知与网络权限。
- lintDebug 未完成：离线缓存缺 lint-gradle:31.9.2，联网下载 Google/Maven 依赖时 TLS 失败；没有声称 lint 通过。Chaquopy 构建未预编译 pyc（本机未自动找到构建 Python），已验证当前 .py 源码进入 APK，运行时由内置 Python 执行。

### 待实机复验与限制

- 没有可用 Android 真机/模拟器验证新版本：后台唤起、WebView 首屏、通知权限/到达、长时间休眠后的 Cookie 可用性仍须手机确认。0.2.8 的成功不等于 0.3.0 后台功能验收。
- 覆盖安装后打开一次、允许通知、手动同步一次读取 term_start，检查主页当前周与正在上的课。系统强制停止、离线和省电会推迟或阻止计划；不承诺准点或即时推送。
- 课表接口之外的临时通知不可见。特殊 KBLB 的考试/取消等含义尚未验证，保留待核对；不会臆测取消现有课程。
- 桌面环境变量凭据机制不变；Android 沿用之前在私有目录保存用户输入的账号行为，本版未新增后台密码登录。

### 参考

- 华侨大学教务处作息时间表：https://jwc.hqu.edu.cn/info/1001/1013.htm
- 当前教务处教学日历：https://jwc.hqu.edu.cn/
- Android 后台任务时机由系统调度：https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work

## 0.2.8（2026-09-17，直接查询数据并同步 HTTP 会话）

### 实机证据与结论边界

- 用户 17:26:23 日志已经包含 GS_SESSIONID、_WEU、JSESSIONID，稍后还包含 EMAP_LANG；用户确认页面显示课表，但导入失败。0.2.7 已消除本次日志中的“cookies=(none)”问题。
- 旧 transport 将任意 3xx 一律转换为“教务会话已失效”，且不记录失败步骤、状态码和目标，因此当前设备日志不能证明真实会话过期，也不能确定具体跳转地址。
- 桌面已验证的 test_wdkb_service.py 从已登录的 wdkb 页面直接查询 cxxljc；0.2.7 却先重复打开 appShow 和 wdkb 首页。此次删除这两次额外访问。

### 修复

- WebView 登录后直接 POST cxxljc，再 POST cxxszhxqkb；保留 XNXQDM 三字段同值和原 Python 转换。
- WdkbTransport 为每个目标 URL 重新读取 CookieManager；接收各响应的 Set-Cookie，在 UI 线程完成 CookieManager 回调后才发下一请求。
- 添加 Referer、Origin 和 AJAX 请求头。仅跟随同源 HTTPS 跳转，最多五次；307/308 保留方法和请求体，303 及 POST 的 301/302 改为 GET。
- 返回 CAS 登录页时单独报错；拒绝其他域名、非 HTTPS、非 443 端口或带用户信息的跳转，不向这些目标转发教务 Cookie。
- 诊断记录 terms/kb、请求方法、HTTP 状态、Cookie 名称和不含查询参数的跳转路径；不记录 Cookie 值、响应正文或 ticket 查询参数。
- versionCode 10 / versionName 0.2.8；登录注入与课表转换保持原逻辑。

### 验证

- 用旧 transport 方法执行新回归测试：3 项通过、7 项失败，复现正常跳转被拒绝、Cookie 更新丢失以及诊断不足。
- 新 transport 离线 HTTP 测试 10/10 通过，使用受控 HttpURLConnection 响应和真实 Java CookieManager 检查路径作用域、Cookie 更新、CAS/外站拦截、POST 跳转、循环上限及日志脱敏。
- Python 课表转换测试 14/14 通过（PYTHONUTF8=1）。
- 离线 :app:assembleDebug 构建成功；aapt 确认包名 cn.hqu.timetable.local、code 10 / 0.2.8、minSdk 24、arm64-v8a + x86_64。
- apksigner verify 通过，签名证书 SHA-256 与 0.2.7 相同，可覆盖安装。
- 本轮没有可用实机/模拟器，没有完成学校系统端到端复验；上述离线测试不代表设备已成功导入。设备成功应出现 http terms status=200、term=2026-2027-1、http kb status=200、kb rows=13 和保存成功（行数以校方当时数据为准）。若失败，提供从 java fetch start 开始的诊断即可定位请求步骤。

### 离线 HTTP 测试命令（JDK 17）

```text
javac -encoding UTF-8 -d build/transport-tests app/src/main/java/cn/hqu/timetable/local/WdkbTransport.java tests/java/WdkbTransportTest.java
java -cp build/transport-tests cn.hqu.timetable.local.WdkbTransportTest
```

## 0.2.7（2026-09-17，按教务接口路径读取 Cookie）

### 实机证据与定位

- 0.2.6 实机 trace：`page jwapp.../wdkb/*default/index.do` 后触发 `java fetch`，但 `cookies=(none)`；截图同时显示校方“我的课表”页面及课程，说明已到达课表页面，失败发生在 Java 侧取得 Cookie 的边界。
- 0.2.6 使用 `CookieManager.getCookie("https://jwapp.hqu.edu.cn/")`。按 Cookie 路径规则，作用于 `/jwapp/...` 的 Cookie 不会出现在根路径查询结果中；这与设备现象一致。实际 Cookie 属性尚未从设备导出，需新版本复验确认。

### 单点修复

- 改为使用将要请求的 `cxxljc.do` 完整 URL 查询 Cookie，避免根路径过滤教务会话。抓取请求序列、Python 转换与登录流程未改。
- versionCode 9 / versionName 0.2.7。

### 本地验证与限制

- 离线 `:app:assembleDebug` 构建成功；APK 为 `cn.hqu.timetable.local`、versionCode 9 / 0.2.7、arm64-v8a + x86_64。
- `apksigner verify` 成功，新旧版签名证书 SHA-256 相同；DEX 包含新的 Cookie 查询 URL。
- Python 单元测试 14/14 通过（Windows 测试环境设置 `PYTHONUTF8=1`）。
- 设备端 Cookie 与最终同步结果仍待实机复验；若新 trace 已列出 Cookie 名称但后续请求失败，再检查 Java 请求过程中的 Cookie 更新与请求头。

## 0.2.6（2026-09-17，抓取改 Java 侧 Cookie 请求，摆脱页面 JS 上下文）

### 背景

0.2.5 设备日志：service 换 wdkb 后 **fetch 注入目标已正确**（`inject fetch_kb.js @ jwapp.../wdkb/*default/index.do?ticket=…`），但 wdkb 页与 portal 一样，**加载完同一秒内弹回 CAS**：

```
15:49:29  page jwapp.../wdkb/*default/index.do?ticket=…  auto=true sync=false inj=2
15:49:29  inject fetch_kb.js @ jwapp.../wdkb/*default/index.do?ticket=…   ← 上下文正确
15:49:29  block non-univ nav: id.hqu.edu.cn/authserver/login?service=…    ← 拦截弹回
15:49:29  page id.hqu.edu.cn/authserver/login?service=…  auto=false sync=true inj=2  ← 页面仍停在 CAS！
15:49:30  kb context lost → FAILED
```

- `shouldOverrideUrlLoading` 返回 true（拦截）**并不能阻止该弹回导航完成**（0.2.4/0.2.5 日志反复出现 `block non-univ nav` 后紧跟 `page CAS` 且无 `start` 前导）。页面 JS 上下文在注入后 <1s 即被销毁，页面脚本路线不可救药。
- 但 **ticket 消费后的教务会话存在 Cookie 中且有效**——桌面 requests 流程（tools/autologin.py、test_wdkb_service.py）就是纯 Cookie 复用取数，从不依赖页面。

### 修复（架构调整）

- **删除整条页面抓取链**：`injectFetch`/`pollKbResult`/`fetch_kb.js`（资产已删）不复存在；`syncPolls`/`syncGen`/`lastFetchUrl` 字段一并移除。
- **新增 `javaFetch()`**：登录成功（onPageFinished 到达 jwapp）后，从 `CookieManager` 读取 jwapp Cookie，worker 线程用 `HttpURLConnection`（禁自动重定向）执行桌面已验证的请求序列：GET appShow.do?id=… → GET wdkb index.do → POST cxxljc.do（选当前学期）→ POST cxxszhxqkb.do（XNXQDM×3），组装与原 fetch_kb.js 完全相同的 payload `{ok,term,termDisplay,rowCount,rows}` 交给 `schedule.sync_wdkb`。3xx 一律按"会话已失效"报错。
- `continueSync` 不再要求当前停在教务页（弹回不影响 Cookie 会话），直接 javaFetch。
- `trace()` 加 synchronized（worker 线程也写诊断），新增抓取步骤 trace（cookies 名单/term/rows/失败原因）。
- 版本 versionCode 8 / versionName 0.2.6。

### 验证

- 请求序列本身已由桌面真凭据端到端验证（0.2.5 的 test_wdkb_service.py：登录 4.0s、四个请求全部 200、数据正常）。
- `gradle --no-daemon :app:assembleDebug` 成功；aapt 确认 versionCode 8 / 0.2.6。
- 产物：`HuaYuanTimetable-0.2.6-javafetch.apk`（debug 签名）。

### 未验证 / 待办

- 实机复验。新链路预期 trace：`page jwapp.../wdkb?ticket=…` → `jwapp arrived -> java fetch` → `java fetch start cookies=JSESSIONID,…` → `java fetch: term=2026-2027-1 candidates=N` → `java fetch: kb rows=13` → 保存成功（页面即使随后弹回 CAS 也无关）。若 `java fetch FAILED: 教务会话已失效` 则说明 Cookie 未随 ticket 建立（需查 CookieManager 捕获）。

## 0.2.5（2026-09-17，service 直指课表页，从源头消除弹回）

### 背景

0.2.4 设备日志：**自动登录全链路已通**（`submitted` → ticket 到达 jwapp → auto-continue 触发），但暴露新时序问题：

```
15:13:39  page jwapp...index.do?forceCas=1&ticket=…   ← ticket 到达
15:13:39  inject fetch_kb.js @ id.hqu.edu.cn/authserver/login?service=…  ← web.getUrl() 竟返回 CAS！
15:13:39  block non-univ nav: id.hqu.edu.cn/authserver/login?service=…  ← portal 弹回导航（非标准 https 形式，疑 http）
15:13:39  page id.hqu.edu.cn/authserver/login?service=…  ← 弹回完成，页面停在 CAS
15:13:40  kb context lost → FAILED
```

- **根因确认（Bug B 结案）**：`portal/index.do?forceCas=1` 页面在浏览器中加载完成后**立刻跳回 CAS**（比 onPageFinished 回调还快——回调执行时 `web.getUrl()` 已是 CAS、fetch 注入进了错误上下文）。requests 不执行 JS，所以桌面爬虫从未复现。
- `pendingSync` 拦截输了时序竞争（置位太晚）；弹回 URL 还有一条非 https 形式（被 university() 误判为"非校方"而拦截，属有益误伤）。

### 修复

- **service 直指课表页（核心）**：`LOGIN_URL` 的 service 从 `emaphome/portal/index.do?forceCas=1` 改为 `wdkb/*default/index.do`——登录 302 直达课表页，不再经过会弹回的 portal；且该页正是 fetch_kb.js 的注册会话目标。
- **弹回拦截提前**：`decideNav` 拦截条件从 `pendingSync`（jwapp 到达才置位）提前到 `wantSync`（一键同步入口即置位，时序必赢）；仅拦"从 jwapp 页面 GET 到 CAS"的导航，**POST 放行**（登录表单提交必须可行）。
- `injectFetch(pageUrl)` 参数化：`lastFetchUrl` 用 onPageFinished 的 url 参数，不再依赖时序不可靠的 `web.getUrl()`。
- `hideBrowser`（返回课表）清除 `wantSync`。
- `auto_login.js`：提交后自检 6s→10s（0.2.4 日志有一次提交实际成功但 >6s 才跳转，误报 still_on_login）。
- 版本 versionCode 7 / versionName 0.2.5。

### 验证

- **桌面端到端（tools/output/test_wdkb_service.py，真实凭据）**：CAS 接受 `service=wdkb/*default/index.do`，登录 4.0s 直达该页（`final on wdkb: True / still on CAS: False`），页面 GET 200（4542B）无 CAS 重定向，同会话 `cxxljc.do` 数据接口正常。
- `node --check` 通过；`gradle --no-daemon :app:assembleDebug` 成功；aapt 确认 versionCode 7 / 0.2.5。
- 产物：`HuaYuanTimetable-0.2.5-wdkb.apk`（debug 签名）。

### 未验证 / 待办

- 实机复验。新链路预期 trace：`page jwapp.../wdkb/*default/index.do?ticket=…` → `jwapp arrived -> auto-continue sync` → `inject fetch_kb.js @ jwapp...`（@ 后必须是 jwapp 而非 CAS）→ `kb -> {...}`。若仍出现 `BLOCK bounce jwapp->CAS` 说明 wdkb 页也有跳转（新证据）。

## 0.2.4（2026-09-17，注入时机修复 + 手动登录后续跑 + 弹回拦截）

### 背景

0.2.3-diag 实机诊断日志（首个设备证据）揭示两个问题：

```
14:47:43  page id.hqu.edu.cn/authserver/login?service=…  auto=true inj=0
14:47:43  inject auto_login.js #1
14:47:44  page id.hqu.edu.cn/authserver/login?service=…  auto=true inj=1   ← 同页第二次 onPageFinished 被 3s 去重拦住
14:47:44  login -> {"ok":false,"stage":"no_form","detail":"salt=false …"}  ← 首次注入时 DOM 未就绪，脚本立即放弃
14:48:08  page jwapp…index.do?forceCas=1&ticket=ST-20967-…  auto=false sync=false  ← 用户手动登录成功（桌面 UA 生效），应用未续跑
14:48:08  page id.hqu.edu.cn/authserver/login?service=…  auto=false sync=false  ← 同秒弹回 CAS（成因待新 trace 分辨）
```

- **Bug A（确诊）**：注入发生在首个过早的 `onPageFinished`（DOM 未就绪 `salt=false`）；1 秒后真正完整的第二次 `onPageFinished` 被自身 3 秒去重逻辑拦住 → `no_form` 失败。
- **Bug B（已缓解、根因待分辨）**：手动登录成功带 ticket 到达 jwapp 后应用什么都不做；同秒页面又回到 CAS 登录页（候选成因：页面被加载两次致 ticket 二次消费 / portal 页 JS 检测会话失败重定向 / 用户按返回）。

### 修复

- `auto_login.js` v4：找表单改为**等待循环**（最多 20×500ms 轮询 `#pwdEncryptSalt`），根治注入过早；`no_form` 时释放 `__LOGIN_RUNNING` 守卫，允许后续重注入。
- `MainActivity`：
  - **移除 3 秒同 URL 去重**（JS `__LOGIN_RUNNING` 守卫已防同页双跑），注入上限 2→3；轮询链改用 `loginGen`/`syncGen` 代际计数，新注入自动作废旧链（防双弹窗/双抓取）。
  - **no_form 不再终止自动流程**：保留 `pendingAutoLogin`，状态栏提示"可手动登录，成功后会自动继续同步"。
  - **jwapp 到达即续跑**：新增 `wantSync` 标志（autoFlow/继续同步置位，成功/失败/登出清除）；任何 jwapp 页面 `onPageFinished` 且 `wantSync` → 自动 `injectFetch()`；页面跳转导致 JS 上下文销毁时在新页面重注入（按 `lastFetchUrl` 判断）。
  - **同步期拦截弹回**：`decideNav()` 在 `pendingSync` 期间拦截跳向 CAS 登录页的导航，保住 fetch 的 JS 上下文（0.2.3 的"同秒弹回"正是"反复安全验证"的直接来源）；若弹回未被拦截（如返回键的 history 导航），`pollKbResult` 检测到 URL 离开 jwapp 即快速失败并提示。
  - **诊断加深**：`onPageStarted`、`shouldOverrideUrlLoading` 决策、主帧 net/http 错误、全部按钮点击、每处 `loadUrl` 均入 trace；环形缓冲 60→80 条；service/ticket 参数值打码。
  - 课表轮询超时 120→60 次（约 48s，上下文丢失场景已有快速失败兜底）。
- 版本 versionCode 6 / versionName 0.2.4。

### 验证

- `node --check` 通过（auto_login.js v4）。
- `gradle --no-daemon :app:assembleDebug` 成功；aapt 确认 versionCode 6 / 0.2.4。
- 产物：`HuaYuanTimetable-0.2.4-retry.apk`（debug 签名）。

### 未验证 / 待办

- 实机复验。若仍失败，索取新"诊断"文本：重点看 ① `start`/`page` 序列是否同一 URL 请求两次（ticket 二次消费）；② 是否出现 `BLOCK nav->CAS during sync`（portal 主动弹回的证据）；③ `kb context lost` 前的导航链。

## 0.2.3（2026-09-17，改用桌面 UA + 设备端诊断）

### 背景

0.2.2 修复了移动版 DOM 定位后，实机仍报"无法跳转，反复安全验证"。说明提交仍被 CAS 拒绝并退回登录页。移动版页面还有一处无法绕开的缺陷：两个表单共用 `id="loginFromId"`，页面自身的登录按钮 `$("#loginFromId").submit()` 只会提交**隐藏的动态码表单**（cllt=dynamicLogin），用户手动点登录也必然失败——这正是"反复安全验证"的直接来源。

### 修复

- **WebView 固定使用桌面 User-Agent**（`DESKTOP_UA`）。桌面版页面是唯一已端到端验证通过的布局：`#pwdFromId` 唯一、页面自身 `utils.setUrlParam("pwdFromId","?service",…)` 能正确给提交表单补 service、无重复 id 干扰。自动登录与手动兜底同时变可靠。
- `auto_login.js` 保持 v3 的 DOM 无关定位（`#pwdEncryptSalt` 反查表单 / 兼容 `passwordText`+`userPassword` / action 缺 service 时补齐）。
- **新增设备端诊断**：`trace()` 记录每次页面加载 URL、注入次数、`auto_login.js` 返回结果（stage/detail/action）、课表抓取结果；浏览器面板新增"诊断"按钮；自动登录失败时弹窗带"查看诊断"（文本可长按复制）。

### 验证

- `gradle :app:assembleDebug` 成功；aapt 确认 versionCode 5 / 0.2.3。
- 桌面 UA 布局的正确性此前已用真实凭据端到端验证（0.2.0 桌面全流程 + 0.2.2 的移动 UA 对照实验）。
- 产物：`HuaYuanTimetable-0.2.3-diag.apk`（18.9MB，debug 签名），已通过微信发送。

### 未验证 / 待办

- 仍需实机复验。若仍失败，请提供"诊断"面板文本（含每次页面 URL 与注入返回），据此可精确判定是提交被拒、还是 CAS↔jwapp 跳转环。
- 模拟器不可用（无 emulator 组件与 system-images）。

## 0.2.2（2026-09-17，移动端 CAS 页面适配——真正的登录循环根因）

### 问题

实机："完成安全验证后，依旧无法进入教务页面"；更早表现为反复触发安全认证。

### 根因（实测 HTML 对比确认）

**华大 CAS 对移动端 UA 返回的是完全不同的 DOM**（Android WebView UA 返回 10922 字节，桌面 UA 返回 18214 字节）：

| | 桌面 UA 页面 | 移动 UA 页面 |
|---|---|---|
| 密码表单 | `form#pwdFromId` | `form#loginFromId`（**两个表单重复使用同一 id**） |
| 密码明文域 | `input[name=passwordText]` | `input[name=userPassword]` |
| `?service=` 注入 | `utils.setUrlParam("pwdFromId","?service",…)` 正常 | `$("#loginFromId").attr(...)` 只命中**文档中第一个（隐藏的动态码）表单**，真正用于提交的密码表单 action 未被补 `?service=` |

后果：
1. `auto_login.js` v2 只查找 `#pwdFromId`，在手机上**必然找不到** → 注入是空操作，自动登录从未真正执行；
2. 页面自身的登录按钮同样受重复 id 影响（`$("#loginFromId").submit()` 只会提交隐藏的动态码表单），用户手动验证滑块后提交的表单不带 `service`，CAS 无法跳回 jwapp → 停留在认证域 → 再次点击又触发认证，形成循环。

### 修复

`auto_login.js` v3：改为**DOM 无关**的定位方式——用 `#pwdEncryptSalt`（两种布局都存在）反查所属表单；明文密码域同时兼容 `passwordText` / `userPassword`；提交前若 action 缺少 `service=` 则按当前页面 URL 的 `service` 补齐（等价于桌面版 `utils.setUrlParam` 的效果）；用 `HTMLFormElement.prototype.submit.call(form)` 绕开重复/被遮蔽的提交控件；失败时上报 action 与页面错误提示。

### 验证

- 以 Android WebView UA 端到端复跑（tools/output/test_mobile_flow.py）：移动版页面 + 加密密码（不含明文域）+ `?service=` → `MOBILE-UA LOGIN OK -> https://jwapp.hqu.edu.cn/jwapp/sys/emaphome/portal/index.do?forceCas=1`，随后 portal 200 且无 CAS 重定向。
- `node --check` 通过；`gradle :app:assembleDebug` 成功；aapt 确认 versionCode 4 / 0.2.2。
- 产物：`HuaYuanTimetable-0.2.2-mobilefix.apk`（18.9MB，debug 签名）。
- 移动端页面 HTML 证据留档：tools/output/login_mobile_ua.html、mobile_login.js、utils.js。

### 未验证

- 修复后的手机实机流程仍待用户复验（本版为针对实机反馈的定向修复）。
- 模拟器不可用（本机 Android SDK 无 emulator 组件与 system-images），无法做设备级自动化回归。

## 0.2.1 补编（2026-09-17，ERR_CONNECTION_ABORTED 修复）

### 回归问题

用户实机 0.2.1 遇到 `https://jwapp.hqu.edu.cn/` → `net::ERR_CONNECTION_ABORTED`，登录流程根本没走到 CAS 入口。

### 根因

0.2.1 首轮改动**删掉了 `showBrowser()` 末尾的 `loadUrl(HOME)`**，改为在 `startAutoLogin()` 里用 `web.loadUrl(HOME)` 检测会话——但：
- HOME 是 `https://jwapp.hqu.edu.cn/`，没有 service 参数，jwapp 侧对无目的地的直连首页处理异常，导致 `ERR_CONNECTION_ABORTED`；
- WebView 创建后没有初始加载，`startAutoLogin()` 紧接着 loadUrl 有时序竞态。

### 修复

- **恢复 `showBrowser()` 末尾加载 `LOGIN_URL`**（带 service 参数的 CAS 入口）：已登录 → 自动 302 到 jwapp；未登录 → 显示登录页。单一 loadUrl 源，时序稳定。
- 新增 `initialLoadPending` 标志：`showBrowser()` 首次创建 WebView 时置 true，`startAutoLogin()` 检查到它就只设状态不 loadUrl，等 `onPageFinished` 自然来；任何 `onPageFinished` 都清掉它。
- 新增 `loggedOutFlag`：`logout()` 清 cookie 后 URL 还没来得及变时，`startAutoLogin()` 看到 `wasLoggedOut=true` 强制走 CAS 入口，防止把刚清掉的会话当成"仍有效"。
- 放宽 `pollLoginResult` 超时 45→60，20 次后显示"正在识别滑块验证码… 可能需要一分钟"。
- `logout()` 的 `loadUrl(HOME)` 也改为 `loadUrl(LOGIN_URL)`，保持一致。

### 验证

- `gradle --no-daemon :app:assembleDebug` 构建成功；aapt 确认 versionCode 3 / 0.2.1。
- 产物：`HuaYuanTimetable-0.2.1-loginfix.apk`（18.9MB，debug 签名）。

## 0.2.1（2026-09-17，登录循环修复）

### 问题与根因（用户实机反馈）

- 症状：点“登录”→ 滑块认证流程触发并完成 → 页面不跳转；再点“登录”→ 重新触发认证，形成循环。
- 根因（桌面复现实证）：
  1. **手机版滑块求解缺少粗扫描兜底**。桌面实测当轮 NCC 估计值 128 与真实位置 190 偏差 62px，桌面版靠 0..238 步进 10 的全范围扫描才命中；手机版只有估计值 ±15 的小窗口，必然失败 → 表单从未提交 → 永不跳转；每次重试都重新加载认证页 → 死循环。
  2. `showBrowser()` 末尾 `loadUrl(HOME)` 与 `startAutoLogin()` 的 `loadUrl(LOGIN_URL)` 存在双重加载竞态，可造成同页二次注入（二次弹出滑块）。
  3. 每次点“一键同步”都无条件重载登录页，不复用有效教务会话/已加载页面 → 重复触发认证。
  4. 提交后无“仍在登录页”检测，失败时用户看不到服务端原因。
- 桌面对照实验（tools/output/test_webview_post.py）：用 requests 精确复现手机 `form.submit()` 的完整字段集（含未禁用的明文 passwordText + 加密 password + captcha 空 + 全部隐域），服务端接受、登录跳转成功 → 字段集与加密本身无问题，排除该假设。

### 修复内容

- `auto_login.js` v2：防重入守卫（`__LOGIN_RUNNING`）；先填账号密码再解滑块（手动兜底始终可用）；滑块探测序列对齐桌面版（估计窗口 [0,±3,±6,±9,±12] + 粗扫描 0..230 步进 10）；镜像页面 `checkForm()` 行为（写入加密 `#saltPassword`、禁用明文字段）；提交后 5 秒仍在登录页则上报 `still_on_login` 及服务端错误提示。
- `MainActivity.java`：会话优先流程（先测教务会话，有效则直接同步、不触发认证）；复用已加载的登录页（不整页重载）；同 URL 3 秒内 `onPageFinished` 去重防二次注入；登录失败按 stage+detail 显示具体原因。
- 版本 versionCode 3 / versionName 0.2.1。

### 验证

- `node --check` 通过（auto_login.js v2）。
- Python 单元测试 14/14 通过。
- `gradle --no-daemon :app:assembleDebug` 构建成功；aapt 确认 versionCode 3 / 0.2.1，ABI arm64-v8a + x86_64。
- 桌面端用同一凭据全流程复跑成功（含本轮难例滑块：粗扫描第 25 次命中 move=190）。
- 产物：`HuaYuanTimetable-0.2.1-loginfix.apk`（18.9MB，debug 签名）。

### 未验证及已知限制

- 0.2.1 修复后的手机实机流程仍待用户重新安装验证（本版为针对实机反馈的定向修复）。
- 粗扫描兜底最坏情况约 33 次验证 × (RTT+300ms 节流)，弱网下自动登录可能耗时 30–60 秒；45 次轮询上限内可完成。
- 其余限制同 0.2.0（KBLB≠'1' 跳过、验证码组件更换需重适配、debug 签名）。

## 0.2.0（2026-09-17）

### 真实系统端到端验证（开发机 Python 复现）

- 统一认证自动登录实测成功：登录页解析 → 滑块验证码（彩色 NCC 缺口识别 + 服务端 oracle 校准 + 节流）→ AES 加密提交 → jwapp 会话，全程约 20 秒。
- 课表接口实测：`/jwapp/sys/wdkb/modules/xskcb/cxxszhxqkb.do`（POST，需 `XNXQDM`/`XNXQDM2`/`XNXQDM3` 同值）返回整学期排课行，含 KCM/SKJS/JASMC/SKXQ/KSJC/JSJC/SKZC(周次位图)/YPSJDD 字段。
- 真实账号（2026-2027-1 学期）抓取 13 条排课记录，全部正确转换为应用 schema 并保存 `courses_raw.json` + `schedule.json`；字段抽查（星期、节次、教室、周次位图展开）与教务一致。
- 学期列表接口 `cxxljc.do` 返回 14 条学期记录，当前学期自动判定正确（2026-2027-1，起始 2026-09-14）。

### 构建验证

- 工具链：Microsoft OpenJDK 17.0.20.1、Gradle 8.13（本地）、Android SDK Platform 35 / Build-Tools 35.0.0、Chaquopy 16.1.0。
- `gradle --no-daemon :app:assembleDebug` 成功（2 分 37 秒，43 tasks）。
- Python 单元测试 14/14 通过：新增 `weeks_from_bitmap` 位图周次、`sync_wdkb` 真实数据形状（含去重、KBLB 过滤、坏行跳过）、坏载荷不覆盖旧数据。
- 注入脚本 `auto_login.js`、`fetch_kb.js` 通过 `node --check` 语法校验。
- APK 检查：包名 cn.hqu.timetable.local；versionCode 2 / versionName 0.2.0；minSdk 24 / targetSdk 35；ABI arm64-v8a + x86_64；`apksigner verify` 通过（debug 签名）；assets 含 auto_login.js、fetch_kb.js；`assets/chaquopy/app.imy` 内含带 `sync_wdkb` 的 schedule.py。

### 未验证及已知限制

- Android 模拟器此前未能启动，本次未做实机/模拟器安装运行与点击交互测试；WebView 注入流程的逻辑与桌面 Python 复现一致，但手机端表现待实机确认。
- 滑块识别依赖验证码图像特征（半透明 overlay + NCC 峰值），校方更换验证码组件后需重新适配。
- 调课、临时安排、考试周等非 `KBLB='1'` 记录会被跳过；个别课程可能因此缺显，以教务页面为准。
- 密码保存在应用私有目录（用于自动登录），设备未加密或 root 环境下有被读取的风险；可在“账号设置→清除账号”删除。
- APK 为 debug 签名测试版，非生产发布包。

## 0.1.0-preview（2026-09-17，历史）

- JDK 17、Gradle 8.13、Android SDK 35、Chaquopy 16.1.0 环境构建成功；Python 单测 11/11；APK ZIP 完整性与 v2 签名通过。
- 当时教务站 502，未验证真实登录与课表（0.2 已补齐）。

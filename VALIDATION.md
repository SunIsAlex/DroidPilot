## 0.9.0 — 2026-09-20

应用、通知、悬浮窗、输入法、模块和文档统一显示 DroidPilot。包名及内部通信/密钥标识保留以兼容升级。编译与 APK 签名校验通过；发布前检查待提交文件并排除密钥、签名文件、日志、构建产物及缓存。本次未运行模型任务或额外真机功能测试。

## 0.8.1 — 2026-09-19

针对用户重复反馈，仅复现和验证悬浮窗，不运行模型任务、不读取短信/号码。

- 旧版：用户报告已真实点击后，窗口为 FINISHED、alpha=0.45；服务诊断 dispatch/down/up/click 均为 0。直接 Root 启动 MainActivity 正常。
- 修复：窗口 alpha 固定为 1，半透明效果改为 panel.setAlpha(0.45)。模拟点击收到 down/up，跳转结果 root_returned_true，topResumedActivity 为 dev.navix.agent/.MainActivity。
- 拖拽：窗口从 (368,188) 移到约 (220,504)，click 计数未增加；拖回后点击仍可进入 App。
- 再从桌面点击复查：click=3，MainActivity 成为前台。无模型调用。

## 0.8.0 — 2026-09-19

新增只读短信/活动 SIM 号码工具及 Android 权限，Goal ask_user 阻塞等待用户回答、问答卡片、悬浮窗/通知入口，两种 Provider 的工具清单同步。仅构建、安装、授权与中转重启；未读取实际短信/号码，未运行测试或模型任务。

## 0.7.1 — 2026-09-19

修正结束后点击跳转路径（Root 显式启动助手，普通启动作为回退），增加父容器统一触摸处理、拖动阈值、位置保存、屏幕边界及旋转处理。仅构建与安装，未进行功能测试，具体设备交互待用户验证。

## 0.7.0 — 2026-09-19

新增独立 OverlayService，任务结束后保留半透明入口，点击打开 App；运行中点击暂停/继续，支持 Codex 中转暂停下一轮及暂停计时处理。仅构建、安装和重启对应服务，未运行测试，未启动模型任务验证交互。

## 0.6.0 — 2026-09-19

新增 Service 生命周期管理的任务状态悬浮窗、设置开关、系统权限入口，接入 DeepSeek / Codex 工具执行阶段。按用户此前要求未运行测试，仅构建、安装并为本机开启悬浮窗权限；未启动模型任务验证实际显示和触摸穿透。

## 0.5.1 — 2026-09-19

更新共享任务授权提示词及 DeepSeek 结构化拒绝提示。仅构建和安装，未运行测试，未调用模型验证拒绝率，未实际发布评论。不能据此认定第三方模型不会拒绝。

## 0.5.0 — 2026-09-19

按用户要求，本次不运行任何测试。仅编译、打包、签名。新增应用任务切换和 InputConnection 输入路径，实际设备兼容性由用户验证。以下为旧版本历史记录，不代表 0.5.0 已验证。

# 当前验证结果（2026-09-18）

目标：PJX110，Android 16 / API 36，arm64-v8a，KernelSU。

已通过：
- APK 编译及 apksigner 签名验证。
- KernelSU ZIP 安装；不挂载系统分区。
- 当前开机手动启动服务，`navi_root` 运行，ping 返回 UID 0。
- AppProbe 在配套 APK 的真实 UID 10241 下连接 Root Unix socket 成功。
- 普通 Termux UID 10232 连接被返回 unauthorized；Root CLI 可连接。
- Android 16 UiAutomation 主 Looper 修复后，取得附带测试页的真实语义树与资源 ID。
- 测试页密码值未出现在观察 JSON 中；一次点击返回成功，随后旧节点被拒绝。
- Java 协议测试：中文/emoji 保留，未知工具、任意命令、非法节点、缺失字段、包名注入、超长输入均被拒绝。
- 系统语音 Activity 可解析为 GoogleTTSActivity；尚未实测语音识别结果。
- DeepSeek /models 可达，未带密钥请求返回 HTTP 401。

尚未通过 / 待验收：
- 点击后的文本状态、长按、中文输入、滚动完整闭环：测试中手机进入锁屏，用户解锁后重跑 tests/device_smoke.py。测试页已添加保持亮屏与禁止输入框自动聚焦。
- 当前最终构建尚未验证开机自启动（本次未重启）。模块已安装到 KernelSU 待更新目录，当前通过 service.sh 手动启动。
- DeepSeek 真实请求、语音端到端任务、任务运行期间停止/锁屏处理：需要用户在 App 中填写 API key 后验证。
- 没有验证其它 Android 版本、ROM、多用户或自绘页面。

产物：build/droidpilot-debug.apk、build/droidpilot-ksu.zip。
源码和构建方式见 README.md；本文件只记录实际观察，不把代码实现视为测试通过。

## 0.2.0 验证补充

- 新增 launch_app、start_intent、am 独立模型工具；移除启动前的控件树读取及必须先 list_apps 的依赖。助手只在需要观察原先页面时隐藏，启动时不再主动回桌面。
- 主机测试通过：启动参数、Intent URI/分类/flags/typed extras、中文与 shell 特殊字符原样传递、参数错误、输出截断/持续排空、超时和退出码 0 的语义错误。
- 构建/签名通过；0.2.0 APK 与模块已安装，并重新启动 Root 服务。
- 实机命令与前台页面验证分别记录；用户切换页面会影响观察结果，不能将命令成功当成持续前台状态。
- `tests/am_device_smoke.py` 全部通过：直接按包名启动设置且不依赖 UI Automation 连接、显示设置 Intent、包含 URI/分类/flags/typed extras 的显式 Intent、原始 am help、无法解析 Activity 的诊断、未知子命令失败。
- 本轮未进行真实 DeepSeek 对话验收；验证覆盖模型调用解析、命令执行和实机系统返回结果。

## 0.3 / 0.4 验证补充（2026-09-19）

- Provider：DeepSeek API / Codex Termux；界面按选项显示独立模型和思考配置，Codex 不要求 DeepSeek 密钥。
- Codex CLI 0.153.3 登录及 App Server 握手通过；早期真实 Codex -> Root am(help) -> 模型结果返回通过。
- Android App 直接连 Termux socket 被 SELinux 拒绝，改为现有已鉴权 Root socket 转发；真实 App UID 下连接检查已通过，未关闭 SELinux。
- 修复连接检查结束后立刻启动任务导致 bridge busy 的竞争：检查不占任务锁，任务启动允许等待上一任务清理。
- 主机测试通过：思考 enabled/disabled/default 参数、完成工具的证据检查、操作 Toast 描述不包含待输入文字；原有命令及协议测试通过。
- Python 中转测试 7 项通过：帧分片、坏长度、工具校验、动态工具往返、断开终止子进程、思考能力校验、Goal 在文字回复后继续且超过 25 次工具调用后完成。
- 实机 model/list 验证当前模型最低均为 low；没有 none，不将轻量思考标作关闭。
- DeepSeek 使用 App 已保存的账户实测，thinking enabled / disabled 请求均被 API 接受；未输出密钥或内部推理内容。
- 实际 AgentService 前台服务的 DeepSeek Goal 流程通过：只读 am(help) 工具派发、操作 Toast 调用路径、完成依据检查、Goal 正常结束。测试后恢复原 Provider/思考/Goal 配置。
- 实际 App UID 经 Root 转发连接 Codex，low 思考 + Goal 实测通过，完成 am(help) 后调用 complete_task 正常结束。
- 最终 0.4.0 APK、KernelSU 模块已安装，Root 与 Termux Codex 中转均已启动。

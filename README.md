# DroidPilot — KernelSU 手机语音操作原型

独立实现“语音指令 → 所选 Provider（DeepSeek API / Codex Termux）→ Android 语义控件操作 → 重新观察”。目标为当前 Android 16、arm64、KernelSU 手机；其它 ROM 尚未实测。

## 安装与使用

产物：`build/droidpilot-debug.apk` 和 `build/droidpilot-ksu.zip`。安装 APK，再在 KernelSU 中安装 ZIP。模块不挂载 `/system`，无需 metamodule。安装后正常重启可启动服务；开发调试可通过 Root 手动运行安装目录里的 `service.sh`，不必立即重启。

1. 打开 **DroidPilot**，确认“KernelSU Root 服务已连接”。
2. 选择 **Provider**：DeepSeek API 或 Codex Termux。DeepSeek 填写 API key 并检测/选择模型；Codex 复用 Termux 登录，模型可留空使用本机默认，点击“检测 Codex 连接”。两个 Provider 独立保存配置，只显示当前 Provider 的设置。
3. 先打开目标应用，再打开助手，输入或说出指令，例如“打开设置，找到显示设置”。语音识别后先显示文本，点击“开始执行”。需要打开 App 时助手直接跳转目标应用；需要读取原先页面时才退到后台。
4. 从通知栏或助手内点击“停止任务”。回到助手查看最后结果。普通模式每任务最多 25 个模型回合、8 分钟；Goal 模式取消这两项限制。单次网络请求仍有超时；停止会中断网络和后续动作，已经发出的点击不能撤回。

语音使用系统 `ACTION_RECOGNIZE_SPEECH`，由安装的识别服务处理音频，不经过 DeepSeek。没有可用语音 Activity 时可使用文本。尚未实现唤醒词、全双工语音、硬件按键入口、截图/OCR兜底。

运行任务时，页面文字、控件描述以及需要时的应用名称/包名会发送给所选 Provider。DeepSeek 使用 `https://api.deepseek.com`；Codex 使用本机 Codex 配置及登录。本地默认不保存页面、任务内容或 API 响应日志。密码节点文字被遮蔽且不允许输入，助手配置页面不向模型暴露。页面中其它个人信息不是自动脱敏的。

## 后台虚拟屏（0.10.1，实验性）

在助手中开启 **后台虚拟屏（实验性）** 并保存，再开始任务，例如“打开某个应用并查找……”。Root 读取主屏实际分辨率与逻辑 DPI，创建相同配置、请求 30 Hz 的隐藏虚拟显示器，具有独立焦点且禁止抢占主屏全局焦点。目标应用实际运行在副显示器，而不是把普通后台 Activity 强行当作前台点击；你可以继续操作主屏的另一个应用。此开关默认关闭，运行中的任务使用启动时选定的模式。

- `launch_app` / `start_intent` 指定虚拟屏，`switch_app` 尝试迁移已有任务；观察只从指定显示器读取窗口，节点缓存绑定显示器。点击、长按、滚动和 `phone set_text` 使用该窗口的语义节点，返回键指定显示器。
- 后台模式不隐藏主屏助手、不发送全局 Home，不使用会切换主屏键盘的 `input_text` / `input_key`。Termux 等没有 `ACTION_SET_TEXT` 的自定义编辑器暂不支持后台输入。通用 `am` 在后台模式仅允许查询子命令，正常前台模式的能力不变。关闭/失效的显示器会报错，不自动退回主屏执行。
- 任务结束后副屏继续存在，方便下一任务恢复应用。在助手中先停止任务，再使用“关闭后台虚拟屏 / 将应用移回主屏”释放资源，先逐个恢复任务到主屏全屏模式，确认成功后再释放虚拟屏；失败时保留虚拟屏供重试。移回时会改变主屏内容。Root 进程退出或手机重启也会移除虚拟屏。视频可能持续播放并消耗资源，音频仍与主屏共享。
- **当前仅在本机 Android 16 验证基础能力**，实现要求 Android 14+ 的独立焦点标志及兼容的隐藏接口。应用可拒绝副屏、重新创建 Activity，或自行将登录、授权、支付、深链等页面打开到主屏；本模式不是虚拟机或系统级应用隔离，无法承诺任何第三方应用都完全不影响主屏。同一应用任务不能同时留在主屏供你操作又被 AI 移到副屏。
- 锁屏仍按原任务规则暂停/停止，不提供锁屏绕过。没有增加截图识别兜底，也没有验证第三方应用中的实际业务操作。

**布局与最近任务（0.10.1）**：取消固定的低 DPI，避免跨屏后出现字体缩小或平板布局。主屏分辨率、旋转或 DPI 改变后，请关闭旧虚拟屏再重开；现存虚拟屏配置不匹配时会提示，避免继续使用旧配置。应用自身缓存的错误布局可能需要用户自行重开该应用。

部分桌面不展示副屏实际任务。DroidPilot 使用 Android [`ActivityManager.addAppTask`](https://developer.android.com/reference/android/app/ActivityManager#addAppTask(android.app.Activity,%20android.content.Intent,%20android.app.ActivityManager.TaskDescription,%20android.graphics.Bitmap)) 为它们创建带“DroidPilot 后台”标签的**返回入口**；点击后恢复原有任务到主屏，不重新发送 Launcher Intent。入口缩略图不包含目标应用截图，划掉入口不会关闭真实应用。添加入口依赖助手 Activity 仍在系统任务栈中，ROM 也可能限制入口数量或展示；无法展示时，使用助手里的“查看后台应用 / 移回主屏”。模型运行期间先停止任务再移回，以免跨屏操作冲突。入口只保留任务 ID 和组件名，恢复时重新校验，失效任务不会自动启动新实例。

定向验证脚本：`python tests/background_device_smoke.py`。需要当前 APK 已复制为 `/data/local/tmp/navi-agent.apk`、Root 模块运行，且没有现存后台虚拟屏。只打开自带控件测试页，验证语义操作与显示器约束，清理测试任务后关闭副屏，不调用模型、不读取短信或其它应用页面。`python tests/background_return_device_smoke.py` 另外检查后台返回入口、恢复原任务和关闭前迁移；会打开助手及自带测试页。

## 半透明窗口点击修复（0.8.1）

本机旧版将 `WindowManager.LayoutParams.alpha` 设为 0.45 后，真实/模拟点击均未到达悬浮窗触摸处理函数。现在窗口 alpha 固定为 1，仅通过内容 View 的 alpha 实现运行时 92%、结束后 45% 的显示效果。修改后已进行定向真机验证：结束状态点击成功打开 MainActivity；拖动改变位置而不触发点击。服务 dump 保留触摸/点击计数和启动阶段，供排查使用，不记录任务内容或短信。

## 短信、号码与 Goal 提问（0.8.0）

- `read_sms`：通过 Android 短信 Provider 只读查询，默认收件箱最近 10 条，单次最多 20 条；支持 inbox/sent/all、发件人精确匹配、Unix 毫秒起始时间、按 ID 倒序分页。每条正文最多 2000 字，截断会标注；不标记已读、不发送、不删除，当前不读取 MMS/RCS。
- `get_phone_numbers`：读取活动 SIM 的号码，支持多卡。依赖 READ_PHONE_STATE / READ_PHONE_NUMBERS；运营商未写入号码时返回空，不猜测，也不将 SIM 号码当作任意账户的绑定号码。
- `ask_user`（仅 Goal）：缺少必要信息时，模型提出问题和最多 4 个选项。悬浮窗/通知提示等待回答，点击进入助手的问答卡片，也可输入自由回答，点击“提交回答并继续”。工具等待真实回答后返回同一任务；等待时不继续手机操作或发起下一轮模型请求，可随时停止。问题/答案仅保留在当前进程内，系统终止任务后不自动恢复。

本机安装时通过 Root 授予读取权限；助手新增“短信 / 号码权限设置”。工具仍遵守 Android 实际权限，撤销后返回权限错误。**任务调用工具时，读取到的短信/号码会发送给当前选择的 DeepSeek 或 Codex。** 提示词要求按任务需要限定范围，短信正文按不可信数据处理；不会在 Toast、悬浮窗或本地日志里展示短信正文。用户回答也会作为工具结果发送给当前模型。

本次仅构建、安装和权限配置，未实际读取短信/号码，未调用模型或运行功能测试。

## 悬浮窗点击与拖动修复（0.7.1）

任务结束后的入口点击通过已鉴权 Root 通道打开 MainActivity，减少后台 Service 拉起 Activity 被系统拦截的情况；Root 不可用时尝试普通跳转并提示使用通知入口。触摸事件统一由悬浮窗容器处理，避免标题/内容区域点击行为不一致。

按住并移动即可拖动，超过系统 touch slop 后不会触发暂停/打开。松手保存相对位置，下次显示时恢复；旋转屏幕或窗口尺寸变化时重新限制在可见区域，避开系统栏和刘海区域。运行中、暂停中、结束后均可移动。

本次仅构建和安装，未运行功能测试。

## 任务悬浮状态与暂停（0.7.0）

任务运行期间，屏幕右上方显示状态条，两种 Provider 共用：蓝色“模型思考中”、橙色“正在操控手机”、青色“正在读取页面”、灰色“等待中”，以及暂停/停止状态。下面显示步骤或操作摘要。“思考中”表示等待模型生成下一步，包含网络等待，不展示模型内部推理。

- **运行中点击**请求暂停；有请求尚未返回时显示“正在暂停”，到达操作检查点后显示“已暂停”。再次点击恢复同一个任务，也可使用主界面“暂停 / 继续任务”。已经发出的手机动作或模型请求不会被撤回，新的工具操作在暂停检查点等待。DeepSeek 暂停后不再发起下一次请求，Codex 中转收到暂停信号后延后下一轮。普通任务的 8 分钟上限扣除相应暂停等待时间，平台网络超时和系统前台服务时限仍适用。
- **任务结束后保留窗口**，不透明度从运行时 92% 降至 45%；显示“任务已结束”，避免把中止/失败误报为成功。点击打开 DroidPilot 查看完整结果。下一次任务复用同一悬浮窗。
- 悬浮窗由独立的前台服务维持，有“关闭悬浮窗”通知操作，关闭窗口不会停止任务。也可关闭“任务悬浮状态条”并保存。强制停止 App、系统回收或重启后不会自动重启旧任务或窗口。
- 状态条不抢键盘焦点，但现在会接收其区域内的点击，不再穿透到下方应用；窗口外可正常操作。0.7.1 起支持拖动并记忆位置。停止任务仍使用任务通知或助手按钮。

需要系统悬浮窗权限；本机安装时通过 Root 开启，其他设备可点击“悬浮窗权限设置”授权。部分禁止悬浮窗的系统/安全页面可能不显示。本版按此前要求仅构建和安装，没有运行自动化或真机任务测试。

## 自动化授权说明（0.5.1）

DeepSeek / Codex 共用的任务提示词明确了正常浏览和互动：自动刷视频按连续浏览理解，不默认当作刷播放量；用户明确要求发布评论时，可以发布一次，明确要求模型撰写并发布时也可执行。仅要求草稿不会发布，目标或撰写要求不清楚时才询问。模型必须根据当前页面执行，并检查发布结果以免重复发送。

这用于减少任务误解及无必要的重复确认，不覆盖 Provider 自身的限制。DeepSeek 返回结构化 `refusal` 或 `content_filter` 时，界面显示模型拒绝原因及任务未完成，Goal 不会对此持续重试。普通文本形式的拒绝没有可靠的结构化标记，仍沿用各 Provider 原来的回复处理（包括 Goal 的一般续轮逻辑），未增加拒绝关键词猜测或针对拒绝的强制重试。本次继续按用户偏好不运行测试，也没有实际浏览视频或发表评论。

## 应用切换与输入（0.5.0）

- `switch_app(package, launch_if_missing=true)`：通过系统任务管理接口将最近的已有任务移到前台，不重新发送 Launcher Intent；找不到已有任务才启动。设置 `launch_if_missing=false` 可禁止首次启动。系统已回收进程时仍可能重建页面，不能保证应用自身不刷新。目前面向主用户 user 0。
- 模型默认优先 `switch_app`；保留 `launch_app`、`start_intent` 和原始 `am`。普通启动也移除了 `RESET_TASK_IF_NEEDED` 标志。
- `input_text(package,text)`：临时切换至 DroidPilot 输入法，通过当前编辑器的 `InputConnection.commitText` 输入模型生成的 Unicode 内容，面向 Duolingo、Termux 等不支持 `ACTION_SET_TEXT` 的输入区域。先点击输入区域使其获得焦点；插入光标处或替换选中内容，不清空全文。标准编辑框需要替换全文时仍用 `phone set_text`。
- `input_key(package,key)`：支持 ENTER、TAB、BACKSPACE、ESCAPE。文字工具只接受单行、最多 2000 字符，不自动回车；Termux 执行命令必须单独调用 ENTER。输入工具返回的是编辑器接收状态，模型仍需观察确认，不能盲目重试。
- 输入请求只允许 Root 连接本地输入法 socket，核对前台应用和编辑器包名，拒绝密码框及助手配置页；结束时恢复原来的输入法和 DroidPilot 输入法原启用状态。若进程被强杀导致键盘未恢复，可在系统输入法选择器手动切回。
- DeepSeek / Codex 共用新工具及 Toast。本次按用户要求仅构建，不运行自动化或真机功能测试，Duolingo / Termux 的实际兼容性待用户验证。App、Root 模块和 Codex 中转脚本均需更新；旧中转进程需要重启。

## Codex Termux（0.3.0）

本机已安装并登录 Codex 时，无需在 App 内填写 OpenAI 或 DeepSeek API key。接入使用官方 [Codex App Server](https://learn.chatgpt.com/docs/app-server) 的 `thread/start.dynamicTools` 和 `item/tool/call` 接口；当前验证 CLI 版本为 0.153.3。这些动态工具接口仍为实验性接口。

在 Termux 中启动中转服务：

```sh
sh ~/DroidPilot/codex-bridge/start.sh
```

然后在 DroidPilot 选择 **Codex Termux**，点击“检测 Codex 连接”，保存配置即可使用。启动后即可使用；**手机重启或 Termux 进程被系统终止后，需要重新执行上述命令**。Root 模块自启动与 Codex 中转启动是两件事，本版未安装额外的 Termux 开机服务。

通信链路：App → 已鉴权 Root socket → Termux Unix socket → `codex app-server --stdio`。Root 转发解决 Android SELinux 对跨 App socket 的限制；Root 端核验 Termux UID，未关闭 SELinux。Codex 进程始终运行在 Termux 用户下，登录文件不复制、不传给 App，不开放 TCP 端口。

每个任务创建独立的 ephemeral Codex thread，复用同一套手机工具、锁屏检查及运行策略。普通模式限制 25 次工具调用和 8 分钟，Goal 模式持续至完成工具确认或手动停止。线程关闭环境访问并禁用 shell、多代理、网页搜索等与手机任务无关的能力；模型名留空时使用本机 Codex 默认配置。动态工具结果由 App 执行后返回，不将 Codex 输出当作直接 shell 命令。停止或 App 断开连接时尝试 interrupt，并终止该任务的 app-server 进程组。已经发送给 Android 的操作不可撤销。

中转服务不记录任务和工具数据，只输出启动状态到 `codex-bridge/bridge.log`；Codex 自身依其配置处理运行状态和遥测。中转服务一次只接受一个任务，忙碌时明确返回错误；模型或登录不可用时显示错误，不静默切换到 DeepSeek。

## 思考、操作提示与 Goal（0.4.0）

- **思考设置按 Provider 保存**。DeepSeek 提供自动/开启/关闭，开启和关闭发送 `thinking.type=enabled/disabled`，自动不发送覆盖值。原始 reasoning_content 随模型消息保留，以支持思考模式下连续工具调用，不在界面展示内部推理文本。
- **Codex 思考设置**提供自动/开启/关闭/轻量。运行前使用 `model/list` 核实当前选中模型支持的强度：开启优先 medium，轻量优先 low；关闭要求支持 none，不支持则报错并保持任务未完成。当前本机列出的模型最低均为 low，因此不能承诺真正关闭。自动保留本机默认，不修改全局 Codex 配置。
- **每个工具执行前显示 Toast**，内容如“打开应用：包名”“点击控件 3:12”“执行 am：start”。不回显输入框的待输入文字。通知栏同时更新当前操作；Android 可能限制后台 Toast 的显示频率。
- **Goal 模式默认关闭**。开启后不因 25 步、8 分钟或模型的一段普通回复结束。DeepSeek 和 Codex 都会继续请求下一轮，直到模型调用 `complete_task(summary,evidence)`，App 核实已收到成功的页面观察或查询结果后接受完成。此为 DroidPilot 的共享运行模式，不是直接启用 Codex CLI `/goal`。
- **完成依据的边界**：App 检查有真实工具结果和非空证据，但无法自动验证任意业务目标的语义；完成判断仍依赖模型。已有证据不是支付、提交等业务成功的万能证明。
- Goal 锁屏时等待解锁，停止按钮始终有效。DeepSeek 的临时网络错误、429/5xx 会等待重试；模型/登录/权限等无法恢复的错误标记“未完成”，不会伪报成功。Codex 的不可恢复协议或服务错误同样标记未完成。
- DeepSeek 长任务接近上下文上限时保留原始目标和最近 30 条截断的工具记录，要求重新观察后继续；早期细节可能不在当前上下文中。Codex 使用同一 thread 继续运行并由自身管理上下文。
- Goal 不代表进程永不退出：Android 前台服务系统时限、系统杀进程、重启和用户停止仍会终止运行。本版不提供重启后恢复未完成任务；系统时限回调会明确标记未完成。

参考：[DeepSeek thinking mode](https://api-docs.deepseek.com/guides/thinking_mode/)、[Codex model/list](https://learn.chatgpt.com/docs/app-server)。

## 实现

- **KernelSU Root 服务**：`app_process` 加载模块 APK，创建 `UiAutomation`，按需连接 Android 语义控件接口；无需手动开启 AccessibilityService。开启 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`，不主动停用已有无障碍服务。任务结束断开；90 秒未收到请求也会释放连接。
- **本地通信**：抽象 Unix socket `dev.navix.agent.root.v1`，4 字节大端长度 + UTF-8 JSON，最大 256 KiB。服务只接受 Root 和启动时确定的配套 APK UID，App 验证对端 UID 为 0。不开放 TCP/HTTP 端口，不向 AI 暴露任意 shell。
- **Agent App**：前台服务负责所选 Provider 的工具调用，DeepSeek 使用 Chat Completions，Codex 使用 App Server dynamic tools，按钮启动任务，通知栏停止。每次操作后自动重新观察；旧节点句柄、页面/目标变化、超过 30 秒的句柄会被拒绝。普通模式锁屏、连续失败或达到上限时终止；Goal 模式锁屏暂停，普通操作失败时允许模型继续调整。
- **持久性**：模块在启动完成后启动一次；若进程退出，可通过 KernelSU 模块操作按钮再启动。模块卸载脚本停止命名进程，APK 和用户配置单独卸载。模块禁用的效果在重启后生效。当前用户为主用户 0，多用户/工作资料未适配。

### 直接启动与 Activity Manager 工具（0.2.0）

独立工具调用无需先读取控件树，也无需先回桌面：

```text
launch_app({"package":"com.android.settings"})
start_intent({"action":"android.settings.DISPLAY_SETTINGS"})
start_intent({"action":"android.intent.action.VIEW","data":"https://example.com","package":"com.android.chrome"})
am({"args":["start","-W","-a","android.settings.SETTINGS"]})
am({"args":["help"]})
```

`launch_app` 接受包名；未知包名可通过 `phone({"op":"list_apps"})` 查询，返回值也包含 launcher component。`start_intent` 支持 action、data URI、mime_type、package、component、categories 数组、flags 字符串，以及 extras 数组：`[{"key":"query","type":"string","value":"中文搜索"}]`。extra 类型支持 string/boolean/int/long/float/uri/component，value 统一为字符串。

`am` 接受原始参数数组，不包括 `am` 本身，不限于启动：系统支持的 broadcast、startservice、force-stop 等子命令也可调用。参数逐项传递，没有 shell 解释、变量展开或管道；复杂参数不需要额外加引号。执行器与设备 `/system/bin/am` 保持相同分发：普通子命令调用 `cmd activity`，instrument 使用 am 原入口。接口只限制参数长度、数量、8 秒命令超时和 16000 字节输出，没有业务级子命令白名单。按本次用户指令使用这些 Root 能力；超时不能撤销系统已经收到的操作。

命令结果包含 `ok`、`exitCode`、`output`、`timedOut`、`truncated`。即使退出码为 0，Android 返回 `Error:` 等错误也会标记失败。操作后 App 会附上新的页面观察；启动成功与最终任务成功分别判断。基础语法参考 [Android 官方 am 文档](https://developer.android.com/tools/adb#am)。

### 页面工具 `phone`（保留兼容 launch）

| op | 参数 | 行为 |
|---|---|---|
| observe | 无 | 当前活动窗口控件树，最多 180 个可见节点 |
| list_apps | 无 | App 查询的可启动应用名称/包名 |
| launch | package | 兼容旧调用，按包名直接启动应用；优先使用独立 launch_app |
| click / long_click | node | 执行节点原生点击/长按动作 |
| set_text | node, text | 设置输入框内容，支持中文，最多 2000 字符 |
| scroll_forward / scroll_backward | node | 对可滚动节点执行语义滚动 |
| back / home | 无 | Android 全局返回/主页 |
| wait | 无 | 等待一秒并重新观察 |

Root 协议另外提供 `ping`、`release`，不向模型暴露。模型不能直接调用 `list_apps`/`wait` 的 Root 操作，它们由 App 实现。

节点包含 `node`（例如 `3:12`）、`parent`、`resourceId`、`text`、`description`、`class`、`bounds`、`actions` 和可点击/可长按/可编辑/可滚动状态。`resourceId` 是应用暴露的 Android ID，可以为空或重复；实际操作必须使用最新快照的临时 `node`，不能编造 ID。

## 构建与验证

```sh
git clone https://github.com/SunIsAlex/DroidPilot.git
cd DroidPilot
```

为兼容现有安装，Android 包名 `dev.navix.agent`、KernelSU 模块 ID `navi_agent`、内部 socket 和密钥别名保持不变；对用户显示的名称为 DroidPilot。开发签名、凭据、日志和构建缓存不进入仓库。


在 Termux 安装 `aapt2`、JDK、`d8`、`apksigner`、`curl`、`zip` 后运行：

```sh
sh build-termux.sh
sh tests/run-host.sh
sh tests/build-device-tests.sh
```

构建脚本校验下载的 Android 35 jar 和 JSON jar 的 SHA-256。使用本地生成的开发签名；更新 APK 需保留 `.cache/debug.keystore`。测试 APK 单独安装后，用 `am instrument -w dev.navix.agent.tests/dev.navix.agent.AppProbe` 检查真实 App UID 到 Root 的通信。

`python tests/device_smoke.py` 会通过 Root 启动附带的控件测试页面，验证 ID、点击、长按、中文输入、滚动、密码过滤和过期句柄。需要手机已解锁、Root 服务运行，并将当前 APK 复制到 `/data/local/tmp/navi-agent.apk`。测试只对附带页面执行操作；测试期间不要切换页面。结果以实际终端输出为准。

## 边界

Root 不会使所有 App 自动暴露全部按钮。Canvas、游戏、部分 WebView 和无障碍语义缺失页面可能无法操作；本版明确返回限制，不增加坐标盲点兜底。一次只支持一个 Agent 会话，可能与其它 UI Automation/测试框架争用系统连接。使用隐藏构造接口的 Root 引导代码需要逐个 Android/ROM 验证。

模型提示词限制不是强制业务权限系统；付款、删除等业务行为没有通用可靠的语义识别与事务撤销能力。请先用测试页、系统设置或搜索任务验证，不把本原型当成完整的商业手机助手。未配置有效 API key 前，不宣称已完成真实模型端到端验证。

参考：
- https://developer.android.com/reference/android/app/UiAutomation
- https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo
- https://kernelsu.org/guide/module.html
- https://api-docs.deepseek.com/guides/tool_calls/

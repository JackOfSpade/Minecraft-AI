# 智能日志(Smart Logging)

## 设计目标

每条 bot 日志都会自动打上当前请求的 scope 标签(`log/BotLog.java` 的 `scopeOf` 从
`TaskManager.activeOrigin(bot)` 实时读取,调用方无需手动传参)。调试某一次玩家指令,
只需按 scope 过滤,不必翻整个 session 的全量日志。日志按服务器每次启动分目录保存在
`logs/aibot/sessions/<session-id>/`,`AIBotConfig.Logging.maxSessions`(默认 3)控制只保
留最近几次启动的日志,更早的自动清理(`log/BotLogWriter.java` 的 `pruneOldSessions`)。

设计上刻意"不记录一切",只记录判断某一类请求是否正常完成所必需的信息——日志量与排
查成本成反比,记多了反而拖慢排查。

## 预期工作流程

玩家玩一段时间后,把日志交给某个 AI(不限于 Claude,任何能读代码、读日志的助手都
适用)去核查有没有 bug。

## 自我改进原则

**核查过程中如果发现某个 scope 的日志不足以判断这次请求是成功还是失败、为什么失
败——这本身就是日志系统的缺陷,不是可以忽略的小事。** 处理方式和修任何其他 bug 一
样:找到本该记录这条信息、但当时没记的代码位置,把缺失的 `BotLog.*` 调用加上去,让
下一次同类请求的日志足够排查。不要只是在这次的排查结论里提一句"日志不够",然后把同
样的盲区留到下一次。

补充记录时范围要收紧到"这一类请求需要什么",不要因为发现一处缺口就在整个任务或整个
分类里普遍调高日志详细度——那样违背了"按需记录"的初衷。

## 通用任务日志覆盖核查(2026-09-27)

`task/TaskManager.java`对每个任务类型都会记 `task_assigned`(含 `describe()`)、
`task_paused`、`task_resumed`、`task_cancelled`、`task_completed`、`task_failed`
(含 `failureReason()`)这几条通用事件,所以任何任务哪怕自己一行日志都不写,也有
起止记录。这次按上面的自我改进原则,逐个核查了当时零日志或日志明显偏薄的任务类
(`FarmTask`、`FollowTask`、`SleepTask`、`GuardTask`、`StripMineTask`、`CraftTask`
等约 29 个),标准是"若这次请求真的失败了,只看日志(不看源码)能不能说清是哪一步、
为什么"。结论按需处理:复用同一个笼统失败原因串跨多个不同成因的,把原因串改得更具
体,或在失败前补一条带上下文的日志;某个决定(放弃一种打法换另一种、静默跳过、部分
完成当成功报告)本身会在之后才暴露、且这之前无迹可寻的,在决定发生的地方补一条;已
经用不同的、自解释的原因串区分各条失败路径的任务,不做任何改动(核查后仍有多个任务
维持零改动,例如 `HoldTask`、`FishTask`、`EatTask`——本来就够用,不为了"看起来做了
事"硬加)。没有新增逐 tick 日志,没有对整个任务类别做统一提高详细度的改动。

## Mining Assist 日志(P0 影子模式)

Mining Assist 的传感器在真实任务里只观察、只写日志,不改变 bot 的任何行为(说明见 [MINING_ASSIST.md](MINING_ASSIST.md))。这一类请求原先没有日志,所以按上面的原则为它补了下面这些事件;为遵守"按需记录",输出刻意保持低量:没有逐 tick、逐射线、逐次普通发现的日志,只有会话起止、POI 档位变化、稀有发现(每个统计窗口至多 6 条)和每分钟一条的成本摘要。配置项 `miningAssist.sense.shadowLog=false` 关闭其中的影子输出;配置、门控和错误日志不受影响。全部事件可用 `grep 'event=assist_'` 取出。

| 类别 | event | 何时写出 | 关键字段 |
|---|---|---|---|
| CONFIG | `assist_config`、`assist_harness_default` | `assist_config` 启动时一条;测试 harness 调用 `setHarnessDefaultOff` 时再补一条 `assist_harness_default`,说明 harness 默认关闭之后的最终结论 | `mode`、`mode_source`、`harness_off`、`deterministic`、`rays_per_tick`、`global_rays_per_tick`、`adaptive_throttle`、`shadow_log`、`edits_sidecar`;`assist_harness_default` 为 `harness_default_off`、`harness_off`、`mode` |
| CONFIG(WARN) | `assist_config_warning` | 配置里被忽略、截断或调整的每一项,启动时各一条 | `note` |
| CONFIG(WARN) | `assist_config_read_failed`、`assist_edits_load_problem` | `aibot.json` 或放置记录文件无法读取(fail-open,按默认/空记录继续) | `path`、`error` 或 `problem` |
| TASK | `assist_gate` | 某个 bot 的门控结论变化时(每 bot 缓存 20 tick) | `enabled`、`deny`(`mode_off`/`harness_off`/`origin`/`audit_session`/`tps_degraded`)、`mode`、`forced` |
| TASK | `assist_sense_enabled` | 一次传感会话开始(挖矿类任务、地下、门控放行)。异常围栏丢弃状态后重建的那一次不算新会话,不写(失败那条已经说明) | `task`、`mode`、`dimension`、`feet` |
| TASK | `assist_sense_disabled` | 会话结束:连续 40 tick 没有传感,短暂中断不写 | `reason`(`not_mining_task`/`gate_closed`/`surface`)、`deny`、`rays_total`、`sweeps_total` |
| TASK | `assist_state_released` | bot 停止挖矿满 2400 tick,状态被释放 | `idle_ticks`、`rays_total`、`sweeps_total`、`sightings`、`hazards` |
| TASK | `assist_poi_band` | 影子 POI 评分的档位变化(`NONE`/`POSSIBLE`/`CAVERN_ONLY`/`STRUCTURE_CERTAIN`/`MANDATORY`);安静的 bot 不写。每个 bot 两条之间至少隔 200 tick:间隔内的变化被推迟(间隔过后档位仍与上一条不同才写),来回抖动而回到原档位的不写任何行 | `band`、`t`、`s`、`c`、`e`、`cells`、`label`、`confirmed`、`trigger`、`anchor`、`biome`、`withheld`(自上一条以来被压下的档位变化数) |
| TASK | `assist_sighting` | 传感器新提名了原始价值不低于 `detour.announceMinValue`(默认 90)的稀有方块;每窗口至多 6 条 | `block`、`pos`、`value`、`dist` |
| TASK | `assist_cavern_channel_disabled` | 每个 bot 至多一条:洞穴开阔度通道因维度未列入或半径小于 12 而关闭 | `dimension`、`radius`、`reason` |
| PROFILE | `assist_sense_summary` | 传感期间每 bot 每分钟一条,会话释放时再补一条 `final=true` | `rays`、`steps`、`sweeps`、`breakthroughs`/`breakthroughs_deferred`(40 tick 间隔内被推迟的突破重启)、`peeked_breaks`/`breaks_unconfirmed`(挖掘后该格没有被观察为空位:被拒绝或不可观察,什么都没有假设)、`poi_bands_withheld`、`step_ms_avg`/`step_ms_max`、`poi_ms_avg`/`poi_ms_max`、`throttled_out`、`sightings`、`best_sighting`、`hazards`、`poi_window`、`open_fraction` |
| ERROR | `assist_tick_failed` | 协调器的异常围栏触发:每 bot 每分钟至多一条,该 bot 的状态被丢弃并暂停传感 100 tick | 异常与栈、`task`、`feet` |
| ERROR | `assist_hook_failed`、`assist_edits_hook_failed`、`assist_edits_save_failed` | 挖掘/放置钩子或放置记录写盘失败(钩子只记前几次) | `hook`/`where`/`path` |

用这些日志回答一次挖矿请求的问题:

- 传感器有没有跑、为什么没跑:看 `assist_sense_enabled`/`assist_sense_disabled` 的 `reason`,以及 `assist_gate` 的 `deny`;
- 花了多少:`assist_sense_summary` 的 `step_ms_*`、`poi_ms_*`、`rays`、`throttled_out`(先测量,再谈让它行动,即设计不变量 I11);
- 它以为看到了什么:`assist_poi_band`、`assist_sighting` 与摘要里的 `best_sighting`。这些只是传感器的提名,不代表 bot 真的挖到了。

如果某次排查发现这些日志仍不足以判断传感器为什么没有提名一个后来被挖到的矿,按上面的自我改进原则,只在传感这一类请求里补缺失的那条,不要整体调高详细度。

## Mining Assist 日志(P1 顺路捡矿)

P1 给挖矿任务加了一段"顺路绕路":走位挖一段路上原生看到的贵重矿(design 4 节),默认仍是
`sense`(不绕路),要绕路需要把 `miningAssist.mode` 显式设成 `detour`(或 `all`)。下表是 P1
新增的事件,格式与上面 `assist_*` 一行保持一致,同样不逐 tick 记录,只在阶段边界、跳过、
异常和结束时各写一条。

| 类别 | event | 何时写出 | 关键字段 |
|---|---|---|---|
| TASK | `ore_dig_detour_start` | 一次绕路开始 | `block`、`pos`、`value`、`score`、`cluster`、`members`、`pose`、`zero_transit`、`anchor`、`locked` |
| TASK | `ore_dig_detour_skip` | 某个候选或矿脉成员被跳过(排除、已认领、消失、无法站位、路线太长……);每格每 600 tick 至多一条 | `reason`、`pos`、`block`、`value` |
| TASK | `ore_dig_detour_route` | 每一次接近、追赶掉落物或返程的寻路尝试 | `leg`(`approach`/`chase`/`return`)、`to`、`result`、`attempt`、`reason` |
| TASK | `ore_dig_detour_break` | 每挖到一块 | `pos`、`block`、`breaks`、`members`、`lease_left` |
| TASK | `ore_dig_detour_seal` | 每一次封堵挖出的流体 | `cell`、`seals` |
| TASK | `ore_dig_detour_drop_lost` | 掉落物等待超时或落点不可站立 | `cell`、`waited`、`reason`(`timeout`/`no_stand`) |
| TASK | `ore_dig_detour_abort` | 绕路中止(含暂停时的中止) | `reason`(design 4.12 的中止原因)、`phase`、`pos`、`breaks` |
| TASK | `ore_dig_detour_end` | 一次绕路彻底结束(FINISH) | `reason`、`breaks`、`members`、`seals`、`drops_lost`、`ticks`、`abort` |
| TASK | `ore_dig_detour_orphan` | 协调器发现发布的绕路已经失去归属(任务被替换、结束或状态过期),代为释放认领并清空发布的元组 | `cause`(`owner_changed`/`not_running`/`phase_idle`/`stale`)、`phase`、`claims_released` |
| TASK | `ore_dig_detour_cursor_drift` | `restoreAnchorNumbers` 发现走带位置和出发前记的锚点对不上,已经按锚点修正 | `dir`、`leg`、`steps`、`len` |
| WARN | `ore_dig_detour_return_rebased` | 走位返程连续失败,只能原地放弃这次绕路并禁用本任务后续绕路(响亮记录,便于事后核查) | `reason`、`unsafe`、`at`、`anchor`、`breaks` |
| DANGER | `ore_dig_detour_lava_claimed` | 绕路自己认领了一次岩浆险情(不再走通用的 Evade/暂停路径) | `lava`、`phase` |
| TASK | `ore_dig_detour_resume_return` | 暂停后恢复绕路时,走位返回锚点的一次尝试起止 | `face`、`attempt`、`result` |

绕路本身不会永久失败任务(design I8):找不到东西可以顺路挖,或者绕路中途撞上任何安全
门槛,都只是安静地放弃/中止,原来的挖矿任务照常继续。这些事件因此都用来回答"这次绕路
绕成了没有、为什么没绕、值不值得":有没有找到顺路的矿看 `ore_dig_detour_start`/`_skip`,
中途出了什么事看 `_abort`/`_route`,一次绕路到底挣了几块矿看 `_end`。

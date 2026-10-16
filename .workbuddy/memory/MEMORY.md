# GetsKeeper — 项目长期笔记

## 定位
Minecraft 1.21.11 + NeoForge 的 GateKeeper 风格容器映像 / 物品信任守卫 Mod。
Mod ID `getskeeper`，包 `top.kdxiaoyi.mod.getskeeper`，客户端与服务端都要装。

## 构建约定
- 插件：`net.neoforged.moddev`（ModDevGradle）**2.0.124**。1.21.9+ 起官方 MDK 已弃用旧的
  `net.neoforged.gradle.userdev` 7.x，不要再回退。
- MC 1.21.11 / NeoForge **21.11.48** / Java 21 / Gradle wrapper 9.2.0。
  21.11.41 虽在 metadata 与 git tag 里存在，但**从未发布成制品**（请求它只会 404）；
  21.11 线从 21.11.42 起才可解析。
- mod 元数据放 `src/main/templates/META-INF/neoforge.mods.toml`，由 `generateModMetadata` 展开。
  **模板注释里不能出现字面量 `${...}`** —— Groovy 会当表达式求值并报 `Unexpected input: '('`。
- **不使用 mixin，不使用 access transformer**；只走公开 API，以便与原版和常见 Mod 共存。
- 客户端代码与 common 代码同处 `src/main/java`，靠 `@Mod(value = ID, dist = Dist.CLIENT)` 隔离；
  客户端负载处理器用 `RegisterClientPayloadHandlersEvent` 注册，common 侧只 `playToClient(type, codec)`。
  这是刻意的：不引入 split source set，少一个构建变量。
- `./gradlew build` 已实测通过，产出 `build/libs/getskeeper-<version>.jar`。
- **`./gradlew runServer` 也已实测启动成功**（Mod 加载、配置生成、命令注册均正常）。改动注册/启动路径后
  请务必跑一次 `runServer` —— 编译通过不代表能跑（见下）。
  下发命令要用 RCON，Gradle **不转发 stdin**。
- **`runClient` 在本机（这个 shell）跑不起来**：LWJGL 无法加载本地库、`DisplayWindow` 拿不到图形会话。
  客户端代码（拾取界面 / 弹窗 / 隐私页 / 叠加层）只能靠编译器与审查，别在这上面反复试。
- **协议版本**：`GKNetwork.VERSION` 目前是 `2`。任何 `CustomPacketPayload` 字段变化都要同步升版，
  否则新旧构建会互相误读。

## 网络受限环境的构建（本机必需，已固化进仓库）
`maven.neoforged.net` 在本机被 DNS 污染（多个 DoH 都返回被注入的 Facebook 段 IP，443 全部超时），
**`/etc/hosts` 无解**。三层绕行缺一不可，细节见 README 的 mirror 小节：
1. `build.gradle` 用 `repositories.all { }` **改写 URL 本身**（仅加镜像无效：MDG 在 `plugins {}` 阶段
   已把官方仓库注册在列表更前面，且 Gradle 遇超时会中止而非跳到下一个仓库）。
2. `net.neoforged:minecraft-dependencies`（承载 MC 全部库）只存在于官方 `/mojang-meta/`，
   由 `tools/generate-minecraft-dependencies.py` 从 MC 版本清单本地重建到 `gradle/local-repo`，
   再把 `Mojang Meta` 仓库重定向过去。**改 MC 版本后必须重跑该脚本并提交产物。**
3. NFRT 会直接从官方域名补下个别库；把文件放进
   `~/.gradle/caches/neoformruntime/artifacts/<group>/<artifact>/<version>/` 即可（它只看文件在不在）。

## 代码约定
- 文案一律走 i18n key（`en_us` / `zh_cn` 全量对齐）；`Inventory` 这个标签**任何语言都不翻译**。
- 除验证进度条复用原版经验条精灵（`hud/experience_bar_background` / `hud/experience_bar_progress`）外，
  所有 UI 形状都用 `GuiGraphics.fill` 自绘（`MacTheme`），不新增 GUI 贴图依赖。
- 不弹无关提示：授权、限制、拦截、密码尝试、移到虚空等只写日志（`GetsKeeper.LOGGER`）。
- 玩家数据用 Data Attachment（`copyOnDeath`）；存档级密码用 `SavedData`；名单用 ModConfig。
- 名单冲突（同一 entry 出现在两个名单）必须抛异常崩溃，不做静默取舍。
- **弹窗按钮必须关闭界面，Esc 必须等同于安全按钮**。`MacDialogScreen` 用 `choose(choice)` 统一
  "跑动作 + 关闭"，Esc 走 `onClose()` → 非破坏性选项；不要再用会重复语义的 `onDismiss`。
  **不要用 `pushGuiLayer`**：弹窗一律 `setScreen`，否则关闭/ Esc 语义会跟图层栈打架。
- **拾取期间任何东西都不该进背包**：`onItemEntityPickup` 要**先否决再 offer**（失败就下次碰撞重试，
  vanilla 每 tick 都触发该事件）。只在 offer 成功时才否决，会让冷却期内的物品被 vanilla 静默拾走。
  同理，模组自己放进背包的东西（交付批次 / 放行会话缓存物品）要 `UseGuard.resync(player)`，否则会被守卫当成"使用"。
- **拦截的"撤销/复现"以 footprint 为单位**：记录本次操作**改动过的槽位 + 改动后的值 + 菜单光标项（前后各一）**。
  撤销 = 把那些槽位与光标写回旧值；继续 = 把它们写回新值（= 原操作，交换仍是交换）；取消 = 保留撤销即可
  （只有"来自背包之外"的栈撤销恢复不了，才需要额外返还）；移到虚空 = 按 item+components 清（计数可能不同）。
  **光标必须一起快照**：拖拽中的栈在两次点击之间活在光标上，它不是背包槽位，忽略它就会出现
  "槽里一份、光标一份"的复制。**只把监视槽写回旧值再把新栈写进该槽** 也会复制（撤销已经把栈放回去了）。
- **能被 vanilla 提前问的事件要在事件里拒绝，别事后撤销**：F 交换 = `LivingSwapItemsEvent.Hands`（可取消），
  左键破坏 = `PlayerInteractEvent.LeftClickBlock`（START；**创造模式无效**）。事后撤销再复现是复制/Loss 的温床。
- **`isTrusted` 只放行"本模组物品"与"玩家已授权"两件事**：`FULLY_TRUSTED` **仍要走验证**（进度条 + 静默通过、
  无对话）。把 pass 整个跳过会让免检物品连进度条都没有 —— 信任列表本身就是"被看着但不被质问"这一档，
  进度条是它唯一的反馈。静默分支必须**发 verdict**，否则客户端进度条永不消失（这条不变）。
- **右键"使用物品"必须单独拦**：`PlayerInteractEvent.RightClickItem`（可取消）是 vanilla 在
  `ServerPlayerGameMode#useItem` 开头就问的事件，**装备盔甲走的就是它**（`Equippable#swapWithEquipmentSlot`）。
  装备不经过 use-on-block / use-item，等 tick 守卫发现时物品已经穿上，事后撤销还要碰 footprint（易复制）。
- **`UseItemOnBlockEvent` 有三个阶段，只拦物品那两个**：BLOCK 阶段是"方块对右键的回应"（开箱子、按钮），
  拦它会让拿着未授权物品时开不了箱子/门。ITEM_BEFORE_BLOCK 阶段要用 **`PASS` 取消而不是 `FAIL`** ——
  vanilla 只有该阶段返回 PASS 才把回合交给方块（`if (result != PASS) return result;`）。
- **界面层盖不住 Screen**：vanilla 是 HUD 先渲染、Screen 后渲染，NeoForge 的 `drawScreen` 还会把注册的 GuiLayer
  画在 Screen 更早。要让自己的 HUD 出现在界面之上，必须挂 `ScreenEvent.Render.Post`（并在有界面时跳过 HUD 层那一遍）。
- **`GuiGraphics#drawCenteredString/drawString` 的旧重载总是带阴影**；`shadow=false` 的那版要自己算居中。
  本模组自定义 UI 一律无阴影，只有直接画在世界之上的进度条标签保留阴影。
- **（已被上面的 footprint 模型取代）**：曾经是"整背包快照 + 整背包回滚 + 把新栈写回监视槽"，那会复制。
  现行做法见上面第一条。
- **快照基线必须在放行后清掉**，否则下一 tick 把刚放回的物品当成新变化，形成无限验证循环。
- **本模组自己的物品对所有关卡豁免**（验证 / 拾取 / 来源记录三处都要显式排除）。
  映像"丢弃即销毁"的正确做法是**拒绝其 ItemEntity 加入世界**（`EntityJoinLevelEvent`），
  而不是让 `onDroppedByPlayer` 返回 false —— 后者只会取消丢弃、把物品留在原位。
- **凡是有配置项的约束，界面必须表达出来**。`security.min_length` 默认 6 却没在界面提示，导致
  "太短"被当成"密码错误"，用户会认为功能坏了。做法：策略由服务端随载荷下发，客户端显示 + 提前校验，
  服务端记录每条拒绝原因。
- 箭矢静止判定不能调 `isInGround()`（protected）：用
  `isNoPhysics() || onGround() || getDeltaMovement().lengthSqr() < 1e-4`；取物品用 public 的
  `getPickupItemStackOrigin()`。
- 客户端发包用 `ClientPacketDistributor#sendToServer`（`PacketDistributor` 只有服务端方法）。
- netty 4.2 的 `ByteBuf` 没有 `writeVarInt/readVarInt`，用 `writeInt/readInt`。
- **物品注册必须用带 key 的工厂**：`Item.Properties` 自带物品 id，自己在 supplier 里
  `new Item.Properties()` 会以 `NullPointerException: Item id not set` 在 RegisterEvent 阶段崩溃。
  写 `ITEMS.register(name, key -> new TheItem(new Item.Properties()...setId(ResourceKey.create(Registries.ITEM, key))))`。

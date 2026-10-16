# GetsKeeper — 项目长期笔记

## 定位
Minecraft 1.21.11 + NeoForge 21.11.41 的 GateKeeper 风格容器映像 / 物品信任守卫 Mod。
Mod ID `getskeeper`，包 `top.kdxiaoyi.mod.getskeeper`，客户端与服务端都要装。

## 构建约定
- 插件：`net.neoforged.moddev`（ModDevGradle）**2.0.124**。1.21.9+ 起官方 MDK 已弃用旧的
  `net.neoforged.gradle.userdev` 7.x，不要再回退。
- MC 1.21.11 / NeoForge 21.11.41 / Java 21 / Gradle wrapper 9.2.0。
- mod 元数据放 `src/main/templates/META-INF/neoforge.mods.toml`，由 `generateModMetadata` 展开。
- **不使用 mixin，不使用 access transformer**；只走公开 API，以便与原版和常见 Mod 共存。
- 客户端代码与 common 代码同处 `src/main/java`，靠 `@Mod(value = ID, dist = Dist.CLIENT)` 隔离；
  客户端负载处理器用 `RegisterClientPayloadHandlersEvent` 注册，common 侧只 `playToClient(type, codec)`。
  这是刻意的：不引入 split source set，少一个无法本地验证的构建变量。

## 代码约定
- 文案一律走 i18n key（`en_us` / `zh_cn` 全量对齐）；`Inventory` 这个标签**任何语言都不翻译**。
- 除验证进度条复用原版经验条精灵（`hud/experience_bar_background` / `hud/experience_bar_progress`）外，
  所有 UI 形状都用 `GuiGraphics.fill` 自绘（`MacTheme`），不新增 GUI 贴图依赖。
- 不弹无关提示：授权、限制、拦截、密码尝试、移到虚空等只写日志（`GetsKeeper.LOGGER`）。
- 玩家数据用 Data Attachment（`copyOnDeath`）；存档级密码用 `SavedData`；名单用 ModConfig。
- 名单冲突（同一 entry 出现在两个名单）必须抛异常崩溃，不做静默取舍。

## 环境注意
`maven.neoforged.net` 可能不可达，届时 Gradle 无法解析 `net.neoforged:neoforge`；
按 README「If the NeoForge Maven is unreachable」一节临时加 `https://maven.creeperhost.net` 镜像。

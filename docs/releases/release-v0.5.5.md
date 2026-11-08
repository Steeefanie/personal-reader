# 图书 0.5.5 验证记录

日期：2026-11-07。versionCode 11，minSdk 24，targetSdk 35，ARM64。

## 修复

主导航原来按 screenWidthDp >= 620 切换胶囊宽度：宽屏 144dp、窄屏 112dp；阅读内面板始终 144dp。应用 DPI 增大后逻辑屏幕宽度可能越过该阈值，导致主页面胶囊的宽高比额外改变。

删除主导航宽度分支，书库、云盘、设置和阅读内面板统一使用 PAGE_CONTROLS_WIDTH=144dp、PAGE_CONTROLS_HEIGHT=48dp；主导航容器也预留相同宽度。控件继续跟随系统 DPI 整体缩放，不锁定物理像素。外框 1.25dp、箭头 2dp、灰显规则及已有功能保留。

## 验证

- 离线 assembleDebug、assembleDebugAndroidTest、assembleRelease、testDebugUnitTest、lintDebug 成功。49 项单元测试通过，Lint 0 错误、50 警告。
- Android 11 / API 30 模拟器分别使用 300 DPI 与 420 DPI，覆盖阈值两侧（约 674dp 与 481dp 宽度）；每种密度均执行主要界面导航回归，2 次均通过。
- 断言实际 Activity 的 densityDpi 与测试密度一致；书库、云盘、设置胶囊实测宽高等于当前密度换算后的 144×48dp，阅读目录按钮宽高与同一常量一致。
- 同时核验三页标题坐标、阅读返回位置、正文重复搜索、目录与排版面板以及无动画导航。测试结束后恢复模拟器原密度。
- DPI 回归仅覆盖模拟器的 300 和 420 DPI，未验证实体设备的密度设置与显示效果。

## 交付

APK：14,400,951 字节（14.40 MB）。

SHA-256：`f58247583a006493ddd310308a428f18c259c3266a6cecdf4591f15e9a22dd3e`

签名证书 SHA-256：`3e540b07f5659b983cd8ef5a3f4a3c06cefd93df527970e3b9cb17a534671cb4`。沿用测试签名，可覆盖更新并保留现有数据。

该版本仅执行上述 DPI 专项回归，未重新执行 0.5.4 的全部仪器测试。

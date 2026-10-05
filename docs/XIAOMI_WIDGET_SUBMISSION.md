# 廿一：小米小部件中心接入资料（0.10.1）

状态：工程已修改待测试；**未提交审核、未在小米中心上线**。核对日期2026-10-04。此文档不代表获得接入资格。

## 可核对的工程信息

| 项目 | 内容 |
| --- | --- |
| 应用 | 廿一 / com.twentyone.rhythm |
| 本轮版本 | 0.10.1 / versionCode 16 |
| 小米组件版本 | miuiWidgetVersion = 3 |
| 打卡按钮 | com.twentyone.rhythm.HyperOsCheckWidget，2×2 |
| 卡组 | com.twentyone.rhythm.HyperOsWidget，目标4×4；用户桌面实际显示4×3 |
| 进程 | :widgetProvider，只读原子展示快照；内部主进程接收器完成记录 |
| 更新 | miui.appwidget.action.APPWIDGET_UPDATE、exposure、最小20000ms；App数据修改后刷新 |
| 编辑 | rhythm-widget://edit/{appWidgetId}；单张选一个习惯，卡组多选 |
| 迁移 | Android onRestored 与小米 miuiIdChanged，复制选择后确认完成；不迁移待确认打卡 |
| 布局 | android.R.id.background、有色圆角背景；Android 12+按实际空间约束小卡可见比例，自适应紧凑布局、夜间色资源 |

Android入口保留 `DataWidget` 2×1、`CheckWidget` 2×2、`RhythmWidget` 大卡组。**2×1不申报为当前小米官方手机应用组件规格**。

## 介绍草稿

名称：廿一·打卡按钮

介绍：把一个正在坚持的习惯放到桌面。次数、计时与每日确认分别展示，点击准备后在卡片内再次确认，直接记录；点习惯名查看，点编辑更换。每张按钮独立选择，保留真实记录和计时明细。

名称：廿一·今日卡组

介绍：在一张桌面卡组里上下切换作息和习惯。按区间、次数与每日确认选择内容；点击打开打卡抽屉，长按完成对应操作。

## 仍缺的外部条件

1. 小米开发者账号、此包名所属应用及应用商店审核状态，用户已确认尚未申请账号或上架应用。若首次接入，官方要求联系小米开通组件上传入口；尚未代用户发送任何邮件。
2. 应用须先通过商店审核，小部件平台才解析应用中的组件。账号协议、主体认证、应用材料及商店构建要求需要在真实账号中核对；本项目当前沿用GitHub原签名覆盖升级通道，不能把debug APK已交付等同于商店准入。
3. 澎湃OS3真机测试环境：中心添加、负一屏与桌面迁移、网格/字体、省电与后台刷新。普通Android模拟器无法验证小米中心收录和系统专用行为。
4. 按平台要求制作并上传实际商店预览素材、选择发布设备并提交审核。`artifacts/0.10.0-small-widgets-*.png`为工程测试宿主截图，不能冒充澎湃OS实机商店素材。

正式提交前应核对上述信息和账号内实际要求；没有执行账号注册、协议同意、邮件联系、商店上传或审核提交。

依据：[官方提交流程](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1588)、[接入问答](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1591)、[技术规范](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1584)、[设计规范](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1664)。

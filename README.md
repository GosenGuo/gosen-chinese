# 拾文 · 每日语文

面向高中语文学习的独立 Android 应用。包名 com.gosen.chinese；Android 8.0 及以上；无需登录，学习记录保存在手机本地。

## 使用

在本仓库 Releases 下载正式签名的 GosenChinese 安装包。首次安装后，后续版本可以直接覆盖安装。打开 App 会检查新版，也可以在「我的 → 检查软件更新」手动检查。系统可能要求为「拾文」开启安装未知应用权限。

- 古诗文：60篇指定学习目录，点击重点字词查看当前语境释义；展开详情后手动加入复习，才显示波浪线。支持字号调整、参考译文、基础练习、逐句背诵和填空。
- 现代文：阅读、作答、原文定位、选择题逐项解析和主观题得分点自评。
- 文言阅读：学习和检测模式，译文及提示按模式控制；断句、翻译与内容分析训练。
- 每日作文：完整题目、先写立意、展开范文、审题结构拆解、仿写和段落积累。首页每日推荐一篇，篇库可查看全部。
- 复习：字词按原句义项分别保存；阅读错题、背诵错句和作文段落共用间隔复习。支持本地备份导出和恢复。

## 首版内容的实际范围

- 60篇古诗文原文（20篇文言文、40首诗词曲），273处重点注释、235条重点句参考译文、60道基础理解练习。
- 现代文3组、课外文言文3组，合计24题。
- 7篇带完整题目的 AI 示范作文，每篇约900汉字，含审题、结构说明和小练习。

古诗文60篇是指定学习目录，不能据此断言为某届海南高考正式范围。原文采用公开通行文本，仍需按使用者在用教材核对字形、节选范围和册次。当前译文为重点句覆盖，并非60篇全部逐句译文。注释与题目注明 AI 辅助整理身份。

阅读练习为公开古典/现代原作配自编题，或原创训练材料；尚未收录可核验的整套高考原题。作文明确标为 AI 示范、未评分，不能作为真实考场满分作文。详细内容出处及校验范围见 content-notes.md、content-practice-notes.md，App 文末可查看每篇来源。

## 本地开发

需要 JDK 17 或21、Android SDK35。设置 ANDROID_HOME 或本地 local.properties。

    ./gradlew testDebugUnitTest
    node scripts/check-classics.mjs
    node scripts/validate-practice-content.mjs
    node --test scripts/write-update-manifest.test.mjs

debug 安装包仅供开发测试，使用系统调试签名。正式分发始终使用 assembleRelease 产物和仓库已保存的同一把正式签名。

## 正式发布与固定签名

仓库 Actions 中 Signed Android release 工作流使用 GitHub Secrets 保存的签名材料：

- ANDROID_KEYSTORE_BASE64
- ANDROID_KEYSTORE_PASSWORD
- ANDROID_KEY_ALIAS
- ANDROID_KEY_PASSWORD

本地正式构建需要这四项环境变量：ANDROID_KEYSTORE_PATH、ANDROID_KEYSTORE_PASSWORD、ANDROID_KEY_ALIAS、ANDROID_KEY_PASSWORD。缺失签名时正式构建必须失败。

release/signing-certificate.sha256 保存正式证书公开指纹。发布时会与固定指纹核对，即使机密配置误换也会拒绝分发。

更新步骤：

1. 保持包名 com.gosen.chinese 和现有签名材料。
2. 修改 release/version.json，递增 versionCode 并更新 versionName 和 releaseNotes。
3. 推送 main，等待测试、正式构建、发布和更新清单提交完成。
4. 检查 Release 的 APK 与清单 SHA-256、版本和签名一致，再通知用户更新。

安装前 App 核验包体 SHA-256、包名、版本号和当前安装证书。更新清单位于 release/app-update.json。新版本应保留学习数据结构兼容；改变结构需要迁移与备份测试。

## API 内容工具

PackyAPI 密钥保存在 GitHub Secrets 的 PACKY_API_KEY 中，不进入源码或 APK。默认使用经过实际连接测试的 Responses 流式协议；模型及协议可以通过环境变量配置。

内容工具生成的是待复核草稿，不能自动把生成文章标成真题或满分作文。生成方式、参数与审核流程见 [API.md](API.md)。当前 App 使用安装包内的离线内容；审核过的新内容随正式版本发布。

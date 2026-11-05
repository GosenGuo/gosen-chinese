# 按需补充后台

手机对现代文、文言文、作文分别计算永久未读数量；某类少于5篇才请求补充30篇。

后台固定只可触发本仓库的内容工作流，GitHub凭据和模型API密钥都不进入APK。手机内的有限触发能力只能请求固定三类、固定30篇，不能指定仓库、工作流、模型或任意付费参数。同类请求六小时去重；三类请求进入持久队列，依次执行，避免多个GitHub待运行任务互相挤掉。

部署前使用 wrangler whoami 验证Cloudflare登录。使用正式账户的稳定Worker地址；临时预览账户不能作为手机正式内容源。

密钥通过 wrangler secret put 配置 GITHUB_TOKEN 和 APP_TRIGGER_TOKEN；配置中仅有非机密的仓库标识。部署后检查 /health 为ready，再验证三类队列和一次真实工作流，最后把真实服务URL和有限触发token配置到正式构建。

本地验证：

    npm test
    npx wrangler deploy --dry-run

不对超时或未观察到的工作流盲目重派；任务失败不会伪造内容，已经发布的题库继续可用。

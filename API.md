# PackyAPI 内容补充

此工具通过 PackyAPI 生成待复核内容。手机 App 读取整理后的内容包；API 密钥只放在运行工具的私有环境或 GitHub Secrets，代码中没有密钥。

## 已验证的连接方式

2026年11月4日本项目接通验证使用：

- 网关：`https://www.packyapi.ai`
- 路径：`POST /v1/responses`
- 认证：`Authorization: Bearer <由环境变量读取>`
- 模型：`gpt-6.1-sol`
- 流式：`stream: true`

客户端按 OpenAI Responses 协议解析 `response.output_text.delta`、`response.output_text.done` 与 `response.completed`，从最终响应保留 token 用量。只有完整结束且正文非空才成功；中断、错误、未完成或空输出都不能变成已发布内容。同一网关的非流式调用曾出现 HTTP 200、正文为空，默认因此使用 SSE。

协议格式参照 [OpenAI 官方流式 Responses 文档](https://developers.openai.com/api/docs/guides/streaming-responses)。PackyAPI 是第三方网关，模型是否可用及收费以该账户和网关为准。[PackyAPI 官网](https://www.packyapi.ai/)

## 环境变量

| 变量 | 用途 | 默认值 |
| --- | --- | --- |
| `PACKY_API_KEY` | 密钥，必填 | 无 |
| `PACKY_API_BASE_URL` | HTTPS 网关，不包含密钥或查询参数 | `https://www.packyapi.ai` |
| `PACKY_API_MODEL` | 该密钥有权限调用的模型 | `gpt-6.1-sol` |
| `PACKY_API_PROTOCOL` | `responses` 或 `anthropic` | `responses` |
| `PACKY_API_STREAM` | Responses 是否使用流式；`false` 才关闭 | 流式开启 |

运行环境为 Node.js 22 或更新版本，无额外依赖。密钥通过当前进程环境注入，工具不读取或解密本机凭据文件，不把私有凭据打包进仓库或 APK。输出错误会脱敏，失败不自动重试，避免重复计费。

`anthropic` 选项保留原生 `/v1/messages` 协议适配，使用 `x-api-key` 与 `anthropic-version: 2023-06-01`。本账户的 Claude 通道曾返回客户端限制错误，当前默认连接不使用它，也不伪装其他官方客户端。

## 本机使用

在项目根目录，并确保当前进程已有 `PACKY_API_KEY` 环境变量：

```powershell
node scripts/generate-content.mjs smoke
```

这次连接检查仅请求一句简短确认，输出预算128 tokens，打印模型、协议和实际 token 用量。

若 Windows 已配置 `HTTP_PROXY` / `HTTPS_PROXY`，且普通 Node 请求没有采用现有网络路由，可在 Node.js 24 中使用其环境代理支持：

```powershell
node --use-env-proxy scripts/generate-content.mjs smoke
node --use-env-proxy scripts/generate-content.mjs enrich-classic --id 现有条目ID
```

也可以在启动进程前设置 `NODE_USE_ENV_PROXY=1`。这只让 Node 使用机器已有的代理配置；无需更改身份、伪装客户端或改写请求。GitHub Actions 使用自身网络，不设置这些本机代理选项。

为一篇已收录的古诗文生成注释、逐句译文草稿：

```powershell
node scripts/generate-content.mjs enrich-classic --id 现有条目ID --max-tokens 8192
```

可先打开 `app/src/main/assets/classics.json` 查询条目 ID。命令只读原文；草稿带原文 SHA-256 校验值，避免审核时把注释应用到已经变化的原文。字词须能定位到原文具体出现位置，所在句必须吻合；译文须按原文顺序引用。草稿的 `coverage` 记录译文覆盖率，`complete: false` 代表仍有未覆盖部分，不能据此宣称全文翻译完成。

生成带完整自拟题目、写作要求、正文、审题结构分析和仿写练习的作文：

```powershell
node scripts/generate-content.mjs generate-essay --topic "传统的延续与创新" --max-tokens 8192
```

这类文章固定标识为 AI 示范作文，正文长度检查为800—1600个汉字，提示词目标为800—1100个汉字；至少四项拆解。不赋予未经核实的考场分数，也不冒充真实高考题或满分考生作品。

默认草稿在 `generated/` 下使用独立文件名。也可以指定该目录中的新文件：

```powershell
node scripts/generate-content.mjs generate-essay --topic "远方与脚下" --out generated/essay-review.json
```

文件存在时会停止，不能覆盖已有草稿。写入采用临时文件和原子链接，原文资产及已经发布的内容包都不会被此命令改写。生成用量、模型、时间、响应ID保存在草稿 `metadata` 中。长文超过输出上限时，客户端会报告未完成；提高单次预算后再手动尝试。

## GitHub 手动补充

仓库 Settings → Secrets and variables → Actions 已配置或应配置 `PACKY_API_KEY`。到 Actions → **Generate content draft** → Run workflow，选择：

- `generate-essay`：填主题，生成一篇作文草稿。
- `enrich-classic`：填现有古诗文 ID，生成一篇补充草稿。
- `smoke`：只检查短文本连接。

工作流先运行离线 mock 测试，再进行一次真实请求。成功后上传名为 `content-draft-运行编号` 的 artifact，保留30天。无定时任务，不自动批量生成，不写回教材，也不自动发布草稿。

下载草稿后，应逐项复核原文对应关系、词义、译文、题文配对、论据和真实性标签。确认后再由内容维护流程合并到正式内容包。

## 离线测试

```powershell
node --test scripts/test-packy-client.mjs
```

测试采用 mock transport，不需要密钥、不调用真实接口。覆盖端点和认证、中文跨分块 SSE、最终文本去重、空输出、中断与失败、输出截断、超时、HTTP错误脱敏、Anthropic原生协议、重复字词锚点、作文标签和长度、草稿原子写入及不覆盖原则。

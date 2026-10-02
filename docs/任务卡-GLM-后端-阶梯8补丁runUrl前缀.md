# 任务卡：GLM 后端 · 阶梯 8 补丁（runUrl 缺少 nginx 路径前缀）

> 小卡，一个 bug 修复。用户实测发现：发布面板展示的 runUrl 是 `http://43.108.111.175/api/public/run/{key}`，
> **公网打不通**——生产 nginx 把 API 挂在 `/fm/api/` 前缀下，正确地址应为 `http://43.108.111.175/fm/api/public/run/{key}`。
> 根因：buildRunUrl 拼的是后端自身视角路径（host + /api/...），不知道部署前缀。

## 修复方案

1. 配置注入：yml 加
   ```yaml
   flowmind:
     public-path-prefix: ${PUBLIC_PATH_PREFIX:}
   ```
   （默认空——本地开发直连 8080 行为不变；生产 env 设 `PUBLIC_PATH_PREFIX=/fm`）
2. PublishService 的 runUrl 拼接改为 `host + prefix + "/api/public/run/{apiKey}"`（prefix 为空时就是原行为）
3. 补/改一个测试：prefix 为空 → 原 URL；prefix=/fm → `/fm/api/public/run/...`

## 验收

- [ ] mvn test 全绿
- [ ] 本地 prefix 空验证不回归 + 设 PUBLIC_PATH_PREFIX=/fm 起服务 curl 验证 runUrl 正确
- [ ] 禁区零改动

改完汇报，统筹部署时在服务器 flowmind.env 加 `PUBLIC_PATH_PREFIX=/fm`。

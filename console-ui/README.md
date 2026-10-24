# console-ui

seatunnel-console 的前端：Vue 3 + TypeScript + Vite + Element Plus，样式按 Ant Design 调整（`src/styles/main.css`）。

```bash
npm install
npm run dev      # http://localhost:5173，/api 代理到 http://localhost:8088
npm run build    # 类型检查（vue-tsc）+ 打包；没有单独的测试和 lint 脚本
```

整体说明、使用流程和设计见仓库根目录的 [README.md](../README.md) 和 [docs/design.md](../docs/design.md)。

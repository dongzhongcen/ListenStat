# ListenStat 桌面端

ListenStat（听时）跨平台桌面客户端。当前版本：本地收听统计、云端同步、设备配对、Windows 系统媒体控件（SMTC）读取；托盘与开机自启可在设置中开关。

## 环境要求

- Node.js 18+
- npm 9+
- 打包 Windows 安装包建议在 **Windows** 机器上执行（本仓库的 Linux 环境通常无法直接产出 `.exe`）

## 运行

```bash
cd desktop
npm install
npm start
```

`npm start` 会先执行 Vite 构建，再用 Electron 加载 `dist/`。

### 开发（热更新渲染层）

```bash
npm run dev
# 另开终端：
VITE_DEV_SERVER_URL=http://127.0.0.1:5173 npx electron .
```

仅预览界面（无主进程同步 / 媒体）：

```bash
npm run build && npm run preview
```

## Windows 打包（在 Windows 上）

在 Windows 机器上进入本仓库的 `desktop` 目录（例如 `D:\GitHub\ListenStat\desktop`）：

```bat
cd desktop
npm install
npm run dist:win
```

产物默认在 `desktop/release/`：

- NSIS 安装包：`ListenStat-*-win-x64.exe`
- 便携版：`ListenStat-*-portable.exe`

仅生成解压目录（更快，便于调试）：

```bat
npm run dist:win:dir
```

> 在 Linux 上执行 `dist:win` 通常需要 Wine，且不一定成功；请以 Windows 本机打包为准。

## 功能说明

| 导航 | 内容 |
|------|------|
| 概览 | 今日 / 本周 / 本月时长、连续天数、近 7 日柱状图、来源占比、正在播放与最近 |
| 排行 | 按应用收听时长 |
| 明细 | 某应用下的标题列表，支持日切换 |
| 成就 | 徽章网格（已解锁 / 未解锁） |
| 设置 | 显示名称、云端同步、配对码、托盘与开机自启 |

### 本地存储

收听日汇总与同步凭据保存在 Electron `userData` 目录（JSON），不写入仓库。

### 云端同步

默认关闭。开启后由 **主进程** 调用同步 API（注册、拉取、推送），避免浏览器 CORS。设备令牌仅存于本机 `userData`，日志中不会打印令牌。

### 设备配对

设置页可「生成本机配对码」或「输入对方配对码」，将多台设备合并到同一账号（服务端 `POST /v1/pair/create`、`POST /v1/pair/claim`）。

### Windows 媒体

在 Windows 上通过 PowerShell 读取系统媒体控件会话；非 Windows 环境为空闲占位，界面与同步仍可用。

## 目录结构（摘要）

```
desktop/
  electron/     # 主进程、本地库、同步客户端、媒体读取、preload
  src/          # 渲染层
  scripts/      # 截图等辅助脚本
  build/        # electron-builder 资源（可选图标）
  index.html
  vite.config.js
  package.json
```

## 说明

- 上下文隔离已开启，渲染进程无 `nodeIntegration`
- 请勿在本目录写入服务器密码、私钥或密钥库
- Android 应用与本目录相互独立；配对的手机端界面可后续单独跟进

## 开源许可

本桌面端基于 Electron（含 Chromium）。安装包内会附带 Electron / Chromium 等上游开源许可文件；本项目代码以 MIT 许可发布。

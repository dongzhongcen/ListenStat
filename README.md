<div align="center">

# ListenStat

**本地听书 / 媒体播放时长统计**

*按日 · 月 · 年 · App · 内容 Top 20 · 数据不出手机*

[English](README.en.md)

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android-green.svg)](#)

</div>

## 简介

ListenStat 是一个 **Android** 本地统计工具：读取系统「正在播放」媒体信息，累计各应用与具体内容的播放时长，并按日 / 月 / 年汇总，列出内容 Top 10–20。

- **本地优先**：统计数据只存在本机，默认不上传任何服务器
- **系统权限**：需要通知使用权（或媒体会话相关权限）才能读到控制中心播放器信息；首次使用会引导开启
- **隐私**：只记录应用包名、媒体标题、时长与时间戳；不采集账号密码、通讯录等

## 第一版范围

| 能力 | 说明 |
|------|------|
| 自动记账 | 后台监听正在播放的 App + 标题 |
| 时间维度 | 日 / 月 / 年汇总 |
| 排行 | 按 App、按内容 Top 20 |
| 存储 | 本机数据库 / 文件 |

## 状态

公开仓已就绪，应用实现进行中。

## 许可证

[MIT](LICENSE)

<div align="center">

# ListenStat

**听时 — Android 本地听书/媒体播放时长统计**

*按日 · 月 · 年汇总 · 应用与内容 Top 20 · 数据只留在手机*

**简体中文** | [English](README.en.md)

[![许可证](https://img.shields.io/badge/许可证-MIT-blue)](LICENSE)
[![平台](https://img.shields.io/badge/平台-Android%208.0%2B-green)](https://github.com/dongzhongcen/ListenStat)

</div>

## 简介

ListenStat 读取系统「正在播放」媒体会话（控制中心 / 媒体通知同源信息），在本地累计播放时长：

- 按 **今天 / 本月 / 本年** 查看
- **按应用** Top 20
- **按内容标题** Top 20（书名、歌名等，取决于播放器是否写入媒体元数据）

**隐私：所有记录只存在本机 Room 数据库，不上传、无账号、无广告分析 SDK。**

需要授予 **通知使用权**（Notification Listener），否则读不到媒体会话。卸载应用即可清除数据。

## 使用

1. 用 Android Studio 打开本仓库，同步 Gradle，安装到手机（Android 8.0+）
2. 打开 App → 「打开通知使用权设置」→ 开启 ListenStat
3. 用听书 / 音乐 App 正常播放一段时间
4. 回到 ListenStat 选「今天 / 本月 / 本年」并刷新

## 限制

- 仅 Android；iOS 无法同等实现
- 标题明细依赖播放器写入 `MediaMetadata`；少数 App 只显示「正在播放」而无书名
- 统计的是「媒体会话处于 Playing」的墙钟时间，不是音频解码精确毫秒

## 构建

```bash
./gradlew :app:assembleDebug
```

（首次可在 Android Studio 生成 Gradle Wrapper。）

## 许可证

[MIT](LICENSE)

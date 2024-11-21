# AcFun 直播间挂机助手

# 免费全开源

一个用于a站直播间自动挂牌子经验的 Android 应用。
请在右侧发行版下载apk文件进行安装(请允许通知权限)。
网盘链接：https://wwkl.lanzouw.com/b00wm4vjte
密码:beym
手机打开网盘链接记得把浏览器标识切换成电脑就可以下载了


## 权限说明

- 通知权限：启动前台服务需要，可以更持久挂在后台
- 电池优化：请允许忽略电池优化，允许后台耗电
- 自启动：允许应用自启动，确保断网重连系统回收后自启动挂机服务
- 后台运行：允许应用后台运行，确保长时间运行挂机服务

## 功能特点

- 开箱既用
- 自动检测关注主播的直播状态
- 自动进入直播间获取经验值
- 支持多个直播间同时挂机
- 智能处理经验值已满的情况
- 显示实时运行状态和经验值进度
- 后台运行，支持长时间挂机
- 自动处理跨天重置
- 维护直播间心跳保持在线状态
- 原生java占用低运行快

使用了WakeLock锁确保后台稳定运行，前台服务确保应用不被系统回收，所以要退出程序要划掉这个应用哦
不然会一直在后台挂，为了不被系统清理掉，可以在多任务的时候有那个小锁，各品牌手机不一样。
其中部分api是从泥壕大佬代码里拿的，懒得自己找

## 使用说明

1. 首次启动会请求忽略电池优化，请允许以确保稳定运行
2. 登录 AcFun 账号
3. 应用会自动检测直播状态并进入直播间
4. 经验值达到上限后会自动退出相应直播间


## 隐私说明

- 应用仅用于模拟正常观看行为
- 不会收集或上传任何个人信息
- 所有操作均在本地完成
- 所有握手行为都是与a站和快手

## 免责声明

本应用仅供学习交流使用，请勿用于其他用途。使用本应用产生的任何问题由使用者自行承担。



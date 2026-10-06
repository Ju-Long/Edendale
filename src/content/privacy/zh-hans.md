---
updated: 2026-10-06
summary:
  - Edendale 没有账号系统，也没有自己的服务器。应用中没有分析统计、广告或跟踪。
  - 你的片库、设置和保存的登录信息都留在你的设备上。需要同步的内容只经过你掌控的服务，例如 iCloud 或你自己的 OneDrive。
  - Google Drive、OneDrive 和 Dropbox 只获得只读访问权限，由你的设备直接连接，仅用于列出和播放你的视频。
  - 影片信息、字幕搜索、跳过按钮和预告片只会连接下文列出的服务，且仅用于所述用途。
---

## 本政策的适用范围

本政策适用于 Apple 设备（iPhone、iPad、Mac、Apple TV 和 Apple Vision Pro）、Android 和 Windows 上的 Edendale 应用，以及本网站 edendale.babasama.com。Edendale 是一个自由开源项目，在 [github.com/Ju-Long/Edendale](https://github.com/Ju-Long/Edendale) 上公开开发。“我们”指开发它的人。

## 我们不收集你的数据

Edendale 没有账号系统，也不运营任何接收应用信息的服务器。应用中不含分析统计、广告、跟踪或崩溃报告代码。你的信息从不会到达我们这里，所以我们没有任何可出售、出租或分享的东西。

如果你允许设备与应用开发者共享诊断数据，你安装 Edendale 所用的应用商店（Apple、Google 或 Microsoft）可能会依照其自身的隐私政策，向我们提供崩溃报告和汇总的使用统计。我们只用它们来修复问题。

## 留在你设备上的内容

- **你的片库**：你添加的文件夹和来源；Edendale 在其中找到的文件的名称、大小、日期和时长；以及每个文件所匹配的电影或剧集。
- **你的设置**：播放、音频、画面、字幕和操控的偏好设置，包括为每部作品记住的选择。
- **你下载的字幕**。
- **保存的登录信息和关联的账号**：服务器密码、S3 访问密钥和云端登录令牌，保存在系统受保护的存储中：Apple 设备上的钥匙串、Android 上由 Android Keystore 加密的存储，以及 Windows 数据保护（DPAPI）。短期有效的访问令牌只保存在内存中。

Edendale 会先在你的设备上读取文件名来识别电影和剧集，然后才会连接任何在线服务；它从不上传你的视频。

## 哪些内容可以同步，经由何处

Edendale 只通过你掌控的服务进行同步，并且只在你开启后才会同步：

- **Apple 设备**：开启 iCloud 后，你的观看进度、评分、收藏和待看列表会通过你的私有 iCloud 数据库同步。关联的账号和保存的登录信息会通过 iCloud 钥匙串同步到你的 iPhone、iPad、Mac 和 Apple Vision Pro。Apple TV 保留自己的副本。
- **Windows**：如果你开启 OneDrive 复制，你的观看进度和作品状态会通过你自己 OneDrive 中的一个文件夹进行复制。登录信息和账号从不离开设备。
- **Android**：Android 自带的备份可能包含你的片库和观看数据。登录信息、密钥和账号令牌不会包含在备份和设备间迁移中。
- **你的 TMDB 账号（可选）**：如果你登录 The Movie Database，Edendale 会让你的收藏、待看列表和评分与该账号保持同步。观看进度从不会发送给 TMDB。

## Edendale 使用的在线服务

下列每项服务都会像任何互联网连接一样收到你设备的 IP 地址，此外还会收到以下信息：

- **[TMDB](https://www.themoviedb.org/privacy-policy)**（The Movie Database），用于获取电影和剧集的信息与图片：Edendale 从文件名中读取的标题和年份（从不发送完整文件名、所在文件夹或文件本身），以及你浏览的作品的 TMDB ID。如果你已登录，还包括你的 TMDB 会话。
- **[Wyzie Subs](https://wyzie.io/privacy)**，仅在你在线搜索字幕时：作品的 TMDB ID、季数和集数、你请求的语言，以及你的 API 密钥。
- **[TheIntroDB](https://theintrodb.org/docs/privacy)**，仅在跳过按钮开启时（默认关闭）：作品的 TMDB ID、季数和集数，以及视频时长。
- **[YouTube](https://policies.google.com/privacy)**，仅在你选择播放预告片时。在 Apple 和 Android 设备上，Edendale 会以 YouTube 的隐私增强模式（youtube-nocookie.com）播放；在 Windows 上，它会在你的浏览器中打开 youtube.com 上的预告片。
- **你关联的存储**，见下一节。

## 你关联的存储

Edendale 可以播放你设备上文件夹中的视频，以及你关联的存储中的视频：SMB、NFS、SFTP 和 WebDAV 服务器，兼容 S3 的存储，Google Drive、OneDrive 和 Dropbox。可用的服务因平台而异；目前 Google Drive 在 Apple 设备上提供。每个连接都直接从你的设备到你选择的服务，不经过任何由我们运营的服务器。

- **登录**：Google Drive、OneDrive 和 Dropbox 通过 OAuth 2.0（使用 PKCE）在服务商自己的页面上让你登录，因此 Edendale 永远看不到你的密码。服务器登录信息（用户名、密码和访问密钥）只会发送给对应的服务器。
- **只读访问**：Edendale 只申请只读权限。Google：`openid`、`email` 和 `drive.readonly`。Microsoft：`Files.Read`、`User.Read` 和 `offline_access`。Dropbox：`account_info.read`、`files.metadata.read` 和 `files.content.read`。Edendale 无法在你的存储中创建、更改、共享或删除任何内容。
- **Edendale 读取的内容**：你账号的 ID 和电子邮件地址，用于标识账号并区分其来源；你浏览和关联的位置中文件和文件夹的名称、大小、日期和时长；以及仅在你播放时读取的视频内容。
- **Edendale 保留的内容**：文件信息会成为你设备上片库的一部分。登录令牌和登录信息保存在上文所述的受保护存储中。视频数据在播放时保存在内存中，从不写入磁盘。
- **电视**：只有在你于电视上发起传输并在手机或平板电脑上确认后，Apple TV 才能通过本地网络中的加密连接，从你的 iPhone 或 iPad 接收账号或登录信息。在电视上，OneDrive 也可以用一个你在其他设备上批准的代码登录。

## Google 用户数据

当你关联 Google Drive 时，Edendale 会访问：

- 你的 Google 账号的唯一 ID 和电子邮件地址（`openid` 和 `email`），用于显示已关联的账号并区分你的各个账号；以及
- 你 Google Drive 中的文件和文件夹（`drive.readonly`）：Edendale 会列出你浏览和关联的文件夹，读取其中文件的名称、大小、日期和视频时长，并串流你选择播放的视频。

Edendale 仅将这些数据用于提供 Google Drive 来源功能：选择文件夹、列出其中的视频并播放。与任何来源一样，Edendale 会在你的设备上读取文件名来识别电影和剧集，并且只把识别出的标题和年份发送给 TMDB 以查询详细信息。

这些数据留在你的设备上：文件信息保存在你的片库中；关联的账号（其 ID、电子邮件地址和登录令牌）保存在钥匙串中，并由 iCloud 钥匙串同步到你的其他 Apple 设备。只有当你在 iPhone 或 iPad 上确认传输时，它才会到达 Apple TV。Google 用户数据从不会发送给我们或任何由我们运营的服务器，因此我们从不会看到或读取它。它从不会被出售，从不会用于广告，也从不会用于开发、改进或训练人工智能或机器学习模型。

要终止 Edendale 的访问权限，请移除该来源（这也会从你的片库中移除其文件），并在**设置 → 帐户**中退出登录。**退出登录并撤销访问权限**还会在 Google 端撤销 Edendale 的访问权限。你也可以随时在 [Google 账号的第三方关联](https://myaccount.google.com/connections)中移除它。删除应用会删除它在该设备上保存的所有内容。

Edendale 对从 Google API 获取的信息的使用及向任何其他应用的传输，将遵守 [Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy)（Google API 服务用户数据政策），包括其中的有限使用（Limited Use）要求。

## Microsoft 和 Dropbox 帐户

OneDrive 和 Dropbox 的方式相同：只读访问，仅用于列出和播放你的视频，并且只保存在你的设备上。请在**设置 → 帐户**中退出登录。对于 Dropbox，**退出登录并撤销访问权限**还会在 Dropbox 端终止 Edendale 的访问权限。你也可以在[可访问你 Microsoft 帐户的应用](https://account.live.com/consent/Manage)或 [Dropbox 已关联的应用](https://www.dropbox.com/account/connected_apps)中移除 Edendale。工作或学校的 Microsoft 帐户可能由你所在的组织管理。

## 本网站

本网站是托管在 GitHub Pages 上的静态网站。它不设置 Cookie，不在你的浏览器中存储任何内容，没有表单，也不从其他网站加载分析工具、字体或脚本。它会根据浏览器设置选择语言，但不保存任何内容；你选择的语言只体现在页面地址中。作为托管方，GitHub 会收到常规的请求信息，例如你的 IP 地址；参见 [GitHub General Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement)。打开 Edendale 应用的链接在你的设备上处理。

## 儿童

Edendale 不会故意收集任何人的个人信息，包括儿童的。应用不会向我们发送任何内容，因此我们没有可收集的东西。

## 你的选择

你可以随时在应用中查看、更改或删除你的数据：移除来源、退出账号、关闭 iCloud 同步或 OneDrive 复制，或删除应用。由于我们不持有你的任何个人数据，这些控制就是你行使访问权和删除权的方式。上述服务所持有的数据受其各自的隐私政策约束。

## 本政策的变更

当应用处理数据的方式发生变化时，我们会更新本页面及顶部的日期。每次修订都会公开记录在 GitHub 上的项目历史中。

## 联系我们

如对本政策或 Edendale 的隐私保护有任何疑问，欢迎在 [github.com/Ju-Long/Edendale/issues](https://github.com/Ju-Long/Edendale/issues) 提交 issue。

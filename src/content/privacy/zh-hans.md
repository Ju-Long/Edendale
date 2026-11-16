---
updated: 2026-11-16
summary:
  - Edendale 没有账号系统，也没有自己的服务器。应用中没有分析统计、广告或跟踪。
  - 你的片库、设置、保存的登录信息和家长控制都留在你的设备上。需要同步的内容只经过你掌控的存储或服务，例如 iCloud，或你自己的 Google Drive、OneDrive、Dropbox 或服务器。
  - 设备间同步在你开启之前一直处于关闭状态。开启后，每台设备会把你的观看记录以可读文件的形式写入你选择的存储，登录信息只会在你自己的设备之间直接传递。
  - Edendale 由你的设备直接连接 Google Drive、OneDrive 和 Dropbox，仅用于列出和播放你的视频，以及维护它自己的同步文件。在 Google Drive 中，它只能看到你选择的视频和它自己创建的文件。
  - 影片信息、字幕搜索、跳过按钮和预告片只会连接下文列出的服务，且仅用于所述用途。
---

## 本政策的适用范围

本政策适用于 Apple 设备（iPhone、iPad、Mac、Apple TV 和 Apple Vision Pro）、Android（包括 Meta Quest 头显）和 Windows 上的 Edendale 应用，以及本网站 edendale.babasama.com。Edendale 是一个自由开源项目，在 [github.com/Ju-Long/Edendale](https://github.com/Ju-Long/Edendale) 上公开开发。“我们”指开发它的人。

## 我们不收集你的数据

Edendale 没有账号系统，也不运营任何接收应用信息的服务器。应用中不含分析统计、广告、跟踪或崩溃报告代码。你的信息从不会到达我们这里，所以我们没有任何可出售、出租或分享的东西。

如果你允许设备与应用开发者共享诊断数据，你安装 Edendale 所用的应用商店（Apple、Google、Meta 或 Microsoft）可能会依照其自身的隐私政策，向我们提供崩溃报告和汇总的使用统计。我们只用它们来修复问题。

## 留在你设备上的内容

- **你的片库**：你添加的文件夹和来源；Edendale 在其中找到的文件的名称、大小、日期和时长；以及每个文件所匹配的电影或剧集。
- **你的设置**：播放、音频、画面、字幕和操控的偏好设置，包括为每部作品记住的选择。
- **你下载的字幕**。
- **保存的登录信息和关联的账号**：服务器密码、S3 访问密钥、云端登录令牌（包括你同步位置的登录信息）、你的 TMDB 登录，以及你的字幕 API 密钥，保存在系统受保护的存储中：Apple 设备上的钥匙串、Android 上由 Android Keystore 加密的存储，以及 Windows 数据保护（DPAPI）。短期有效的访问令牌只保存在内存中。
- **家长控制**：“适合低龄观众”开关；在 Android 和 Meta Quest 上，还包括分级上限、其他家长控制开关，以及家长 PIN 码。PIN 码只以加盐的慢速哈希形式保存。Edendale 从不同步这些内容，从不把它们发送到其他设备，也从不发送到任何其他地方。你设备自身的备份可能会保留“适合低龄观众”开关，但绝不会保留 PIN 码或分级上限。
- **你的设备上显示的内容**：你正在观看的作品及其封面和进度，可能会显示在应用之外：Edendale 的小组件、Apple TV 的顶部栏、Windows 任务栏的跳转列表、播放时系统的媒体控制中；如果你开启了**在主屏幕上显示“继续观看”**，还会显示在 Android TV 的“继续观看”行中，电视的主屏幕应用可以读取这些内容。任何能看到你设备的人都能在那里看到它们。

Edendale 会先在你的设备上读取文件名来识别电影和剧集，然后才会连接任何在线服务；它从不上传你的视频。

## 哪些内容可以同步，经由何处

Edendale 只通过你掌控的服务进行同步，并且只在你开启后才会同步：

- **设备间同步（Edendale 27.1 及更高版本）**：你的观看记录，经由你选择的存储同步，详见下一节。
- **Apple 设备**：开启 iCloud 后，你的观看进度、评分、评论、收藏和待看列表会通过你的私有 iCloud 数据库同步。关联的账号、保存的登录信息、你的 TMDB 登录和字幕 API 密钥会通过 iCloud 钥匙串同步到你的 iPhone、iPad、Mac 和 Apple Vision Pro。Apple TV 保留自己的副本。与任何应用一样，你设备自身的备份也包含 Edendale 的数据。
- **Windows**：在 Edendale 27.1 之前，只要电脑上设置了 OneDrive，Edendale 就会通过你自己 OneDrive 中的 `Apps/Edendale` 文件夹复制你的观看进度和作品状态。登录信息和账号从未离开设备。Edendale 27.1 停止了这种复制：它会读取这些文件一次，将文件原样保留，并建议把该文件夹作为你的同步位置。在你同意之前，同步一直处于关闭状态。
- **Android**：Android 自带的备份可能包含你的片库（包括每个关联来源所属账号的电子邮件地址或名称）、你的观看数据、你的设置，以及“适合低龄观众”开关。登录信息、密钥、账号令牌、同步设置、家长 PIN 码以及其他家长控制不会包含在备份和设备间迁移中。
- **Meta Quest**：如果在头显设置中开启了云备份，Meta 的备份会使用 Android 的备份机制，排除的内容也相同。从头显中移除应用会删除它在头显上保存的所有内容。Meta 会保留其备份，并在你重新安装 Edendale 时恢复最新的备份，直到你在头显设置的云备份中将其删除；参见 [Meta 关于云备份的帮助](https://www.meta.com/help/quest/399621398228171/)。
- **你的 TMDB 账号（可选）**：如果你登录 The Movie Database，Edendale 会让你的收藏、待看列表和评分与该账号保持同步。观看进度从不会发送给 TMDB。

## 设备间同步

从 Edendale 27.1 起，**设置 → 同步**可以让你所有设备上的观看记录保持一致。在你开启并选择一个位置之前，同步一直处于关闭状态。Edendale 仍然没有服务器，也没有账号系统：你的设备只通过你已经拥有的存储来共享观看记录。

- **同步文件包含的内容**：每台设备写入一个文件 `Edendale/<空间名称>/<设备 ID>.json`，其中包括你的观看进度和是否看完、评分、评论、收藏和待看列表状态（均以作品的 TMDB ID 标识，并记录最后更改的时间和做出更改的设备）；空间名称；设备的名称和平台；为同步生成的随机 ID；以及 Edendale 的版本。它从不包含你的片库、文件名或路径、登录信息、你的 TMDB 会话或家长控制。
- **文件存放的位置**：存放在你选择并拥有的位置：你设备上的文件夹（包括 iCloud Drive，或其他云应用提供的文件夹）、Apple 设备上 Edendale 自己的 iCloud Drive 文件夹、你的 Google Drive、OneDrive（位于其应用文件夹 `Apps/Edendale` 中）、Dropbox，或你自己的 WebDAV、SMB 或 SFTP 服务器。每台设备从那里读取其他设备的文件，并在本设备上合并。这些文件是简单、可读的 JSON，因此任何能打开该文件夹或账号的人都能读取你的观看记录；你选择位置时，Edendale 会提示这一点。
- **如何停止**：在**设置 → 同步**中选择**关闭**，会停止该设备上的同步，并询问是否删除它的文件；设备上的观看记录会保留。在**设置 → 同步**中移除一台设备，会删除该设备的文件。清除某部作品的进度，或在 Android 上使用**删除观看记录**，会在该设备上将其清除；如果开启了同步，你的其他设备会在下次同步时一并清除。之后，同步文件会为每部已清除的作品保留一个不含进度的标记，最长一年，以免旧副本把记录带回来。要删除全部内容，请在每台设备上关闭同步，并从你的存储中删除 `Edendale` 文件夹。
- **设置其他设备**：**设置其他设备**会通过你的本地网络，把同步位置及其登录信息，以及你勾选的其他账号和登录信息，直接从你的一台设备发送到另一台设备。一台设备显示二维码，另一台设备扫描它或将其作为链接打开；如果两台设备都支持，也可以在另一台设备上输入显示的代码。两台设备用它协商出加密连接的密钥；发送设备会在发送任何内容之前请你确认对方设备的名称。任何内容都不经过我们或其他任何人。在 iPhone、iPad、Android 手机和平板电脑，以及 Meta Quest 3 和 3S 上，Edendale 可以用相机扫描代码：它只在你选择扫描时请求相机权限，在设备上读取代码，从不保存或发送相机的画面。配对界面打开期间，设备会在你的本地网络中公布自己的名称和平台，以便另一台设备找到它。在无法转移 Google 登录的平台之间，只会发送 Google 账号的电子邮件地址、空间名称和同步文件夹的 ID，然后由你在新设备上自行登录 Google。
- **你移除的设备**会保留它收到的登录信息和它已有的观看记录，直到你在存储服务商处撤销 Edendale 的访问权限，或更改服务器的密码。

## Edendale 使用的在线服务

下列每项服务都会像任何互联网连接一样收到你设备的 IP 地址，此外还会收到以下信息：

- **[TMDB](https://www.themoviedb.org/privacy-policy)**（The Movie Database），用于获取电影和剧集的信息、图片和年龄分级：Edendale 从文件名中读取的标题，有时还包括年份（从不发送完整文件名、所在文件夹或文件本身）；你在搜索中输入的内容；以及你浏览的作品或 Edendale 查询详细信息的作品的 TMDB ID。如果你已登录，还包括你的 TMDB 会话。
- **[Wyzie Subs](https://wyzie.io/privacy)**，仅在你在线搜索字幕时：作品的 TMDB ID、季数和集数、Edendale 请求的语言和字幕格式、是否包含听障字幕，以及一个 API 密钥：如果你输入了自己的密钥则为你的，否则为 Edendale 自己的。
- **[TheIntroDB](https://theintrodb.org/docs/privacy)**，仅在跳过按钮开启时（默认关闭）：作品的 TMDB ID、季数和集数，以及视频时长。
- **[YouTube](https://policies.google.com/privacy)**，仅在你选择播放预告片时。在 Apple TV 以外的 Apple 设备以及 Meta Quest 以外的 Android 设备上，Edendale 会以 YouTube 的隐私增强模式（youtube-nocookie.com）播放；在 Apple TV 上，它会在 YouTube 应用中打开预告片；在 Windows 上，它会在你的浏览器中打开 youtube.com 上的预告片；在 Meta Quest 上，则会在浏览器或 YouTube 应用中打开。
- **你关联的存储**，以及你为同步选择的存储，见下一节。

## 你关联的存储

Edendale 可以播放你设备上文件夹中的视频，以及你关联的存储中的视频：SMB、NFS、SFTP 和 WebDAV 服务器，兼容 S3 的存储，Google Drive、OneDrive 和 Dropbox。可用的服务因平台而异。每个连接都直接从你的设备到你选择的服务，不经过任何由我们运营的服务器。

- **登录**：Google Drive、OneDrive 和 Dropbox 通过 OAuth 2.0（使用 PKCE）在服务商自己的页面上让你登录，因此 Edendale 永远看不到你的密码。服务器登录信息（用户名、密码和访问密钥）只会发送给对应的服务器。
- **有限的访问**：Google 只获得 `drive.file`，其范围仅限你在 Google 自己的文件选择器中选择的视频和 Edendale 创建的文件，不包括你 Drive 中的任何其他内容。Microsoft：`Files.Read`、`Files.ReadWrite.AppFolder`、`User.Read` 和 `offline_access`。Dropbox：`account_info.read`、`files.metadata.read`、`files.content.read` 和 `files.content.write`。Edendale 只读取你的视频：它从不上传、更改、共享或删除这些视频，也不会对你存储中的其他任何内容这样做。它写入或删除的文件只有它自己的同步文件，而且仅在你开启同步时：位于 Google Drive、Dropbox 或你所选服务器或文件夹中它的 `Edendale` 文件夹里，以及 OneDrive 的应用文件夹里。
- **Edendale 读取的内容**：你账号的 ID、电子邮件地址和名称，用于标识账号并区分其来源；你浏览和关联的位置中文件和文件夹（对于 Google Drive，则是你选择的视频）的名称、大小、日期和时长；仅在你播放时读取的视频内容；以及在你开启同步时，你各台设备的同步文件。
- **Edendale 保留的内容**：文件信息会成为你设备上片库的一部分。登录令牌和登录信息保存在上文所述的受保护存储中。视频数据在播放时保存在内存中，从不写入磁盘。
- **电视**：Apple TV 可以从你的 iPhone 或 iPad，Android TV 可以从另一台 Android 设备上的 Edendale，通过本地网络中的加密连接接收账号或登录信息，但只有在你于电视上发起传输并在另一台设备上确认后才会进行。Android TV 在等待期间会在你的本地网络中公布自己的名称。在电视上，OneDrive 也可以用一个你在其他设备上批准的代码登录。从 Edendale 27.1 起，你的任何一台设备都可以用这种方式设置另一台设备，详见“设备间同步”。

## Google 用户数据

当你关联 Google Drive 时，你会在 Google 自己的文件选择器中选择视频；它会在你登录时打开，之后每次使用**选择更多视频**时也会打开。如果你登录 Google Drive 只是为了在其中保存同步文件，则不会打开文件选择器。Edendale 会访问：

- 你选择的视频，而不访问你 Google Drive 中的其他任何内容（`drive.file`）：Edendale 会列出这些视频，读取它们的名称、大小、日期和视频时长，并串流你选择播放的视频。Google 会在 Edendale 的各个应用之间共享你的选择，因此你在一台设备上选择的视频，也可能出现在登录同一 Google 账号的另一台设备上；
- 如果你选择 Google Drive 进行同步，则包括 `Edendale` 文件夹以及 Edendale 自己在其中创建的同步文件，它会按“设备间同步”中所述读取、替换和删除这些文件；以及
- Google Drive 以同一权限提供的 Google Drive 用户 ID、电子邮件地址和账号名称，用于显示已关联的账号并区分你的各个账号。

Edendale 仅将这些数据用于提供 Google Drive 来源功能（列出你选择的视频并播放），以及在你选择时于你的 Drive 中维护同步文件。它从不上传、更改、共享或删除你的视频或你 Drive 中的任何其他文件。与任何来源一样，Edendale 会在你的设备上读取文件名来识别电影和剧集，并且只把识别出的标题（有时还包括年份）发送给 TMDB 以查询详细信息。

这些数据留在你的设备上和你自己的 Drive 中：文件信息保存在你的片库中；关联的账号（其 ID、电子邮件地址、名称和登录令牌）保存在上文所述的受保护存储中。在 Apple 设备上，iCloud 钥匙串会把该账号同步到你的其他 Apple 设备。只有当你在自己的某台设备上确认传输时，它才会到达另一台设备；在无法转移 Google 登录的平台之间，只会发送电子邮件地址和同步文件夹的 ID。如果你从片库中移除某个视频，Edendale 可能会只在该设备上保留它的文件 ID，以免它再次出现在列表中。Google 用户数据从不会发送给我们或任何由我们运营的服务器，因此我们从不会看到或读取它。它从不会被出售，从不会用于广告，也从不会用于开发、改进或训练人工智能或机器学习模型。

要终止 Edendale 的访问权限，请移除该来源（这也会从你的片库中移除其文件），并在**设置 → 帐户**中退出登录。**退出登录并撤销访问权限**（在 Windows 上为**退出登录**）还会在 Google 端撤销 Edendale 的访问权限。你也可以随时在 [Google 账号的第三方关联](https://myaccount.google.com/connections)中移除它。撤销访问权限后，所有设备（包括电视）上的访问都会终止，Google 也会忘记你选择过的视频。Edendale 创建的同步文件会留在你的 Drive 中，直到你将其删除。删除应用会删除它在该设备上保存的所有内容。Android 或 Meta Quest 上的备份可能会保留你片库的副本，其中包括视频所属账号的电子邮件地址或名称，但绝不会保留该账号的登录令牌。

Edendale 对从 Google API 获取的信息的使用及向任何其他应用的传输，将遵守 [Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy)（Google API 服务用户数据政策），包括其中的有限使用（Limited Use）要求。

## Microsoft 和 Dropbox 帐户

OneDrive 和 Dropbox 的方式大致相同：对你浏览和关联的文件夹的读取访问仅用于列出和播放你的视频；如果你选择其中之一进行同步，写入访问也仅用于 Edendale 自己的同步文件，位于 OneDrive 的应用文件夹（`Apps/Edendale`）或你 Dropbox 中的 `Edendale` 文件夹。Edendale 从不更改或删除那里的其他任何内容。账号信息和登录令牌只保存在你的设备上。请在**设置 → 帐户**中退出登录。在 Apple 设备和 Android 上，**退出登录并撤销访问权限**还会在 Dropbox 端终止 Edendale 的访问权限。你也可以在[可访问你 Microsoft 帐户的应用](https://account.live.com/consent/Manage)或 [Dropbox 已关联的应用](https://www.dropbox.com/account/connected_apps)中移除 Edendale。同步文件会留在你的存储中，直到你将其删除。工作或学校的 Microsoft 帐户可能由你所在的组织管理。

## 本网站

本网站是托管在 GitHub Pages 上的静态网站。它不设置 Cookie，不在你的浏览器中存储任何内容，没有表单，也不从其他网站加载分析工具、字体或脚本。它会根据浏览器设置选择语言，但不保存任何内容；你选择的语言只体现在页面地址中。作为托管方，GitHub 会收到常规的请求信息，例如你的 IP 地址；参见 [GitHub General Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement)。打开 Edendale 应用的链接在你的设备上处理。

## 儿童

Edendale 不会故意收集任何人的个人信息，包括儿童的。应用不会向我们发送任何内容，因此我们没有可收集的东西。

## 你的选择

你可以随时在应用中查看、更改或删除你的数据：移除来源、退出账号、关闭设备间同步、iCloud 同步或设备备份、从你的存储中删除同步文件（在 Windows 上，还有以前的 OneDrive 副本），或删除应用。由于我们不持有你的任何个人数据，这些控制就是你行使访问权和删除权的方式。上述服务所持有的数据受其各自的隐私政策约束。

## 本政策的变更

当应用处理数据的方式发生变化时，我们会更新本页面及顶部的日期。每次修订都会公开记录在 GitHub 上的项目历史中。

## 联系我们

如对本政策或 Edendale 的隐私保护有任何疑问，欢迎在 [github.com/Ju-Long/Edendale/issues](https://github.com/Ju-Long/Edendale/issues) 提交 issue。

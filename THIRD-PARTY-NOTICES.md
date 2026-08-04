# Third-party notices

Edendale is licensed under the Mozilla Public License 2.0 (see [LICENSE](LICENSE)).
It redistributes the third-party components below. Each entry lists what ships
inside a build, not merely what the build depends on: tools used only at compile
time are excluded because they never reach a user's machine.

Every license text referenced here is included in the shipped output alongside
the binary it covers.

## Fonts

Both families ship as `.ttf` files in `Assets/Fonts/` and are embedded in every
build. Both are licensed under the SIL Open Font License, Version 1.1, whose
full text ships beside them as `Assets/Fonts/OFL.txt`. The copyright lines below
are reproduced exactly as they appear in each font binary's name table.

| Font | Copyright | License |
|---|---|---|
| Inter (`Inter-Variable.ttf`, `Inter-Italic-Variable.ttf`) | Copyright 2016 The Inter Project Authors (https://github.com/rsms/inter) | SIL OFL 1.1 |
| Bebas Neue (`BebasNeue-Regular.ttf`) | Copyright 2019 The Bebas Neue Project Authors (https://github.com/dharmatype/Bebas-Neue) | SIL OFL 1.1 |

## Runtime components

| Component | Version | Copyright | License |
|---|---|---|---|
| Microsoft Windows App SDK | 1.7.250401001 | © Microsoft Corporation. All rights reserved. | Microsoft Software License Terms (Windows App SDK) |
| QRCoder | 1.8.0 | Copyright © 2013-2025 Raffael Herrmann. Copyright © 2024-2025 Shane Krueger. All rights reserved. | MIT |
| System.Security.Cryptography.ProtectedData | 8.0.0 | © Microsoft Corporation. All rights reserved. | MIT |
| System.Text.Encoding.CodePages | 8.0.0 | © Microsoft Corporation. All rights reserved. | MIT |

The Windows App SDK is redistributed under proprietary Microsoft terms rather
than an open-source license, because `WindowsAppSDKSelfContained` bundles its
runtime into the application. Its terms are in the `Microsoft.WindowsAppSDK`
package as `license.txt`, and online at https://aka.ms/windowsappsdk/license.

`Microsoft.Windows.SDK.BuildTools` is intentionally absent: it supplies
compile-time tooling only and none of it is redistributed.

## MIT License

Applies to QRCoder, System.Security.Cryptography.ProtectedData, and
System.Text.Encoding.CodePages, with the copyright holders named above.

```
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Metadata service

Edendale enriches locally classified files with metadata from TMDB. This
product uses the TMDB API but is not endorsed or certified by TMDB.

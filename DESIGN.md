# Edendale Archive Design System

Edendale uses **Cinematic Minimalism**: an obsidian archive lit by Projection
Gold. Interfaces should feel precise, architectural, and content-first—never
glossy, playful, or crowded.

This file defines universal intent. Each platform branch implements it with
native resources, controls, navigation, accessibility, and input behavior. No
platform implementation is the executable dependency or sole source code
reference for another.

## Color

| Token | Value | Use |
|---|---:|---|
| Background | `#0A0A0F` | App floor and deep scrims |
| SurfaceLow | `#131318` | Sidebars and dim containers |
| Surface | `#1F1F25` | Cards and panels |
| SurfaceHigh | `#2A292F` | Elevated controls and thumbnails |
| Gold | `#F4BE5D` | Focus, active text, progress, and glow |
| GoldDeep | `#C9973A` | Solid primary actions and borders |
| OnGold | `#422C00` | Text on solid gold |
| TextPrimary | `#E4E1E9` | Primary copy; never pure white |
| TextSecondary | `#D3C4B1` | Metadata and body copy |
| Outline | `#4F4537` | Resting borders and rules |
| OutlineBright | `#9C8F7D` | Hover and focus borders |
| GoldGlow | Gold at 35% | Focus glow |
| HairlineBorder | White at 6% | One-pixel translucent edges |
| HeatLow / HeatMid | `#4A3A1D` / `#8A672B` | Release heatmap ramp |

Use semantic tokens through the active platform's resource system. Do not
derive new production colors from arbitrary literals, runtime brightness, or
untracked opacity changes; add a named token when a new role is needed.

## Typography

- Bebas Neue is the theatrical display face for hero titles, page titles,
  shelf headings, and panel headings.
- Inter is the functional face for navigation, metadata, controls, labels, and
  body copy.
- Reference scale: display XL 96, headline LG 64, headline MD 32, title LG 20
  semibold, body LG 16, body SM 14, and label caps 12 bold.
- Adapt the scale to platform text settings and viewport constraints without
  losing the hierarchy.
- Use uppercase display copy and labels sparingly. Label caps use 1.2-point
  tracking where supported.

## Shape and depth

- Soft control radius: 4.
- Card radius: 8.
- Glass or panel radius: 12.
- Prefer hairline edges, layered dark surfaces, restrained blur, and a quiet
  gold glow.
- Reserve large pill-shaped containers for chips, status badges, and segmented
  selections.

## Layout and components

- Use native platform navigation while preserving the same information
  hierarchy.
- Marketing surfaces use a focused layout: navigation, product story, platform
  availability, privacy promises, and clear source or download actions.
- Heroes use a full-bleed backdrop, strong left and bottom scrims, a prominent
  title, compact metadata, and clear Play, Watch Trailer, and Details actions.
- Shelves scroll on their natural axis; posters are 2:3 and landscape items are
  16:9. Hover or focus raises a card with a restrained gold spotlight.
- Details retain the cinematic hero, archive record, cast shelf, private
  rating/review, and consensus rail.
- Empty states use one strong archival illustration, a short theatrical
  heading, supporting copy, and no more than three actions.
- The player is black-first and full-screen. Chrome hides after five seconds
  when appropriate, with the title above, transport centered, timeline below,
  and secondary controls in a trailing panel.

## Motion and interaction

- Motion communicates selection and continuity through short fades, a 1–5%
  card lift, and an optional subtle ten-second hero push.
- Honor system reduced-motion settings and any in-app reduced-motion
  preference.
- Keyboard, pointer, touch, remote, focus, and assistive-technology states must
  expose equivalent actions when available on the platform.
- Gold indicates the current or primary state; it is not decoration.
- Trailer playback never starts automatically.

## Imagery and icons

- TMDB artwork is the primary imagery inside native applications. Scrims keep
  copy readable when artwork is bright or missing.
- Use a consistent, legally distributable icon family suited to the active
  platform.
- Missing artwork uses the dark archival placeholder, not a generic
  broken-image treatment.

## Product behavior

- Local libraries store device-specific paths and access grants on the device.
- Portable watch state, when supported, is stored separately and syncs only
  through a documented user-controlled service.
- Filename classification is local and precedes metadata enrichment.
- Network work must not block the initial import experience.
- Web remains a static information and verified-link surface; it stores no
  library, watch state, ratings, reviews, preferences, credentials, or media
  files.
- A Watch Trailer action may open a privacy-enhanced provider only after
  explicit user intent.

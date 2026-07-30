/**
 * The single source of truth for the languages the Edendale website ships.
 *
 * `astro.config.ts` imports this file, so it must never import from `astro:*`
 * virtual modules — those do not exist yet while the configuration loads.
 *
 * To add a language: append an entry here, add its dictionary in `ui.ts`, and
 * run `npm run check`. TypeScript fails the build until the new dictionary is
 * complete.
 */

export interface LocaleDefinition {
  /**
   * The URL segment and the Astro locale id. Lowercase and hyphenated so it is
   * unambiguous in a path; `getRelativeLocaleUrl` uses it verbatim.
   */
  readonly path: string;
  /** BCP 47 tag for `<html lang>`, `hreflang`, and `Intl` formatting. */
  readonly lang: string;
  /** Writing direction for `<html dir>`. */
  readonly dir: "ltr" | "rtl";
  /** The language named in itself — what the language picker shows. */
  readonly nativeName: string;
  /** The language named in English, for the picker's English `lang` label. */
  readonly englishName: string;
  /** Open Graph locale, which wants `language_TERRITORY`. */
  readonly ogLocale: string;
  /**
   * Whether headings need the CJK typography treatment: Bebas Neue has no CJK
   * glyphs, and uppercasing plus wide tracking is wrong for those scripts.
   */
  readonly typeface: "latin" | "cjk";
  /**
   * Normalized (lowercase, hyphenated) BCP 47 tags this locale claims when
   * matching a visitor's `navigator.languages`.
   *
   * Matching truncates a visitor's tag from the right, so `en` already covers
   * `en-GB` and `zh` already covers `zh-CN`. Only list a tag when it needs an
   * explicit mapping or when the locale claims a broader tag than its own —
   * `pt-BR` claiming plain `pt`, for example.
   */
  readonly claims: readonly string[];
}

export const LOCALES = [
  {
    path: "en",
    lang: "en",
    dir: "ltr",
    nativeName: "English",
    englishName: "English",
    ogLocale: "en_US",
    typeface: "latin",
    claims: ["en"],
  },
  {
    path: "es",
    lang: "es",
    dir: "ltr",
    nativeName: "Español",
    englishName: "Spanish",
    ogLocale: "es_ES",
    typeface: "latin",
    claims: ["es"],
  },
  {
    path: "fr",
    lang: "fr",
    dir: "ltr",
    nativeName: "Français",
    englishName: "French",
    ogLocale: "fr_FR",
    typeface: "latin",
    claims: ["fr"],
  },
  {
    path: "de",
    lang: "de",
    dir: "ltr",
    nativeName: "Deutsch",
    englishName: "German",
    ogLocale: "de_DE",
    typeface: "latin",
    claims: ["de"],
  },
  {
    path: "pt-br",
    lang: "pt-BR",
    dir: "ltr",
    nativeName: "Português (Brasil)",
    englishName: "Portuguese (Brazil)",
    ogLocale: "pt_BR",
    typeface: "latin",
    // Brazilian Portuguese is the only Portuguese shipped, so it also answers
    // for plain `pt` and, by truncation, for `pt-PT`.
    claims: ["pt-br", "pt"],
  },
  {
    path: "ja",
    lang: "ja",
    dir: "ltr",
    nativeName: "日本語",
    englishName: "Japanese",
    ogLocale: "ja_JP",
    typeface: "cjk",
    claims: ["ja"],
  },
  {
    path: "ko",
    lang: "ko",
    dir: "ltr",
    nativeName: "한국어",
    englishName: "Korean",
    ogLocale: "ko_KR",
    typeface: "cjk",
    claims: ["ko"],
  },
  {
    path: "zh-hans",
    lang: "zh-Hans",
    dir: "ltr",
    nativeName: "简体中文",
    englishName: "Chinese (Simplified)",
    ogLocale: "zh_CN",
    typeface: "cjk",
    // Claiming bare `zh` means a Traditional reader gets Simplified rather than
    // English. Add a `zh-hant` locale before that stops being the kinder answer.
    claims: ["zh-hans", "zh"],
  },
] as const satisfies readonly LocaleDefinition[];

export type LocalePath = (typeof LOCALES)[number]["path"];

export const DEFAULT_LOCALE = "en" satisfies LocalePath;

/** The locale ids Astro's `i18n.locales` needs, in picker order. */
export const LOCALE_PATHS = LOCALES.map((locale) => locale.path);

export function isLocalePath(value: string): value is LocalePath {
  return LOCALE_PATHS.includes(value as LocalePath);
}

export function getLocale(path: LocalePath): LocaleDefinition {
  // `LOCALES` is exhaustive over `LocalePath`, so this always resolves.
  return LOCALES.find((locale) => locale.path === path) ?? LOCALES[0];
}

/**
 * Resolves the best supported locale for an ordered list of visitor language
 * tags, the way RFC 4647 lookup does: try each tag, then progressively drop its
 * trailing subtags, before moving on to the visitor's next preference.
 *
 * Shared by the build and by the inline browser script, so it stays dependency
 * free and returns a `LocalePath` rather than reading any global state.
 */
export function matchLocale(preferred: readonly string[]): LocalePath {
  for (const preference of preferred) {
    let tag = preference.toLowerCase().replaceAll("_", "-");
    while (tag.length > 0) {
      for (const locale of LOCALES) {
        if ((locale.claims as readonly string[]).includes(tag)) {
          return locale.path;
        }
      }
      const lastSeparator = tag.lastIndexOf("-");
      if (lastSeparator < 0) break;
      tag = tag.slice(0, lastSeparator);
    }
  }
  return DEFAULT_LOCALE;
}

import { getAbsoluteLocaleUrl, getRelativeLocaleUrl } from "astro:i18n";

import {
  DEFAULT_LOCALE,
  getLocale,
  isLocalePath,
  LOCALES,
  type LocaleDefinition,
  type LocalePath,
} from "./locales.ts";
import { dictionaries, type UIKey } from "./ui.ts";

export function useTranslations(locale: LocalePath) {
  const dictionary = dictionaries[locale];
  const fallback = dictionaries[DEFAULT_LOCALE];
  return function t(key: UIKey): string {
    return dictionary[key] || fallback[key];
  };
}

/** Splits an authored `\n` so a heading can break where its language allows. */
export function lines(value: string): string[] {
  return value.split("\n");
}

/**
 * Reads the locale out of a pathname. `Astro.currentLocale` already does this
 * for localized routes; this exists for the app-link pages, which stay on
 * unprefixed URLs and therefore have no locale of their own.
 */
export function localeFromPathname(pathname: string): LocalePath {
  const segments = pathname.replace(import.meta.env.BASE_URL, "").split("/");
  const first = segments.find((segment) => segment.length > 0) ?? "";
  return isLocalePath(first) ? first : DEFAULT_LOCALE;
}

/**
 * The canonical home URL for a locale — `/` for English, `/<locale>/` for the
 * rest, matching apple.com's `/` and `/jp/`.
 */
export function homeUrl(locale: LocalePath): string {
  return getRelativeLocaleUrl(locale);
}

/**
 * The URL the language picker points at. Unlike {@link homeUrl}, English
 * resolves to the explicit `/en/` alias rather than `/`.
 *
 * `/` is the automatic entry point: it redirects to whatever language the
 * browser asks for. A visitor who deliberately chooses English needs somewhere
 * that will not bounce them back, and since DESIGN.md forbids this site from
 * storing preferences, that "somewhere" has to be the URL itself.
 */
export function pickerUrl(locale: LocalePath, path?: string): string {
  return locale === DEFAULT_LOCALE
    ? `${getRelativeLocaleUrl(DEFAULT_LOCALE)}${DEFAULT_LOCALE}/`
    : getRelativeLocaleUrl(locale, path);
}

export interface AlternateLink {
  readonly hreflang: string;
  readonly href: string;
}

/**
 * `hreflang` annotations for every language plus `x-default`, so search engines
 * serve the right one instead of guessing from the visitor's location.
 */
export function alternateLinks(path?: string): AlternateLink[] {
  const alternates: AlternateLink[] = LOCALES.map((locale) => ({
    hreflang: locale.lang,
    href: getAbsoluteLocaleUrl(locale.path, path),
  }));
  alternates.push({
    hreflang: "x-default",
    href: getAbsoluteLocaleUrl(DEFAULT_LOCALE, path),
  });
  return alternates;
}

/**
 * The locale table handed to the inline browser script: everything needed to
 * match `navigator.languages` and to switch pages, and nothing else.
 */
export interface ClientLocale {
  readonly path: LocalePath;
  readonly lang: string;
  readonly dir: LocaleDefinition["dir"];
  readonly typeface: LocaleDefinition["typeface"];
  readonly nativeName: string;
  readonly claims: readonly string[];
  /** The canonical page for this locale — English resolves to `/`. */
  readonly homeHref: string;
  /** The picker's target for this locale — English resolves to `/en/`. */
  readonly pickerHref: string;
}

export function clientLocales(path?: string): ClientLocale[] {
  return LOCALES.map((locale) => ({
    path: locale.path,
    lang: locale.lang,
    dir: locale.dir,
    typeface: locale.typeface,
    nativeName: locale.nativeName,
    claims: locale.claims,
    homeHref: getRelativeLocaleUrl(locale.path, path),
    pickerHref: pickerUrl(locale.path, path),
  }));
}

/**
 * Every language's copy of a chosen set of keys, for the pages that must live
 * on a single URL and therefore pick their language in the browser.
 */
export function clientStrings(
  keys: readonly UIKey[],
): Record<LocalePath, Record<string, string>> {
  const strings = {} as Record<LocalePath, Record<string, string>>;
  for (const locale of LOCALES) {
    const t = useTranslations(locale.path);
    strings[locale.path] = Object.fromEntries(keys.map((key) => [key, t(key)]));
  }
  return strings;
}

export { DEFAULT_LOCALE, getLocale, isLocalePath, LOCALES };
export type { LocaleDefinition, LocalePath, UIKey };

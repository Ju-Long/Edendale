/// <reference types="astro/client" />

import type { ClientLocale } from "./i18n/utils.ts";

declare global {
  interface Window {
    /**
     * The language `SiteLayout`'s inline head script resolved from
     * `navigator.languages`. Present only on pages that pick their language in
     * the browser or that redirect to it.
     */
    __edendaleLocale?: ClientLocale;
    /** That language's strings, on pages localized in the browser. */
    __edendaleStrings?: Record<string, string>;
  }
}

export {};

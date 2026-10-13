// SPDX-License-Identifier: GPL-3.0-only
// Interoperability declaration from Offline Translator; see docs/offline-translator-contract.md.
package dev.davidv.translator;

import dev.davidv.translator.TranslationError;

oneway interface ITranslationCallback {
    void onTranslationResult(String translatedText);
    void onTranslationError(in TranslationError error);
}

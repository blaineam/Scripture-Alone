package com.blainemiller.scripturealone.data.rights

import com.blainemiller.scripturealone.data.sabible.PackagePolicy

/**
 * A signed package's own grant, as `PackagePolicy.rights()` in Swift maps it. Throws when `expires`
 * is unreadable: an expiry the app cannot parse must stop the package, never become "no expiry".
 */
fun PackagePolicy.rights(): TranslationRights = TranslationRights(
    allowCopy = allowCopy,
    allowShare = allowShare,
    allowVerseImages = allowVerseImages,
    allowNotesExport = allowNotesExport,
    allowExternalHandoff = allowExternalHandoff,
    allowOfflineStorage = allowOfflineStorage,
    maxQuotationVerses = maxQuotationVerses,
    expires = expiryInstant(),
)

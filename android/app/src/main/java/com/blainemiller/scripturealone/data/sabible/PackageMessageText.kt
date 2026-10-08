package com.blainemiller.scripturealone.data.sabible

import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.text.AppText

/**
 * The phone's localized sentences for [TranslationPackageException], installed into the shared package
 * reader at startup ([PackageMessages.lookup]) so a reader is told why in their own language.
 */
object PackageMessageText {
    fun install() {
        PackageMessages.lookup = { message, args -> AppText.get(resource(message), *args) }
    }

    private fun resource(message: PackageMessage): Int = when (message) {
        PackageMessage.NOT_A_PACKAGE -> R.string.data_package_not_a_package
        PackageMessage.NOT_FOR_WEARABLES -> R.string.data_package_not_for_wearables
        PackageMessage.UNSUPPORTED_VERSION -> R.string.data_package_unsupported_version
        PackageMessage.DAMAGED_HEADER -> R.string.data_package_damaged_header
        PackageMessage.UNREADABLE -> R.string.data_package_unreadable
        PackageMessage.UNKNOWN_PUBLISHER_KEY -> R.string.data_package_unknown_publisher_key
        PackageMessage.SIGNATURE_INVALID -> R.string.data_package_signature_invalid
        PackageMessage.EXPIRED -> R.string.data_package_expired
        PackageMessage.WRONG_CONTENT_KEY -> R.string.data_package_wrong_content_key
        PackageMessage.CHAPTER_MISSING -> R.string.data_package_chapter_missing
        PackageMessage.CHAPTER_TAMPERED -> R.string.data_package_chapter_tampered
        PackageMessage.TRUNCATED -> R.string.data_package_truncated
        PackageMessage.NOT_SEARCHABLE -> R.string.data_package_not_searchable
        PackageMessage.DAMAGED_INDEX -> R.string.data_package_damaged_index
        PackageMessage.BUCKET_TAMPERED -> R.string.data_package_bucket_tampered
    }
}

# Nothing reflective yet. Add rules here as dependencies need them.

# PDFBox's Android port (PDF import reads text only): JPEG 2000 decoding is an optional module it
# references but doesn't ship, and nothing here decodes images.
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.gemalto.jp2.JP2Encoder

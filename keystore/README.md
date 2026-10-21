# LibreHU signing key

The CI signs the APK with the key shared by every LibreHU app (alias `librehu`, passwords `librehu`), so that
updates install over each other (LibreHU OTA) and keep the app's data.

Put `librehu.jks` here (the same file as in the other LibreHU app repositories, for example
`LibreHU-EasySource-app/keystore/librehu.jks`), or add the repository secret `LIBREHU_KEYSTORE_B64` (base64 of the
keystore; `LIBREHU_KEY_ALIAS`, `LIBREHU_KEYSTORE_PASSWORD`, `LIBREHU_KEY_PASSWORD` if they differ). Without one of
the two the build fails at signing.

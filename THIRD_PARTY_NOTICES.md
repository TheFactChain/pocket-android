# Происхождение и лицензии

Pocket Android основан на Shelter Peter Cai (PeterCxy) и участниках проекта:
https://github.com/PeterCxy/Shelter

Исходная точка: 0afac87b2dc34e58652d6bb3cebb61b1baaf1b98.
Сохраняются LICENSE и уведомления исходного проекта. Авторы Shelter не заявляются
авторами или службой поддержки изменений Pocket.

SetupWizardLibrary закреплена на ae5ee4b4a0fb380c99e05f53b753cc86b212f1dd:
https://gitea.angry.im/PeterCxy/SetupWizardLibrary
Её исходные уведомления находятся в libs/SetupWizardLibrary.

AndroidX и Material Components используются с исходными лицензиями;
зависимости закреплены в Gradle. JUnit и Espresso относятся к тестам.
JDK, SDK, NDK и компилятор Go — инструменты сборки; стандартная библиотека и
runtime Go, а также binding runtime gomobile включены в нативную библиотеку.

В Pocket интегрируется локально собранный libbox из официального sing-box
v1.14.4, commit `c005f35300ce76427dc285481ec5695f5b179cd2`:
https://github.com/SagerNet/sing-box/tree/c005f35300ce76427dc285481ec5695f5b179cd2

Авторское уведомление sing-box: Copyright (C) 2022 by nekohasekai.
Движок распространяется по GPL-3.0-or-later. Оригинальный текст, включая условие
об использовании имени и ассоциации с исходным приложением, сохранён без изменений
в [docs/licenses/sing-box/LICENSE](docs/licenses/sing-box/LICENSE).
Полный текст GPL находится в [LICENSE](LICENSE) и
[docs/licenses/GPL-3.0.txt](docs/licenses/GPL-3.0.txt).

Собранный AAR содержит ARM64 и ARMv7, gVisor, QUIC/Hysteria 2 и uTLS/REALITY.
Отключение build tags дополнительных протоколов не исключает все их общие
зависимости: реальные ELF также содержат код модулей Tailscale/WireGuard.
Точный состав 93 Go-модулей, версии, Go sums, ссылки на исходники и пути лицензий
сохранены в [docs/licenses/libbox-dependencies.json](docs/licenses/libbox-dependencies.json).
Он извлечён из обеих скомпилированных `.so`, а не только из `go.mod`.
Лицензии зависимостей сохранены в `docs/licenses/go-modules/`, Go 1.25.14 —
в `docs/licenses/go-runtime/`. Исходное авторство этих компонентов сохраняется.

Для сканирования QR используется JourneyApps ZXing Android Embedded 4.3.0
(Ralf Kistner / Journey Mobile, Inc), исходники:
https://github.com/journeyapps/zxing-android-embedded/tree/v4.3.0
и транзитивный ZXing Core 3.4.1 (ZXing authors):
https://github.com/zxing/zxing/tree/zxing-3.4.1
Сохранены оригинальные [JourneyApps COPYING](docs/licenses/qr/journeyapps-4.3.0-COPYING),
[ZXing LICENSE](docs/licenses/qr/zxing-3.4.1-LICENSE) и
[ZXing NOTICE](docs/licenses/qr/zxing-3.4.1-NOTICE).
Оба проекта используют Apache-2.0; файл ZXing LICENSE также сохраняет
дополнительные исходные уведомления.

Порядок получения исходников и границы собранных уведомлений описаны в
[docs/licenses/README.md](docs/licenses/README.md). Эти документы сами по себе
не означают, что окончательный release APK уже проверен или опубликован.

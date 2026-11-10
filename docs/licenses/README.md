# Лицензии и исходники встроенных компонентов

`libbox-dependencies.json` привязан к SHA256 конкретного `app/libs/libbox.aar`.
Оба ELF имеют одинаковые 93 Go-зависимости; исходные выводы `go version -m`
сохранены в `libbox-*-build-info.txt`. Версии и `h1` суммы относятся к реальной
скомпилированной библиотеке. Они не являются перечнем всех функций приложения.
Новый AAR включает Pocket patch `tools/patches/libbox-no-config-snapshots.patch`:
удалён вызов сохранения plaintext-конфигурации, копирование конфигурации в
crash/OOM/power-отчёты отключено. Base commit сохранён; путь и SHA256 патча
указаны в `sourcePatches` JSON. Патч и `tools/patches/README.md` должны входить
в исходный комплект вместе с неизменёнными исходными copyright/LICENSE.

`Collect-Libbox-Licenses.ps1` читает готовый AAR, локальные исходники sing-box,
Go и модульный кеш. Он ничего не загружает и не пересобирает. Для каждого
включённого модуля копируются LICENSE/LICENCE, COPYING, NOTICE, COPYRIGHT,
AUTHORS и PATENTS, в том числе во вложенных каталогах. Ни один из 93 модулей
не остался без найденного текста лицензии. Хешированные имена каталогов
`go-modules/` сопоставлены с именами/версиями в JSON. Вложенные лицензии
сохранены консервативно: их наличие не утверждает, что каждый файл модуля попал
в бинарник. Лицензии sing-box также включают исходные уведомления его подкаталогов.

Локальные Go-модули не изменялись. Для получения их полных исходников используй
`sourceZip` и `sourceMod` из JSON; копии ZIP также находятся в локальном
`.tools/go-mod/cache/download/<escaped-module>/@v/<version>.zip`.
Экранирование заглавных букв Go (`!lowercase`) сохранено в URL. Для sing-box
указан архив точного commit; для стандартной библиотеки — Go 1.25.14 source tarball.
Локальная сборка описана в `tools/Build-Libbox.ps1` и `tools/toolchain.lock.json`.

QR-зависимости подтверждены локальными Maven POM: JourneyApps 4.3.0 зависит от
ZXing Core 3.4.1. Их лицензии отсутствовали в кешированных AAR/JAR и загружены
без изменения текста из официальных тегов:

- https://raw.githubusercontent.com/journeyapps/zxing-android-embedded/v4.3.0/COPYING
- https://raw.githubusercontent.com/zxing/zxing/zxing-3.4.1/LICENSE
- https://raw.githubusercontent.com/zxing/zxing/zxing-3.4.1/NOTICE

Исходники QR: https://github.com/journeyapps/zxing-android-embedded/archive/refs/tags/v4.3.0.tar.gz
и https://github.com/zxing/zxing/archive/refs/tags/zxing-3.4.1.tar.gz.

При передаче бинарной сборки документация с уведомлениями должна сопровождать
её. Для исходного комплекта Pocket сохраняются исходники Shelter/SetupWizard,
изменения Pocket, точные исходники встроенного libbox и его зависимостей,
скрипты/настройки сборки и эти лицензии. JSON со ссылками — индекс для подготовки
такого комплекта, а не готовый архив Corresponding Source и не письменное
предложение предоставить исходники. Публичная публикация исходного комплекта
или APK этим изменением не выполнялась. Проверка полного release classpath
AndroidX/Material и упаковка уведомлений внутрь APK остаются у владельца интеграции.

# Проверка нативных библиотек

`tools/Verify-Native.ps1 -Apk <release.apk>` проверяет наличие `libbox.so` для
обеих ARM ABI, ELF class/machine, все LOAD segments (alignment >=16384 для ARM64,
>=4096 для ARMv7 и совместимость offset/virtual address с размером страницы ABI),
затем запускает SDK `zipalign -c -P 16 -v 4`.
Проверяются все `.so` APK; посторонние ABI отклоняются. Сжатые native ZIP entries
отмечаются как непроверенное прямое mmap-выравнивание и отклоняются этой строгой
проверкой. Результат — JSON в stdout и код 0/1. APK не меняется; временные ELF удаляются.

`native-old-apk-check.json` относится к старому APK с SHA256 из отчёта: в нём
отсутствуют обе libbox.so, поэтому результат отрицательный.
`native-aar-container-check.json` получен для синтетического ZIP-контейнера из
готового AAR, выровненного `zipalign`; это не APK и не проверка установки.
ARM64 ELF имеет LOAD alignment 16384, ARMv7 — 4096; оба соответствуют требованиям
своей ABI. В официальной [документации NDK](https://android.googlesource.com/platform/ndk/+/a931ae9831d03373e1b122d76574255e5562adf0/docs/BuildSystemMaintainers.md)
16-КиБ страницы относятся к arm64-v8a/x86_64; для 32-битных ABI изменение
размера страницы не планируется. Отчёт сохраняет фактический alignment и отдельно
`aligned16KiB`, даже когда для ARMv7 он не требуется. ZIP alignment контейнера
также проходит. Это не подтверждает совместимость окончательного APK с 16-КиБ устройством.
Проверка финального APK и реальный запуск выполняются отдельно; AAR здесь не пересобирался.
